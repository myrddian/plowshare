package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.integrations.*;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.sdk.Plowshare;
import io.aeyer.plowshare.server.PlowshareServerApplication;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.auth.TokenStore;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
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
import org.testcontainers.junit.jupiter.*;

/**
 * Real core/harness/SDK/runtime/HA transport, with deterministic inference and simulated devices.
 */
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
  @Autowired DataLayout layout;
  @Autowired Model model;
  private Journal journal;

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
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("plowshare.data.dir", DATA::toString);
    registry.add("LLM_BASE_URL", () -> "http://127.0.0.1:1/v1");
    registry.add("SPARK_BASE_URL", () -> "http://127.0.0.1:1/v1");
  }

  private String installProject() throws Exception {
    String name = "house-" + UUID.randomUUID();
    String account = "ha-harness-owner";
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin) VALUES(?,'fixture',true) ON"
            + " CONFLICT DO NOTHING",
        account);
    Path workspace = Files.createDirectory(directory.resolve("workspace"));
    Path examples = Path.of(System.getProperty("integration.examples")).resolve("project");
    var manifest =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            Json.parse(Files.readString(examples.resolve("plowshare")));
    manifest.put("name", name);
    Files.writeString(workspace.resolve("plowshare"), manifest.toString());
    projects.define(name, workspace, List.of(), account);
    Long id = jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, name);
    try (var paths = Files.walk(examples)) {
      for (Path source : paths.toList()) {
        if (source.equals(examples.resolve("plowshare"))) continue;
        Path target = layout.agentsFor(id).getParent().resolve(examples.relativize(source));
        if (Files.isDirectory(source)) Files.createDirectories(target);
        else Files.copy(source, target);
      }
    }
    model.reset();
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
  void direct_question_uses_real_outgoing_tools_and_preserves_missing_evidence(String reading)
      throws Exception {
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
        var job =
            caller
                .request(
                    "agent.run",
                    Map.of(
                        "agent",
                        "house_coordinator",
                        "project",
                        project,
                        "task",
                        "What is the office temperature?",
                        "newConversation",
                        true))
                .requirePayload();
        JsonNode ended =
            drive(
                runtime,
                () ->
                    caller
                        .request("job.status", Map.of("job", job.path("id").asText()))
                        .requirePayload(),
                status -> status.path("outcome").isObject());
        assertTrue(ended.path("outcome").path("answered").asBoolean(), ended.toString());
        assertEquals(
            reading.equals("unavailable")
                ? "Office temperature is unavailable."
                : "Office temperature is 22 °C.",
            ended.path("outcome").path("text").asText());
        assertEquals(1, countWork(project));
        assertEquals(0, fixture.actions());
        assertEquals(3, model.calls.size());
        assertEquals(
            project,
            jdbc.queryForObject(
                "SELECT p.name FROM outgoing_work w JOIN projects p ON"
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
        assertEquals(1, countWork(project));
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
      }
    }
  }

  private IntegrationRuntime runtime(String project, MockWebServer ha, Plowshare sdk)
      throws Exception {
    return runtime(project, ha, sdk, false);
  }

  private IntegrationRuntime runtime(
      String project, MockWebServer ha, Plowshare sdk, boolean scripted) throws Exception {
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
            "ha-house",
            HomeAssistantAdapterTest.configuration(ha.url("/").toString()),
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
            "harness-fixture");
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
        java.time.Instant::now);
  }

  private int countWork(String project) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM outgoing_work x JOIN projects p ON p.id=x.project_id WHERE"
            + " p.name=?",
        Integer.class,
        project);
  }

  @FunctionalInterface
  interface Read {
    JsonNode get() throws Exception;
  }

  private JsonNode drive(
      IntegrationRuntime runtime, Read read, java.util.function.Predicate<JsonNode> done)
      throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    JsonNode status;
    do {
      runtime.tick();
      UUID work = model.work;
      if (work != null
          && jdbc.queryForObject("SELECT state FROM outgoing_work WHERE id=?", String.class, work)
              .equals("COMPLETED")) model.settled.complete(null);
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
    volatile CompletableFuture<Void> settled;
    final List<String> calls = new CopyOnWriteArrayList<>();

    void reset() {
      work = null;
      evidence = null;
      settled = new CompletableFuture<>();
      calls.clear();
    }

    public String poolName() {
      return "fixture";
    }

    public Completion complete(
        String wire, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      try {
        ChatMessage last =
            messages.stream()
                .filter(m -> m.role() == ChatMessage.Role.TOOL)
                .reduce((a, b) -> b)
                .orElse(null);
        boolean conductor = tools.stream().anyMatch(t -> t.name().equals("orchestration_finish"));
        if (last == null) return call("peers", "outgoing_peers", Json.object(), tools);
        return switch (last.toolCallId()) {
          case "peers" -> {
            assertTrue(Json.parse(last.content()).path("peers").toString().contains("ha-house"));
            yield call(
                "send",
                "outgoing_send",
                Json.object()
                    .put("requestId", UUID.randomUUID().toString())
                    .put("peer", "ha-house")
                    .set(
                        "message",
                        Json.parse(
                            """
{"parts":[{"data":{"schema":"plowshare-integration/1","binding":"house","operation":"states.read","arguments":{"entities":["office.temperature"]}}}]}
""")),
                tools);
          }
          case "send" -> {
            work = UUID.fromString(Json.parse(last.content()).path("id").asText());
            // Deterministic synchronization; no synthetic tool result or status is
            // supplied.
            settled.get(8, TimeUnit.SECONDS);
            yield call("read", "outgoing_read", Json.object().put("id", work.toString()), tools);
          }
          case "read" -> {
            var result = Json.parse(last.content());
            assertEquals("COMPLETED", result.path("state").asText());
            evidence = result.path("result").path("states").path("office.temperature");
            assertTrue(evidence.isObject(), result.toString());
            yield conductor ? call("todos", "todo_read", Json.object(), tools) : answer();
          }
          case "todos" -> {
            var ops = Json.MAPPER.createArrayNode();
            for (String line : last.content().split("\n")) {
              if (!line.contains("(stage)")) continue;
              var item =
                  java.util.regex.Pattern.compile("^\\[[^]]*]\\s+(\\S+)").matcher(line.strip());
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
