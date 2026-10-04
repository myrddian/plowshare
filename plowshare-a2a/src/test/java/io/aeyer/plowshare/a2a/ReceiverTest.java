package io.aeyer.plowshare.a2a;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.aeyer.plowshare.protocol.Incoming;
import io.aeyer.plowshare.sdk.IncomingClient;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Real HTTP binding; durable messaging is independently tested against Postgres. */
class ReceiverTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final HttpClient HTTP = HttpClient.newHttpClient();

  static class Bridge implements Receiver.Bridge {
    final Map<UUID, Incoming.Task> tasks = new HashMap<>();
    final Map<UUID, String> owners = new HashMap<>();
    Incoming.Receive received;
    int calls;
    boolean complete;

    public synchronized Incoming.Task receive(Incoming.Receive request) {
      calls++;
      received = request;
      var task =
          new Incoming.Task(
              request.requestId(),
              request.context() == null ? UUID.randomUUID() : request.context(),
              request.agent(),
              "msg",
              complete ? "COMPLETED" : "SUBMITTED",
              complete ? "ANSWERED" : null,
              request.source(),
              List.of(),
              Instant.now());
      tasks.put(task.id(), task);
      owners.put(task.id(), request.client());
      return task;
    }

    public synchronized Incoming.Task status(Incoming.Id id) throws java.io.IOException {
      if (!Objects.equals(id.client(), owners.get(id.id())))
        throw new IncomingClient.Refused("BAD_REQUEST");
      return tasks.get(id.id());
    }

    public synchronized Incoming.Task cancel(Incoming.Id id) throws java.io.IOException {
      var task = status(id);
      var next =
          new Incoming.Task(
              task.id(),
              task.context(),
              task.agent(),
              task.message(),
              "CANCELED",
              "CANCELLED",
              task.source(),
              task.replies(),
              task.createdAt());
      tasks.put(task.id(), next);
      return next;
    }

    public JsonNode catalog() {
      return JSON.valueToTree(
          Map.of(
              "name",
              "reviewer",
              "description",
              "Review work",
              "served",
              true,
              "commands",
              List.of(
                  Map.of(
                      "command",
                      "/skill:review",
                      "name",
                      "Review",
                      "description",
                      "Review inputs",
                      "kind",
                      "skill"))));
    }
  }

  Receiver receiver(Bridge bridge, long wait) throws Exception {
    return new Receiver(
        new Receiver.Config(
            "127.0.0.1",
            0,
            "http://127.0.0.1:8093/rpc",
            "payments",
            "reviewer",
            Map.of("remote", "fixture-token", "other", "other-token"),
            wait),
        bridge);
  }

  Map<String, Object> message() {
    return Map.of(
        "messageId",
        UUID.randomUUID().toString(),
        "role",
        "ROLE_USER",
        "parts",
        List.of(Map.of("text", "Review")));
  }

  HttpResponse<String> rpc(
      Receiver receiver, String token, String method, Map<String, Object> params, String extension)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + receiver.port() + "/rpc"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .header("A2A-Version", "1.0");
    if (extension != null) request.header("A2A-Extensions", extension);
    return HTTP.send(
        request
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    JSON.writeValueAsString(
                        Map.of(
                            "jsonrpc", "2.0", "id", "request", "method", method, "params",
                            params))))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  JsonNode json(HttpResponse<String> response) throws Exception {
    return JSON.readTree(response.body());
  }

  @Test
  void card_is_public_and_describes_granted_commands_and_standard_bearer_shape() throws Exception {
    var bridge = new Bridge();
    try (var receiver = receiver(bridge, 1000)) {
      var response =
          HTTP.send(
              HttpRequest.newBuilder(
                      URI.create(
                          "http://127.0.0.1:" + receiver.port() + "/.well-known/agent-card.json"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      JsonNode card = json(response);
      assertEquals("/skill:review", card.path("skills").get(1).path("id").asText());
      assertTrue(
          card.path("securityRequirements")
              .get(0)
              .path("schemes")
              .path("clientBearer")
              .path("list")
              .isArray());
      assertFalse(response.body().contains("fixture-token"));
      assertFalse(card.path("capabilities").path("streaming").asBoolean());
    }
  }

  @Test
  void send_get_cancel_and_client_isolation_use_the_bridge() throws Exception {
    var bridge = new Bridge();
    try (var receiver = receiver(bridge, 1000)) {
      var sent =
          json(
              rpc(
                  receiver,
                  "fixture-token",
                  "SendMessage",
                  Map.of("message", message(), "configuration", Map.of("returnImmediately", true)),
                  null));
      var task = sent.path("result").path("task");
      assertEquals("TASK_STATE_SUBMITTED", task.path("status").path("state").asText());
      String id = task.path("id").asText();
      assertEquals(
          -32001,
          json(rpc(receiver, "other-token", "GetTask", Map.of("id", id), null))
              .path("error")
              .path("code")
              .asInt());
      assertEquals(
          id,
          json(rpc(
                  receiver, "fixture-token", "GetTask", Map.of("id", id, "historyLength", 0), null))
              .path("result")
              .path("id")
              .asText());
      assertEquals(
          -32602,
          json(rpc(
                  receiver,
                  "fixture-token",
                  "CancelTask",
                  Map.of("id", id, "historyLength", -1),
                  null))
              .path("error")
              .path("code")
              .asInt());
      assertEquals(
          "SUBMITTED",
          bridge.tasks.get(UUID.fromString(id)).state(),
          "Invalid cancellation arguments must have no side effect");
      assertEquals(
          "TASK_STATE_CANCELED",
          json(rpc(receiver, "fixture-token", "CancelTask", Map.of("id", id), null))
              .path("result")
              .path("status")
              .path("state")
              .asText());
      assertEquals(
          -32002,
          json(rpc(receiver, "fixture-token", "CancelTask", Map.of("id", id), null))
              .path("error")
              .path("code")
              .asInt());
      assertEquals(1, bridge.calls);
    }
  }

  @Test
  void bad_auth_version_content_type_and_parts_never_create_work() throws Exception {
    var bridge = new Bridge();
    try (var receiver = receiver(bridge, 1000)) {
      assertEquals(
          401,
          rpc(receiver, "wrong", "SendMessage", Map.of("message", message()), null).statusCode());
      var noVersion =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + receiver.port() + "/rpc"))
              .header("Authorization", "Bearer fixture-token")
              .POST(HttpRequest.BodyPublishers.ofString("{}"))
              .build();
      assertEquals(400, HTTP.send(noVersion, HttpResponse.BodyHandlers.ofString()).statusCode());
      assertEquals(
          -32005,
          json(rpc(
                  receiver,
                  "fixture-token",
                  "SendMessage",
                  Map.of(
                      "message",
                      Map.of(
                          "messageId",
                          "bad",
                          "role",
                          "ROLE_USER",
                          "parts",
                          List.of(Map.of("data", Map.of("x", 1))))),
                  null))
              .path("error")
              .path("code")
              .asInt());
      assertEquals(
          -32004,
          json(rpc(receiver, "fixture-token", "SendStreamingMessage", Map.of(), null))
              .path("error")
              .path("code")
              .asInt());
      assertEquals(0, bridge.calls);
    }
  }

  @Test
  void explicit_skills_require_extension_and_grant_while_plain_slashes_remain_text()
      throws Exception {
    var bridge = new Bridge();
    try (var receiver = receiver(bridge, 1000)) {
      var input = new LinkedHashMap<>(message());
      input.put("metadata", Map.of("plowshareCommand", "/skill:review"));
      Map<String, Object> params =
          Map.of("message", input, "configuration", Map.of("returnImmediately", true));
      assertEquals(
          -32602,
          json(rpc(receiver, "fixture-token", "SendMessage", params, null))
              .path("error")
              .path("code")
              .asInt());
      assertTrue(
          json(rpc(receiver, "fixture-token", "SendMessage", params, Receiver.COMMAND_EXTENSION))
              .has("result"));
      assertEquals("/skill:review", bridge.received.command());
      input.put("metadata", Map.of("plowshareCommand", "/skill:ungranted"));
      assertEquals(
          -32602,
          json(rpc(receiver, "fixture-token", "SendMessage", params, Receiver.COMMAND_EXTENSION))
              .path("error")
              .path("code")
              .asInt());
      assertEquals(1, bridge.calls);
      assertEquals(
          -32005,
          json(rpc(
                  receiver,
                  "fixture-token",
                  "SendMessage",
                  Map.of(
                      "message",
                      message(),
                      "configuration",
                      Map.of(
                          "returnImmediately",
                          true,
                          "acceptedOutputModes",
                          List.of("application/json"))),
                  null))
              .path("error")
              .path("code")
              .asInt());
      assertEquals(1, bridge.calls, "Unsupported output mode must not accept work");
    }
  }

  @Test
  void default_blocking_never_returns_success_for_unfinished_work() throws Exception {
    var bridge = new Bridge();
    try (var receiver = receiver(bridge, 50)) {
      var response =
          rpc(receiver, "fixture-token", "SendMessage", Map.of("message", message()), null);
      assertEquals(504, response.statusCode());
      assertFalse(json(response).has("result"));
      assertEquals(1, bridge.calls);
      bridge.complete = true;
      assertEquals(
          "TASK_STATE_COMPLETED",
          json(rpc(receiver, "fixture-token", "SendMessage", Map.of("message", message()), null))
              .path("result")
              .path("task")
              .path("status")
              .path("state")
              .asText());
    }
  }

  @Test
  void existing_sender_interoperates_and_negotiates_command_extension() throws Exception {
    var bridge = new Bridge();
    try (var receiver = receiver(bridge, 1000);
        var client =
            new A2aClient(
                "http://127.0.0.1:" + receiver.port() + "/rpc",
                "fixture-token",
                Duration.ofSeconds(3))) {
      // Public URL is pinned by discovery in production; port zero is a test listener.
      var sent =
          client.send(
              UUID.randomUUID(),
              Map.of(
                  "parts",
                  List.of(Map.of("text", "Review")),
                  "metadata",
                  Map.of("plowshareCommand", "/skill:review")));
      assertEquals("WORKING", sent.state());
      assertEquals("/skill:review", bridge.received.command());
      assertEquals("WORKING", client.status(sent.task(), sent.context()).state());
      assertEquals("CANCELED", client.cancel(sent.task(), sent.context()).state());
    }
  }
}
