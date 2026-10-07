package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.archive.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * PostgreSQL verifies destination constraints, receipt races, bounded cleanup and coherent log SQL.
 */
@Tag("full-db")
@Testcontainers
class JdbcRelayWorkTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private static UnitOfWork work;
  private static final Instant AT = Instant.parse("2026-10-05T01:00:00Z");
  private RelayRepository broker;
  private RelayDeliveryRepository deliveries;
  private RelayExecutions executions;
  private RelayLogRepository logs;
  private Relay.TopicKey topic;
  private Relay.SubscriptionKey sub;
  private RelayDeliveries.Decision decision;

  @BeforeAll
  static void migrate() {
    var data =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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

  @BeforeEach
  void fresh() {
    jdbc.execute("TRUNCATE relay_topics CASCADE");
    jdbc.update(
        "DELETE FROM conversations WHERE project_id IN (SELECT id FROM projects WHERE name='relay-work-fixture')");
    jdbc.update("DELETE FROM projects WHERE name='relay-work-fixture'");
    long id =
        Objects.requireNonNull(
            jdbc.queryForObject(
                "INSERT INTO projects(name,workspace) VALUES('relay-work-fixture','fixture') RETURNING id",
                Long.class));
    topic = new Relay.TopicKey(id, "release.observed");
    sub = new Relay.SubscriptionKey(topic, "relay.notices.release");
    broker = new JdbcRelayRepository(jdbc, work);
    deliveries = new JdbcRelayDeliveryRepository(jdbc, work);
    executions = new JdbcRelayExecutions(jdbc, () -> AT);
    logs = new JdbcRelayLogRepository(jdbc, work);
    broker.configureTopic(topic, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    broker.subscribe(sub, Relay.Start.OLDEST_RETAINED, AT);
    decision =
        new RelayDeliveries.Decision(
            RelayDeliveries.SourcePin.of(
                "notices/routes.js", "export function route(){return [];};"),
            List.of(
                new RelayDeliveries.Branch(
                    "review",
                    "script.run",
                    RelayDeliveries.SourcePin.of(
                        "notices/scripts/review.js",
                        "// plowshare-script v1\nexport const manifest={};export function step(input){return {state:null,command:{finish:'done'}};}"),
                    null,
                    new RelayWork("worker", null, null))));
  }

  private Relay.Publication append(String id) {
    return broker.append(
        topic,
        new Relay.Draft(id, "publisher", AT, null, null, new RelayPayload.Text("released")),
        AT);
  }

  private RelayReceiver.Request pending() {
    var publication = append("first");
    deliveries.admit(new RelayDeliveries.AdmissionKey(sub, publication.position()), decision, AT);
    var claimed = deliveries.claim(sub, "worker", Duration.ofMinutes(2), AT).orElseThrow();
    var prepared = deliveries.prepareDispatch(claimed.claim(), AT);
    return new RelayReceiver.Request(
        new RelayProjectFiles.Access("operator", "relay-work-fixture", topic.projectId()),
        prepared);
  }

  private String conversation() {
    return new ConversationStore(jdbc, () -> AT, null)
        .log(Origin.EVENT, Home.of("relay-work-fixture"), "worker", null, Budget.of(5), "operator")
        .id();
  }

  @Test
  void expired_dispatch_intent_cannot_start_work_before_the_recovery_sweep() {
    var request = pending();
    var expired = new JdbcRelayExecutions(jdbc, () -> AT.plusSeconds(120));
    assertFalse(expired.begin(request));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM relay_executions", Integer.class));
  }

  @Test
  void forward_log_pages_leave_room_for_metadata_and_continue_without_skipping_input() {
    for (int index = 1; index <= 20; index++) append("event:" + index);
    var first = logs.read(topic, 0, 100, "operator");
    assertEquals(16, first.events().size());
    var second = logs.read(topic, first.events().getLast().position(), 100, "operator");
    assertEquals(4, second.events().size());
    assertEquals(17, second.events().getFirst().position());
    assertEquals(20, second.events().getLast().position());
  }

  @Test
  void pinned_handler_runs_in_the_shared_driver_with_a_real_durable_script_journal() {
    var request = pending();
    String conversation = conversation();
    String source = request.delivery().branch().handler().source();
    var scripts = new io.aeyer.plowshare.server.orchestrations.scripted.JdbcScriptStore(jdbc);
    var models =
        org.mockito.Mockito.mock(io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher.class);
    var runtime = new io.aeyer.plowshare.server.agents.JobRuntime(models, List.of());
    runtime.useScripts(scripts);
    var sampling = io.aeyer.plowshare.server.llm.dispatch.Sampling.NONE;
    var agent =
        new io.aeyer.plowshare.server.agents.AgentDefinition(
            "worker",
            "fixture",
            "fast",
            io.aeyer.plowshare.server.llm.dispatch.Sampling.Intent.DEFAULT,
            sampling,
            List.of(),
            List.of(),
            List.of(),
            10,
            5,
            source,
            true,
            true,
            false,
            false,
            false,
            io.aeyer.plowshare.server.agents.AgentDefinition.Fallback.NONE,
            List.of(),
            null,
            false,
            List.of());
    var transcript =
        new io.aeyer.plowshare.server.agents.Transcript() {
          public List<io.aeyer.plowshare.server.llm.dispatch.ChatMessage> before() {
            return List.of();
          }

          public void promptMeasured(int tokens) {}

          public String conversationId() {
            return conversation;
          }

          public Origin origin() {
            return Origin.EVENT;
          }
        };
    var outcome =
        runtime.run(
            agent,
            RelayRouteCodec.event(request.delivery().publication()),
            Home.of("relay-work-fixture"),
            Budget.of(5),
            () -> false,
            null,
            io.aeyer.plowshare.server.agents.JobWatch.UNWATCHED,
            transcript,
            io.aeyer.plowshare.server.agents.TurnCap.of(10),
            List.of(),
            "operator");
    assertEquals(io.aeyer.plowshare.server.agents.Outcome.Ending.ANSWERED, outcome.ending());
    var step = scripts.latest(conversation).orElseThrow();
    assertEquals("done", step.result());
    assertEquals("done", step.raw());
    assertEquals("sha256:" + request.delivery().branch().handler().sha256(), step.hash());
    org.mockito.Mockito.verifyNoInteractions(models);
  }

  @Test
  void destinations_and_exact_script_sources_round_trip_and_log_privacy_is_enforced() {
    var request = pending();
    assertEquals(decision.branches().getFirst(), request.delivery().branch());
    assertTrue(executions.begin(request));
    String conversation = conversation();
    var receipt = new RelayDeliveries.Receipt("job", "job_fixture");
    executions.accepted(request, receipt, conversation);
    // A recovery inspection may race or repeat, but may never replace a proven link.
    executions.accepted(request, receipt, conversation);
    assertThrows(
        IllegalStateException.class,
        () ->
            executions.accepted(
                request, new RelayDeliveries.Receipt("job", "job_conflict"), conversation));
    deliveries.accepted(request.delivery().claim(), receipt, AT);
    var owner = logs.read(topic, 0, 100, "operator");
    var other = logs.read(topic, 0, 100, "reader");
    assertEquals(conversation, owner.branches().getFirst().conversation());
    assertEquals("relay-work-fixture", owner.branches().getFirst().conversationProject());
    assertEquals(receipt, owner.branches().getFirst().receipt());
    assertNull(other.branches().getFirst().conversation());
    assertNull(other.branches().getFirst().conversationProject());
    assertNull(other.branches().getFirst().receipt());
    assertEquals(0, other.subscribers().getFirst().seenThrough() - 1);
    assertEquals(1, owner.events().size());
    assertEquals(decision.routing().sha256(), owner.branches().getFirst().routingHash());
    assertEquals(1, logs.topics(topic.scope(), 100).size());
    assertTrue(logs.topics(Relay.SystemScope.SERVER, 100).isEmpty());
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE relay_deliveries SET work_definition='ungranted' WHERE id=?",
                request.identity()));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "UPDATE relay_deliveries SET work_agent=NULL WHERE id=?", request.identity()));
  }

  @Test
  void only_one_concurrent_start_wins_and_unknown_intents_are_never_cleaned() throws Exception {
    var request = pending();
    var go = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Callable<Boolean> start =
          () -> {
            assertTrue(go.await(5, TimeUnit.SECONDS));
            return executions.begin(request);
          };
      var a = executor.submit(start);
      var b = executor.submit(start);
      go.countDown();
      assertNotEquals(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
    }
    assertTrue(executions.find("operator", topic.projectId(), request.identity()).isEmpty());
    assertEquals(
        0, executions.prune(Instant.now().plus(Duration.ofDays(4000)), Duration.ofDays(30), 100));
    assertFalse(executions.begin(request));
  }

  @Test
  void receipt_cleanup_waits_for_broker_cleanup_and_log_close_and_does_not_remove_a_live_source() {
    var request = pending();
    executions.begin(request);
    String conversation = conversation();
    var receipt = new RelayDeliveries.Receipt("job", "job_fixture");
    executions.accepted(request, receipt, conversation);
    deliveries.accepted(request.delivery().claim(), receipt, AT);
    Instant later = Instant.now().plus(Duration.ofDays(60));
    Duration retention = Duration.ofDays(30);
    assertEquals(0, executions.prune(later, retention, 100));
    deliveries.pruneSettled(later, retention, 100);
    assertEquals(0, executions.prune(later, retention, 100));
    jdbc.update(
        "UPDATE conversations SET log_closed_at=? WHERE id=?",
        java.sql.Timestamp.from(AT),
        conversation);
    assertEquals(1, executions.prune(later, retention, 100));
    assertTrue(executions.find("operator", topic.projectId(), request.identity()).isEmpty());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM conversations WHERE id=?", Integer.class, conversation));
  }

  @Test
  void
      pending_queue_backpressure_never_advances_availability_and_zero_branch_admission_still_works() {
    // Fill the real queue at its bound. The topic lock serializes the count and next insert.
    for (int sequence = 1; sequence <= 32; sequence++) {
      var publication = append("event-" + sequence);
      var branches = new ArrayList<RelayDeliveries.Branch>();
      for (int branch = 0; branch < (sequence == 32 ? 8 : 32); branch++)
        branches.add(new RelayDeliveries.Branch("branch-" + branch, "agent-message", null));
      deliveries.admit(
          new RelayDeliveries.AdmissionKey(sub, publication.position()),
          new RelayDeliveries.Decision(decision.routing(), branches),
          AT);
    }
    var next = append("blocked");
    var key = new RelayDeliveries.AdmissionKey(sub, next.position());
    assertThrows(IllegalStateException.class, () -> deliveries.admit(key, decision, AT));
    assertEquals(32, broker.read(sub, 1).subscription().seenThrough());
    assertTrue(deliveries.admission(key).isEmpty());
    assertEquals(
        1000,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM relay_deliveries WHERE scope_key=?",
            Integer.class,
            "project:" + topic.projectId()));
    assertTrue(
        deliveries
            .admit(key, new RelayDeliveries.Decision(decision.routing(), List.of()), AT)
            .deliveries()
            .isEmpty());
    assertEquals(33, broker.read(sub, 1).subscription().seenThrough());
  }
}
