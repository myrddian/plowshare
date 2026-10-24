package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.approvals.ApprovalDelivery;
import io.aeyer.plowshare.server.approvals.ApprovalNotifications;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.approvals.RunApprovalStore;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.delivery.PersonDelivery;
import io.aeyer.plowshare.server.events.AccountPushes;
import io.aeyer.plowshare.server.events.Dispatcher;
import io.aeyer.plowshare.server.events.Inbox;
import io.aeyer.plowshare.server.events.InboxStore;
import io.aeyer.plowshare.server.events.JdbcInboxStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
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

/**
 * PostgreSQL verifies outbox publication, consumer leases, Inbox idempotency and retention gaps.
 */
@Tag("full-db")
@Testcontainers
class RelayApprovalInboxDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private static UnitOfWork transactions;
  private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
  private final java.util.concurrent.atomic.AtomicReference<Instant> notificationTime =
      new java.util.concurrent.atomic.AtomicReference<>(NOW);
  private RunApprovalStore approvals;
  private Inbox inbox;
  private AccountPushes pushes;
  private InboxStore inboxStore;
  private Relay relay;
  private RelayRepository broker;
  private RelaySourceRepository sources;
  private RelayConsumerRepository consumers;
  private RelayApprovalInboxRepository repository;
  private ApprovalDelivery notifications;
  private PersonDelivery.Voice voice;
  private Relay.TopicKey requested;
  private long project;

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
          public <T> T inTransaction(Supplier<T> work) {
            return Objects.requireNonNull(template.execute(status -> work.get()));
          }
        };
  }

  @BeforeEach
  void fresh() {
    jdbc.execute(
        "TRUNCATE projects,admins,run_approvals,user_inbox,relay_topics,relay_source_events CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash) VALUES('owner','fixture'),('other','fixture')");
    project =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO projects(name,workspace) VALUES('approval-fixture','fixture') RETURNING id",
                Long.class));
    jdbc.update(
        "INSERT INTO conversations(id,project_id,origin,lifecycle,created_at,budget_total,budget_spent,owner_handle) VALUES('cnv_owner',?,'turn','active',?,20,0,'owner')",
        project,
        java.sql.Timestamp.from(NOW));
    approvals = new RunApprovalStore(jdbc, () -> NOW);
    inboxStore = new JdbcInboxStore(jdbc);
    pushes = mock(AccountPushes.class);
    notificationTime.set(NOW);
    inbox = new Inbox(inboxStore, pushes, notificationTime::get);
    var conversations = mock(ConversationStore.class);
    when(conversations.ownerOf("cnv_owner")).thenReturn(Optional.of("owner"));
    voice = mock(PersonDelivery.Voice.class);
    var people =
        new PersonDelivery(
            conversations,
            voice,
            new PersonDelivery.Inbox() {
              public void notify(String handle, String kind, String text, String about) {
                inbox.notify(handle, kind, text, about);
              }

              public void notifyOnceFromLog(
                  String handle, String kind, String text, String about, String source) {
                inbox.notifyOnceFromLog(handle, kind, text, source, about);
              }
            });
    notifications = new ApprovalDelivery(approvals, people, inbox::settle);
    notifications.useRelayDelivery(true);
    broker =
        new JdbcRelayRepository(
            jdbc,
            transactions,
            new JdbcRelayPayloadStore(
                jdbc,
                transactions,
                java.time.Duration.ofHours(1),
                java.time.Duration.ofHours(24),
                java.time.Duration.ofDays(30)));
    relay = new DurableRelay(broker, () -> NOW);
    consumers =
        new JdbcRelayConsumerRepository(
            jdbc, transactions, new JdbcRelayDeliveryRepository(jdbc, transactions), broker);
    sources =
        new JdbcRelaySourceRepository(jdbc, transactions, relay, mock(RelayNativeRepository.class));
    repository =
        new JdbcRelayApprovalInboxRepository(
            jdbc,
            transactions,
            relay,
            consumers,
            io.aeyer.plowshare.server.events.EventsProperties.DEFAULT_QUESTION_ALERT_RETRY,
            notificationTime::get);
    requested = new Relay.TopicKey(project, "approval.requested");
  }

  private RunApproval ask() {
    return approvals.ask(
        project,
        "cnv_owner",
        "cnv_owner",
        "coder",
        "local",
        List.of("python3", "connect.py", "post", "Hello from Professor Farnsworth!"),
        "/fixture",
        null);
  }

  @Test
  void native_workers_deliver_an_approval_alert_without_any_local_signal() throws Exception {
    var alerted = new java.util.concurrent.CountDownLatch(1);
    var retried = new java.util.concurrent.CountDownLatch(1);
    var attempts = new java.util.concurrent.atomic.AtomicInteger();
    doAnswer(
            call -> {
              if (attempts.incrementAndGet() == 1) alerted.countDown();
              else retried.countDown();
              return null;
            })
        .when(pushes)
        .push(eq("owner"), any());
    var adapter = new DurableRelayApprovalInbox(relay, consumers, repository, notifications);
    var properties = new RelayWorkerProperties();
    properties.setIdleInterval(Duration.ofSeconds(1));
    properties.setMaxWorkers(8);
    properties.setConcurrency(2);
    var approval = ask();
    try (var workers =
        new RelayInternalWorkers(
            sources,
            mock(RelayNativeRepository.class),
            consumers,
            relay,
            new LocalRelayPublicationSignals(),
            mock(Dispatcher.class),
            properties,
            true)) {
      workers.useApprovalInbox(() -> adapter);
      workers.start();
      assertTrue(alerted.await(5, java.util.concurrent.TimeUnit.SECONDS));
      // Wait for the first availability event to be consumed, then advance only the alert
      // clock. No Relay publication or local signal asks the supervisor to retry this push.
      long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
      var subscription =
          new Relay.SubscriptionKey(requested, RelayApprovalInboxRepository.SUBSCRIBER);
      while (relay.read(subscription, 10).subscription().seenThrough() < 1
          && System.nanoTime() < deadline) Thread.sleep(10);
      assertEquals(1, relay.read(subscription, 10).subscription().seenThrough());
      notificationTime.set(NOW.plusSeconds(30));
      assertTrue(retried.await(5, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(1, inbox.list("owner", false, 0, 10).size());
      assertEquals(1, inboxStore.unread("owner"));
      assertEquals(RunApproval.ASKED, approvals.find(approval.id()).orElseThrow().state());
      verifyNoInteractions(voice);
    }
  }

  @Test
  void upgrade_republishes_missing_pending_and_resolution_notices_without_changing_decisions() {
    jdbc.execute("CREATE DATABASE approval_upgrade");
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/approval_upgrade"),
            POSTGRES.getUsername(),
            POSTGRES.getPassword());
    Flyway.configure().dataSource(source).target("151").load().migrate();
    var before = new JdbcTemplate(source);
    before.update("INSERT INTO admins(handle,password_hash) VALUES('owner','fixture')");
    long projectId =
        Objects.requireNonNull(
            before.queryForObject(
                "INSERT INTO projects(name,workspace) VALUES('upgrade-fixture','fixture') RETURNING id",
                Long.class));
    before.update(
        "INSERT INTO conversations(id,project_id,origin,lifecycle,created_at,budget_total,budget_spent,owner_handle) VALUES('cnv_upgrade',?,'turn','active',?,20,0,'owner')",
        projectId,
        java.sql.Timestamp.from(NOW));
    var store = new RunApprovalStore(before, () -> NOW);
    var pending =
        store.ask(
            projectId,
            "cnv_upgrade",
            "cnv_upgrade",
            "coder",
            "local",
            List.of("echo", "hello world"),
            "/fixture",
            null);
    store.delivered(pending.id());
    var settled =
        store.ask(
            projectId,
            "cnv_upgrade",
            "cnv_upgrade",
            "coder",
            "local",
            List.of("echo", "settled"),
            "/fixture",
            null);
    new JdbcInboxStore(before)
        .noticeFromLog(
            "owner",
            "approval",
            "Review command",
            "cnv_upgrade",
            ApprovalDelivery.about(settled.id()),
            NOW);
    // Seed the historical decision using the pre-upgrade schema. The current store's deny
    // path also writes denial_reason, which is introduced by the later suspended-call migration.
    before.update(
        "UPDATE run_approvals SET state='denied',answered_by='owner',answered_at=? WHERE id=?",
        java.sql.Timestamp.from(NOW),
        settled.id());
    before.execute("DELETE FROM relay_source_events");
    Flyway.configure().dataSource(source).load().migrate();
    assertEquals(
        1,
        before.queryForObject(
            "SELECT count(*) FROM relay_source_events WHERE topic='approval.requested' AND subject=?",
            Integer.class,
            pending.id()));
    assertEquals(
        1,
        before.queryForObject(
            "SELECT count(*) FROM relay_source_events WHERE topic='approval.resolved' AND subject=?",
            Integer.class,
            settled.id()));
    assertEquals(RunApproval.ASKED, store.find(pending.id()).orElseThrow().state());
    assertEquals(RunApproval.DENIED, store.find(settled.id()).orElseThrow().state());
    assertEquals(List.of("echo", "hello world"), store.find(pending.id()).orElseThrow().argv());
  }

  @Test
  void a_foreground_request_flows_through_relay_to_one_owning_inbox_and_resolution_retires_it() {
    var approval = ask();
    assertEquals(0, inboxStore.unread("owner"));
    assertTrue(sources.publishNext(requested));
    assertTrue(repository.ready(null, 10).contains(requested));
    var adapter = new DurableRelayApprovalInbox(relay, consumers, repository, notifications);
    adapter.consume(requested, 10);
    assertEquals(1, inboxStore.unread("owner"));
    assertEquals(0, inboxStore.unread("other"));
    assertEquals("approval:" + approval.id(), inbox.list("owner", true, 0, 10).getFirst().about());
    assertEquals(RunApproval.ASKED, approvals.find(approval.id()).orElseThrow().state());
    // Simulate losing the consumer acknowledgement after Inbox commit.
    jdbc.update(
        "UPDATE relay_subscriptions SET seen_through=0 WHERE subscriber=?",
        RelayApprovalInboxRepository.SUBSCRIBER);
    adapter.consume(requested, 10);
    assertEquals(1, inbox.list("owner", false, 0, 10).size());
    assertTrue(approvals.deny(approval.id(), "owner"));
    assertEquals(
        1, inboxStore.unread("owner"), "direct settlement delivery was intentionally absent");
    var resolved = new Relay.TopicKey(project, "approval.resolved");
    assertTrue(sources.publishNext(resolved));
    adapter.consume(resolved, 10);
    assertEquals(0, inboxStore.unread("owner"));
    verifyNoInteractions(voice);
  }

  @Test
  void notification_failure_recovers_from_pending_source_after_relay_offset_advanced() {
    var approval = ask();
    assertTrue(sources.publishNext(requested));
    var failing = new AtomicBoolean(true);
    ApprovalNotifications intermittent =
        new ApprovalNotifications() {
          public boolean requested(String id, long projectId) {
            if (failing.get()) throw new IllegalStateException("Inbox temporarily unavailable");
            return notifications.requested(id, projectId);
          }

          public void resolved(String id, long projectId) {
            notifications.resolved(id, projectId);
          }
        };
    var adapter = new DurableRelayApprovalInbox(relay, consumers, repository, intermittent);
    adapter.consume(requested, 10);
    assertEquals(0, inboxStore.unread("owner"));
    assertEquals(
        1,
        relay
            .read(new Relay.SubscriptionKey(requested, RelayApprovalInboxRepository.SUBSCRIBER), 10)
            .subscription()
            .seenThrough());
    assertTrue(repository.ready(null, 10).contains(requested));
    failing.set(false);
    adapter.consume(requested, 10);
    assertEquals(1, inboxStore.unread("owner"));
    assertEquals(RunApproval.ASKED, approvals.find(approval.id()).orElseThrow().state());
    verifyNoInteractions(voice);
  }

  @Test
  void expired_publications_and_legacy_delivered_markers_do_not_swallow_pending_decisions() {
    var approval = ask();
    approvals.delivered(approval.id());
    assertTrue(sources.publishNext(requested));
    relay.subscribe(
        new Relay.SubscriptionKey(requested, RelayApprovalInboxRepository.SUBSCRIBER),
        Relay.Start.OLDEST_RETAINED);
    assertEquals(1, broker.prune(requested, NOW.plus(Duration.ofDays(5)), 100));
    var adapter = new DurableRelayApprovalInbox(relay, consumers, repository, notifications);
    adapter.consume(requested, 10);
    assertEquals(1, inboxStore.unread("owner"));
    assertEquals(
        1,
        jdbc.queryForObject("SELECT count(*) FROM relay_approval_gap_recoveries", Integer.class));
    assertEquals(RunApproval.ASKED, approvals.find(approval.id()).orElseThrow().state());
    verifyNoInteractions(voice);
  }

  @Test
  void a_lost_and_expired_resolution_cannot_leave_a_settled_decision_in_the_inbox() {
    var approval = ask();
    assertTrue(sources.publishNext(requested));
    var adapter = new DurableRelayApprovalInbox(relay, consumers, repository, notifications);
    adapter.consume(requested, 10);
    assertTrue(approvals.deny(approval.id(), "owner"));
    var resolved = new Relay.TopicKey(project, "approval.resolved");
    assertTrue(sources.publishNext(resolved));
    relay.subscribe(
        new Relay.SubscriptionKey(resolved, RelayApprovalInboxRepository.SUBSCRIBER),
        Relay.Start.OLDEST_RETAINED);
    assertEquals(1, broker.prune(resolved, NOW.plus(Duration.ofDays(5)), 100));
    assertTrue(repository.ready(null, 10).contains(resolved));
    adapter.consume(resolved, 10);
    assertEquals(0, inboxStore.unread("owner"));
    assertEquals(RunApproval.DENIED, approvals.find(approval.id()).orElseThrow().state());
    verifyNoInteractions(voice);
  }

  @Test
  void a_missed_push_retries_after_restart_until_read_or_answered_without_replaying_commands() {
    var approval = ask();
    assertTrue(sources.publishNext(requested));
    var adapter = new DurableRelayApprovalInbox(relay, consumers, repository, notifications);
    // The first push reaches no client. AccountPushes cannot acknowledge end-to-end receipt.
    adapter.consume(requested, 10);
    org.mockito.Mockito.verify(pushes, org.mockito.Mockito.times(1)).push(eq("owner"), any());
    assertTrue(!repository.ready(null, 10).contains(requested));
    notificationTime.set(NOW.plusSeconds(29));
    assertTrue(!repository.ready(null, 10).contains(requested));
    notificationTime.set(NOW.plusSeconds(30));
    assertTrue(repository.ready(null, 10).contains(requested));
    // Reconstruct the worker after its offset advanced: retries are durable, not a local timer.
    adapter = new DurableRelayApprovalInbox(relay, consumers, repository, notifications);
    adapter.consume(requested, 10);
    org.mockito.Mockito.verify(pushes, org.mockito.Mockito.times(2)).push(eq("owner"), any());
    assertEquals(1, inbox.list("owner", false, 0, 10).size());
    var item = inbox.list("owner", true, 0, 10).getFirst();
    inboxStore.markRead("owner", List.of(item.id()), notificationTime.get());
    notificationTime.set(NOW.plusSeconds(60));
    assertTrue(!repository.ready(null, 10).contains(requested));
    adapter.consume(requested, 10);
    org.mockito.Mockito.verify(pushes, org.mockito.Mockito.times(2)).push(eq("owner"), any());
    assertEquals(RunApproval.ASKED, approvals.find(approval.id()).orElseThrow().state());
    assertTrue(approvals.deny(approval.id(), "owner"));
    notificationTime.set(NOW.plusSeconds(90));
    adapter.consume(requested, 10);
    org.mockito.Mockito.verify(pushes, org.mockito.Mockito.times(2)).push(eq("owner"), any());
    verifyNoInteractions(voice);
  }
}
