package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL proves lease races, stale fences, atomic rollback and worker restart offsets. */
@Tag("full-db")
@Testcontainers
class JdbcRelayConsumerRepositoryTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private static UnitOfWork transactions;
  private RelayRepository broker;
  private RelayDeliveryRepository deliveries;
  private RelayConsumerRepository consumers;
  private Relay.TopicKey topic;
  private Relay.SubscriptionKey subscription;
  private RelayDeliveries.Decision decision;
  private long projectId;

  @BeforeAll
  static void migrate() {
    var data =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(data).load().migrate();
    jdbc = new JdbcTemplate(data);
    var template = new TransactionTemplate(new DataSourceTransactionManager(data));
    transactions =
        new UnitOfWork() {
          public <T> T inTransaction(Supplier<T> action) {
            return Objects.requireNonNull(template.execute(status -> action.get()));
          }

          public void afterCommit(Runnable action) {
            if (TransactionSynchronizationManager.isActualTransactionActive())
              TransactionSynchronizationManager.registerSynchronization(
                  new TransactionSynchronization() {
                    public void afterCommit() {
                      action.run();
                    }
                  });
            else action.run();
          }
        };
  }

  @BeforeEach
  void fresh() {
    jdbc.update("DELETE FROM projects WHERE name='relay-consumer-fixture'");
    projectId =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO projects(name,workspace) VALUES('relay-consumer-fixture','fixture') RETURNING id",
                Long.class));
    topic = new Relay.TopicKey(projectId, "release.observed");
    subscription = new Relay.SubscriptionKey(topic, "relay.notices.release");
    broker = new JdbcRelayRepository(jdbc, transactions);
    deliveries = new JdbcRelayDeliveryRepository(jdbc, transactions);
    consumers = new JdbcRelayConsumerRepository(jdbc, transactions, deliveries);
    broker.registerTopic(topic, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    broker.subscribe(subscription, Relay.Start.OLDEST_RETAINED, Instant.now());
    decision =
        new RelayDeliveries.Decision(
            RelayDeliveries.SourcePin.of(
                "notices/routes.js", "export function route(){return [];}"),
            List.of(new RelayDeliveries.Branch("record", "test.record", null)));
  }

  private Relay.Publication append(String id) {
    return broker.append(
        topic,
        new Relay.Draft(id, "test", Instant.now(), null, null, new RelayPayload.Text("released")),
        Instant.now());
  }

  private void expire() {
    jdbc.update(
        "UPDATE relay_consumer_leases SET lease_until=clock_timestamp()-interval '1 second'");
  }

  @Test
  void competing_workers_have_one_owner_while_other_groups_consume_independently()
      throws Exception {
    var go = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> {
                go.await();
                return consumers.acquire(subscription, "one", "operator", Duration.ofMinutes(1));
              });
      var second =
          executor.submit(
              () -> {
                go.await();
                return consumers.acquire(subscription, "two", "operator", Duration.ofMinutes(1));
              });
      go.countDown();
      assertNotEquals(
          first.get(5, TimeUnit.SECONDS).isPresent(), second.get(5, TimeUnit.SECONDS).isPresent());
    }
    var other = new Relay.SubscriptionKey(topic, "relay.notices.other");
    broker.subscribe(other, Relay.Start.OLDEST_RETAINED, Instant.now());
    assertTrue(consumers.acquire(other, "three", "operator", Duration.ofMinutes(1)).isPresent());
  }

  @Test
  void stale_admission_claim_and_release_cannot_change_a_successor_or_advance_offsets() {
    var input = append("first");
    var key = new RelayDeliveries.AdmissionKey(subscription, input.position());
    var old =
        consumers.acquire(subscription, "old", "operator", Duration.ofMinutes(1)).orElseThrow();
    expire();
    var next =
        consumers.acquire(subscription, "new", "operator", Duration.ofMinutes(1)).orElseThrow();
    assertTrue(next.epoch() > old.epoch());
    assertThrows(IllegalStateException.class, () -> consumers.admit(old, key, decision));
    assertThrows(IllegalStateException.class, () -> consumers.claim(old, Duration.ofMinutes(1)));
    assertEquals(
        0, broker.subscribe(subscription, Relay.Start.LATEST, Instant.now()).seenThrough());
    assertTrue(deliveries.admission(key).isEmpty());
    consumers.release(old);
    assertTrue(
        consumers.acquire(subscription, "other", "operator", Duration.ofMinutes(1)).isEmpty());
    consumers.admit(next, key, decision);
    assertEquals(
        1, broker.subscribe(subscription, Relay.Start.LATEST, Instant.now()).seenThrough());
    assertTrue(consumers.claim(next, Duration.ofMinutes(1)).isPresent());
  }

  @Test
  void same_worker_reacquisition_gets_a_fresh_epoch_and_scope_mismatch_is_refused() {
    var old =
        consumers.acquire(subscription, "worker", "operator", Duration.ofMinutes(1)).orElseThrow();
    var current =
        consumers.acquire(subscription, "worker", "operator", Duration.ofMinutes(1)).orElseThrow();
    assertTrue(current.epoch() > old.epoch());
    consumers.release(old);
    assertTrue(
        consumers.acquire(subscription, "other", "operator", Duration.ofMinutes(1)).isEmpty());
    var different = new Relay.SubscriptionKey(topic, "relay.notices.other");
    assertThrows(
        IllegalArgumentException.class,
        () -> consumers.admit(current, new RelayDeliveries.AdmissionKey(different, 1), decision));
    assertThrows(IllegalStateException.class, () -> consumers.claim(old, Duration.ofMinutes(1)));
  }

  @Test
  void ownership_expiring_while_admission_waits_rolls_back_all_input_and_offset_changes()
      throws Exception {
    var input = append("first");
    var key = new RelayDeliveries.AdmissionKey(subscription, input.position());
    var lease =
        consumers.acquire(subscription, "worker", "operator", Duration.ofSeconds(1)).orElseThrow();
    var held = new CountDownLatch(1);
    var unblock = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var lock =
          executor.submit(
              () ->
                  transactions.inTransaction(
                      () -> {
                        jdbc.queryForObject(
                            "SELECT name FROM relay_topics WHERE scope_key=? AND name=? FOR UPDATE",
                            String.class,
                            RelayScopeCodec.write(topic),
                            topic.name());
                        held.countDown();
                        try {
                          assertTrue(unblock.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException stop) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(stop);
                        }
                        return true;
                      }));
      assertTrue(held.await(3, TimeUnit.SECONDS));
      var admission = executor.submit(() -> consumers.admit(lease, key, decision));
      // Actual PostgreSQL waits beyond this lease, proving the post-admission clock check rolls
      // back.
      jdbc.execute("SELECT pg_sleep(1.2)");
      unblock.countDown();
      lock.get(5, TimeUnit.SECONDS);
      assertThrows(
          java.util.concurrent.ExecutionException.class, () -> admission.get(5, TimeUnit.SECONDS));
    } finally {
      unblock.countDown();
    }
    assertTrue(deliveries.admission(key).isEmpty());
    assertEquals(
        0, broker.subscribe(subscription, Relay.Start.LATEST, Instant.now()).seenThrough());
  }

  @Test
  void publication_hints_follow_commit_and_rollback_does_not_wake_consumers() {
    var hints = new AtomicInteger();
    var relay =
        new DurableRelay(
            broker, Instant::now, key -> transactions.afterCommit(hints::incrementAndGet));
    var draft =
        new Relay.Draft(
            "rollback", "test", Instant.now(), null, null, new RelayPayload.Text("released"));
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.inTransaction(
                () -> {
                  relay.publish(topic, draft);
                  assertEquals(0, hints.get());
                  throw new IllegalStateException("rollback");
                }));
    assertEquals(0, hints.get());
    assertTrue(relay.retained(topic, "rollback").isEmpty());
    transactions.inTransaction(
        () -> {
          relay.publish(topic, draft);
          assertEquals(0, hints.get());
          return true;
        });
    assertEquals(1, hints.get());
  }

  @Test
  void automatic_workers_resume_persisted_groups_after_restart_without_duplicate_dispatch()
      throws Exception {
    var account = "operator";
    var project = "relay-consumer-fixture";
    var access = new RelayProjectFiles.Access(account, project, projectId);
    var files = mock(RelayProjectFiles.class);
    when(files.read(access, "active.json"))
        .thenReturn(Optional.of("{\"version\":1,\"active\":[\"notices\"]}"));
    when(files.read(access, "notices/routes.js"))
        .thenReturn(
            Optional.of(
                """
        export const manifest={version:1,subscriptions:[
          {name:'release',topic:'release.observed',kind:'text',start:'oldest-retained'},
          {name:'other',topic:'release.observed',kind:'text',start:'oldest-retained'}]};
        export function route(event){return [{name:'record',receiver:'test.record'}];}
        """));
    var members = mock(ProjectMembers.class);
    when(members.mayWork(project, account)).thenReturn(true);
    var projects = mock(ProjectWorkspaces.class);
    when(projects.id(project)).thenReturn(projectId);
    var signals = new LocalRelayPublicationSignals();
    var relay =
        new DurableRelay(
            broker, Instant::now, key -> transactions.afterCommit(() -> signals.published(key)));
    var durableDeliveries = new DurableRelayDeliveries(deliveries, Instant::now);
    var accepted = new AtomicInteger();
    var firstTwo = new CountDownLatch(2);
    var nextTwo = new CountDownLatch(2);
    RelayReceiver receiver =
        new RelayReceiver() {
          public void require(Request request) {}

          public Result dispatch(Request request) {
            int count = accepted.incrementAndGet();
            if (count <= 2) firstTwo.countDown();
            else nextTwo.countDown();
            return new Settled(
                new RelayDeliveries.Accepted(
                    new RelayDeliveries.Receipt("test", request.identity().toString())));
          }

          public Optional<RelayDeliveries.Resolution> inspect(Request request) {
            return Optional.empty();
          }
        };
    var dispatch =
        new ReceiverRelayDispatch(
            durableDeliveries,
            new RegisteredRelayReceivers(
                List.of(new RelayReceivers.Binding("test.record", receiver))),
            members,
            projects,
            () -> assertFalse(TransactionSynchronizationManager.isActualTransactionActive()));
    var properties = new RelayWorkerProperties();
    properties.setProjects(List.of(new RelayWorkerProperties.Project(project, account)));
    properties.setIdleInterval(Duration.ofSeconds(1));
    properties.setConfigurationInterval(Duration.ofSeconds(1));
    try (var programs = new GraalRelayRouteProgram()) {
      var routing = new ProjectRelayRouting(files, programs, relay, durableDeliveries);
      var work =
          new OwnedRelaySubscriptionWork(files, members, routing, relay, dispatch, consumers);
      append("first");
      try (var workers = new RelayWorkers(projects, work, signals, properties);
          var replica = new RelayWorkers(projects, work, signals, properties)) {
        workers.start();
        replica.start();
        assertTrue(firstTwo.await(15, TimeUnit.SECONDS));
        // Wait for accepted outcomes to commit before shutting down; acceptance is not the callback
        // itself.
        awaitAccepted(2);
      }
      append("second");
      try (var workers = new RelayWorkers(projects, work, signals, properties)) {
        workers.start();
        assertTrue(nextTwo.await(15, TimeUnit.SECONDS));
        awaitAccepted(4);
      }
      assertEquals(4, accepted.get());
      assertEquals(
          2, broker.subscribe(subscription, Relay.Start.LATEST, Instant.now()).seenThrough());
      var other = new Relay.SubscriptionKey(topic, "relay.notices.other");
      assertEquals(2, broker.subscribe(other, Relay.Start.LATEST, Instant.now()).seenThrough());
    }
  }

  @Test
  void cyclic_topic_forwarding_stops_at_the_bound_and_missing_history_refuses_another_hop() {
    var relay = new DurableRelay(broker, Instant::now);
    var history = new JdbcRelayForwardingHistory(jdbc);
    var receiver = new ForwardRelayReceiver(relay, history, 8);
    var other = new Relay.TopicKey(projectId, "release.forwarded");
    relay.registerTopic(other, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    var input = append("initial");
    RelayDeliveries.AdmissionKey root = null;
    for (int index = 0; index < 8; index++) {
      var destination = input.topic().equals(topic) ? other : topic;
      var group = new Relay.SubscriptionKey(input.topic(), "relay.notices.forward");
      broker.subscribe(group, Relay.Start.OLDEST_RETAINED, Instant.now());
      var key = new RelayDeliveries.AdmissionKey(group, input.position());
      if (root == null) root = key;
      deliveries.admit(
          key,
          new RelayDeliveries.Decision(
              decision.routing(),
              List.of(
                  new RelayDeliveries.Branch(
                      "forward", "relay.publish", null, destination.name()))),
          Instant.now());
      var claimed =
          deliveries.claim(group, "forward", Duration.ofMinutes(1), Instant.now()).orElseThrow();
      var prepared = deliveries.prepareDispatch(claimed.claim(), Instant.now());
      var request =
          new RelayReceiver.Request(
              new RelayProjectFiles.Access("operator", "relay-consumer-fixture", projectId),
              prepared);
      var result = (RelayReceiver.Settled) receiver.dispatch(request);
      deliveries.accepted(
          claimed.claim(),
          ((RelayDeliveries.Accepted) result.resolution()).receipt(),
          Instant.now());
      input = relay.retained(destination, "relay:" + request.identity()).orElseThrow();
      assertEquals(index + 1, history.depth(input, 8).orElseThrow());
    }
    var finalInput = input;
    assertEquals(8, history.depth(finalInput, 8).orElseThrow());
    var group = new Relay.SubscriptionKey(finalInput.topic(), "relay.notices.forward");
    var destination = finalInput.topic().equals(topic) ? other : topic;
    deliveries.admit(
        new RelayDeliveries.AdmissionKey(group, finalInput.position()),
        new RelayDeliveries.Decision(
            decision.routing(),
            List.of(
                new RelayDeliveries.Branch("forward", "relay.publish", null, destination.name()))),
        Instant.now());
    var claimed =
        deliveries.claim(group, "forward", Duration.ofMinutes(1), Instant.now()).orElseThrow();
    var prepared = deliveries.prepareDispatch(claimed.claim(), Instant.now());
    var request =
        new RelayReceiver.Request(
            new RelayProjectFiles.Access("operator", "relay-consumer-fixture", projectId),
            prepared);
    assertThrows(RelayReceiver.Refused.class, () -> receiver.dispatch(request));
    assertTrue(relay.retained(destination, "relay:" + request.identity()).isEmpty());
    jdbc.update(
        "DELETE FROM relay_admissions WHERE scope_key=? AND topic=? AND subscriber=? AND publication_position=?",
        RelayScopeCodec.write(root.subscription().topic()),
        root.subscription().topic().name(),
        root.subscription().subscriber(),
        root.position());
    assertTrue(history.depth(finalInput, 8).isEmpty());
    assertThrows(RelayReceiver.Refused.class, () -> receiver.dispatch(request));
  }

  private void awaitAccepted(int expected) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (jdbc.queryForObject(
              "SELECT count(*) FROM relay_deliveries WHERE scope_key=? AND state='ACCEPTED'",
              Integer.class,
              RelayScopeCodec.write(topic))
          == expected) return;
      TimeUnit.MILLISECONDS.sleep(20);
    }
    fail("accepted outcomes did not commit");
  }
}
