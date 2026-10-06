package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.events.FiringRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
  private BoardMessaging messages;
  private AgentRegistry agents;
  private RunExtras.Context caller;
  private FakeVoice voice;

  record Call(
      BoardMessaging.Instance instance, String utterance, Budget lease, Consumer<Outcome> ended) {}

  static final class FakeVoice implements BoardMessaging.Voice {
    boolean busy;
    final List<Call> calls = new ArrayList<>();
    final List<String> commands = new ArrayList<>();

    public String speakCommand(
        BoardMessaging.Instance instance,
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
        BoardMessaging.Instance instance,
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
        BoardMessaging.Instance instance,
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
        new BoardMessaging(
            new JdbcBoardMessagingRepository(fixture.jdbc),
            fixture.store,
            fixture.conversations,
            fixture.firings,
            new BoardPot(fixture.store),
            fixture.work,
            voice,
            (name, project) -> agents.get(name),
            fixture.drained::add,
            fixture.clock);
    String conversation =
        fixture.conversations.open(Home.of("payments"), Budget.of(20), TurnCap.of(4), "enzo").id();
    caller = context("sender", conversation);
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

  private FiringRecord wake(JsonNode receipt) {
    var recipient = messages.instance(receipt.path("to").asText()).orElseThrow();
    return fixture.firings.oldestWaiting("conversation:" + recipient.conversation()).orElseThrow();
  }

  private Outcome outcome(Outcome.Ending ending, String text) {
    return new Outcome(ending, text, 1, 1, "");
  }

  private io.aeyer.plowshare.protocol.Incoming.Receive incoming(
      java.util.UUID request, java.util.UUID context, String body, String command) {
    return new io.aeyer.plowshare.protocol.Incoming.Receive(
        "payments",
        "remote",
        "reviewer",
        request,
        context,
        body,
        command,
        new io.aeyer.plowshare.protocol.Incoming.Source(
            request.toString(),
            "ROLE_USER",
            null,
            context,
            List.of(new io.aeyer.plowshare.protocol.Incoming.TextPart(body)),
            command == null ? null : new io.aeyer.plowshare.protocol.Incoming.Metadata(command),
            null,
            null));
  }

  private FiringRecord incomingWake(String message) {
    return fixture
        .firings
        .find(
            fixture.jdbc.queryForObject(
                "SELECT id FROM firings WHERE data->>'direct_message'=?", String.class, message))
        .orElseThrow();
  }

  @Test
  void incoming_receipts_are_idempotent_and_owned_by_account_project_client() {
    var request = incoming(java.util.UUID.randomUUID(), null, "Review", null);
    var first = messages.receive("enzo", request);
    assertEquals(first, messages.receive("enzo", request));
    assertEquals("SUBMITTED", first.state());
    assertThrows(
        Board.Refused.class,
        () -> messages.receive("enzo", incoming(request.requestId(), null, "Different", null)));
    assertThrows(
        Board.Refused.class, () -> messages.externalTask("enzo", "payments", "other", first.id()));
    assertThrows(
        Board.Refused.class,
        () -> messages.externalTask("enzo", "elsewhere", "remote", first.id()));
    assertThrows(
        Board.Refused.class,
        () -> messages.externalTask("other", "payments", "remote", first.id()));
    var next =
        messages.receive(
            "enzo", incoming(java.util.UUID.randomUUID(), first.context(), "Next", null));
    assertEquals(first.context(), next.context());
    assertEquals(
        2,
        fixture.jdbc.queryForObject("SELECT count(*) FROM board_message_instances", Integer.class));
    assertEquals(2, fixture.jdbc.queryForObject("SELECT count(*) FROM firings", Integer.class));
  }

  @Test
  void external_reply_is_durable_without_a_model_wake_and_retains_failure_ending()
      throws Exception {
    var accepted =
        messages.receive("enzo", incoming(java.util.UUID.randomUUID(), null, "Review", null));
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
    assertEquals(
        1,
        fixture.jdbc.queryForObject("SELECT count(*) FROM firings", Integer.class),
        "Passive return address never runs a model");
    assertThrows(
        Board.Refused.class,
        () -> messages.cancelExternal("enzo", "payments", "remote", accepted.id()));
  }

  @Test
  void cancellation_fences_late_completion_and_approval_does_not_replace_the_receipt() {
    var accepted =
        messages.receive("enzo", incoming(java.util.UUID.randomUUID(), null, "Review", null));
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
                incoming(
                    java.util.UUID.fromString(accepted.source().messageId()), null, "Review", null))
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
        messages.receive(
            "enzo", incoming(java.util.UUID.randomUUID(), null, "/skill:review Do it", null));
    messages.start(incomingWake(plain.message()), (conversation, outcome) -> {});
    assertTrue(voice.commands.isEmpty());
    assertTrue(voice.calls.getFirst().utterance().startsWith("Incoming message"));
    var bound =
        messages.receive(
            "enzo", incoming(java.util.UUID.randomUUID(), null, "Do it", "/skill:review"));
    messages.start(incomingWake(bound.message()), (conversation, outcome) -> {});
    assertEquals(List.of("/skill:review Do it"), voice.commands);
    messages.useLogVisibility((conversation, account) -> false);
    assertThrows(
        Board.Refused.class, () -> messages.externalTask("enzo", "payments", "remote", bound.id()));
    assertThrows(
        Board.Refused.class,
        () -> messages.cancelExternal("enzo", "payments", "remote", bound.id()));
  }

  private io.aeyer.plowshare.server.archive.ProjectStore routeProjects;

  private Path routingWorkspace(String project) throws Exception {
    Path root = Files.createDirectories(definitions.resolve("workspaces").resolve(project));
    if (routeProjects == null) {
      routeProjects =
          new io.aeyer.plowshare.server.archive.ProjectStore(
              fixture.jdbc,
              definitions.resolve("config"),
              definitions.resolve("sampling"),
              null,
              null,
              null);
      messages.useRouting(
          new ProjectMessageRouting(
              routeProjects,
              new io.aeyer.plowshare.server.archive.JdbcProjectMembers(fixture.jdbc)));
    }
    routeProjects.define(project, root, List.of(), "enzo");
    return root;
  }

  private void routingManifest(String project, java.util.Map<String, Object> policy)
      throws Exception {
    Files.writeString(
        routingWorkspace(project).resolve("plowshare"),
        JSON.writeValueAsString(
            java.util.Map.of(
                "version",
                1,
                "name",
                project,
                "routing",
                policy,
                "integration",
                java.util.Map.of("enabled", true))));
  }

  private io.aeyer.plowshare.server.personal.PersonalSpaces personalSpaces;
  private MessagingProperties personalSettings;

  private void personalRouting() throws Exception {
    routingWorkspace("payments");
    var data =
        new io.aeyer.plowshare.server.data.DataLayout(definitions.resolve("personal-data"))
            .initialise();
    personalSpaces =
        new io.aeyer.plowshare.server.personal.PersonalSpaces(
            new io.aeyer.plowshare.server.personal.JdbcPersonalSpaceRepository(fixture.jdbc),
            new io.aeyer.plowshare.server.archive.ArchiveConfig()
                .unitOfWork(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        fixture.jdbc.getDataSource())),
            data);
    personalSpaces.ensure("enzo");
    personalSettings = new MessagingProperties();
    messages.useRouting(
        new ProjectMessageRouting(
            routeProjects,
            new io.aeyer.plowshare.server.archive.JdbcProjectMembers(fixture.jdbc),
            personalSettings,
            personalSpaces));
  }

  @Test
  void personal_routes_default_to_any_accessible_project_and_have_account_qualified_addresses()
      throws Exception {
    personalRouting();
    routingWorkspace("notifications");
    String personal = io.aeyer.plowshare.server.personal.PersonalSpaces.name("enzo");
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
    for (String address :
        List.of("Personal:bob", io.aeyer.plowshare.server.personal.PersonalSpaces.name("bob"))) {
      String denied =
          messages
              .tool(caller)
              .run(
                  JSON.writeValueAsString(
                      java.util.Map.of("to_project", address, "to", "reviewer", "body", "Denied")),
                  caller.home());
      assertTrue(denied.contains("another account"), denied);
    }
    assertEquals(
        2, fixture.jdbc.queryForObject("SELECT count(*) FROM board_message_routes", Integer.class));
  }

  @Test
  void personal_defaults_can_be_disabled_independently_and_explicit_qualified_routes_then_apply()
      throws Exception {
    personalRouting();
    String personal = io.aeyer.plowshare.server.personal.PersonalSpaces.name("enzo");
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
            java.util.Map.of(
                "version",
                1,
                "name",
                "Personal:enzo",
                "routing",
                java.util.Map.of(
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
        java.util.Map.of(
            "acceptFrom", List.of("Personal:enzo"), "sendTo", List.of("Personal:enzo")));
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
    routingManifest("payments", java.util.Map.of());
    routingManifest("notifications", java.util.Map.of());
    String args = "{\"to_project\":\"notifications\",\"to\":\"reviewer\",\"body\":\"Review\"}";
    assertTrue(messages.tool(caller).run(args, caller.home()).contains("Both projects"));
    routingManifest("payments", java.util.Map.of("sendTo", List.of("notifications")));
    assertTrue(messages.tool(caller).run(args, caller.home()).contains("Both projects"));
    assertEquals(
        0,
        fixture.jdbc.queryForObject("SELECT count(*) FROM board_message_instances", Integer.class));
    routingManifest("notifications", java.util.Map.of("acceptFrom", List.of("payments")));
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
    routingManifest("notifications", java.util.Map.of());
    assertTrue(messages.tool(caller).run(args, caller.home()).contains("Both projects"));
    assertTrue(
        messages
            .tool(caller)
            .run(
                "{\"to\":\"" + destination.id() + "\",\"body\":\"Denied by address too\"}",
                caller.home())
            .contains("Both projects"));
    assertEquals(
        2, fixture.jdbc.queryForObject("SELECT count(*) FROM board_message_routes", Integer.class));
    assertEquals(2, fixture.jdbc.queryForObject("SELECT count(*) FROM firings", Integer.class));
  }

  @Test
  void
      named_routes_queue_recipient_work_without_inheriting_inputs_and_replies_survive_policy_revocation()
          throws Exception {
    routingManifest(
        "payments",
        java.util.Map.of(
            "sendTo", List.of("notifications"), "routeFiles", List.of("routes/internal.json")));
    routingManifest("notifications", java.util.Map.of("acceptFrom", List.of("payments")));
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
    routingManifest("payments", java.util.Map.of());
    routingManifest("notifications", java.util.Map.of());
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
    routingManifest("payments", java.util.Map.of("routeFiles", List.of("routes.json")));
    routingManifest("notifications", java.util.Map.of("acceptFrom", List.of("payments")));
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
        java.util.Map.of(
            "sendTo", List.of("notifications"), "routeFiles", List.of("../outside.json")));
    assertTrue(
        messages
            .tool(caller)
            .run("{\"route\":\"review\",\"body\":\"Denied\"}", caller.home())
            .contains("relative paths"));
    routingManifest(
        "payments",
        java.util.Map.of("sendTo", List.of("notifications"), "routeFiles", List.of("routes.json")));
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
        0, fixture.jdbc.queryForObject("SELECT count(*) FROM board_message_routes", Integer.class));
    assertEquals(
        0,
        fixture.jdbc.queryForObject("SELECT count(*) FROM board_message_instances", Integer.class));
  }

  private void messageRoutes(List<java.util.Map<String, Object>> routes) throws Exception {
    routingManifest(
        "payments",
        java.util.Map.of("sendTo", List.of("notifications"), "routeFiles", List.of("routes.json")));
    routingManifest("notifications", java.util.Map.of("acceptFrom", List.of("payments")));
    Files.writeString(
        routingWorkspace("payments").resolve("routes.json"),
        JSON.writeValueAsString(java.util.Map.of("version", 1, "routes", routes)));
  }

  private java.util.Map<String, Object> retainedRoute(String name) {
    return java.util.Map.of(
        "name", name, "project", "notifications", "agent", "reviewer", "retainConversation", true);
  }

  @Test
  void retained_routes_have_dedicated_logs_survive_restart_and_do_not_follow_project_defaults()
      throws Exception {
    messageRoutes(
        List.of(
            retainedRoute("review"),
            retainedRoute("audit"),
            java.util.Map.of("name", "shared", "project", "notifications", "agent", "reviewer")));
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
        new BoardMessaging(
            new JdbcBoardMessagingRepository(fixture.jdbc),
            fixture.store,
            fixture.conversations,
            fixture.firings,
            new BoardPot(fixture.store),
            fixture.work,
            voice,
            (name, project) -> agents.get(name),
            fixture.drained::add,
            fixture.clock);
    messages.useRouting(
        new ProjectMessageRouting(
            routeProjects, new io.aeyer.plowshare.server.archive.JdbcProjectMembers(fixture.jdbc)));
    JsonNode later = send(caller, "{\"route\":\"review\",\"body\":\"Later\"}", "retained-later");
    assertEquals(first.path("to"), later.path("to"));
    assertEquals(first.path("to_conversation"), later.path("to_conversation"));
    assertEquals(
        later.path("message"),
        send(caller, "{\"route\":\"review\",\"body\":\"Later\"}", "retained-later")
            .path("message"));
    assertEquals(
        2,
        fixture.jdbc.queryForObject(
            "SELECT count(*) FROM board_message_route_bindings", Integer.class));
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
            java.util.Map.of(
                "name",
                "review",
                "project",
                "notifications",
                "agent",
                "reviewer",
                "conversation",
                target)));
    var opened = new ArrayList<io.aeyer.plowshare.server.agents.LogStages.LogOpened>();
    messages.useLogStages(
        new io.aeyer.plowshare.server.agents.LogStages() {
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
            java.util.Map.of(
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
        fixture.jdbc.queryForObject(
            "SELECT count(*) FROM board_message_route_bindings", Integer.class));
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
    fixture.conversations.moveTo(
        archived, io.aeyer.plowshare.server.archive.ConversationLifecycle.ARCHIVED);
    String hidden =
        fixture
            .conversations
            .open(Home.of("notifications"), Budget.of(20), TurnCap.of(4), "enzo")
            .id();
    messages.useLogVisibility((conversation, account) -> !conversation.equals(hidden));
    for (String id : List.of(foreign, archived, hidden, caller.conversationId(), "cnv_missing")) {
      messageRoutes(
          List.of(
              java.util.Map.of(
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
        0, fixture.jdbc.queryForObject("SELECT count(*) FROM board_message_routes", Integer.class));
    messageRoutes(
        List.of(
            java.util.Map.of("name", "plain", "project", "notifications", "agent", "reviewer")));
    JsonNode task =
        send(caller, "{\"route\":\"plain\",\"body\":\"Task\",\"lifetime\":\"task\"}", "task");
    String taskConversation = task.path("to_conversation").asText();
    messageRoutes(
        List.of(
            java.util.Map.of(
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
              java.util.Map.of(
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
            java.util.Map.of(
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
        fixture.jdbc.queryForObject(
            "SELECT count(*) FROM board_message_route_bindings", Integer.class));
    assertEquals(
        0, fixture.jdbc.queryForObject("SELECT count(*) FROM board_message_routes", Integer.class));
  }

  @Test
  void concurrent_first_sends_to_a_retained_route_create_one_binding_and_one_log()
      throws Exception {
    messageRoutes(List.of(retainedRoute("review")));
    String second =
        fixture.conversations.open(Home.of("payments"), Budget.of(20), TurnCap.of(4), "enzo").id();
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
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
          first.get(10, java.util.concurrent.TimeUnit.SECONDS).path("to"),
          next.get(10, java.util.concurrent.TimeUnit.SECONDS).path("to"));
    }
    assertEquals(
        1,
        fixture.jdbc.queryForObject(
            "SELECT count(*) FROM board_message_route_bindings", Integer.class));
    assertEquals(
        3, fixture.jdbc.queryForObject("SELECT count(*) FROM conversations", Integer.class));
  }

  @Test
  void retained_route_bindings_are_account_and_source_project_scoped_and_recheck_permission()
      throws Exception {
    messageRoutes(List.of(retainedRoute("review")));
    JsonNode first = send(caller, "{\"route\":\"review\",\"body\":\"Enzo\"}", "enzo-route");
    fixture.jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('other', 'h')");
    var members = new io.aeyer.plowshare.server.archive.JdbcProjectMembers(fixture.jdbc);
    members.add("payments", "other");
    members.add("notifications", "other");
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
        java.util.Map.of("sendTo", List.of("notifications"), "routeFiles", List.of("routes.json")));
    Files.writeString(
        routingWorkspace("operations").resolve("routes.json"),
        JSON.writeValueAsString(
            java.util.Map.of("version", 1, "routes", List.of(retainedRoute("review")))));
    routingManifest(
        "notifications", java.util.Map.of("acceptFrom", List.of("payments", "operations")));
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
    routingManifest("notifications", java.util.Map.of());
    assertTrue(
        messages
            .tool(caller)
            .run("{\"route\":\"review\",\"body\":\"Revoked\"}", caller.home())
            .contains("Both projects"));
    assertEquals(
        3,
        fixture.jdbc.queryForObject(
            "SELECT count(*) FROM board_message_route_bindings", Integer.class));
    assertEquals(
        3, fixture.jdbc.queryForObject("SELECT count(*) FROM board_message_routes", Integer.class));
  }

  @Test
  void cross_project_routes_do_not_bypass_membership_accounts_or_private_client_scopes()
      throws Exception {
    routingManifest("payments", java.util.Map.of("sendTo", List.of("notifications")));
    routingManifest("notifications", java.util.Map.of("acceptFrom", List.of("payments")));
    fixture.jdbc.update("UPDATE admins SET server_admin = FALSE WHERE handle = 'enzo'");
    fixture.jdbc.update(
        "DELETE FROM project_members WHERE project_id = (SELECT id FROM projects WHERE name = 'notifications') AND handle = 'enzo'");
    String args = "{\"to_project\":\"notifications\",\"to\":\"reviewer\",\"body\":\"Review\"}";
    assertTrue(messages.tool(caller).run(args, caller.home()).contains("not permitted"));
    new io.aeyer.plowshare.server.archive.JdbcProjectMembers(fixture.jdbc)
        .add("notifications", "enzo");
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
        1, fixture.jdbc.queryForObject("SELECT count(*) FROM board_message_routes", Integer.class));
  }

  @Test
  void a_send_is_a_private_board_record_with_a_durable_return_address_and_wake() throws Exception {
    JsonNode receipt = request(true);
    String id = receipt.path("message").asText();
    assertTrue(fixture.store.message(id).isPresent());
    assertTrue(messages.owns(wake(receipt)));
    String incoming = messages.incoming(id);
    assertTrue(incoming.contains(receipt.path("from").asText()));
    assertTrue(incoming.contains("return_address"));
    assertTrue(fixture.store.summaries("enzo", "payments", true, 0, 100).isEmpty());
    assertTrue(fixture.store.openTopics().isEmpty());
    assertFalse(messages.visible(recipient(receipt).conversationId(), caller.conversationId()));
  }

  @Test
  void retries_do_not_duplicate_messages_or_wakes_and_names_reuse_the_default() throws Exception {
    JsonNode first = request(true);
    JsonNode retry = request(true);
    assertEquals(first, retry);
    JsonNode next = send(caller, "{\"to\":\"reviewer\",\"body\":\"Another\"}", "next");
    assertEquals(first.path("to"), next.path("to"));
    assertEquals(
        2, fixture.jdbc.queryForObject("SELECT count(*) FROM board_message_routes", Integer.class));
    assertEquals(2, fixture.jdbc.queryForObject("SELECT count(*) FROM firings", Integer.class));
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
        2, fixture.jdbc.queryForObject("SELECT count(*) FROM board_message_routes", Integer.class));
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
    BoardMessaging.Route reply = messages.route(finalId).orElseThrow();
    assertTrue(reply.generated());
    assertEquals("TURN_CAP", reply.ending());
    assertEquals("The run reached its cap.", fixture.store.message(finalId).orElseThrow().body());
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
    assertEquals(1, fixture.store.topic(call.instance().topic()).orElseThrow().potSpent());
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

  private io.aeyer.plowshare.server.approvals.RunApproval approval(
      JsonNode receipt, String askedIn) {
    var instance = messages.instance(receipt.path("to").asText()).orElseThrow();
    return new io.aeyer.plowshare.server.approvals.RunApproval(
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
    assertTrue(
        ((io.aeyer.plowshare.server.events.EventPayload.Message) continued.data()).wake().approval()
            != null);
    messages.start(continued, (conversation, outcome) -> {});
    voice.calls.getLast().ended().accept(outcome(Outcome.Ending.ANSWERED, "Approved work done"));
    assertEquals(2, voice.calls.size());
    assertTrue(messages.route(original).orElseThrow().handled());
    assertFalse(messages.instance(request.path("to").asText()).orElseThrow().active());
    assertEquals(
        "Approved work done",
        fixture.store.message(messages.finalReply(original).orElseThrow()).orElseThrow().body());
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
    assertTrue(
        ((io.aeyer.plowshare.server.events.EventPayload.Message) continued.data()).wake().approval()
            != null);
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
            .log(
                io.aeyer.plowshare.server.archive.Origin.DELEGATION,
                Home.of("payments"),
                "reviewer",
                root,
                null)
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
        ((io.aeyer.plowshare.server.events.EventPayload.Message) report.data())
                .wake()
                .continuation()
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
            .log(
                io.aeyer.plowshare.server.archive.Origin.DELEGATION,
                Home.of("payments"),
                "reviewer",
                root,
                null)
            .id();
    assertFalse(messages.visible(child, caller.conversationId()));
    assertTrue(messages.visible(child, root));
    assertTrue(messages.visible(root, child));
    assertFalse(messages.visible(caller.conversationId(), child));
  }

  @Test
  void withheld_instance_rows_preserve_pagination_without_revealing_their_contents() {
    var first =
        messages.open(
            "enzo", "payments", "reviewer", false, java.util.UUID.randomUUID().toString());
    var second =
        messages.open(
            "enzo", "payments", "reviewer", false, java.util.UUID.randomUUID().toString());
    messages.useLogVisibility(
        (conversation, account) -> !conversation.equals(first.conversation()));
    var page = messages.listingPage("enzo", "payments", false, 0, 1);
    assertTrue(page.instances().isEmpty());
    assertTrue(page.more());
    assertEquals(
        second.id(),
        messages.listingPage("enzo", "payments", false, 1, 1).instances().getFirst().id());
    assertThrows(Board.Refused.class, () -> messages.inspect(first.id(), "enzo"));
  }

  @Test
  void instance_opening_defaults_and_archival_are_durable_and_account_scoped() throws Exception {
    String key = java.util.UUID.randomUUID().toString();
    var first = messages.open("enzo", "payments", "reviewer", true, key);
    assertEquals(first, messages.open("enzo", "payments", "reviewer", true, key));
    assertThrows(Board.Refused.class, () -> messages.open("enzo", "payments", "bot", true, key));
    var second =
        messages.open(
            "enzo", "payments", "reviewer", false, java.util.UUID.randomUUID().toString());
    assertEquals(first.id(), request(false).path("to").asText());
    messages.makeDefault(second.id(), "enzo");
    assertEquals(
        second.id(),
        send(caller, "{\"to\":\"reviewer\",\"body\":\"New default\"}", "default")
            .path("to")
            .asText());
    assertFalse(messages.inspect(first.id(), "enzo").defaultInstance());
    assertThrows(Board.Refused.class, () -> messages.inspect(first.id(), "someone"));
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
        Board.Refused.class,
        () -> messages.start(wakeFrom(original), (conversation, outcome) -> {}));
    messages.complete(original, outcome(Outcome.Ending.ANSWERED, "Late answer"));
    assertEquals(cancelled.reply(), messages.finalReply(original).orElseThrow());
    assertEquals(0, voice.calls.size());
    assertTrue(
        fixture.firings.list(null, null, 0, 100).isEmpty(),
        "The global firing inspector cannot expose private messaging data");
  }

  private FiringRecord wakeFrom(String message) {
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
    List<String> cancelled = new java.util.concurrent.CopyOnWriteArrayList<>();
    var cancellation = new java.util.concurrent.CompletableFuture<String>();
    messages.useCancellation(
        id -> {
          cancelled.add(id);
          cancellation.complete(id);
        });
    var stopped = messages.stop(request.path("to").asText(), "enzo", false);
    assertFalse(stopped.active());
    assertEquals(job, cancellation.get(5, java.util.concurrent.TimeUnit.SECONDS));
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
            "SELECT count(*) FROM board_message_routes WHERE reply_to = ? AND final",
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
        "UPDATE board_message_routes SET deadline_at = ? WHERE message = ?",
        java.sql.Timestamp.from(fixture.clock.get().minusSeconds(1)),
        original);
    messages.expire();
    messages.expire();
    assertEquals("expired", messages.delivery(original, "enzo").state());
    assertEquals("CANCELLED", messages.delivery(original, "enzo").ending());
    assertEquals(
        1,
        fixture.jdbc.queryForObject(
            "SELECT count(*) FROM board_message_routes WHERE reply_to = ? AND final",
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
        fixture
            .store
            .topic(messages.instance(request.path("to").asText()).orElseThrow().topic())
            .orElseThrow()
            .potTotal();
    String refused =
        messages
            .tool(caller)
            .run("{\"to\":\"reviewer\",\"body\":\"Another\"}", Home.of("payments"));
    assertTrue(refused.contains("queue is full"), refused);
    assertEquals(
        potBefore,
        fixture
            .store
            .topic(messages.instance(request.path("to").asText()).orElseThrow().topic())
            .orElseThrow()
            .potTotal());
    int instances =
        fixture.jdbc.queryForObject("SELECT count(*) FROM board_message_instances", Integer.class);
    refused =
        messages
            .tool(caller)
            .run(
                "{\"to\":\"bot\",\"body\":\"A message beyond the configured limit\"}",
                Home.of("payments"));
    assertTrue(refused.contains("size limit"), refused);
    assertEquals(
        instances,
        fixture.jdbc.queryForObject("SELECT count(*) FROM board_message_instances", Integer.class));
    assertEquals(
        1, fixture.jdbc.queryForObject("SELECT count(*) FROM board_message_routes", Integer.class));
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
          public io.aeyer.plowshare.server.llm.dispatch.ToolSchema schema() {
            return io.aeyer.plowshare.server.llm.dispatch.ToolSchema.from(
                "conversation_trajectory", "test", java.util.Map.of());
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
        2, fixture.jdbc.queryForObject("SELECT count(*) FROM board_message_routes", Integer.class));
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
    var approvals =
        new io.aeyer.plowshare.server.approvals.RunApprovalStore(
            fixture.jdbc, () -> java.time.Instant.now().plusSeconds(1));
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
        2, fixture.jdbc.queryForObject("SELECT count(*) FROM board_message_routes", Integer.class));
  }

  @Test
  void cancelling_an_unreadable_delivery_is_refused_before_changing_its_state() throws Exception {
    JsonNode request = request(true);
    messages.useLogVisibility(
        (conversation, account) -> !conversation.equals(recipient(request).conversationId()));
    assertThrows(
        Board.Refused.class, () -> messages.cancel(request.path("message").asText(), "enzo"));
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
    assertEquals(16, fixture.store.topic(call.instance().topic()).orElseThrow().potSpent());
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
        1, fixture.jdbc.queryForObject("SELECT count(*) FROM board_message_routes", Integer.class));
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
        0, fixture.jdbc.queryForObject("SELECT count(*) FROM board_message_routes", Integer.class));
    assertEquals(
        0,
        fixture.jdbc.queryForObject("SELECT count(*) FROM board_message_instances", Integer.class));
    assertEquals(
        0, fixture.jdbc.queryForObject("SELECT count(*) FROM board_topics", Integer.class));
    assertEquals(0, fixture.jdbc.queryForObject("SELECT count(*) FROM firings", Integer.class));
    assertTrue(fixture.drained.isEmpty());
  }

  @Test
  void concurrent_completion_and_explicit_reply_produce_one_final_message_and_one_wake()
      throws Exception {
    JsonNode receipt = request(true);
    String original = receipt.path("message").asText();
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
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
      String result = explicit.get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertTrue(result.startsWith("{") || result.contains("already has a final reply"), result);
      assertTrue(fallback.get(10, java.util.concurrent.TimeUnit.SECONDS));
    }
    assertTrue(messages.finalReply(original).isPresent());
    assertEquals(
        2, fixture.jdbc.queryForObject("SELECT count(*) FROM board_message_routes", Integer.class));
    assertEquals(2, fixture.jdbc.queryForObject("SELECT count(*) FROM firings", Integer.class));
  }

  @Test
  void search_and_list_projection_remove_other_instances_without_exposing_their_snippets()
      throws Exception {
    JsonNode receipt = request(false);
    String hidden = recipient(receipt).conversationId();
    for (String name : List.of("conversation_search", "conversation_list")) {
      AgentTool underlying =
          new AgentTool() {
            public io.aeyer.plowshare.server.llm.dispatch.ToolSchema schema() {
              return io.aeyer.plowshare.server.llm.dispatch.ToolSchema.from(
                  name, "test", java.util.Map.of());
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
          throw new io.aeyer.plowshare.server.faults.NotFoundFault(
              "log inputs are unavailable to this account");
        });
    messages.complete(original, outcome(Outcome.Ending.ANSWERED, "Restricted result"));
    messages.recover();
    assertTrue(messages.route(original).orElseThrow().handled());
    assertEquals("UNAVAILABLE", messages.route(original).orElseThrow().ending());
    assertTrue(messages.finalReply(original).isEmpty());
    assertEquals(
        1, fixture.jdbc.queryForObject("SELECT count(*) FROM board_message_routes", Integer.class));
    assertEquals(1, fixture.jdbc.queryForObject("SELECT count(*) FROM firings", Integer.class));
    assertEquals(0, voice.calls.size());
  }

  @Test
  void a_delegated_child_inherits_the_message_instances_scheduling_share() throws Exception {
    JsonNode receipt = request(false);
    var parent = recipient(receipt);
    String child =
        fixture
            .conversations
            .log(
                io.aeyer.plowshare.server.archive.Origin.DELEGATION,
                parent.home(),
                "sender",
                parent.conversationId(),
                null,
                "enzo")
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
        new BoardRunExtras(
            fixture.store, fixture.board(BoardFixture.TWO), fixture.conversations, messages);
    List<String> names =
        extras.forRun(recipient(receipt)).tools().stream().map(t -> t.schema().name()).toList();
    assertEquals(List.of("send_message", "board_open"), names);
    assertFalse(names.contains("board_read"));
    assertFalse(names.contains("board_post"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"local", "server", "personal", "retained", "conversation"})
  void the_real_dispatcher_and_turn_loop_deliver_an_explicit_reply_and_wake_the_caller(
      String routeKind) throws Exception {
    boolean crossProject = !routeKind.equals("local");
    boolean personalRoute = routeKind.equals("personal");
    boolean pinnedRoute = routeKind.equals("retained") || routeKind.equals("conversation");
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var sawPriorHistory = new java.util.concurrent.atomic.AtomicBoolean();
    var transport =
        new io.aeyer.plowshare.server.llm.dispatch.LlmTransport() {
          public String poolName() {
            return "test";
          }

          public io.aeyer.plowshare.server.llm.dispatch.Completion complete(
              String model,
              List<io.aeyer.plowshare.server.llm.dispatch.ChatMessage> history,
              io.aeyer.plowshare.server.llm.dispatch.Sampling sampling,
              List<io.aeyer.plowshare.server.llm.dispatch.ToolSchema> tools) {
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
                        java.util.Map.of(
                            "reply_to",
                            incoming.path("id").asText(),
                            "body",
                            "Reviewed explicitly",
                            "final",
                            true));
                return new io.aeyer.plowshare.server.llm.dispatch.Completion(
                    "",
                    "tool_calls",
                    io.aeyer.plowshare.server.llm.dispatch.TokenUsage.UNKNOWN,
                    List.of(
                        new io.aeyer.plowshare.protocol.ToolCall("reply-1", "send_message", args)));
              } catch (Exception failure) {
                throw new IllegalStateException(failure);
              }
            }
            return new io.aeyer.plowshare.server.llm.dispatch.Completion(
                "Handled",
                "stop",
                io.aeyer.plowshare.server.llm.dispatch.TokenUsage.UNKNOWN,
                List.of());
          }

          public io.aeyer.plowshare.server.llm.dispatch.Completion stream(
              String model,
              List<io.aeyer.plowshare.server.llm.dispatch.ChatMessage> history,
              io.aeyer.plowshare.server.llm.dispatch.Sampling sampling,
              List<io.aeyer.plowshare.server.llm.dispatch.ToolSchema> tools,
              io.aeyer.plowshare.server.llm.dispatch.Deltas sink,
              java.util.function.BooleanSupplier cancelled) {
            return complete(model, history, sampling, tools);
          }

          public io.aeyer.plowshare.server.llm.dispatch.Embeddings embed(
              String model, List<String> input) {
            throw new UnsupportedOperationException();
          }

          public void close() {}
        };
    var pool =
        new io.aeyer.plowshare.server.llm.dispatch.LlmPool(
            "test",
            List.of("wire"),
            java.util.Map.of("test", "wire"),
            2,
            1,
            java.time.Duration.ofSeconds(5),
            transport,
            Set.of());
    var llm =
        new io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher(
            List.of(pool), new io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger());
    var runtime = new JobRuntime(llm, List.of());
    JobStore jobs = new JobStore(runtime);
    try {
      var turns = new io.aeyer.plowshare.server.archive.TurnStore(fixture.jdbc);
      var entries = new io.aeyer.plowshare.server.archive.EntryStore(fixture.jdbc);
      var compaction =
          new Compaction(
              llm,
              () -> agents.get("reviewer"),
              turns,
              new io.aeyer.plowshare.server.archive.CompactionStore(fixture.jdbc),
              entries,
              1_000_000);
      var turn = new Turn(jobs, fixture.conversations, turns, compaction);
      var dispatcher =
          new io.aeyer.plowshare.server.events.Dispatcher(
              fixture.firings,
              new io.aeyer.plowshare.server.events.JdbcTriggerStore(fixture.jdbc),
              new io.aeyer.plowshare.server.events.Dispatcher.Runner() {
                public boolean busy(io.aeyer.plowshare.server.events.TriggerRecord trigger) {
                  return false;
                }

                public String start(
                    io.aeyer.plowshare.server.events.TriggerRecord trigger,
                    String utterance,
                    java.util.function.BiConsumer<String, Outcome> ended) {
                  throw new AssertionError("No trigger expected");
                }
              },
              new io.aeyer.plowshare.server.events.Inbox(
                  new io.aeyer.plowshare.server.events.JdbcInboxStore(fixture.jdbc),
                  io.aeyer.plowshare.server.events.AccountPushes.NONE,
                  fixture.clock),
              fixture.clock);
      messages =
          new BoardMessaging(
              new JdbcBoardMessagingRepository(fixture.jdbc),
              fixture.store,
              fixture.conversations,
              fixture.firings,
              new BoardPot(fixture.store),
              fixture.work,
              new BoardMessaging.Voice() {
                @Override
                public String speakFrom(
                    BoardMessaging.Instance instance,
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
                    BoardMessaging.Instance instance,
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
      var runHomes = new java.util.concurrent.CopyOnWriteArrayList<Home>();
      runtime.useRunExtras(
          c -> {
            runHomes.add(c.home());
            return new RunExtras.Extras(List.of(messages.tool(c)), c.end(), false);
          });
      dispatcher.useWakes(messages);
      turn.whenFree(c -> dispatcher.drain("conversation:" + c));
      if (crossProject) {
        if (personalRoute) {
          personalRouting();
          routingManifest("payments", java.util.Map.of("routeFiles", List.of("routes.json")));
        } else {
          routingManifest(
              "payments",
              java.util.Map.of(
                  "sendTo", List.of("notifications"), "routeFiles", List.of("routes.json")));
          routingManifest("notifications", java.util.Map.of("acceptFrom", List.of("payments")));
        }
        var configured =
            new java.util.LinkedHashMap<String, Object>(
                java.util.Map.of(
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
            JSON.writeValueAsString(java.util.Map.of("version", 1, "routes", List.of(configured))));
      }
      JsonNode receipt =
          crossProject
              ? send(
                  caller,
                  "{\"route\":\"review\",\"body\":\"Review\",\"reply_expected\":true}",
                  "request")
              : request(true);
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
      while (fixture.jdbc.queryForObject(
              "SELECT count(*) FROM firings WHERE status = 'queued'"
                  + " OR (status = 'started' AND finished_at IS NULL)",
              Integer.class)
          != 0) {
        assertTrue(System.nanoTime() < deadline, "Message wakes did not finish");
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
                  + fixture.store.message(reply.message()).orElseThrow().body()
                  + "; entries: "
                  + fixture.jdbc.queryForList(
                      "SELECT kind,content FROM entries ORDER BY recorded_at"));
      assertEquals(
          "Reviewed explicitly", fixture.store.message(reply.message()).orElseThrow().body());
      assertEquals(
          3, calls.get(), "Reviewer makes a tool call and ends; the original sender wakes once");
      assertEquals(2, fixture.jdbc.queryForObject("SELECT count(*) FROM firings", Integer.class));
      assertEquals(1, turns.forConversation(caller.conversationId()).size());
      assertTrue(
          runHomes.contains(
              Home.of(
                  personalRoute
                      ? io.aeyer.plowshare.server.personal.PersonalSpaces.name("enzo")
                      : crossProject ? "notifications" : "payments")));
      assertEquals(
          Home.of("payments"),
          runHomes.getLast(),
          "The return wake retains the sender's own project");
      var delivery = messages.delivery(receipt.path("message").asText(), "enzo");
      var trajectory = entries.pageOfLog(delivery.conversation(), 0, 100).listed();
      var incoming =
          trajectory.stream()
              .filter(row -> row.kind() == io.aeyer.plowshare.server.agents.EntryKind.UTTERANCE)
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
        deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (fixture.jdbc.queryForObject(
                "SELECT count(*) FROM firings WHERE status = 'queued'"
                    + " OR (status = 'started' AND finished_at IS NULL)",
                Integer.class)
            != 0) {
          assertTrue(System.nanoTime() < deadline, "Later retained message wakes did not finish");
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
        new io.aeyer.plowshare.protocol.ScheduledWork(
            1,
            "0 0 9 * * *",
            "UTC",
            false,
            new io.aeyer.plowshare.protocol.ScheduledWork.Action(
                "skill", "reviewer", "review", "Exact arguments", null),
            new io.aeyer.plowshare.protocol.ScheduledWork.Target(
                "message", null, null, "reviewer", null),
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
