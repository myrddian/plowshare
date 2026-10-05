package io.aeyer.plowshare.server.embedding;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns registry SQL. Read-committed conflict resolution never mutates an existing descriptor. */
@Repository
public class JdbcEmbeddingSpaceRepository implements EmbeddingSpaceRepository {
  private final JdbcTemplate jdbc;

  public JdbcEmbeddingSpaceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public EmbeddingSpace register(EmbeddingSpace.Definition definition) {
    Objects.requireNonNull(definition, "embedding space definition");
    Object[] parameters = {
      definition.modelId(), definition.modelRevision(), definition.dimensions(),
      definition.queryPrefix(), definition.documentPrefix(), definition.pooling(),
      definition.normalization().stored(), definition.reduction()
    };
    var inserted =
        jdbc.query(
            """
        INSERT INTO embedding_spaces(model_id, model_revision, dimensions, query_prefix,
          document_prefix, pooling, normalization, reduction) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT DO NOTHING RETURNING *
        """,
            JdbcEmbeddingSpaceRepository::read,
            parameters);
    if (!inserted.isEmpty()) return inserted.getFirst();
    // A fresh statement sees a competing committed registration under READ COMMITTED.
    // Higher isolation may require the caller to retry its transaction after a conflict.
    var existing =
        jdbc.query(
            """
        SELECT * FROM embedding_spaces WHERE model_id=? AND model_revision=? AND dimensions=?
          AND query_prefix=? AND document_prefix=? AND pooling=? AND normalization=? AND reduction=?
        """,
            JdbcEmbeddingSpaceRepository::read,
            parameters);
    if (existing.size() != 1) {
      throw new IllegalStateException(
          "embedding registration did not resolve to its exact descriptor");
    }
    return existing.getFirst();
  }

  @Override
  public Optional<EmbeddingSpace> find(String id) {
    EmbeddingSpace.requireId(id);
    return jdbc
        .query("SELECT * FROM embedding_spaces WHERE id=?", JdbcEmbeddingSpaceRepository::read, id)
        .stream()
        .findFirst();
  }

  private static EmbeddingSpace read(ResultSet row, int ignored) throws SQLException {
    return new EmbeddingSpace(
        row.getString("id"),
        new EmbeddingSpace.Definition(
            row.getString("model_id"),
            row.getString("model_revision"),
            row.getInt("dimensions"),
            row.getString("query_prefix"),
            row.getString("document_prefix"),
            row.getString("pooling"),
            EmbeddingSpace.Normalization.fromStored(row.getString("normalization")),
            row.getString("reduction")));
  }
}
