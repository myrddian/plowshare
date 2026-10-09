package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Exercise selector checks over the real socket, including uncertain mutation receipts. */
class SelectedResourceRepliesTest {
  enum Operation {
    JOB,
    CANCEL,
    DEFINE,
    LEND,
    UNLEND,
    WORKSPACE,
    OPEN,
    LIST,
    FETCH_URL,
    FETCH_OFFSET
  }

  private static JsonNode payload(Operation operation, boolean foreign) throws IOException {
    String selected = foreign ? "foreign" : "selected";
    return SdkJson.mapper()
        .readTree(
            switch (operation) {
              case JOB, CANCEL ->
                  "{\"id\":\""
                      + selected
                      + "\",\"agent\":\"worker\",\"state\":\"RUNNING\",\"cancelRequested\":false}";
              case DEFINE, LEND, UNLEND, WORKSPACE ->
                  "{\"name\":\""
                      + selected
                      + "\",\"workspace\":\"/fixture\",\"lent\":[],\"exclusions\":[]}";
              case OPEN ->
                  "{\"id\":\"conversation\",\"project\":\""
                      + selected
                      + "\",\"modelCallsSpent\":0}";
              case LIST ->
                  "[{\"id\":\"conversation\",\"project\":\""
                      + selected
                      + "\",\"modelCallsSpent\":0}]";
              case FETCH_URL, FETCH_OFFSET -> {
                String url =
                    operation == Operation.FETCH_URL && foreign
                        ? "https://other.example/page"
                        : "https://example.com/page";
                int offset = operation == Operation.FETCH_OFFSET && foreign ? 1 : 0;
                yield "{\"url\":\""
                    + url
                    + "\",\"text\":\"\",\"offset\":"
                    + offset
                    + ",\"nextOffset\":"
                    + offset
                    + ",\"total\":0,\"hasMore\":false}";
              }
            });
  }

  private static void call(WsServerClient client, Operation operation) throws IOException {
    switch (operation) {
      case JOB -> client.job("selected");
      case CANCEL -> client.cancelJob("selected");
      case DEFINE -> client.defineProject("selected", "/fixture", List.of());
      case LEND -> client.lendProject("selected", List.of("/fixture"));
      case UNLEND -> client.unlendProject("selected", List.of("/fixture"));
      case WORKSPACE -> client.setProjectWorkspace("selected", "/fixture");
      case OPEN -> client.openConversation("selected", 10);
      case LIST -> client.conversations("selected");
      case FETCH_URL, FETCH_OFFSET -> client.fetch("https://example.com/page", 0);
    }
  }

  @ParameterizedTest
  @EnumSource(Operation.class)
  void accepts_selected_resources_and_refuses_foreign_replies_without_replay(Operation operation)
      throws Exception {
    for (boolean foreign : List.of(false, true)) {
      var requests = new AtomicInteger();
      var payload = payload(operation, foreign);
      try (var server = new MockWebServer()) {
        server.enqueue(
            new MockResponse()
                .withWebSocketUpgrade(
                    new WebSocketListener() {
                      @Override
                      public void onMessage(WebSocket socket, String text) {
                        requests.incrementAndGet();
                        try {
                          var json = SdkJson.mapper();
                          var input = json.readTree(text);
                          var reply =
                              json.createObjectNode()
                                  .put("id", input.path("id").textValue())
                                  .put("type", input.path("type").textValue())
                                  .put("protocol_version", "plowshare-v1");
                          reply.set(
                              "payload",
                              json.createObjectNode().put("code", "OK").set("payload", payload));
                          socket.send(json.writeValueAsString(reply));
                        } catch (IOException invalid) {
                          throw new AssertionError(invalid);
                        }
                      }
                    }));
        try (var client =
            new WsServerClient(
                server.url("/").toString(), "fixture", Plowshare.TransportMode.LEGACY)) {
          if (foreign) {
            var error =
                assertThrows(Plowshare.TransportException.class, () -> call(client, operation));
            assertEquals(Plowshare.Delivery.INVALID_RESPONSE, error.delivery());
          } else {
            assertDoesNotThrow(() -> call(client, operation));
          }
          assertEquals(1, requests.get());
        }
      }
    }
  }
}
