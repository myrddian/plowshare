package io.aeyer.plowshare.server.approvals;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.agents.BudgetCheckpoint;
import io.aeyer.plowshare.server.allowances.AllowanceId;
import io.aeyer.plowshare.server.allowances.AllowanceSnapshot;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Locks the canonical allowance before creating or settling a request. Approval and its additive
 * grant are one transaction; a crash or repeated answer cannot spend the grant twice. Checkpoints
 * retain original conversation identities and exact pending tool calls, never a rewritten task.
 */
public final class JdbcBudgetRequestRepository implements BudgetRequestRepository {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SELECT =
      """
      SELECT b.approval, b.allowance_id, b.checkpoint::text, b.acknowledged_at,
        b.recovery, a.conversation, a.budget_calls, a.budget_steps, a.state, a.answered_by,
        e.call_limit, e.spent
      FROM budget_extension_requests b JOIN run_approvals a ON a.id=b.approval
      JOIN execution_allowances e ON e.id=b.allowance_id
      """;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public JdbcBudgetRequestRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.transactions = Objects.requireNonNull(transactions);
  }

  @Override
  public Request ask(
      long project,
      BudgetCheckpoint checkpoint,
      AllowanceId allowance,
      BudgetExtension extension,
      String reason) {
    String root = checkpoint.frames().getFirst().conversation();
    BudgetCheckpoint.Frame leaf = checkpoint.frames().getLast();
    String retained = encode(checkpoint);
    if (reason == null || reason.isBlank() || reason.length() > 4000 || reason.indexOf('\0') >= 0)
      throw new IllegalArgumentException("A bounded reason is required");
    return Objects.requireNonNull(
        transactions.execute(
            status -> {
              lockSource(root, project, checkpoint.executor(), allowance);
              List<Request> existing =
                  jdbc.query(
                      SELECT + " WHERE b.allowance_id=? AND b.acknowledged_at IS NULL",
                      JdbcBudgetRequestRepository::read,
                      allowance.value());
              if (!existing.isEmpty()) {
                Request request = existing.getFirst();
                if (!request.root().equals(root))
                  throw new CallerFault("This allowance already has a pending budget request");
                return request;
              }
              String id = "apr_" + UUID.randomUUID().toString().replace("-", "");
              jdbc.update(
                  """
          INSERT INTO run_approvals(id,project_id,conversation,asked_in,handle,agent,side,argv,
            cwd,reason,state,created_at,budget_calls,budget_steps)
          VALUES(?,?,?,?,?,?,'server','[]'::jsonb,'',?,'asked',clock_timestamp(),?,?)
          """,
                  id,
                  project,
                  root,
                  leaf.conversation(),
                  checkpoint.executor(),
                  leaf.agent(),
                  reason,
                  extension.calls(),
                  extension.steps());
              jdbc.update(
                  """
          INSERT INTO budget_extension_requests(approval,allowance_id,checkpoint)
          VALUES(?,?,?::jsonb)
          """,
                  id,
                  allowance.value(),
                  retained);
              jdbc.update(
                  "UPDATE message_routes SET awaiting=TRUE WHERE allowance_id=? AND handled_at IS NULL AND started_at IS NOT NULL",
                  allowance.value());
              return find(id).orElseThrow();
            }));
  }

  private void lockSource(String root, long project, String executor, AllowanceId allowance) {
    List<String> found =
        jdbc.query(
            """
        SELECT c.id FROM conversations c JOIN execution_allowances e ON e.id=?
        WHERE c.id=? AND c.project_id=? AND c.owner_handle=? AND c.lifecycle='active'
          AND e.call_limit IS DISTINCT FROM 0
          AND (c.allowance_id=e.id OR EXISTS(
            SELECT 1 FROM message_routes r JOIN message_instances i ON i.id=r.recipient
            WHERE i.conversation=c.id AND i.active AND r.allowance_id=e.id AND r.handled_at IS NULL
              AND (r.deadline_at IS NULL OR r.deadline_at>clock_timestamp())))
        FOR UPDATE OF e,c
        """,
            (row, number) -> row.getString(1),
            allowance.value(),
            root,
            project,
            executor);
    if (found.isEmpty())
      throw new CallerFault("The original execution or allowance is no longer available");
  }

  @Override
  public Optional<Request> find(String approval) {
    return jdbc
        .query(SELECT + " WHERE b.approval=?", JdbcBudgetRequestRepository::read, approval)
        .stream()
        .findFirst();
  }

  @Override
  public void requireExecutable(Request request) {
    transactions.executeWithoutResult(
        status -> {
          Long project =
              jdbc.queryForObject(
                  "SELECT project_id FROM run_approvals WHERE id=?",
                  Long.class,
                  request.approval());
          lockSource(
              request.root(),
              Objects.requireNonNull(project),
              request.checkpoint().executor(),
              request.allowance().id());
        });
  }

  @Override
  public boolean awaiting(String root) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS(SELECT 1 FROM budget_extension_requests b JOIN run_approvals a ON a.id=b.approval
          WHERE a.conversation=? AND b.acknowledged_at IS NULL)
        """,
            Boolean.class,
            root));
  }

  @Override
  public Request answer(String approval, boolean allow, String by, String reason) {
    if (by == null || by.isBlank() || by.length() > 1024 || by.indexOf('\0') >= 0)
      throw new IllegalArgumentException("An answering identity is required");
    if (reason != null
        && (allow || reason.isBlank() || reason.length() > 4000 || reason.indexOf('\0') >= 0))
      throw new IllegalArgumentException("A bounded denial reason is required");
    return Objects.requireNonNull(
        transactions.execute(
            status -> {
              Request request =
                  jdbc
                      .query(
                          SELECT + " WHERE b.approval=? FOR UPDATE OF b,a,e",
                          JdbcBudgetRequestRepository::read,
                          approval)
                      .stream()
                      .findFirst()
                      .orElseThrow(
                          () -> new CallerFault("No retained budget request has that approval"));
              if (!RunApproval.ASKED.equals(request.state())) {
                boolean granted =
                    RunApproval.ALLOWED.equals(request.state())
                        || RunApproval.USED.equals(request.state());
                if (allow != granted)
                  throw new CallerFault("This budget approval was already answered differently");
                return request;
              }
              Long project =
                  jdbc.queryForObject(
                      "SELECT project_id FROM run_approvals WHERE id=?", Long.class, approval);
              if (allow)
                lockSource(
                    request.root(),
                    Objects.requireNonNull(project),
                    request.checkpoint().executor(),
                    request.allowance().id());
              if (allow
                  && request.extension().calls() > 0
                  && request.allowance().ceiling() != null) {
                int next;
                try {
                  next = Math.addExact(request.allowance().ceiling(), request.extension().calls());
                } catch (ArithmeticException overflow) {
                  throw new CallerFault(
                      "The requested call extension exceeds the supported allowance");
                }
                jdbc.update(
                    "UPDATE execution_allowances SET call_limit=? WHERE id=?",
                    next,
                    request.allowance().id().value());
              }
              jdbc.update(
                  """
          UPDATE run_approvals SET state=?,scope=?,answered_by=?,answered_at=clock_timestamp(),denial_reason=?
          WHERE id=? AND state='asked'
          """,
                  allow ? RunApproval.ALLOWED : RunApproval.DENIED,
                  allow ? RunApproval.ONCE : null,
                  by,
                  reason,
                  approval);
              return find(approval).orElseThrow();
            }));
  }

  @Override
  public void cancel(String approval) {
    jdbc.update(
        """
        UPDATE run_approvals SET state='denied',scope=NULL,answered_by=?,answered_at=clock_timestamp()
        WHERE id=? AND state='asked'
        """,
        RunApproval.RUN_ENDED,
        approval);
  }

  @Override
  public void acknowledged(String approval) {
    transactions.executeWithoutResult(
        status -> {
          jdbc.update(
              "UPDATE budget_extension_requests SET acknowledged_at=clock_timestamp() WHERE approval=? AND acknowledged_at IS NULL",
              approval);
          jdbc.update(
              "UPDATE run_approvals SET state='used' WHERE id=? AND state='allowed'", approval);
        });
  }

  @Override
  public List<Request> recoverable() {
    return jdbc.query(
        SELECT
            + " WHERE b.acknowledged_at IS NULL AND a.state IN ('allowed','denied') ORDER BY b.created_at LIMIT 100",
        JdbcBudgetRequestRepository::read);
  }

  @Override
  public boolean queueRecovery(String approval) {
    return jdbc.update(
            "UPDATE budget_extension_requests SET recovery='queued' WHERE approval=? AND recovery='pending' AND acknowledged_at IS NULL",
            approval)
        == 1;
  }

  @Override
  public void recoveryStarted(String approval) {
    jdbc.update(
        "UPDATE budget_extension_requests SET recovery='started' WHERE approval=? AND acknowledged_at IS NULL",
        approval);
  }

  @Override
  public void recoveryDeferred(String approval) {
    jdbc.update(
        "UPDATE budget_extension_requests SET recovery='pending' WHERE approval=? AND recovery='queued' AND acknowledged_at IS NULL",
        approval);
  }

  private static String encode(BudgetCheckpoint checkpoint) {
    try {
      String text = JSON.writeValueAsString(checkpoint);
      if (text.length() > 1048576)
        throw new IllegalArgumentException("Budget checkpoint is too large");
      return text;
    } catch (JsonProcessingException failed) {
      throw new IllegalStateException("Cannot retain budget checkpoint", failed);
    }
  }

  private static Request read(ResultSet row, int number) throws SQLException {
    BudgetCheckpoint checkpoint;
    try {
      checkpoint = JSON.readValue(row.getString("checkpoint"), BudgetCheckpoint.class);
    } catch (JsonProcessingException corrupt) {
      throw new IllegalStateException("Invalid retained budget checkpoint", corrupt);
    }
    return new Request(
        row.getString("approval"),
        row.getString("conversation"),
        checkpoint,
        new BudgetExtension(row.getInt("budget_calls"), row.getInt("budget_steps")),
        row.getString("state"),
        row.getString("answered_by"),
        new AllowanceSnapshot(
            new AllowanceId(row.getObject("allowance_id", UUID.class)),
            row.getObject("call_limit", Integer.class),
            row.getInt("spent")),
        row.getTimestamp("acknowledged_at") != null,
        row.getString("recovery"));
  }
}
