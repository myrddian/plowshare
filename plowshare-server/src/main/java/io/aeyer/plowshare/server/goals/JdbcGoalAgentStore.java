package io.aeyer.plowshare.server.goals;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.events.FiringRecord.GoalSchedule;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.goals.GoalAgents.Batch;
import io.aeyer.plowshare.server.goals.GoalAgents.Unlisten;
import io.aeyer.plowshare.server.goals.GoalAgents.Unlistened;
import io.aeyer.plowshare.server.messaging.MessageService.Instance;
import io.aeyer.plowshare.server.pool.SqlWorkBudget;
import io.aeyer.plowshare.server.pool.SqlWorkBudgetProperties;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

final class JdbcGoalAgentStore implements GoalAgentStore {
  private final JdbcTemplate jdbc;
  private final UnitOfWork work;
  private final SqlWorkBudget sql;
  private final Duration ceiling;

  JdbcGoalAgentStore(
      JdbcTemplate jdbc, UnitOfWork work, SqlWorkBudget sql, SqlWorkBudgetProperties budgets) {
    this.jdbc = jdbc;
    this.work = work;
    this.sql = sql;
    this.ceiling = budgets.queryCeiling();
  }

  @Override
  public <T> T transaction(Supplier<T> write) {
    return sql.within(ceiling, () -> work.inTransaction(write));
  }

  @Override
  public UUID launch(String actor, UUID request, String fingerprint, Supplier<UUID> create) {
    return transaction(
        () -> {
          if (jdbc.update(
                  """
          INSERT INTO goal_agent_launch_requests(executor,request,fingerprint) VALUES(?,?,?)
          ON CONFLICT (executor,request) DO NOTHING
          """,
                  actor,
                  request,
                  fingerprint)
              == 0) {
            return jdbc.query(
                    """
            SELECT fingerprint,goal FROM goal_agent_launch_requests WHERE executor=? AND request=?
            """,
                    (row, i) -> {
                      if (!fingerprint.equals(row.getString("fingerprint")))
                        throw new CallerFault(
                            "Request ID already belongs to another Goal Agent launch");
                      return row.getObject("goal", UUID.class);
                    },
                    actor,
                    request)
                .getFirst();
          }
          UUID goal = create.get();
          jdbc.update(
              "UPDATE goal_agent_launch_requests SET goal=? WHERE executor=? AND request=?",
              goal,
              actor,
              request);
          return goal;
        });
  }

  @Override
  public boolean stage(Instance instance, String message) {
    var mailbox = lockMailbox(instance);
    if (mailbox.isEmpty()) return false;
    jdbc.update(
        "INSERT INTO goal_agent_inputs(goal,message) VALUES(?,?) ON CONFLICT(message) DO NOTHING",
        mailbox.get(),
        message);
    return true;
  }

  @Override
  public Optional<UUID> lockMailbox(Instance instance) {
    // Messaging already owns the mailbox slot. Lock the goal before staging in that transaction.
    return mailbox(instance, true);
  }

  @Override
  public boolean scheduleTarget(Instance instance) {
    return sql.within(ceiling, () -> mailbox(instance, false).isPresent());
  }

  private Optional<UUID> mailbox(Instance instance, boolean lock) {
    var target =
        jdbc
            .query(
                """
        SELECT id,state FROM conversation_goals
        WHERE instance=? AND conversation=? AND executor=?
        """
                    + (lock ? " FOR UPDATE" : ""),
                (row, index) ->
                    new Mailbox(row.getObject("id", UUID.class), row.getString("state")),
                instance.id(),
                instance.conversation(),
                instance.account())
            .stream()
            .findFirst();
    return target.map(
        mailbox -> {
          if (terminal(mailbox.state())) throw new CallerFault("That Goal Agent has ended");
          return mailbox.goal();
        });
  }

  private record Mailbox(UUID goal, String state) {}

