package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.relay.DurableRelay;
import io.aeyer.plowshare.server.relay.JdbcRelayRepository;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import io.aeyer.plowshare.server.relay.RelayRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL is required for atomic schedule/firing/log rollback and guarded claim races. */
@Tag("full-db")
@Testcontainers
class RelayScheduledPublicationDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Instant AT = Instant.parse("2026-10-05T01:00:00Z");
  private static final CronSchedule CRON = CronSchedule.parse("0 0 * * * *", "UTC");
  private static JdbcTemplate jdbc;
  private static UnitOfWork work;
  private ScheduleStore schedules;
  private TriggerStore triggers;
  private FiringStore firings;
  private RelayRepository repository;
  private Relay relay;
  private Dispatcher dispatcher;
  private Intake intake;

  @BeforeAll
  static void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
    var template = new TransactionTemplate(new DataSourceTransactionManager(source));
    work =
        new UnitOfWork() {
          public <T> T inTransaction(Supplier<T> action) {
            return Objects.requireNonNull(template.execute(status -> action.get()));
          }
        };
  }

  @BeforeEach
  void fresh() {
    jdbc.execute("TRUNCATE relay_topics, firings, triggers, schedules, projects, admins CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin) VALUES('operator','fixture',true)");
    schedules = new JdbcScheduleStore(jdbc);
    triggers = new JdbcTriggerStore(jdbc);
    firings = new JdbcFiringStore(jdbc);
    repository = new JdbcRelayRepository(jdbc, work);
    relay = new DurableRelay(repository, () -> AT);
    dispatcher = mock(Dispatcher.class);
    intake = new Intake(triggers, firings, dispatcher, () -> AT);
  }

  private ScheduleRecord schedule(String name, String event) {
    return schedules.define(name, CRON, event, "operator", AT.minusSeconds(1));
  }

  private TriggerRecord trigger(String name, String event, int cap) {
    return triggers.define(
        new TriggerRecord(
            name, event, null, null, "fixture-bot", "task", 1, 1, cap, false, "operator"));
  }

  private ScheduledPublication publisher(Relay broker, ScheduledArrival arrivals) {
    return new RelayScheduledPublication(
        schedules,
        broker,
        arrivals,
        work,
        () -> {
          if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("job dispatch must not hold a transaction");
        });
  }

  private static Relay.TopicKey system() {
    return new Relay.TopicKey(Relay.SystemScope.SERVER, "schedule.due");
  }

  @Test
  void different_topic_scopes_serialize_the_shared_trigger_queue_before_commit() throws Exception {
    long project =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO projects(name,workspace) VALUES('fixture-project','fixture') RETURNING id",
                Long.class));
    long source =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO schedule_sources(account,project_id,source) VALUES('operator',?,'workspace') RETURNING id",
                Long.class,
                project));
    var managed = schedule("managed", "daily");
    var global = schedule("global", "daily");
    jdbc.update("UPDATE schedules SET file_managed=true WHERE name='managed'");
    jdbc.update(
        "INSERT INTO schedule_files(source_id,name,internal_name,definition,status) VALUES(?,'managed','managed','{}'::jsonb,'active')",
        source);
    trigger("listener", "daily", 1);
    var entrants = new CountDownLatch(2);
    var queued = spy(firings);
    doAnswer(
            call -> {
              entrants.countDown();
              assertTrue(entrants.await(10, TimeUnit.SECONDS));
              return call.callRealMethod();
            })
        .when(queued)
        .lockScheduledQueues(anyList());
    var scheduled = publisher(relay, new Intake(triggers, queued, dispatcher, () -> AT));
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var a = threads.submit(() -> scheduled.publish(managed, AT.plusSeconds(3600), AT));
      var b = threads.submit(() -> scheduled.publish(global, AT.plusSeconds(3600), AT));
      assertTrue(a.get(15, TimeUnit.SECONDS));
      assertTrue(b.get(15, TimeUnit.SECONDS));
    }
    assertEquals(1, relay.topic(system()).lastPosition());
    assertEquals(1, relay.topic(new Relay.TopicKey(project, "schedule.due")).lastPosition());
    assertEquals(1, firings.list("listener", "queued", 0, 10).size());
    assertEquals(1, firings.list("listener", "superseded", 0, 10).size());
  }

  @Test
  void failures_after_append_partial_fanout_and_cursor_write_roll_back_everything() {
    trigger("first", "daily", 2);
    trigger("second", "daily", 2);
    var selected = schedule("morning", "daily");
    Relay broken = spy(relay);
    doAnswer(
            call -> {
              call.callRealMethod();
              throw new IllegalStateException("after append");
            })
        .when(broken)
        .publish(any(), any());
    assertThrows(
        IllegalStateException.class,
        () -> publisher(broken, intake).publish(selected, AT.plusSeconds(3600), AT));
    assertRolledBack(selected);

    FiringStore partial = spy(firings);
    doThrow(new IllegalStateException("second firing"))
        .when(partial)
        .arrive(
            eq("daily"),
            any(),
            eq("morning"),
            eq(AT),
            argThat(t -> t != null && t.name().equals("second")),
            eq(AT));
    var partialIntake = new Intake(triggers, partial, dispatcher, () -> AT);
    assertThrows(
        IllegalStateException.class,
        () -> publisher(relay, partialIntake).publish(selected, AT.plusSeconds(3600), AT));
    assertRolledBack(selected);

    Relay failedCursor = spy(relay);
    doAnswer(
            call -> {
              call.callRealMethod();
              throw new IllegalStateException("after cursor");
            })
        .when(failedCursor)
        .advanceSeen(any(), anyLong());
    assertThrows(
        IllegalStateException.class,
        () -> publisher(failedCursor, intake).publish(selected, AT.plusSeconds(3600), AT));
    assertRolledBack(selected);
    verifyNoInteractions(dispatcher);
    assertTrue(publisher(relay, intake).publish(selected, AT.plusSeconds(3600), AT));
    assertEquals(2, firings.list(null, null, 0, 10).size());
  }

  private void assertRolledBack(ScheduleRecord selected) {
    assertEquals(selected, schedules.find(selected.name()).orElseThrow());
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM relay_topics", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM relay_publications", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM relay_subscriptions", Integer.class));
    assertTrue(firings.list(null, null, 0, 10).isEmpty());
  }

  @Test
  void committed_fanout_queue_policy_and_independent_readers_survive_topic_eviction() {
    var t = trigger("listener", "daily", 1);
    doAnswer(
            call -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertTrue(relay.topic(system()).lastPosition() > 0);
              return null;
            })
        .when(dispatcher)
        .dispatch(any(), eq(t));
    var scheduled = publisher(relay, intake);
    for (int i = 0; i < 3; i++) {
      Instant due = AT.plusSeconds(3600L * i);
      var selected = schedules.define("morning", CRON, "daily", "operator", due.minusSeconds(1));
      assertTrue(scheduled.publish(selected, due.plusSeconds(3600), due));
    }
    var builtin = new Relay.SubscriptionKey(system(), "builtin.scheduling");
    assertEquals(3, relay.read(builtin, 10).subscription().seenThrough());
    var viewer = new Relay.SubscriptionKey(system(), "independent.listener");
    relay.subscribe(viewer, Relay.Start.OLDEST_RETAINED);
    assertEquals(3, relay.read(viewer, 10).publications().size());
    assertEquals(1, firings.list("listener", "queued", 0, 10).size());
    assertEquals(2, firings.list("listener", "superseded", 0, 10).size());
    relay.configureTopic(
        system(), RelayPayload.Kind.SCHEDULE_DUE, new Relay.Policy(Duration.ofSeconds(1), 1L));
    relay.registerTopic(system(), RelayPayload.Kind.SCHEDULE_DUE, Relay.Policy.systemDefault());
    assertEquals(Duration.ofSeconds(1), relay.topic(system()).policy().retention());
    assertEquals(3, repository.prune(system(), AT.plusSeconds(2), 100));
    assertTrue(relay.read(viewer, 10).gap().isPresent());
    assertTrue(relay.read(builtin, 10).gap().isEmpty());
    assertEquals(3, firings.list("listener", null, 0, 10).size());
    assertEquals(List.of(t.target()), firings.targetsWaiting());
    assertEquals(List.of(t.target()), firings.scheduledTargetsWaiting());
    publisher(relay, intake).recover();
    verify(dispatcher).drain(t.target());
    var pending = firings.list("listener", "queued", 0, 10).getFirst();
    assertTrue(firings.claimStart(pending.id(), AT.plusSeconds(3)));
    assertTrue(firings.scheduledTargetsWaiting().isEmpty());
  }

  @Test
  void concurrent_claims_publish_once_and_revoked_or_changed_definitions_publish_nothing()
      throws Exception {
    var selected = schedule("morning", "daily");
    var scheduled = publisher(relay, intake);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      java.util.concurrent.Callable<Boolean> fire =
          () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS))
              throw new IllegalStateException("race start timed out");
            return scheduled.publish(selected, AT.plusSeconds(3600), AT);
          };
      var a = threads.submit(fire);
      var b = threads.submit(fire);
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
    }
    assertEquals(1, relay.topic(system()).lastPosition());
    assertEquals(1, firings.list(null, "unmatched", 0, 10).size());
    var stale = schedule("changed", "daily");
    schedules.define("changed", CRON, "different", "operator", AT.minusSeconds(1));
    assertFalse(scheduled.publish(stale, AT.plusSeconds(3600), AT));
    var denied = schedule("revoked", "daily");
    jdbc.update("UPDATE admins SET enabled=false WHERE handle='operator'");
    assertFalse(scheduled.publish(denied, AT.plusSeconds(3600), AT));
    assertEquals(1, relay.topic(system()).lastPosition());
    assertEquals(AT, schedules.find("revoked").orElseThrow().nextFireAt());
  }

  @Test
  void project_file_sources_keep_their_scope_and_live_source_authority() {
    long project =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO projects(name,workspace) VALUES('fixture-project','fixture') RETURNING id",
                Long.class));
    long source =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO schedule_sources(account,project_id,source) VALUES('operator',?,'workspace') RETURNING id",
                Long.class,
                project));
    var selected = schedule("managed", "daily");
    jdbc.update("UPDATE schedules SET file_managed=true WHERE name='managed'");
    jdbc.update(
        "INSERT INTO schedule_files(source_id,name,internal_name,definition,status) VALUES(?,'managed','managed','{}'::jsonb,'active')",
        source);
    assertTrue(publisher(relay, intake).publish(selected, AT.plusSeconds(3600), AT));
    var topic = new Relay.TopicKey(project, "schedule.due");
    assertEquals(1, relay.topic(topic).lastPosition());
    assertThrows(IllegalStateException.class, () -> relay.topic(system()));
    var next = schedules.find("managed").orElseThrow();
    jdbc.update("UPDATE schedule_files SET status='refused' WHERE source_id=?", source);
    assertFalse(publisher(relay, intake).publish(next, AT.plusSeconds(7200), AT.plusSeconds(3600)));
    assertEquals(1, relay.topic(topic).lastPosition());
  }

  @Test
  void an_existing_tick_does_not_abort_the_owning_publication_transaction() {
    var t = trigger("listener", "daily", 1);
    var selected = schedule("morning", "daily");
    var original =
        firings
            .arrive("daily", new EventPayload.Scheduled("morning", AT), "morning", AT, t, AT)
            .orElseThrow();
    assertTrue(publisher(relay, intake).publish(selected, AT.plusSeconds(3600), AT));
    assertEquals(List.of(original), firings.list(null, null, 0, 10));
    assertEquals(
        1,
        relay
            .read(new Relay.SubscriptionKey(system(), "builtin.scheduling"), 10)
            .subscription()
            .seenThrough());
    verifyNoInteractions(dispatcher);
    assertFalse(publisher(relay, intake).publish(selected, AT.plusSeconds(3600), AT));
  }
}
