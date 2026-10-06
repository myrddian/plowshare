package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.board.MessageWake;
import io.aeyer.plowshare.server.board.SeatWake;
import io.aeyer.plowshare.server.events.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Source triggers, native inbox fences and expiry recovery require actual PostgreSQL transactions.
 */
@Tag("full-db")
@Testcontainers
class RelayLifecycleDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private static UnitOfWork work;
  private long project;
  private RelayRepository repository;
  private Relay relay;
  private RelayConsumerRepository consumers;
  private RelayNativeRepository nativeInboxes;
  private RelaySourceRepository sources;
  private FiringStore firings;
  private final Instant at = Instant.now();

  @BeforeAll
  static void migrate() {
    var data =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(data).load().migrate();
    jdbc = new JdbcTemplate(data);
    var template =
        new org.springframework.transaction.support.TransactionTemplate(
            new DataSourceTransactionManager(data));
    work =
        new UnitOfWork() {
          public <T> T inTransaction(java.util.function.Supplier<T> action) {
            return Objects.requireNonNull(template.execute(status -> action.get()));
          }
        };
  }

  @BeforeEach
  void fresh() {
    jdbc.execute("TRUNCATE relay_source_events,relay_topics,projects,admins CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin) VALUES('operator','fixture',true)");
    project =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO projects(name,workspace) VALUES('fixture','fixture') RETURNING id",
                Long.class));
    jdbc.update(
        "INSERT INTO conversations(id,project_id,created_at,budget_total,budget_spent,owner_handle,origin) VALUES('cnv_fixture',?,clock_timestamp(),100,0,'operator','turn')",
        project);
    jdbc.update(
        """
        INSERT INTO board_topics(id,project,root,depth,title,label,account,opener_kind,opener,state,pot_total,pot_spent,reserve,opened_at)
        VALUES('brd_fixture','fixture','brd_fixture',0,'fixture','fixture','operator','person','operator','open',100,0,1,clock_timestamp())
        """);
    jdbc.update("DELETE FROM relay_source_events");
    repository = new JdbcRelayRepository(jdbc, work);
    relay = new DurableRelay(repository, Instant::now);
    consumers =
        new JdbcRelayConsumerRepository(jdbc, work, new JdbcRelayDeliveryRepository(jdbc, work));
    firings = new JdbcFiringStore(jdbc);
    nativeInboxes = new JdbcRelayNativeRepository(jdbc, work, relay, consumers, firings);
    sources = new JdbcRelaySourceRepository(jdbc, work, relay, nativeInboxes);
  }

  private Relay.TopicKey topic(String name) {
    return new Relay.TopicKey(project, name);
  }

  private Relay.TopicKey wakes(boolean message) {
    return new Relay.TopicKey(
        Relay.SystemScope.SERVER, message ? "message.wake.requested" : "board.wake.requested");
  }

  private FiringRecord wake(boolean message) {
    return firings
        .owe(
            "brd_fixture",
            "conversation:cnv_fixture",
            message
                ? new EventPayload.Message(new MessageWake("bdm_fixture"))
                : new EventPayload.Seat(new SeatWake("opened", null, null, null)),
            at)
        .orElseThrow();
  }

  private void publishAll() {
    for (int pass = 0; pass < 20; pass++) {
      var topics = sources.pendingTopics(null, 256);
      if (topics.isEmpty()) return;
      for (var topic : topics) while (sources.publishNext(topic)) {}
    }
    fail("outbox did not drain");
  }

  private RelayNativeRepository.Binding binding() {
    return nativeInboxes.ready(null, 256).getFirst();
  }

  private RelayConsumerRepository.Lease lease(RelayNativeRepository.Binding binding) {
    return consumers
        .acquire(binding.subscription(), "worker", binding.account(), Duration.ofMinutes(1))
        .orElseThrow();
  }

  @Test
  void causal_job_capture_is_atomic_and_survives_completion_pruning_and_publisher_restart() {
    var cause = RelayCausation.root("external-event").next("external-event", 8);
    var jobs = new io.aeyer.plowshare.server.archive.JdbcJobLog(jdbc);
    assertThrows(
        IllegalStateException.class,
        () ->
            work.inTransaction(
                () -> {
                  jobs.started(
                      "job_rollback",
                      "fixture",
                      io.aeyer.plowshare.protocol.Home.of("fixture"),
                      at,
                      "cnv_fixture",
                      cause);
                  throw new IllegalStateException("rollback");
                }));
    assertNull(
        jdbc.queryForObject(
            "SELECT relay_causation::text FROM conversations WHERE id='cnv_fixture'",
            String.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM relay_source_events", Integer.class));
    work.inTransaction(
        () -> {
          jobs.started(
              "job_causal",
              "fixture",
              io.aeyer.plowshare.protocol.Home.of("fixture"),
              at,
              "cnv_fixture",
              cause);
          assertTrue(
              jobs.ended(
                  "job_causal",
                  io.aeyer.plowshare.server.agents.Outcome.Ending.ANSWERED,
                  1,
                  1,
                  at.plusSeconds(1)));
          return true;
        });
    assertEquals(
        cause,
        RelayCausationCodec.read(
            jdbc.queryForObject(
                "SELECT relay_causation::text FROM conversations WHERE id='cnv_fixture'",
                String.class)));
    assertEquals(
        List.of(cause, cause),
        jdbc.query(
            "SELECT relay_causation::text FROM relay_source_events ORDER BY sequence",
            (row, index) -> RelayCausationCodec.read(row.getString(1))));
    // The owning job can be pruned before the stalled outbox publisher recovers.
    jdbc.update("DELETE FROM jobs WHERE id='job_causal'");
    sources =
        new JdbcRelaySourceRepository(
            jdbc,
            work,
            new DurableRelay(new JdbcRelayRepository(jdbc, work), Instant::now),
            nativeInboxes);
    publishAll();
    var group = new Relay.SubscriptionKey(topic("job.ended"), "test.causation");
    relay.subscribe(group, Relay.Start.OLDEST_RETAINED);
    var event = relay.read(group, 1).publications().getFirst();
    assertEquals(cause, event.event().causation());
    assertEquals("external-event", event.event().causationId());
    var admissions = new JdbcRelayDeliveryRepository(jdbc, work);
    admissions.admit(
        new RelayDeliveries.AdmissionKey(group, event.position()),
        new RelayDeliveries.Decision(
            RelayDeliveries.SourcePin.of(
                "notices/routes.js", "export function route(){return [];}"),
            List.of(
                new RelayDeliveries.Branch(
                    "work", "agent.run", null, null, new RelayWork("worker", null, null)))),
        Instant.now());
    var claimed =
        admissions.claim(group, "new-worker", Duration.ofMinutes(1), Instant.now()).orElseThrow();
    assertEquals(cause, claimed.publication().event().causation());
    jdbc.update(
        "DELETE FROM relay_publications WHERE scope_key=? AND topic=?",
        RelayScopeCodec.write(group.topic()),
        group.topic().name());
    assertEquals(
        1, new JdbcRelayForwardingHistory(jdbc).depth(claimed.publication(), 8).orElseThrow());
  }

  @Test
  void orchestration_conductor_and_nested_work_inherit_the_same_causation() {
    var cause = new RelayCausation("root", "parent-event", 4);
    var store =
        new io.aeyer.plowshare.server.orchestrations.OrchestrationStore(jdbc, Instant::now, work);
    var run =
        store.insert(
            new io.aeyer.plowshare.server.orchestrations.OrchestrationStore.NewOrchestration(
                "fixture",
                io.aeyer.plowshare.server.agents.OrchestrationDefinition.Tier.PROJECT,
                "a".repeat(64),
                "fixture",
                "fixture",
                List.of(),
                0,
                "fixture",
                "cnv_fixture",
                null,
                "fixture",
                "operator",
                null,
                null,
                0,
                cause));
    var jobs = new io.aeyer.plowshare.server.archive.JdbcJobLog(jdbc);
    jobs.started(
        "job_conductor",
        "fixture",
        io.aeyer.plowshare.protocol.Home.of("fixture"),
        at,
        "cnv_fixture",
        null);
    assertEquals(
        cause,
        RelayCausationCodec.read(
            jdbc.queryForObject(
                "SELECT relay_causation::text FROM jobs WHERE id='job_conductor'", String.class)));
    jdbc.update(
        "INSERT INTO conversations(id,project_id,created_at,budget_total,budget_spent,owner_handle,origin) VALUES('cnv_child',?,clock_timestamp(),100,0,'operator','turn')",
        project);
    var child =
        store.insert(
            new io.aeyer.plowshare.server.orchestrations.OrchestrationStore.NewOrchestration(
                "fixture",
                io.aeyer.plowshare.server.agents.OrchestrationDefinition.Tier.PROJECT,
                "a".repeat(64),
                "fixture",
                "fixture",
                List.of(),
                0,
                "fixture",
                "cnv_child",
                "cnv_fixture",
                "fixture",
                "operator",
                null,
                run.id(),
                1));
    assertEquals(
        cause,
        RelayCausationCodec.read(
            jdbc.queryForObject(
                "SELECT relay_causation::text FROM orchestrations WHERE id=?",
                String.class,
                child.id())));
    jdbc.update(
        "UPDATE orchestrations SET state='cancelled',ended_at=clock_timestamp() WHERE id=?",
        child.id());
    publishAll();
    var group = new Relay.SubscriptionKey(topic("orchestration.ended"), "test.causation");
    relay.subscribe(group, Relay.Start.OLDEST_RETAINED);
    assertEquals(cause, relay.read(group, 1).publications().getFirst().event().causation());
  }

  @Test
  void independent_jobs_get_roots_and_inconsistent_causation_rolls_back_without_notices() {
    var jobs = new io.aeyer.plowshare.server.archive.JdbcJobLog(jdbc);
    jobs.started(
        "job_independent",
        "fixture",
        io.aeyer.plowshare.protocol.Home.of("fixture"),
        at,
        "cnv_fixture",
        null);
    assertEquals(
        RelayCausation.root("job:job_independent"),
        RelayCausationCodec.read(
            jdbc.queryForObject(
                "SELECT relay_causation::text FROM jobs WHERE id='job_independent'",
                String.class)));
    assertThrows(
        RuntimeException.class,
        () ->
            jobs.started(
                "job_conflict",
                "fixture",
                io.aeyer.plowshare.protocol.Home.of("fixture"),
                at,
                "cnv_fixture",
                new RelayCausation("different", "parent", 2)));
    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM jobs WHERE id='job_conflict'", Integer.class));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM relay_source_events", Integer.class));
  }

  @Test
  void
      source_capture_rolls_back_with_the_owning_change_and_publication_rolls_back_with_outbox_removal() {
    assertThrows(
        IllegalStateException.class,
        () ->
            work.inTransaction(
                () -> {
                  jdbc.update(
                      "INSERT INTO jobs(id,agent,project_id,started_at) VALUES('job_rollback','fixture',?,clock_timestamp())",
                      project);
                  throw new IllegalStateException("rollback");
                }));
    assertTrue(sources.pendingTopics(null, 10).isEmpty());
    jdbc.update(
        "INSERT INTO jobs(id,agent,project_id,started_at) VALUES('job_fixture','fixture',?,clock_timestamp())",
        project);
    assertThrows(
        IllegalStateException.class,
        () ->
            work.inTransaction(
                () -> {
                  assertTrue(sources.publishNext(topic("job.started")));
                  throw new IllegalStateException("rollback");
                }));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM relay_source_events", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM relay_publications", Integer.class));
    assertTrue(sources.publishNext(topic("job.started")));
    assertFalse(sources.publishNext(topic("job.started")));
    jdbc.update(
        "UPDATE jobs SET ending='CANCELLED',ended_at=clock_timestamp(),steps=0,model_calls=0 WHERE id='job_fixture'");
    jdbc.update("UPDATE jobs SET ending=ending,ended_at=ended_at WHERE id='job_fixture'");
    publishAll();
    var group = new Relay.SubscriptionKey(topic("job.ended"), "test.lifecycle");
    relay.subscribe(group, Relay.Start.OLDEST_RETAINED);
    var events = relay.read(group, 10).publications();
    assertEquals(1, events.size());
    assertEquals(
        "CANCELLED", ((RelayPayload.Lifecycle) events.getFirst().event().payload()).state());
  }

  @Test
  void wake_binding_publication_and_source_removal_are_atomic_and_continuations_keep_priority() {
    var ordinary = wake(true);
    var continuation =
        firings
            .owe(
                "brd_fixture",
                ordinary.target(),
                new EventPayload.Message(
                    new MessageWake(
                        "bdm_fixture",
                        MessageWake.Continuation.APPROVAL,
                        "apr_fixture",
                        "private command",
                        null)),
                at.plusSeconds(1))
            .orElseThrow();
    assertTrue(nativeInboxes.routes(ordinary.target()));
    assertThrows(
        IllegalStateException.class,
        () ->
            work.inTransaction(
                () -> {
                  sources.publishNext(wakes(true));
                  throw new IllegalStateException("rollback");
                }));
    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM relay_native_consumers", Integer.class));
    publishAll();
    var binding = binding();
    var lease = lease(binding);
    assertFalse(nativeInboxes.claim(binding, lease, ordinary.id(), at));
    assertTrue(nativeInboxes.claim(binding, lease, continuation.id(), at));
    assertFalse(nativeInboxes.claim(binding, lease, continuation.id(), at));
    assertFalse(nativeInboxes.claim(binding, lease, ordinary.id(), at));
    assertEquals("queued", firings.find(ordinary.id()).orElseThrow().status());
    firings.finish(continuation.id(), at.plusSeconds(2));
    assertTrue(nativeInboxes.claim(binding, lease, ordinary.id(), at.plusSeconds(2)));
    assertFalse(
        jdbc.queryForObject(
                "SELECT string_agg(payload::text,'') FROM relay_publications", String.class)
            .contains("private command"));
  }

  @Test
  void stale_native_lease_cannot_advance_or_start_and_a_successor_resumes_the_same_inbox() {
    var firing = wake(false);
    publishAll();
    var binding = binding();
    var old = lease(binding);
    jdbc.update(
        "UPDATE relay_consumer_leases SET lease_until=clock_timestamp()-interval '1 second'");
    var current =
        consumers
            .acquire(binding.subscription(), "successor", "operator", Duration.ofMinutes(1))
            .orElseThrow();
    assertThrows(IllegalStateException.class, () -> nativeInboxes.advance(old, 1));
    assertThrows(
        IllegalStateException.class, () -> nativeInboxes.claim(binding, old, firing.id(), at));
    assertEquals(0, relay.read(binding.subscription(), 1).subscription().seenThrough());
    nativeInboxes.advance(current, 1);
    assertTrue(nativeInboxes.claim(binding, current, firing.id(), at));
    assertEquals(1, relay.read(binding.subscription(), 1).subscription().seenThrough());
  }

  @Test
  void expired_notice_gap_recovers_from_the_owning_inbox_with_an_explicit_audit() {
    var firing = wake(false);
    publishAll();
    var binding = binding();
    var lease = lease(binding);
    relay.configureTopic(
        wakes(false),
        RelayPayload.Kind.WAKE_REQUESTED,
        new Relay.Policy(Duration.ofSeconds(1), null));
    repository.prune(wakes(false), Instant.now().plusSeconds(2), 100);
    assertTrue(relay.read(binding.subscription(), 10).gap().isPresent());
    assertThrows(IllegalArgumentException.class, () -> nativeInboxes.recoverGap(lease, 2));
    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM relay_native_gap_recoveries", Integer.class));
    nativeInboxes.recoverGap(lease, 1);
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT pending_inbox FROM relay_native_gap_recoveries", Integer.class));
    assertTrue(nativeInboxes.claim(binding, lease, firing.id(), at));
    assertTrue(relay.read(binding.subscription(), 10).gap().isEmpty());
    var log = new JdbcRelayLogRepository(jdbc, work).read(wakes(false), 0, 10, "operator");
    assertEquals(1, log.recoveries().size());
    assertEquals(1, log.recoveries().getFirst().pendingInbox());
    assertEquals(binding.subscription().subscriber(), log.recoveries().getFirst().subscriber());
    jdbc.update(
        "UPDATE relay_native_gap_recoveries SET recovered_at=clock_timestamp()-interval '100 days'");
    assertEquals(1, nativeInboxes.prune(Duration.ofDays(90), 1));
  }

  @Test
  void parallel_publishers_append_once_and_replica_consumers_start_once_outside_the_transaction()
      throws Exception {
    var firing = wake(false);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> sources.publishNext(wakes(false)));
      var second = executor.submit(() -> sources.publishNext(wakes(false)));
      first.get(5, TimeUnit.SECONDS);
      second.get(5, TimeUnit.SECONDS);
    }
    assertEquals(1, relay.topic(wakes(false)).lastPosition());
    var owned = binding();
    var accepted = new AtomicInteger();
    var started = new CountDownLatch(1);
    var dispatcher =
        new Dispatcher(
            firings,
            mock(TriggerStore.class),
            mock(Dispatcher.Runner.class),
            mock(Inbox.class),
            Instant::now);
    dispatcher.useWakes(
        new Dispatcher.Wakes() {
          public boolean busy(FiringRecord wake) {
            return false;
          }

          public String start(
              FiringRecord wake,
              java.util.function.BiConsumer<String, io.aeyer.plowshare.server.agents.Outcome>
                  ended) {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            accepted.incrementAndGet();
            started.countDown();
            return "job_native";
          }
        });
    var properties = new RelayWorkerProperties();
    properties.setIdleInterval(Duration.ofSeconds(1));
    var signals = new LocalRelayPublicationSignals();
    try (var workers =
            new RelayInternalWorkers(
                sources, nativeInboxes, consumers, relay, signals, dispatcher, properties, true);
        var replica =
            new RelayInternalWorkers(
                sources, nativeInboxes, consumers, relay, signals, dispatcher, properties, true)) {
      workers.start();
      replica.start();
      dispatcher.drain(firing.target());
      assertTrue(started.await(10, TimeUnit.SECONDS));
    }
    assertEquals(1, accepted.get());
    assertEquals("started", firings.find(firing.id()).orElseThrow().status());
    assertEquals(1, relay.read(owned.subscription(), 1).subscription().seenThrough());
  }

  @Test
  void approval_and_orchestration_notices_preserve_actual_transitions_without_private_text() {
    jdbc.update(
        """
        INSERT INTO run_approvals(id,project_id,conversation,asked_in,agent,side,argv,cwd,state,created_at)
        VALUES('apr_fixture',?,'cnv_fixture','job_fixture','fixture','server','["private command"]'::jsonb,'private path','asked',clock_timestamp())
        """,
        project);
    jdbc.update(
        "UPDATE run_approvals SET state='denied',answered_at=clock_timestamp() WHERE id='apr_fixture'");
    jdbc.update(
        """
        INSERT INTO orchestrations(id,definition_name,tier,definition_hash,stages,max_returns,project,conductor_conversation,caller_agent,state,created_at,definition_source,definition_origin,depth)
        VALUES('orc_fixture','fixture','PROJECT',?,'[]'::jsonb,0,'fixture','cnv_fixture','fixture','running',clock_timestamp(),'fixture','fixture',0)
        """,
        "a".repeat(64));
    jdbc.update(
        """
        INSERT INTO orchestration_messages(id,orchestration,kind,text,author,created_at)
        VALUES('msg_question','orc_fixture','question','private question','fixture',clock_timestamp()),
          ('msg_answer','orc_fixture','answer','private answer','fixture',clock_timestamp())
        """);
    jdbc.update(
        "UPDATE orchestrations SET state='failed',failure='private failure',ended_at=clock_timestamp() WHERE id='orc_fixture'");
    jdbc.update(
        "UPDATE orchestrations SET state='running',failure=NULL,ended_at=NULL WHERE id='orc_fixture'");
    jdbc.update(
        "UPDATE orchestrations SET state='cancelled',ended_at=clock_timestamp() WHERE id='orc_fixture'");
    publishAll();
    for (String name :
        List.of(
            "approval.requested",
            "approval.resolved",
            "orchestration.started",
            "orchestration.resumed",
            "orchestration.question.asked",
            "orchestration.question.answered",
            "orchestration.ended")) assertTrue(relay.topic(topic(name)).lastPosition() > 0);
    assertEquals(2, relay.topic(topic("orchestration.ended")).lastPosition());
    assertFalse(
        jdbc.queryForObject(
                "SELECT string_agg(payload::text,'') FROM relay_publications", String.class)
            .contains("private"));
    var group = new Relay.SubscriptionKey(topic("approval.resolved"), "test.lifecycle");
    relay.subscribe(group, Relay.Start.OLDEST_RETAINED);
    assertEquals(
        "denied",
        ((RelayPayload.Lifecycle) relay.read(group, 10).publications().getFirst().event().payload())
            .state());
  }

  @Test
  void ownership_expiring_while_waiting_for_native_target_rolls_back_the_start() throws Exception {
    var firing = wake(false);
    publishAll();
    var binding = binding();
    var lease =
        consumers
            .acquire(binding.subscription(), "worker", "operator", Duration.ofSeconds(1))
            .orElseThrow();
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var lock =
          executor.submit(
              () ->
                  work.inTransaction(
                      () -> {
                        jdbc.queryForObject(
                            "SELECT pg_advisory_xact_lock(hashtextextended('relay-native:'||?,0))",
                            (row, index) -> true,
                            binding.target());
                        held.countDown();
                        try {
                          assertTrue(release.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(interrupted);
                        }
                        return true;
                      }));
      assertTrue(held.await(3, TimeUnit.SECONDS));
      var claim = executor.submit(() -> nativeInboxes.claim(binding, lease, firing.id(), at));
      jdbc.execute("SELECT pg_sleep(1.2)");
      release.countDown();
      lock.get(5, TimeUnit.SECONDS);
      assertThrows(
          java.util.concurrent.ExecutionException.class, () -> claim.get(5, TimeUnit.SECONDS));
    } finally {
      release.countDown();
    }
    assertEquals("queued", firings.find(firing.id()).orElseThrow().status());
  }

  @Test
  void public_board_posts_resolution_and_close_emit_references_once() {
    jdbc.update(
        """
        INSERT INTO board_messages(id,topic,author_kind,author,kind,body,posted_at)
        VALUES('bdm_public','brd_fixture','person','operator','post','private content',clock_timestamp())
        """);
    jdbc.update(
        "UPDATE board_topics SET state='closed',closed_at=clock_timestamp(),resolution='bdm_public' WHERE id='brd_fixture'");
    jdbc.update("UPDATE board_topics SET state=state,resolution=resolution WHERE id='brd_fixture'");
    publishAll();
    for (String name : List.of("board.message.posted", "board.resolved", "board.closed"))
      assertEquals(1, relay.topic(topic(name)).lastPosition());
    assertFalse(
        jdbc.queryForObject(
                "SELECT string_agg(payload::text,'') FROM relay_publications", String.class)
            .contains("private content"));
  }

  @Test
  void native_cleanup_keeps_uncertain_inbox_claims_and_bounds_recovery_audits() {
    var firing = wake(false);
    publishAll();
    var binding = binding();
    var lease = lease(binding);
    assertTrue(nativeInboxes.claim(binding, lease, firing.id(), at));
    consumers.release(lease);
    jdbc.update("DELETE FROM conversations WHERE id='cnv_fixture'");
    assertEquals(0, nativeInboxes.prune(Duration.ofDays(90), 10));
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM relay_native_consumers", Integer.class));
    firings.finish(firing.id(), at.plusSeconds(1));
    assertEquals(1, nativeInboxes.prune(Duration.ofDays(90), 10));
    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM relay_native_consumers", Integer.class));
  }

  @Test
  void upgrading_maps_only_queued_native_wakes_without_manufacturing_lifecycle_history() {
    jdbc.execute("CREATE DATABASE relay_upgrade");
    var data =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/relay_upgrade"),
            POSTGRES.getUsername(),
            POSTGRES.getPassword());
    Flyway.configure().dataSource(data).target("123").load().migrate();
    var old = new JdbcTemplate(data);
    old.update(
        "INSERT INTO admins(handle,password_hash,server_admin) VALUES('operator','fixture',true)");
    long id =
        Objects.requireNonNull(
            old.queryForObject(
                "INSERT INTO projects(name,workspace) VALUES('fixture','fixture') RETURNING id",
                Long.class));
    old.update(
        """
        INSERT INTO board_topics(id,project,root,depth,title,label,account,opener_kind,opener,state,pot_total,pot_spent,reserve,opened_at)
        VALUES('brd_upgrade','fixture','brd_upgrade',0,'fixture','fixture','operator','person','operator','open',100,0,1,clock_timestamp())
        """);
    old.update(
        "INSERT INTO jobs(id,agent,project_id,started_at) VALUES('job_historical','fixture',?,clock_timestamp())",
        id);
    var store = new JdbcFiringStore(old);
    var queued =
        store
            .owe(
                "brd_upgrade",
                "conversation:queued",
                new EventPayload.Seat(new SeatWake("opened", null, null, null)),
                at)
            .orElseThrow();
    var uncertain =
        store
            .owe(
                "brd_upgrade",
                "conversation:uncertain",
                new EventPayload.Seat(new SeatWake("opened", null, null, null)),
                at)
            .orElseThrow();
    assertTrue(store.claimStart(uncertain.id(), at));
    Flyway.configure().dataSource(data).load().migrate();
    assertEquals(
        List.of(queued.id()),
        old.queryForList(
            "SELECT subject FROM relay_source_events ORDER BY sequence", String.class));
    assertEquals("started", store.find(uncertain.id()).orElseThrow().status());
    assertEquals(
        -1,
        RelayCausationCodec.read(
                old.queryForObject(
                    "SELECT relay_causation::text FROM jobs WHERE id='job_historical'",
                    String.class))
            .depth());
    assertEquals(
        -1,
        RelayCausationCodec.read(
                old.queryForObject(
                    "SELECT relay_causation::text FROM relay_source_events WHERE subject=?",
                    String.class,
                    queued.id()))
            .depth());
  }

  @Test
  void private_mailbox_creation_is_not_exposed_as_a_project_board_notice() {
    work.inTransaction(
        () -> {
          jdbc.update(
              """
          INSERT INTO board_topics(id,project,root,depth,title,label,account,opener_kind,opener,state,pot_total,pot_spent,reserve,opened_at)
          VALUES('brd_private','fixture','brd_private',0,'private','MESSAGES','operator','person','operator','open',2,0,1,clock_timestamp())
          """);
          jdbc.update(
              """
          INSERT INTO board_message_instances(id,account,project,agent,conversation,topic,lifetime)
          VALUES('ins_fixture','operator','fixture','fixture','cnv_fixture','brd_private','persistent')
          """);
          return true;
        });
    publishAll();
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM relay_publications WHERE scope_key=?",
            Integer.class,
            "project:" + project));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM relay_publications WHERE scope_key='system' AND topic='message.instance.changed'",
            Integer.class));
  }
}
