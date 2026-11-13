package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.protocol.ScheduledWork;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.AgentTool;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Compaction;
import io.aeyer.plowshare.server.agents.EntryKind;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.LogStages;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.RunExtras;
import io.aeyer.plowshare.server.agents.SendMessageTool;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.agents.Transcript;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.allowances.JdbcAllowanceRepository;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.approvals.RunApprovalStore;
import io.aeyer.plowshare.server.archive.ArchiveConfig;
import io.aeyer.plowshare.server.archive.CompactionStore;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.JdbcProjectMembers;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.events.AccountPushes;
import io.aeyer.plowshare.server.events.Dispatcher;
import io.aeyer.plowshare.server.events.EventPayload;
import io.aeyer.plowshare.server.events.FiringRecord;
import io.aeyer.plowshare.server.events.Inbox;
import io.aeyer.plowshare.server.events.JdbcInboxStore;
import io.aeyer.plowshare.server.events.JdbcTriggerStore;
import io.aeyer.plowshare.server.events.TriggerRecord;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.accounting.UsageLineage;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmPool;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransport;
import io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import io.aeyer.plowshare.server.messaging.JdbcMessagingRepository;
import io.aeyer.plowshare.server.messaging.MessageInboxRepository;
import io.aeyer.plowshare.server.messaging.MessageInboxes;
import io.aeyer.plowshare.server.messaging.MessageIngress;
import io.aeyer.plowshare.server.messaging.MessageRefused;
import io.aeyer.plowshare.server.messaging.MessageService;
import io.aeyer.plowshare.server.messaging.MessageWake;
import io.aeyer.plowshare.server.messaging.MessagingProperties;
import io.aeyer.plowshare.server.messaging.ProjectMessageRouting;
import io.aeyer.plowshare.server.messaging.RelayMessageInboxes;
import io.aeyer.plowshare.server.messaging.RelayMessaging;
import io.aeyer.plowshare.server.personal.JdbcPersonalSpaceRepository;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import io.aeyer.plowshare.server.relay.JdbcRelayPayloadStore;
import io.aeyer.plowshare.server.relay.JdbcRelayRepository;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real board persistence, transaction boundaries and budget leases; inference is a stand-in. */
@Tag("full-db")
@Testcontainers
class BoardMessagingTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static DriverManagerDataSource source;
  private static final ObjectMapper JSON = new ObjectMapper();
  @TempDir Path definitions;
  private BoardFixture fixture;
  private RelayMessaging messages;
  private AgentRegistry agents;
  private RunExtras.Context caller;
  private FakeVoice voice;

  record Call(
      MessageService.Instance instance, String utterance, Budget lease, Consumer<Outcome> ended) {}

  static final class FakeVoice implements MessageService.Voice {
    boolean busy;
    final List<Call> calls = new ArrayList<>();
    final List<String> commands = new ArrayList<>();

    public String speakCommand(
        MessageService.Instance instance,
        AgentDefinition definition,
        String utterance,
        Budget lease,
        TurnCap cap,
        Consumer<Outcome> ended) {
      commands.add(utterance);
      return speak(instance, definition, utterance, lease, cap, ended);
    }

    final List<String> resumedChildren = new ArrayList<>();

    public String resumeDelegate(
        MessageService.Instance instance,
        String child,
        AgentDefinition definition,
        String utterance,
        String approval,
        Budget lease,
        Consumer<Outcome> ended) {
      resumedChildren.add(child);
      calls.add(new Call(instance, utterance, lease, ended));
      return "job_delegate_" + calls.size();
    }

    public boolean busy(String conversation) {
      return busy;
    }

    public String speak(
        MessageService.Instance instance,
        AgentDefinition definition,
        String utterance,
        Budget lease,
        TurnCap cap,
        Consumer<Outcome> ended) {
      calls.add(new Call(instance, utterance, lease, ended));
      return "job_message_" + calls.size();
    }
  }

  @BeforeAll
  static void migrate() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
  }

  @BeforeEach
  void setup() throws Exception {
    fixture = new BoardFixture(source);
    fixture.jdbc.update("UPDATE admins SET server_admin=TRUE WHERE handle='enzo'");
    for (String name : List.of("sender", "reviewer", "bot"))
      Files.writeString(
          definitions.resolve(name + ".md"),
          "---\nname: "
              + name
              + "\ndescription: "
              + name
              + "\nmodel: test\ntools: [send_message]\n"
              + "bot: "
              + name.equals("bot")
              + "\nmax-turns: 4\nmax-model-calls: 8\n---\nHelp.\n");
    agents = AgentRegistry.of(definitions, Set.of("send_message"));
    voice = new FakeVoice();
    messages =
        new RelayMessaging(
            new JdbcMessagingRepository(fixture.jdbc),
            new JdbcAllowanceRepository(fixture.jdbc),
            fixture.conversations,
            fixture.firings,
            MessageInboxFixture.inboxes(fixture),
            fixture.work,
            voice,
            (name, project) -> agents.get(name),
            fixture.drained::add,
            fixture.clock);
    String conversation =
        fixture.conversations.open(Home.of("payments"), Budget.of(20), TurnCap.of(4), "enzo").id();
    caller = context("sender", conversation);
    fixture.jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'enzo','CONTRIBUTOR' FROM projects WHERE name='payments' ON CONFLICT(project_id,handle) DO UPDATE SET role='CONTRIBUTOR'");
  }

  private RunExtras.Context context(String agent, String conversation) {
    return new RunExtras.Context(
        agents.get(agent), conversation, null, Transcript.NONE, Home.of("payments"), "enzo");
  }

  private JsonNode send(RunExtras.Context context, String arguments, String call) throws Exception {
    SendMessageTool tool = messages.tool(context);
    tool.calledAs(call);
    String result = tool.run(arguments, context.home());
    assertTrue(result.startsWith("{"), result);
    MessageInboxFixture.pulse(fixture);
    return JSON.readTree(result);
  }

  private JsonNode request(boolean expected) throws Exception {
    return send(
        caller,
        "{\"to\":\"reviewer\",\"body\":\"Review\",\"reply_expected\":" + expected + "}",
        "request");
  }

  private RunExtras.Context recipient(JsonNode receipt) {
    var instance = messages.instance(receipt.path("to").asText()).orElseThrow();
    return context(instance.agent(), instance.conversation());
  }

  private int firingCount() {
    MessageInboxFixture.pulse(fixture);
    return fixture.jdbc.queryForObject("SELECT count(*) FROM firings", Integer.class);
  }

  private FiringRecord wake(JsonNode receipt) {
    MessageInboxFixture.pulse(fixture);
    var recipient = messages.instance(receipt.path("to").asText()).orElseThrow();
    return fixture.firings.oldestWaiting(RelayMessageInboxes.target(recipient)).orElseThrow();
  }

  private Outcome outcome(Outcome.Ending ending, String text) {
    return new Outcome(ending, text, 1, 1, "");
  }

  private MessageIngress.Request incoming(UUID request, UUID context, String body, String command) {
    return new MessageIngress.Request(
        "payments",
        "remote",
        "reviewer",
        request,
        context,
        body,
        command,
        new MessageIngress.Source(
            "test",
            request.toString(),
            RelayPort.hash(request + "\n" + context + "\n" + body + "\n" + command)));
  }

  private FiringRecord incomingWake(String message) {
    MessageInboxFixture.pulse(fixture);
    return fixture
        .firings
        .find(
            fixture.jdbc.queryForObject(
                "SELECT id FROM firings WHERE data->>'direct_message'=?", String.class, message))
        .orElseThrow();
  }

  @Test
  void physical_inbox_retirement_keeps_message_history_and_restores_a_fresh_topic_incarnation()
      throws Exception {
    var receipt = request(false);
    String message = receipt.path("message").asText();
    var inbox = fixture.messageInboxes.getFirst();
    var row =
        fixture.jdbc.queryForMap(
            "SELECT project_id,topic FROM message_agent_inboxes WHERE agent='reviewer'");
    var key =
        new Relay.TopicKey(((Number) row.get("project_id")).longValue(), (String) row.get("topic"));
    var broker =
        new JdbcRelayRepository(
            fixture.jdbc,
            fixture.work,
            new JdbcRelayPayloadStore(
                fixture.jdbc,
                fixture.work,
                Duration.ofHours(1),
                Duration.ofHours(24),
                Duration.ofDays(30)));
    var generation = broker.topic(key).generation();
    messages.complete(message, outcome(Outcome.Ending.ANSWERED, "Done"));
    inbox.retireMissing((account, project) -> Set.of("sender"));
    assertEquals(
        0, inbox.cleanupRetired(), "Retained publications must survive until their normal expiry");
    broker.configureTopic(
        key, RelayPayload.Kind.TEXT, new Relay.Policy(Duration.ofSeconds(1), null));
    broker.prune(key, fixture.clock.get().plusSeconds(2), 100);
    inbox.cleanupRetired(); // Advance/wrap the bounded cursor from the earlier blocked attempt.
    inbox.cleanupRetired();
    assertEquals(
        0,
        fixture.jdbc.queryForObject(
            "SELECT count(*) FROM message_agent_inboxes WHERE agent='reviewer'", Integer.class));
    assertEquals(
        "Review", new JdbcMessagingRepository(fixture.jdbc).message(message).orElseThrow().body());
    assertTrue(messages.route(message).orElseThrow().handled());
    var restored =
        send(caller, "{\"to\":\"reviewer\",\"body\":\"Restored\"}", "restored-after-cleanup");
    assertEquals(receipt.path("to"), restored.path("to"));
    assertNotEquals(generation, broker.topic(key).generation());
    assertEquals(1, broker.topic(key).lastPosition());
    assertEquals(0, voice.calls.size(), "Retirement never replays previously handled work");
  }

  @Test
  void inbox_order_is_preserved_when_admission_timestamps_are_identical() throws Exception {
    var first = request(false);
    var second = send(caller, "{\"to\":\"reviewer\",\"body\":\"Second\"}", "second");
    var third = send(caller, "{\"to\":\"reviewer\",\"body\":\"Third\"}", "third");
    for (var receipt : List.of(first, second, third)) {
      var firing = wake(first);
      assertEquals(
          receipt.path("message").asText(),
          ((EventPayload.Message) firing.data()).wake().message());
      assertTrue(fixture.firings.claimStart(firing.id(), fixture.clock.get()));
      fixture.firings.finish(firing.id(), fixture.clock.get());
    }
  }

  @Test
  void removed_definitions_retire_only_after_their_pending_requests_drain() throws Exception {
    var receipt = request(false);
    var inbox = fixture.messageInboxes.getFirst();
    assertEquals(0, inbox.retireMissing((account, project) -> Set.of("sender")));
    assertFalse(
        fixture.jdbc.queryForObject(
            "SELECT retired_at IS NOT NULL FROM message_agent_inboxes WHERE agent='reviewer'",
            Boolean.class));
    messages.complete(receipt.path("message").asText(), outcome(Outcome.Ending.ANSWERED, "Done"));
    // Reset the bounded keyset after its first pass before reconsidering the now-drained inbox.
    inbox.retireMissing((account, project) -> Set.of("sender"));
    inbox.retireMissing((account, project) -> Set.of("sender"));
    assertTrue(
        fixture.jdbc.queryForObject(
            "SELECT retired_at IS NOT NULL FROM message_agent_inboxes WHERE agent='reviewer'",
            Boolean.class));
    send(caller, "{\"to\":\"reviewer\",\"body\":\"Restored\"}", "restored");
    assertFalse(
        fixture.jdbc.queryForObject(
            "SELECT retired_at IS NOT NULL FROM message_agent_inboxes WHERE agent='reviewer'",
            Boolean.class));
  }

  @Test
  void a_failed_wake_insert_rolls_back_admission_and_cursor_then_retries_once() throws Exception {
    var receipt =
        messages.send(
            caller,
            new SendMessageTool.Request(
                "reviewer", "body", null, false, false, "persistent", "call"),
            caller.home());
    String message = JSON.readTree(receipt).path("message").asText();
    fixture.messageInboxes.clear();
    var firings = Mockito.spy(fixture.firings);
    Mockito.doThrow(new IllegalStateException("Wake refused"))
        .when(firings)
        .oweMessage(
            ArgumentMatchers.anyString(),
            ArgumentMatchers.anyString(),
            ArgumentMatchers.any(),
            ArgumentMatchers.any(),
            ArgumentMatchers.any());
    var inbox = MessageInboxFixture.inboxes(fixture, fixture.drained::add, firings);
    assertThrows(IllegalStateException.class, inbox::poll);
    assertFalse(
        fixture.jdbc.queryForObject(
            "SELECT inbox_admitted FROM message_routes WHERE message=?", Boolean.class, message));
    assertEquals(
        0L,
        fixture.jdbc.queryForObject(
            "SELECT sum(seen_through) FROM relay_subscriptions WHERE subscriber='messaging.inbox'",
            Long.class));
    assertEquals(0, fixture.jdbc.queryForObject("SELECT count(*) FROM firings", Integer.class));
    Mockito.doCallRealMethod()
        .when(firings)
        .oweMessage(
            ArgumentMatchers.anyString(),
            ArgumentMatchers.anyString(),
            ArgumentMatchers.any(),
            ArgumentMatchers.any(),
            ArgumentMatchers.any());
    MessageInboxFixture.pulse(fixture);
    assertEquals(1, fixture.jdbc.queryForObject("SELECT count(*) FROM firings", Integer.class));
    assertTrue(
        fixture.jdbc.queryForObject(
            "SELECT inbox_admitted FROM message_routes WHERE message=?", Boolean.class, message));
    MessageInboxFixture.pulse(fixture);
    assertEquals(1, fixture.jdbc.queryForObject("SELECT count(*) FROM firings", Integer.class));
  }

  @Test
  void task_instances_reuse_one_agent_topic_but_have_independent_wakes_and_allowances()
      throws Exception {
    var one = send(caller, "{\"to\":\"reviewer\",\"body\":\"One\",\"lifetime\":\"task\"}", "one");
    var two = send(caller, "{\"to\":\"reviewer\",\"body\":\"Two\",\"lifetime\":\"task\"}", "two");
    assertNotEquals(one.path("to"), two.path("to"));
    assertEquals(
        1,
        fixture.jdbc.queryForObject(
            "SELECT count(*) FROM message_agent_inboxes WHERE agent='reviewer'", Integer.class));
    assertEquals(
        0, fixture.jdbc.queryForObject("SELECT count(*) FROM board_topics", Integer.class));
    assertEquals(
        2,
        fixture.jdbc.queryForObject(
            "SELECT count(DISTINCT allowance_id) FROM message_routes", Integer.class));
    messages.start(wake(one), (conversation, outcome) -> {});
    assertFalse(
        messages.busy(wake(two)),
        "Busy task instances must not delay others on the same agent topic");
    messages.start(wake(two), (conversation, outcome) -> {});
    assertEquals(2, voice.calls.size());
    voice.calls.forEach(call -> call.ended().accept(outcome(Outcome.Ending.ANSWERED, "Done")));
  }

  @Test
  void a_failed_publication_rolls_back_the_message_instance_and_allowance() {
    var broken = Mockito.mock(MessageInboxes.class);
    Mockito.doThrow(new IllegalStateException("Publication refused"))
        .when(broken)
        .publish(
            ArgumentMatchers.any(),
            ArgumentMatchers.any(),
            ArgumentMatchers.any(),
            ArgumentMatchers.any(),
            ArgumentMatchers.any(),
            ArgumentMatchers.any());
    var service =
        new RelayMessaging(
            new JdbcMessagingRepository(fixture.jdbc),
            new JdbcAllowanceRepository(fixture.jdbc),
            fixture.conversations,
            fixture.firings,
            broken,
            fixture.work,
            voice,
            (name, project) -> agents.get(name),
            fixture.drained::add,
            fixture.clock);
    int allowances =
        fixture.jdbc.queryForObject("SELECT count(*) FROM execution_allowances", Integer.class);
    assertThrows(
        IllegalStateException.class,
        () ->
            service.send(
                caller,
                new SendMessageTool.Request(
                    "reviewer", "body", null, false, false, "persistent", "call"),
                caller.home()));
    assertEquals(
        0, fixture.jdbc.queryForObject("SELECT count(*) FROM message_records", Integer.class));
    assertEquals(
        0, fixture.jdbc.queryForObject("SELECT count(*) FROM message_instances", Integer.class));
    assertEquals(
        allowances,
        fixture.jdbc.queryForObject("SELECT count(*) FROM execution_allowances", Integer.class));
  }

  @Test
  void incoming_receipts_are_idempotent_and_owned_by_account_project_client() {
    var request = incoming(UUID.randomUUID(), null, "Review", null);
    var first = messages.receive("enzo", request);
    MessageInboxFixture.pulse(fixture);
    var recovered = messages.receive("enzo", request);
    assertEquals(first.id(), recovered.id());
    assertEquals(first.context(), recovered.context());
    assertEquals(first.requestId(), recovered.requestId());
    assertEquals(first.message(), recovered.message());
    assertEquals(first.source(), recovered.source());
    assertEquals(first.createdAt(), recovered.createdAt());
    assertTrue(recovered.revision() >= first.revision());
    assertEquals("SUBMITTED", first.state());
    assertThrows(
        MessageRefused.class,
        () -> messages.receive("enzo", incoming(request.requestId(), null, "Different", null)));
    assertThrows(
        MessageRefused.class, () -> messages.externalTask("enzo", "payments", "other", first.id()));
    assertThrows(
        MessageRefused.class,
        () -> messages.externalTask("enzo", "elsewhere", "remote", first.id()));
    assertThrows(
        MessageRefused.class,
        () -> messages.externalTask("other", "payments", "remote", first.id()));
    var next = messages.receive("enzo", incoming(UUID.randomUUID(), first.context(), "Next", null));
    assertEquals(first.context(), next.context());
    assertEquals(
        2, fixture.jdbc.queryForObject("SELECT count(*) FROM message_instances", Integer.class));
    assertEquals(2, firingCount());
  }

  @Test
  void external_reply_is_durable_without_a_model_wake_and_retains_failure_ending()
      throws Exception {
    var accepted = messages.receive("enzo", incoming(UUID.randomUUID(), null, "Review", null));
    var route = messages.route(accepted.message()).orElseThrow();
    var recipient = messages.instance(route.recipient()).orElseThrow();
    send(
        context("reviewer", recipient.conversation()),
        "{\"reply_to\":\"" + accepted.message() + "\",\"body\":\"Progress\",\"final\":false}",
        "progress");
    messages.complete(accepted.message(), outcome(Outcome.Ending.TURN_CAP, "Reached the cap"));
    var done = messages.externalTask("enzo", "payments", "remote", accepted.id());
    assertEquals("FAILED", done.state());
    assertEquals("TURN_CAP", done.ending());
    assertEquals(2, done.replies().size());
    assertTrue(done.replies().getLast().generated());
    assertEquals("Reached the cap", done.replies().getLast().body());
    assertEquals(1, firingCount(), "Passive return address never runs a model");
    assertThrows(
        MessageRefused.class,
        () -> messages.cancelExternal("enzo", "payments", "remote", accepted.id()));
  }

  @Test
  void cancellation_fences_late_completion_and_approval_does_not_replace_the_receipt() {
    var accepted = messages.receive("enzo", incoming(UUID.randomUUID(), null, "Review", null));
    messages.start(incomingWake(accepted.message()), (conversation, outcome) -> {});
    voice
        .calls
        .getFirst()
        .ended()
        .accept(outcome(Outcome.Ending.AWAITING, "Operator approval required"));
    assertEquals(
        "INPUT_REQUIRED",
        messages.externalTask("enzo", "payments", "remote", accepted.id()).state());
    assertEquals(
        accepted.source(),
        messages
            .receive(
                "enzo",
                incoming(UUID.fromString(accepted.source().identity()), null, "Review", null))
            .source());
    assertEquals(
        "CANCELED", messages.cancelExternal("enzo", "payments", "remote", accepted.id()).state());
    messages.complete(accepted.message(), outcome(Outcome.Ending.ANSWERED, "Late answer"));
    assertEquals(
        "CANCELED", messages.externalTask("enzo", "payments", "remote", accepted.id()).state());
  }

  @Test
  void only_explicit_external_commands_enter_command_binding_and_revoked_logs_refuse_reads() {
    var plain =
        messages.receive("enzo", incoming(UUID.randomUUID(), null, "/skill:review Do it", null));
    messages.start(incomingWake(plain.message()), (conversation, outcome) -> {});
    assertTrue(voice.commands.isEmpty());
    assertTrue(voice.calls.getFirst().utterance().startsWith("Incoming message"));
    var bound =
        messages.receive("enzo", incoming(UUID.randomUUID(), null, "Do it", "/skill:review"));
    messages.start(incomingWake(bound.message()), (conversation, outcome) -> {});
    assertEquals(List.of("/skill:review Do it"), voice.commands);
    messages.useLogVisibility((conversation, account) -> false);
    assertThrows(
        MessageRefused.class,
        () -> messages.externalTask("enzo", "payments", "remote", bound.id()));
    assertThrows(
        MessageRefused.class,
        () -> messages.cancelExternal("enzo", "payments", "remote", bound.id()));
  }

  private ProjectStore routeProjects;

  private Path routingWorkspace(String project) throws Exception {
    Path root = Files.createDirectories(definitions.resolve("workspaces").resolve(project));
    if (routeProjects == null) {
      routeProjects =
          new ProjectStore(
              fixture.jdbc,
              definitions.resolve("config"),
              definitions.resolve("sampling"),
              null,
              null,
              null);
      messages.useRouting(
          new ProjectMessageRouting(routeProjects, new JdbcProjectMembers(fixture.jdbc)));
    }
    routeProjects.define(project, root, List.of(), "enzo");
    // A fixture workspace grants no data authority merely because its creator is an admin.
    fixture.jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'enzo','CONTRIBUTOR' FROM projects WHERE name=? ON CONFLICT(project_id,handle) DO UPDATE SET role='CONTRIBUTOR'",
        project);
    return root;
  }

  private void routingManifest(String project, Map<String, Object> policy) throws Exception {
    Files.writeString(
        routingWorkspace(project).resolve("plowshare"),
        JSON.writeValueAsString(
            Map.of(
                "version",
                1,
                "name",
                project,
                "routing",
                policy,
                "integration",
                Map.of("enabled", true))));
  }

  private PersonalSpaces personalSpaces;
  private MessagingProperties personalSettings;

  private void personalRouting() throws Exception {
    routingWorkspace("payments");
    var data = new DataLayout(definitions.resolve("personal-data")).initialise();
    personalSpaces =
        new PersonalSpaces(
            new JdbcPersonalSpaceRepository(fixture.jdbc),
            new ArchiveConfig()
                .unitOfWork(new DataSourceTransactionManager(fixture.jdbc.getDataSource())),
            data);
    personalSpaces.ensure("enzo");
    personalSettings = new MessagingProperties();
    messages.useRouting(
        new ProjectMessageRouting(
            routeProjects, new JdbcProjectMembers(fixture.jdbc), personalSettings, personalSpaces));
  }

  @Test
  void personal_routes_default_to_any_accessible_project_and_have_account_qualified_addresses()
      throws Exception {
    personalRouting();
    routingWorkspace("notifications");
    String personal = PersonalSpaces.name("enzo");
    JsonNode inbound =
        send(
            caller,
            "{\"to_project\":\"Personal:enzo\",\"to\":\"reviewer\",\"body\":\"Review\",\"reply_expected\":true}",
            "personal-in");
    assertEquals("Personal:enzo", inbound.path("to_project").asText());
    var receiver = messages.instance(inbound.path("to").asText()).orElseThrow();
    assertEquals(personal, receiver.project());
    assertTrue(
        messages
            .incoming(inbound.path("message").asText())
            .contains("\"to_project\":\"Personal:enzo\""));
    var context =
        new RunExtras.Context(
            agents.get(receiver.agent()),
            receiver.conversation(),
            null,
            Transcript.NONE,
            Home.of(personal),
            "enzo");
    JsonNode outbound =
        send(
            context,
            "{\"to_project\":\"notifications\",\"to\":\"reviewer\",\"body\":\"Notify\"}",
            "personal-out");
    assertEquals("Personal:enzo", outbound.path("from_project").asText());
    assertEquals("notifications", outbound.path("to_project").asText());
    assertTrue(
        messages
            .tool(context)
            .run(
                "{\"to_project\":\"unknown-project\",\"to\":\"reviewer\",\"body\":\"Denied\"}",
                context.home())
            .contains("not permitted"));
    assertNull(routeProjects.id("unknown-project"));
    fixture.jdbc.update("UPDATE admins SET server_admin = FALSE WHERE handle = 'enzo'");
    fixture.jdbc.update(
        "DELETE FROM project_members WHERE project_id = (SELECT id FROM projects WHERE name = 'notifications')");
    assertTrue(
        messages
            .tool(context)
            .run(
                "{\"to_project\":\"notifications\",\"to\":\"reviewer\",\"body\":\"Denied\"}",
                context.home())
            .contains("not permitted"));
    fixture.jdbc.update("INSERT INTO admins(handle,password_hash) VALUES ('bob','h')");
    personalSpaces.ensure("bob");
    for (String address : List.of("Personal:bob", PersonalSpaces.name("bob"))) {
      String denied =
          messages
              .tool(caller)
              .run(
                  JSON.writeValueAsString(
                      Map.of("to_project", address, "to", "reviewer", "body", "Denied")),
                  caller.home());
      assertTrue(denied.contains("another account"), denied);
    }
    assertEquals(
        2, fixture.jdbc.queryForObject("SELECT count(*) FROM message_routes", Integer.class));
  }

  @Test
  void personal_defaults_can_be_disabled_independently_and_explicit_qualified_routes_then_apply()
      throws Exception {
    personalRouting();
    String personal = PersonalSpaces.name("enzo");
    JsonNode first =
        send(
            caller,
            "{\"to_project\":\"Personal:enzo\",\"to\":\"reviewer\",\"body\":\"First\"}",
            "initial");
    var receiver = messages.instance(first.path("to").asText()).orElseThrow();
    var context =
        new RunExtras.Context(
            agents.get(receiver.agent()),
            receiver.conversation(),
            null,
            Transcript.NONE,
            Home.of(personal),
            "enzo");
    personalSettings.getPersonal().setSendToAnyProject(false);
    assertFalse(
        messages
            .tool(context)
            .run(
                "{\"to_project\":\"payments\",\"to\":\"reviewer\",\"body\":\"Denied\"}",
                context.home())
            .startsWith("{"));
    send(
        caller,
        "{\"to_project\":\"Personal:enzo\",\"to\":\"reviewer\",\"body\":\"Still inbound\"}",
        "inbound-enabled");
    personalSettings.getPersonal().setAcceptFromAnyProject(false);
    assertFalse(
        messages
            .tool(caller)
            .run(
                "{\"to_project\":\"Personal:enzo\",\"to\":\"reviewer\",\"body\":\"Denied\"}",
                caller.home())
            .startsWith("{"));
    Path root = personalSpaces.workspace(personal).orElseThrow();
    Files.writeString(
        root.resolve("plowshare"),
        JSON.writeValueAsString(
            Map.of(
                "version",
                1,
                "name",
                "Personal:enzo",
                "routing",
                Map.of(
                    "acceptFrom",
                    List.of("payments"),
                    "sendTo",
                    List.of("payments"),
                    "routeFiles",
                    List.of("routes.json")))));
    Files.writeString(
        root.resolve("routes.json"),
        "{\"version\":1,\"routes\":[{\"name\":\"pay\",\"project\":\"payments\",\"agent\":\"reviewer\"}]}");
    routingManifest(
        "payments",
        Map.of("acceptFrom", List.of("Personal:enzo"), "sendTo", List.of("Personal:enzo")));
    send(
        caller,
        "{\"to_project\":\"Personal:enzo\",\"to\":\"reviewer\",\"body\":\"Explicit inbound\"}",
        "explicit-in");
    JsonNode explicit =
        send(context, "{\"route\":\"pay\",\"body\":\"Explicit outbound\"}", "explicit-out");
    assertEquals("payments", explicit.path("to_project").asText());
  }

  @Test
  void manual_example_routes_between_projects_and_correlates_a_reply_without_reverse_permission()
      throws Exception {
    // Exercise the deployable manual files, so a stale alias, marker or definition fails here.
    Path examples = Path.of("../docs/examples/messaging");
    Path research = routingWorkspace("research");
    Path notes = routingWorkspace("notes");
    Files.copy(examples.resolve("research/plowshare"), research.resolve("plowshare"));
    Files.copy(examples.resolve("notes/plowshare"), notes.resolve("plowshare"));
    Files.createDirectories(research.resolve("routes"));
    Files.copy(
        examples.resolve("research/routes/internal.json"),
        research.resolve("routes/internal.json"));
    for (String name : List.of("route_sender", "note_reviewer")) {
      Files.copy(examples.resolve(name + ".md"), definitions.resolve(name + ".md"));
    }
    agents = AgentRegistry.of(definitions, Set.of("send_message"));
    String conversation =
        fixture.conversations.open(Home.of("research"), Budget.of(20), TurnCap.of(8), "enzo").id();
    var sender =
        new RunExtras.Context(
            agents.get("route_sender"),
            conversation,
            null,
            Transcript.NONE,
            Home.of("research"),
            "enzo");
    messages.useLineage(
        (from, to) -> fail("A cross-project message must not inherit source document grants"));
    JsonNode receipt =
        send(
            sender,
            "{\"route\":\"review-note\",\"body\":\"Assess this claim\","
                + "\"reply_expected\":true,\"timeout_seconds\":600}",
            "manual-route");
    var destination = messages.instance(receipt.path("to").asText()).orElseThrow();
    assertEquals("notes", destination.project());
    assertEquals("note_reviewer", destination.agent());
    assertEquals("enzo", destination.account());
    messages.start(wake(receipt), (log, outcome) -> {});
    assertEquals(destination, voice.calls.getLast().instance());
    var receiver =
        new RunExtras.Context(
            agents.get(destination.agent()),
            destination.conversation(),
            null,
            Transcript.NONE,
            Home.of("notes"),
            "enzo");
    // The examples deliberately close unsolicited reverse sends while permitting a reply.
    assertTrue(
        messages
            .tool(receiver)
            .run(
                "{\"to_project\":\"research\",\"to\":\"route_sender\",\"body\":\"New request\"}",
                receiver.home())
            .contains("Both projects"));
    JsonNode reply =
        send(
            receiver,
            "{\"reply_to\":\""
                + receipt.path("message").asText()
                + "\",\"body\":\"More evidence is needed\",\"final\":true}",
            "manual-reply");
    assertEquals(receipt.path("from"), reply.path("to"));
    assertTrue(messages.finalReply(receipt.path("message").asText()).isPresent());
  }

  @Test
  void cross_project_delivery_requires_both_policies_and_rechecks_revocation_before_new_sends()
      throws Exception {
    routingManifest("payments", Map.of());
    routingManifest("notifications", Map.of());
    String args = "{\"to_project\":\"notifications\",\"to\":\"reviewer\",\"body\":\"Review\"}";
    assertTrue(messages.tool(caller).run(args, caller.home()).contains("Both projects"));
    routingManifest("payments", Map.of("sendTo", List.of("notifications")));
    assertTrue(messages.tool(caller).run(args, caller.home()).contains("Both projects"));
    assertEquals(
        0, fixture.jdbc.queryForObject("SELECT count(*) FROM message_instances", Integer.class));
    routingManifest("notifications", Map.of("acceptFrom", List.of("payments")));
    JsonNode receipt = send(caller, args, "cross");
    var destination = messages.instance(receipt.path("to").asText()).orElseThrow();
    assertEquals("notifications", destination.project());
    assertEquals("enzo", destination.account());
    assertEquals(
        Home.of("notifications"),
        fixture.conversations.find(destination.conversation()).orElseThrow().home());
    assertTrue(
        messages
            .incoming(receipt.path("message").asText())
            .contains("\"from_project\":\"payments\""));
    assertEquals(receipt, send(caller, args, "cross"), "A retried send owes only one durable wake");
    assertEquals(receipt.path("to"), send(caller, args, "second").path("to"));
    routingManifest("notifications", Map.of());
    assertTrue(messages.tool(caller).run(args, caller.home()).contains("Both projects"));
    assertTrue(
        messages
            .tool(caller)
            .run(
                "{\"to\":\"" + destination.id() + "\",\"body\":\"Denied by address too\"}",
                caller.home())
            .contains("Both projects"));
    assertEquals(
        2, fixture.jdbc.queryForObject("SELECT count(*) FROM message_routes", Integer.class));
    assertEquals(2, firingCount());
  }

  @Test
  void
      named_routes_queue_recipient_work_without_inheriting_inputs_and_replies_survive_policy_revocation()
          throws Exception {
    routingManifest(
        "payments",
        Map.of("sendTo", List.of("notifications"), "routeFiles", List.of("routes/internal.json")));
    routingManifest("notifications", Map.of("acceptFrom", List.of("payments")));
    Path routes =
        Files.createDirectories(routingWorkspace("payments").resolve("routes"))
            .resolve("internal.json");
    Files.writeString(
        routes,
        "{\"version\":1,\"routes\":[{\"name\":\"review\",\"project\":\"notifications\",\"agent\":\"reviewer\"}]}");
    messages.useLineage(
        (sender, recipient) -> fail("Cross-project routing must not inherit document selections"));
    JsonNode receipt =
        send(caller, "{\"route\":\"review\",\"body\":\"Review\",\"reply_expected\":true}", "named");
    JsonNode task =
        send(
            caller,
            "{\"route\":\"review\",\"body\":\"Task\",\"lifetime\":\"task\",\"reply_expected\":true}",
            "named-task");
    var target = messages.instance(receipt.path("to").asText()).orElseThrow();
    assertEquals("notifications", target.project());
    assertFalse(messages.visible(caller.conversationId(), target.conversation()));
    messages.start(wake(receipt), (conversation, outcome) -> {});
    assertEquals(target, voice.calls.getLast().instance());
    routingManifest("payments", Map.of());
    routingManifest("notifications", Map.of());
    var receiver =
        new RunExtras.Context(
            agents.get(target.agent()),
            target.conversation(),
            null,
            Transcript.NONE,
            Home.of(target.project()),
            target.account());
    JsonNode reply =
        send(
            receiver,
            "{\"reply_to\":\"" + receipt.path("message").asText() + "\",\"body\":\"Reviewed\"}",
            "reply-cross");
    assertEquals(receipt.path("from"), reply.path("to"));
    messages.complete(
        task.path("message").asText(), outcome(Outcome.Ending.ANSWERED, "Task reviewed"));
    assertTrue(messages.finalReply(task.path("message").asText()).isPresent());
    assertTrue(
        messages
            .tool(receiver)
            .run(
                "{\"to\":\"" + receipt.path("from").asText() + "\",\"body\":\"Unsolicited\"}",
                receiver.home())
            .contains("Both projects"));
    assertTrue(
        messages
            .tool(receiver)
            .run(
                "{\"reply_to\":\""
                    + task.path("message").asText()
                    + "\",\"body\":\"Forged reply\"}",
                receiver.home())
            .contains("Only the recipient"));
  }

  @Test
  void routing_files_are_bounded_fenced_and_cannot_expand_project_permissions() throws Exception {
    routingManifest("payments", Map.of("routeFiles", List.of("routes.json")));
    routingManifest("notifications", Map.of("acceptFrom", List.of("payments")));
    Path route = routingWorkspace("payments").resolve("routes.json");
    String valid =
        "{\"version\":1,\"routes\":[{\"name\":\"review\",\"project\":\"notifications\",\"agent\":\"reviewer\"}]}";
    Files.writeString(route, valid);
    assertTrue(
        messages
            .tool(caller)
            .run("{\"route\":\"review\",\"body\":\"Denied\"}", caller.home())
            .contains("Both projects"));
    routingManifest(
        "payments",
        Map.of("sendTo", List.of("notifications"), "routeFiles", List.of("../outside.json")));
    assertTrue(
        messages
            .tool(caller)
            .run("{\"route\":\"review\",\"body\":\"Denied\"}", caller.home())
            .contains("relative paths"));
    routingManifest(
        "payments",
        Map.of("sendTo", List.of("notifications"), "routeFiles", List.of("routes.json")));
    for (String invalid :
        List.of(
            "{",
            "{\"version\":2,\"routes\":[]}",
            valid.replace(
                "}]",
                ",\"extra\":true},{\"name\":\"review\",\"project\":\"notifications\",\"agent\":\"reviewer\"}]"),
            " ".repeat(65537))) {
      Files.writeString(route, invalid);
      assertFalse(
          messages
              .tool(caller)
              .run("{\"route\":\"review\",\"body\":\"Denied\"}", caller.home())
              .startsWith("{"));
    }
    Files.delete(route);
    Path outside = definitions.resolve("outside.json");
    Files.writeString(outside, valid);
    Files.createSymbolicLink(route, outside);
    assertTrue(
        messages
            .tool(caller)
            .run("{\"route\":\"review\",\"body\":\"Denied\"}", caller.home())
            .contains("symbolic links"));
    assertEquals(
        0, fixture.jdbc.queryForObject("SELECT count(*) FROM message_routes", Integer.class));
    assertEquals(
        0, fixture.jdbc.queryForObject("SELECT count(*) FROM message_instances", Integer.class));
  }

  private void messageRoutes(List<Map<String, Object>> routes) throws Exception {
    routingManifest(
        "payments",
        Map.of("sendTo", List.of("notifications"), "routeFiles", List.of("routes.json")));
    routingManifest("notifications", Map.of("acceptFrom", List.of("payments")));
    Files.writeString(
        routingWorkspace("payments").resolve("routes.json"),
        JSON.writeValueAsString(Map.of("version", 1, "routes", routes)));
  }

  private Map<String, Object> retainedRoute(String name) {
    return Map.of(
        "name", name, "project", "notifications", "agent", "reviewer", "retainConversation", true);
  }

  @Test
  void retained_routes_have_dedicated_logs_survive_restart_and_do_not_follow_project_defaults()
      throws Exception {
    messageRoutes(
        List.of(
            retainedRoute("review"),
            retainedRoute("audit"),
            Map.of("name", "shared", "project", "notifications", "agent", "reviewer")));
    JsonNode shared = send(caller, "{\"route\":\"shared\",\"body\":\"Shared\"}", "shared");
    JsonNode sharedAgain =
        send(caller, "{\"route\":\"shared\",\"body\":\"Shared again\"}", "shared-again");
    assertEquals(
        shared.path("to"),
        sharedAgain.path("to"),
        "Unconfigured routes retain the existing default behavior");
    JsonNode first = send(caller, "{\"route\":\"review\",\"body\":\"Review\"}", "retained-first");
    JsonNode audit = send(caller, "{\"route\":\"audit\",\"body\":\"Audit\"}", "retained-audit");
    assertNotEquals(shared.path("to"), first.path("to"));
    assertNotEquals(audit.path("to"), first.path("to"));
    var pinned = messages.instance(first.path("to").asText()).orElseThrow();
    assertEquals(pinned.conversation(), first.path("to_conversation").asText());
    assertFalse(messages.inspect(pinned.id(), "enzo").defaultInstance());
    messages.complete(first.path("message").asText(), outcome(Outcome.Ending.ANSWERED, "Reviewed"));
    messages.makeDefault(audit.path("to").asText(), "enzo");
    messages =
        new RelayMessaging(
            new JdbcMessagingRepository(fixture.jdbc),
            new JdbcAllowanceRepository(fixture.jdbc),
            fixture.conversations,
            fixture.firings,
            MessageInboxFixture.inboxes(fixture),
            fixture.work,
            voice,
            (name, project) -> agents.get(name),
            fixture.drained::add,
            fixture.clock);
    messages.useRouting(
        new ProjectMessageRouting(routeProjects, new JdbcProjectMembers(fixture.jdbc)));
    JsonNode later = send(caller, "{\"route\":\"review\",\"body\":\"Later\"}", "retained-later");
    assertEquals(first.path("to"), later.path("to"));
    assertEquals(first.path("to_conversation"), later.path("to_conversation"));
    assertEquals(
        later.path("message"),
        send(caller, "{\"route\":\"review\",\"body\":\"Later\"}", "retained-later")
            .path("message"));
    assertEquals(
        2,
        fixture.jdbc.queryForObject("SELECT count(*) FROM message_route_bindings", Integer.class));
  }

  @Test
  void routes_can_target_an_existing_conversation_without_creating_or_announcing_a_new_log()
      throws Exception {
    String target =
        fixture
            .conversations
            .open(Home.of("notifications"), Budget.of(20), TurnCap.of(4), "enzo")
            .id();
    messageRoutes(
        List.of(
            Map.of(
                "name",
                "review",
                "project",
                "notifications",
                "agent",
                "reviewer",
                "conversation",
                target)));
    var opened = new ArrayList<LogStages.LogOpened>();
    messages.useLogStages(
        new LogStages() {
          public void opened(LogOpened event) {
            opened.add(event);
          }
        });
    JsonNode first = send(caller, "{\"route\":\"review\",\"body\":\"Review\"}", "existing-first");
    JsonNode next = send(caller, "{\"route\":\"review\",\"body\":\"More\"}", "existing-next");
    assertEquals(target, first.path("to_conversation").asText());
    assertEquals(first.path("to"), next.path("to"));
    assertEquals(
        2, fixture.jdbc.queryForObject("SELECT count(*) FROM conversations", Integer.class));
    assertTrue(opened.isEmpty());
    messages.start(wake(first), (conversation, outcome) -> {});
    assertEquals(target, voice.calls.getLast().instance().conversation());
    var receiver =
        new RunExtras.Context(
            agents.get("reviewer"),
            target,
            null,
            Transcript.NONE,
            Home.of("notifications"),
            "enzo");
    JsonNode reply =
        send(
            receiver,
            "{\"reply_to\":\"" + first.path("message").asText() + "\",\"body\":\"Reviewed\"}",
            "existing-reply");
    assertEquals(first.path("from"), reply.path("to"));
  }

  @Test
  void retained_routes_never_silently_replace_stopped_logs_or_changed_destinations()
      throws Exception {
    messageRoutes(List.of(retainedRoute("review")));
    JsonNode first = send(caller, "{\"route\":\"review\",\"body\":\"Review\"}", "first");
    assertTrue(
        messages
            .tool(caller)
            .run("{\"route\":\"review\",\"body\":\"Fresh\",\"lifetime\":\"task\"}", caller.home())
            .contains("retains its conversation"));
    messageRoutes(
        List.of(
            Map.of(
                "name",
                "review",
                "project",
                "notifications",
                "agent",
                "bot",
                "retainConversation",
                true)));
    assertTrue(
        messages
            .tool(caller)
            .run("{\"route\":\"review\",\"body\":\"Changed\"}", caller.home())
            .contains("pinned to another destination"));
    messageRoutes(List.of(retainedRoute("review")));
    messages.stop(first.path("to").asText(), "enzo", true);
    assertFalse(
        messages
            .tool(caller)
            .run("{\"route\":\"review\",\"body\":\"Later\"}", caller.home())
            .startsWith("{"));
    assertEquals(
        2, fixture.jdbc.queryForObject("SELECT count(*) FROM conversations", Integer.class));
    assertEquals(
        1,
        fixture.jdbc.queryForObject("SELECT count(*) FROM message_route_bindings", Integer.class));
  }

  @Test
  void explicit_route_conversations_reject_foreign_wrong_project_hidden_archived_and_task_logs()
      throws Exception {
    fixture.jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('other', 'h')");
    String foreign =
        fixture
            .conversations
            .open(Home.of("notifications"), Budget.of(20), TurnCap.of(4), "other")
            .id();
    String archived =
        fixture
            .conversations
            .open(Home.of("notifications"), Budget.of(20), TurnCap.of(4), "enzo")
            .id();
    fixture.conversations.moveTo(archived, ConversationLifecycle.ARCHIVED);
    String hidden =
        fixture
            .conversations
            .open(Home.of("notifications"), Budget.of(20), TurnCap.of(4), "enzo")
            .id();
    messages.useLogVisibility((conversation, account) -> !conversation.equals(hidden));
    for (String id : List.of(foreign, archived, hidden, caller.conversationId(), "cnv_missing")) {
      messageRoutes(
          List.of(
              Map.of(
                  "name",
                  "review",
                  "project",
                  "notifications",
                  "agent",
                  "reviewer",
                  "conversation",
                  id)));
      assertFalse(
          messages
              .tool(caller)
              .run("{\"route\":\"review\",\"body\":\"Denied\"}", caller.home())
              .startsWith("{"),
          id);
    }
    assertEquals(
        0, fixture.jdbc.queryForObject("SELECT count(*) FROM message_routes", Integer.class));
    messageRoutes(
        List.of(Map.of("name", "plain", "project", "notifications", "agent", "reviewer")));
    JsonNode task =
        send(caller, "{\"route\":\"plain\",\"body\":\"Task\",\"lifetime\":\"task\"}", "task");
    String taskConversation = task.path("to_conversation").asText();
    messageRoutes(
        List.of(
            Map.of(
                "name",
                "review",
                "project",
                "notifications",
                "agent",
                "reviewer",
                "conversation",
                taskConversation)));
    assertTrue(
        messages
            .tool(caller)
            .run("{\"route\":\"review\",\"body\":\"Denied\"}", caller.home())
            .contains("task-scoped"));
  }

  @Test
  void malformed_conversation_retention_configuration_is_refused_before_delivery()
      throws Exception {
    for (Object retain : List.of("true", 1)) {
      messageRoutes(
          List.of(
              Map.of(
                  "name",
                  "review",
                  "project",
                  "notifications",
                  "agent",
                  "reviewer",
                  "retainConversation",
                  retain)));
      assertTrue(
          messages
              .tool(caller)
              .run("{\"route\":\"review\",\"body\":\"Denied\"}", caller.home())
              .contains("true or false"));
    }
    messageRoutes(
        List.of(
            Map.of(
                "name",
                "review",
                "project",
                "notifications",
                "agent",
                "reviewer",
                "conversation",
                "cnv_test",
                "retainConversation",
                false)));
    assertTrue(
        messages
            .tool(caller)
            .run("{\"route\":\"review\",\"body\":\"Denied\"}", caller.home())
            .contains("not both"));
    assertEquals(
        0,
        fixture.jdbc.queryForObject("SELECT count(*) FROM message_route_bindings", Integer.class));
    assertEquals(
        0, fixture.jdbc.queryForObject("SELECT count(*) FROM message_routes", Integer.class));
  }

  @Test
  void concurrent_first_sends_to_a_retained_route_create_one_binding_and_one_log()
      throws Exception {
    messageRoutes(List.of(retainedRoute("review")));
    String second =
        fixture.conversations.open(Home.of("payments"), Budget.of(20), TurnCap.of(4), "enzo").id();
    var start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var first =
          workers.submit(
              () -> {
                start.await();
                return send(
                    caller, "{\"route\":\"review\",\"body\":\"First\"}", "concurrent-first");
              });
      var next =
          workers.submit(
              () -> {
                start.await();
                return send(
                    context("sender", second),
                    "{\"route\":\"review\",\"body\":\"Second\"}",
                    "concurrent-next");
              });
      start.countDown();
      assertEquals(
          first.get(10, TimeUnit.SECONDS).path("to"), next.get(10, TimeUnit.SECONDS).path("to"));
    }
    assertEquals(
        1,
        fixture.jdbc.queryForObject("SELECT count(*) FROM message_route_bindings", Integer.class));
    assertEquals(
        3, fixture.jdbc.queryForObject("SELECT count(*) FROM conversations", Integer.class));
  }

  @Test
  void retained_route_bindings_are_account_and_source_project_scoped_and_recheck_permission()
      throws Exception {
    messageRoutes(List.of(retainedRoute("review")));
    JsonNode first = send(caller, "{\"route\":\"review\",\"body\":\"Enzo\"}", "enzo-route");
    fixture.jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('other', 'h')");
    var members = new JdbcProjectMembers(fixture.jdbc);
    members.add("payments", "other");
    members.add("notifications", "other");
    fixture.jdbc.update("UPDATE project_members SET role='CONTRIBUTOR' WHERE handle='other'");
    String otherConversation =
        fixture.conversations.open(Home.of("payments"), Budget.of(20), TurnCap.of(4), "other").id();
    var other =
        new RunExtras.Context(
            agents.get("sender"),
            otherConversation,
            null,
            Transcript.NONE,
            Home.of("payments"),
            "other");
    JsonNode second = send(other, "{\"route\":\"review\",\"body\":\"Other\"}", "other-route");
    assertNotEquals(first.path("to"), second.path("to"));
    routingManifest(
        "operations",
        Map.of("sendTo", List.of("notifications"), "routeFiles", List.of("routes.json")));
    Files.writeString(
        routingWorkspace("operations").resolve("routes.json"),
        JSON.writeValueAsString(Map.of("version", 1, "routes", List.of(retainedRoute("review")))));
    routingManifest("notifications", Map.of("acceptFrom", List.of("payments", "operations")));
    String operations =
        fixture
            .conversations
            .open(Home.of("operations"), Budget.of(20), TurnCap.of(4), "enzo")
            .id();
    var source =
        new RunExtras.Context(
            agents.get("sender"), operations, null, Transcript.NONE, Home.of("operations"), "enzo");
    JsonNode third =
        send(source, "{\"route\":\"review\",\"body\":\"Operations\"}", "operations-route");
    assertNotEquals(first.path("to"), third.path("to"));
    routingManifest("notifications", Map.of());
    assertTrue(
        messages
            .tool(caller)
            .run("{\"route\":\"review\",\"body\":\"Revoked\"}", caller.home())
            .contains("Both projects"));
    assertEquals(
        3,
        fixture.jdbc.queryForObject("SELECT count(*) FROM message_route_bindings", Integer.class));
    assertEquals(
        3, fixture.jdbc.queryForObject("SELECT count(*) FROM message_routes", Integer.class));
  }

  @Test
  void cross_project_routes_do_not_bypass_membership_accounts_or_private_client_scopes()
      throws Exception {
    routingManifest("payments", Map.of("sendTo", List.of("notifications")));
    routingManifest("notifications", Map.of("acceptFrom", List.of("payments")));
    fixture.jdbc.update("UPDATE admins SET server_admin = FALSE WHERE handle = 'enzo'");
    fixture.jdbc.update(
        "DELETE FROM project_members WHERE project_id = (SELECT id FROM projects WHERE name = 'notifications') AND handle = 'enzo'");
    String args = "{\"to_project\":\"notifications\",\"to\":\"reviewer\",\"body\":\"Review\"}";
    assertTrue(messages.tool(caller).run(args, caller.home()).contains("not permitted"));
    new JdbcProjectMembers(fixture.jdbc).add("notifications", "enzo");
    JsonNode receipt = send(caller, args, "permitted");
    fixture.jdbc.update("INSERT INTO admins(handle,password_hash) VALUES ('other','h')");
    String foreign =
        fixture.conversations.open(Home.of("payments"), Budget.of(20), TurnCap.of(4), "other").id();
    var other =
        new RunExtras.Context(
            agents.get("sender"), foreign, null, Transcript.NONE, Home.of("payments"), "other");
    assertTrue(
        messages
            .tool(other)
            .run(
                "{\"to\":\"" + receipt.path("to").asText() + "\",\"body\":\"Denied\"}",
                other.home())
            .contains("No accessible instance"));
    var privateProject =
        routeProjects.attachClient("Private", "/client-only", "laptop", "client", "enzo");
    assertTrue(
        messages
            .tool(caller)
            .run(
                "{\"to_project\":\""
                    + privateProject.name()
                    + "\",\"to\":\"reviewer\",\"body\":\"Denied\"}",
                caller.home())
            .contains("not permitted"));
    assertEquals(
        1, fixture.jdbc.queryForObject("SELECT count(*) FROM message_routes", Integer.class));
  }

  @Test
  void a_send_is_a_messaging_record_with_a_durable_return_address_and_relay_admitted_wake()
      throws Exception {
    JsonNode receipt = request(true);
    String id = receipt.path("message").asText();
    assertTrue(new JdbcMessagingRepository(fixture.jdbc).message(id).isPresent());
    assertTrue(messages.owns(wake(receipt)));
    String incoming = messages.incoming(id);
    assertTrue(incoming.contains(receipt.path("from").asText()));
    assertTrue(incoming.contains("return_address"));
    assertTrue(fixture.store.summaries("enzo", "payments", true, 0, 100).isEmpty());
    assertTrue(fixture.store.openTopics().isEmpty());
    assertFalse(messages.visible(recipient(receipt).conversationId(), caller.conversationId()));
  }

  @Test
  void attributedSendRetriesSurviveRestartAndDistinctStepsReuseProviderIds() throws Exception {
    // This test owns invocation deduplication. The archive's exact run-source proof is covered
    // separately; provide an explicit trusted source rather than treating missing history as root.
    messages =
        new RelayMessaging(
            new JdbcMessagingRepository(fixture.jdbc),
            new JdbcAllowanceRepository(fixture.jdbc),
            fixture.conversations,
            fixture.firings,
            MessageInboxFixture.inboxes(fixture),
            fixture.work,
            voice,
            (name, project) -> agents.get(name),
            fixture.drained::add,
            fixture.clock,
            (project, owner) -> Optional.of(RelayCausation.root("fixture-run")),
            8);
    var lineage = UsageLineage.root(caller.conversationId());
    var run = UsageLineage.root("retained-run");
    var base = UsageAttribution.project("enzo", "1", UsageAttribution.Operation.AGENT_CHAT);
    var firstOwner = base.withExecution(lineage, run, UsageLineage.NONE, "sender", 1L, 1L);
    String input = "{\"to\":\"reviewer\",\"body\":\"Review\"}";
    var tool = messages.tool(caller);
    tool.calledAs("provider-id");
    JsonNode first = JSON.readTree(tool.run(input, caller.home(), firstOwner));
    var reconstructed = messages.tool(caller);
    reconstructed.calledAs("provider-id");
    assertEquals(first, JSON.readTree(reconstructed.run(input, caller.home(), firstOwner)));
    reconstructed.calledAs("provider-id");
    var secondOwner = base.withExecution(lineage, run, UsageLineage.NONE, "sender", 1L, 2L);
    JsonNode second = JSON.readTree(reconstructed.run(input, caller.home(), secondOwner));
    assertNotEquals(first.path("message"), second.path("message"));
    assertEquals(2, firingCount());
  }

  @Test
  void attributed_send_without_exact_execution_ancestry_never_acquires_a_fresh_root()
      throws Exception {
    var owner =
        UsageAttribution.project("enzo", "1", UsageAttribution.Operation.AGENT_CHAT)
            .withExecution(
                UsageLineage.root(caller.conversationId()),
                UsageLineage.root("retained-run"),
                UsageLineage.NONE,
                "sender",
                1L,
                1L);
    var tool = messages.tool(caller);
    tool.calledAs("provider-id");
    assertEquals(
        "Message execution ancestry is unavailable",
        tool.run("{\"to\":\"reviewer\",\"body\":\"Review\"}", caller.home(), owner));
    assertEquals(
        0, fixture.jdbc.queryForObject("SELECT count(*) FROM message_routes", Integer.class));
  }

  @Test
  void concurrentIdenticalSendsCommitOneMessageAndWake() throws Exception {
    var start = new CountDownLatch(1);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var a =
          pool.submit(
              () -> {
                start.await();
                return request(true);
              });
      var b =
          pool.submit(
              () -> {
                start.await();
                return request(true);
              });
      start.countDown();
      assertEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
    }
    assertEquals(
        1, fixture.jdbc.queryForObject("SELECT count(*) FROM message_routes", Integer.class));
    assertEquals(1, firingCount());
  }

  @Test
  void conflictingSendIdentityCannotChangeMessageWakeAllowanceOrDeadline() throws Exception {
    JsonNode first = request(true);
    String id = first.path("message").asText();
    var before = fixture.jdbc.queryForList("SELECT message,deadline_at FROM message_routes");
    String call =
        fixture.jdbc.queryForObject(
            "SELECT call_id FROM message_routes WHERE message=?", String.class, id);
    var attempt =
        new SendMessageTool.Request(
            "reviewer",
            "changed",
            null,
            true,
            false,
            "persistent",
            call.substring(call.indexOf(':') + 1),
            99L);
    String result = messages.send(caller, attempt, Home.of("payments"));
    assertTrue(result.contains("different message arguments"), result);
    assertEquals(
        before, fixture.jdbc.queryForList("SELECT message,deadline_at FROM message_routes"));
    assertEquals(1, firingCount());
  }

  @Test
  void retries_do_not_duplicate_messages_or_wakes_and_names_reuse_the_default() throws Exception {
    JsonNode first = request(true);
    JsonNode retry = request(true);
    assertEquals(first, retry);
    JsonNode next = send(caller, "{\"to\":\"reviewer\",\"body\":\"Another\"}", "next");
    assertEquals(first.path("to"), next.path("to"));
    assertEquals(
        2, fixture.jdbc.queryForObject("SELECT count(*) FROM message_routes", Integer.class));
    assertEquals(2, firingCount());
  }

  @Test
  void delivery_page_batches_status_instances_jobs_and_final_reply_with_existing_paging()
      throws Exception {
    JsonNode request = request(true);
    String original = request.path("message").asText();
    String sender = request.path("from").asText();
    var firing = wake(request);
    assertTrue(fixture.firings.claimStart(firing.id(), fixture.clock.get()));
    fixture.firings.startedAs(firing.id(), "running-job");
    var before = messages.deliveries(sender, "enzo", 0, 10);
    assertEquals(List.of(messages.delivery(original, "enzo")), before);
    assertEquals("running", before.getFirst().state());
    assertEquals("running-job", before.getFirst().job());
    var reply =
        send(
            recipient(request),
            "{\"reply_to\":\"" + original + "\",\"body\":\"Reviewed\",\"final\":true}",
            "reply");
    var page = messages.deliveries(sender, "enzo", 0, 10);
    assertEquals(2, page.size());
    for (var row : page) assertEquals(messages.delivery(row.message(), "enzo"), row);
    var requestRow =
        page.stream().filter(row -> row.message().equals(original)).findFirst().orElseThrow();
    assertEquals(reply.path("message").asText(), requestRow.reply());
    assertEquals(page.subList(1, 2), messages.deliveries(sender, "enzo", 1, 1));
    assertThrows(MessageRefused.class, () -> messages.deliveries(sender, "another-account", 0, 10));
    messages.useLogVisibility((conversation, account) -> false);
    assertThrows(MessageRefused.class, () -> messages.deliveries(sender, "enzo", 0, 10));
  }

  @Test
  void explicit_final_reply_satisfies_the_request_and_repeated_completion_cannot_add_a_fallback()
      throws Exception {
    JsonNode request = request(true);
    String original = request.path("message").asText();
    JsonNode reply =
        send(
            recipient(request),
            "{\"reply_to\":\"" + original + "\",\"body\":\"Reviewed\",\"final\":true}",
            "reply");
    assertEquals(request.path("from"), reply.path("to"));
    messages.complete(original, outcome(Outcome.Ending.ANSWERED, "Different prose"));
    messages.complete(original, outcome(Outcome.Ending.ANSWERED, "Different prose"));
    assertEquals(reply.path("message").asText(), messages.finalReply(original).orElseThrow());
    assertFalse(messages.route(reply.path("message").asText()).orElseThrow().generated());
    assertEquals(
        2, fixture.jdbc.queryForObject("SELECT count(*) FROM message_routes", Integer.class));
  }

  @Test
  void progress_does_not_suppress_the_outcome_and_failure_keeps_its_status() throws Exception {
    JsonNode request = request(true);
    String original = request.path("message").asText();
    send(
        recipient(request),
        "{\"reply_to\":\"" + original + "\",\"body\":\"Working\",\"final\":false}",
        "progress");
    messages.complete(original, outcome(Outcome.Ending.TURN_CAP, "The run reached its cap."));
    String finalId = messages.finalReply(original).orElseThrow();
    MessageService.Route reply = messages.route(finalId).orElseThrow();
    assertTrue(reply.generated());
    assertEquals("TURN_CAP", reply.ending());
    assertEquals(
        "The run reached its cap.",
        new JdbcMessagingRepository(fixture.jdbc).message(finalId).orElseThrow().body());
  }

  @Test
  void one_way_messages_do_not_generate_replies_but_allow_voluntary_replies() throws Exception {
    JsonNode request = request(false);
    String original = request.path("message").asText();
    messages.complete(original, outcome(Outcome.Ending.ANSWERED, "Noted"));
    assertTrue(messages.finalReply(original).isEmpty());
    send(recipient(request), "{\"reply_to\":\"" + original + "\",\"body\":\"Thanks\"}", "thanks");
    assertTrue(messages.finalReply(original).isPresent());
  }

  @Test
  void a_message_wakes_an_existing_instance_and_charges_its_normal_allowance() throws Exception {
    JsonNode request = request(true);
    voice.busy = true;
    assertTrue(messages.busy(wake(request)));
    voice.busy = false;
    messages.start(wake(request), (conversation, outcome) -> {});
    Call call = voice.calls.getFirst();
    assertTrue(call.utterance().contains("Review"));
    assertTrue(
        messages.busy(wake(request)),
        "A lease stays busy until outcome settlement, even after the voice becomes free");
    assertTrue(call.lease().trySpend());
    call.ended().accept(outcome(Outcome.Ending.ANSWERED, "Reviewed"));
    assertTrue(messages.finalReply(request.path("message").asText()).isPresent());
    assertEquals(
        1,
        fixture.jdbc.queryForObject(
            "SELECT sum(a.spent) FROM execution_allowances a JOIN message_routes r ON r.allowance_id=a.id WHERE r.recipient=?",
            Integer.class,
            call.instance().id()));
  }

  @Test
  void bots_receive_messages_and_task_instances_are_distinct_from_the_default() throws Exception {
    JsonNode bot = send(caller, "{\"to\":\"bot\",\"body\":\"Hello\"}", "bot");
    assertEquals("bot", messages.instance(bot.path("to").asText()).orElseThrow().agent());
    JsonNode first =
        send(caller, "{\"to\":\"reviewer\",\"body\":\"One\",\"lifetime\":\"task\"}", "task1");
    JsonNode second =
        send(caller, "{\"to\":\"reviewer\",\"body\":\"Two\",\"lifetime\":\"task\"}", "task2");
    assertNotEquals(first.path("to"), second.path("to"));
    messages.complete(first.path("message").asText(), outcome(Outcome.Ending.ANSWERED, "Done"));
    assertFalse(messages.instance(first.path("to").asText()).orElseThrow().active());
    assertTrue(messages.instance(second.path("to").asText()).orElseThrow().active());
  }

  private RunApproval approval(JsonNode receipt, String askedIn) {
    var instance = messages.instance(receipt.path("to").asText()).orElseThrow();
    return new RunApproval(
        "apr_message",
        1,
        instance.conversation(),
        askedIn,
        "enzo",
        "reviewer",
        "server",
        List.of("echo", "ok"),
        ".",
        "question",
        "allowed",
        "once",
        null,
        "enzo",
        fixture.clock.get(),
        null,
        fixture.clock.get(),
        null,
        null);
  }

  @Test
  void awaiting_keeps_the_original_request_and_task_alive_until_approval_continuation_ends()
      throws Exception {
    JsonNode request =
        send(
            caller,
            "{\"to\":\"reviewer\",\"body\":\"Review\",\"reply_expected\":true,\"lifetime\":\"task\"}",
            "task");
    String original = request.path("message").asText();
    FiringRecord first = wake(request);
    messages.start(first, (conversation, outcome) -> {});
    voice.calls.getFirst().ended().accept(outcome(Outcome.Ending.AWAITING, "Please approve"));
    assertFalse(messages.route(original).orElseThrow().handled());
    assertTrue(messages.finalReply(original).isEmpty());
    assertTrue(messages.instance(request.path("to").asText()).orElseThrow().active());
    assertTrue(fixture.firings.claimStart(first.id(), fixture.clock.get()));
    fixture.firings.startedAs(first.id(), "job_initial");
    fixture.firings.finish(first.id(), fixture.clock.get());
    messages.recover();
    assertFalse(
        messages.route(original).orElseThrow().handled(),
        "A normal approval pause survives boot recovery");
    var approval = approval(request, recipient(request).conversationId());
    assertTrue(messages.continueApproved(approval, "Allowed, run it again."));
    assertTrue(messages.continueApproved(approval, "Allowed, run it again."));
    FiringRecord continued = fixture.firings.oldestWaiting(first.target()).orElseThrow();
    assertTrue(((EventPayload.Message) continued.data()).wake().approval() != null);
    messages.start(continued, (conversation, outcome) -> {});
    voice.calls.getLast().ended().accept(outcome(Outcome.Ending.ANSWERED, "Approved work done"));
    assertEquals(2, voice.calls.size());
    assertTrue(messages.route(original).orElseThrow().handled());
    assertFalse(messages.instance(request.path("to").asText()).orElseThrow().active());
    assertEquals(
        "Approved work done",
        new JdbcMessagingRepository(fixture.jdbc)
            .message(messages.finalReply(original).orElseThrow())
            .orElseThrow()
            .body());
  }

  @Test
  void approval_answers_racing_the_pause_are_durable_and_take_priority_over_unrelated_messages()
      throws Exception {
    JsonNode request = request(true);
    FiringRecord initial = wake(request);
    messages.start(initial, (conversation, outcome) -> {});
    JsonNode another = send(caller, "{\"to\":\"reviewer\",\"body\":\"Later request\"}", "later");
    assertTrue(
        messages.continueApproved(
            approval(request, recipient(request).conversationId()),
            "Denied. Continue without it."));
    voice.calls.getFirst().ended().accept(outcome(Outcome.Ending.AWAITING, "Question"));
    assertTrue(
        messages.busy(
            fixture
                .jdbc
                .query(
                    "SELECT id FROM firings WHERE data->>'direct_message' = ?",
                    (row, n) -> fixture.firings.find(row.getString(1)).orElseThrow(),
                    another.path("message").asText())
                .getFirst()));
    assertTrue(fixture.firings.claimStart(initial.id(), fixture.clock.get()));
    fixture.firings.startedAs(initial.id(), "job_initial");
    fixture.firings.finish(initial.id(), fixture.clock.get());
    FiringRecord continued = fixture.firings.oldestWaiting(initial.target()).orElseThrow();
    assertTrue(((EventPayload.Message) continued.data()).wake().approval() != null);
    assertFalse(messages.busy(continued));
    messages.start(continued, (conversation, outcome) -> {});
    voice.calls.getLast().ended().accept(outcome(Outcome.Ending.ANSWERED, "Done without command"));
    assertEquals(
        "ANSWERED",
        messages
            .route(messages.finalReply(request.path("message").asText()).orElseThrow())
            .orElseThrow()
            .ending());
  }

  @Test
  void an_approved_delegate_resumes_then_reports_to_its_original_message_handler()
      throws Exception {
    JsonNode request = request(true);
    String original = request.path("message").asText();
    FiringRecord initial = wake(request);
    messages.start(initial, (conversation, outcome) -> {});
    String root = recipient(request).conversationId();
    String child =
        fixture
            .conversations
            .log(Origin.DELEGATION, Home.of("payments"), "reviewer", root, null)
            .id();
    voice.calls.getFirst().ended().accept(outcome(Outcome.Ending.AWAITING, "Delegate approval"));
    fixture.firings.claimStart(initial.id(), fixture.clock.get());
    fixture.firings.startedAs(initial.id(), "job_initial");
    fixture.firings.finish(initial.id(), fixture.clock.get());
    assertTrue(messages.continueApproved(approval(request, child), "Approved"));
    FiringRecord continuation = fixture.firings.oldestWaiting(initial.target()).orElseThrow();
    messages.start(continuation, (conversation, outcome) -> {});
    assertEquals(List.of(child), voice.resumedChildren);
    voice.calls.getLast().ended().accept(outcome(Outcome.Ending.ANSWERED, "Delegate's findings"));
    assertTrue(
        messages.finalReply(original).isEmpty(), "The root agent owns the task's final outcome");
    fixture.firings.claimStart(continuation.id(), fixture.clock.get());
    fixture.firings.startedAs(continuation.id(), "job_delegate");
    fixture.firings.finish(continuation.id(), fixture.clock.get());
    FiringRecord report = fixture.firings.oldestWaiting(initial.target()).orElseThrow();
    assertTrue(
        ((EventPayload.Message) report.data()).wake().continuation()
            == MessageWake.Continuation.DELEGATE_RESULT);
    messages.start(report, (conversation, outcome) -> {});
    assertTrue(voice.calls.getLast().utterance().contains("Delegate's findings"));
    voice
        .calls
        .getLast()
        .ended()
        .accept(outcome(Outcome.Ending.ANSWERED, "Root's reviewed result"));
    assertEquals(
        "Root's reviewed result",
        messages.delivery(messages.finalReply(original).orElseThrow(), "enzo").body());
  }

  @Test
  void private_delegate_history_is_visible_only_to_its_own_message_participant() throws Exception {
    JsonNode request = request(false);
    String root = recipient(request).conversationId();
    String child =
        fixture
            .conversations
            .log(Origin.DELEGATION, Home.of("payments"), "reviewer", root, null)
            .id();
    assertFalse(messages.visible(child, caller.conversationId()));
    assertTrue(messages.visible(child, root));
    assertTrue(messages.visible(root, child));
    assertFalse(messages.visible(caller.conversationId(), child));
  }

  @Test
  void withheld_instance_rows_preserve_pagination_without_revealing_their_contents() {
    var first = messages.open("enzo", "payments", "reviewer", false, UUID.randomUUID().toString());
    var second = messages.open("enzo", "payments", "reviewer", false, UUID.randomUUID().toString());
    messages.useLogVisibility(
        (conversation, account) -> !conversation.equals(first.conversation()));
    var page = messages.listingPage("enzo", "payments", false, 0, 1);
    assertTrue(page.instances().isEmpty());
    assertTrue(page.more());
    assertEquals(
        second.id(),
        messages.listingPage("enzo", "payments", false, 1, 1).instances().getFirst().id());
    assertThrows(MessageRefused.class, () -> messages.inspect(first.id(), "enzo"));
  }

  @Test
  void instance_opening_defaults_and_archival_are_durable_and_account_scoped() throws Exception {
    String key = UUID.randomUUID().toString();
    var first = messages.open("enzo", "payments", "reviewer", true, key);
    assertEquals(first, messages.open("enzo", "payments", "reviewer", true, key));
    assertThrows(MessageRefused.class, () -> messages.open("enzo", "payments", "bot", true, key));
    var second = messages.open("enzo", "payments", "reviewer", false, UUID.randomUUID().toString());
    assertEquals(first.id(), request(false).path("to").asText());
    messages.makeDefault(second.id(), "enzo");
    assertEquals(
        second.id(),
        send(caller, "{\"to\":\"reviewer\",\"body\":\"New default\"}", "default")
            .path("to")
            .asText());
    assertFalse(messages.inspect(first.id(), "enzo").defaultInstance());
    assertThrows(MessageRefused.class, () -> messages.inspect(first.id(), "someone"));
    var archived = messages.stop(second.id(), "enzo", true);
    assertFalse(archived.active());
    assertTrue(archived.archived());
    assertFalse(archived.defaultInstance());
    assertEquals(archived, messages.stop(second.id(), "enzo", true));
    assertEquals(
        1,
        messages.listing("enzo", "payments", false, 0, 100).stream()
            .filter(i -> i.agent().equals("reviewer"))
            .count());
    assertEquals(
        2,
        messages.listing("enzo", "payments", true, 0, 100).stream()
            .filter(i -> i.agent().equals("reviewer"))
            .count());
    assertNotEquals(
        second.id(),
        send(caller, "{\"to\":\"reviewer\",\"body\":\"Replacement\"}", "replacement")
            .path("to")
            .asText());
  }

  @Test
  void cancellation_refuses_queued_work_and_emits_one_expected_terminal_reply() throws Exception {
    JsonNode request = request(true);
    String original = request.path("message").asText();
    var cancelled = messages.cancel(original, "enzo");
    assertEquals("cancelled", cancelled.state());
    assertEquals("CANCELLED", cancelled.ending());
    assertEquals(cancelled, messages.cancel(original, "enzo"));
    assertThrows(
        MessageRefused.class,
        () -> messages.start(wakeFrom(original), (conversation, outcome) -> {}));
    messages.complete(original, outcome(Outcome.Ending.ANSWERED, "Late answer"));
    assertEquals(cancelled.reply(), messages.finalReply(original).orElseThrow());
    assertEquals(0, voice.calls.size());
    assertTrue(
        fixture.firings.list(null, null, 0, 100).isEmpty(),
        "The global firing inspector cannot expose private messaging data");
  }

  private FiringRecord wakeFrom(String message) {
    MessageInboxFixture.pulse(fixture);
    return fixture
        .jdbc
        .query(
            "SELECT id FROM firings WHERE data->>'direct_message' = ? ORDER BY arrived_at LIMIT 1",
            (row, n) -> fixture.firings.find(row.getString(1)).orElseThrow(),
            message)
        .getFirst();
  }

  @Test
  void stopping_a_running_instance_cancels_its_exact_job_and_late_outcomes_cannot_revive_it()
      throws Exception {
    JsonNode request = request(true);
    String original = request.path("message").asText();
    FiringRecord wake = wake(request);
    assertTrue(fixture.firings.claimStart(wake.id(), fixture.clock.get()));
    String job = messages.start(wake, (conversation, outcome) -> {});
    fixture.firings.startedAs(wake.id(), job);
    List<String> cancelled = new CopyOnWriteArrayList<>();
    var cancellation = new CompletableFuture<String>();
    messages.useCancellation(
        id -> {
          cancelled.add(id);
          cancellation.complete(id);
        });
    var stopped = messages.stop(request.path("to").asText(), "enzo", false);
    assertFalse(stopped.active());
    assertEquals(job, cancellation.get(5, TimeUnit.SECONDS));
    assertEquals(List.of(job), cancelled);
    voice.calls.getFirst().ended().accept(outcome(Outcome.Ending.ANSWERED, "Late result"));
    assertEquals("stopped", messages.delivery(original, "enzo").state());
    fixture.firings.finish(wake.id(), fixture.clock.get());
    var historical = messages.delivery(original, "enzo");
    assertEquals(job, historical.job());
    assertEquals(recipient(request).conversationId(), historical.conversation());
    assertEquals(
        messages.instance(historical.sender()).orElseThrow().conversation(),
        historical.sourceConversation());
    assertEquals("stopped", historical.state());
    assertEquals(
        1,
        fixture.jdbc.queryForObject(
            "SELECT count(*) FROM message_routes WHERE reply_to = ? AND final",
            Integer.class,
            original));
    assertTrue(
        messages.continueApproved(
            approval(request, recipient(request).conversationId()), "A late approval"));
    assertEquals(1, voice.calls.size());
  }

  @Test
  void deadlines_include_approval_waits_and_cannot_restart_expired_work() throws Exception {
    JsonNode request =
        send(
            caller,
            "{\"to\":\"reviewer\",\"body\":\"Review\",\"reply_expected\":true,\"timeout_seconds\":60}",
            "timed");
    String original = request.path("message").asText();
    messages.start(wake(request), (conversation, outcome) -> {});
    voice.calls.getFirst().ended().accept(outcome(Outcome.Ending.AWAITING, "Question"));
    assertNotNull(messages.delivery(original, "enzo").deadlineAt());
    fixture.jdbc.update(
        "UPDATE message_routes SET deadline_at = ? WHERE message = ?",
        Timestamp.from(fixture.clock.get().minusSeconds(1)),
        original);
    messages.expire();
    messages.expire();
    assertEquals("expired", messages.delivery(original, "enzo").state());
    assertEquals("CANCELLED", messages.delivery(original, "enzo").ending());
    assertEquals(
        1,
        fixture.jdbc.queryForObject(
            "SELECT count(*) FROM message_routes WHERE reply_to = ? AND final",
            Integer.class,
            original));
  }

  @Test
  void transport_limits_fail_without_partial_instances_messages_or_budget_grants()
      throws Exception {
    MessagingProperties limits = new MessagingProperties();
    limits.setQueueLimit(1);
    limits.setBodyBytes(10);
    messages.useLimits(limits);
    JsonNode request = request(false);
    int potBefore =
        fixture.jdbc.queryForObject(
            "SELECT coalesce(sum(call_limit),0) FROM execution_allowances", Integer.class);
    String refused =
        messages
            .tool(caller)
            .run("{\"to\":\"reviewer\",\"body\":\"Another\"}", Home.of("payments"));
    assertTrue(refused.contains("queue is full"), refused);
    assertEquals(
        potBefore,
        fixture.jdbc.queryForObject(
            "SELECT coalesce(sum(call_limit),0) FROM execution_allowances", Integer.class));
    int instances =
        fixture.jdbc.queryForObject("SELECT count(*) FROM message_instances", Integer.class);
    refused =
        messages
            .tool(caller)
            .run(
                "{\"to\":\"bot\",\"body\":\"A message beyond the configured limit\"}",
                Home.of("payments"));
    assertTrue(refused.contains("size limit"), refused);
    assertEquals(
        instances,
        fixture.jdbc.queryForObject("SELECT count(*) FROM message_instances", Integer.class));
    assertEquals(
        1, fixture.jdbc.queryForObject("SELECT count(*) FROM message_routes", Integer.class));
  }

  @Test
  void an_unrelated_instance_cannot_reply_or_read_a_private_trajectory() throws Exception {
    JsonNode request = request(true);
    JsonNode other = send(caller, "{\"to\":\"bot\",\"body\":\"Hello\"}", "other");
    String refusal =
        messages
            .tool(recipient(other))
            .run(
                "{\"reply_to\":\"" + request.path("message").asText() + "\",\"body\":\"Forged\"}",
                Home.of("payments"));
    assertTrue(refusal.contains("Only the recipient"), refusal);
    AgentTool read =
        new AgentTool() {
          public ToolSchema schema() {
            return ToolSchema.from("conversation_trajectory", "test", Map.of());
          }

          public String run(String arguments, Home home) {
            fail("Private read reached the underlying tool");
            return "";
          }
        };
    String hidden =
        messages
            .protect(read, agents.get("bot"), recipient(other).conversationId())
            .run(
                "{\"conversation\":\"" + recipient(request).conversationId() + "\"}",
                Home.of("payments"));
    assertTrue(hidden.contains("not visible"));
  }

  @Test
  void restart_reports_an_interrupted_turn_once_without_executing_it_again() throws Exception {
    JsonNode request = request(true);
    FiringRecord wake = wake(request);
    assertTrue(fixture.firings.claimStart(wake.id(), fixture.clock.get()));
    fixture.firings.abandonUnfinished("the server restarted during this run", fixture.clock.get());
    messages.recover();
    messages.recover();
    assertEquals(0, voice.calls.size());
    assertEquals(
        "UNAVAILABLE",
        messages
            .route(messages.finalReply(request.path("message").asText()).orElseThrow())
            .orElseThrow()
            .ending());
    assertEquals(
        2, fixture.jdbc.queryForObject("SELECT count(*) FROM message_routes", Integer.class));
  }

  @Test
  void restart_recovers_an_answer_committed_before_its_continuation_was_queued_once()
      throws Exception {
    JsonNode request = request(true);
    FiringRecord first = wake(request);
    messages.start(first, (conversation, outcome) -> {});
    voice.calls.getFirst().ended().accept(outcome(Outcome.Ending.AWAITING, "Please approve"));
    assertTrue(fixture.firings.claimStart(first.id(), fixture.clock.get()));
    fixture.firings.startedAs(first.id(), "job_initial");
    fixture.firings.finish(first.id(), fixture.clock.get());
    var receiver = messages.instance(request.path("to").asText()).orElseThrow();
    var approvals = new RunApprovalStore(fixture.jdbc, () -> Instant.now().plusSeconds(1));
    messages.useApprovals(approvals);
    long project =
        fixture.jdbc.queryForObject(
            "SELECT project_id FROM conversations WHERE id = ?",
            Long.class,
            receiver.conversation());
    var asked =
        approvals.ask(
            project,
            receiver.conversation(),
            receiver.conversation(),
            "enzo",
            "reviewer",
            "server",
            List.of("echo", "ok"),
            ".",
            "approval");
    assertTrue(approvals.allow(asked.id(), "once", null, "enzo"));
    messages.recover();
    messages.recover();
    assertEquals(
        1,
        fixture.jdbc.queryForObject(
            "SELECT count(*) FROM firings WHERE data->>'message_approval' = ?",
            Integer.class,
            asked.id()));
    assertEquals(
        1,
        voice.calls.size(),
        "Recovery only queues the continuation; it does not replay inference");
    var continuation = fixture.firings.oldestWaiting(first.target()).orElseThrow();
    messages.start(continuation, (conversation, outcome) -> {});
    voice
        .calls
        .getLast()
        .ended()
        .accept(outcome(Outcome.Ending.ANSWERED, "Approved work finished"));
    assertTrue(messages.route(request.path("message").asText()).orElseThrow().handled());
    assertTrue(messages.finalReply(request.path("message").asText()).isPresent());
  }

  @Test
  void restart_finishes_a_completed_firing_whose_route_settlement_was_lost_without_replaying()
      throws Exception {
    JsonNode request = request(true);
    FiringRecord first = wake(request);
    messages.start(first, (conversation, outcome) -> {});
    assertTrue(fixture.firings.claimStart(first.id(), fixture.clock.get()));
    fixture.firings.startedAs(first.id(), "job_initial");
    fixture.firings.finish(first.id(), fixture.clock.get());
    messages.recover();
    messages.recover();
    assertEquals(1, voice.calls.size());
    assertEquals(
        "UNAVAILABLE", messages.route(request.path("message").asText()).orElseThrow().ending());
    assertEquals(
        2, fixture.jdbc.queryForObject("SELECT count(*) FROM message_routes", Integer.class));
  }

  @Test
  void cancelling_an_unreadable_delivery_is_refused_before_changing_its_state() throws Exception {
    JsonNode request = request(true);
    messages.useLogVisibility(
        (conversation, account) -> !conversation.equals(recipient(request).conversationId()));
    assertThrows(
        MessageRefused.class, () -> messages.cancel(request.path("message").asText(), "enzo"));
    assertFalse(messages.route(request.path("message").asText()).orElseThrow().handled());
    assertTrue(messages.finalReply(request.path("message").asText()).isEmpty());
  }

  @Test
  void persistent_instances_can_handle_later_messages_after_spending_a_full_turn_allowance()
      throws Exception {
    JsonNode first = request(false);
    messages.start(wake(first), (conversation, outcome) -> {});
    Call call = voice.calls.getFirst();
    for (int i = 0; i < 8; i++) assertTrue(call.lease().trySpend());
    assertFalse(call.lease().trySpend());
    call.ended().accept(outcome(Outcome.Ending.CALL_BUDGET, "Spent the turn allowance"));
    JsonNode next = send(caller, "{\"to\":\"reviewer\",\"body\":\"Next task\"}", "next");
    assertEquals(first.path("to"), next.path("to"));
    // A fake voice does not settle its firing; select the second message's firing directly.
    FiringRecord nextWake =
        fixture
            .firings
            .find(
                fixture.jdbc.queryForObject(
                    "SELECT id FROM firings WHERE data->>'direct_message' = ?",
                    String.class,
                    next.path("message").asText()))
            .orElseThrow();
    messages.start(nextWake, (conversation, outcome) -> {});
    Call second = voice.calls.getLast();
    for (int i = 0; i < 8; i++) assertTrue(second.lease().trySpend());
    assertFalse(second.lease().trySpend());
    second.ended().accept(outcome(Outcome.Ending.ANSWERED, "Done"));
    assertEquals(
        16,
        fixture.jdbc.queryForObject(
            "SELECT sum(a.spent) FROM execution_allowances a JOIN message_routes r ON r.allowance_id=a.id WHERE r.recipient=?",
            Integer.class,
            call.instance().id()));
  }

  @Test
  void account_and_project_scope_cannot_be_crossed_by_an_address_or_a_forged_context()
      throws Exception {
    JsonNode receipt = request(false);
    fixture.jdbc.update("INSERT INTO admins(handle,password_hash) VALUES ('other','h')");
    String foreign =
        fixture.conversations.open(Home.of("payments"), Budget.of(20), TurnCap.of(4), "other").id();
    var other =
        new RunExtras.Context(
            agents.get("sender"), foreign, null, Transcript.NONE, Home.of("payments"), "other");
    String denied =
        messages
            .tool(other)
            .run(
                "{\"to\":\"" + receipt.path("to").asText() + "\",\"body\":\"Hello\"}",
                other.home());
    assertTrue(denied.contains("No accessible instance"), denied);
    String wrongProject =
        fixture.conversations.open(Home.of("another"), Budget.of(20), TurnCap.of(4), "enzo").id();
    var elsewhere =
        new RunExtras.Context(
            agents.get("sender"), wrongProject, null, Transcript.NONE, Home.of("another"), "enzo");
    denied =
        messages
            .tool(elsewhere)
            .run(
                "{\"to\":\"" + receipt.path("to").asText() + "\",\"body\":\"Hello\"}",
                elsewhere.home());
    assertTrue(denied.contains("No accessible instance"), denied);
    denied =
        messages
            .tool(context("sender", foreign))
            .run("{\"to\":\"reviewer\",\"body\":\"Hello\"}", Home.of("payments"));
    assertTrue(denied.contains("not in the sending project"), denied);
    assertEquals(
        1, fixture.jdbc.queryForObject("SELECT count(*) FROM message_routes", Integer.class));
  }

  @Test
  void committed_send_admits_immediately_but_never_before_the_outer_transaction_commits()
      throws Exception {
    var hinted = new CountDownLatch(1);
    var drained = new CountDownLatch(1);
    var hint = new AtomicReference<MessageInboxRepository.Inbox>();
    var transport =
        MessageInboxFixture.inboxes(
            fixture,
            target -> drained.countDown(),
            fixture.firings,
            inbox -> {
              hint.set(inbox);
              hinted.countDown();
            });
    var engine =
        new RelayMessaging(
            new JdbcMessagingRepository(fixture.jdbc),
            new JdbcAllowanceRepository(fixture.jdbc),
            fixture.conversations,
            fixture.firings,
            transport,
            fixture.work,
            voice,
            (name, project) -> agents.get(name),
            fixture.drained::add,
            fixture.clock);
    fixture.work.inTransaction(
        () -> {
          engine.tool(caller).run("{\"to\":\"reviewer\",\"body\":\"Hello\"}", caller.home());
          assertEquals(1, hinted.getCount(), "The hint must wait for the owning send commit");
          assertEquals(
              0, fixture.jdbc.queryForObject("SELECT count(*) FROM firings", Integer.class));
          assertEquals(0, voice.calls.size());
          return null;
        });
    assertTrue(hinted.await(2, TimeUnit.SECONDS), "A committed send must signal without discovery");
    // Execute the queued hint as the production worker does, without running a periodic scan.
    assertEquals(1, transport.poll(hint.get()));
    assertEquals(1, fixture.jdbc.queryForObject("SELECT count(*) FROM firings", Integer.class));
    assertTrue(drained.await(2, TimeUnit.SECONDS));
    assertEquals(
        0,
        voice.calls.size(),
        "Admission queues native work rather than directly invoking inference");
  }

  @Test
  void rolled_back_sends_leave_no_message_wake_instance_or_allowance() {
    assertThrows(
        IllegalStateException.class,
        () ->
            fixture.work.inTransaction(
                () -> {
                  messages
                      .tool(caller)
                      .run("{\"to\":\"reviewer\",\"body\":\"Hello\"}", caller.home());
                  throw new IllegalStateException("rollback");
                }));
    assertEquals(
        0, fixture.jdbc.queryForObject("SELECT count(*) FROM message_routes", Integer.class));
    assertEquals(
        0, fixture.jdbc.queryForObject("SELECT count(*) FROM message_instances", Integer.class));
    assertEquals(
        0, fixture.jdbc.queryForObject("SELECT count(*) FROM board_topics", Integer.class));
    assertEquals(0, firingCount());
    assertTrue(fixture.drained.isEmpty());
  }

  @Test
  void concurrent_completion_and_explicit_reply_produce_one_final_message_and_one_wake()
      throws Exception {
    JsonNode receipt = request(true);
    String original = receipt.path("message").asText();
    var start = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var explicit =
          workers.submit(
              () -> {
                start.await();
                return messages
                    .tool(recipient(receipt))
                    .run(
                        "{\"reply_to\":\"" + original + "\",\"body\":\"Explicit\"}",
                        Home.of("payments"));
              });
      var fallback =
          workers.submit(
              () -> {
                start.await();
                messages.complete(original, outcome(Outcome.Ending.ANSWERED, "Outcome"));
                return true;
              });
      start.countDown();
      String result = explicit.get(10, TimeUnit.SECONDS);
      assertTrue(result.startsWith("{") || result.contains("already has a final reply"), result);
      assertTrue(fallback.get(10, TimeUnit.SECONDS));
    }
    assertTrue(messages.finalReply(original).isPresent());
    assertEquals(
        2, fixture.jdbc.queryForObject("SELECT count(*) FROM message_routes", Integer.class));
    assertEquals(2, firingCount());
  }

  @Test
  void search_and_list_projection_remove_other_instances_without_exposing_their_snippets()
      throws Exception {
    JsonNode receipt = request(false);
    String hidden = recipient(receipt).conversationId();
    for (String name : List.of("conversation_search", "conversation_list")) {
      AgentTool underlying =
          new AgentTool() {
            public ToolSchema schema() {
              return ToolSchema.from(name, "test", Map.of());
            }

            public String run(String arguments, Home home) {
              if (name.equals("conversation_list"))
                return "[{\"id\":\"" + hidden + "\"},{\"id\":\"" + caller.conversationId() + "\"}]";
              return "{\"hits\":[{\"conversationId\":\""
                  + hidden
                  + "\",\"excerpt\":\"secret\"},"
                  + "{\"conversationId\":\""
                  + caller.conversationId()
                  + "\",\"excerpt\":\"mine\"}],\"retrieval\":{\"complete\":true}}";
            }
          };
      String result =
          messages
              .protect(underlying, agents.get("sender"), caller.conversationId())
              .run("{}", caller.home());
      assertFalse(result.contains(hidden), result);
      assertFalse(result.contains("secret"), result);
      assertTrue(result.contains(caller.conversationId()), result);
      if (name.equals("conversation_search"))
        assertFalse(JSON.readTree(result).path("retrieval").path("complete").asBoolean());
    }
  }

  @Test
  void unavailable_inputs_withhold_outcomes_and_do_not_break_or_replay_recovery() throws Exception {
    JsonNode receipt = request(true);
    String original = receipt.path("message").asText();
    messages.useLineage(
        (sender, recipient) -> {
          throw new NotFoundFault("log inputs are unavailable to this account");
        });
    messages.complete(original, outcome(Outcome.Ending.ANSWERED, "Restricted result"));
    messages.recover();
    assertTrue(messages.route(original).orElseThrow().handled());
    assertEquals("UNAVAILABLE", messages.route(original).orElseThrow().ending());
    assertTrue(messages.finalReply(original).isEmpty());
    assertEquals(
        1, fixture.jdbc.queryForObject("SELECT count(*) FROM message_routes", Integer.class));
    assertEquals(1, firingCount());
    assertEquals(0, voice.calls.size());
  }

  @Test
  void a_delegated_child_inherits_the_message_instances_scheduling_share() throws Exception {
    JsonNode receipt = request(false);
    var parent = recipient(receipt);
    String child =
        fixture
            .conversations
            .log(Origin.DELEGATION, parent.home(), "sender", parent.conversationId(), null, "enzo")
            .id();
    assertEquals(messages.share(parent), messages.share(context("sender", child)));
  }

  @Test
  void a_message_instance_keeps_public_board_capability_without_exposing_its_transport_seat()
      throws Exception {
    Path file = definitions.resolve("reviewer.md");
    Files.writeString(
        file, Files.readString(file).replace("max-turns:", "board: true\nmax-turns:"));
    agents = AgentRegistry.of(definitions, Set.of("send_message"));
    JsonNode receipt = request(false);
    var extras =
        new BoardRunExtras(fixture.store, fixture.board(BoardFixture.TWO), fixture.conversations);
    List<String> names =
        extras.forRun(recipient(receipt)).tools().stream().map(t -> t.schema().name()).toList();
    assertEquals(List.of("board_open", "board_swarm_types"), names);
    assertFalse(names.contains("board_read"));
    assertFalse(names.contains("board_post"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"local", "server", "personal", "retained", "conversation"})
  void the_real_dispatcher_and_turn_loop_deliver_an_explicit_reply_and_wake_the_caller(
      String routeKind) throws Exception {
    boolean crossProject = !routeKind.equals("local");
    boolean personalRoute = routeKind.equals("personal");
    boolean pinnedRoute = routeKind.equals("retained") || routeKind.equals("conversation");
    var calls = new AtomicInteger();
    var sawPriorHistory = new AtomicBoolean();
    var transport =
        new LlmTransport() {
          public String poolName() {
            return "test";
          }

          public Completion complete(
              String model, List<ChatMessage> history, Sampling sampling, List<ToolSchema> tools) {
            calls.incrementAndGet();
            var last = history.getLast();
            if (last.content().contains("\"body\":\"Review\"")) {
              if (calls.get() > 3)
                sawPriorHistory.set(
                    history.stream().anyMatch(row -> "Handled".equals(row.content())));
              assertTrue(tools.stream().anyMatch(t -> t.name().equals("send_message")));
              try {
                var incoming =
                    JSON.readTree(last.content().substring(last.content().indexOf('\n') + 1));
                String args =
                    JSON.writeValueAsString(
                        Map.of(
                            "reply_to",
                            incoming.path("id").asText(),
                            "body",
                            "Reviewed explicitly",
                            "final",
                            true));
                return new Completion(
                    "",
                    "tool_calls",
                    TokenUsage.UNKNOWN,
                    List.of(new ToolCall("reply-1", "send_message", args)));
              } catch (Exception failure) {
                throw new IllegalStateException(failure);
              }
            }
            return new Completion("Handled", "stop", TokenUsage.UNKNOWN, List.of());
          }

          public Completion stream(
              String model,
              List<ChatMessage> history,
              Sampling sampling,
              List<ToolSchema> tools,
              Deltas sink,
              BooleanSupplier cancelled) {
            return complete(model, history, sampling, tools);
          }

          public Embeddings embed(String model, List<String> input) {
            throw new UnsupportedOperationException();
          }

          public void close() {}
        };
    var pool =
        new LlmPool(
            "test",
            List.of("wire"),
            Map.of("test", "wire"),
            2,
            1,
            Duration.ofSeconds(5),
            transport,
            Set.of());
    var llm = new LlmDispatcher(List.of(pool), new NoOpTokenLedger());
    var runtime = new JobRuntime(llm, List.of());
    JobStore jobs = new JobStore(runtime);
    try {
      var turns = new TurnStore(fixture.jdbc);
      var entries = new EntryStore(fixture.jdbc);
      var compaction =
          new Compaction(
              llm,
              () -> agents.get("reviewer"),
              turns,
              new CompactionStore(fixture.jdbc),
              entries,
              1_000_000);
      var turn = new Turn(jobs, fixture.conversations, turns, compaction);
      var dispatcher =
          new Dispatcher(
              fixture.firings,
              new JdbcTriggerStore(fixture.jdbc),
              new Dispatcher.Runner() {
                public boolean busy(TriggerRecord trigger) {
                  return false;
                }

                public String start(
                    TriggerRecord trigger, String utterance, BiConsumer<String, Outcome> ended) {
                  throw new AssertionError("No trigger expected");
                }
              },
              new Inbox(new JdbcInboxStore(fixture.jdbc), AccountPushes.NONE, fixture.clock),
              fixture.clock);
      fixture.messageInboxes.clear();
      messages =
          new RelayMessaging(
              new JdbcMessagingRepository(fixture.jdbc),
              new JdbcAllowanceRepository(fixture.jdbc),
              fixture.conversations,
              fixture.firings,
              MessageInboxFixture.inboxes(fixture, dispatcher::drain),
              fixture.work,
              new MessageService.Voice() {
                @Override
                public String speakFrom(
                    MessageService.Instance instance,
                    AgentDefinition definition,
                    String utterance,
                    Budget lease,
                    TurnCap cap,
                    Consumer<Outcome> ended,
                    Speaker source,
                    boolean command) {
                  return turn.speakToMessage(
                      instance.conversation(),
                      definition,
                      utterance,
                      lease,
                      cap,
                      source,
                      ended,
                      command);
                }

                public boolean busy(String conversation) {
                  return turn.isSpeaking(conversation);
                }

                public String speak(
                    MessageService.Instance instance,
                    AgentDefinition definition,
                    String utterance,
                    Budget lease,
                    TurnCap cap,
                    Consumer<Outcome> ended) {
                  return turn.speakToMessage(
                      instance.conversation(),
                      definition,
                      utterance,
                      lease,
                      cap,
                      Speaker.harness(),
                      ended);
                }
              },
              (name, project) -> agents.get(name),
              dispatcher::drain,
              fixture.clock);
      runtime.useMessaging(messages);
      var runHomes = new CopyOnWriteArrayList<Home>();
      runtime.useRunExtras(
          c -> {
            runHomes.add(c.home());
            return new RunExtras.Extras(List.of(messages.tool(c)), c.end(), false);
          });
      dispatcher.useWakes(messages);
      turn.whenFree(
          c -> {
            dispatcher.drain("conversation:" + c);
            messages.whenFree(c);
          });
      if (crossProject) {
        if (personalRoute) {
          personalRouting();
          routingManifest("payments", Map.of("routeFiles", List.of("routes.json")));
        } else {
          routingManifest(
              "payments",
              Map.of("sendTo", List.of("notifications"), "routeFiles", List.of("routes.json")));
          routingManifest("notifications", Map.of("acceptFrom", List.of("payments")));
        }
        var configured =
            new LinkedHashMap<String, Object>(
                Map.of(
                    "name",
                    "review",
                    "project",
                    personalRoute ? "Personal:enzo" : "notifications",
                    "agent",
                    "reviewer"));
        if (routeKind.equals("retained")) configured.put("retainConversation", true);
        if (routeKind.equals("conversation"))
          configured.put(
              "conversation",
              fixture
                  .conversations
                  .open(Home.of("notifications"), Budget.of(20), TurnCap.of(4), "enzo")
                  .id());
        Files.writeString(
            routingWorkspace("payments").resolve("routes.json"),
            JSON.writeValueAsString(Map.of("version", 1, "routes", List.of(configured))));
      }
      JsonNode receipt =
          crossProject
              ? send(
                  caller,
                  "{\"route\":\"review\",\"body\":\"Review\",\"reply_expected\":true}",
                  "request")
              : request(true);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      while (fixture.jdbc.queryForObject(
              "SELECT (SELECT count(*) FROM firings WHERE status = 'queued' OR (status = 'started' AND finished_at IS NULL)) + (SELECT count(*) FROM message_routes WHERE runnable AND handled_at IS NULL)",
              Integer.class)
          != 0) {
        assertTrue(System.nanoTime() < deadline, "Message wakes did not finish");
        MessageInboxFixture.pulse(fixture);
        Thread.sleep(20);
      }
      var reply =
          messages
              .route(messages.finalReply(receipt.path("message").asText()).orElseThrow())
              .orElseThrow();
      assertFalse(
          reply.generated(),
          () ->
              "Calls: "
                  + calls.get()
                  + "; reply: "
                  + new JdbcMessagingRepository(fixture.jdbc)
                      .message(reply.message())
                      .orElseThrow()
                      .body()
                  + "; entries: "
                  + fixture.jdbc.queryForList(
                      "SELECT kind,content FROM entries ORDER BY recorded_at"));
      assertEquals(
          "Reviewed explicitly",
          new JdbcMessagingRepository(fixture.jdbc).message(reply.message()).orElseThrow().body());
      assertEquals(
          3, calls.get(), "Reviewer makes a tool call and ends; the original sender wakes once");
      assertEquals(2, firingCount());
      assertEquals(1, turns.forConversation(caller.conversationId()).size());
      assertTrue(
          runHomes.contains(
              Home.of(
                  personalRoute
                      ? PersonalSpaces.name("enzo")
                      : crossProject ? "notifications" : "payments")));
      assertEquals(
          Home.of("payments"),
          runHomes.getLast(),
          "The return wake retains the sender's own project");
      var delivery = messages.delivery(receipt.path("message").asText(), "enzo");
      var trajectory = entries.pageOfLog(delivery.conversation(), 0, 100).listed();
      var incoming =
          trajectory.stream()
              .filter(row -> row.kind() == EntryKind.UTTERANCE)
              .findFirst()
              .orElseThrow();
      assertEquals(Speaker.message(delivery.message()), incoming.speaker());
      assertEquals(delivery.job(), incoming.job());
      assertNotNull(delivery.job());
      assertTrue(trajectory.stream().allMatch(row -> delivery.job().equals(row.job())));
      if (pinnedRoute) {
        JsonNode later =
            send(
                caller,
                "{\"route\":\"review\",\"body\":\"Review\",\"reply_expected\":true}",
                "request-later");
        assertEquals(receipt.path("to_conversation"), later.path("to_conversation"));
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (fixture.jdbc.queryForObject(
                "SELECT (SELECT count(*) FROM firings WHERE status = 'queued' OR (status = 'started' AND finished_at IS NULL)) + (SELECT count(*) FROM message_routes WHERE runnable AND handled_at IS NULL)",
                Integer.class)
            != 0) {
          assertTrue(System.nanoTime() < deadline, "Later retained message wakes did not finish");
          MessageInboxFixture.pulse(fixture);
          Thread.sleep(20);
        }
        assertFalse(
            messages
                .route(messages.finalReply(later.path("message").asText()).orElseThrow())
                .orElseThrow()
                .generated());
        assertEquals(2, turns.forConversation(later.path("to_conversation").asText()).size());
        assertTrue(
            sawPriorHistory.get(),
            "Later messages must see the retained conversation's previous handling history");
        assertEquals(6, calls.get());
      }
    } finally {
      jobs.close();
    }
  }

  @Test
  void scheduledMessagingReusesRoutesAndDeduplicatesTheSameFiring() {
    var work =
        new ScheduledWork(
            1,
            "0 0 9 * * *",
            "UTC",
            false,
            new ScheduledWork.Action("skill", "reviewer", "review", "Exact arguments", null),
            new ScheduledWork.Target("message", null, null, "reviewer", null),
            null);
    var first = messages.receiveScheduled("enzo", "payments", "schedule-one", "firing-one", work);
    var repeated =
        messages.receiveScheduled("enzo", "payments", "schedule-one", "firing-one", work);
    var next = messages.receiveScheduled("enzo", "payments", "schedule-one", "firing-two", work);
    assertEquals(first.id(), repeated.id());
    assertNotEquals(first.id(), next.id());
    assertEquals(first.context(), next.context());
  }
}
