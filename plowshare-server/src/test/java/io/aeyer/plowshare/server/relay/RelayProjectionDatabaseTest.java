package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.cache.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Actual migration, bounded SQL, commit/rollback, account filtering and remote LISTEN recovery. */
@Tag("full-db")
@Testcontainers
class RelayProjectionDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Instant AT = Instant.parse("2026-10-20T00:00:00Z");
  private static DataSource data;
  private static JdbcTemplate jdbc;
  private static UnitOfWork work;

  @BeforeAll
  static void migrate() {
    data = source();
    Flyway.configure().dataSource(data).load().migrate();
    jdbc = new JdbcTemplate(data);
    var transactions = new TransactionTemplate(new DataSourceTransactionManager(data));
    work =
        new UnitOfWork() {
          public <T> T inTransaction(Supplier<T> action) {
            return Objects.requireNonNull(transactions.execute(status -> action.get()));
          }
        };
  }

  @Test
  void warmDtosSkipAllProjectionQueriesAndDurableVersionsRecoverAppendExpiryAndRecreation() {
    try (var fixture = new Fixture()) {
      var topics = fixture.logs.topics(fixture.topic.scope(), 100);
      var empty = fixture.logs.read(fixture.topic, 0, 100, "reader");
      fixture.counting.queries.clear();
      assertSame(topics, fixture.logs.topics(fixture.topic.scope(), 100));
      assertSame(empty, fixture.logs.read(fixture.topic, 0, 100, "reader"));
      assertTrue(fixture.counting.queries.isEmpty());
      fixture.append("first", "message");
      var first = fixture.logs.read(fixture.topic, 0, 100, "reader");
      assertEquals(1, first.events().size());
      assertEquals(1, fixture.logs.topics(fixture.topic.scope(), 100).getFirst().lastPosition());
      fixture.broker.advanceSeen(fixture.subscription, 1, AT);
      assertEquals(
          1,
          fixture
              .logs
              .read(fixture.topic, 0, 100, "reader")
              .subscribers()
              .getFirst()
              .seenThrough());
      assertEquals(1, fixture.broker.prune(fixture.topic, AT.plus(Duration.ofDays(365)), 100));
      var expired = fixture.logs.read(fixture.topic, 0, 100, "reader");
      assertTrue(expired.events().isEmpty());
      assertEquals(1, expired.topic().expiredThrough());
      jdbc.update(
          "DELETE FROM relay_topics WHERE scope_key=? AND name=?",
          RelayScopeCodec.write(fixture.topic),
          fixture.topic.name());
      assertThrows(
          IllegalArgumentException.class, () -> fixture.logs.read(fixture.topic, 0, 100, "reader"));
      fixture.broker.configureTopic(
          fixture.topic, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
      var recreated = fixture.logs.read(fixture.topic, 0, 100, "reader");
      assertNotEquals(expired.topic().generation(), recreated.topic().generation());
      assertEquals(0, recreated.topic().lastPosition());
      assertTrue(recreated.subscribers().isEmpty());
    }
  }

  @Test
  void dirtyOuterReadAndRollbackNeverPublishUncommittedMessages() {
    try (var fixture = new Fixture()) {
      fixture.logs.read(fixture.topic, 0, 100, "reader");
      assertThrows(
          IllegalStateException.class,
          () ->
              work.inTransaction(
                  () -> {
                    fixture.append("rollback", "private uncommitted");
                    assertEquals(
                        1, fixture.logs.read(fixture.topic, 0, 100, "reader").events().size());
                    throw new IllegalStateException("fixture rollback");
                  }));
      assertTrue(fixture.logs.read(fixture.topic, 0, 100, "reader").events().isEmpty());
      assertEquals(0, fixture.broker.topic(fixture.topic).lastPosition());
    }
  }

  @Test
  void accountFilteredBranchesFollowReceiptsExecutionOwnershipAndProjectMetadata() {
    try (var fixture = new Fixture()) {
      var publication = fixture.append("first", "message");
      var deliveries = new JdbcRelayDeliveryRepository(jdbc, work);
      var decision =
          new RelayDeliveries.Decision(
              RelayDeliveries.SourcePin.of(
                  "notices/routes.js", "export function route(){return [];};"),
              List.of(
                  new RelayDeliveries.Branch(
                      "review",
                      "script.run",
                      RelayDeliveries.SourcePin.of(
                          "notices/scripts/review.js",
                          "export function step(){return {finish:'done'};}"),
                      null,
                      new RelayWork("worker", null, null))));
      deliveries.admit(
          new RelayDeliveries.AdmissionKey(fixture.subscription, publication.position()),
          decision,
          AT);
      var claim =
          deliveries.claim(fixture.subscription, "worker", Duration.ofMinutes(2), AT).orElseThrow();
      var prepared = deliveries.prepareDispatch(claim.claim(), AT);
      var request =
          new RelayReceiver.Request(
              new RelayProjectFiles.Access("reader", fixture.project, fixture.topic.projectId()),
              prepared);
      var executions = new JdbcRelayExecutions(jdbc, () -> AT);
      assertTrue(executions.begin(request));
      var conversation =
          new ConversationStore(jdbc, () -> AT, null)
              .log(Origin.EVENT, Home.of(fixture.project), "worker", null, Budget.of(5), "reader")
              .id();
      var receipt = new RelayDeliveries.Receipt("job", "job_fixture");
      executions.accepted(request, receipt, conversation);
      deliveries.accepted(prepared.claim(), receipt, AT);
      var owner = fixture.logs.read(fixture.topic, 0, 100, "reader");
      var other = fixture.logs.read(fixture.topic, 0, 100, "other-reader");
      assertEquals(receipt, owner.branches().getFirst().receipt());
      assertEquals(conversation, owner.branches().getFirst().conversation());
      assertNull(other.branches().getFirst().receipt());
      assertNull(other.branches().getFirst().conversation());
      fixture.counting.queries.clear();
      assertSame(owner, fixture.logs.read(fixture.topic, 0, 100, "reader"));
      assertSame(other, fixture.logs.read(fixture.topic, 0, 100, "other-reader"));
      assertTrue(fixture.counting.queries.isEmpty());
      var unchanged =
          fixture.versions.capture(Set.of(ProjectionVersions.Domain.RELAY)).orElseThrow();
      jdbc.update(
          "UPDATE conversations SET log_closed_at=? WHERE id=?",
          java.sql.Timestamp.from(AT),
          conversation);
      assertTrue(
          fixture.versions.current(unchanged),
          "Non-projected conversation changes must not flush Relay during inference");
      jdbc.update(
          "UPDATE relay_executions SET account='other-reader' WHERE request_id=?",
          request.identity());
      assertNull(
          fixture.logs.read(fixture.topic, 0, 100, "reader").branches().getFirst().receipt());
      assertEquals(
          receipt,
          fixture.logs.read(fixture.topic, 0, 100, "other-reader").branches().getFirst().receipt());
      jdbc.update(
          "UPDATE projects SET name=? WHERE id=?",
          fixture.project + "-renamed",
          fixture.topic.projectId());
      assertEquals(
          fixture.project + "-renamed",
          fixture
              .logs
              .read(fixture.topic, 0, 100, "other-reader")
              .branches()
              .getFirst()
              .conversationProject());
    }
  }

  @Test
  void oversizedUnicodeFallsBackCompletelyAndTheExistingSixteenEventPrefixIsPreserved() {
    try (var fixture = new Fixture()) {
      String large = "message \uD83D\uDE42 ".repeat(3000);
      fixture.append("large", large);
      fixture.counting.queries.clear();
      assertEquals(
          large,
          ((RelayPayload.Text)
                  fixture
                      .logs
                      .read(fixture.topic, 0, 100, "reader")
                      .events()
                      .getFirst()
                      .event()
                      .payload())
              .text());
      assertEquals(7, fixture.counting.queries.size());
      fixture.logs.read(fixture.topic, 0, 100, "reader");
      assertEquals(14, fixture.counting.queries.size());
      for (int i = 0; i < 20; i++) fixture.append("small-" + i, "message");
      var first = fixture.logs.read(fixture.topic, 0, 100, "reader");
      var second =
          fixture.logs.read(fixture.topic, first.events().getLast().position(), 100, "reader");
      assertEquals(16, first.events().size());
      assertEquals(5, second.events().size());
      assertEquals(17, second.events().getFirst().position());
    }
  }

  @Test
  void workOnlyHintsRequireCommitAndRollbackRetainsTheCorrectDurableOutbox() throws Exception {
    try (var fixture = new Fixture();
        var listener = new PostgresProjectionNotificationSource(source()).open();
        var writer = data.getConnection()) {
      listener.listen();
      writer.setAutoCommit(false);
      var transaction = new JdbcTemplate(new SingleConnectionDataSource(writer, true));
      insertSource(transaction, fixture.topic.projectId(), "committed");
      assertTrue(listener.poll(Duration.ofMillis(20)).isEmpty());
      writer.commit();
      assertTrue(listener.poll(Duration.ofSeconds(2)).contains(ProjectionVersions.Domain.RELAY));
      insertSource(transaction, fixture.topic.projectId(), "rolled-back");
      writer.rollback();
      assertTrue(listener.poll(Duration.ofMillis(20)).isEmpty());
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM relay_source_events WHERE project_id=?",
              Integer.class,
              fixture.topic.projectId()));
      var sources =
          new JdbcRelaySourceRepository(
              jdbc,
              work,
              new DurableRelay(fixture.broker, () -> AT),
              mock(RelayNativeRepository.class));
      assertTrue(
          sources
              .pendingTopics(null, 256)
              .contains(new Relay.TopicKey(fixture.topic.projectId(), "job.started")));
      assertTrue(sources.publishNext(new Relay.TopicKey(fixture.topic.projectId(), "job.started")));
      assertFalse(
          sources.publishNext(new Relay.TopicKey(fixture.topic.projectId(), "job.started")));
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT count(*) FROM relay_source_events WHERE project_id=?",
              Integer.class,
              fixture.topic.projectId()));
    }
  }

  @Test
  void reconnectRescansDurableStateAndBypassesTheCacheDuringTheListenerOutage() throws Exception {
    try (var fixture = new Fixture()) {
      var transport = new InterruptedTransport();
      var ready = new CountDownLatch(1);
      var outage = new CountDownLatch(1);
      var recovered = new CountDownLatch(1);
      var transitions = new AtomicInteger();
      try (var signals =
              new ListeningProjectionChangeSignals(
                  transport, fixture.versions, 10, Duration.ofMillis(20), Duration.ofMillis(20));
          var caches =
              new ProjectionCaches(
                  fixture.versions, signals, RelayProjectionCacheTest.properties());
          var wakes =
              new NotificationRelayPublicationSignals(signals, new RelayWorkerProperties())) {
        var registration =
            signals.subscribe(
                Set.of(ProjectionVersions.Domain.RELAY),
                new ProjectionChangeSignals.Observer() {
                  public void changed(Set<ProjectionVersions.Domain> domains) {}

                  public void stateChanged(ProjectionChangeSignals.State state) {
                    if (state.ready()) {
                      if (transitions.incrementAndGet() == 1) ready.countDown();
                      else recovered.countDown();
                    } else if (transitions.get() > 0) outage.countDown();
                  }
                });
        try {
          var domains =
              Set.of(ProjectionVersions.Domain.RELAY, ProjectionVersions.Domain.AUTHORITY);
          var logs =
              new JdbcRelayLogRepository(
                  fixture.counting,
                  work,
                  caches.create("reconnect-topics", domains),
                  caches.create("reconnect-pages", domains));
          signals.start();
          assertTrue(ready.await(3, TimeUnit.SECONDS));
          var before = logs.read(fixture.topic, 0, 100, "reader");
          assertSame(before, logs.read(fixture.topic, 0, 100, "reader"));
          transport.active.get().close();
          assertTrue(outage.await(3, TimeUnit.SECONDS));
          fixture.counting.queries.clear();
          assertNotSame(before, logs.read(fixture.topic, 0, 100, "reader"));
          assertEquals(5, fixture.counting.queries.size());
          assertEquals(Duration.ofSeconds(5), wakes.reconciliationWait(Duration.ofSeconds(5)));
          fixture.append("missed-while-offline", "retained message");
          transport.resume.countDown();
          assertTrue(recovered.await(3, TimeUnit.SECONDS));
          var after = logs.read(fixture.topic, 0, 100, "reader");
          assertEquals(1, after.events().size());
          assertSame(after, logs.read(fixture.topic, 0, 100, "reader"));
        } finally {
          transport.resume.countDown();
          registration.close();
        }
      }
    }
  }

  @Test
  void sourcePartitionAndDiscoveryPredicatesHaveMatchingPostgresIndexesAndStableKeysets() {
    try (var fixture = new Fixture()) {
      jdbc.update(
          "INSERT INTO relay_source_events(project_id,topic,family,subject,state,occurred_at) SELECT ?, 'fixture.event'||g,'LIFECYCLE','fixture','ready',now() FROM generate_series(1,5000) g",
          fixture.topic.projectId());
      jdbc.execute("ANALYZE relay_source_events");
      var source =
          new JdbcRelaySourceRepository(
              jdbc, work, mock(Relay.class), mock(RelayNativeRepository.class));
      var first = source.pendingTopics(null, 256);
      var second = source.pendingTopics(RelaySourceRepository.cursor(first.getLast()), 256);
      assertEquals(256, first.size());
      assertEquals(256, second.size());
      assertTrue(Collections.disjoint(first, second));
      String plan =
          String.join(
              "\n",
              jdbc.queryForList(
                  "EXPLAIN (ANALYZE,BUFFERS) SELECT id FROM relay_source_events WHERE COALESCE(project_id,0)=? AND topic=? ORDER BY sequence LIMIT 1",
                  String.class,
                  fixture.topic.projectId(),
                  "fixture.event100"));
      assertTrue(plan.contains("relay_source_events_partition"), plan);
      String discoveryPlan =
          String.join(
              "\n",
              jdbc.queryForList(
                  "EXPLAIN (ANALYZE,BUFFERS) SELECT DISTINCT COALESCE('project:'||project_id::text,'system') AS scope_key,topic FROM relay_source_events ORDER BY scope_key,topic LIMIT 256",
                  String.class));
      assertTrue(discoveryPlan.contains("relay_source_events_discovery"), discoveryPlan);
      assertThrows(IllegalArgumentException.class, () -> source.pendingTopics("invalid", 256));
    }
  }

  @Test
  void aCommittedPublicationWakesTheBridgeWithoutAnyInMemoryPublisherCallback() throws Exception {
    try (var fixture = new Fixture()) {
      var polling = new CountDownLatch(1);
      ProjectionNotificationSource transport =
          () -> {
            var session = new PostgresProjectionNotificationSource(source()).open();
            return new ProjectionNotificationSource.Session() {
              @Override
              public void listen() {
                session.listen();
              }

              @Override
              public Set<ProjectionVersions.Domain> poll(Duration timeout) {
                // Poll begins only after baseline recovery and ALL ready callbacks have returned.
                // A startup wake therefore cannot accidentally satisfy the publication assertion.
                polling.countDown();
                return session.poll(timeout);
              }

              @Override
              public void close() {
                session.close();
              }
            };
          };
      try (var signals =
              new ListeningProjectionChangeSignals(
                  transport, fixture.versions, 4, Duration.ofMillis(20), Duration.ofMillis(20));
          var wakes =
              new NotificationRelayPublicationSignals(signals, new RelayWorkerProperties());
          var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        signals.start();
        assertTrue(polling.await(3, TimeUnit.SECONDS));
        try (var waiting = wakes.listen(fixture.subscription)) {
          var result =
              executor.submit(
                  () -> {
                    waiting.await(Duration.ofSeconds(30));
                    return true;
                  });
          try {
            // JdbcRelayRepository performs SQL only: it never calls RelayPublicationSignals.
            // A separate committed database writer must reach the shared listener and bridge.
            fixture.append("remote-commit", "retained message");
            assertTrue(result.get(3, TimeUnit.SECONDS));
          } finally {
            result.cancel(true);
          }
        }
      }
    }
  }

  private static void insertSource(JdbcTemplate jdbc, long project, String subject) {
    jdbc.update(
        "INSERT INTO relay_source_events(project_id,topic,family,subject,state,occurred_at) VALUES(?,'job.started','LIFECYCLE',?,'done',now())",
        project,
        subject);
  }

  private static DataSource source() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static final class Fixture implements AutoCloseable {
    final String project = "relay-cache-" + UUID.randomUUID();
    final Relay.TopicKey topic;
    final Relay.SubscriptionKey subscription;
    final RelayRepository broker =
        new JdbcRelayRepository(
            jdbc, work, new JdbcRelayPayloadStore(jdbc, work, java.time.Duration.ofHours(1)));
    final JdbcProjectionVersions versions = new JdbcProjectionVersions(jdbc, source(), 128);
    final CountingJdbc counting = new CountingJdbc(data);
    final ProjectionCaches caches;
    final RelayLogRepository logs;

    Fixture() {
      long id =
          Objects.requireNonNull(
              jdbc.queryForObject(
                  "INSERT INTO projects(name,workspace) VALUES(?,'fixture') RETURNING id",
                  Long.class,
                  project));
      topic = new Relay.TopicKey(id, "notices.status");
      subscription = new Relay.SubscriptionKey(topic, "reader");
      broker.configureTopic(topic, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
      broker.subscribe(subscription, Relay.Start.OLDEST_RETAINED, AT);
      var signals = mock(ProjectionChangeSignals.class);
      when(signals.subscribe(
              org.mockito.ArgumentMatchers.anySet(), org.mockito.ArgumentMatchers.any()))
          .thenAnswer(
              call -> {
                ProjectionChangeSignals.Observer observer = call.getArgument(1);
                observer.stateChanged(new ProjectionChangeSignals.State(1, true));
                ProjectionChangeSignals.Subscription registration = () -> {};
                return registration;
              });
      caches = new ProjectionCaches(versions, signals, RelayProjectionCacheTest.properties());
      var domains = Set.of(ProjectionVersions.Domain.RELAY, ProjectionVersions.Domain.AUTHORITY);
      logs =
          new JdbcRelayLogRepository(
              counting, work, caches.create("topics", domains), caches.create("pages", domains));
    }

    Relay.Publication append(String id, String text) {
      return broker.append(
          topic, new Relay.Draft(id, "publisher", AT, null, null, new RelayPayload.Text(text)), AT);
    }

    public void close() {
      try {
        caches.close();
      } finally {
        jdbc.update("DELETE FROM conversations WHERE project_id=?", topic.projectId());
        jdbc.update("DELETE FROM projects WHERE id=?", topic.projectId());
      }
    }
  }

  private static final class CountingJdbc extends JdbcTemplate {
    final List<String> queries = new CopyOnWriteArrayList<>();

    CountingJdbc(DataSource source) {
      super(source);
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... arguments) {
      queries.add(sql);
      return super.query(sql, mapper, arguments);
    }
  }

  private static final class InterruptedTransport implements ProjectionNotificationSource {
    final AtomicInteger opens = new AtomicInteger();
    final AtomicReference<Session> active = new AtomicReference<>();
    final CountDownLatch resume = new CountDownLatch(1);

    public Session open() {
      if (opens.incrementAndGet() == 2) {
        try {
          if (!resume.await(5, TimeUnit.SECONDS))
            throw new IllegalStateException("Reconnect fixture timed out");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Reconnect interrupted");
        }
      }
      var opened = new PostgresProjectionNotificationSource(source()).open();
      active.set(opened);
      return opened;
    }
  }
}
