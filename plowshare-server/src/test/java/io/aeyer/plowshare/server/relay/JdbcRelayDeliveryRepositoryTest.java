package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real PostgreSQL verifies atomic input/cursor admission, fenced claims and cleanup concurrency.
 */
@Tag("full-db")
@Testcontainers
class JdbcRelayDeliveryRepositoryTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Instant T0 = Instant.parse("2026-10-05T01:00:00Z");
  private static final Duration LEASE = Duration.ofSeconds(10);
  private static final RelayDeliveries.SourcePin ROUTE =
      RelayDeliveries.SourcePin.of("release-review/routes.js", "export const routes = [];");
  private static final RelayDeliveries.Branch NOTIFY =
      new RelayDeliveries.Branch("notify", "agent-message", null);
  private static final RelayDeliveries.Decision ONE =
      new RelayDeliveries.Decision(ROUTE, List.of(NOTIFY));
  private static JdbcTemplate jdbc;
  private static UnitOfWork transactions;
  private RelayRepository log;
  private RelayDeliveryRepository deliveries;
  private Relay.TopicKey topic;
  private Relay.SubscriptionKey subscription;
  private RelayDeliveries.AdmissionKey first;

  @BeforeAll
  static void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
    var template = new TransactionTemplate(new DataSourceTransactionManager(source));
    transactions =
        new UnitOfWork() {
          @Override
          public <T> T inTransaction(Supplier<T> work) {
            return Objects.requireNonNull(template.execute(status -> work.get()));
          }
        };
  }

  @BeforeEach
  void fresh() {
    jdbc.execute("TRUNCATE relay_topics CASCADE");
    jdbc.update("DELETE FROM projects WHERE name='relay-delivery-fixture'");
    Long project =
        jdbc.queryForObject(
            "INSERT INTO projects(name,workspace) VALUES('relay-delivery-fixture','fixture') RETURNING id",
            Long.class);
    topic = new Relay.TopicKey(Objects.requireNonNull(project), "release.observed");
    subscription = new Relay.SubscriptionKey(topic, "release-review");
    first = new RelayDeliveries.AdmissionKey(subscription, 1);
    log = new JdbcRelayRepository(jdbc, transactions);
    deliveries = new JdbcRelayDeliveryRepository(jdbc, transactions);
    log.configureTopic(topic, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    log.subscribe(subscription, Relay.Start.OLDEST_RETAINED, T0);
    append("one");
  }

  private Relay.Publication append(String event) {
    return log.append(
        topic,
        new Relay.Draft(
            event,
            "release-source",
            T0.minusSeconds(60),
            "release-cycle",
            null,
            new RelayPayload.Text("retained " + event)),
        T0);
  }

  private RelayDeliveries.Delivery claim() {
    deliveries.admit(first, ONE, T0);
    return deliveries.claim(subscription, "worker-a", LEASE, T0).orElseThrow();
  }

  @Test
  void fan_out_pins_optional_handler_input_and_stable_branch_identities_per_subscriber() {
    var handler =
        RelayDeliveries.SourcePin.of(
            "release-review/scripts/review.js", "export const step = () => {};");
    var decision =
        new RelayDeliveries.Decision(
            ROUTE, List.of(NOTIFY, new RelayDeliveries.Branch("review", "script", handler)));
    var admitted = deliveries.admit(first, decision, T0);
    assertEquals(admitted, deliveries.admit(first, decision, T0.plusSeconds(1)));
    assertEquals(admitted, deliveries.admission(first).orElseThrow());
    assertEquals(2, admitted.deliveries().size());
    assertEquals(0, log.unread(subscription).count());
    var notify = deliveries.delivery(admitted.deliveries().getFirst()).orElseThrow();
    assertNull(notify.branch().handler());
    assertEquals(admitted.publication(), notify.publication());
    var review = deliveries.delivery(admitted.deliveries().getLast()).orElseThrow();
    assertEquals(handler, review.branch().handler());
    var other = new Relay.SubscriptionKey(topic, "other-relay");
    log.subscribe(other, Relay.Start.OLDEST_RETAINED, T0);
    var independent = deliveries.admit(new RelayDeliveries.AdmissionKey(other, 1), decision, T0);
    assertNotEquals(
        admitted.deliveries().getFirst().id(), independent.deliveries().getFirst().id());
    assertTrue(
        deliveries.delivery(new RelayDeliveries.DeliveryKey(other, notify.key().id())).isEmpty());
    assertThrows(
        IllegalStateException.class,
        () ->
            deliveries.prepareDispatch(
                new RelayDeliveries.Claim(
                    new RelayDeliveries.DeliveryKey(other, notify.key().id()), "worker", 1),
                T0));
    var changed =
        new RelayDeliveries.Decision(
            RelayDeliveries.SourcePin.of(ROUTE.path(), "changed"), decision.branches());
    assertThrows(IllegalArgumentException.class, () -> deliveries.admit(first, changed, T0));
  }

  @Test
  void publication_target_survives_admission_and_claim_and_is_constrained() {
    var branch = new RelayDeliveries.Branch("forward", "relay.publish", null, "release.forwarded");
    var decision = new RelayDeliveries.Decision(ROUTE, List.of(branch));
    var admitted = deliveries.admit(first, decision, T0);
    assertEquals(decision, deliveries.admission(first).orElseThrow().decision());
    var claimed = deliveries.claim(subscription, "worker", LEASE, T0).orElseThrow();
    assertEquals(branch, claimed.branch());
    assertEquals(branch, deliveries.prepareDispatch(claimed.claim(), T0).branch());
    var changed =
        new RelayDeliveries.Decision(
            ROUTE,
            List.of(new RelayDeliveries.Branch("forward", "relay.publish", null, "release.other")));
    assertThrows(IllegalArgumentException.class, () -> deliveries.admit(first, changed, T0));
    var id = admitted.deliveries().getFirst().id();
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE relay_deliveries SET publish_to=NULL WHERE id=?", id));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE relay_deliveries SET publish_to='../outside' WHERE id=?", id));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE relay_deliveries SET receiver='other' WHERE id=?", id));
    assertEquals(branch, deliveries.delivery(claimed.key()).orElseThrow().branch());
  }

  @Test
  void zero_branch_decisions_advance_availability_and_survive_repeated_admission() {
    var decision = new RelayDeliveries.Decision(ROUTE, List.of());
    var admitted = deliveries.admit(first, decision, T0);
    assertTrue(admitted.deliveries().isEmpty());
    assertEquals(admitted, deliveries.admit(first, decision, T0.plusSeconds(1)));
    assertEquals(1, log.unread(subscription).subscription().seenThrough());
    assertTrue(deliveries.claim(subscription, "worker", LEASE, T0).isEmpty());
    append("two");
    assertEquals(
        2,
        deliveries
            .admit(new RelayDeliveries.AdmissionKey(subscription, 2), ONE, T0)
            .key()
            .position());
  }

  @Test
  void admission_requires_next_publication_and_explicit_gap_acknowledgement() {
    append("two");
    assertThrows(
        IllegalArgumentException.class,
        () -> deliveries.admit(new RelayDeliveries.AdmissionKey(subscription, 2), ONE, T0));
    assertEquals(0, log.unread(subscription).subscription().seenThrough());
    assertEquals(1, log.prune(topic, T0.plus(Duration.ofDays(4)), 1));
    assertThrows(
        IllegalStateException.class,
        () -> deliveries.admit(new RelayDeliveries.AdmissionKey(subscription, 2), ONE, T0));
    log.acknowledgeGap(subscription, 1, T0.plus(Duration.ofDays(4)));
    assertThrows(IllegalStateException.class, () -> deliveries.admit(first, ONE, T0));
    assertEquals(
        2,
        deliveries
            .admit(new RelayDeliveries.AdmissionKey(subscription, 2), ONE, T0)
            .key()
            .position());
  }

  @Test
  void admitted_input_and_sources_survive_topic_eviction_without_replaying_routing() {
    var admitted = deliveries.admit(first, ONE, T0);
    Instant later = T0.plus(Duration.ofDays(4));
    assertEquals(1, log.prune(topic, later, 100));
    assertTrue(log.read(subscription, 100).publications().isEmpty());
    assertEquals(admitted, deliveries.admit(first, ONE, later));
    var claim = deliveries.claim(subscription, "worker", LEASE, later).orElseThrow();
    assertEquals(admitted.publication(), claim.publication());
    assertEquals(ROUTE, claim.routing());
    assertEquals(
        "retained one", ((RelayPayload.Text) claim.publication().event().payload()).text());
  }

  @Test
  void domain_rollback_removes_admission_branches_and_cursor_advancement_together() {
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.inTransaction(
                () -> {
                  deliveries.admit(first, ONE, T0);
                  throw new IllegalStateException("domain failure");
                }));
    assertTrue(deliveries.admission(first).isEmpty());
    assertEquals(0, log.unread(subscription).subscription().seenThrough());
    assertTrue(deliveries.claim(subscription, "worker", LEASE, T0).isEmpty());
    assertEquals(1, deliveries.admit(first, ONE, T0).deliveries().size());
  }

  @Test
  void concurrent_admission_keeps_one_set_of_identities() throws Exception {
    var start = new CountDownLatch(1);
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var a =
          threads.submit(
              () -> {
                await(start);
                return deliveries.admit(first, ONE, T0);
              });
      var b =
          threads.submit(
              () -> {
                await(start);
                return deliveries.admit(first, ONE, T0.plusSeconds(1));
              });
      start.countDown();
      assertEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
      assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM relay_deliveries", Integer.class));
    }
  }

  @Test
  void concurrent_workers_cannot_claim_the_same_branch() throws Exception {
    deliveries.admit(first, ONE, T0);
    var start = new CountDownLatch(1);
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var a =
          threads.submit(
              () -> {
                await(start);
                return deliveries.claim(subscription, "worker-a", LEASE, T0);
              });
      var b =
          threads.submit(
              () -> {
                await(start);
                return deliveries.claim(subscription, "worker-b", LEASE, T0);
              });
      start.countDown();
      assertEquals(
          1,
          (a.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0)
              + (b.get(10, TimeUnit.SECONDS).isPresent() ? 1 : 0));
    }
  }

  @Test
  void expired_unprepared_claim_can_retry_with_a_new_fence_and_stale_workers_cannot_settle() {
    var original = claim();
    assertEquals(0, deliveries.recoverExpired(T0.plusSeconds(9), 10));
    assertEquals(1, deliveries.recoverExpired(T0.plusSeconds(10), 10));
    assertEquals(
        RelayDeliveries.State.READY, deliveries.delivery(original.key()).orElseThrow().state());
    assertThrows(
        IllegalStateException.class,
        () -> deliveries.prepareDispatch(original.claim(), T0.plusSeconds(11)));
    var replacement =
        deliveries.claim(subscription, "worker-b", LEASE, T0.plusSeconds(11)).orElseThrow();
    assertEquals(original.fence() + 1, replacement.fence());
    assertThrows(
        IllegalStateException.class,
        () -> deliveries.failed(original.claim(), "refused", T0.plusSeconds(12)));
    var prepared = deliveries.prepareDispatch(replacement.claim(), T0.plusSeconds(12));
    assertEquals(prepared, deliveries.prepareDispatch(replacement.claim(), T0.plusSeconds(13)));
    var receipt = new RelayDeliveries.Receipt("messaging", "message-receipt");
    assertThrows(
        IllegalStateException.class,
        () -> deliveries.accepted(original.claim(), receipt, T0.plusSeconds(13)));
    var accepted = deliveries.accepted(replacement.claim(), receipt, T0.plusSeconds(14));
    assertEquals(accepted, deliveries.accepted(replacement.claim(), receipt, T0.plusSeconds(100)));
    assertEquals(receipt, accepted.receipt());
    assertTrue(deliveries.claim(subscription, "worker-c", LEASE, T0.plusSeconds(100)).isEmpty());
  }

  @Test
  void topic_eviction_waits_for_admission_to_commit_its_input_and_branches() throws Exception {
    var admitted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var admission =
          threads.submit(
              () ->
                  transactions.inTransaction(
                      () -> {
                        var result = deliveries.admit(first, ONE, T0);
                        admitted.countDown();
                        await(release);
                        return result;
                      }));
      try {
        assertTrue(admitted.await(10, TimeUnit.SECONDS));
        var eviction = threads.submit(() -> log.prune(topic, T0.plus(Duration.ofDays(4)), 100));
        assertThrows(TimeoutException.class, () -> eviction.get(200, TimeUnit.MILLISECONDS));
        release.countDown();
        var result = admission.get(10, TimeUnit.SECONDS);
        assertEquals(1, eviction.get(10, TimeUnit.SECONDS));
        assertEquals(result, deliveries.admission(first).orElseThrow());
        assertEquals(
            result.publication(),
            deliveries.delivery(result.deliveries().getFirst()).orElseThrow().publication());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void expired_prepared_dispatch_is_uncertain_until_owning_api_reconciliation() {
    var claimed = claim();
    deliveries.prepareDispatch(claimed.claim(), T0.plusSeconds(1));
    assertEquals(1, deliveries.recoverExpired(T0.plusSeconds(10), 10));
    var uncertain = deliveries.delivery(claimed.key()).orElseThrow();
    assertEquals(RelayDeliveries.State.UNCERTAIN, uncertain.state());
    assertEquals("lease.expired", uncertain.failureCode());
    assertTrue(deliveries.claim(subscription, "worker-b", LEASE, T0.plusSeconds(11)).isEmpty());
    var receipt = new RelayDeliveries.Receipt("orchestration", "run-receipt");
    assertThrows(
        IllegalStateException.class,
        () -> deliveries.accepted(claimed.claim(), receipt, T0.plusSeconds(11)));
    var reconciled =
        deliveries.reconcile(
            claimed.key(), new RelayDeliveries.Accepted(receipt), T0.plusSeconds(12));
    assertEquals(RelayDeliveries.State.ACCEPTED, reconciled.state());
    assertEquals(
        reconciled,
        deliveries.reconcile(
            claimed.key(), new RelayDeliveries.Accepted(receipt), T0.plusSeconds(20)));
    assertThrows(
        IllegalStateException.class,
        () ->
            deliveries.reconcile(
                claimed.key(), new RelayDeliveries.Failed("refused"), T0.plusSeconds(20)));
  }

  @Test
  void explicit_uncertainty_and_definite_failure_never_create_an_automatic_replay() {
    var claimed = claim();
    assertThrows(
        IllegalStateException.class, () -> deliveries.uncertain(claimed.claim(), "ack.lost", T0));
    assertThrows(
        IllegalStateException.class,
        () ->
            deliveries.accepted(
                claimed.claim(), new RelayDeliveries.Receipt("messaging", "id"), T0));
    deliveries.prepareDispatch(claimed.claim(), T0);
    var uncertain = deliveries.uncertain(claimed.claim(), "ack.lost", T0.plusSeconds(1));
    assertEquals(uncertain, deliveries.uncertain(claimed.claim(), "ack.lost", T0.plusSeconds(2)));
    assertEquals(
        RelayDeliveries.State.FAILED,
        deliveries
            .reconcile(
                claimed.key(), new RelayDeliveries.Failed("definitely.refused"), T0.plusSeconds(3))
            .state());
    assertTrue(deliveries.claim(subscription, "worker-b", LEASE, T0.plusSeconds(4)).isEmpty());
  }

  @Test
  void renewal_extends_live_leases_but_cannot_revive_an_expired_owner() {
    var claimed = claim();
    var renewed = deliveries.renew(claimed.claim(), LEASE, T0.plusSeconds(5));
    assertEquals(T0.plusSeconds(15), renewed.leaseUntil());
    assertEquals(claimed.fence(), renewed.fence());
    assertEquals(0, deliveries.recoverExpired(T0.plusSeconds(10), 1));
    assertThrows(
        IllegalStateException.class,
        () -> deliveries.renew(claimed.claim(), LEASE, T0.plusSeconds(15)));
    assertThrows(
        IllegalStateException.class,
        () -> deliveries.prepareDispatch(claimed.claim(), T0.plusSeconds(15)));
    assertEquals(1, deliveries.recoverExpired(T0.plusSeconds(15), 1));
  }

  @Test
  void settled_input_retention_uses_last_outcome_and_cannot_reset_seen_positions() {
    var admitted = deliveries.admit(first, ONE, T0);
    assertEquals(
        0, deliveries.pruneSettled(T0.plus(Duration.ofDays(60)), Duration.ofDays(30), 100));
    var claimed =
        deliveries.claim(subscription, "worker", LEASE, T0.plus(Duration.ofDays(30))).orElseThrow();
    deliveries.failed(claimed.claim(), "permission.denied", T0.plus(Duration.ofDays(30)));
    assertEquals(
        0, deliveries.pruneSettled(T0.plus(Duration.ofDays(59)), Duration.ofDays(30), 100));
    assertEquals(
        1, deliveries.pruneSettled(T0.plus(Duration.ofDays(60)), Duration.ofDays(30), 100));
    assertTrue(deliveries.admission(first).isEmpty());
    assertTrue(deliveries.delivery(admitted.deliveries().getFirst()).isEmpty());
    assertEquals(1, log.unread(subscription).subscription().seenThrough());
    assertThrows(
        IllegalStateException.class,
        () -> deliveries.admit(first, ONE, T0.plus(Duration.ofDays(61))));
    assertTrue(
        deliveries.claim(subscription, "worker", LEASE, T0.plus(Duration.ofDays(61))).isEmpty());
  }

  @Test
  void zero_branch_cleanup_is_bounded_and_uncertain_input_is_preserved() {
    deliveries.admit(first, new RelayDeliveries.Decision(ROUTE, List.of()), T0);
    append("two");
    var second = deliveries.admit(new RelayDeliveries.AdmissionKey(subscription, 2), ONE, T0);
    var claimed = deliveries.claim(subscription, "worker", LEASE, T0).orElseThrow();
    deliveries.prepareDispatch(claimed.claim(), T0);
    deliveries.recoverExpired(T0.plusSeconds(10), 100);
    assertEquals(1, deliveries.pruneSettled(T0.plus(Duration.ofDays(60)), Duration.ofDays(30), 1));
    assertEquals(0, deliveries.pruneSettled(T0.plus(Duration.ofDays(60)), Duration.ofDays(30), 1));
    assertTrue(deliveries.admission(second.key()).isPresent());
    assertEquals(
        RelayDeliveries.State.UNCERTAIN, deliveries.delivery(claimed.key()).orElseThrow().state());
  }

  @Test
  void database_constraints_and_source_validation_refuse_inconsistent_claims() {
    var admission = deliveries.admit(first, ONE, T0);
    var key = admission.deliveries().getFirst();
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE relay_deliveries SET state='CLAIMED' WHERE id=?", key.id()));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE relay_deliveries SET receipt_id='fake' WHERE id=?", key.id()));
    jdbc.update(
        "UPDATE relay_admissions SET routing_source='different' WHERE scope_key=?",
        RelayScopeCodec.write(topic));
    assertThrows(
        IllegalArgumentException.class, () -> deliveries.claim(subscription, "worker", LEASE, T0));
    assertEquals(
        "READY",
        jdbc.queryForObject(
            "SELECT state FROM relay_deliveries WHERE id=?", String.class, key.id()));
    assertEquals(
        0L,
        jdbc.queryForObject("SELECT fence FROM relay_deliveries WHERE id=?", Long.class, key.id()));
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS))
        throw new IllegalStateException("test latch timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("test interrupted", interrupted);
    }
  }
}
