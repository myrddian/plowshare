package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.integrations.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;

class HomeAssistantAdapterTest {
  static JsonNode configuration(String endpoint) throws Exception {
    return Json.parse(
        """
{"endpoint":"ENDPOINT","tokenEnv":"HA_TOKEN",
 "entities":{"office.temperature":{"entity":"sensor.office_temperature","attributes":[]}},
 "subscriptions":["office.temperature"],
 "actions":{"notify":{"service":"notify.fixture_phone","targets":[],"parameters":{"message":{"type":"string","maxLength":100}},"required":["message"]}}}
"""
            .replace("ENDPOINT", endpoint));
  }

  static JsonNode state(String temperature, String timestamp) throws Exception {
    return Json.parse(
        """
{"entity_id":"sensor.office_temperature","state":"TEMPERATURE","attributes":{"unit_of_measurement":"°C","private":"not-for-model"},"last_updated":"TIME","last_changed":"TIME","context":{"id":"context-fixture","user_id":"private-user"}}
"""
            .replace("TEMPERATURE", temperature)
            .replace("TIME", timestamp));
  }

  static final class HaFixture extends WebSocketListener {
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    volatile WebSocket socket;
    boolean loseAction;
    boolean secondSelected, badSelected, badAcknowledgment;
    boolean bufferedDelta;
    boolean authRefused, missingState, loseRead;
    String reading = "22";

    @Override
    public void onOpen(WebSocket socket, Response response) {
      this.socket = socket;
      socket.send("{\"type\":\"auth_required\"}");
    }

    @Override
    public void onMessage(WebSocket socket, String text) {
      try {
        JsonNode request = Json.parse(text);
        requests.add(request);
        if (request.path("type").asText().equals("auth")) {
          if (authRefused) {
            socket.send("{\"type\":\"auth_invalid\",\"message\":\"private-auth-detail\"}");
            return;
          }
          socket.send("{\"type\":\"auth_ok\"}");
          return;
        }
        JsonNode result = Json.object();
        if (request.path("type").asText().equals("get_states")) {
          if (loseRead) return;
          if (bufferedDelta) event("31", "2026-10-04T00:02:00Z");
          result =
              Json.MAPPER
                  .createArrayNode()
                  .add(state(reading, "2026-10-04T00:00:00Z"))
                  .add(Json.object().put("entity_id", "sensor.private").put("state", "private"));
          if (secondSelected) {
            var second =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                    state("22", "2026-10-04T00:00:00Z");
            second.put("entity_id", "sensor.office_other");
            if (badSelected)
              ((com.fasterxml.jackson.databind.node.ObjectNode) second.path("attributes"))
                  .set("temperature", Json.object().put("private", "private-fixture"));
            ((com.fasterxml.jackson.databind.node.ArrayNode) result).add(second);
          }
          if (missingState) result = Json.MAPPER.createArrayNode();
        }
        if (request.path("type").asText().equals("call_service")) {
          if (loseAction) return;
          result =
              Json.object()
                  .set(
                      "context",
                      badAcknowledgment
                          ? Json.object().put("id", 12).put("private", "private-fixture")
                          : Json.object().put("id", "action-context"));
        }
        var reply = Json.object().put("type", "result").put("success", true);
        reply.set("id", request.path("id"));
        reply.set("result", result);
        socket.send(reply.toString());
      } catch (Exception failed) {
        throw new AssertionError(failed);
      }
    }

    void event(String temperature, String time) throws Exception {
      event(temperature, time, "context-fixture", null);
    }

    void event(String temperature, String time, String context, String parent) throws Exception {
      var data = Json.object().put("entity_id", "sensor.office_temperature");
      var value = (com.fasterxml.jackson.databind.node.ObjectNode) state(temperature, time);
      var causal = Json.object().put("id", context);
      if (parent != null) causal.put("parent_id", parent);
      value.set("context", causal);
      data.set("new_state", value);
      socket.send(
          Json.object()
              .put("type", "event")
              .put("id", 1)
              .set("event", Json.object().set("data", data))
              .toString());
    }

    long actions() {
      return requests.stream().filter(n -> n.path("type").asText().equals("call_service")).count();
    }
  }

