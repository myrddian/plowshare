package io.aeyer.plowshare.a2a.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.aeyer.plowshare.a2a.Receiver;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.sdk.Plowshare;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Independent A2A HTTP client over the real public Java SDK; no core imports or database. */
class IngressSdkTest {
  @TempDir Path directory;
  final ObjectMapper json =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  final HttpClient http = HttpClient.newHttpClient();

  @Test
  void incoming_text_context_status_cancellation_and_alias_isolation_use_only_general_relay()
      throws Exception {
    var nativeMessages = new RelayIngressBridgeTest.Messages();
    var operations = new ArrayList<String>();
    try (var broker = new MockWebServer()) {
      broker.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket socket, String text) {
                      try {
                        var frame = json.readTree(text);
                        String operation = frame.get("type").asText();
                        operations.add(operation);
                        var payload = frame.get("payload");
                        Object result;
                        result =
                            switch (operation) {
                              case "agent.list" ->
                                  List.of(
                                      Map.of(
                                          "name",
                                          "reviewer",
                                          "description",
                                          "Review",
                                          "served",
                                          true,
                                          "bot",
                                          false,
                                          "commands",
                                          List.of()));
                              case "relay.log" ->
                                  nativeMessages.log(
                                      json.treeToValue(payload, RelayLog.Query.class));
                              case "relay.publish" ->
                                  nativeMessages.publish(
                                      json.treeToValue(payload, RelayPort.Publish.class));
                              case "relay.consume" ->
                                  nativeMessages.consume(
                                      json.treeToValue(payload, RelayPort.Consume.class));
                              case "relay.ack" ->
                                  nativeMessages.acknowledge(
                                      json.treeToValue(payload, RelayPort.Ack.class));
                              default ->
                                  throw new AssertionError(
                                      "Unexpected legacy operation " + operation);
                            };
                        var response = json.createObjectNode();
                        response.set("id", frame.get("id"));
                        response.put("type", operation).put("protocol_version", "plowshare-v1");
                        response
                            .putObject("payload")
                            .put("code", "OK")
                            .set("payload", json.valueToTree(result));
                        socket.send(response.toString());
                      } catch (IOException failed) {
                        throw new AssertionError(failed);
                      }
                    }
                  }));
      var listener =
          new Receiver.Config(
              "127.0.0.1",
              0,
              "https://receiver.example.invalid/rpc",
              "project",
              "reviewer",
              Map.of("alice", "alice-secret", "bob", "bob-secret"),
              30000);
      var old = RelayIngressBridgeTest.BINDING;
      var binding =
          new RelayIngressBridge.Binding(
              old.project(),
              old.agent(),
              old.requests(),
              old.replies(),
              old.group(),
              old.consumer(),
              old.publisher(),
              30000);
      var config =
          new IngressConfiguration(
              broker.url("/").toString(), "fixture-token", directory, binding, listener);
      try (var sdk =
              Plowshare.connect(
                  broker.url("/").toString(),
                  "fixture-token",
                  UUID.randomUUID().toString(),
                  Duration.ofSeconds(3),
                  null,
                  Plowshare.TransportMode.LEGACY,
                  Plowshare.DispatchMode.ORDERED);
          var journal =
              new FileIngressJournal(
                  directory, config.fingerprint(), config.identityFingerprint());
          var receiver = new Receiver(listener, IngressMain.connect(config, sdk, journal))) {
        String message = UUID.randomUUID().toString();
        var sent =
            rpc(
                receiver,
                "alice-secret",
                "SendMessage",
                Map.of(
                    "message",
                    Map.of(
                        "messageId",
                        message,
                        "role",
                        "ROLE_USER",
                        "parts",
                        List.of(Map.of("text", "Question"))),
                    "configuration",
                    Map.of("returnImmediately", true)));
        var task = sent.path("result").path("task");
        String id = task.get("id").asText();
        String context = task.get("contextId").asText();
        assertEquals("TASK_STATE_SUBMITTED", task.path("status").path("state").asText());
        assertEquals(
            "Question", task.path("history").get(0).path("parts").get(0).path("text").asText());
        var duplicate =
            rpc(
                receiver,
                "alice-secret",
                "SendMessage",
                Map.of(
                    "message",
                    Map.of(
                        "messageId",
                        message,
                        "role",
                        "ROLE_USER",
                        "parts",
                        List.of(Map.of("text", "Question"))),
                    "configuration",
                    Map.of("returnImmediately", true)));
        assertEquals(id, duplicate.path("result").path("task").path("id").asText());
        assertEquals(1, nativeMessages.receives);
        assertEquals(
            -32001,
            rpc(receiver, "bob-secret", "GetTask", Map.of("id", id))
                .path("error")
                .path("code")
                .asInt());
        var next =
            rpc(
                receiver,
                "alice-secret",
                "SendMessage",
                Map.of(
                    "message",
                    Map.of(
                        "messageId",
                        UUID.randomUUID().toString(),
                        "role",
                        "ROLE_USER",
                        "contextId",
                        context,
                        "parts",
                        List.of(Map.of("text", "Follow up"))),
                    "configuration",
                    Map.of("returnImmediately", true)));
        assertEquals(context, next.path("result").path("task").path("contextId").asText());
        assertEquals(
            "TASK_STATE_CANCELED",
            rpc(receiver, "alice-secret", "CancelTask", Map.of("id", id))
                .path("result")
                .path("status")
                .path("state")
                .asText());
        assertEquals(1, nativeMessages.cancels);
        assertEquals(
            -32002,
            rpc(receiver, "alice-secret", "CancelTask", Map.of("id", id))
                .path("error")
                .path("code")
                .asInt());
        assertEquals(1, nativeMessages.cancels);
        assertTrue(
            operations.stream()
                .allMatch(value -> value.equals("agent.list") || value.startsWith("relay.")));
        assertTrue(operations.contains("agent.list"));
      }
    }
  }

  @Test
  void rotated_credential_with_different_authenticated_principal_stops_before_any_intake()
      throws Exception {
    var operations = new ArrayList<String>();
    try (var broker = new MockWebServer()) {
      broker.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket socket, String text) {
                      try {
                        var frame = json.readTree(text);
                        String operation = frame.get("type").asText();
                        operations.add(operation);
                        assertEquals("admin.status", operation);
                        var response = json.createObjectNode();
                        response.set("id", frame.get("id"));
                        response.put("type", operation).put("protocol_version", "plowshare-v1");
                        response
                            .putObject("payload")
                            .put("code", "OK")
                            .set(
                                "payload",
                                json.valueToTree(
                                    Map.of("handle", "different-principal", "serverAdmin", false)));
                        socket.send(response.toString());
                      } catch (IOException failed) {
                        throw new AssertionError(failed);
                      }
                    }
                  }));
      var listener =
          new Receiver.Config(
              "127.0.0.1",
              0,
              "https://receiver.example.invalid/rpc",
              "project",
              "reviewer",
              Map.of("alice", "alice-secret"),
              30000);
      var old =
          new IngressConfiguration(
              broker.url("/").toString(),
              "old-token",
              directory,
              RelayIngressBridgeTest.BINDING,
              listener,
              "expected-principal");
      var changed =
          new IngressConfiguration(
              old.origin(), "new-token", directory, old.binding(), listener, "expected-principal");
      assertEquals(old.identityFingerprint(), changed.identityFingerprint());
      try (var sdk =
              Plowshare.connect(
                  broker.url("/").toString(),
                  "new-token",
                  UUID.randomUUID().toString(),
                  Duration.ofSeconds(3),
                  null,
                  Plowshare.TransportMode.LEGACY,
                  Plowshare.DispatchMode.ORDERED);
          var journal =
              new FileIngressJournal(directory, old.fingerprint(), old.identityFingerprint())) {
        assertThrows(IOException.class, () -> IngressMain.connect(changed, sdk, journal));
        assertEquals(old.fingerprint(), journal.ownerFingerprint());
        assertEquals(List.of("admin.status"), operations);
      }
    }
  }

  private JsonNode rpc(Receiver receiver, String bearer, String method, Object params)
      throws Exception {
    String body =
        json.writeValueAsString(
            Map.of(
                "jsonrpc",
                "2.0",
                "id",
                UUID.randomUUID().toString(),
                "method",
                method,
                "params",
                params));
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + receiver.port() + "/rpc"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer " + bearer)
            .header("A2A-Version", "1.0")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    return json.readTree(response.body());
  }
}
