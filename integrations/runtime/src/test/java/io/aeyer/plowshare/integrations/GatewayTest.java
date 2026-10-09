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
                                .put("state", "running")
                                .put(
                                    "requestId", frame.path("payload").path("requestId").asText()));
                        if (type.equals("orchestration.status")) {
                          outcome.set(
                              "payload",
                              Json.parse(
                                  """
                              {"orchestration":{"id":"run-fixture","definition":"investigate","tier":"project","project":"home","state":"running","returnsUsed":0,"maxReturns":2,"nudges":0,"restarts":0,"depth":0,"conductorConversation":"cnv_fixture","createdAt":"2026-10-04T00:00:00Z"},"todos":[],"messages":[],"children":[]}
                              """));
                        }
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
                  server.url("/").toString(),
                  "fixture-token",
                  "legacy-gateway-fixture",
                  Duration.ofSeconds(3),
                  null,
                  Plowshare.TransportMode.LEGACY))) {
        gateway.advertise("home", List.of("ha-house"));
        assertTrue(gateway.receipt(UUID.randomUUID()).isEmpty());
        assertEquals(
            "run-fixture",
            gateway
                .start(
                    new io.aeyer.plowshare.protocol.Orchestration.Start(
                        "coordinator", "investigate", "Explain", null, "home", UUID.randomUUID()))
                .id());
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