  @Override
  public boolean hasSchedule(UUID goal, GoalSchedule firing, boolean lock) {
    if (lock && !TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("A Goal schedule claim requires a transaction");
    // Lock the backing file row, not the view or the trigger queue. The Goal row lock precedes
    // this in both staging and turn admission. Reconciliation owns the file generation.
    return sql.within(
        ceiling,
        () ->
            !jdbc.queryForList(
                    """
                    SELECT b.schedule FROM goal_schedule_bindings b
                    JOIN schedule_files f ON f.internal_name=b.schedule AND f.generation=b.generation
                    JOIN firings due ON due.id=? AND due.schedule=b.schedule
                      AND due.schedule_generation=b.generation AND due.fire_at=? AND due.status='started'
                    WHERE b.goal=? AND b.schedule=? AND b.generation=? AND NOT b.paused
                    """
                        + (lock ? " FOR SHARE OF f" : ""),
                    String.class,
                    firing.firing(),
                    OffsetDateTime.ofInstant(firing.fireAt(), ZoneOffset.UTC),
                    goal,
                    firing.schedule(),
                    firing.generation())
                .isEmpty());
  }

  @Override
  public void stageSchedule(UUID goal, String message, GoalSchedule firing) {
    jdbc.update(
        """
        INSERT INTO goal_agent_inputs(goal,message,schedule_firing) VALUES(?,?,?)
        """,
        goal,
        message,
        firing.firing());
  }

  @Override
  public void disposeUnclaimed(UUID goal, long sequence, Disposition disposition) {
    jdbc.update(
        """
        UPDATE goal_agent_inputs SET settled=TRUE,disposition=?
        WHERE goal=? AND sequence=? AND NOT settled AND claimed_job IS NULL AND message IS NOT NULL
        """,
        disposition.name(),
        goal,
        sequence);
  }

  @Override
  public boolean owns(String instance) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM conversation_goals WHERE instance=?)",
            Boolean.class,
            instance));
  }

  @Override
  public boolean lockOpen(UUID goal, UUID admission) {
    return jdbc
        .query(
            """
        SELECT state FROM conversation_goals
        WHERE id=? AND instance IS NOT NULL AND (?::uuid IS NULL OR admission=?)
        FOR UPDATE
        """,
            (row, index) -> !terminal(row.getString(1)),
            goal,
            admission,
            admission)
        .stream()
        .findFirst()
        .orElse(false);
  }

  @Override
  public boolean canCollect(UUID goal, int limit) {
    return !Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS (SELECT 1 FROM goal_agent_inputs
          WHERE goal=? AND NOT settled AND listener IS NOT NULL
          ORDER BY sequence OFFSET ? LIMIT 1)
        """,
            Boolean.class,
            goal,
            limit - 1));
  }

  private static boolean terminal(String state) {
    return List.of("STOPPING", "COMPLETE", "CANCELLED").contains(state);
  }

  private static final String LISTENER_COLUMNS =
      "l.id,l.goal,l.project_id,l.topic,l.generation,l.cursor_generation,l.batch_count,l.max_delay_seconds ";

  @Override
  public Listener bind(UUID goal, Relay.TopicKey topic, UUID generation, Batch batch) {
    // Repeating an identical binding preserves its cursor. Explicit removal permits a new
    // incarnation, which independently subscribes from the current head of the same topic.
    jdbc.update(
        """
        INSERT INTO goal_agent_listeners(id,goal,project_id,topic,generation,batch_count,max_delay_seconds)
        SELECT ?,?,?,?,?,?,? WHERE (SELECT count(*) FROM goal_agent_listeners
          WHERE goal=? AND removed_at IS NULL) < 16
        ON CONFLICT (goal,project_id,topic,generation) WHERE removed_at IS NULL DO NOTHING
        """,
        UUID.randomUUID(),
        goal,
        topic.projectId(),
        topic.name(),
        generation,
        batch == null ? 1 : batch.count(),
        batch == null ? null : batch.maxDelaySeconds(),
        goal);
    var found =
        jdbc
            .query(
                "SELECT "
                    + LISTENER_COLUMNS
                    + """
                 FROM goal_agent_listeners l
                 WHERE l.goal=? AND l.project_id=? AND l.topic=? AND l.generation=?
                   AND l.removed_at IS NULL AND l.failure IS NULL
                 """,
                JdbcGoalAgentStore::listener,
                goal,
                topic.projectId(),
                topic.name(),
                generation)
            .stream()
            .findFirst()
            .orElseThrow(
                () ->
                    new CallerFault(
                        "Remove the failed listener first or free a Goal Agent listener slot"));
    if (!Objects.equals(found.batch(), batch))
      throw new CallerFault("Remove the existing listener before changing its batch policy");
    return found;
  }

  @Override
  public void retainCursor(Listener listener, UUID generation) {
    if (jdbc.update(
            "UPDATE goal_agent_listeners SET cursor_generation=? WHERE id=? AND cursor_generation IS NULL",
            Objects.requireNonNull(generation),
            listener.id())
        != 1) throw new IllegalStateException("Goal listener cursor changed");
  }

  @Override
  public boolean standing(UUID goal) {
    return exists(
        """
        SELECT EXISTS (SELECT 1 FROM goal_agent_listeners WHERE goal=? AND removed_at IS NULL)
            OR EXISTS (SELECT 1 FROM goal_schedule_bindings WHERE goal=?)
        """,
        goal,
        goal);
  }

  @Override
  public boolean directPending(UUID goal) {
    return exists(
        """
        SELECT EXISTS (SELECT 1 FROM goal_agent_inputs WHERE goal=? AND NOT settled
          AND claimed_job IS NULL AND message IS NOT NULL AND schedule_firing IS NULL)
        """,
        goal);
  }

  @Override
  public Optional<ListenerFailure> failedListener(UUID goal) {
    return sql.within(
        ceiling,
        () ->
            jdbc
                .query(
                    """
        SELECT id,failure,expired_after,expired_through FROM goal_agent_listeners
        WHERE goal=? AND removed_at IS NULL AND failure IS NOT NULL ORDER BY id LIMIT 1
        """,
                    (row, index) -> {
                      Long after = row.getObject("expired_after", Long.class);
                      return new ListenerFailure(
                          row.getObject("id", UUID.class),
                          Failure.valueOf(row.getString("failure")),
                          after == null
                              ? null
                              : new Relay.Gap(after, row.getLong("expired_through")));
                    },
                    goal)
                .stream()
                .findFirst());
  }

  private boolean exists(String query, Object... args) {
    return sql.within(
        ceiling, () -> Boolean.TRUE.equals(jdbc.queryForObject(query, Boolean.class, args)));
  }

  @Override
  public List<Listener> listeners(UUID after, int limit) {
    return sql.within(
        ceiling,
        () ->
            jdbc.query(
                "SELECT "
                    + LISTENER_COLUMNS
                    + """
             FROM goal_agent_listeners l JOIN conversation_goals g ON g.id=l.goal
             WHERE l.removed_at IS NULL AND l.failure IS NULL
               AND g.state IN ('READY','RUNNING','IDLE','WAITING') AND l.id>?
             ORDER BY l.id LIMIT ?
             """,
                JdbcGoalAgentStore::listener,
                after,
                limit));
  }

  @Override
  public boolean collectible(Listener listener) {
    return exists(
        """
        SELECT EXISTS (SELECT 1 FROM goal_agent_listeners l JOIN conversation_goals g ON g.id=l.goal
          WHERE l.id=? AND l.goal=? AND l.removed_at IS NULL AND l.failure IS NULL
            AND g.state IN ('READY','RUNNING','IDLE','WAITING'))
        """,
        listener.id(),
        listener.goal());
  }

  @Override
  public boolean fail(Listener listener, ListenerFailure failure) {
    var gap = failure.gap();
    if (jdbc.update(
            """
        UPDATE goal_agent_listeners SET failure=?,expired_after=?,expired_through=?
        WHERE id=? AND removed_at IS NULL AND failure IS NULL
        """,
            failure.reason().name(),
            gap == null ? null : gap.afterExclusive(),
            gap == null ? null : gap.throughInclusive(),
            listener.id())
        == 0) return false;
    disarm(listener.goal(), listener.id(), Disposition.SOURCE_UNAVAILABLE);
    return true;
  }

  @Override
  public void stage(Listener listener, Relay.Publication publication, Instant now) {
    if (!publication.topic().equals(listener.topic()))
      throw new IllegalArgumentException("Wrong Relay source");
    String text =
        switch (publication.event().payload()) {
          case RelayPayload.Text body -> body.text();
          case RelayPayload.Empty ignored -> null;
          default -> throw new IllegalArgumentException("Unsupported Goal Agent wake payload");
        };
    // Freeze before staging a late arrival, including when the age worker was delayed.
    jdbc.update(
        """
        UPDATE goal_agent_relay_batches SET state='READY'
        WHERE listener=? AND state='OPEN' AND deadline<=?
        """,
        listener.id(),
        timestamp(now));
    var batch =
        jdbc
            .queryForList(
                "SELECT id FROM goal_agent_relay_batches WHERE listener=? AND state='OPEN'",
                UUID.class,
                listener.id())
            .stream()
            .findFirst()
            .orElseGet(() -> startBatch(listener, now));
    var staged =
        jdbc.queryForList(
            """
        INSERT INTO goal_agent_inputs(goal,listener,batch,position,event_id,body) VALUES(?,?,?,?,?,?)
        ON CONFLICT (listener,position) DO NOTHING RETURNING sequence
        """,
            Long.class,
            listener.goal(),
            listener.id(),
            batch,
            publication.position(),
            publication.event().eventId(),
            text);
    if (staged.isEmpty()) {
      // A duplicate must not leave a new, empty open batch or extend the prior deadline.
      jdbc.update("DELETE FROM goal_agent_relay_batches WHERE id=? AND member_count=0", batch);
      return;
    }
    int target = listener.batch() == null ? 1 : listener.batch().count();
    jdbc.update(
        """
        UPDATE goal_agent_relay_batches
        SET first_sequence=COALESCE(first_sequence,?),member_count=member_count+1,
            state=CASE WHEN member_count+1>=? THEN 'READY' ELSE 'OPEN' END
        WHERE id=? AND state='OPEN'
        """,
        staged.getFirst(),
        target,
        batch);
  }

  private UUID startBatch(Listener listener, Instant now) {
    UUID batch = UUID.randomUUID();
    Instant deadline =
        listener.batch() == null ? now : now.plusSeconds(listener.batch().maxDelaySeconds());
    jdbc.update(
        """
        INSERT INTO goal_agent_relay_batches(id,listener,first_at,deadline) VALUES(?,?,?,?)
        """,
        batch,
        listener.id(),
        timestamp(now),
        timestamp(deadline));
    return batch;
  }

  @Override
  public List<DueBatch> dueBatches(Instant now, int limit) {
    return sql.within(
        ceiling,
        () ->
            jdbc.query(
                """
            SELECT b.id,l.goal FROM goal_agent_relay_batches b
            JOIN goal_agent_listeners l ON l.id=b.listener
            WHERE b.state='OPEN' AND b.deadline<=? ORDER BY b.deadline,b.id LIMIT ?
            """,
                (row, index) ->
                    new DueBatch(row.getObject(1, UUID.class), row.getObject(2, UUID.class)),
                timestamp(now),
                limit));
  }

  @Override
  public void mature(DueBatch batch, Instant now) {
    jdbc.update(
        """
        UPDATE goal_agent_relay_batches b SET state='READY'
        FROM goal_agent_listeners l
        WHERE b.id=? AND b.listener=l.id AND l.goal=? AND b.state='OPEN' AND b.deadline<=?
          AND l.removed_at IS NULL AND l.failure IS NULL
        """,
        batch.id(),
        batch.goal(),
        timestamp(now));
  }

  // One queue head per ready Relay batch. Direct steering wins for both ongoing and parked work.
  static final String READY_INPUTS =
      """
      FROM goal_agent_inputs i LEFT JOIN goal_agent_listeners l ON l.id=i.listener
      LEFT JOIN goal_agent_relay_batches b ON b.id=i.batch
      WHERE NOT i.settled AND i.claimed_job IS NULL
        AND (i.listener IS NULL OR (b.state='READY' AND b.first_sequence=i.sequence
          AND l.removed_at IS NULL AND l.failure IS NULL))
      """;
  private static final String NEXT_INPUT =
      READY_INPUTS
          + """
        AND i.goal=? AND (? OR (i.message IS NOT NULL AND i.schedule_firing IS NULL))
        ORDER BY (i.message IS NULL OR i.schedule_firing IS NOT NULL),i.sequence LIMIT 1
        """;

  @Override
  public boolean first(UUID goal, Long sequence, boolean automatic) {
    return Objects.equals(
        jdbc.queryForList("SELECT i.sequence " + NEXT_INPUT, Long.class, goal, automatic).stream()
            .findFirst()
            .orElse(null),
        sequence);
  }

  @Override
  public Optional<Input> next(UUID goal, boolean automatic) {
    return sql.within(
        ceiling,
        () ->
            jdbc.query(
                "WITH head AS (SELECT i.sequence,i.message,i.batch,i.listener,i.schedule_firing "
                    + NEXT_INPUT
                    + ") SELECT h.sequence,h.message,h.batch,"
                    + LISTENER_COLUMNS
                    + """
                ,due.id AS schedule_firing,due.schedule,due.fire_at,due.schedule_generation,
                member.position,member.event_id,member.body
              FROM head h LEFT JOIN goal_agent_listeners l ON l.id=h.listener
              LEFT JOIN firings due ON due.id=h.schedule_firing
              LEFT JOIN goal_agent_inputs member ON member.batch=h.batch
              ORDER BY member.sequence
              """,
                (ResultSet rows) -> {
                  return readInput(rows);
                },
                goal,
                automatic));
  }

  @Override
  public Optional<SourceFence> claimedSource(UUID goal, String job) {
    return sql.within(
        ceiling,
        () ->
            jdbc
                .query(
                    """
        SELECT due.id AS schedule_firing,due.schedule,due.fire_at,due.schedule_generation,
        """
                        + LISTENER_COLUMNS
                        + """
        FROM goal_agent_inputs i
        LEFT JOIN goal_agent_listeners l ON l.id=i.listener
        LEFT JOIN firings due ON due.id=i.schedule_firing
        WHERE i.goal=? AND i.claimed_job=? AND (i.listener IS NOT NULL OR i.schedule_firing IS NOT NULL)
        ORDER BY i.sequence LIMIT 1
        """,
                    (row, i) ->
                        new SourceFence(
                            scheduled(row),
                            row.getObject("id", UUID.class) == null ? null : listener(row, i)),
                    goal,
                    job)
                .stream()
                .findFirst());
  }

  @Override
  public boolean currentSchedule(UUID goal, GoalSchedule firing, boolean lock) {
    if (lock && !TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("A retained Goal schedule requires a transaction");
    return sql.within(
        ceiling,
        () ->
            !jdbc.queryForList(
                    """
        SELECT b.schedule FROM goal_schedule_bindings b
        JOIN schedule_files f ON f.internal_name=b.schedule AND f.generation=b.generation
        WHERE b.goal=? AND b.schedule=? AND b.generation=? AND NOT b.paused
        """
                        + (lock ? " FOR SHARE OF f" : ""),
                    String.class,
                    goal,
                    firing.schedule(),
                    firing.generation())
                .isEmpty());
  }

  @Override
  public boolean currentListener(Listener listener) {
    return sql.within(
        ceiling,
        () ->
            Boolean.TRUE.equals(
                jdbc.queryForObject(
                    """
        SELECT EXISTS (SELECT 1 FROM goal_agent_listeners
        WHERE id=? AND goal=? AND generation=? AND cursor_generation=?
          AND removed_at IS NULL AND failure IS NULL)
        """,
                    Boolean.class,
                    listener.id(),
                    listener.goal(),
                    listener.generation(),
                    listener.cursor())));
  }

  private static Optional<Input> readInput(ResultSet rows) throws SQLException {
    if (!rows.next()) return Optional.empty();
    long sequence = rows.getLong("sequence");
    String message = rows.getString("message");
    GoalSchedule schedule = scheduled(rows);
    UUID batch = rows.getObject("batch", UUID.class);
    if (batch == null) return Optional.of(new Input(sequence, message, schedule, null, null));
    var source = listener(rows, 0);
    var events = new ArrayList<Event>();
    do {
      events.add(
          new Event(rows.getLong("position"), rows.getString("event_id"), rows.getString("body")));
    } while (rows.next());
    return Optional.of(
        new Input(sequence, message, schedule, source, new RelayBatch(batch, events)));
  }

  @Override
  public void claim(Input input, String job) {
    int updated =
        jdbc.update(
            """
        UPDATE goal_agent_inputs SET claimed_job=? WHERE sequence=? AND NOT settled AND claimed_job IS NULL
        """,
            job,
            input.sequence());
    if (updated != 1) throw new IllegalStateException("Goal Agent input ownership changed");
    if (input.batch() != null
        && jdbc.update(
                "UPDATE goal_agent_relay_batches SET state='CLAIMED',job=? WHERE id=? AND state='READY'",
                job,
                input.batch().id())
            != 1) throw new IllegalStateException("Goal Agent batch ownership changed");
  }

  @Override
  public void settle(String job, Disposition disposition) {
    jdbc.update(
        """
        WITH handled AS (
          UPDATE goal_agent_relay_batches SET state='SETTLED' WHERE job=? AND state='CLAIMED'
          RETURNING id
        ), owned_inputs AS (
          SELECT sequence FROM goal_agent_inputs WHERE claimed_job=? AND batch IS NULL
          UNION ALL
          SELECT i.sequence FROM handled b JOIN goal_agent_inputs i ON i.batch=b.id
        )
        UPDATE goal_agent_inputs i SET settled=TRUE,disposition=?
          FROM owned_inputs owned WHERE i.sequence=owned.sequence AND NOT i.settled
        """,
        job,
        job,
        disposition.name());
  }

  @Override
  public void end(UUID goal) {
    jdbc.update(
        "UPDATE goal_agent_listeners SET removed_at=clock_timestamp() WHERE goal=? AND removed_at IS NULL",
        goal);
    disarm(goal, null, Disposition.GOAL_ENDED);
    jdbc.update(
        """
        UPDATE goal_agent_inputs SET settled=TRUE,disposition='GOAL_ENDED'
        WHERE goal=? AND NOT settled AND claimed_job IS NULL AND message IS NOT NULL
        """,
        goal);
  }

  @Override
  public void park(UUID goal) {
    jdbc.update(
        "UPDATE conversation_goals SET cycle=NULL,cycle_start_spent=NULL,cycle_calls=NULL WHERE id=?",
        goal);
  }

  @Override
  public void extendCycle(UUID goal, int calls) {
    if (jdbc.update(
            "UPDATE conversation_goals SET cycle_calls=? WHERE id=? AND cycle IS NOT NULL AND cycle_calls<?",
            calls,
            goal,
            calls)
        != 1) throw new IllegalStateException("The Goal cycle changed before extension");
  }

  private void disarm(UUID goal, UUID listener, Disposition disposition) {
    jdbc.update(
        """
        WITH disarmed AS (
          UPDATE goal_agent_relay_batches b SET state='DISARMED'
          FROM goal_agent_listeners l WHERE l.id=b.listener AND l.goal=?
            AND (?::uuid IS NULL OR l.id=?) AND b.state IN ('OPEN','READY')
          RETURNING b.id
        )
        UPDATE goal_agent_inputs SET settled=TRUE,disposition=?
        WHERE batch IN (SELECT id FROM disarmed) AND NOT settled
        """,
        goal,
        listener,
        listener,
        disposition.name());
  }

  @Override
  public Optional<Unlistened> unlistenReceipt(String actor, Unlisten request) {
    return jdbc
        .query(
            """
            SELECT goal,binding,prior_version FROM goal_agent_unlisten_requests
            WHERE executor=? AND request=?
            """,
            (row, index) -> {
              if (!request.goal().equals(row.getObject(1, UUID.class))
                  || !request.binding().equals(row.getObject(2, UUID.class))
                  || request.version() != row.getInt(3))
                throw new CallerFault("Request ID already belongs to a different listener removal");
              return new Unlistened(
                  request.goal(), request.binding(), request.version() + 1, request.requestId());
            },
            actor,
            request.requestId())
        .stream()
        .findFirst();
  }

  @Override
  public boolean recordUnlisten(String actor, Unlisten request) {
    return jdbc.update(
            """
        INSERT INTO goal_agent_unlisten_requests(executor,request,goal,binding,prior_version)
        VALUES(?,?,?,?,?) ON CONFLICT(executor,request) DO NOTHING
        """,
            actor,
            request.requestId(),
            request.goal(),
            request.binding(),
            request.version())
        == 1;
  }

  @Override
  public boolean remove(UUID goal, UUID listener) {
    if (jdbc.update(
            """
            UPDATE goal_agent_listeners SET removed_at=clock_timestamp()
            WHERE id=? AND goal=? AND removed_at IS NULL
            """,
            listener,
            goal)
        != 1) return false;
    disarm(goal, listener, Disposition.DISARMED);
    return true;
  }

  @Override
  public List<Listener> retirements(int limit) {
    return sql.within(
        ceiling,
        () ->
            jdbc.query(
                "SELECT "
                    + LISTENER_COLUMNS
                    + """
              FROM goal_agent_listeners l
              WHERE l.removed_at IS NOT NULL AND l.retired_at IS NULL ORDER BY l.id LIMIT ?
              """,
                JdbcGoalAgentStore::listener,
                limit));
  }

  @Override
  public boolean lockRetirement(Listener listener) {
    return !jdbc.queryForList(
            """
        SELECT g.id FROM conversation_goals g JOIN goal_agent_listeners l ON l.goal=g.id
        WHERE g.id=? AND l.id=? AND l.removed_at IS NOT NULL AND l.retired_at IS NULL
        FOR UPDATE OF g
        """,
            UUID.class,
            listener.goal(),
            listener.id())
        .isEmpty();
  }

  @Override
  public void retired(Listener listener) {
    jdbc.update(
        "UPDATE goal_agent_listeners SET retired_at=clock_timestamp() WHERE id=? AND removed_at IS NOT NULL AND retired_at IS NULL",
        listener.id());
  }

  @Override
  public List<AddressedSettlement> addressedSettlements(int limit) {
    return sql.within(
        ceiling,
        () ->
            jdbc.query(
                """
        SELECT sequence,message,disposition FROM goal_agent_inputs WHERE settled AND message IS NOT NULL
        ORDER BY sequence LIMIT ?
        """,
                (row, index) ->
                    new AddressedSettlement(
                        row.getLong(1), row.getString(2), Disposition.valueOf(row.getString(3))),
                limit));
  }

  @Override
  public void acknowledged(long sequence) {
    sql.within(
        ceiling,
        () ->
            jdbc.update(
                "DELETE FROM goal_agent_inputs WHERE sequence=? AND settled AND message IS NOT NULL",
                sequence));
  }

  private static GoalSchedule scheduled(ResultSet row) throws SQLException {
    String firing = row.getString("schedule_firing");
    return firing == null
        ? null
        : new GoalSchedule(
            row.getString("schedule"),
            firing,
            row.getObject("schedule_generation", UUID.class),
            row.getObject("fire_at", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime timestamp(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static Listener listener(ResultSet row, int index) throws SQLException {
    return new Listener(
        row.getObject("id", UUID.class),
        row.getObject("goal", UUID.class),
        new Relay.TopicKey(row.getLong("project_id"), row.getString("topic")),
        row.getObject("generation", UUID.class),
        row.getObject("cursor_generation", UUID.class),
        row.getInt("batch_count") == 1
            ? null
            : new Batch(row.getInt("batch_count"), row.getInt("max_delay_seconds")));
  }
}
