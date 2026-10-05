package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.integrations.*;
import io.aeyer.plowshare.protocol.Outgoing;
import io.aeyer.plowshare.sdk.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
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
    var clock =
        new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-10-04T00:00:00Z"));
    try (MockWebServer ha = new MockWebServer();
        MockWebServer plowshare = new MockWebServer()) {
      var haFixture = new HomeAssistantAdapterTest.HaFixture();
      ha.enqueue(new MockResponse().withWebSocketUpgrade(haFixture));
      List<String> frames = new CopyOnWriteArrayList<>();
      Map<String, JsonNode> starts = new ConcurrentHashMap<>();
      var outbox = new java.util.concurrent.atomic.AtomicReference<Outgoing.Work>();
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
                          case "outgoing.advertise" -> {}
                          case "outgoing.peers" ->
                              result =
                                  Json.object()
                                      .set("peers", Json.MAPPER.createArrayNode().add("ha-house"));
                          case "outgoing.send" -> {
                            Outgoing.Work work =
                                new Outgoing.Work(
                                    UUID.randomUUID(),
                                    UUID.fromString(payload.path("requestId").asText()),
                                    "ha-house",
                                    "home",
                                    null,
                                    io.aeyer.plowshare.sdk.ExternalPayloadCodec.message(
                                        payload.path("message").toString()),
                                    "QUEUED",
                                    false,
                                    null,
                                    null,
                                    null,
                                    null,
                                    0,
                                    Instant.now());
                            outbox.set(work);
                            result = Json.MAPPER.valueToTree(work);
                          }
                          case "outgoing.claim" -> {
                            var old = outbox.get();
                            if (old == null || !old.state().equals("QUEUED"))
                              result = Json.object().putNull("work").putNull("action");
                            else {
                              var work =
                                  new Outgoing.Work(
                                      old.id(),
                                      old.requestId(),
                                      old.peer(),
                                      old.project(),
                                      null,
                                      old.message(),
                                      "DISPATCHED",
                                      false,
                                      null,
                                      null,
                                      null,
                                      null,
                                      1,
                                      old.createdAt());
                              outbox.set(work);
                              result = Json.MAPPER.valueToTree(new Outgoing.Claimed(work, "send"));
                            }
                          }
                          case "outgoing.report" -> {
                            var old = outbox.get();
                            assertEquals(1, payload.path("revision").asInt());
                            var work =
                                new Outgoing.Work(
                                    old.id(),
                                    old.requestId(),
                                    old.peer(),
                                    old.project(),
                                    null,
                                    old.message(),
                                    payload.path("state").asText(),
                                    false,
                                    null,
                                    null,
                                    io.aeyer.plowshare.sdk.ExternalPayloadCodec.result(
                                        payload.path("result").toString()),
                                    null,
                                    2,
                                    old.createdAt());
                            outbox.set(work);
                            result = Json.MAPPER.valueToTree(work);
                          }
                          case "outgoing.status" -> result = Json.MAPPER.valueToTree(outbox.get());
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
                                    type.equals("outgoing.send")
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
              "ha-house",
              new HomeAssistantFactory()
                  .decode(
                      HomeAssistantAdapterTest.configuration(ha.url("/").toString()).toString()),
              Map.of("heat", route),
              Set.of("house"),
              script,
              "fixture-config",
              Configuration.QueuePolicy.defaults(),
              new Configuration.FeedbackPolicy(true, 300, 3));
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
                  config.plowshare(), "fixture-plowshare-token", Duration.ofSeconds(3), null);
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
        OutgoingClient requests = new OutgoingClient(sdk);
        var message =
            io.aeyer.plowshare.sdk.ExternalPayloadCodec.message(
                "{\"parts\":[{\"data\":{\"schema\":\"plowshare-integration/1\",\"binding\":\"house\",\"operation\":\"states.read\",\"arguments\":{\"entities\":[\"office.temperature\"]}}}]}");
        var receipt =
            requests.send(new Outgoing.Send(UUID.randomUUID(), "ha-house", message, "home", null));
        runtime.tick();
        var read = requests.status(receipt.id());
        assertEquals("COMPLETED", read.state());
        assertTrue(
            read.result()
                    instanceof io.aeyer.plowshare.protocol.ExternalResult.IntegrationResult result
                && result.states() != null);
        assertEquals(1, haFixture.actions());
        var selectedAction =
            io.aeyer.plowshare.sdk.ExternalPayloadCodec.message(
                """
{"parts":[{"data":{"schema":"plowshare-integration/1","binding":"house","operation":"actions.execute","arguments":{"action":"notify","parameters":{"message":"Selected action"}}}}]}
""");
        var action =
            requests.send(
                new Outgoing.Send(UUID.randomUUID(), "ha-house", selectedAction, "home", null));
        runtime.tick();
        assertEquals("COMPLETED", requests.status(action.id()).state());
        assertEquals(2, haFixture.actions());
        var deniedAction =
            io.aeyer.plowshare.sdk.ExternalPayloadCodec.message(
                """
{"parts":[{"data":{"schema":"plowshare-integration/1","binding":"house","operation":"actions.execute","arguments":{"action":"notify","parameters":{"message":"Denied action","entity_id":"light.foreign"}}}}]}
""");
        var denied =
            requests.send(
                new Outgoing.Send(UUID.randomUUID(), "ha-house", deniedAction, "home", null));
        runtime.tick();
        assertEquals("REJECTED", requests.status(denied.id()).state());
        assertEquals(2, haFixture.actions());
        assertTrue(
            frames.containsAll(
                List.of(
                    "orchestration.start",
                    "orchestration.status",
                    "outgoing.claim",
                    "outgoing.report")));
      }
    }
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
        new io.aeyer.plowshare.protocol.Orchestration.RunView(
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
            java.time.Instant.parse("2026-10-04T00:00:00Z"),
            null,
            null);
    return Json.MAPPER.valueToTree(
        new io.aeyer.plowshare.protocol.Orchestration.Status(
            run, java.util.List.of(), java.util.List.of(), java.util.List.of()));
  }
}
