package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;

class PlowshareTest {
  @org.junit.jupiter.api.Test
  void sdk_json_boundary_accepts_fifty_mib_text_without_jackson_default_string_refusal()
      throws Exception {
    var json = SdkJson.mapper();
    var request =
        new io.aeyer.plowshare.protocol.RelayPort.Publish(
            "11111111-1111-1111-1111-111111111111",
            "fixture",
            "large.events",
            "x".repeat(io.aeyer.plowshare.protocol.RelayPort.MAX_TEXT_BYTES),
            java.time.Instant.parse("2026-10-10T00:00:00Z"),
            null,
            null,
            null);
    var read =
        SdkJson.decode(
            json,
            json.readTree(json.writeValueAsString(request)),
            io.aeyer.plowshare.protocol.RelayPort.Publish.class);
    assertEquals(request, read);
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void segmented_reply_with_trailing_json_strands_the_request_without_replay() throws Exception {
    try (var server = new MockWebServer()) {
      var requests = new java.util.concurrent.atomic.AtomicInteger();
      server.enqueue(
          new MockResponse()
              .setHeader("Sec-WebSocket-Protocol", "plowshare-segments-v1")
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket ws, String text) {
                      try {
                        var packet = JSON.readTree(text);
                        if (!packet.path("kind").asText().equals("transport.segment")) return;
                        requests.incrementAndGet();
                        var request =
                            JSON.readTree(
                                java.util.Base64.getDecoder().decode(packet.path("data").asText()));
                        ws.send(
                            JSON.writeValueAsString(
                                new io.aeyer.plowshare.protocol.transport.SegmentCredit(
                                    "transport.credit", 1, packet.path("transferId").asText(), 1)));
                        byte[] reply =
                            JSON.writeValueAsBytes(
                                Map.of(
                                    "id",
                                    request.path("id").asText(),
                                    "type",
                                    "project.list",
                                    "protocol_version",
                                    "plowshare-v1",
                                    "payload",
                                    Map.of("code", "OK", "payload", java.util.List.of())));
                        var segment =
                            new io.aeyer.plowshare.protocol.transport.MessageSegment(
                                "transport.segment",
                                1,
                                java.util.UUID.randomUUID().toString(),
                                1,
                                1,
                                0,
                                reply.length,
                                java.util.HexFormat.of()
                                    .formatHex(
                                        java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(reply)),
                                java.util.Base64.getEncoder().encodeToString(reply));
                        ws.send(JSON.writeValueAsString(segment) + " {}");
                      } catch (Exception failure) {
                        throw new AssertionError(failure);
                      }
                    }
                  }));
      try (var client =
          Plowshare.connect(
              server.url("/").toString(), "fixture-token", Duration.ofSeconds(3), null)) {
        var failure =
            assertThrows(
                Plowshare.TransportException.class, () -> client.request("project.list", Map.of()));
        assertEquals(Plowshare.Delivery.UNKNOWN, failure.delivery());
        assertEquals(1, requests.get());
      }
    }
  }

  @Test
  void default_transport_refuses_a_legacy_server_before_any_operation() throws Exception {
    try (var server = new MockWebServer()) {
      var messages = new java.util.concurrent.atomic.AtomicInteger();
      server.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket ws, String text) {
                      messages.incrementAndGet();
                    }
                  }));
      var refusal =
          assertThrows(
              Plowshare.TransportException.class,
              () ->
                  Plowshare.connect(
                      server.url("/").toString(), "fixture-token", Duration.ofSeconds(3), null));
      assertEquals(Plowshare.Delivery.NOT_SUBMITTED, refusal.delivery());
      assertEquals(0, messages.get());
      assertEquals(
          "plowshare-segments-v1",
          server.takeRequest(3, TimeUnit.SECONDS).getHeader("Sec-WebSocket-Protocol"));
    }
  }

  @Test
  void multiplexes_replies_and_delivers_pushes_with_bearer_upgrade() throws Exception {
    try (var server = new MockWebServer()) {
      var pushed = new CompletableFuture<io.aeyer.plowshare.protocol.ServerPush>();
      var first = new CompletableFuture<JsonNode>();
      server.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket ws, Response response) {
                      ws.send("{\"job\":\"job_1\",\"part\":\"ANSWER\",\"text\":\"hello\"}");
                    }

                    @Override
                    public void onMessage(WebSocket ws, String text) {
                      try {
                        JsonNode frame = JSON.readTree(text);
                        assertEquals("plowshare-v1", frame.path("protocol_version").asText());
                        if (!first.complete(frame)) {
                          respond(ws, frame, "OK");
                          respond(ws, first.get(), "BAD_REQUEST");
                        }
                      } catch (Exception failed) {
                        throw new AssertionError(failed);
                      }
                    }
                  }));
      try (var sdk =
              LegacyFixture.connect(
                  server.url("/").toString(),
                  "test-bearer",
                  Duration.ofSeconds(3),
                  pushed::complete);
          var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var one = executor.submit(() -> sdk.request("project.list", Map.of()));
        first.get(3, TimeUnit.SECONDS);
        var two = executor.submit(() -> sdk.request("job.list", Map.of()));
        assertEquals("OK", two.get(3, TimeUnit.SECONDS).code());
        var refusal = one.get(3, TimeUnit.SECONDS);
        assertEquals("BAD_REQUEST", refusal.code());
        assertEquals("the server's sentence", refusal.said());
        assertThrows(java.io.IOException.class, refusal::requirePayload);
        assertEquals(
            "hello",
            ((io.aeyer.plowshare.protocol.JobDelta) pushed.get(3, TimeUnit.SECONDS)).text());
        assertEquals(19, refusal.raw().path("payload").path("futureField").asInt());
        var upgrade = server.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(upgrade);
        assertEquals("Bearer test-bearer", upgrade.getHeader("Authorization"));
        assertFalse(upgrade.getPath().contains("test-bearer"));
      }
    }
  }

  @Test
  void timeout_never_replays_a_mutation_and_close_refuses_new_work() throws Exception {
    try (var server = new MockWebServer()) {
      var count = new java.util.concurrent.atomic.AtomicInteger();
      server.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket ws, String text) {
                      count.incrementAndGet();
                    }
                  }));
      var sdk =
          LegacyFixture.connect(server.url("/").toString(), null, Duration.ofMillis(150), null);
      try {
        var failure =
            assertThrows(
                Plowshare.TransportException.class,
                () -> sdk.request("outgoing.send", Map.of("requestId", "stable")));
        assertEquals(Plowshare.Delivery.UNKNOWN, failure.delivery());
        assertEquals(1, count.get());
      } finally {
        sdk.close();
      }
      assertEquals(
          Plowshare.Delivery.NOT_SUBMITTED,
          assertThrows(
                  Plowshare.TransportException.class, () -> sdk.request("outgoing.send", Map.of()))
              .delivery());
      assertEquals(1, server.getRequestCount());
    }
  }

  @Test
  void correlated_wrong_type_is_invalid_not_success() throws Exception {
    try (var server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket ws, String text) {
                      try {
                        var frame = JSON.readTree(text);
                        ((com.fasterxml.jackson.databind.node.ObjectNode) frame)
                            .put("type", "job.cancel");
                        respond(ws, frame, "OK");
                      } catch (Exception failure) {
                        throw new AssertionError(failure);
                      }
                    }
                  }));
      try (var sdk =
          LegacyFixture.connect(server.url("/").toString(), null, Duration.ofSeconds(3), null)) {
        assertEquals(
            Plowshare.Delivery.INVALID_RESPONSE,
            assertThrows(
                    Plowshare.TransportException.class,
                    () -> sdk.request("job.status", Map.of("job", "x")))
                .delivery());
      }
    }
  }

  private static void respond(WebSocket ws, JsonNode frame, String code) throws Exception {
    var reply = JSON.createObjectNode();
    reply.set("id", frame.get("id"));
    reply.set("type", frame.get("type"));
    reply.put("protocol_version", "plowshare-v1");
    reply.set(
        "payload",
        JSON.valueToTree(
            Map.of(
                "code",
                code,
                "said",
                "the server's sentence",
                "payload",
                Map.of("id", "fixture"),
                "futureField",
                19)));
    ws.send(JSON.writeValueAsString(reply));
  }

  @Test
  void typed_facade_uses_ws_and_retains_nullable_budgets_and_full_job_fields() throws Exception {
    try (var server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket ws, String text) {
                      try {
                        JsonNode frame = JSON.readTree(text);
                        Object result =
                            switch (frame.path("type").asText()) {
                              case "conversation.open" ->
                                  Map.of(
                                      "id",
                                      "cnv_test",
                                      "noBudget",
                                      true,
                                      "noTurnCap",
                                      true,
                                      "title",
                                      "fixture");
                              case "job.status" ->
                                  Map.of(
                                      "id",
                                      "job_test",
                                      "agent",
                                      "bot",
                                      "state",
                                      "DONE",
                                      "cancelRequested",
                                      false,
                                      "conversation",
                                      "cnv_test",
                                      "limits",
                                      Map.of(
                                          "noBudget",
                                          true,
                                          "noTurnCap",
                                          true,
                                          "modelCallsSpent",
                                          4),
                                      "outcome",
                                      Map.of(
                                          "ending",
                                          "ANSWERED",
                                          "answered",
                                          true,
                                          "resumable",
                                          false,
                                          "text",
                                          "done",
                                          "steps",
                                          2,
                                          "modelCalls",
                                          4,
                                          "future",
                                          true));
                              default -> throw new AssertionError(frame.toString());
                            };
                        var response = JSON.createObjectNode();
                        response.set("id", frame.get("id"));
                        response.set("type", frame.get("type"));
                        response.put("protocol_version", "plowshare-v1");
                        response.set(
                            "payload", JSON.valueToTree(Map.of("code", "OK", "payload", result)));
                        ws.send(JSON.writeValueAsString(response));
                      } catch (Exception failed) {
                        throw new AssertionError(failed);
                      }
                    }
                  }));
      try (var sdk =
          new WsServerClient(server.url("/").toString(), null, Plowshare.TransportMode.LEGACY)) {
        assertEquals(0, server.getRequestCount());
        var conversation = sdk.openConversation(null, null);
        assertNull(conversation.maxModelCalls());
        assertTrue(conversation.noBudget());
        var job = sdk.job("job_test");
        assertEquals("cnv_test", job.conversation());
        assertEquals(4, job.limits().modelCallsSpent());
        assertTrue(job.outcome().answered());
        assertEquals(1, server.getRequestCount());
      }
    }
  }
}
