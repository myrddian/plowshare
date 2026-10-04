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
          if (missingState) result = Json.MAPPER.createArrayNode();
        }
        if (request.path("type").asText().equals("call_service")) {
          if (loseAction) return;
          result = Json.object().set("context", Json.object().put("id", "action-context"));
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
              configuration(server.url("/").toString()), "fixture-secret", Duration.ofSeconds(2))) {
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
              configuration(server.url("/").toString()), "fixture-token", Duration.ofSeconds(2))) {
        adapter.start(
            event -> {
              if (event.data().path("type").asText().equals("connection.gap")) gap.countDown();
            });
        assertEquals(
            "unavailable",
            adapter.snapshot().path("office.temperature").path("availability").asText());
        fixture.missingState = true;
        var result =
            adapter.execute("states.read", Json.parse("{\"entities\":[\"office.temperature\"]}"));
        assertEquals(
            "missing",
            result.data().path("states").path("office.temperature").path("availability").asText());
        fixture.loseRead = true;
        var failedRead =
            adapter.execute("states.read", Json.parse("{\"entities\":[\"office.temperature\"]}"));
        assertEquals("FAILED", failedRead.state());
        assertFalse(failedRead.data().has("states"));
        fixture.socket.send("{");
        assertTrue(gap.await(2, TimeUnit.SECONDS));
        assertEquals(
            "stale", adapter.snapshot().path("office.temperature").path("availability").asText());
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
              configuration(server.url("/").toString()), "fixture-token", Duration.ofSeconds(3))) {
        adapter.start(event -> {});
        var result =
            adapter.execute("states.read", Json.parse("{\"entities\":[\"office.temperature\"]}"));
        assertEquals("COMPLETED", result.state());
        assertEquals(1, result.data().path("states").size());
        assertEquals(
            "22", result.data().path("states").path("office.temperature").path("state").asText());
        assertFalse(result.data().toString().contains("not-for-model"));
        assertFalse(result.data().toString().contains("private-user"));
        assertThrows(
            IllegalArgumentException.class,
            () ->
                adapter.execute("states.read", Json.parse("{\"entities\":[\"sensor.private\"]}")));
        assertThrows(
            IllegalArgumentException.class,
            () ->
                adapter.execute(
                    "actions.execute",
                    Json.parse(
                        "{\"action\":\"notify\",\"parameters\":{\"message\":\"ok\",\"entity_id\":\"light.other\"}}")));
        assertEquals(0, fixture.actions());
        assertEquals(
            "COMPLETED",
            adapter
                .execute(
                    "actions.execute",
                    Json.parse("{\"action\":\"notify\",\"parameters\":{\"message\":\"Report\"}}"))
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
              configuration(server.url("/").toString()), "fixture-token", Duration.ofSeconds(3))) {
        adapter.start(events::add);
        assertEquals("31", adapter.snapshot().path("office.temperature").path("state").asText());
        assertEquals(1, events.size());
        assertTrue(events.getLast().data().path("resync").asBoolean());
        assertEquals(
            "31",
            adapter
                .execute("states.read", Json.parse("{\"entities\":[\"office.temperature\"]}"))
                .data()
                .path("states")
                .path("office.temperature")
                .path("state")
                .asText());
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
              configuration(server.url("/").toString()), "fixture-token", Duration.ofMillis(300))) {
        adapter.start(event -> {});
        var result =
            adapter.execute(
                "actions.execute",
                Json.parse("{\"action\":\"notify\",\"parameters\":{\"message\":\"Report\"}}"));
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
              configuration(server.url("/").toString()), "fixture-token", Duration.ofSeconds(3))) {
        adapter.start(events::add);
        String epoch = adapter.snapshot().path("office.temperature").path("epoch").asText();
        first.socket.close(1000, "fixture close");
        long end = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (events.stream()
                .noneMatch(e -> e.data().path("type").asText().equals("connection.gap"))
            && System.nanoTime() < end) Thread.sleep(10);
        assertEquals(
            "stale", adapter.snapshot().path("office.temperature").path("availability").asText());
        while (adapter.snapshot().path("office.temperature").path("epoch").asText().equals(epoch)
            && System.nanoTime() < end) {
          adapter.maintain();
          Thread.sleep(20);
        }
        assertNotEquals(
            epoch, adapter.snapshot().path("office.temperature").path("epoch").asText());
        assertEquals(
            "available",
            adapter.snapshot().path("office.temperature").path("availability").asText());
      }
    }
  }
}
