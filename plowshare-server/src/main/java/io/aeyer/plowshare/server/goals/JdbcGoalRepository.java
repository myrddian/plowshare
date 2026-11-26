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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.dao.NonTransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.CannotCreateTransactionException;

final class JdbcGoalRepository implements GoalRepository {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String UPDATE =
      """
UPDATE conversation_goals
SET state=?, activations=?, activation_limit=?, job=?, last_job=?, last_turn=?, reason=?, detail=?,
    no_progress=?, answer_digest=?, version=version+1
WHERE id=? AND version=?
RETURNING *
""";

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
    return bounded(
        () ->
            select(
                    """
INSERT INTO conversation_goals
    (id,conversation,admission,executor,agent,fingerprint,allowance,objective,criteria,activation_limit)
VALUES (?,?,?,?,?,?,?,?,?,?)
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
                    limit)
                .orElseThrow(
                    () -> new Refused("This conversation already has an unfinished goal")));
  }

  @Override
  public Optional<Goal> change(Goal before, Progress after) {
    return bounded(() -> update(before, after));
  }

  @Override
  public void settle(UUID id, String job, Runnable writeTurn, Function<Goal, Progress> next) {
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
                  return update(current, next.apply(current)).orElseThrow();
                }));
  }

  @Override
  public List<Goal> scan(UUID after, int limit) {
    return bounded(
        () ->
            jdbc.query(
                """
                SELECT * FROM conversation_goals
                WHERE state IN ('READY','RUNNING','PAUSING','STOPPING')
                  AND id>? ORDER BY id LIMIT ?
                """,
                ROW,
                after,
                limit));
  }

  private Optional<Goal> update(Goal goal, Progress progress) {
    return select(
        UPDATE,
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
            row.getString("criteria"));
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
    return new Goal(binding, row.getInt("version"), progress);
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
