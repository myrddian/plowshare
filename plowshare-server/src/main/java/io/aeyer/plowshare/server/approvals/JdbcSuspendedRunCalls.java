package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.protocol.ToolCall;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL execution fence and retained results for approved run calls. */
public final class JdbcSuspendedRunCalls implements SuspendedRunCalls {
  private final JdbcTemplate jdbc;

  public JdbcSuspendedRunCalls(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  @Override
  public void retain(
      String approval,
      String conversation,
      String session,
      String side,
      String owner,
      ToolCall tool) {
    if (!"run".equals(tool.name())) throw new IllegalArgumentException("Only run is suspended");
    int inserted =
        jdbc.update(
            "INSERT INTO suspended_run_calls (approval, conversation, session_id, side, owner_handle, call_id, arguments)"
                + " SELECT id, ?, ?, ?, ?, ?, ? FROM run_approvals WHERE id = ? AND asked_in = ? AND side = ?",
            conversation,
            session,
            side,
            owner,
            tool.id(),
            tool.arguments(),
            approval,
            conversation,
            side);
    if (inserted != 1) throw new IllegalStateException("Approval does not own the suspended call");
  }

  @Override
  public List<Call> pending(String conversation) {
    return read(
        "c.conversation = ? AND c.acknowledged_at IS NULL ORDER BY c.sequence", conversation);
  }

  @Override
  public Optional<Call> find(String approval) {
    return read("c.approval = ?", approval).stream().findFirst();
  }

  @Override
  public List<Call> recoverable() {
    return read("c.acknowledged_at IS NULL AND a.state <> 'asked' ORDER BY c.sequence", null);
  }

  private List<Call> read(String predicate, String value) {
    String sql =
        "SELECT c.*, a.state AS decision, a.answered_by, a.denial_reason"
            + " FROM suspended_run_calls c JOIN run_approvals a ON a.id = c.approval"
            + " WHERE "
            + predicate;
    org.springframework.jdbc.core.RowMapper<Call> mapper =
        (row, n) ->
            new Call(
                row.getString("approval"),
                row.getString("conversation"),
                row.getString("session_id"),
                row.getString("side"),
                new ToolCall(row.getString("call_id"), "run", row.getString("arguments")),
                row.getString("state"),
                row.getString("decision"),
                row.getString("answered_by"),
                row.getString("denial_reason"),
                row.getString("result"),
                row.getString("owner_handle"),
                row.getBoolean("released"));
    return value == null ? jdbc.query(sql, mapper) : jdbc.query(sql, mapper, value);
  }

  @Override
  public boolean claim(String approval) {
    return jdbc.update(
            "UPDATE suspended_run_calls c SET state = 'started' WHERE approval = ?"
                + " AND state = 'waiting' AND released AND EXISTS (SELECT 1 FROM run_approvals a"
                + " WHERE a.id = c.approval AND a.state = 'allowed')",
            approval)
        == 1;
  }

  @Override
  public void release(String approval) {
    jdbc.update(
        "UPDATE suspended_run_calls c SET released = TRUE WHERE approval = ?"
            + " AND EXISTS (SELECT 1 FROM run_approvals a WHERE a.id = c.approval AND a.state <> 'asked')",
        approval);
  }

  @Override
  public void complete(String approval, String result) {
    if (result == null || result.isBlank())
      throw new IllegalArgumentException("A result is required");
    if (jdbc.update(
            "UPDATE suspended_run_calls SET state = 'completed', result = ?"
                + " WHERE approval = ? AND state IN ('waiting', 'started')",
            result,
            approval)
        != 1) throw new IllegalStateException("Suspended call is already completed");
  }

  @Override
  public void acknowledged(String approval) {
    if (jdbc.update(
            "UPDATE suspended_run_calls SET acknowledged_at = CURRENT_TIMESTAMP"
                + " WHERE approval = ? AND state = 'completed' AND acknowledged_at IS NULL",
            approval)
        != 1) throw new IllegalStateException("Suspended result is not awaiting acknowledgement");
  }
}
