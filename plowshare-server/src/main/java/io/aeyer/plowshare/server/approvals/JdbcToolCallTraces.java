package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.server.pool.SqlWorkBudget;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads and writes one bounded metadata record per retained call. */
public final class JdbcToolCallTraces implements ToolCallTraces {
  private final JdbcTemplate jdbc;
  private final SqlWorkBudget sql;
  private final Duration deadline;

  public JdbcToolCallTraces(JdbcTemplate jdbc, SqlWorkBudget sql, Duration deadline) {
    this.jdbc = jdbc;
    this.sql = sql;
    this.deadline = deadline;
  }

  @Override
  public void observe(String invocation, ToolEffectReceipt.Kind observation) {
    sql.within(
        deadline,
        () ->
            jdbc.update(
                """
                INSERT INTO tool_call_trace (invocation_id, sent_at, reply_observed_at, reply_timed_out_at)
                SELECT invocation_id,
                    CASE WHEN ? THEN CURRENT_TIMESTAMP END,
                    CASE WHEN ? THEN CURRENT_TIMESTAMP END,
                    CASE WHEN ? THEN CURRENT_TIMESTAMP END
                FROM suspended_run_calls WHERE invocation_id = ? AND answer_ordinal IS NOT NULL
                ON CONFLICT (invocation_id) DO UPDATE SET
                    sent_at = COALESCE(tool_call_trace.sent_at, EXCLUDED.sent_at),
                    reply_observed_at = COALESCE(tool_call_trace.reply_observed_at, EXCLUDED.reply_observed_at),
                    reply_timed_out_at = COALESCE(tool_call_trace.reply_timed_out_at, EXCLUDED.reply_timed_out_at)
                """,
                observation == ToolEffectReceipt.Kind.FILE_SENT,
                switch (observation) {
                  case FILE_EFFECT_CONFIRMED,
                          FILE_EFFECT_VERIFIED,
                          FILE_EFFECT_UNCHANGED,
                          FILE_REPLY_UNVERIFIED ->
                      true;
                  default -> false;
                },
                observation == ToolEffectReceipt.Kind.FILE_REPLY_TIMEOUT,
                invocation));
  }

  @Override
  public Optional<Trace> read(String conversation, String invocation) {
    return sql.within(
        deadline,
        () ->
            jdbc
                .query(
                    """
                    SELECT t.*, c.effect_request_id FROM tool_call_trace t JOIN suspended_run_calls c USING (invocation_id)
                    WHERE t.invocation_id = ? AND c.conversation = ? AND c.answer_ordinal IS NOT NULL
                    """,
                    (row, n) ->
                        new Trace(
                            row.getString("invocation_id"),
                            instant(row, "first_observed_at"),
                            instant(row, "sent_at"),
                            instant(row, "reply_observed_at"),
                            instant(row, "reply_timed_out_at"),
                            row.getString("effect_request_id")),
                    invocation,
                    conversation)
                .stream()
                .findFirst());
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    var stamp = row.getObject(column, OffsetDateTime.class);
    return stamp == null ? null : stamp.toInstant();
  }
}
