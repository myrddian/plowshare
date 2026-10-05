package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.RelayControl;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL proves generation fencing, operation receipts, rollback and unknown-effect retention.
 */
@Tag("full-db")
@Testcontainers
class JdbcRelayOperationRepositoryTest {
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
  private RelayOperationRepository operations;
  private RelayProjectFiles.Access access;

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
    jdbc.update("DELETE FROM projects WHERE name='relay-operator-fixture'");
    projectId =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO projects(name,workspace) VALUES('relay-operator-fixture','fixture') RETURNING id",
                Long.class));
    access = new RelayProjectFiles.Access("operator", "relay-operator-fixture", projectId);
    topic = new Relay.TopicKey(projectId, "release.observed");
    subscription = new Relay.SubscriptionKey(topic, "relay.notices.release");
    broker = new JdbcRelayRepository(jdbc, transactions);
    deliveries = new JdbcRelayDeliveryRepository(jdbc, transactions);
    consumers = new JdbcRelayConsumerRepository(jdbc, transactions, deliveries);
    operations = new JdbcRelayOperationRepository(jdbc, transactions, broker, deliveries);
    broker.registerTopic(topic, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    broker.subscribe(subscription, Relay.Start.OLDEST_RETAINED, Instant.now());
    decision =
        new RelayDeliveries.Decision(
            RelayDeliveries.SourcePin.of(
                "notices/routes.js", "export function route(){return [];}"),
            List.of(new RelayDeliveries.Branch("record", "test.record", null)));
  }

  private RelayControl.Request request(
      RelayControl.Action action, RelayDeliveries.Delivery branch, String gap) {
    return new RelayControl.Request(
        UUID.randomUUID().toString(),
        access.project(),
        topic.name(),
        broker.topic(topic).generation().toString(),
        action,
        action == RelayControl.Action.REMOVE_TOPIC ? null : subscription.subscriber(),
        action == RelayControl.Action.REMOVE_TOPIC
            ? null
            : broker.unread(subscription).subscription().generation().toString(),
        branch == null ? null : branch.key().id().toString(),
        branch == null ? null : Long.toString(branch.fence()),
        gap,
        branch == null ? null : branch.state().name(),
        "Resolve retained fixture work");
  }

  private RelayControl.Result apply(RelayControl.Request request) {
    return operations.apply(access, request, Optional.empty());
  }

  private RelayDeliveries.Delivery admit() {
    var now = Instant.now();
    var event =
        broker.append(
            topic,
            new Relay.Draft(
                UUID.randomUUID().toString(),
                "fixture",
                now,
                null,
                null,
                new RelayPayload.Text("release")),
            now);
    var admitted =
        deliveries.admit(
            new RelayDeliveries.AdmissionKey(subscription, event.position()), decision, now);
    return deliveries.delivery(admitted.deliveries().getFirst()).orElseThrow();
  }

  private RelayDeliveries.Delivery uncertain() {
    admit();
    var now = Instant.now();
    var claim = deliveries.claim(subscription, "worker", Duration.ofMinutes(1), now).orElseThrow();
    deliveries.prepareDispatch(claim.claim(), now);
    return deliveries.uncertain(claim.claim(), "dispatch.unknown", now);
  }

  @Test
  void exact_gap_acknowledgement_is_atomic_audited_and_conflicting_ids_are_refused() {
    var now = Instant.now();
    broker.configureTopic(
        topic, RelayPayload.Kind.TEXT, new Relay.Policy(Duration.ofSeconds(1), null));
    broker.append(
        topic,
        new Relay.Draft(
            "one", "fixture", now.minusSeconds(10), null, null, new RelayPayload.Text("release")),
        now.minusSeconds(10));
    assertEquals(1, broker.prune(topic, now, 10));
    var stale = request(RelayControl.Action.ACKNOWLEDGE_GAP, null, "2");
    assertThrows(CallerFault.class, () -> apply(stale));
    assertTrue(operations.receipt(access, stale).isEmpty());
    assertEquals(0, broker.unread(subscription).subscription().seenThrough());
    var valid = request(RelayControl.Action.ACKNOWLEDGE_GAP, null, "1");
    var result = apply(valid);
    assertEquals("1", result.seenThrough());
    assertEquals(result, apply(valid));
    assertEquals(result, operations.receipt(access, valid).orElseThrow());
    assertThrows(
        CallerFault.class,
        () ->
            operations.receipt(
                new RelayProjectFiles.Access("other", access.project(), projectId), valid));
    var changed =
        new RelayControl.Request(
            valid.requestId(),
            valid.project(),
            valid.topic(),
            valid.topicGeneration(),
            valid.action(),
            valid.subscriber(),
            valid.subscriptionGeneration(),
            null,
            null,
            "2",
            null,
            valid.reason());
    assertThrows(CallerFault.class, () -> apply(changed));
  }

  @Test
  void ready_abandonment_is_known_and_can_age_out_but_cannot_be_claimed() {
    var branch = admit();
    var request = request(RelayControl.Action.ABANDON, branch, null);
    assertEquals("ABANDONED", apply(request).status());
    assertEquals(
        RelayDeliveries.State.ABANDONED, deliveries.delivery(branch.key()).orElseThrow().state());
    assertTrue(
        deliveries.claim(subscription, "late", Duration.ofMinutes(1), Instant.now()).isEmpty());
    assertEquals(
        1,
        deliveries.pruneSettled(Instant.now().plus(Duration.ofDays(31)), Duration.ofDays(30), 10));
    assertEquals("ABANDONED", apply(request).status());
  }

  @Test
  void uncertain_abandonment_retains_unknown_effect_and_allows_later_owning_resolution() {
    var branch = uncertain();
    var request = request(RelayControl.Action.ABANDON, branch, null);
    assertEquals("ABANDONED_UNCERTAIN", apply(request).status());
    assertEquals(
        0,
        deliveries.pruneSettled(Instant.now().plus(Duration.ofDays(100)), Duration.ofDays(30), 10));
    var abandoned = deliveries.delivery(branch.key()).orElseThrow();
    var reconcile = request(RelayControl.Action.RECONCILE, abandoned, null);
    assertEquals("ABANDONED_UNCERTAIN", apply(reconcile).status());
    var resolved = request(RelayControl.Action.RECONCILE, abandoned, null);
    assertEquals(
        "ACCEPTED",
        operations
            .apply(
                access,
                resolved,
                Optional.of(
                    new RelayDeliveries.Accepted(
                        new RelayDeliveries.Receipt("job", "fixture-job"))))
            .status());
    assertEquals(
        RelayDeliveries.State.ACCEPTED, deliveries.delivery(branch.key()).orElseThrow().state());
  }

  @Test
  void stale_branch_fences_and_worker_claims_cannot_be_abandoned() {
    var ready = admit();
    var request = request(RelayControl.Action.ABANDON, ready, null);
    var claimed =
        deliveries
            .claim(subscription, "worker", Duration.ofMinutes(1), Instant.now())
            .orElseThrow();
    assertThrows(CallerFault.class, () -> apply(request));
    assertTrue(operations.receipt(access, request).isEmpty());
    assertEquals(
        RelayDeliveries.State.CLAIMED, deliveries.delivery(ready.key()).orElseThrow().state());
    deliveries.prepareDispatch(claimed.claim(), Instant.now());
    assertThrows(CallerFault.class, () -> apply(request));
  }

  @Test
  void empty_removal_preserves_receipts_and_stale_incarnations_cannot_remove_recreated_names() {
    var request = request(RelayControl.Action.REMOVE_SUBSCRIPTION, null, null);
    var topicGeneration = broker.topic(topic).generation();
    assertEquals("REMOVED", apply(request).status());
    var recreated = broker.subscribe(subscription, Relay.Start.OLDEST_RETAINED, Instant.now());
    assertNotEquals(request.subscriptionGeneration(), recreated.generation().toString());
    assertEquals("REMOVED", apply(request).status()); // original receipt, no second deletion
    assertEquals(recreated, broker.unread(subscription).subscription());
    jdbc.update(
        "UPDATE relay_operator_receipts SET completed_at=clock_timestamp()-interval '91 days' WHERE request_id=?",
        UUID.fromString(request.requestId()));
    assertEquals(1, operations.prune(Duration.ofDays(90), 10));
    assertThrows(CallerFault.class, () -> apply(request));
    apply(request(RelayControl.Action.REMOVE_SUBSCRIPTION, null, null));
    var removeTopic = request(RelayControl.Action.REMOVE_TOPIC, null, null);
    assertEquals("REMOVED", apply(removeTopic).status());
    var next = broker.registerTopic(topic, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    assertNotEquals(topicGeneration, next.generation());
    assertEquals("REMOVED", apply(removeTopic).status());
    assertEquals(next, broker.topic(topic));
  }

  @Test
  void live_workers_retained_inputs_and_publications_prevent_metadata_removal() {
    var remove = request(RelayControl.Action.REMOVE_SUBSCRIPTION, null, null);
    var lease =
        consumers.acquire(subscription, "worker", "operator", Duration.ofMinutes(1)).orElseThrow();
    assertThrows(CallerFault.class, () -> apply(remove));
    consumers.release(lease);
    admit();
    assertThrows(CallerFault.class, () -> apply(remove));
    assertThrows(
        CallerFault.class, () -> apply(request(RelayControl.Action.REMOVE_TOPIC, null, null)));
    assertTrue(operations.receipt(access, remove).isEmpty());
  }

  @Test
  void claim_and_abandon_race_has_one_winner_with_no_replay() throws Exception {
    var branch = admit();
    var request = request(RelayControl.Action.ABANDON, branch, null);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var abandonment =
          executor.submit(
              () -> {
                start.await();
                try {
                  apply(request);
                  return true;
                } catch (CallerFault stale) {
                  return false;
                }
              });
      var claim =
          executor.submit(
              () -> {
                start.await();
                return deliveries
                    .claim(subscription, "worker", Duration.ofMinutes(1), Instant.now())
                    .isPresent();
              });
      start.countDown();
      assertNotEquals(abandonment.get(5, TimeUnit.SECONDS), claim.get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void an_audit_write_failure_rolls_back_abandonment_and_the_request_receipt() {
    var branch = admit();
    var request = request(RelayControl.Action.ABANDON, branch, null);
    jdbc.execute(
        "ALTER TABLE relay_operator_receipts ADD CONSTRAINT fixture_refuse_abandon CHECK(status IS NULL OR status<>'ABANDONED')");
    try {
      assertThrows(DataIntegrityViolationException.class, () -> apply(request));
      assertEquals(
          RelayDeliveries.State.READY, deliveries.delivery(branch.key()).orElseThrow().state());
      assertTrue(operations.receipt(access, request).isEmpty());
    } finally {
      jdbc.execute("ALTER TABLE relay_operator_receipts DROP CONSTRAINT fixture_refuse_abandon");
    }
    assertEquals("ABANDONED", apply(request).status());
  }

  @Test
  void concurrent_identical_requests_share_one_committed_receipt() throws Exception {
    var branch = admit();
    var request = request(RelayControl.Action.ABANDON, branch, null);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> {
                start.await();
                return apply(request);
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return apply(request);
              });
      start.countDown();
      assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
    }
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM relay_operator_receipts WHERE request_id=?",
            Integer.class,
            UUID.fromString(request.requestId())));
  }
}
