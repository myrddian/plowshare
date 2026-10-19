package io.aeyer.plowshare.server.access;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns SYSTEM purpose evidence, without granting or manufacturing executor authority. */
@Repository
public final class JdbcSystemOperationRecords implements SystemOperationRecords {
  private final JdbcTemplate jdbc;

  public JdbcSystemOperationRecords(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  public void retain(Envelope e) {
    jdbc.update(
        "INSERT INTO system_operations(id,purpose,scope_id,grant_id,agent,model,binding_hash) VALUES(?,?,?,?,?,?,?)",
        e.id(),
        e.purpose().name(),
        e.scope().value(),
        e.grant(),
        e.agent(),
        e.model(),
        e.bindingHash());
  }

  public void require(Envelope e) {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM system_operations WHERE id=? AND purpose=? AND scope_id=? AND grant_id=? AND agent=? AND model=? AND binding_hash=?)",
            Boolean.class,
            e.id(),
            e.purpose().name(),
            e.scope().value(),
            e.grant(),
            e.agent(),
            e.model(),
            e.bindingHash()))) throw new IllegalStateException("Missing retained SYSTEM purpose");
  }
}
