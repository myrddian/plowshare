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

/** Real PostgreSQL is required for migration constraints, transaction rollback and topic locks. */
@Tag("full-db")
@Testcontainers
class JdbcRelayRepositoryTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Instant T0 = Instant.parse("2026-10-05T01:00:00Z");
  private static JdbcTemplate jdbc;
  private static UnitOfWork transactions;
  private RelayRepository repository;
  private Relay.TopicKey topic;
  private Relay.SubscriptionKey a;
  private Relay.SubscriptionKey b;

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
    jdbc.update("DELETE FROM projects WHERE name IN ('relay-fixture', 'relay-other')");
    Long project =
        jdbc.queryForObject(
            "INSERT INTO projects(name,workspace) VALUES('relay-fixture','fixture') RETURNING id",
            Long.class);
    topic = new Relay.TopicKey(Objects.requireNonNull(project), "release.observed");
    a = new Relay.SubscriptionKey(topic, "route-a");
    b = new Relay.SubscriptionKey(topic, "route-b");
    repository = new JdbcRelayRepository(jdbc, transactions);
    repository.configureTopic(topic, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
  }

  private static Relay.Draft draft(String id) {
    return new Relay.Draft(
        id,
        "release-source",
        T0.minus(Duration.ofDays(30)),
        "release-cycle",
        null,
        new RelayPayload.Text("released " + id));
  }

  @Test
  void receipt_lookup_is_scoped_and_retention_absence_remains_explicit() {
    var original = repository.append(topic, draft("shared"), T0);
    var otherTopic = new Relay.TopicKey(topic.projectId(), "release.other");
    repository.configureTopic(otherTopic, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    var other = repository.append(otherTopic, draft("shared"), T0);
    Long project =
        jdbc.queryForObject(
            "INSERT INTO projects(name,workspace) VALUES('relay-other','fixture') RETURNING id",
            Long.class);
    var otherProject = new Relay.TopicKey(Objects.requireNonNull(project), topic.name());
    repository.configureTopic(otherProject, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    var foreign = repository.append(otherProject, draft("shared"), T0);
    assertEquals(java.util.Optional.of(original), repository.retained(topic, "shared"));
    assertEquals(java.util.Optional.of(other), repository.retained(otherTopic, "shared"));
    assertEquals(java.util.Optional.of(foreign), repository.retained(otherProject, "shared"));
    assertTrue(repository.retained(topic, "missing").isEmpty());
    assertEquals(1, repository.prune(topic, T0.plus(Duration.ofDays(4)), 100));
    assertTrue(repository.retained(topic, "shared").isEmpty());
    assertEquals(java.util.Optional.of(other), repository.retained(otherTopic, "shared"));
    assertThrows(IllegalArgumentException.class, () -> repository.retained(topic, "bad\n"));
  }

  @Test
  void publication_receiver_persists_intent_effect_and_receipt_outside_transactions() {
    var log = new DurableRelay(repository, () -> T0);
    var deliveries =
        new DurableRelayDeliveries(new JdbcRelayDeliveryRepository(jdbc, transactions), () -> T0);
    var target = new Relay.TopicKey(topic.projectId(), "release.forwarded");
    log.configureTopic(target, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    var dispatch =
        dispatch(
            deliveries, new ForwardRelayReceiver(log, new JdbcRelayForwardingHistory(jdbc), 8));
    var publication = log.publish(topic, draft("first"));
    log.subscribe(a, Relay.Start.OLDEST_RETAINED);
    var admission =
        deliveries.admit(
            new RelayDeliveries.AdmissionKey(a, publication.position()), forwardingDecision());
    var access = new RelayProjectFiles.Access("operator", "relay-fixture", topic.projectId());
    // A transaction joining admission/claim preparation would leave intent uncommitted at the
    // receiver call. The production guard must fail before either a claim or publication effect.
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.inTransaction(
                () -> dispatch.next(access, a, "worker", Duration.ofSeconds(30))));
    assertEquals(
        RelayDeliveries.State.READY,
        deliveries.delivery(admission.deliveries().getFirst()).orElseThrow().state());
    var accepted = dispatch.next(access, a, "worker", Duration.ofSeconds(30)).orElseThrow();
    assertEquals(RelayDeliveries.State.ACCEPTED, accepted.state());
    var forwarded = log.retained(target, "relay:" + accepted.key().id()).orElseThrow();
    assertEquals(publication.event().payload(), forwarded.event().payload());
    assertEquals(publication.event().eventId(), forwarded.event().causationId());
    assertEquals("relay.publication", accepted.receipt().namespace());
    assertEquals(accepted, deliveries.delivery(accepted.key()).orElseThrow());
    assertTrue(dispatch.next(access, a, "worker", Duration.ofSeconds(30)).isEmpty());
    assertEquals(
        1,
        repository
            .configureTopic(target, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault())
            .lastPosition());
  }

  @Test
  void
      publication_accepted_before_exception_reconciles_without_replay_and_expired_receipts_stay_unknown() {
    var time = new java.util.concurrent.atomic.AtomicReference<>(T0);
    var log = new DurableRelay(repository, time::get);
    var deliveries =
        new DurableRelayDeliveries(new JdbcRelayDeliveryRepository(jdbc, transactions), time::get);
    var target = new Relay.TopicKey(topic.projectId(), "release.forwarded");
    log.configureTopic(target, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    var forward = new ForwardRelayReceiver(log, new JdbcRelayForwardingHistory(jdbc), 8);
    RelayReceiver interrupted =
        new RelayReceiver() {
          public void require(Request request) {
            forward.require(request);
          }

          public Result dispatch(Request request) {
            forward.dispatch(request);
            throw new IllegalStateException("accepted but receipt was lost");
          }

          public java.util.Optional<RelayDeliveries.Resolution> inspect(Request request) {
            return forward.inspect(request);
          }
        };
    var dispatch = dispatch(deliveries, interrupted);
    var access = new RelayProjectFiles.Access("operator", "relay-fixture", topic.projectId());
    log.subscribe(a, Relay.Start.OLDEST_RETAINED);
    for (String id : List.of("first", "second")) {
      var publication = log.publish(topic, draft(id));
      deliveries.admit(
          new RelayDeliveries.AdmissionKey(a, publication.position()), forwardingDecision());
      var uncertain = dispatch.next(access, a, "worker", Duration.ofSeconds(30)).orElseThrow();
      assertEquals(RelayDeliveries.State.UNCERTAIN, uncertain.state());
      assertTrue(dispatch.next(access, a, "worker", Duration.ofSeconds(30)).isEmpty());
      if (id.equals("first")) {
        assertEquals(
            RelayDeliveries.State.ACCEPTED, dispatch.reconcile(access, uncertain.key()).state());
      } else {
        time.set(T0.plus(Duration.ofDays(4)));
        assertEquals(2, repository.prune(target, time.get(), 100));
        assertEquals(uncertain, dispatch.reconcile(access, uncertain.key()));
        assertTrue(log.retained(target, "relay:" + uncertain.key().id()).isEmpty());
      }
    }
    assertEquals(
        2,
        repository
            .configureTopic(target, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault())
            .lastPosition());
  }

  private RelayDispatch dispatch(RelayDeliveries deliveries, RelayReceiver receiver) {
    var members = org.mockito.Mockito.mock(io.aeyer.plowshare.server.archive.ProjectMembers.class);
    var projects =
        org.mockito.Mockito.mock(io.aeyer.plowshare.server.archive.ProjectWorkspaces.class);
    org.mockito.Mockito.when(members.mayWork("relay-fixture", "operator")).thenReturn(true);
    org.mockito.Mockito.when(projects.id("relay-fixture")).thenReturn(topic.projectId());
    var receivers =
        new RegisteredRelayReceivers(
            List.of(new RelayReceivers.Binding("relay.publish", receiver)));
    return new RelayConfiguration().relayDispatch(deliveries, receivers, members, projects);
  }

  private static RelayDeliveries.Decision forwardingDecision() {
    return new RelayDeliveries.Decision(
        RelayDeliveries.SourcePin.of("notices/routes.js", "export function route(){return []; }"),
        List.of(new RelayDeliveries.Branch("forward", "relay.publish", null, "release.forwarded")));
  }

  @Test
  void retained_identity_returns_original_time_and_conflicts_do_not_allocate_positions() {
    var first = repository.append(topic, draft("one"), T0);
    assertEquals(first, repository.append(topic, draft("one"), T0.plusSeconds(5)));
    var conflicting =
        new Relay.Draft(
            "one", "release-source", T0, null, null, new RelayPayload.Text("different"));
    assertThrows(IllegalArgumentException.class, () -> repository.append(topic, conflicting, T0));
    assertEquals(2, repository.append(topic, draft("two"), T0).position());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            repository.configureTopic(
                topic, RelayPayload.Kind.EMPTY, Relay.Policy.systemDefault()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            repository.append(
                topic,
                new Relay.Draft("empty", "source", T0, null, null, new RelayPayload.Empty()),
                T0));
  }

  @Test
  void each_subscriber_has_an_independent_monotonic_position_and_read_has_no_effect() {
    repository.subscribe(a, Relay.Start.OLDEST_RETAINED, T0);
    repository.subscribe(b, Relay.Start.OLDEST_RETAINED, T0);
    repository.append(topic, draft("one"), T0);
    repository.append(topic, draft("two"), T0.plusSeconds(1));
    assertEquals(1, repository.read(a, 1).publications().size());
    assertEquals(2, repository.unread(a).count());
    var seen = repository.advanceSeen(a, 1, T0.plusSeconds(2));
    assertEquals(seen, repository.advanceSeen(a, 1, T0.plusSeconds(3)));
    assertEquals(seen, repository.subscribe(a, Relay.Start.LATEST, T0.plusSeconds(4)));
    assertEquals(1, repository.unread(a).count());
    assertEquals(2, repository.unread(b).count());
    assertThrows(IllegalArgumentException.class, () -> repository.advanceSeen(a, 3, T0));
    var latest = new Relay.SubscriptionKey(topic, "latest");
    assertEquals(2, repository.subscribe(latest, Relay.Start.LATEST, T0).seenThrough());
    assertEquals(0, repository.unread(latest).count());
  }

  @Test
  void retention_uses_publication_age_and_unread_records_expire_with_an_explicit_gap() {
    repository.subscribe(a, Relay.Start.OLDEST_RETAINED, T0);
    repository.append(topic, draft("one"), T0);
    repository.append(topic, draft("two"), T0.plusSeconds(60));
    assertEquals(0, repository.prune(topic, T0.plus(Duration.ofDays(4)).minusSeconds(1), 100));
    assertEquals(List.of(topic), repository.topicsToPrune(T0.plus(Duration.ofDays(4)), 100));
    assertEquals(1, repository.prune(topic, T0.plus(Duration.ofDays(4)), 100));
    var page = repository.read(a, 100);
    assertEquals(new Relay.Gap(0, 1), page.gap().orElseThrow());
    assertEquals(2, page.publications().getFirst().position());
    assertEquals(1, repository.unread(a).count());
    assertEquals(0, repository.unread(a).subscription().seenThrough());
    assertThrows(IllegalStateException.class, () -> repository.advanceSeen(a, 2, T0));
    assertThrows(IllegalArgumentException.class, () -> repository.acknowledgeGap(a, 2, T0));
    var acknowledged = repository.acknowledgeGap(a, 1, T0.plus(Duration.ofDays(4)));
    assertEquals(acknowledged, repository.acknowledgeGap(a, 1, T0.plus(Duration.ofDays(5))));
    assertTrue(repository.read(a, 100).gap().isEmpty());
    assertEquals(2, repository.advanceSeen(a, 2, T0.plus(Duration.ofDays(4))).seenThrough());
  }

  @Test
  void stale_gap_acknowledgement_does_not_swallow_a_newer_gap() {
    repository.subscribe(a, Relay.Start.OLDEST_RETAINED, T0);
    repository.append(topic, draft("one"), T0);
    repository.append(topic, draft("two"), T0);
    Instant expired = T0.plus(Duration.ofDays(4));
    assertEquals(1, repository.prune(topic, expired, 1));
    assertEquals(1, repository.read(a, 1).gap().orElseThrow().throughInclusive());
    assertEquals(1, repository.prune(topic, expired, 1));
    assertThrows(IllegalStateException.class, () -> repository.acknowledgeGap(a, 1, expired));
    assertEquals(0, repository.unread(a).subscription().seenThrough());
    assertEquals(2, repository.acknowledgeGap(a, 2, expired).seenThrough());
  }

  @Test
  void record_caps_and_policy_changes_are_bounded_and_keep_the_last_position() {
    repository.subscribe(a, Relay.Start.OLDEST_RETAINED, T0);
    for (String id : List.of("one", "two", "three", "four"))
      repository.append(topic, draft(id), T0);
    assertTrue(repository.topicsToPrune(T0, 100).isEmpty());
    repository.configureTopic(
        topic, RelayPayload.Kind.TEXT, new Relay.Policy(Duration.ofDays(30), 2L));
    assertEquals(List.of(topic), repository.topicsToPrune(T0, 100));
    assertEquals(1, repository.prune(topic, T0, 1));
    assertEquals(1, repository.prune(topic, T0, 1));
    assertEquals(0, repository.prune(topic, T0, 1));
    assertEquals(2, repository.unread(a).count());
    repository.configureTopic(
        topic, RelayPayload.Kind.TEXT, new Relay.Policy(Duration.ofSeconds(1), null));
    assertEquals(2, repository.prune(topic, T0.plusSeconds(1), 100));
    var late = new Relay.SubscriptionKey(topic, "late");
    assertEquals(4, repository.subscribe(late, Relay.Start.OLDEST_RETAINED, T0).seenThrough());
    assertTrue(repository.unread(late).gap().isEmpty());
    // An expired ID is allowed again, but positions never reset or reuse the expired slot.
    assertEquals(5, repository.append(topic, draft("one"), T0.plusSeconds(2)).position());
  }

  @Test
  void clock_correction_cannot_make_retention_delete_a_hole_in_the_log() {
    repository.subscribe(a, Relay.Start.OLDEST_RETAINED, T0);
    repository.configureTopic(
        topic, RelayPayload.Kind.TEXT, new Relay.Policy(Duration.ofSeconds(10), null));
    repository.append(topic, draft("one"), T0.plusSeconds(20));
    repository.append(topic, draft("two"), T0);
    assertEquals(0, repository.prune(topic, T0.plusSeconds(15), 100));
    assertTrue(repository.topicsToPrune(T0.plusSeconds(15), 100).isEmpty());
    assertEquals(2, repository.prune(topic, T0.plusSeconds(30), 100));
    assertEquals(new Relay.Gap(0, 2), repository.unread(a).gap().orElseThrow());
  }

  @Test
  void append_joins_domain_transaction_and_rollback_does_not_leave_a_position_hole() {
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.inTransaction(
                () -> {
                  repository.append(topic, draft("rolled-back"), T0);
                  throw new IllegalStateException("domain transition failed");
                }));
    var committed = repository.append(topic, draft("committed"), T0);
    assertEquals(1, committed.position());
    repository.subscribe(a, Relay.Start.OLDEST_RETAINED, T0);
    assertEquals(List.of(committed), repository.read(a, 100).publications());
  }

  @Test
  void topic_lock_orders_appends_by_commit_before_readers_can_skip_them() throws Exception {
    repository.subscribe(a, Relay.Start.OLDEST_RETAINED, T0);
    var appended = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var attempted = new CountDownLatch(1);
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          threads.submit(
              () ->
                  transactions.inTransaction(
                      () -> {
                        var publication = repository.append(topic, draft("one"), T0);
                        appended.countDown();
                        await(release);
                        return publication;
                      }));
      try {
        assertTrue(appended.await(10, TimeUnit.SECONDS));
        var second =
            threads.submit(
                () -> {
                  attempted.countDown();
                  return repository.append(topic, draft("two"), T0);
                });
        assertTrue(attempted.await(10, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));
        var reader = threads.submit(() -> repository.read(a, 100));
        assertThrows(TimeoutException.class, () -> reader.get(200, TimeUnit.MILLISECONDS));
        release.countDown();
        assertEquals(1, first.get(10, TimeUnit.SECONDS).position());
        assertEquals(2, second.get(10, TimeUnit.SECONDS).position());
        assertEquals(
            "one", reader.get(10, TimeUnit.SECONDS).publications().getFirst().event().eventId());
        assertEquals(
            List.of("one", "two"),
            repository.read(a, 100).publications().stream().map(p -> p.event().eventId()).toList());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void concurrent_same_identity_admits_one_publication() throws Exception {
    var start = new CountDownLatch(1);
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          threads.submit(
              () -> {
                await(start);
                return repository.append(topic, draft("one"), T0);
              });
      var second =
          threads.submit(
              () -> {
                await(start);
                return repository.append(topic, draft("one"), T0.plusSeconds(1));
              });
      start.countDown();
      assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
      assertEquals(2, repository.append(topic, draft("two"), T0).position());
    }
  }

  @Test
  void project_scope_is_part_of_publication_and_subscriber_identity() {
    Long otherId =
        jdbc.queryForObject(
            "INSERT INTO projects(name,workspace) VALUES('relay-other','fixture') RETURNING id",
            Long.class);
    var other = new Relay.TopicKey(Objects.requireNonNull(otherId), topic.name());
    repository.configureTopic(other, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    var otherSubscriber = new Relay.SubscriptionKey(other, a.subscriber());
    repository.subscribe(a, Relay.Start.OLDEST_RETAINED, T0);
    repository.subscribe(otherSubscriber, Relay.Start.OLDEST_RETAINED, T0);
    repository.append(topic, draft("same-id"), T0);
    assertTrue(repository.read(otherSubscriber, 100).publications().isEmpty());
    assertEquals(1, repository.append(other, draft("same-id"), T0).position());
    repository.advanceSeen(a, 1, T0);
    assertEquals(1, repository.unread(otherSubscriber).count());
    jdbc.update("DELETE FROM projects WHERE id=?", other.projectId());
    assertThrows(IllegalStateException.class, () -> repository.unread(otherSubscriber));
    assertEquals(0, repository.unread(a).count());
  }

  @Test
  void registered_schedule_and_empty_families_round_trip_through_their_topics() {
    var schedule = new Relay.TopicKey(topic.projectId(), "schedule.due");
    var subscriber = new Relay.SubscriptionKey(schedule, "schedule-notices");
    repository.configureTopic(
        schedule, RelayPayload.Kind.SCHEDULE_DUE, Relay.Policy.systemDefault());
    repository.subscribe(subscriber, Relay.Start.OLDEST_RETAINED, T0);
    var occurrence =
        new Relay.Draft(
            "morning-occurrence",
            "scheduler",
            T0,
            null,
            null,
            new RelayPayload.ScheduleDue("morning", "daily", T0));
    var published = repository.append(schedule, occurrence, T0.plusSeconds(1));
    assertEquals(List.of(published), repository.read(subscriber, 100).publications());
    assertEquals(published, repository.append(schedule, occurrence, T0.plusSeconds(2)));
    var empty = new Relay.TopicKey(topic.projectId(), "custom.signal");
    repository.configureTopic(empty, RelayPayload.Kind.EMPTY, Relay.Policy.systemDefault());
    var signal =
        new Relay.Draft("signal-one", "signal-source", T0, null, null, new RelayPayload.Empty());
    assertEquals(
        repository.append(empty, signal, T0), repository.append(empty, signal, T0.plusSeconds(1)));
  }

  @Test
  void constraints_reject_invalid_policy_and_payload_shape_and_unknown_payloads_fail_closed() {
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE relay_topics SET retention_seconds=0 WHERE project_id=?",
                topic.projectId()));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE relay_topics SET max_records=0 WHERE project_id=?", topic.projectId()));
    repository.append(topic, draft("one"), T0);
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE relay_publications SET payload='[]'::jsonb WHERE scope_key=?",
                RelayScopeCodec.write(topic)));
    jdbc.update(
        "UPDATE relay_publications SET payload='{\"unknown\":1}'::jsonb WHERE scope_key=?",
        RelayScopeCodec.write(topic));
    repository.subscribe(a, Relay.Start.OLDEST_RETAINED, T0);
    assertThrows(IllegalArgumentException.class, () -> repository.read(a, 100));
    assertEquals(0, repository.unread(a).subscription().seenThrough());
    assertThrows(IllegalArgumentException.class, () -> repository.append(topic, draft("one"), T0));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM relay_publications WHERE scope_key=?",
            Integer.class,
            RelayScopeCodec.write(topic)));
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
