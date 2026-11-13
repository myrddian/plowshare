package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.RelayPort;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

/** The public wire contract uses ISO strings, independent of Jackson's numeric time defaults. */
class RelayTimeContractTest {
  @Test
  void public_publish_preserves_iso_timestamp_in_actual_sdk_frame() throws Exception {
    Instant at = Instant.parse("2026-11-13T01:02:03.123456Z");
    String id = UUID.randomUUID().toString();
    try (var server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket socket, String text) {
                      try {
                        var json = SdkJson.mapper();
                        var frame = json.readTree(text);
                        var payload = frame.get("payload");
                        assertEquals("relay.publish", frame.get("type").asText());
                        assertEquals(id, payload.get("requestId").asText());
                        assertTrue(payload.get("occurredAt").isTextual());
                        assertEquals(at.toString(), payload.get("occurredAt").textValue());
                        var reply = json.createObjectNode();
                        reply.set("id", frame.get("id"));
                        reply.set("type", frame.get("type"));
                        reply.put("protocol_version", "plowshare-v1");
                        reply
                            .putObject("payload")
                            .put("code", "OK")
                            .putObject("payload")
                            .put("requestId", id)
                            .put("project", "fixture")
                            .put("topic", "requests")
                            .put("position", "1")
                            .put("publishedAt", at.toString());
                        socket.send(reply.toString());
                      } catch (IOException invalid) {
                        throw new AssertionError(invalid);
                      }
                    }
                  }));
      try (var sdk =
          LegacyFixture.connect(
              server.url("/").toString(), "fixture", Duration.ofSeconds(3), null)) {
        var result =
            new RelayClient(sdk)
                .publish(
                    new RelayPort.Publish(id, "fixture", "requests", "body", at, null, null, null));
        assertEquals(at, result.publishedAt());
      }
    }
  }

  @Test
  void numeric_and_malformed_response_times_are_rejected_before_returning_a_dto() throws Exception {
    var json = SdkJson.mapper();
    var row =
        json.createObjectNode()
            .put("requestId", UUID.randomUUID().toString())
            .put("project", "fixture")
            .put("topic", "requests")
            .put("position", "1");
    row.put("publishedAt", 1791072015);
    assertThrows(IOException.class, () -> SdkJson.decode(json, row, RelayPort.Published.class));
    row.put("publishedAt", "private-marker-not-a-timestamp");
    var rejected =
        assertThrows(IOException.class, () -> SdkJson.decode(json, row, RelayPort.Published.class));
    assertNull(rejected.getCause(), "A parser cause could echo private wire values");
    assertFalse(rejected.toString().contains("private-marker"));
    row.put("publishedAt", "2026-11-13T01:02:03.123456Z");
    assertEquals(
        Instant.parse("2026-11-13T01:02:03.123456Z"),
        SdkJson.decode(json, row, RelayPort.Published.class).publishedAt());
  }
}
