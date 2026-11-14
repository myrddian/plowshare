package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.integrations.Configuration;
import io.aeyer.plowshare.integrations.Gateway;
import io.aeyer.plowshare.integrations.IntegrationCodec;
import io.aeyer.plowshare.integrations.IntegrationRuntime;
import io.aeyer.plowshare.integrations.Journal;
import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.integrations.RelayRequests;
import io.aeyer.plowshare.integrations.ScriptHost;
import io.aeyer.plowshare.protocol.Orchestration;
import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.sdk.Plowshare;
import io.aeyer.plowshare.sdk.RelayClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Both external protocols, with durable fixture receipt identities and scripted core results. */
class PipelineTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(longs = {0, 5})
  void real_ha_event_js_pipeline_outbox_read_and_completion_notification(long holdSeconds)
      throws Exception {
    var clock = new AtomicReference<>(Instant.parse("2026-10-04T00:00:00Z"));
    try (MockWebServer ha = new MockWebServer();
        MockWebServer plowshare = new MockWebServer()) {
      var haFixture = new HomeAssistantAdapterTest.HaFixture();
      ha.enqueue(new MockResponse().withWebSocketUpgrade(haFixture));
      List<String> frames = new CopyOnWriteArrayList<>();
      Map<String, JsonNode> starts = new ConcurrentHashMap<>();
      Map<String, List<RelayLog.Event>> logs = new ConcurrentHashMap<>();
      String publisher = RelayPort.publisher("fixture-sdk");
      UUID requestGeneration = UUID.randomUUID(), returnGeneration = UUID.randomUUID();
      plowshare.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket ws, String text) {
                      try {
                        JsonNode frame = Json.parse(text), payload = frame.path("payload");
                        String type = frame.path("type").asText();
                        frames.add(type);
                        JsonNode result = Json.object();
                        switch (type) {
                          case "relay.log" -> {
                            String topic = payload.path("topic").asText();
                            var events = logs.getOrDefault(topic, List.of());
                            String after = payload.path("after").asText();
                            var rows =
                                events.stream()
                                    .filter(
                                        event ->
                                            Long.parseLong(event.position())
                                                > Long.parseLong(after))
                                    .limit(payload.path("limit").asInt())
                                    .toList();
                            String through = events.isEmpty() ? "0" : events.getLast().position();
                            result =
                                Json.MAPPER.valueToTree(
                                    new RelayLog.Page(
                                        new RelayLog.Scope("home", false),
                                        new RelayLog.Topic(
                                            topic,
                                            "TEXT",
                                            "3600",
                                            null,
                                            through,
                                            "0",
                                            (topic.equals("house.requests")
                                                    ? requestGeneration
                                                    : returnGeneration)
                                                .toString()),
                                        after,
                                        rows.isEmpty() ? after : rows.getLast().position(),
                                        null,
                                        rows,
                                        List.of(),
                                        List.of()));
                          }
                          case "relay.publish" -> {
                            String topic = payload.path("topic").asText();
                            var events =
                                logs.computeIfAbsent(
                                    topic, ignored -> new CopyOnWriteArrayList<>());
                            String position = Integer.toString(events.size() + 1);
                            String id = payload.path("requestId").asText();
                            Instant at = Instant.parse(payload.path("occurredAt").asText());
                            String correlation =
                                payload.hasNonNull("correlationId")
                                    ? payload.path("correlationId").asText()
                                    : null;
                            String parent =
                                payload.hasNonNull("parentEventId")
                                    ? payload.path("parentEventId").asText()
                                    : null;
                            events.add(
                                new RelayLog.Event(
                                    position,
                                    id,
                                    publisher,
                                    at,
                                    at,
                                    correlation,
                                    parent,
                                    new RelayLog.Payload(
                                        "TEXT", payload.path("text").asText(), null, null, null),
                                    RelayCausation.root(id)));
                            result =
                                Json.MAPPER.valueToTree(
                                    new RelayPort.Published(id, "home", topic, position, at));
                          }
                          case "orchestration.start" -> {
                            String id = payload.path("requestId").asText();
                            assertEquals("home", payload.path("project").asText());
                            assertEquals("investigate", payload.path("definition").asText());
                            result =
                                starts.computeIfAbsent(
                                    id,
                                    key ->
                                        Json.object()
                                            .put("id", "run-fixture")
                                            .put("requestId", key)
                                            .put("state", "running"));
                          }
                          case "orchestration.receipt" ->
                              result = starts.get(payload.path("requestId").asText());
                          case "orchestration.status" -> result = status();
                          default -> throw new AssertionError(type);
                        }
                        var reply = Json.object().put("protocol_version", "plowshare-v1");
                        reply.set("id", frame.path("id"));
                        reply.set("type", frame.path("type"));
                        reply.set(
                            "payload",
                            Json.object()
                                .put(
                                    "code",
                                    type.equals("relay.publish")
                                            || type.equals("orchestration.start")
                                        ? "ACCEPTED"
                                        : "OK")
                                .set("payload", result));
                        ws.send(reply.toString());
                      } catch (Exception failed) {
                        throw new AssertionError(failed);
                      }
                    }
                  }));
      var route =
          new Configuration.Route(
              "heat",
              "office.temperature",
              28.0,
              "°C",
              0,
              "investigate",
              "coordinator",
              "Explain the observations",
              "notify",
              holdSeconds);
      String script =
          """
export default {
  onEvent(e,c){if(e.causality)c.state.echoes=(c.state.echoes??0)+1;if(e.alias!=='office.temperature'||e.resync||Number(e.state)<=28)return [];return [c.startPipeline('heat',{evidence:e},{key:'heat'})]},
  onCompletion(r,c){if(r.state!=='completed')return [];return [c.executeAction('house','notify',{message:r.reportText},{key:'report'})]}
};
""";
      var binding =
          new Configuration.Binding(
              "house",
              "home-assistant",
              "home",
              new HomeAssistantFactory()
                  .decode(
                      HomeAssistantAdapterTest.configuration(ha.url("/").toString()).toString()),
              Map.of("heat", route),
              Set.of("house"),
              script,
              "fixture-config",
              Configuration.QueuePolicy.defaults(),
              new Configuration.FeedbackPolicy(true, 300, 3),
              new RelayRequests.Policy(
                  "house.requests",
                  requestGeneration,
                  "house.returns",
                  returnGeneration,
                  "house",
                  publisher,
                  Set.of(publisher),
                  8,
                  65536));
      var config =
          new Configuration(
              plowshare.url("/").toString(),
              "PLOWSHARE_TOKEN",
              directory,
              Map.of("house", binding));
      var adapter =
          new HomeAssistantAdapter(
              binding.configuration(), "fixture-ha-token", Duration.ofSeconds(3));
      try (Journal journal = new Journal(directory);
          var sdk =
              Plowshare.connect(
                  config.plowshare(),
                  "fixture-plowshare-token",
                  "legacy-ha-fixture",
                  Duration.ofSeconds(3),
                  null,
                  Plowshare.TransportMode.LEGACY,
                  Plowshare.DispatchMode.ORDERED);
          var runtime =
              new IntegrationRuntime(
                  config,
                  Map.of("house", adapter),
                  new Gateway.Sdk(sdk),
                  journal,
                  new ScriptHost(Duration.ofSeconds(5)),
                  clock::get)) {
        runtime.start();
        runtime.tick();
        haFixture.event("30", "2026-10-04T00:02:00Z");
        long end = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (journal.entries().stream()
                    .map(e -> Map.entry(e.getKey(), IntegrationCodec.tree(e.getValue())))
                    .filter(e -> e.getValue().path("kind").asText().equals("handler"))
                    .count()
                < 2
            && System.nanoTime() < end) Thread.sleep(10);
        runtime.tick();
        if (holdSeconds > 0) {
          assertTrue(starts.isEmpty());
          clock.set(clock.get().plusSeconds(holdSeconds));
        }
        runtime.tick();
        runtime.tick();
        assertEquals(1, starts.size());
        assertEquals(1, haFixture.actions());
        haFixture.event("20", "2026-10-04T00:03:00Z");
        awaitReading(adapter, "20");
        runtime.tick();
        haFixture.event("30", "2026-10-04T00:04:00Z", "child-context", "action-context");
        awaitReading(adapter, "30");
        runtime.tick();
        clock.set(clock.get().plusSeconds(holdSeconds));
        runtime.tick();
        assertEquals(1, starts.size(), "acknowledged-action echo cannot start another run");
        assertEquals(1, haFixture.actions());
        assertEquals(
            1,
            IntegrationCodec.tree(journal.bindingState("house"))
                .path("script")
                .path("echoes")
                .asInt(),
            "verification observation remains available to JS");
        haFixture.event("20", "2026-10-04T00:05:00Z");
        awaitReading(adapter, "20");
        runtime.tick();
        haFixture.event("30", "2026-10-04T00:06:00Z", "grandchild-context", "child-context");
        awaitReading(adapter, "30");
        runtime.tick();
        clock.set(clock.get().plusSeconds(holdSeconds));
        runtime.tick();
        assertEquals(1, starts.size(), "observed automation ancestry cannot start another run");
        assertEquals(1, haFixture.actions());
        assertEquals(
            2,
            IntegrationCodec.tree(journal.bindingState("house"))
                .path("script")
                .path("echoes")
                .asInt());
        var grandchild =
            journal.entries().stream()
                .map(e -> Map.entry(e.getKey(), IntegrationCodec.tree(e.getValue())))
                .map(Map.Entry::getValue)
                .filter(
                    r ->
                        r.path("event")
                            .path("context")
                            .path("id")
                            .asText()
                            .equals("grandchild-context"))
                .findFirst()
                .orElseThrow();
        assertEquals(2, grandchild.path("causality").path("depth").asInt());
        assertEquals("action-context", grandchild.path("causality").path("rootContext").asText());
        RelayClient requests = new RelayClient(sdk);
        var read =
            request(
                requests, clock.get(), "states.read", "{\"entities\":[\"office.temperature\"]}");
        runtime.tick();
        assertTrue(runtime.relayAttention().isEmpty(), () -> runtime.relayAttention().toString());
        var readResult = outcome(requests, read);
        assertEquals("COMPLETED", readResult.path("state").asText());
        assertTrue(readResult.path("result").path("states").has("office.temperature"));
        assertEquals(1, haFixture.actions());
        var action =
            request(
                requests,
                clock.get(),
                "actions.execute",
                "{\"action\":\"notify\",\"parameters\":{\"message\":\"Selected action\"}}");
        runtime.tick();
        assertTrue(runtime.relayAttention().isEmpty(), () -> runtime.relayAttention().toString());
        assertEquals("COMPLETED", outcome(requests, action).path("state").asText());
        assertEquals(2, haFixture.actions());
        var denied =
            request(
                requests,
                clock.get(),
                "actions.execute",
                "{\"action\":\"notify\",\"parameters\":{\"message\":\"Denied action\",\"entity_id\":\"light.foreign\"}}");
        runtime.tick();
        assertTrue(runtime.relayAttention().isEmpty(), () -> runtime.relayAttention().toString());
        assertEquals("REJECTED", outcome(requests, denied).path("state").asText());
        assertEquals(2, haFixture.actions());
        assertTrue(
            frames.containsAll(
                List.of(
                    "orchestration.start", "orchestration.status", "relay.log", "relay.publish")));
      }
    }
  }

  private static UUID request(
      RelayClient sdk, Instant occurredAt, String operation, String arguments) throws Exception {
    UUID id = UUID.randomUUID();
    String text =
        "{\"schema\":\"plowshare-integration/1\",\"binding\":\"house\",\"operation\":\""
            + operation
            + "\",\"arguments\":"
            + arguments
            + "}";
    sdk.publish(
        new RelayPort.Publish(
            id.toString(), "home", "house.requests", text, occurredAt, null, null, null));
    return id;
  }

  private static JsonNode outcome(RelayClient sdk, UUID source) throws Exception {
    for (var event :
        sdk.log(new RelayLog.Query("home", false, "house.returns", "0", 100)).events()) {
      var payload = Json.parse(event.payload().text());
      if (payload.path("sourceEvent").asText().equals(source.toString()))
        return Json.parse(payload.path("body").asText());
    }
    throw new AssertionError("No correlated integration result");
  }

  private static void awaitReading(HomeAssistantAdapter adapter, String value) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(3).toNanos();
    while (!IntegrationCodec.tree(adapter.snapshot())
            .path("office.temperature")
            .path("state")
            .asText()
            .equals(value)
        && System.nanoTime() < end) Thread.sleep(10);
    assertEquals(
        value,
        IntegrationCodec.tree(adapter.snapshot())
            .path("office.temperature")
            .path("state")
            .asText());
  }

  private static JsonNode status() {
    var run =
        new Orchestration.RunView(
            "run-fixture",
            "fixture",
            "shipped",
            "home",
            "finished",
            null,
            "Temperature exceeds the configured threshold.",
            null,
            0,
            1,
            0,
            0,
            null,
            null,
            "cnv_fixture",
            null,
            0,
            null,
            Instant.parse("2026-10-04T00:00:00Z"),
            null,
            null);
    return Json.MAPPER.valueToTree(new Orchestration.Status(run, List.of(), List.of(), List.of()));
  }
}
