package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.FilterReview;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.sdk.Plowshare;
import io.aeyer.plowshare.sdk.RelayClient;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.security.FilteredChat;
import io.aeyer.plowshare.server.security.FilteringProperties;
import io.aeyer.plowshare.server.security.LocalTextFilter;
import io.aeyer.plowshare.server.security.RelayMessageReview;
import io.aeyer.plowshare.server.ws.Asking;
import io.aeyer.plowshare.server.ws.FrameRouter;
import io.aeyer.plowshare.server.ws.RelayPortFrames;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL proves migration, row locks, rollback, retention and acknowledgement fencing. */
@Tag("full-db")
@Testcontainers
class JdbcRelayPortRepositoryTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private static UnitOfWork transactions;
  private Relay relay;
  private Relay.TopicKey topic;
  private JdbcRelayPortRepository ports;

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
            action.run();
          }
        };
  }

  @BeforeEach
  void setup() {
    jdbc.update("DELETE FROM projects WHERE name='relay-port-fixture'");
    long id =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO projects(name,workspace) VALUES('relay-port-fixture','fixture') RETURNING id",
                Long.class));
    topic = new Relay.TopicKey(id, "checks.requests");
    relay = new DurableRelay(new JdbcRelayRepository(jdbc, transactions), Instant::now);
    ports = new JdbcRelayPortRepository(jdbc, transactions, relay);
    relay.registerTopic(topic, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
  }

  private RelayPort.Consume consume(String group, String consumer, int limit) {
    return new RelayPort.Consume(
        "fixture", topic.name(), group, consumer, RelayPort.Start.OLDEST_RETAINED, limit, 0);
  }

  private RelayPort.Ack ack(RelayPort.Batch batch) {
    return new RelayPort.Ack(
        batch.project(),
        batch.topic(),
        batch.group(),
        batch.consumerId(),
        batch.batchId(),
        batch.fence(),
        batch.expiredThrough());
  }

  private void publish(String id) {
    relay.publish(
        topic,
        new Relay.Draft(id, "fixture", Instant.now(), null, null, new RelayPayload.Text("hello")));
  }

  @Test
  void large_pages_preserve_the_issued_cursor_and_leave_the_tail_unacknowledged() {
    relay =
        new DurableRelay(
            new JdbcRelayRepository(
                jdbc, transactions, new RelayTextLimit(RelayPort.MAX_TEXT_BYTES)),
            Instant::now);
    ports = new JdbcRelayPortRepository(jdbc, transactions, relay);
    String text = "x".repeat(RelayPort.MAX_TEXT_BYTES);
    for (int i = 1; i <= 7; i++)
      relay.publish(
          topic,
          new Relay.Draft(
              "large-" + i, "fixture", Instant.now(), null, null, new RelayPayload.Text(text)));
    String consumer = UUID.randomUUID().toString();
    var request = consume("large-readers", consumer, 100);
    var batch = ports.consume(topic, "reviewer", request);
    assertEquals(6, batch.events().size());
    assertEquals("6", batch.through());
    assertEquals(batch, ports.consume(topic, "reviewer", request));
    assertThrows(
        CallerFault.class, () -> ports.consume(topic, "reviewer", request, RelayReadBudget.LEGACY));
    assertEquals(batch, ports.consume(topic, "reviewer", request));
    var inspection = new JdbcRelayLogRepository(jdbc, transactions);
    var page = inspection.read(topic, 0, 100, "reviewer");
    assertEquals(6, page.events().size());
    assertEquals(6, page.events().getLast().position());
    assertEquals(7, inspection.read(topic, 6, 100, "reviewer").events().getFirst().position());
    ports.acknowledge(topic, "reviewer", ack(batch));
    var tail = ports.consume(topic, "reviewer", request);
    assertEquals(1, tail.events().size());
    assertEquals("7", tail.through());
    assertEquals(text, tail.events().getFirst().payload().text());
  }

  @Test
  void reading_does_not_acknowledge_and_repetition_preserves_the_whole_batch() {
    publish("first");
    publish("second");
    String consumer = UUID.randomUUID().toString();
    var request = consume("detectors", consumer, 100);
    var batch = ports.consume(topic, "reviewer", request);
    assertEquals(
        "0",
        Long.toString(
            relay
                .read(new Relay.SubscriptionKey(topic, "sdk.detectors"), 100)
                .subscription()
                .seenThrough()));
    publish("third");
    assertEquals(batch, ports.consume(topic, "reviewer", request));
    assertThrows(
        CallerFault.class,
        () -> ports.consume(topic, "reviewer", consume("detectors", consumer, 1)));
    assertEquals(batch.through(), ports.acknowledge(topic, "reviewer", ack(batch)).through());
    assertEquals(batch.through(), ports.acknowledge(topic, "reviewer", ack(batch)).through());
    var next = ports.consume(topic, "reviewer", request);
    assertEquals(1, next.events().size());
    assertThrows(CallerFault.class, () -> ports.acknowledge(topic, "reviewer", ack(batch)));
    assertThrows(CallerFault.class, () -> ports.acknowledge(topic, "foreign", ack(next)));
  }

  @Test
  void competing_workers_are_fenced_and_different_groups_have_independent_cursors()
      throws Exception {
    publish("first");
    var go = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var one =
          executor.submit(
              () -> {
                go.await();
                return ports.consume(
                    topic, "reviewer", consume("detectors", UUID.randomUUID().toString(), 100));
              });
      var two =
          executor.submit(
              () -> {
                go.await();
                return ports.consume(
                    topic, "reviewer", consume("detectors", UUID.randomUUID().toString(), 100));
              });
      go.countDown();
      var a = one.get(5, TimeUnit.SECONDS);
      var b = two.get(5, TimeUnit.SECONDS);
      assertNotEquals(a.status(), b.status());
      var old = a.status() == RelayPort.Status.DATA ? a : b;
      jdbc.update("UPDATE relay_sdk_batches SET lease_until=clock_timestamp()-interval '1 second'");
      assertThrows(CallerFault.class, () -> ports.acknowledge(topic, "reviewer", ack(old)));
      var successor =
          ports.consume(topic, "reviewer", consume("detectors", UUID.randomUUID().toString(), 100));
      assertTrue(Long.parseLong(successor.fence()) > Long.parseLong(old.fence()));
      assertThrows(CallerFault.class, () -> ports.acknowledge(topic, "reviewer", ack(old)));
      ports.acknowledge(topic, "reviewer", ack(successor));
      assertEquals(
          RelayPort.Status.DATA,
          ports
              .consume(topic, "reviewer", consume("other", UUID.randomUUID().toString(), 100))
              .status());
    }
  }

  @Test
  void sdk_websocket_review_uses_durable_topics_and_returns_the_complete_approved_message()
      throws Exception {
    var members = mock(ProjectMembers.class);
    var projects = mock(ProjectWorkspaces.class);
    when(members.mayWork(eq("fixture"), anyString())).thenReturn(true);
    when(projects.id("fixture")).thenReturn(((Relay.ProjectScope) topic.scope()).projectId());
    when(projects.personalOwner("fixture")).thenReturn(Optional.empty());
    var grants = new RelayPortProperties();
    grants.setBindings(
        java.util.List.of(
            new RelayPortProperties.Binding(
                "fixture",
                topic.name(),
                "reviewer",
                RelayPortProperties.Direction.EGRESS,
                java.util.List.of("detectors")),
            new RelayPortProperties.Binding(
                "fixture",
                "checks.responses",
                "reviewer",
                RelayPortProperties.Direction.INGRESS,
                java.util.List.of())));
    var filtering = new FilteringProperties();
    filtering.setExternal(
        java.util.List.of(
            new FilteringProperties.External(
                "fixture", "subject", topic.name(), "checks.responses", "reviewer", 15)));
    var filter =
        new FilteredChat(
            new LocalTextFilter(filtering),
            new RelayMessageReview(
                filtering,
                relay,
                new JdbcRelayLogRepository(jdbc, transactions),
                members,
                projects,
                grants));
    var router =
        new FrameRouter(
            new RelayPortFrames(new ProjectRelayPorts(relay, ports, grants, members, projects, 8))
                .frames());
    var json =
        com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    // This fixture authenticates the upgrade as reviewer. Production AuthFilter/session wiring
    // is outside this test; real frame validation, membership/grants and repositories run below.
    try (var server = new MockWebServer();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      server.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket socket, String text) {
                      try {
                        var frame = json.readTree(text);
                        var outcome = router.route(text, new Asking("fixture-session", "reviewer"));
                        socket.send(
                            json.writeValueAsString(
                                java.util.Map.of(
                                    "id",
                                    frame.path("id").textValue(),
                                    "type",
                                    frame.path("type").textValue(),
                                    "protocol_version",
                                    "plowshare-v1",
                                    "payload",
                                    outcome)));
                      } catch (java.io.IOException invalid) {
                        throw new AssertionError(invalid);
                      }
                    }
                  }));
      var request =
          ChatRequest.of("model", null, "Please review this complete message.")
              .withAttribution(
                  UsageAttribution.project(
                      "subject", "fixture", UsageAttribution.Operation.AGENT_CHAT));
      var held = executor.submit(() -> filter.input(request, () -> false));
      try (var connection =
          Plowshare.connect(server.url("/").toString(), "fixture", Duration.ofSeconds(5), null)) {
        var sdk = new RelayClient(connection);
        var consumption =
            new RelayPort.Consume(
                "fixture",
                topic.name(),
                "detectors",
                UUID.randomUUID().toString(),
                RelayPort.Start.OLDEST_RETAINED,
                100,
                3000);
        var batch = sdk.consume(consumption);
        assertEquals(RelayPort.Status.DATA, batch.status());
        var event = batch.events().getFirst();
        var review = json.readValue(event.payload().text(), FilterReview.Request.class);
        assertEquals("Please review this complete message.", review.message());
        assertFalse(held.isDone());
        String approved = "Complete approved replacement with all of its content.";
        var verdict =
            new FilterReview.Response(
                1, review.requestId(), review.sourceHash(), true, approved, "accepted");
        var publication =
            new RelayPort.Publish(
                UUID.randomUUID().toString(),
                "fixture",
                "checks.responses",
                json.writeValueAsString(verdict),
                Instant.now(),
                review.requestId(),
                topic.name(),
                event.eventId());
        var stored = sdk.publish(publication);
        assertEquals("1", stored.position());
        assertEquals(stored.position(), sdk.publish(publication).position());
        assertEquals(approved, held.get(5, TimeUnit.SECONDS).messages().getFirst().content());
        assertEquals(batch.through(), sdk.acknowledge(ack(batch)).through());
        assertEquals(
            RelayPort.Status.EMPTY,
            sdk.consume(
                    new RelayPort.Consume(
                        "fixture",
                        topic.name(),
                        "detectors",
                        consumption.consumerId(),
                        RelayPort.Start.OLDEST_RETAINED,
                        100,
                        0))
                .status());
      }
    }
  }

  @Test
  void escaped_large_messages_issue_a_bounded_prefix_and_ack_only_delivered_records()
      throws Exception {
    for (int i = 0; i < 5; i++)
      relay.publish(
          topic,
          new Relay.Draft(
              "large-" + i,
              "fixture",
              Instant.now(),
              null,
              null,
              new RelayPayload.Text("\u0001".repeat(43680))));
    var request = consume("detectors", UUID.randomUUID().toString(), 100);
    var first = ports.consume(topic, "reviewer", request);
    assertEquals(2, first.events().size());
    var mapper =
        com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .build();
    assertTrue(
        mapper.writeValueAsBytes(
                    java.util.Map.of(
                        "id",
                        "fixture",
                        "type",
                        "relay.consume",
                        "protocol_version",
                        "plowshare-v1",
                        "payload",
                        java.util.Map.of("code", "OK", "payload", first)))
                .length
            < 1024 * 1024);
    assertEquals(first, ports.consume(topic, "reviewer", request));
    assertEquals("2", ports.acknowledge(topic, "reviewer", ack(first)).through());
    var second = ports.consume(topic, "reviewer", request);
    assertEquals("large-2", second.events().getFirst().eventId());
    assertEquals("4", second.through());
  }

  @Test
  void retention_loss_requires_an_explicit_exact_gap_and_rollback_preserves_the_cursor() {
    publish("first");
    String consumer = UUID.randomUUID().toString();
    var request = consume("detectors", consumer, 100);
    var original = ports.consume(topic, "reviewer", request);
    relay.configureTopic(topic, RelayPayload.Kind.TEXT, new Relay.Policy(Duration.ofDays(4), 1L));
    publish("second");
    new JdbcRelayRepository(jdbc, transactions).prune(topic, Instant.now(), 100);
    var gap = ports.consume(topic, "reviewer", request);
    assertEquals(RelayPort.Status.GAP, gap.status());
    assertEquals("1", gap.expiredThrough());
    assertThrows(CallerFault.class, () -> ports.acknowledge(topic, "reviewer", ack(original)));
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.inTransaction(
                () -> {
                  ports.acknowledge(topic, "reviewer", ack(gap));
                  throw new IllegalStateException("rollback");
                }));
    assertEquals(gap, ports.consume(topic, "reviewer", request));
    assertTrue(ports.acknowledge(topic, "reviewer", ack(gap)).gap());
    assertEquals("second", ports.consume(topic, "reviewer", request).events().getFirst().eventId());
  }
}
