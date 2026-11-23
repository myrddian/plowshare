package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.archive.FileReadWindowCodec;
import io.aeyer.plowshare.server.files.FileReadWindow;
import io.aeyer.plowshare.server.pool.SqlWorkBudget;
import io.aeyer.plowshare.server.reads.ReadFailedException;
import io.aeyer.plowshare.server.reads.ReadUnavailableException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** PostgreSQL execution fence and retained results for approved run calls. */
public final class JdbcSuspendedRunCalls
    implements SuspendedRunCalls, DirectFileCalls, TrajectoryToolReceipts {
  private static final Logger log = LoggerFactory.getLogger(JdbcSuspendedRunCalls.class);
  private final JdbcTemplate jdbc;
  private final SqlWorkBudget sql;
  private final Duration queryDeadline;
  private final ToolCallTraces traces;

  public JdbcSuspendedRunCalls(JdbcTemplate jdbc, SqlWorkBudget sql, Duration queryDeadline) {
    this(jdbc, sql, queryDeadline, new JdbcToolCallTraces(jdbc, sql, queryDeadline));
  }

  public JdbcSuspendedRunCalls(
      JdbcTemplate jdbc, SqlWorkBudget sql, Duration queryDeadline, ToolCallTraces traces) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.sql = Objects.requireNonNull(sql);
    this.queryDeadline = Objects.requireNonNull(queryDeadline);
    this.traces = Objects.requireNonNull(traces);
  }

  @Override
  public void retain(
      String approval,
      String conversation,
      String session,
      String side,
      String owner,
      ToolCall tool) {
    retainAt(approval, conversation, session, side, owner, tool, null);
  }

  @Override
  public void retainAt(
      String approval,
      String conversation,
      String session,
      String side,
      String owner,
      ToolCall tool,
      Integer answerOrdinal) {
    if (!SuspendedRunCalls.supported(tool.name()))
      throw new IllegalArgumentException("Unsupported retained approval tool");
    int inserted =
        jdbc.update(
            """
            INSERT INTO suspended_run_calls
                (invocation_id, approval, conversation, session_id, side, owner_handle, call_id, arguments, tool_name, answer_ordinal)
            SELECT id, id, ?, ?, ?, ?, ?, ?, ?, ? FROM run_approvals
            WHERE id = ? AND asked_in = ? AND side = ?
              AND (CAST(? AS INTEGER) IS NULL OR EXISTS (
                SELECT 1 FROM entries e CROSS JOIN LATERAL jsonb_array_elements(e.tool_calls) t
                WHERE e.conversation_id = asked_in AND e.ordinal = ? AND e.kind = 'answer'
                  AND t ->> 'id' = ? AND t ->> 'name' = ?))
            """,
            conversation,
            session,
            side,
            owner,
            tool.id(),
            tool.arguments(),
            tool.name(),
            answerOrdinal,
            approval,
            conversation,
            side,
            answerOrdinal,
            answerOrdinal,
            tool.id(),
            tool.name());
    if (inserted != 1) throw new IllegalStateException("Approval does not own the suspended call");
  }

  @Override
  public String claimDirect(String conversation, int answerOrdinal, ToolCall tool, String owner) {
    String invocation = "file_" + UUID.randomUUID();
    int inserted =
        sql.within(
            queryDeadline,
            () ->
                jdbc.update(
                    """
        INSERT INTO suspended_run_calls (invocation_id, conversation, answer_ordinal, call_id,
            tool_name, owner_handle, state, effect_observation)
        SELECT ?, e.conversation_id, e.ordinal, ?, ?, ?, 'started', 'FILE_DISPATCH_UNCERTAIN'
        FROM entries e
        WHERE e.conversation_id = ? AND e.ordinal = ? AND e.kind = 'answer'
          AND EXISTS (SELECT 1 FROM jsonb_array_elements(e.tool_calls) t
                      WHERE t ->> 'id' = ? AND t ->> 'name' = ? AND t ->> 'arguments' = ?)
        ON CONFLICT (conversation, answer_ordinal, call_id) WHERE answer_ordinal IS NOT NULL DO NOTHING
        """,
                    invocation,
                    tool.id(),
                    tool.name(),
                    owner,
                    conversation,
                    answerOrdinal,
                    tool.id(),
                    tool.name(),
                    tool.arguments()));
    if (inserted != 1)
      throw new IllegalStateException("The file call was not newly claimed. No mutation was sent.");
    return invocation;
  }

  @Override
  public List<DirectFileCalls.Pending> pendingDirect(String conversation, int stoppedTurn) {
    return sql.within(
        queryDeadline,
        () ->
            jdbc.query(
                """
        SELECT c.invocation_id, c.answer_ordinal, c.call_id, c.tool_name, c.result, c.effect_observation,
               c.effect_request_id, t ->> 'arguments' AS arguments
        FROM suspended_run_calls c
        JOIN entries e ON (e.conversation_id, e.ordinal) = (c.conversation, c.answer_ordinal)
        CROSS JOIN LATERAL jsonb_array_elements(e.tool_calls) t
        WHERE c.conversation = ? AND c.approval IS NULL AND c.acknowledged_at IS NULL
          AND (e.turn_ordinal = ? OR (e.turn_ordinal < ? AND EXISTS (
               SELECT 1 FROM turns prior
               WHERE prior.conversation_id = e.conversation_id AND prior.ordinal = e.turn_ordinal)))
          AND t ->> 'id' = c.call_id AND t ->> 'name' = c.tool_name
        ORDER BY c.sequence
        """,
                (row, n) ->
                    new DirectFileCalls.Pending(
                        row.getString("invocation_id"),
                        row.getInt("answer_ordinal"),
                        new ToolCall(
                            row.getString("call_id"),
                            row.getString("tool_name"),
                            row.getString("arguments")),
                        new ToolEffectReceipt(
                            ToolEffectReceipt.Kind.valueOf(row.getString("effect_observation")),
                            row.getString("effect_request_id")),
                        row.getString("result")),
                conversation,
                stoppedTurn,
                stoppedTurn));
  }

  @Override
  public List<Recorded> forPage(
      String conversation, List<Integer> answerOrdinals, List<Integer> resultOrdinals) {
    if (answerOrdinals.isEmpty() && resultOrdinals.isEmpty()) return List.of();
    return sql.within(
        queryDeadline,
        () ->
            new NamedParameterJdbcTemplate(jdbc)
                .query(
                    """
            WITH matched AS (
                SELECT invocation_id FROM suspended_run_calls
                WHERE conversation = :conversation AND answer_ordinal IN (:answers)
                UNION
                SELECT retained_invocation FROM entries
                WHERE conversation_id = :conversation AND ordinal IN (:results)
                  AND retained_invocation IS NOT NULL
            )
            SELECT c.invocation_id, c.answer_ordinal, c.call_id, c.effect_observation,
                   c.effect_request_id, r.ordinal AS result_ordinal
            FROM matched m JOIN suspended_run_calls c ON c.invocation_id = m.invocation_id
              LEFT JOIN entries r ON r.retained_invocation = c.invocation_id
            WHERE c.conversation = :conversation AND c.answer_ordinal IS NOT NULL
              AND c.effect_observation IS NOT NULL
            """,
                    new MapSqlParameterSource("conversation", conversation)
                        .addValue("answers", answerOrdinals.isEmpty() ? List.of(0) : answerOrdinals)
                        .addValue(
                            "results", resultOrdinals.isEmpty() ? List.of(0) : resultOrdinals),
                    (row, n) ->
                        new Recorded(
                            row.getString("invocation_id"),
                            row.getInt("answer_ordinal"),
                            row.getString("call_id"),
                            row.getObject("result_ordinal", Integer.class),
                            new ToolEffectReceipt(
                                ToolEffectReceipt.Kind.valueOf(row.getString("effect_observation")),
                                row.getString("effect_request_id")))));
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
        (row, n) -> {
          String observation = row.getString("effect_observation");
          var receipt =
              observation == null
                  ? null
                  : new ToolEffectReceipt(
                      ToolEffectReceipt.Kind.valueOf(observation),
                      row.getString("effect_request_id"));
          return new Call(
              row.getString("approval"),
              row.getString("conversation"),
              row.getString("session_id"),
              row.getString("side"),
              new ToolCall(
                  row.getString("call_id"), row.getString("tool_name"), row.getString("arguments")),
              row.getString("state"),
              row.getString("decision"),
              row.getString("answered_by"),
              row.getString("denial_reason"),
              row.getString("result"),
              row.getString("owner_handle"),
              row.getBoolean("released"),
              receipt,
              FileReadWindowCodec.read(row.getString("file_window")));
        };
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
  public void recordEffect(String approval, ToolEffectReceipt receipt) {
    String observation = receipt.kind().name();
    String requestId = receipt.providerRequestId();
    // A single retained file operation owns a single mutation request. Once there is evidence
    // beyond a send, only an identical observation is accepted on this execution path.
    int recorded =
        sql.within(
            queryDeadline,
            () ->
                jdbc.update(
                    """
            UPDATE suspended_run_calls SET effect_observation = ?, effect_request_id = ?
            WHERE invocation_id = ? AND state = 'started' AND (
                effect_observation IS NULL
                OR (effect_observation = 'FILE_DISPATCH_UNCERTAIN'
                    AND ? IN ('PREFLIGHT_REFUSED', 'FILE_SENT', 'FILE_LOCAL_CHANGED',
                              'FILE_LOCAL_UNCHANGED', 'FILE_LOCAL_UNVERIFIED'))
                OR (effect_request_id IS NOT DISTINCT FROM ? AND (
                    effect_observation = ?
                    OR (effect_observation = 'FILE_SENT'
                        AND ? IN ('FILE_REPLY_TIMEOUT', 'FILE_EFFECT_CONFIRMED', 'FILE_EFFECT_VERIFIED',
                                  'FILE_EFFECT_UNCHANGED', 'FILE_REPLY_UNVERIFIED')))))
            """,
                    observation,
                    requestId,
                    approval,
                    observation,
                    requestId,
                    observation,
                    observation));
    if (recorded != 1)
      throw new IllegalStateException("Effect evidence does not match the claimed call");
    // The diagnostic write has a separate budget and cannot roll back authoritative evidence.
    try {
      traces.observe(approval, receipt.kind());
    } catch (DataAccessException | ReadFailedException | ReadUnavailableException unavailable) {
      log.debug(
          "Could not retain tool trace for {} ({})",
          approval,
          unavailable.getClass().getSimpleName());
    }
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
                + " WHERE invocation_id = ? AND state IN ('waiting', 'started')",
            result,
            approval)
        != 1) throw new IllegalStateException("Suspended call is already completed");
  }

  @Override
  public void completeRead(String approval, String result, FileReadWindow window) {
    Objects.requireNonNull(result, "result");
    Objects.requireNonNull(window, "window");
    int recorded =
        sql.within(
            queryDeadline,
            () ->
                jdbc.update(
                    """
        UPDATE suspended_run_calls SET state = 'completed', result = ?, file_window = CAST(? AS JSONB)
        WHERE approval = ? AND state = 'started' AND tool_name = 'file_read'
        """,
                    result,
                    FileReadWindowCodec.write(window),
                    approval));
    if (recorded != 1)
      throw new IllegalStateException("The claimed file read could not be settled");
  }

  @Override
  public void acknowledged(String approval) {
    if (jdbc.update(
            "UPDATE suspended_run_calls SET acknowledged_at = CURRENT_TIMESTAMP"
                + " WHERE invocation_id = ? AND state = 'completed' AND acknowledged_at IS NULL",
            approval)
        != 1) throw new IllegalStateException("Suspended result is not awaiting acknowledgement");
  }
}
