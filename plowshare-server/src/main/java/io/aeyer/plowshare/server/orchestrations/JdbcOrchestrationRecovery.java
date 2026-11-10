package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.protocol.Orchestration;
import io.aeyer.plowshare.protocol.Orchestration.CheckpointRepair;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.orchestrations.scripted.ScriptProgram;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Resume receipts and state changes commit together under a request lock and row CAS. */
public final class JdbcOrchestrationRecovery implements OrchestrationRecovery {
  private static final String RESET =
      """
      state='running', failure=NULL, ended_at=NULL, result_delivered_at=NULL, pending_cap=NULL,
      waiting_for=NULL, stalled_since=NULL, nudges=0, restarts=0
      """;
  private static final String FAILED_ROOT =
      " WHERE id=? AND caller_handle=? AND state='failed' AND parent IS NULL AND ended_at=?\n";

  private final JdbcTemplate jdbc;
  private final UnitOfWork work;

  public JdbcOrchestrationRecovery(JdbcTemplate jdbc, UnitOfWork work) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.work = Objects.requireNonNull(work);
  }

  private record Receipt(String run, CheckpointRepair repair) {}

  @Override
  public boolean received(String id, String account, UUID requestId) {
    return received(id, account, requestId, null);
  }

  @Override
  public boolean received(String id, String account, UUID requestId, CheckpointRepair repair) {
    new Orchestration.Resume(id, requestId);
    Objects.requireNonNull(account, "account");
    var prior =
        jdbc.query(
            """
            SELECT run_id, checkpoint_sequence, previous_script_hash, replacement_script_hash
            FROM orchestration_resume_receipts WHERE account=? AND request_id=?
            """,
            (row, n) -> {
              Integer sequence = row.getObject("checkpoint_sequence", Integer.class);
              return new Receipt(
                  row.getString("run_id"),
                  sequence == null
                      ? null
                      : new CheckpointRepair(
                          sequence,
                          row.getString("previous_script_hash"),
                          row.getString("replacement_script_hash")));
            },
            account,
            requestId);
    if (prior.isEmpty()) return false;
    var receipt = prior.getFirst();
    if (!id.equals(receipt.run()) || !Objects.equals(repair, receipt.repair()))
      throw new CallerFault("requestId was already used for a different orchestration resume");
    return true;
  }

  @Override
  public boolean claim(String id, String account, UUID requestId, Instant failureAt) {
    return claim(id, account, requestId, failureAt, null, null, null);
  }

  @Override
  public boolean claimRepair(
      String id,
      String account,
      UUID requestId,
      Instant failureAt,
      CheckpointRepair repair,
      String previousSource,
      String replacementSource) {
    Objects.requireNonNull(repair, "repair");
    if (!repair.sourceHash().equals(ScriptProgram.sourceHash(previousSource))
        || !repair.replacementHash().equals(ScriptProgram.sourceHash(replacementSource)))
      throw new IllegalArgumentException("repair script hashes differ from their source");
    return claim(id, account, requestId, failureAt, repair, previousSource, replacementSource);
  }

  private boolean claim(
      String id,
      String account,
      UUID requestId,
      Instant failureAt,
      CheckpointRepair repair,
      String previousSource,
      String replacementSource) {
    new Orchestration.Resume(id, requestId);
    Objects.requireNonNull(account, "account");
    var ended = OffsetDateTime.ofInstant(Objects.requireNonNull(failureAt), ZoneOffset.UTC);
    return work.inTransaction(
        () -> {
          jdbc.queryForObject(
              "SELECT pg_advisory_xact_lock(hashtextextended(?,0)) IS NULL",
              Boolean.class,
              account + ":orchestration.resume:" + requestId);
          if (received(id, account, requestId, repair)) return false;
          if (repair == null) {
            if (jdbc.update("UPDATE orchestrations SET " + RESET + FAILED_ROOT, id, account, ended)
                != 1)
              throw new CallerFault(
                  "Only a failed root orchestration can be resumed; refresh its state or resume its root.");
            jdbc.update(
                "INSERT INTO orchestration_resume_receipts(account,request_id,run_id) VALUES(?,?,?)",
                account,
                requestId,
                id);
            return true;
          }

          // The failed root, source and latest completed checkpoint must still be the ones
          // previewed. A journal that has entered information writes uses normal receipt recovery.
          int changed =
              jdbc.update(
                  "UPDATE orchestrations SET definition_source=?, definition_hash=?, "
                      + RESET
                      + FAILED_ROOT
                      + """
                    AND definition_hash=?
                    AND EXISTS (
                      SELECT 1 FROM orchestration_script_steps s
                      WHERE s.conversation_id=orchestrations.conductor_conversation
                        AND s.sequence=? AND s.source_hash=orchestrations.definition_hash
                        AND s.started_at IS NOT NULL AND s.executed_at IS NOT NULL
                        AND s.completed_at IS NOT NULL AND s.raw_result IS NOT NULL AND s.result IS NOT NULL
                        AND s.command->>'tool'='agent_run'
                    )
                    AND NOT EXISTS (
                      SELECT 1 FROM orchestration_script_steps s
                      WHERE s.conversation_id=orchestrations.conductor_conversation
                        AND (s.sequence>? OR s.command->>'tool'='information_write')
                    )
                    AND NOT EXISTS (
                      SELECT 1 FROM orchestration_resume_receipts r
                      WHERE r.run_id=orchestrations.id AND r.checkpoint_sequence IS NOT NULL
                    )
                    """,
                  replacementSource,
                  repair.replacementHash(),
                  id,
                  account,
                  ended,
                  repair.sourceHash(),
                  repair.sequence(),
                  repair.sequence());
          if (changed != 1)
            throw new CallerFault(
                "The failed script checkpoint changed or is ineligible for repair.");
          jdbc.update(
              """
              INSERT INTO orchestration_resume_receipts(account,request_id,run_id,checkpoint_sequence,
                 previous_script_hash,replacement_script_hash,previous_script_source)
              VALUES(?,?,?,?,?,?,?)
              """,
              account,
              requestId,
              id,
              repair.sequence(),
              repair.sourceHash(),
              repair.replacementHash(),
              previousSource);
          return true;
        });
  }
}
