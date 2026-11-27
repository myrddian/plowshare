package io.aeyer.plowshare.server.goals;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.agents.Turn.Refused;
import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.goals.ConversationGoals.Reason;
import io.aeyer.plowshare.server.goals.ConversationGoals.State;
import io.aeyer.plowshare.server.goals.ConversationGoals.Status;
import io.aeyer.plowshare.server.pool.SqlWorkBudget;
import io.aeyer.plowshare.server.pool.SqlWorkBudgetProperties;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.dao.NonTransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.transaction.CannotCreateTransactionException;

final class JdbcGoalRepository implements GoalRepository {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String UPDATE =
      """
UPDATE conversation_goals
SET state=?, activations=?, activation_limit=?, job=?, last_job=?, last_turn=?, reason=?, detail=?,
    no_progress=?, answer_digest=?, version=version+1
""";
  private static final String VERSION = " WHERE id=? AND version=? RETURNING *";

  private final JdbcTemplate jdbc;
  private final UnitOfWork transactions;
  private final SqlWorkBudget sql;
  private final SqlWorkBudgetProperties budgets;

  JdbcGoalRepository(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      SqlWorkBudget sql,
      SqlWorkBudgetProperties budgets) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.sql = sql;
    this.budgets = budgets;
  }

  @Override
  public Optional<Goal> latest(String conversation) {
    return bounded(
        () ->
            select(
                "SELECT * FROM conversation_goals WHERE conversation=? ORDER BY created_order"
                    + " DESC,id DESC LIMIT 1",
                conversation));
  }

  @Override
  public Optional<Goal> find(UUID id) {
    return bounded(() -> select("SELECT * FROM conversation_goals WHERE id=?", id));
  }

  @Override
  public Optional<Goal> lock(UUID id) {
    return bounded(() -> select("SELECT * FROM conversation_goals WHERE id=? FOR UPDATE", id));
  }

  @Override
  public boolean running(UUID id, String job) {
    return bounded(
        () ->
            Boolean.TRUE.equals(
                jdbc.queryForObject(
                    """
                    SELECT EXISTS (SELECT 1 FROM conversation_goals g
                                   JOIN conversations c ON c.id=g.conversation
                                   WHERE g.id=? AND g.job=? AND g.state IN ('RUNNING','PAUSING')
                                     AND c.lifecycle='active')
                    """,
                    Boolean.class,
                    id,
                    job)));
  }

  @Override
  public Optional<Receipt> receipt(String actor, UUID request) {
    return bounded(
        () ->
            jdbc
                .query(
                    """
                    SELECT fingerprint, receipt FROM conversation_goal_requests
                    WHERE executor=? AND request=? AND receipt IS NOT NULL
                    """,
                    (row, index) -> new Receipt(row.getString(1), decode(row.getString(2))),
                    actor,
                    request)
                .stream()
                .findFirst());
  }

  @Override
  public Status request(
      String actor,
      UUID request,
      String conversation,
      String fingerprint,
      Supplier<Status> change) {
    return bounded(
        () ->
            transactions.inTransaction(
                () -> {
                  // Reserve idempotency before changing the goal. A concurrent duplicate waits for
                  // the
                  // original transaction and can only read its receipt, including after a lost
                  // reply.
                  int inserted =
                      jdbc.update(
                          """
INSERT INTO conversation_goal_requests(executor,request,fingerprint,conversation)
SELECT ?,?,?,id FROM conversations WHERE id=?
ON CONFLICT (executor,request) DO NOTHING
""",
                          actor,
                          request,
                          fingerprint,
                          conversation);
                  if (inserted == 0) {
                    var previous =
                        receipt(actor, request)
                            .orElseThrow(
                                () ->
                                    new Refused(
                                        "Goal request no longer has an available conversation"));
                    if (!previous.fingerprint().equals(fingerprint))
                      throw new CallerFault(
                          "Request ID already belongs to a different goal command");
                    return previous.status();
                  }
                  var status = change.get();
                  int updated =
                      jdbc.update(
                          """
                          UPDATE conversation_goal_requests SET receipt=CAST(? AS jsonb)
                          WHERE executor=? AND request=? AND conversation=? AND receipt IS NULL
                          """,
                          encode(status),
                          actor,
                          request,
                          status.conversation());
                  if (updated != 1)
                    throw new IllegalStateException("Goal request could not record its receipt");
                  return status;
                }));
  }

  @Override
  public Goal insert(Binding binding, int limit) {
    return insertDedicated(binding, limit, null);
  }

  @Override
  public Goal insertDedicated(Binding binding, int limit, Cycle initial) {
    return bounded(
        () ->
            select(
                    """
INSERT INTO conversation_goals
    (id,conversation,admission,executor,agent,fingerprint,allowance,objective,criteria,
     activation_limit,instance,calling_agent,calling_conversation,cycle,cycle_start_spent,cycle_calls)
VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
ON CONFLICT DO NOTHING RETURNING *
""",
                    binding.id(),
                    binding.conversation(),
                    binding.admission(),
                    binding.executor(),
                    binding.agent(),
                    binding.fingerprint(),
                    binding.allowance(),
                    binding.objective(),
                    binding.criteria(),
                    limit,
                    binding.instance(),
                    binding.callingAgent(),
                    binding.callingConversation(),
                    initial == null ? null : initial.id(),
                    initial == null ? null : initial.startSpent(),
                    initial == null ? null : initial.maxCalls())
                .orElseThrow(
                    () -> new Refused("This conversation already has an unfinished goal")));
  }

  @Override
  public Optional<Goal> change(Goal before, Progress after) {
    return bounded(() -> update(before, after));
  }

  @Override
  public Optional<Goal> claim(Goal before, Progress running, Cycle cycle, Instant automaticAt) {
    return bounded(
        () ->
            select(
                UPDATE
                    + """
        ,cycle=?,cycle_start_spent=?,cycle_calls=?,last_automatic_at=COALESCE(?,last_automatic_at)
        """
                    + VERSION,
                running.state().name(),
                running.activations(),
                running.limit(),
                running.job(),
                running.lastJob(),
                running.lastTurn(),
                null,
                null,
                running.noProgress(),
                running.answerDigest(),
                cycle.id(),
                cycle.startSpent(),
                cycle.maxCalls(),
                automaticAt == null ? null : OffsetDateTime.ofInstant(automaticAt, ZoneOffset.UTC),
                before.binding().id(),
                before.version()));
  }

  @Override
  public void settle(UUID id, String job, Runnable writeTurn, Function<Goal, Settlement> next) {
    bounded(
        () ->
            transactions.inTransaction(
                () -> {
                  var current =
                      select("SELECT * FROM conversation_goals WHERE id=? FOR UPDATE", id)
                          .filter(goal -> job.equals(goal.progress().job()))
                          .orElseThrow(
                              () -> new IllegalStateException("The goal no longer owns this turn"));
                  writeTurn.run();
                  var settlement = next.apply(current);
                  var updated = update(current, settlement.progress()).orElseThrow();
                  if (settlement.completion() != null)
                    retainResult(current, settlement.completion());
                  return updated;
                }));
  }

  private void retainResult(Goal current, GoalAgents.Result result) {
    jdbc.update(
        """
        INSERT INTO goal_agent_latest_results(goal,cycle,job,turn,summary,evidence)
        VALUES (?,?,?,?,?,?)
        ON CONFLICT (goal) DO UPDATE SET cycle=EXCLUDED.cycle,job=EXCLUDED.job,
          turn=EXCLUDED.turn,summary=EXCLUDED.summary,evidence=EXCLUDED.evidence
        """,
        current.binding().id(),
        current.cycle() == null ? null : current.cycle().id(),
        result.job(),
        result.turn(),
        result.summary(),
        new SqlArrayValue("text", result.evidence().toArray()));
  }

  @Override
  public List<Goal> scan(UUID after, int limit) {
    return bounded(
        () ->
            jdbc.query(
                """
                SELECT g.* FROM conversation_goals g
                WHERE (g.state IN ('READY','RUNNING','WAITING','PAUSING','STOPPING')
                  OR (g.state='IDLE' AND EXISTS (SELECT 1
                """
                    + JdbcGoalAgentStore.READY_INPUTS
                    + """
                    AND i.goal=g.id)))
                  AND g.id>? ORDER BY g.id LIMIT ?
                """,
                ROW,
                after,
                limit));
  }

  private Optional<Goal> update(Goal goal, Progress progress) {
    return select(
        UPDATE + VERSION,
        progress.state().name(),
        progress.activations(),
        progress.limit(),
        progress.job(),
        progress.lastJob(),
        progress.lastTurn(),
        progress.reason() == null ? null : progress.reason().name(),
        progress.detail(),
        progress.noProgress(),
        progress.answerDigest(),
        goal.binding().id(),
        goal.version());
  }

  private Optional<Goal> select(String statement, Object... parameters) {
    return jdbc.query(statement, ROW, parameters).stream().findFirst();
  }

  private <T> T bounded(Supplier<T> action) {
    try {
      return sql.within(budgets.queryCeiling(), action);
    } catch (NonTransientDataAccessResourceException
        | CannotCreateTransactionException unavailable) {
      throw new ArchiveUnavailableException(
          "Conversation goal storage is unavailable", unavailable);
    }
  }

  private static final RowMapper<Goal> ROW = (row, index) -> map(row);

  private static Goal map(ResultSet row) throws SQLException {
    var binding =
        new Binding(
            row.getObject("id", UUID.class),
            row.getString("conversation"),
            row.getObject("admission", UUID.class),
            row.getString("executor"),
            row.getString("agent"),
            row.getString("fingerprint"),
            row.getObject("allowance", UUID.class),
            row.getString("objective"),
            row.getString("criteria"),
            row.getString("instance"),
            row.getString("calling_agent"),
            row.getString("calling_conversation"));
    var reason = row.getString("reason");
    var progress =
        new Progress(
            State.valueOf(row.getString("state")),
            row.getInt("activations"),
            row.getInt("activation_limit"),
            row.getString("job"),
            row.getString("last_job"),
            row.getObject("last_turn", Integer.class),
            reason == null ? null : Reason.valueOf(reason),
            row.getString("detail"),
            row.getInt("no_progress"),
            row.getString("answer_digest"));
    UUID cycleId = row.getObject("cycle", UUID.class);
    var cycle =
        cycleId == null
            ? null
            : new Cycle(cycleId, row.getInt("cycle_start_spent"), row.getInt("cycle_calls"));
    var automaticAt = row.getObject("last_automatic_at", OffsetDateTime.class);
    return new Goal(
        binding,
        row.getInt("version"),
        progress,
        cycle,
        automaticAt == null ? null : automaticAt.toInstant());
  }

  private static String encode(Status status) {
    try {
      return JSON.writeValueAsString(status);
    } catch (JsonProcessingException invalid) {
      throw new IllegalStateException("Goal receipt could not be encoded", invalid);
    }
  }

  private static Status decode(String receipt) {
    try {
      return JSON.readValue(receipt, Status.class);
    } catch (JsonProcessingException invalid) {
      throw new IllegalStateException("Goal receipt could not be decoded", invalid);
    }
  }
}
