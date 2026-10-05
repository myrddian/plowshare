package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.Usage;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class UsageClientTest {
  private static Usage.Filter project(String name) {
    return new Usage.Filter(
        null, name, null, null, null, null, null, null, null, null, null, null, null, null);
  }

  @Test
  void a_foreign_snapshot_is_rejected_without_another_request() throws Exception {
    var received = new AtomicInteger();
    try (var server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket socket, String text) {
                      received.incrementAndGet();
                      try {
                        var frame = SdkJson.mapper().readTree(text);
                        socket.send(
                            "{\"id\":\""
                                + frame.path("id").textValue()
                                + "\",\"type\":\"usage.calls\",\"protocol_version\":\"plowshare-v1\",\"payload\":{\"code\":\"OK\",\"payload\":{"
                                + "\"filters\":{\"type\":\"usage.calls\",\"filter\":{\"project\":\"foreign\"}},"
                                + "\"calls\":[],\"health\":{\"watermark\":\"0\",\"as_of\":\"2026-10-04T00:00:00Z\",\"historical_usage\":\"not_imported\",\"capture_enabled\":false}}}}");
                      } catch (IOException error) {
                        throw new AssertionError(error);
                      }
                    }
                  }));
      try (var connection =
          Plowshare.connect(
              server.url("/").toString(), "fixture", Duration.ofSeconds(2), push -> {})) {
        assertThrows(
            IOException.class, () -> new UsageClient(connection).calls(project("selected")));
        assertEquals(1, received.get());
      }
    }
  }

  @Test
  void preflight_counts_do_not_coerce_numbers_or_missing_flags() throws Exception {
    String valid =
        "{\"tokens\":\"12\",\"basis\":\"MEASURED\",\"source\":\"TOKENIZER\",\"countedAt\":\"2026-10-04T00:00:00Z\",\"elapsedMillis\":0,\"cached\":false,\"gaps\":[]}";
    var json = SdkJson.mapper();
    assertEquals(
        "12", SdkJson.decode(json, json.readTree(valid), Usage.ContextCount.class).tokens());
    for (String invalid :
        new String[] {
          valid.replace("\"12\"", "12"),
          valid.replace("\"cached\":false,", ""),
          valid.replace("\"elapsedMillis\":0", "\"elapsedMillis\":0.5"),
          valid.replace("\"MEASURED\"", "\" invented \"")
        }) {
      var node = json.readTree(invalid);
      assertThrows(IOException.class, () -> SdkJson.decode(json, node, Usage.ContextCount.class));
    }
  }
}
