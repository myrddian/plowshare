package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns proposal-to-memory scope resolution; a provenance label never becomes caller identity. */
@Repository
public final class JdbcPromotionSourceRepository implements PromotionSourceRepository {
  private final JdbcTemplate jdbc;

  public JdbcPromotionSourceRepository(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  @Override
  public Optional<Source> source(String proposal) {
    if (proposal == null || proposal.isBlank() || proposal.length() > 1024)
      throw new IllegalArgumentException("Proposal identity required");
    return jdbc
        .query(
            "SELECT m.home_scope_id,p.name,information_memory_safe(m.id) FROM proposals q JOIN memories m ON m.id=q.memory_id JOIN authority_scopes s ON s.id=m.home_scope_id JOIN projects p ON p.id=s.project_id WHERE q.id=? AND s.kind IN ('PROJECT','PERSONAL')",
            (row, index) ->
                new Source(
                    new ScopeId(row.getObject(1, UUID.class)), row.getString(2), row.getBoolean(3)),
            proposal)
        .stream()
        .findFirst();
  }
}
