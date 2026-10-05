package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.protocol.Orchestration;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Resume receipts and state changes commit together under a request lock and row CAS. */
public final class JdbcOrchestrationRecovery implements OrchestrationRecovery {
  private final JdbcTemplate jdbc;
  private final UnitOfWork work;

  public JdbcOrchestrationRecovery(JdbcTemplate jdbc, UnitOfWork work) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.work = Objects.requireNonNull(work);
  }

  @Override
  public boolean received(String id, String account, UUID requestId) {
    new Orchestration.Resume(id, requestId);
    Objects.requireNonNull(account, "account");
    var prior =
        jdbc.queryForList(
            "SELECT run_id FROM orchestration_resume_receipts WHERE account=? AND request_id=?",
            String.class,
            account,
            requestId);
    if (prior.isEmpty()) return false;
    if (!id.equals(prior.getFirst()))
      throw new CallerFault("requestId was already used for a different orchestration resume");
    return true;
  }

  @Override
  public boolean claim(String id, String account, UUID requestId, Instant failureAt) {
    new Orchestration.Resume(id, requestId);
    Objects.requireNonNull(account, "account");
    Objects.requireNonNull(failureAt, "failureAt");
    return work.inTransaction(
        () -> {
          jdbc.queryForObject(
              "SELECT pg_advisory_xact_lock(hashtextextended(?,0)) IS NULL",
              Boolean.class,
              account + ":orchestration.resume:" + requestId);
          if (received(id, account, requestId)) return false;
          // Resume a root only. Stopping a tree cancels descendants; reviving one below a terminal
          // parent would give it no durable recipient and would not repair the parent.
          int changed =
              jdbc.update(
                  "UPDATE orchestrations SET state='running', failure=NULL, ended_at=NULL,"
                      + " result_delivered_at=NULL, pending_cap=NULL, waiting_for=NULL, stalled_since=NULL,"
                      + " nudges=0, restarts=0 WHERE id=? AND caller_handle=? AND state='failed'"
                      + " AND parent IS NULL AND ended_at=?",
                  id,
                  account,
                  OffsetDateTime.ofInstant(failureAt, ZoneOffset.UTC));
          if (changed != 1)
            throw new CallerFault(
                "Only a failed root orchestration can be resumed; refresh its state or resume its root.");
          jdbc.update(
              "INSERT INTO orchestration_resume_receipts(account,request_id,run_id) VALUES(?,?,?)",
              account,
              requestId,
              id);
          return true;
        });
  }
}
