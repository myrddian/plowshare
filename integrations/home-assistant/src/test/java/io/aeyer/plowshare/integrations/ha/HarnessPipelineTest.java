package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.integrations.Configuration;
import io.aeyer.plowshare.integrations.Gateway;
import io.aeyer.plowshare.integrations.IntegrationRuntime;
import io.aeyer.plowshare.integrations.Journal;
import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.integrations.RelayRequests;
import io.aeyer.plowshare.integrations.ScriptHost;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.sdk.AgentClient;
import io.aeyer.plowshare.sdk.Plowshare;
import io.aeyer.plowshare.sdk.RelayClient;
import io.aeyer.plowshare.server.PlowshareServerApplication;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.auth.TokenStore;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
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
import io.aeyer.plowshare.server.messaging.MessageExportProperties;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayExecutionPublisher;
import io.aeyer.plowshare.server.relay.RelayPayload;
import io.aeyer.plowshare.server.relay.RelayPortProperties;
import io.aeyer.plowshare.server.relay.RelayPublishingProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real core/harness/SDK/runtime/HA transport, with deterministic inference and simulated devices.
 */
@Tag("full-db")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = {PlowshareServerApplication.class, HarnessPipelineTest.Models.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HarnessPipelineTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static final Path DATA = data();
  @TempDir Path directory;
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ProjectStore projects;
  @Autowired TokenStore tokens;
  @Autowired Model model;
  @Autowired Relay relay;
  @Autowired RelayPortProperties ports;
  @Autowired RelayPublishingProperties publishing;
  @Autowired MessageExportProperties exports;
  private Journal journal;
  private String project;
  private RelayClient relayClient;

  @AfterEach
  void closeJournal() throws Exception {
    if (journal != null) journal.close();
  }

  private static Path data() {
    try {
      return new DataLayout(Files.createTempDirectory("ha-harness-")).initialise().root();
    } catch (Exception failed) {
      throw new IllegalStateException(failed);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("server.address", () -> "127.0.0.1");
    registry.add(
        "plowshare.projects.workspace-directory", () -> DATA.resolve("workspaces").toString());
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("plowshare.data.dir", DATA::toString);
    registry.add("LLM_BASE_URL", () -> "http://127.0.0.1:1/v1");
    registry.add("SPARK_BASE_URL", () -> "http://127.0.0.1:1/v1");
    registry.add("LLM_CHAT_MODEL", () -> "fixture-model");
    registry.add("LLM_EMBEDDING_MODEL", () -> "fixture-embedding");
    // An indexed pool replaces the deployment list. Declare every required fixture capability;
    // the primary test dispatcher below supplies deterministic inference and no endpoint is live.
    registry.add("plowshare.llm.pools[0].name", () -> "fixture");
    registry.add("plowshare.llm.pools[0].base-url", () -> "http://127.0.0.1:1/v1");
    registry.add("plowshare.llm.pools[0].models[0]", () -> "fixture-model");
    registry.add("plowshare.llm.pools[0].models[1]", () -> "fixture-embedding");
    registry.add("plowshare.llm.pools[0].vision[0]", () -> "fixture-model");
    registry.add("plowshare.llm.pools[0].chat", () -> "1");
    registry.add("plowshare.llm.pools[0].embedding", () -> "1");
  }

  private String installProject() throws Exception {
    String name = "house-" + UUID.randomUUID();
    String account = "ha-harness-owner";
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin) VALUES(?,'fixture',true) ON"
            + " CONFLICT DO NOTHING",
        account);
    Path workspace = Files.createDirectory(directory.resolve("workspace"));
    Path examples = Path.of(System.getProperty("integration.examples")).resolve("application");
    try (var paths = Files.walk(examples)) {
      for (Path source : paths.toList()) {
        Path target = workspace.resolve(examples.relativize(source));
        if (Files.isDirectory(source)) Files.createDirectories(target);
        else Files.copy(source, target);
      }
    }
    var manifest = (ObjectNode) Json.parse(Files.readString(examples.resolve("plowshare.json")));
    manifest.put("name", name);
    manifest
        .withObject("access")
        .withArray("accounts")
        .addObject()
        .put("handle", account)
        .put("role", "MANAGER");
    Files.writeString(workspace.resolve("plowshare.json"), manifest.toString());
    Files.createDirectories(workspace.resolve("Relay/native-returns"));
    Files.writeString(
        workspace.resolve("Relay/active.json"), "{\"version\":1,\"active\":[\"native-returns\"]}");
    Files.writeString(
        workspace.resolve("Relay/native-returns/routes.js"),
        """
      export const manifest = {version:1,subscriptions:[
        {name:'results',topic:'ha.returns',kind:'text',start:'oldest-retained'}]};
      export function route(event) {return [{name:'house',receiver:'message.return'}];}
      """);
    projects.define(name, workspace, List.of(), account);
    var portGrants = new ArrayList<>(ports.getBindings());
    portGrants.add(
        new RelayPortProperties.Binding(
            name, "ha.returns", account, RelayPortProperties.Direction.INGRESS, List.of()));
    ports.setBindings(portGrants);
    var grants = new ArrayList<>(publishing.getGrants());
    grants.add(new RelayPublishingProperties.Grant(name, "house_coordinator", "ha.requests"));
    publishing.setGrants(grants);
    var policies = new ArrayList<>(exports.getPolicies());
    policies.add(
        new MessageExportProperties.Policy(name, "ha.requests", "house", "ha.returns", account));
    exports.setPolicies(policies);
    long id = jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, name);
    relay.registerTopic(
        new Relay.TopicKey(id, "ha.requests"),
        RelayPayload.Kind.TEXT,
        Relay.Policy.systemDefault());
    relay.registerTopic(
        new Relay.TopicKey(id, "ha.returns"), RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
    model.reset();
    project = name;
    return name;
  }

  private Plowshare sdk() throws Exception {
    return Plowshare.connect(
        "http://127.0.0.1:" + port,
        tokens.issuePair("ha-harness-owner", false).access(),
        Duration.ofSeconds(8),
        null);
  }

  @ParameterizedTest
  @ValueSource(strings = {"22", "unavailable"})
  void direct_question_uses_real_native_export_and_correlated_reply_preserving_missing_evidence(
      String reading) throws Exception {
    String project = installProject();
    try (var ha = new MockWebServer();
        var caller = sdk();
        var adapterSession = sdk()) {
      var fixture = new HomeAssistantAdapterTest.HaFixture();
      fixture.reading = reading;
      ha.enqueue(new MockResponse().withWebSocketUpgrade(fixture));
      try (var runtime = runtime(project, ha, adapterSession)) {
        runtime.start();
        runtime.tick();
        var agents = new AgentClient(caller);
        var job =
            agents.run(
                new AgentClient.Run(
                    "house_coordinator",
                    "What is the office temperature?",
                    project,
                    null,
                    null,
                    true));
        JsonNode ended =
            drive(runtime, () -> returnedJob(agents), status -> status.path("outcome").isObject());
        assertTrue(ended.path("outcome").path("answered").asBoolean(), ended.toString());
        assertEquals(
            reading.equals("unavailable")
                ? "Office temperature is unavailable."
                : "Office temperature is 22 °C.",
            ended.path("outcome").path("text").asText());
        assertEquals(1, countWork(project));
        assertEquals(0, fixture.actions());
        assertEquals(List.of("relay_publish"), model.calls);
        assertEquals(3, model.invocations.get());
        assertNotNull(model.work);
        assertEquals(job.conversation(), ended.path("conversation").asText());
        assertTrue(
            jdbc.queryForObject(
                "SELECT r.reply_to=e.request_message AND r.recipient=e.requester FROM message_execution_returns returned JOIN message_execution_exports e ON e.source_event=returned.source_event JOIN message_routes r ON r.message=returned.message WHERE e.source_event=?",
                Boolean.class,
                model.work));
        assertEquals(
            project,
            jdbc.queryForObject(
                "SELECT p.name FROM message_execution_exports w JOIN projects p ON"
                    + " p.id=w.project_id WHERE p.name=?",
                String.class,
                project));
        assertFalse(model.evidence.toString().contains("private"));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void event_started_real_conductor_reads_evidence_finishes_and_delivers_once(boolean scripted)
      throws Exception {
    String project = installProject();
    try (var ha = new MockWebServer();
        var adapterSession = sdk()) {
      var fixture = new HomeAssistantAdapterTest.HaFixture();
      ha.enqueue(new MockResponse().withWebSocketUpgrade(fixture));
      try (var runtime = runtime(project, ha, adapterSession, scripted)) {
        runtime.start();
        runtime.tick();
        fixture.reading = "30";
        fixture.event("30", "2026-10-04T00:02:00Z");
        drive(
            runtime,
            () -> Json.object().put("actions", fixture.actions()),
            status -> status.path("actions").asInt() == 1);
        for (int i = 0; i < 3; i++) runtime.tick();
        assertEquals(1, fixture.actions());
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM orchestrations WHERE project=?", Integer.class, project));
        assertEquals(
            0,
            countWork(project),
            "The authorized event reading is reused without a redundant external read");
        var notification =
            fixture.requests.stream()
                .filter(r -> r.path("type").asText().equals("call_service"))
                .findFirst()
                .orElseThrow();
        assertEquals(
            "Office temperature is 30 °C.",
            notification.path("service_data").path("message").asText());
        assertEquals(
            "finished",
            jdbc.queryForObject(
                "SELECT state FROM orchestrations WHERE project=?", String.class, project));
        assertTrue(model.calls.contains("orchestration_finish"));
        assertEquals("office.temperature", model.evidence.path("alias").asText());
        assertEquals("available", model.evidence.path("availability").asText());
        assertEquals("2026-10-04T00:02:00Z", model.evidence.path("last_updated").asText());
        assertTrue(model.evidence.path("context").isObject());
        assertFalse(model.evidence.toString().contains("private"));
      }
    }
  }

  private IntegrationRuntime runtime(String project, MockWebServer ha, Plowshare sdk)
      throws Exception {
    return runtime(project, ha, sdk, false);
  }

  private IntegrationRuntime runtime(
      String project, MockWebServer ha, Plowshare sdk, boolean scripted) throws Exception {
    relayClient = new RelayClient(sdk);
    var requests =
        relayClient.log(new RelayLog.Query(project, false, "ha.requests", null, 1)).topic();
    var returns =
        relayClient.log(new RelayLog.Query(project, false, "ha.returns", null, 1)).topic();
    var relayPolicy =
        new RelayRequests.Policy(
            "ha.requests",
            UUID.fromString(requests.generation()),
            "ha.returns",
            UUID.fromString(returns.generation()),
            "house",
            RelayPort.publisher("ha-harness-owner"),
            Set.of(RelayExecutionPublisher.identity("ha-harness-owner", "house_coordinator")),
            8,
            65536);
    var route =
        new Configuration.Route(
            "heat",
            "office.temperature",
            28.0,
            "°C",
            900,
            "investigate_office_heat",
            "house_coordinator",
            "Explain the office temperature",
            "notify",
            0);
    var binding =
        new Configuration.Binding(
            "house",
            "home-assistant",
            project,
            new HomeAssistantFactory()
                .decode(HomeAssistantAdapterTest.configuration(ha.url("/").toString()).toString()),
            Map.of("heat", route),
            Set.of("house"),
            scripted
                ? """
export default {
  onEvent(e,c) {
    if (e.alias !== 'office.temperature' || e.resync || e.availability !== 'available' || Number(e.state) <= 28) return [];
    return [c.startPipeline('heat', {evidence:e}, {key:'heat'})];
  },
  onCompletion(r,c) {
    if (r.state !== 'completed') return [];
    return [c.executeAction('house', 'notify', {message:r.reportText}, {key:'report'})];
  }
};
"""
                : null,
            "harness-fixture",
            Configuration.QueuePolicy.defaults(),
            Configuration.FeedbackPolicy.disabled(),
            relayPolicy);
    var config =
        new Configuration(
            "http://127.0.0.1:" + port,
            "PLOWSHARE_TOKEN",
            directory.resolve("journal"),
            Map.of("house", binding));
    journal = new Journal(config.journal());
    return new IntegrationRuntime(
        config,
        Map.of(
            "house",
            new HomeAssistantAdapter(
                binding.configuration(), "fixture-ha-token", Duration.ofSeconds(3))),
        new Gateway.Sdk(sdk),
        journal,
        new ScriptHost(Duration.ofSeconds(5)),
        Instant::now);
  }

  private int countWork(String project) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM message_execution_exports x JOIN projects p ON p.id=x.project_id WHERE"
            + " p.name=?",
        Integer.class,
        project);
  }

  @FunctionalInterface
  interface Read {
    JsonNode get() throws Exception;
  }

  private JsonNode returnedJob(AgentClient agents) throws Exception {
    UUID event = model.work;
    if (event == null) return Json.object();
    var jobs =
        jdbc.queryForList(
            "SELECT f.job_id FROM message_execution_returns r JOIN firings f ON f.data->>'direct_message'=r.message WHERE r.source_event=? AND f.job_id IS NOT NULL",
            String.class,
            event);
    if (jobs.isEmpty()) return Json.object();
    assertEquals(1, jobs.size());
    return Json.MAPPER.valueToTree(agents.status(jobs.getFirst()));
  }

  private JsonNode drive(IntegrationRuntime runtime, Read read, Predicate<JsonNode> done)
      throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    JsonNode status;
    do {
      runtime.tick();
      relayClient.process(new RelayLog.Process(project, 32));
      status = read.get();
      if (done.test(status)) return status;
      Thread.sleep(10);
    } while (System.nanoTime() < deadline);
    fail("The real harness pipeline did not settle: " + status + "; calls=" + model.calls);
    return status;
  }

  @TestConfiguration
  static class Models {
    @Bean
    Model fixtureModel() {
      return new Model();
    }

    @Bean
    @Primary
    LlmDispatcher fixtureDispatcher(Model model) {
      return new LlmDispatcher(
          List.of(
              new LlmPool(
                  "fixture",
                  List.of("fixture-model"),
                  Map.of(
                      "fast",
                      "fixture-model",
                      "reasoning",
                      "fixture-model",
                      "coding",
                      "fixture-model",
                      "vision",
                      "fixture-model"),
                  4,
                  1,
                  Duration.ofSeconds(5),
                  model,
                  Set.of("fixture-model"))),
          new NoOpTokenLedger(),
          type -> "fixture-model");
    }

    @Bean
    @Primary
    EmbeddingClient fixtureEmbeddings() {
      return new EmbeddingClient() {
        public float[] embed(String text) {
          float[] vector = new float[768];
          vector[0] = 1;
          return vector;
        }

        public List<float[]> embedAll(List<String> text) {
          return text.stream().map(this::embed).toList();
        }
      };
    }
  }

  static final class Model implements LlmTransport {
    volatile UUID work;
    volatile JsonNode evidence;
    final List<String> calls = new CopyOnWriteArrayList<>();
    final AtomicInteger invocations = new AtomicInteger();

    void reset() {
      work = null;
      evidence = null;
      calls.clear();
      invocations.set(0);
    }

    public String poolName() {
      return "fixture";
    }

    public Completion complete(
        String wire, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      try {
        invocations.incrementAndGet();
        ChatMessage last =
            messages.stream()
                .filter(m -> m.role() == ChatMessage.Role.TOOL)
                .reduce((a, b) -> b)
                .orElse(null);
        boolean conductor = tools.stream().anyMatch(t -> t.name().equals("orchestration_finish"));
        if (conductor && evidence == null) {
          // Both native threshold and script plans carry the already-authorized HA reading. A
          // second remote read would discard that captured version and need a different lifecycle.
          String input =
              messages.stream()
                  .filter(
                      message ->
                          message.role() == ChatMessage.Role.USER
                              && message.content().contains("Integration evidence (data):\n"))
                  .map(ChatMessage::content)
                  .findFirst()
                  .orElseThrow();
          String marker = "Integration evidence (data):\n";
          var captured = capturedEvidence(input.substring(input.indexOf(marker) + marker.length()));
          evidence = captured.has("evidence") ? captured.path("evidence") : captured;
          assertEquals("office.temperature", evidence.path("alias").asText());
          assertEquals("available", evidence.path("availability").asText());
          assertTrue(evidence.path("context").isObject());
          return call("todos", "todo_read", Json.object(), tools);
        }
        var incoming =
            messages.stream()
                .filter(
                    message ->
                        message.role() == ChatMessage.Role.USER
                            && message.content().startsWith("Incoming message ("))
                .reduce((before, after) -> after);
        if (!conductor && incoming.isPresent()) {
          String content = incoming.orElseThrow().content();
          var envelope = Json.parse(content.substring(content.indexOf('\n') + 1));
          var result = Json.parse(envelope.path("body").asText());
          assertEquals("plowshare-integration-result/1", result.path("schema").asText());
          assertEquals("COMPLETED", result.path("state").asText());
          evidence = result.path("result").path("states").path("office.temperature");
          assertTrue(evidence.isObject(), result.toString());
          return answer();
        }
        if (last == null)
          return call(
              "send",
              "relay_publish",
              Json.object()
                  .put("topic", "ha.requests")
                  .put(
                      "text",
                      "{\"schema\":\"plowshare-integration/1\",\"binding\":\"house\",\"operation\":\"states.read\",\"arguments\":{\"entities\":[\"office.temperature\"]}}"),
              tools);
        return switch (last.toolCallId()) {
          case "send" -> {
            work = UUID.fromString(Json.parse(last.content()).path("eventId").asText());
            // End the source turn normally. The retained native reply starts its own later turn;
            // no synthetic wait, tool result, approval or orchestration continuation is created.
            yield new Completion("Evidence requested.", "stop", TokenUsage.UNKNOWN, List.of());
          }
          case "todos" -> {
            var ops = Json.MAPPER.createArrayNode();
            for (String line : last.content().split("\n")) {
              if (!line.contains("(stage)")) continue;
              var item = Pattern.compile("^\\[[^]]*]\\s+(\\S+)").matcher(line.strip());
              assertTrue(item.find(), line);
              String id = item.group(1);
              ops.add(Json.object().put("op", "update").put("id", id).put("status", "in_progress"));
              ops.add(
                  Json.object()
                      .put("op", "update")
                      .put("id", id)
                      .put("status", "done")
                      .put("summary", report()));
            }
            assertEquals(6, ops.size(), last.content());
            yield call("done", "todo_write", Json.object().set("ops", ops), tools);
          }
          case "done" -> {
            assertTrue(last.content().startsWith("Done."), last.content());
            yield call(
                "finish", "orchestration_finish", Json.object().put("result", report()), tools);
          }
          default -> throw new AssertionError("Unexpected fixture tool result: " + last);
        };
      } catch (Exception failed) {
        throw new AssertionError(failed);
      }
    }

    private String report() {
      return evidence.path("availability").asText().equals("unavailable")
          ? "Office temperature is unavailable."
          : "Office temperature is " + evidence.path("state").asText() + " °C.";
    }

    private static JsonNode capturedEvidence(String prompt) throws IOException {
      // The real conductor prompt appends framework instructions after its supplied input. Find
      // exactly the first JSON object's grammar boundary, then apply the production strict parser
      // to those original bytes. This fixture neither relaxes runtime JSON nor invents evidence.
      try (var parser =
          JsonFactory.builder()
              .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
              .build()
              .createParser(prompt)) {
        assertEquals(JsonToken.START_OBJECT, parser.nextToken());
        parser.skipChildren();
        assertEquals(JsonToken.END_OBJECT, parser.currentToken());
        int end = Math.toIntExact(parser.currentLocation().getCharOffset());
        return Json.parse(prompt.substring(0, end));
      }
    }

    private Completion answer() {
      return new Completion(report(), "stop", TokenUsage.UNKNOWN, List.of());
    }

    private Completion call(String id, String name, JsonNode args, List<ToolSchema> tools) {
      assertTrue(
          tools.stream().anyMatch(t -> t.name().equals(name)), "Tool was not granted: " + name);
      calls.add(name);
      return new Completion(
          "", "tool_calls", TokenUsage.UNKNOWN, List.of(new ToolCall(id, name, args.toString())));
    }

    public Completion stream(
        String wire,
        List<ChatMessage> messages,
        Sampling sampling,
        List<ToolSchema> tools,
        Deltas sink,
        BooleanSupplier abandoned) {
      Completion result = complete(wire, messages, sampling, tools);
      if (!result.content().isEmpty()) sink.answered(result.content());
      return result;
    }

    public Embeddings embed(String wire, List<String> input) {
      throw new AssertionError("Unexpected model embedding");
    }

    public void close() {}
  }
}
