package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.InformationDiscovery;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class InformationDiscoveryClientTest {
  @Test
  void typed_discovery_uses_explicit_scope_ids_and_no_implicit_home_fields() throws Exception {
    String scope = UUID.randomUUID().toString();
    var calls = new AtomicInteger();
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
                        calls.incrementAndGet();
                        assertEquals("information.discovery.list", frame.path("type").asText());
                        assertFalse(frame.path("payload").has("scope"));
                        assertFalse(frame.path("payload").has("query"));
                        assertFalse(frame.path("payload").has("cursor"));
                        assertEquals(scope, frame.path("payload").path("scopes").get(0).asText());
                        var response =
                            json.createObjectNode()
                                .put("id", frame.path("id").asText())
                                .put("type", frame.path("type").asText())
                                .put("protocol_version", "plowshare-v1");
                        var page =
                            json.createObjectNode().putNull("nextCursor").put("hasMore", false);
                        page.putArray("scopes").add(scope);
                        page.putArray("items");
                        response.putObject("payload").put("code", "OK").set("payload", page);
                        socket.send(response.toString());
                      } catch (IOException invalid) {
                        throw new AssertionError(invalid);
                      }
                    }
                  }));
      try (var client =
          new WsServerClient(
              server.url("/").toString(),
              "fixture",
              Plowshare.TransportMode.LEGACY,
              Plowshare.DispatchMode.ORDERED)) {
        var discovery = client.informationDiscovery();
        var request = new InformationDiscovery.Query(List.of(scope), null, null, null, 10, null);
        var page =
            discovery
                .list(request, RequestOptions.defaults(Duration.ofSeconds(3)))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals(List.of(scope), page.scopes());
        assertFalse(page.hasMore());
        assertThrows(
            IllegalArgumentException.class,
            () ->
                discovery.list(
                    new InformationDiscovery.Query(
                        List.of(scope), null, "not a listing", null, 10, null),
                    null));
        assertEquals(1, calls.get());
      }
    }
  }
}
