package io.aeyer.plowshare.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Orchestration;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.sdk.Plowshare;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class GatewayTest {
  @Test
  void real_sdk_uses_existing_ws_frames_and_preserves_receipt_absence() throws Exception {
    List<String> frames = new CopyOnWriteArrayList<>();
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
                        if (type.equals("relay.log")) {
                          var query = frame.path("payload");
                          outcome.set(
                              "payload",
                              Json.MAPPER.valueToTree(
                                  new RelayLog.Page(
                                      new RelayLog.Scope(query.path("project").asText(), false),
                                      new RelayLog.Topic(
                                          query.path("topic").asText(),
                                          "TEXT",
                                          "3600",
                                          null,
                                          "0",
                                          "0",
                                          Json.identity("topic").toString()),
                                      query.path("after").asText(),
                                      "0",
                                      null,
                                      List.of(),
                                      List.of(),
                                      List.of())));
                        }
                        if (type.equals("relay.publish")) {
                          var request = frame.path("payload");
                          outcome.set(
                              "payload",
                              Json.MAPPER.valueToTree(
                                  new RelayPort.Published(
                                      request.path("requestId").asText(),
                                      request.path("project").asText(),
                                      request.path("topic").asText(),
                                      "1",
                                      Instant.parse("2026-11-13T00:00:00Z"))));
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
                  Plowshare.TransportMode.LEGACY,
                  Plowshare.DispatchMode.ORDERED))) {
        assertTrue(
            gateway
                .log(new RelayLog.Query("home", false, "house.requests", "0", 100))
                .events()
                .isEmpty());
        var id = UUID.randomUUID().toString();
        assertEquals(
            id,
            gateway
                .publish(
                    new RelayPort.Publish(
                        id,
                        "home",
                        "house.returns",
                        "Result",
                        Instant.parse("2026-11-13T00:00:00Z"),
                        null,
                        null,
                        null))
                .requestId());
        assertTrue(gateway.receipt(UUID.randomUUID()).isEmpty());
        assertEquals(
            "run-fixture",
            gateway
                .start(
                    new Orchestration.Start(
                        "coordinator", "investigate", "Explain", null, "home", UUID.randomUUID()))
                .id());
        gateway.status("run-fixture");
      }
      assertEquals(
          List.of(
              "relay.log",
              "relay.publish",
              "orchestration.receipt",
              "orchestration.start",
              "orchestration.status"),
          frames);
      assertEquals(
          "/v1/events", Objects.requireNonNull(server.takeRequest()).getRequestUrl().encodedPath());
    }
  }
}
