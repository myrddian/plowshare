package io.aeyer.plowshare.server.goals;

import io.aeyer.plowshare.server.messaging.MessageService.GoalReturn;
import io.aeyer.plowshare.server.pool.SqlWorkBudget;
import io.aeyer.plowshare.server.pool.SqlWorkBudgetProperties;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;

final class JdbcGoalReturnStore implements GoalReturnStore {
  private static final String INTENTS =
      """
      SELECT i.id,i.goal,r.audience,r.launch_message,r.caller_instance,r.goal_instance,
             i.kind,i.goal_version,i.body,i.delivery,i.receipt,i.answer_message
      FROM goal_return_intents i JOIN goal_return_routes r ON r.goal=i.goal
      """;

  private final JdbcTemplate jdbc;
  private final SqlWorkBudget sql;
  private final Duration ceiling;

  JdbcGoalReturnStore(JdbcTemplate jdbc, SqlWorkBudget sql, SqlWorkBudgetProperties limits) {
    this.jdbc = jdbc;
    this.sql = sql;
    this.ceiling = limits.queryCeiling();
  }

  @Override
  public void bind(Route route) {
    var bot = route.bot();
    jdbc.update(
        """
        INSERT INTO goal_return_routes(goal,audience,launch_message,caller_instance,goal_instance)
        VALUES (?,?,?,?,?)
        """,
        route.goal(),
        route.human() ? "HUMAN" : "BOT",
        bot == null ? null : bot.message(),
        bot == null ? null : bot.sender(),
        bot == null ? null : bot.recipient());
  }

  @Override
  public Optional<Route> route(UUID goal) {
    return bounded(
        () ->
            jdbc
                .query(
                    "SELECT * FROM goal_return_routes WHERE goal=?",
                    JdbcGoalReturnStore::route,
                    goal)
                .stream()
                .findFirst());
  }

  @Override
  public void stage(UUID id, UUID goal, Kind kind, int goalVersion, String body) {
    jdbc.update(
        """
        INSERT INTO goal_return_intents(id,goal,kind,goal_version,body)
        SELECT ?,r.goal,?,?,? FROM goal_return_routes r WHERE r.goal=?
        """,
        id,
        kind.name(),
        goalVersion,
        body,
        goal);
  }

  @Override
  public Optional<Intent> intent(UUID id) {
    return bounded(
        () ->
            jdbc.query(INTENTS + " WHERE i.id=?", JdbcGoalReturnStore::intent, id).stream()
                .findFirst());
  }

  @Override
  public Optional<Intent> questionByReceipt(String message) {
    return bounded(
        () ->
            jdbc
                .query(
                    INTENTS + " WHERE i.receipt=? AND i.kind='QUESTION'",
                    JdbcGoalReturnStore::intent,
                    message)
                .stream()
                .findFirst());
  }

  @Override
  public void delivered(UUID intent, String receipt) {
    if (jdbc.update(
            "UPDATE goal_return_intents SET delivery='DELIVERED',receipt=? WHERE id=? AND delivery='PENDING'",
            receipt,
            intent)
        != 1) throw new IllegalStateException("The Goal return was already settled");
  }

  @Override
  public void withheld(UUID intent) {
    bounded(
        () ->
            jdbc.update(
                "UPDATE goal_return_intents SET delivery='WITHHELD' WHERE id=? AND delivery='PENDING'",
                intent));
  }

  @Override
  public List<Intent> pendingAfter(UUID after, int limit) {
    return bounded(
        () ->
            jdbc.query(
                INTENTS + " WHERE i.delivery='PENDING' AND i.id>? ORDER BY i.id LIMIT ?",
                JdbcGoalReturnStore::intent,
                after,
                limit));
  }

  @Override
  public List<Intent> questionsToClean(UUID after, int limit) {
    return bounded(
        () ->
            jdbc.query(
                INTENTS
                    + """
            WHERE i.kind='QUESTION' AND i.delivery='DELIVERED' AND i.cleaned_at IS NULL AND i.id>?
              AND EXISTS (SELECT 1 FROM goal_agent_waits w WHERE w.goal=i.goal AND w.request_id=i.id
                          AND w.state IN ('SETTLED','ABANDONED'))
            ORDER BY i.id LIMIT ?
            """,
                JdbcGoalReturnStore::intent,
                after,
                limit));
  }

  @Override
  public void cleaned(UUID intent) {
    bounded(
        () ->
            jdbc.update(
                "UPDATE goal_return_intents SET cleaned_at=clock_timestamp() WHERE id=? AND cleaned_at IS NULL",
                intent));
  }

  @Override
  public Optional<HumanAnswer> humanAttempt(UUID goal, UUID request) {
    return bounded(
        () ->
            jdbc
                .query(
                    "SELECT question,request_id,body FROM goal_return_answers WHERE goal=? AND request_id=?",
                    JdbcGoalReturnStore::answer,
                    goal,
                    request)
                .stream()
                .findFirst());
  }

  @Override
  public Optional<HumanAnswer> humanAnswer(UUID question) {
    return bounded(
        () ->
            jdbc
                .query(
                    "SELECT question,request_id,body FROM goal_return_answers WHERE question=?",
                    JdbcGoalReturnStore::answer,
                    question)
                .stream()
                .findFirst());
  }

  @Override
  public boolean answer(UUID goal, HumanAnswer answer) {
    return jdbc.update(
            """
        INSERT INTO goal_return_answers(question,goal,request_id,body)
        VALUES (?,?,?,?) ON CONFLICT DO NOTHING
        """,
            answer.question(),
            goal,
            answer.requestId(),
            answer.body())
        == 1;
  }

  @Override
  public boolean answerFromBot(UUID question, String message) {
    return jdbc.update(
            """
        UPDATE goal_return_intents SET answer_message=?
        WHERE id=? AND kind='QUESTION' AND delivery='DELIVERED' AND answer_message IS NULL
        """,
            message,
            question)
        == 1;
  }

  private static Route route(ResultSet row, int unused) throws SQLException {
    return new Route(
        row.getObject("goal", UUID.class),
        "HUMAN".equals(row.getString("audience"))
            ? null
            : new GoalReturn(
                row.getString("caller_instance"),
                row.getString("goal_instance"),
                row.getString("launch_message")));
  }

  private static Intent intent(ResultSet row, int index) throws SQLException {
    return new Intent(
        row.getObject("id", UUID.class),
        route(row, index),
        Kind.valueOf(row.getString("kind")),
        row.getInt("goal_version"),
        row.getString("body"),
        Delivery.valueOf(row.getString("delivery")),
        row.getString("receipt"),
        row.getString("answer_message"));
  }

  private static HumanAnswer answer(ResultSet row, int unused) throws SQLException {
    return new HumanAnswer(
        row.getObject("question", UUID.class),
        row.getObject("request_id", UUID.class),
        row.getString("body"));
  }

  private <T> T bounded(Supplier<T> action) {
    return sql.within(ceiling, action);
  }
}
