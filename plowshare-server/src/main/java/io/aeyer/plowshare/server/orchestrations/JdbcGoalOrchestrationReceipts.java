package io.aeyer.plowshare.server.orchestrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.server.orchestrations.GoalOrchestrations.Run;
import io.aeyer.plowshare.server.pool.SqlWorkBudget;
import io.aeyer.plowshare.server.pool.SqlWorkBudgetProperties;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** One indexed receipt lookup per operation. The payload's namespace excludes other root starts. */
final class JdbcGoalOrchestrationReceipts implements GoalOrchestrationReceipts {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String RECEIPT =
      """
      FROM orchestration_start_receipts receipt
      JOIN orchestrations run ON run.id = receipt.run_id
      WHERE receipt.account = :executor AND receipt.request_id = :invocation
        AND receipt.payload @> CAST(:identity AS jsonb)
        AND run.caller_handle = :executor AND run.caller_conversation = :conversation
        AND run.caller_agent = :agent AND run.project = :project
        AND run.definition_name = :name AND run.definition_hash = :hash
        AND run.parent IS NULL
      """;

  private final JdbcClient jdbc;
  private final SqlWorkBudget sql;
  private final SqlWorkBudgetProperties limits;

  JdbcGoalOrchestrationReceipts(
      JdbcTemplate jdbc, SqlWorkBudget sql, SqlWorkBudgetProperties limits) {
    this.jdbc = JdbcClient.create(jdbc);
    this.sql = sql;
    this.limits = limits;
  }

  /** The original task is part of the immutable receipt, but never needed for reconciliation. */
  static String payload(Key key, String request) {
    return identity(key).put("request", request).toString();
  }

  private static ObjectNode identity(Key key) {
    return JSON.createObjectNode()
        .put("source", SOURCE)
        .put("admission", key.owner().admission().toString())
        .put("conversation", key.owner().conversation())
        .put("caller", key.owner().agent())
        .put("name", key.name())
        .put("hash", key.hash());
  }

  @Override
  public Optional<Run> find(Key key) {
    return sql.within(
        limits.queryCeiling(),
        () ->
            receipt(
                    "SELECT run.id, run.conductor_conversation, run.state, run.ended_at, "
                        + "LEFT(run.result, :resultWindow) AS result ",
                    key,
                    "")
                .param("resultWindow", GoalOrchestrations.MAX_INLINE_RESULT + 1)
                .query(
                    (row, index) -> {
                      var ended = row.getObject("ended_at", OffsetDateTime.class);
                      return new Run(
                          row.getString("id"),
                          row.getString("conductor_conversation"),
                          row.getString("state"),
                          ended == null ? null : ended.toInstant(),
                          row.getString("result"));
                    })
                .optional());
  }

  @Override
  public boolean stillTerminal(Key key, Child child, String state, Instant endedAt) {
    return sql.within(
        limits.queryCeiling(),
        () ->
            receipt(
                    "SELECT 1 ",
                    key,
                    """
                      AND run.id = :id AND run.conductor_conversation = :child
                      AND run.state = :state AND run.ended_at = :ended
                      FOR SHARE OF run
                    """)
                .param("id", child.id())
                .param("child", child.conversation())
                .param("state", state)
                .param("ended", endedAt.atOffset(ZoneOffset.UTC))
                .query(Integer.class)
                .optional()
                .isPresent());
  }

  @Override
  public Optional<Child> active(Key key) {
    return sql.within(
        limits.queryCeiling(),
        () ->
            receipt("SELECT run.id, run.conductor_conversation ", key, " AND run.ended_at IS NULL")
                .query(
                    (row, index) ->
                        new Child(row.getString("id"), row.getString("conductor_conversation")))
                .optional());
  }

  private JdbcClient.StatementSpec receipt(String select, Key key, String filter) {
    // Select and filter are fixed statements from this repository. All identity values are bound.
    return jdbc.sql(select + RECEIPT + filter)
        .param("executor", key.owner().executor())
        .param("invocation", key.invocation())
        .param("identity", identity(key).toString())
        .param("conversation", key.owner().conversation())
        .param("agent", key.owner().agent())
        .param("project", key.project())
        .param("name", key.name())
        .param("hash", key.hash());
  }
}
