package io.aeyer.plowshare.server.images;

import io.aeyer.plowshare.protocol.ImageAdmissions;
import io.aeyer.plowshare.server.blobs.BlobClaim;
import io.aeyer.plowshare.server.blobs.BlobKey;
import io.aeyer.plowshare.server.blobs.BlobRepository;
import io.aeyer.plowshare.server.blobs.BlobUnavailableException;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.tools.RelayToolRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Caller owns the short unit of work. Invocation row locking serializes admission with settlement.
 */
public final class JdbcArtifactRepository implements ArtifactRepository {
  private final JdbcTemplate jdbc;
  private final BlobRepository blobs;

  public JdbcArtifactRepository(JdbcTemplate jdbc, BlobRepository blobs) {
    this.jdbc = jdbc;
    this.blobs = java.util.Objects.requireNonNull(blobs);
  }

  @Override
  public void validateResult(
      RelayToolRepository.Intent intent, io.aeyer.plowshare.protocol.ToolContent.Body content) {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive())
      throw new IllegalStateException("result validation requires the invocation transaction");
    var images = content.images();
    if (images.isEmpty()) return;
    // A single bounded join checks exact admission slots, aliases, lengths and retained claims.
    // Invocation locking excludes settlement/deletion; active claims exclude collection. Disk
    // integrity remains checked when bytes are resolved, rather than holding SQL across file I/O.
    var parameters =
        new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("invocation", intent.id())
            .addValue("scope", "projects/" + intent.projectId())
            .addValue(
                "slots",
                images.stream()
                    .map(io.aeyer.plowshare.protocol.ToolContent.Part::ordinal)
                    .toList());
    record Image(int ordinal, String id) {}
    var retained =
        new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc)
            .query(
                """
        SELECT a.ordinal,a.image_id FROM invocation_image_admissions a
        JOIN binary_blob_aliases x ON x.scope=:scope AND x.alias=a.image_id AND x.sha256=a.sha256
        JOIN binary_blobs b ON b.scope=x.scope AND b.sha256=a.sha256
          AND b.byte_length=a.byte_length AND b.state='READY'
        JOIN binary_blob_claims c ON c.scope=b.scope AND c.sha256=b.sha256
          AND c.kind='invocation-admission' AND c.owner=CAST(:invocation AS text)
          AND c.slot=a.ordinal AND c.retention='RELEASABLE'
        WHERE a.invocation_id=:invocation AND a.ordinal IN (:slots)
        """,
                parameters,
                (row, ordinal) -> new Image(row.getInt(1), row.getString(2)));
    if (retained.size() != images.size()
        || images.stream()
            .anyMatch(part -> !retained.contains(new Image(part.ordinal(), part.imageId()))))
      throw new CallerFault("Tool result image is not retained by this invocation");
  }

  @Override
  public Optional<ImageAdmissions.Receipt> find(ImageAdmissions.Request request) {
    return jdbc
        .query(
            """
        SELECT a.*,i.account,i.binding::jsonb->>'project' AS project,
          i.binding::jsonb->>'account' AS producer
        FROM invocation_image_admissions a JOIN relay_tool_invocations i ON i.id=a.invocation_id
        WHERE a.invocation_id=? AND a.ordinal=? AND a.request_id=?
        """,
            (row, n) ->
                new ImageAdmissions.Receipt(
                    request,
                    row.getString("project"),
                    row.getString("account"),
                    row.getString("producer"),
                    row.getString("image_id"),
                    row.getString("mime_type"),
                    row.getInt("width"),
                    row.getInt("height"),
                    row.getLong("byte_length"),
                    row.getString("sha256"),
                    row.getTimestamp("admitted_at").toInstant().toString()),
            request.invocation(),
            request.ordinal(),
            request.requestId())
        .stream()
        .findFirst();
  }

  @Override
  public ImageAdmissions.Receipt commit(
      RelayToolRepository.Intent intent, ImageAdmissions.Receipt receipt) {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive())
      throw new IllegalStateException("artifact receipt commit requires a transaction");
    if (!intent.id().equals(receipt.request().invocation())
        || !intent.binding().project().equals(receipt.project())
        || !intent.request().account().equals(receipt.owner())
        || !intent.binding().account().equals(receipt.producer()))
      throw new IllegalArgumentException("foreign artifact receipt");
    var invocation =
        jdbc
            .query(
                "SELECT result IS NOT NULL AS settled FROM relay_tool_invocations WHERE id=? AND project_id=? AND account=? AND fingerprint=? FOR UPDATE",
                (row, n) -> row.getBoolean("settled"),
                intent.id(),
                intent.projectId(),
                receipt.owner(),
                intent.fingerprint())
            .stream()
            .findFirst()
            .orElseThrow(() -> new CallerFault("artifact invocation is unavailable"));
    var existing = find(receipt.request());
    if (existing.isPresent()) {
      InvocationArtifactAdmissions.requireSame(existing.get(), receipt);
      claim(intent, receipt);
      return existing.get();
    }
    if (invocation) throw new CallerFault("invocation has already settled");
    if (jdbc.update(
            """
        INSERT INTO invocation_image_admissions(invocation_id,ordinal,request_id,image_id,sha256,width,height,byte_length,admitted_at,mime_type)
        VALUES(?,?,?,?,?,?,?,?,?,?)
        """,
            intent.id(),
            receipt.request().ordinal(),
            receipt.request().requestId(),
            receipt.imageId(),
            receipt.sha256(),
            receipt.width(),
            receipt.height(),
            receipt.byteLength(),
            Timestamp.from(Instant.parse(receipt.admittedAt())),
            receipt.mimeType())
        != 1) throw new IllegalStateException("artifact receipt was not committed");
    claim(intent, receipt);
    return receipt;
  }

  private void claim(RelayToolRepository.Intent intent, ImageAdmissions.Receipt receipt) {
    BlobKey key = new BlobKey("projects/" + intent.projectId(), receipt.sha256());
    var binding =
        blobs.bound(key.scope(), receipt.imageId()).orElseThrow(BlobUnavailableException::new);
    if (!binding.source().key().equals(key)
        || binding.source().byteLength() != receipt.byteLength()) {
      throw new BlobUnavailableException();
    }
    blobs.acquire(
        key,
        new BlobClaim(
            "invocation-admission",
            intent.id().toString(),
            receipt.request().ordinal(),
            BlobClaim.Retention.RELEASABLE));
  }
}
