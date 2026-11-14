package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class AgentCatalogTest {
  private static String catalog() {
    return """
        [{"name":"reviewer","description":"Review work","served":true,"bot":false,
          "tools":["read"],"future":{"ignored":true},"commands":[
          {"command":"/skill:review","aliases":["/skill:inspect"],"kind":"skill",
           "name":"review","description":"Review inputs","argumentHint":"question",
           "executor":null,"mode":null,"tier":"PROJECT","hash":"sha256:HASH",
           "agentVisible":true}]}]
        """
        .replace("HASH", "a".repeat(64));
  }

  @Test
  void existing_agent_list_uses_explicit_project_and_preserves_complete_commands()
      throws Exception {
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
                        var request = json.readTree(text);
                        calls.incrementAndGet();
                        assertEquals("agent.list", request.get("type").asText());
                        assertEquals("project", request.get("payload").get("project").asText());
                        var response = json.createObjectNode();
                        response.set("id", request.get("id"));
                        response.put("type", "agent.list");
                        response.put("protocol_version", "plowshare-v1");
                        response
                            .putObject("payload")
                            .put("code", "OK")
                            .set("payload", json.readTree(catalog()));
                        socket.send(response.toString());
                      } catch (IOException failure) {
                        throw new AssertionError(failure);
                      }
                    }
                  }));
      try (var connection =
          LegacyFixture.connect(
              server.url("/").toString(), "fixture", Duration.ofSeconds(3), null)) {
        var selected = new AgentClient(connection).list("project").getFirst();
        assertTrue(selected.served());
        assertEquals("reviewer", selected.name());
        var command = selected.commands().getFirst();
        assertEquals("question", command.argumentHint());
        assertEquals(List.of("/skill:inspect"), command.aliases());
        assertEquals("PROJECT", command.tier());
        assertThrows(UnsupportedOperationException.class, () -> selected.commands().clear());
        assertEquals(1, calls.get());
      }
    }
  }

  @Test
  void malformed_missing_and_coerced_discovery_values_are_refused() throws Exception {
    var json = SdkJson.mapper();
    var valid = (ArrayNode) json.readTree(catalog());
    for (String field : List.of("name", "description", "served", "bot", "commands")) {
      var rows = valid.deepCopy();
      ((ObjectNode) rows.get(0)).remove(field);
      assertThrows(
          IOException.class, () -> SdkJson.decode(json, rows, AgentClient.Definition[].class));
    }
    for (String field : List.of("argumentHint", "executor", "mode", "agentVisible")) {
      var rows = valid.deepCopy();
      ((ObjectNode) rows.get(0).get("commands").get(0)).remove(field);
      assertThrows(
          IOException.class, () -> SdkJson.decode(json, rows, AgentClient.Definition[].class));
    }
    for (String field : List.of("served", "bot")) {
      var rows = valid.deepCopy();
      ((ObjectNode) rows.get(0)).put(field, "true");
      assertThrows(
          IOException.class, () -> SdkJson.decode(json, rows, AgentClient.Definition[].class));
    }
    var rows = valid.deepCopy();
    ((ObjectNode) rows.get(0).get("commands").get(0)).put("command", "/orchestration:other");
    assertThrows(
        IOException.class, () -> SdkJson.decode(json, rows, AgentClient.Definition[].class));
    assertThrows(IllegalArgumentException.class, () -> new AgentClient.ListQuery(null));
    assertThrows(IllegalArgumentException.class, () -> new AgentClient.ListQuery(" project "));
  }

  @Test
  void messaging_delivery_accepts_server_configured_bodies_beyond_legacy_32768_characters()
      throws Exception {
    var json = SdkJson.mapper();
    var row = json.createObjectNode();
    row.put("message", "message")
        .put("sender", "sender")
        .put("recipient", "recipient")
        .putNull("replyTo");
    row.put("replyExpected", false)
        .put("finalReply", false)
        .put("generated", false)
        .put("state", "handled");
    for (String field :
        List.of("ending", "reply", "job", "conversation", "sourceConversation", "deadlineAt"))
      row.putNull(field);
    row.put("postedAt", "2026-11-13T00:00:00Z").put("body", "x".repeat(40000));
    assertEquals(40000, SdkJson.decode(json, row, MessagingClient.Delivery.class).body().length());
    row.put("body", "invalid\0body");
    assertThrows(
        IOException.class, () -> SdkJson.decode(json, row, MessagingClient.Delivery.class));
  }
}
