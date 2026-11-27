package io.aeyer.plowshare.server.goals;

import io.aeyer.plowshare.server.events.FiringRecord.GoalSchedule;
import io.aeyer.plowshare.server.pool.SqlWorkBudget;
import io.aeyer.plowshare.server.pool.SqlWorkBudgetProperties;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;

final class JdbcGoalWaitStore implements GoalWaitStore {
  private final JdbcTemplate jdbc;
  private final SqlWorkBudget sql;
  private final Duration ceiling;

  JdbcGoalWaitStore(JdbcTemplate jdbc, SqlWorkBudget sql, SqlWorkBudgetProperties limits) {
    this.jdbc = jdbc;
    this.sql = sql;
    this.ceiling = limits.queryCeiling();
  }

  @Override
  public boolean dedicatedConversation(String conversation) {
    return bounded(
        () ->
            Boolean.TRUE.equals(
                jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM conversation_goals WHERE conversation=? AND instance IS NOT NULL)",
                    Boolean.class,
                    conversation)));
  }

  @Override
  public Wait arm(Origin origin, Dependency dependency) {
    UUID id = UUID.randomUUID();
    var schedule = origin.schedule();
    requireOne(
        jdbc.update(
            """
        INSERT INTO goal_agent_waits
            (id,goal,cycle,origin_job,input_job,source_schedule,source_firing,source_generation,
             source_fire_at,turn_ordinal,answer_ordinal,call_id,kind,dependency,
             request_id,request_hash,definition,state)
        SELECT ?,g.id,g.cycle,g.job,?,?,?,?,?,?,?,?,?,?,?,?,?,? FROM conversation_goals g
        WHERE g.id=? AND g.cycle=? AND g.job=? AND g.instance IS NOT NULL
          AND g.state IN ('RUNNING','PAUSING')
        """,
            id,
            origin.inputJob(),
            schedule == null ? null : schedule.schedule(),
            schedule == null ? null : schedule.firing(),
            schedule == null ? null : schedule.generation(),
            schedule == null ? null : time(schedule.fireAt()),
            origin.turn(),
            origin.answer(),
            origin.call(),
            dependency.kind().name(),
            dependency.reference(),
            dependency.request(),
            dependency.hash(),
            dependency.definition(),
            State.PARKING.name(),
            origin.goal(),
            origin.cycle(),
            origin.job()));
    return new Wait(id, origin, dependency, State.PARKING, null, null);
  }

  @Override
  public Optional<Wait> open(UUID goal) {
    return bounded(
        () ->
            jdbc
                .query(
                    """
        SELECT * FROM goal_agent_waits WHERE goal=? AND state IN ('PARKING','WAITING','CLAIMED')
        """,
                    JdbcGoalWaitStore::map,
                    goal)
                .stream()
                .findFirst());
  }

  @Override
  public void park(Wait wait) {
    requireOne(
        jdbc.update(
            "UPDATE goal_agent_waits SET state='WAITING' WHERE id=? AND state='PARKING'",
            wait.id()));
  }

  @Override
  public void claim(Wait wait, String dependency, Instant ownerEndedAt, String job) {
    requireOne(
        jdbc.update(
            """
        UPDATE goal_agent_waits
        SET state='CLAIMED', dependency=?, owner_ended_at=?, continuation_job=?
        WHERE id=? AND goal=? AND cycle=? AND state='WAITING'
          AND (dependency IS NULL OR dependency=?)
        """,
            dependency,
            time(ownerEndedAt),
            job,
            wait.id(),
            wait.origin().goal(),
            wait.origin().cycle(),
            dependency));
  }

  @Override
  public void settle(Wait wait, String job) {
    requireOne(
        jdbc.update(
            """
        UPDATE goal_agent_waits SET state='SETTLED'
        WHERE id=? AND state='CLAIMED' AND continuation_job=?
        """,
            wait.id(),
            job));
  }

  @Override
  public void abandon(UUID goal) {
    jdbc.update(
        """
        UPDATE goal_agent_waits SET state='ABANDONED'
        WHERE goal=? AND state IN ('PARKING','WAITING','CLAIMED')
        """,
        goal);
  }

  @Override
  public List<Wait> cleanupAfter(UUID after, int limit) {
    return bounded(
        () ->
            jdbc.query(
                """
        SELECT * FROM goal_agent_waits
        WHERE state='ABANDONED' AND cleanup_at IS NULL AND id>? ORDER BY id LIMIT ?
        """,
                JdbcGoalWaitStore::map,
                after,
                limit));
  }

  @Override
  public void cleaned(UUID id) {
    bounded(
        () ->
            jdbc.update(
                """
        UPDATE goal_agent_waits SET cleanup_at=CURRENT_TIMESTAMP
        WHERE id=? AND state='ABANDONED' AND cleanup_at IS NULL
        """,
                id));
  }

  @Override
  public List<String> orphanedApprovals(int limit) {
    return bounded(
        () ->
            jdbc.queryForList(
                """
        SELECT a.id FROM run_approvals a
        JOIN conversation_goals g ON g.conversation=a.conversation AND g.instance IS NOT NULL
        WHERE a.asked_in=g.conversation AND a.state='asked' AND g.job IS NULL
          AND NOT EXISTS (SELECT 1 FROM goal_agent_waits w
                          WHERE w.kind='APPROVAL' AND w.dependency=a.id)
        ORDER BY a.id LIMIT ?
        """,
                String.class,
                limit));
  }

  private <T> T bounded(Supplier<T> action) {
    return sql.within(ceiling, action);
  }

  private static void requireOne(int count) {
    if (count != 1) throw new IllegalStateException("The Goal no longer owns this dependency");
  }

  private static OffsetDateTime time(Instant at) {
    return at == null ? null : OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
  }

  private static GoalSchedule readSchedule(ResultSet row) throws SQLException {
    var schedule = row.getString("source_schedule");
    if (schedule == null) return null;
    return new GoalSchedule(
        schedule,
        row.getString("source_firing"),
        row.getObject("source_generation", UUID.class),
        row.getObject("source_fire_at", OffsetDateTime.class).toInstant());
  }

  private static Wait map(ResultSet row, int index) throws SQLException {
    var ended = row.getObject("owner_ended_at", OffsetDateTime.class);
    return new Wait(
        row.getObject("id", UUID.class),
        new Origin(
            row.getObject("goal", UUID.class),
            row.getObject("cycle", UUID.class),
            row.getString("origin_job"),
            row.getString("input_job"),
            readSchedule(row),
            row.getInt("turn_ordinal"),
            row.getInt("answer_ordinal"),
            row.getString("call_id")),
        new Dependency(
            Kind.valueOf(row.getString("kind")),
            row.getString("dependency"),
            row.getObject("request_id", UUID.class),
            row.getString("request_hash"),
            row.getString("definition")),
        State.valueOf(row.getString("state")),
        row.getString("continuation_job"),
        ended == null ? null : ended.toInstant());
  }
}
