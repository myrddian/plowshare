package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.relay.DurableRelay;
import io.aeyer.plowshare.server.relay.JdbcRelayConsumerRepository;
import io.aeyer.plowshare.server.relay.JdbcRelayDeliveryRepository;
import io.aeyer.plowshare.server.relay.JdbcRelayPayloadStore;
import io.aeyer.plowshare.server.relay.JdbcRelayRepository;
import io.aeyer.plowshare.server.relay.JdbcRelaySourceRepository;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayConsumerRepository;
import io.aeyer.plowshare.server.relay.RelayNativeRepository;
import io.aeyer.plowshare.server.relay.RelayPayload;
import io.aeyer.plowshare.server.testing.AuthorityFixtures;
import io.aeyer.plowshare.server.todos.StageRules;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Requires PostgreSQL triggers, upgrade/backfill, row mapping, transaction rollback and leases. */
@Tag("full-db")
@Testcontainers
class RelayChildCompletionDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");
  private static JdbcTemplate jdbc;
  private static UnitOfWork work;
  private static OrchestrationStore store;
  private static ConversationStore conversations;
  private static final AtomicLong ticks = new AtomicLong();
  private long project;
  private Relay.TopicKey topic;
  private Relay.SubscriptionKey subscription;
  private JdbcRelayRepository broker;
  private Relay relay;
  private RelayConsumerRepository leases;
  private JdbcChildCompletions repository;
  private JdbcRelaySourceRepository sources;
  private final List<String> heard = new CopyOnWriteArrayList<>();
  private final AtomicBoolean busy = new AtomicBoolean();

  @BeforeAll
  static void migration() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).target("242").load().migrate();
    jdbc = new JdbcTemplate(source);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    work =
        new UnitOfWork() {
          public <T> T inTransaction(Supplier<T> body) {
            return Objects.requireNonNull(tx.execute(status -> body.get()));
          }
        };
    store = new OrchestrationStore(jdbc, () -> NOW.plusMillis(ticks.incrementAndGet()), work);
    conversations = new ConversationStore(jdbc, () -> NOW, null);
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES('alice','fixture')");
    var root = run(null);
    var failed = run(root);
    var delivered = run(root);
    var finished = run(root);
    store.stop(failed.id(), OrchestrationState.FAILED, "fixture failure");
    store.stop(delivered.id(), OrchestrationState.CANCELLED, "fixture cancellation");
    store.resultDelivered(delivered.id());
    store.finish(finished.id(), "fixture result");
    assertEquals(0L, notices(failed.id()));
    assertEquals(1L, notices(finished.id()));
    Flyway.configure().dataSource(source).load().migrate();
    assertEquals(1L, notices(failed.id()), "upgrade recovers pending non-success outcomes");
    assertEquals(0L, notices(delivered.id()), "delivered history is not manufactured as work");
    assertEquals(1L, notices(finished.id()), "an existing pending outbox notice is not duplicated");
  }

  private static OrchestrationRecord run(OrchestrationRecord parent) {
    return runIn(parent, Home.of("completion-fixture"));
  }

  private static OrchestrationRecord runIn(OrchestrationRecord parent, Home home) {
    String conversation =
        conversations.log(Origin.ORCHESTRATION, home, "writer", null, Budget.of(20)).id();
    return store.insert(
        new OrchestrationStore.NewOrchestration(
            "writer",
            OrchestrationDefinition.Tier.PROJECT,
            "sha256:fixture",
            "fixture",
            "fixture",
            List.of(new StageRules.Stage("goal", List.of())),
            1,
            home.project(),
            conversation,
            parent == null ? null : parent.conductorConversation(),
            "writer",
            "alice",
            null,
            parent == null ? null : parent.id(),
            parent == null ? 0 : parent.depth() + 1));
  }

  private static long notices(String child) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT count(*) FROM relay_source_events WHERE topic=? AND subject=?",
            Long.class,
            ChildCompletions.TOPIC,
            child));
  }

  @BeforeEach
  void fresh() {
    AuthorityFixtures.truncate(
        jdbc, "TRUNCATE projects,conversations,orchestrations,admins CASCADE");
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES('alice','fixture')");
    // ConversationStore creates the isolated named project as its owning API normally does.
    var root = run(null);
    project =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "SELECT project_id FROM conversations WHERE id=?",
                Long.class,
                root.conductorConversation()));
    topic = new Relay.TopicKey(project, ChildCompletions.TOPIC);
    subscription = new Relay.SubscriptionKey(topic, ChildCompletions.GROUP);
    broker =
        new JdbcRelayRepository(
            jdbc,
            work,
            new JdbcRelayPayloadStore(
                jdbc, work, Duration.ofHours(1), Duration.ofHours(24), Duration.ofDays(30)));
    relay = new DurableRelay(broker, () -> NOW.plusSeconds(1));
    leases =
        new JdbcRelayConsumerRepository(
            jdbc, work, new JdbcRelayDeliveryRepository(jdbc, work), broker);
    repository = new JdbcChildCompletions(jdbc, work, leases);
    sources = new JdbcRelaySourceRepository(jdbc, work, relay, mock(RelayNativeRepository.class));
    heard.clear();
    busy.set(false);
  }

  private OrchestrationRecord parent() {
    return store.live().getFirst();
  }

  private Delivery delivery() {
    return new Delivery(
        store,
        conversations,
        mock(CallerVoice.class),
        mock(InboxPort.class),
        ignored -> false,
        new ParentVoice() {
          public boolean isLiveParent(String id) {
            return store.find(id).filter(row -> !row.state().terminal()).isPresent();
          }

          public boolean isSpeaking(String id) {
            return busy.get();
          }

          public void speak(String id, String utterance) {
            if (store.find(id).orElseThrow().state() == OrchestrationState.ASKING)
              throw new Turn.Refused("waiting for caller");
            store.wake(id);
            heard.add(utterance);
          }
        });
  }

  private void publish() {
    while (sources.publishNext(topic)) {}
  }

  private RelayConsumerRepository.Lease acquire(String worker) {
    relay.registerTopic(topic, RelayPayload.Kind.LIFECYCLE, Relay.Policy.systemDefault());
    relay.subscribe(subscription, Relay.Start.OLDEST_RETAINED);
    return leases
        .acquire(subscription, worker, ChildCompletions.GROUP, Duration.ofMinutes(2))
        .orElseThrow();
  }

  @Test
  void two_consumers_share_one_group_and_a_terminal_report_wakes_the_waiting_parent_once()
      throws Exception {
    var root = parent();
    var child = run(root);
    store.waitFor(root.id(), child.id());
    store.finish(child.id(), "child report");
    publish();
    var holder = acquire("fixture-holder");
    try (var first = new RelayChildCompletions(relay, leases, repository, delivery(), work);
        var second = new RelayChildCompletions(relay, leases, repository, delivery(), work)) {
      first.consume(project, 8);
      assertTrue(heard.isEmpty(), "live ownership excludes another server");
      leases.release(holder);
      try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
        var one = threads.submit(() -> first.consume(project, 8));
        var two = threads.submit(() -> second.consume(project, 8));
        one.get();
        two.get();
      }
      first.consume(project, 8);
    }
    assertEquals(1, heard.size());
    assertEquals(OrchestrationState.RUNNING, store.find(root.id()).orElseThrow().state());
    assertNotNull(store.find(child.id()).orElseThrow().resultDeliveredAt());
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM relay_subscriptions WHERE subscriber=?",
            Long.class,
            ChildCompletions.GROUP));
  }

  @Test
  void busy_reports_survive_cursor_advance_retention_and_a_fresh_consumer_instance() {
    var child = run(parent());
    store.stop(child.id(), OrchestrationState.FAILED, "retained failure");
    publish();
    busy.set(true);
    try (var consumer = new RelayChildCompletions(relay, leases, repository, delivery(), work)) {
      consumer.consume(project, 8);
    }
    assertNull(store.find(child.id()).orElseThrow().resultDeliveredAt());
    assertEquals(1, broker.prune(topic, NOW.plus(Duration.ofDays(5)), 100));
    busy.set(false);
    try (var restarted = new RelayChildCompletions(relay, leases, repository, delivery(), work)) {
      restarted.consume(project, 8);
    }
    assertEquals(1, heard.size());
    assertTrue(heard.getFirst().contains("retained failure"));
    assertNotNull(store.find(child.id()).orElseThrow().resultDeliveredAt());
  }

  @Test
  void recovery_cursor_rotates_busy_pages_persistently_and_stale_leases_cannot_move_it() {
    for (int i = 0; i < 3; i++) store.finish(run(parent()).id(), "result");
    var lease = acquire("first");
    assertTrue(
        repository.pending(lease, 1).isEmpty(),
        "unpublished outbox entries must reach Relay first");
    publish();
    var one = repository.pending(lease, 1).getFirst();
    leases.release(lease);
    var successor = acquire("second");
    var two = repository.pending(successor, 1).getFirst();
    assertTrue(
        !one.child().equals(two.child()), "a restarted owner continues beyond the busy first page");
    assertThrows(IllegalStateException.class, () -> repository.pending(lease, 1));
    var three = repository.pending(successor, 1).getFirst();
    assertTrue(!three.child().equals(one.child()) && !three.child().equals(two.child()));
    store.finish(run(parent()).id(), "new arrival beyond the captured recovery cycle");
    publish();
    assertTrue(
        repository.pending(successor, 1).isEmpty(), "new arrivals cannot extend an existing cycle");
    assertEquals(one.child(), repository.pending(successor, 1).getFirst().child());
    leases.release(successor);
  }

  @Test
  void global_and_project_groups_cannot_select_each_others_child_reports() {
    var globalParent = runIn(null, Home.global());
    var globalChild = runIn(globalParent, Home.global());
    store.finish(globalChild.id(), "global report");
    var ended = store.find(globalChild.id()).orElseThrow();
    var globalTopic = new Relay.TopicKey(Relay.SystemScope.SERVER, ChildCompletions.TOPIC);
    while (sources.publishNext(globalTopic)) {}
    var projectLease = acquire("project-only");
    var forged =
        new ChildCompletions.Completion(project, ended.id(), ended.parent(), ended.endedAt());
    assertTrue(repository.current(projectLease, List.of(forged)).isEmpty());
    leases.release(projectLease);
    try (var consumer = new RelayChildCompletions(relay, leases, repository, delivery(), work)) {
      consumer.consume(project, 8);
      assertTrue(heard.isEmpty());
      consumer.consume(0, 8);
    }
    assertEquals(1, heard.size());
    assertNotNull(store.find(globalChild.id()).orElseThrow().resultDeliveredAt());
  }

  @Test
  void
      terminal_capture_rolls_back_and_current_notice_resolution_rejects_foreign_or_stale_attempts() {
    var child = run(parent());
    assertThrows(
        IllegalStateException.class,
        () ->
            work.inTransaction(
                () -> {
                  store.stop(child.id(), OrchestrationState.CAPPED, "cap");
                  throw new IllegalStateException("fixture rollback");
                }));
    assertEquals(0L, notices(child.id()));
    assertEquals(OrchestrationState.RUNNING, store.find(child.id()).orElseThrow().state());
    store.stop(child.id(), OrchestrationState.CANCELLED, "cancelled");
    assertEquals(1L, notices(child.id()));
    var ended = store.find(child.id()).orElseThrow();
    var lease = acquire("current");
    var current =
        new ChildCompletions.Completion(project, child.id(), child.parent(), ended.endedAt());
    assertEquals(List.of(current), repository.current(lease, List.of(current)));
    var stale =
        new ChildCompletions.Completion(
            project, child.id(), child.parent(), ended.endedAt().minusSeconds(1));
    assertTrue(repository.current(lease, List.of(stale)).isEmpty());
    var foreign =
        new ChildCompletions.Completion(project + 1, child.id(), child.parent(), ended.endedAt());
    assertThrows(IllegalArgumentException.class, () -> repository.current(lease, List.of(foreign)));
    store.resultDelivered(child.id(), ended.endedAt());
    assertTrue(repository.current(lease, List.of(current)).isEmpty());
    leases.release(lease);
  }
}
