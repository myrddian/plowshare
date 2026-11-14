package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class ForumClientsTest {
  private static final String UUID = "00000000-0000-0000-0000-000000000089";

  private static JsonNode fixture(String operation) throws IOException {
    var json = SdkJson.mapper();
    return json.readTree(
            Files.readString(
                Path.of("../../test-support/contracts/ws-administrative-fixtures.json")))
        .path(operation)
        .path("payload");
  }

  @Test
  void typed_records_preserve_nulls_and_reject_missing_or_inconsistent_values() throws Exception {
    var json = SdkJson.mapper();
    var opened = SdkJson.decode(json, fixture("board.open"), BoardClient.Opened.class);
    assertEquals("default", opened.topic().swarm().name());
    assertEquals("full\nsource", opened.message().body());
    for (String field : List.of("project", "openedAt", "swarm", "closedAt", "potTotal")) {
      ObjectNode topic = fixture("board.open").path("topic").deepCopy();
      topic.remove(field);
      assertThrows(IOException.class, () -> SdkJson.decode(json, topic, BoardClient.Topic.class));
    }
    ObjectNode bad = fixture("board.open").deepCopy();
    ((ObjectNode) bad.path("message")).put("topic", "foreign");
    assertThrows(IOException.class, () -> SdkJson.decode(json, bad, BoardClient.Opened.class));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SwarmClient.Selection(
                "default", "a".repeat(64), "", List.of("worker", "worker"), 20));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SwarmClient.Type(
                "default", List.of("worker"), 20, Map.of(), "swarm/default.md", null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MessagingClient.Instance(
                "i", "p", "a", "c", "task", true, true, false, "ready", 0, null, Instant.now()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BoardClient.Close("p", "bdt_root", "done", List.of("invalid"), UUID));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MessagingClient.InstancesQuery("p", false, 0, 201));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BoardClient.Open("p", null, "title", "label", "body", "bad", 20));
  }

  @Test
  void native_agent_forum_authorship_is_readable_without_private_execution_log_links()
      throws Exception {
    ObjectNode value = fixture("board.open").path("message").deepCopy();
    value.put("authorKind", "agent");
    value.put("author", "worker");
    value.putNull("conversation");
    value.putNull("entry");
    var message = SdkJson.decode(SdkJson.mapper(), value, BoardClient.Message.class);
    assertEquals("agent", message.authorKind());
    assertEquals("worker", message.author());
    assertEquals(null, message.conversation());
    assertEquals(null, message.entry());
  }

  @Test
  void all_mutations_and_reads_use_existing_operations_and_never_replay_a_foreign_receipt()
      throws Exception {
    var received = new AtomicInteger();
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
                        String operation = request.path("type").asText();
                        received.incrementAndGet();
                        JsonNode payload;
                        if (operation.startsWith("board.")) payload = fixture(operation);
                        else if (operation.equals("swarm.types"))
                          payload = json.readTree("{\"project\":\"p\",\"types\":[]}");
                        else if (operation.equals("swarm.status"))
                          payload =
                              json.readTree(
                                  "{\"pools\":[],\"ready\":[],\"topics\":[],\"seats\":[],\"more\":false}");
                        else if (operation.equals("message.instances"))
                          payload = json.readTree("{\"instances\":[],\"more\":false,\"offset\":0}");
                        else if (operation.equals("message.deliveries"))
                          payload =
                              json.readTree("{\"deliveries\":[],\"more\":false,\"offset\":0}");
                        else if (operation.equals("message.delivery")
                            || operation.equals("message.cancel"))
                          payload =
                              json.readTree(
                                  "{\"message\":\"msg\",\"sender\":\"a\",\"recipient\":\"b\",\"replyTo\":null,\"replyExpected\":false,\"finalReply\":false,\"generated\":false,\"state\":\"handled\",\"ending\":null,\"reply\":null,\"job\":null,\"deadlineAt\":null,\"postedAt\":\"2026-11-13T00:00:00Z\",\"body\":\"hi\"}");
                        else
                          payload =
                              json.readTree(
                                  "{\"id\":\"i\",\"project\":\"p\",\"agent\":\"a\",\"conversation\":\"c\",\"lifetime\":\"persistent\",\"defaultInstance\":false,\"active\":true,\"archived\":false,\"state\":\"ready\",\"pending\":0,\"job\":null,\"createdAt\":\"2026-11-13T00:00:00Z\"}");
                        var envelope = json.createObjectNode();
                        envelope.set("id", request.get("id"));
                        envelope.put("type", operation);
                        envelope.put("protocol_version", "plowshare-v1");
                        var outcome = envelope.putObject("payload");
                        outcome.put("code", "OK");
                        outcome.set("payload", payload);
                        socket.send(json.writeValueAsString(envelope));
                      } catch (IOException error) {
                        throw new AssertionError(error);
                      }
                    }
                  }));
      try (var connection =
          LegacyFixture.connect(
              server.url("/").toString(), "fixture", Duration.ofSeconds(3), null)) {
        var board = new BoardClient(connection);
        assertEquals(
            "root",
            board
                .openAsync(new BoardClient.Open("p", null, "Title", "label", "body", UUID, 20))
                .toCompletableFuture()
                .get()
                .topic()
                .id());
        assertEquals(
            "msg", board.post(new BoardClient.Post("p", "root", "hi", UUID)).message().id());
        assertEquals(
            24, board.retry(new BoardClient.Retry("p", "root", "researcher", UUID, 24)).maxTurns());
        assertEquals("root", board.topUp(new BoardClient.TopUp("root", 100)).id());
        assertThrows(
            IOException.class,
            () -> board.close(new BoardClient.Close("p", "bdt_root", "done", null, UUID)));
        assertThrows(
            IOException.class,
            () ->
                board.open(
                    new BoardClient.Open("foreign", null, "Title", "label", "body", UUID, 20)));
        var swarm = new SwarmClient(connection);
        assertEquals("p", swarm.types(new SwarmClient.TypesQuery("p")).project());
        assertEquals(0, swarm.status(new SwarmClient.StatusQuery()).topics().size());
        var messages = new MessagingClient(connection);
        var query = new MessagingClient.InstanceQuery("i");
        assertEquals("i", messages.open(new MessagingClient.Open("p", "a", false, UUID)).id());
        assertEquals("i", messages.instance(query).id());
        assertEquals("i", messages.makeDefault(query).id());
        assertEquals("i", messages.stop(query).id());
        assertEquals("i", messages.archive(query).id());
        assertEquals(
            0,
            messages.instances(new MessagingClient.InstancesQuery("p", null, null, null)).offset());
        assertEquals(
            0, messages.deliveries(new MessagingClient.DeliveriesQuery("i", null, null)).offset());
        assertEquals("msg", messages.delivery(new MessagingClient.DeliveryQuery("msg")).message());
        assertEquals("msg", messages.cancel(new MessagingClient.DeliveryQuery("msg")).message());
        assertThrows(
            IOException.class,
            () -> messages.instance(new MessagingClient.InstanceQuery("foreign")));
        assertEquals(18, received.get());
      }
    }
  }

  @Test
  void detail_and_page_reads_check_scope_and_nested_ownership() throws Exception {
    var json = SdkJson.mapper();
    var topic = fixture("board.open").path("topic");
    var detail = json.createObjectNode();
    detail.set("topic", topic);
    detail.set("root", topic);
    detail.putArray("messages").add(fixture("board.open").path("message"));
    detail.putArray("seats");
    detail.putArray("decisions");
    var page = json.createObjectNode();
    page.putArray("topics").addObject().set("topic", topic);
    var summary = (ObjectNode) page.path("topics").get(0);
    summary.put("messages", 1);
    summary.put("documents", 0);
    page.put("more", false);
    page.put("offset", 0);
    try (var server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket socket, String text) {
                      try {
                        var request = json.readTree(text);
                        var envelope = json.createObjectNode();
                        envelope.set("id", request.get("id"));
                        envelope.set("type", request.get("type"));
                        envelope.put("protocol_version", "plowshare-v1");
                        var outcome = envelope.putObject("payload");
                        outcome.put("code", "OK");
                        outcome.set(
                            "payload",
                            request.path("type").asText().equals("board.topics") ? page : detail);
                        socket.send(json.writeValueAsString(envelope));
                      } catch (IOException failure) {
                        throw new AssertionError(failure);
                      }
                    }
                  }));
      try (var connection =
          LegacyFixture.connect(
              server.url("/").toString(), "fixture", Duration.ofSeconds(3), null)) {
        var board = new BoardClient(connection);
        assertEquals(1, board.topics(new BoardClient.TopicsQuery("p", 0, 200)).topics().size());
        assertEquals("root", board.messages(new BoardClient.TopicQuery("root")).root().id());
        assertThrows(
            IOException.class, () -> board.topics(new BoardClient.TopicsQuery("foreign", 0, 200)));
        assertThrows(
            IOException.class, () -> board.topics(new BoardClient.TopicsQuery("p", 1, 200)));
        assertThrows(
            IOException.class, () -> board.messages(new BoardClient.TopicQuery("foreign")));
      }
    }
    ((ObjectNode) detail.path("messages").get(0)).put("topic", "foreign");
    assertThrows(IOException.class, () -> SdkJson.decode(json, detail, BoardClient.Detail.class));
    assertEquals(
        200,
        new BoardClient.Close("p", "bdt_root", "done", Collections.nCopies(200, "bdm_one"), UUID)
            .cites()
            .size());
  }
}