  @Test
  void authentication_failure_is_sanitized_and_no_commands_are_sent() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      var fixture = new HaFixture();
      fixture.authRefused = true;
      server.enqueue(new MockResponse().withWebSocketUpgrade(fixture));
      try (var adapter =
          new HomeAssistantAdapter(
              new HomeAssistantFactory()
                  .decode(configuration(server.url("/").toString()).toString()),
              "fixture-secret",
              Duration.ofSeconds(2))) {
        var failed = assertThrows(java.io.IOException.class, () -> adapter.start(event -> {}));
        assertFalse(failed.toString().contains("fixture-secret"));
        assertFalse(failed.toString().contains("private-auth-detail"));
        assertEquals(1, fixture.requests.size());
      }
    }
  }

  @Test
  void missing_and_unavailable_entities_remain_explicit_and_malformed_frames_mark_a_gap()
      throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      var fixture = new HaFixture();
      fixture.reading = "unavailable";
      server.enqueue(new MockResponse().withWebSocketUpgrade(fixture));
      CountDownLatch gap = new CountDownLatch(1);
      try (var adapter =
          new HomeAssistantAdapter(
              new HomeAssistantFactory()
                  .decode(configuration(server.url("/").toString()).toString()),
              "fixture-token",
              Duration.ofSeconds(2))) {
        adapter.start(
            event -> {
              if (IntegrationCodec.tree(event.data())
                  .path("type")
                  .asText()
                  .equals("connection.gap")) gap.countDown();
            });
        assertEquals(
            "unavailable",
            IntegrationCodec.tree(adapter.snapshot())
                .path("office.temperature")
                .path("availability")
                .asText());
        fixture.missingState = true;
        var result =
            adapter.execute(
                "states.read",
                IntegrationCodec.arguments(Json.parse("{\"entities\":[\"office.temperature\"]}")));
        assertEquals(
            "missing",
            IntegrationCodec.tree(result.data())
                .path("states")
                .path("office.temperature")
                .path("availability")
                .asText());
        fixture.loseRead = true;
        var failedRead =
            adapter.execute(
                "states.read",
                IntegrationCodec.arguments(Json.parse("{\"entities\":[\"office.temperature\"]}")));
        assertEquals("FAILED", failedRead.state());
        assertFalse(IntegrationCodec.tree(failedRead.data()).has("states"));
        fixture.socket.send("{");
        assertTrue(gap.await(2, TimeUnit.SECONDS));
        assertEquals(
            "stale",
            IntegrationCodec.tree(adapter.snapshot())
                .path("office.temperature")
                .path("availability")
                .asText());
      }
    }
  }

  @Test
  void auth_selected_state_reads_and_permission_checks_use_real_ws() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      HaFixture fixture = new HaFixture();
      server.enqueue(new MockResponse().withWebSocketUpgrade(fixture));
      try (HomeAssistantAdapter adapter =
          new HomeAssistantAdapter(
              new HomeAssistantFactory()
                  .decode(configuration(server.url("/").toString()).toString()),
              "fixture-token",
              Duration.ofSeconds(3))) {
        adapter.start(event -> {});
        var result =
            adapter.execute(
                "states.read",
                IntegrationCodec.arguments(Json.parse("{\"entities\":[\"office.temperature\"]}")));
        assertEquals("COMPLETED", result.state());
        assertEquals(1, IntegrationCodec.tree(result.data()).path("states").size());
        assertEquals(
            "22",
            IntegrationCodec.tree(result.data())
                .path("states")
                .path("office.temperature")
                .path("state")
                .asText());
        assertFalse(IntegrationCodec.tree(result.data()).toString().contains("not-for-model"));
        assertFalse(IntegrationCodec.tree(result.data()).toString().contains("private-user"));
        assertThrows(
            IllegalArgumentException.class,
            () ->
                adapter.execute(
                    "states.read",
                    IntegrationCodec.arguments(Json.parse("{\"entities\":[\"sensor.private\"]}"))));
        assertThrows(
            IllegalArgumentException.class,
            () ->
                adapter.execute(
                    "actions.execute",
                    IntegrationCodec.arguments(
                        Json.parse(
                            "{\"action\":\"notify\",\"parameters\":{\"message\":\"ok\",\"entity_id\":\"light.other\"}}"))));
        assertEquals(0, fixture.actions());
        assertEquals(
            "COMPLETED",
            adapter
                .execute(
                    "actions.execute",
                    IntegrationCodec.arguments(
                        Json.parse(
                            "{\"action\":\"notify\",\"parameters\":{\"message\":\"Report\"}}")))
                .state());
        assertEquals(1, fixture.actions());
        assertEquals("fixture-token", fixture.requests.getFirst().path("access_token").asText());
        var upgrade = server.takeRequest();
        assertEquals("/api/websocket", upgrade.getRequestUrl().encodedPath());
        assertNull(upgrade.getHeader("Authorization"));
      }
    }
  }

  @Test
  void buffered_delta_newer_than_snapshot_wins_without_launching_historical_edges()
      throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      HaFixture fixture = new HaFixture();
      fixture.bufferedDelta = true;
      server.enqueue(new MockResponse().withWebSocketUpgrade(fixture));
      List<IntegrationAdapter.Observation> events = new CopyOnWriteArrayList<>();
      try (HomeAssistantAdapter adapter =
          new HomeAssistantAdapter(
              new HomeAssistantFactory()
                  .decode(configuration(server.url("/").toString()).toString()),
              "fixture-token",
              Duration.ofSeconds(3))) {
        adapter.start(events::add);
        assertEquals(
            "31",
            IntegrationCodec.tree(adapter.snapshot())
                .path("office.temperature")
                .path("state")
                .asText());
        assertEquals(1, events.size());
        assertTrue(events.getLast().data().resync());
        assertEquals(
            "31",
            adapter
                .execute(
                    "states.read",
                    IntegrationCodec.arguments(
                        Json.parse("{\"entities\":[\"office.temperature\"]}")))
                .data()
                .states()
                .get("office.temperature")
                .state());
      }
    }
  }

  @Test
  void action_acknowledgment_loss_is_unknown_and_never_replayed() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      HaFixture fixture = new HaFixture();
      fixture.loseAction = true;
      server.enqueue(new MockResponse().withWebSocketUpgrade(fixture));
      try (HomeAssistantAdapter adapter =
          new HomeAssistantAdapter(
              new HomeAssistantFactory()
                  .decode(configuration(server.url("/").toString()).toString()),
              "fixture-token",
              Duration.ofMillis(300))) {
        adapter.start(event -> {});
        var result =
            adapter.execute(
                "actions.execute",
                IntegrationCodec.arguments(
                    Json.parse("{\"action\":\"notify\",\"parameters\":{\"message\":\"Report\"}}")));
        assertEquals("UNKNOWN", result.state());
        assertEquals(1, fixture.actions());
        adapter.maintain();
        assertEquals(1, fixture.actions());
      }
    }
  }

  @Test
  void disconnect_marks_cached_states_stale_and_reconnect_refreshes_snapshot() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      HaFixture first = new HaFixture(), second = new HaFixture();
      server.enqueue(new MockResponse().withWebSocketUpgrade(first));
      server.enqueue(new MockResponse().withWebSocketUpgrade(second));
      List<IntegrationAdapter.Observation> events = new CopyOnWriteArrayList<>();
      try (HomeAssistantAdapter adapter =
          new HomeAssistantAdapter(
              new HomeAssistantFactory()
                  .decode(configuration(server.url("/").toString()).toString()),
              "fixture-token",
              Duration.ofSeconds(3))) {
        adapter.start(events::add);
        String epoch =
            IntegrationCodec.tree(adapter.snapshot())
                .path("office.temperature")
                .path("epoch")
                .asText();
        first.socket.close(1000, "fixture close");
        long end = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (events.stream()
                .noneMatch(
                    e ->
                        IntegrationCodec.tree(e.data())
                            .path("type")
                            .asText()
                            .equals("connection.gap"))
            && System.nanoTime() < end) Thread.sleep(10);
        assertEquals(
            "stale",
            IntegrationCodec.tree(adapter.snapshot())
                .path("office.temperature")
                .path("availability")
                .asText());
        while (IntegrationCodec.tree(adapter.snapshot())
                .path("office.temperature")
                .path("epoch")
                .asText()
                .equals(epoch)
            && System.nanoTime() < end) {
          adapter.maintain();
          Thread.sleep(20);
        }
        assertNotEquals(
            epoch,
            IntegrationCodec.tree(adapter.snapshot())
                .path("office.temperature")
                .path("epoch")
                .asText());
        assertEquals(
            "available",
            IntegrationCodec.tree(adapter.snapshot())
                .path("office.temperature")
                .path("availability")
                .asText());
      }
    }
  }

  @Test
  void unregistered_service_response_configuration_is_refused_before_connecting_or_submitting()
      throws Exception {
    try (var server = new MockWebServer()) {
      var config = configuration(server.url("/").toString());
      ((com.fasterxml.jackson.databind.node.ObjectNode) config.path("actions").path("notify"))
          .put("returnResponse", true);
      var refusal =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  new HomeAssistantAdapter(
                      new HomeAssistantFactory().decode(config.toString()),
                      "fixture",
                      Duration.ofSeconds(2)));
      assertTrue(refusal.getMessage().contains("registered service-response DTO"));
      assertEquals(0, server.getRequestCount());
    }
  }

  @Test
  void a_malformed_selected_state_never_publishes_a_partial_fresh_batch() throws Exception {
    try (var server = new MockWebServer()) {
      var fixture = new HaFixture();
      fixture.secondSelected = true;
      server.enqueue(new MockResponse().withWebSocketUpgrade(fixture));
      var config =
          (com.fasterxml.jackson.databind.node.ObjectNode)
              configuration(server.url("/").toString());
      ((com.fasterxml.jackson.databind.node.ObjectNode) config.path("entities"))
          .set(
              "office.other",
              Json.object()
                  .put("entity", "sensor.office_other")
                  .set("attributes", Json.MAPPER.createArrayNode().add("temperature")));
      try (var adapter =
          new HomeAssistantAdapter(
              new HomeAssistantFactory().decode(config.toString()),
              "fixture-token",
              Duration.ofSeconds(2))) {
        adapter.start(ignored -> {});
        var before = adapter.snapshot();
        fixture.reading = "35";
        fixture.badSelected = true;
        var result =
            adapter.execute(
                "states.read",
                new io.aeyer.plowshare.protocol.IntegrationPayload.Arguments(
                    java.util.List.of("office.temperature", "office.other"), null, null));
        assertEquals("FAILED", result.state());
        assertNull(result.data().states());
        assertEquals(before, adapter.snapshot());
        assertFalse(result.data().toString().contains("private-fixture"));
      }
    }
  }

  @Test
  void malformed_action_acknowledgment_is_unknown_and_is_never_replayed() throws Exception {
    try (var server = new MockWebServer()) {
      var fixture = new HaFixture();
      fixture.badAcknowledgment = true;
      server.enqueue(new MockResponse().withWebSocketUpgrade(fixture));
      try (var adapter =
          new HomeAssistantAdapter(
              new HomeAssistantFactory()
                  .decode(configuration(server.url("/").toString()).toString()),
              "fixture-token",
              Duration.ofSeconds(2))) {
        adapter.start(ignored -> {});
        var result =
            adapter.execute(
                "actions.execute",
                new io.aeyer.plowshare.protocol.IntegrationPayload.Arguments(
                    null,
                    "notify",
                    java.util.Map.of(
                        "message",
                        new io.aeyer.plowshare.protocol.IntegrationPayload.TextParameter(
                            "report"))));
        assertEquals("UNKNOWN", result.state());
        assertNull(result.data().context());
        assertEquals(1, fixture.actions());
        adapter.maintain();
        assertEquals(1, fixture.actions());
        assertFalse(result.data().toString().contains("private-fixture"));
      }
    }
  }

  @Test
  void wrong_configuration_types_and_nested_enum_values_are_refused_without_transport_io()
      throws Exception {
    try (var server = new MockWebServer()) {
      String valid = configuration(server.url("/").toString()).toString();
      for (String invalid :
          java.util.List.of(
              valid.replace("\"tokenEnv\":\"HA_TOKEN\"", "\"tokenEnv\":12"),
              valid.replace("\"maxLength\":100", "\"maxLength\":\"100\""),
              valid.replace("\"maxLength\":100", "\"enum\":[12]")))
        assertThrows(
            IllegalArgumentException.class, () -> new HomeAssistantFactory().decode(invalid));
      assertEquals(0, server.getRequestCount());
    }
  }
}
