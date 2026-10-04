package io.aeyer.plowshare.integrations;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.sdk.Plowshare;
import java.time.Duration;
import java.util.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;

class GatewayTest {
  @Test
  void real_sdk_uses_existing_ws_frames_and_preserves_receipt_absence() throws Exception {
    List<String> frames = new java.util.concurrent.CopyOnWriteArrayList<>();
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket socket, String text) {
                      try {
                        var frame = Json.parse(text);
                        String type = frame.path("type").asText();
                        frames.add(type);
                        var response = Json.object();
                        response.set("id", frame.path("id"));
                        response.set("type", frame.path("type"));
                        response.put("protocol_version", "plowshare-v1");
                        var outcome = Json.object();
                        outcome.put(
                            "code", type.equals("orchestration.receipt") ? "BAD_REQUEST" : "OK");
                        if (type.equals("orchestration.receipt"))
                          outcome.put(
                              "said",
                              "No orchestration start receipt is" + " owned by this account");
                        outcome.set(
                            "payload",
                            Json.object()
                                .put("id", "run-fixture")
                                .put(
                                    "requestId", frame.path("payload").path("requestId").asText()));
                        response.set("payload", outcome);
                        socket.send(response.toString());
                      } catch (Exception error) {
                        throw new AssertionError(error);
                      }
                    }
                  }));
      try (Gateway gateway =
          new Gateway.Sdk(
              Plowshare.connect(
                  server.url("/").toString(), "fixture-token", Duration.ofSeconds(3), null))) {
        gateway.advertise("home", List.of("ha-house"));
        assertNull(gateway.receipt(UUID.randomUUID()));
        assertEquals(
            "run-fixture",
            gateway
                .start(
                    Map.of(
                        "project",
                        "home",
                        "agent",
                        "coordinator",
                        "definition",
                        "investigate",
                        "request",
                        "Explain",
                        "requestId",
                        UUID.randomUUID().toString()))
                .path("id")
                .asText());
        gateway.status("run-fixture");
      }
      assertEquals(
          List.of(
              "outgoing.advertise",
              "orchestration.receipt",
              "orchestration.start",
              "orchestration.status"),
          frames);
      assertEquals(
          "/v1/events", Objects.requireNonNull(server.takeRequest()).getRequestUrl().encodedPath());
    }
  }
}
