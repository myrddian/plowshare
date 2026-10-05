package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.protocol.*;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Reject foreign receipts at the real SDK socket, with no mutation replay. */
class OutgoingReceiptsTest {
  private static final UUID ID = UUID.randomUUID();
  private static final UUID REQUEST = UUID.randomUUID();

  private static ObjectNode receipt() throws Exception {
    return (ObjectNode)
        SdkJson.mapper()
            .readTree(
                "{\"id\":\""
                    + ID
                    + "\",\"requestId\":\""
                    + REQUEST
                    + "\",\"peer\":\"peer\",\"project\":\"selected\",\"message\":{\"parts\":[{\"text\":\"question\"}]},"
                    + "\"state\":\"COMPLETED\",\"cancelRequested\":false,\"result\":{\"diagnostic\":\"done\"},\"revision\":2,\"createdAt\":\"2026-10-04T00:00:00Z\"}");
  }

  private static Plowshare connection(
      MockWebServer server, ObjectNode payload, AtomicInteger requests) throws Exception {
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
                      var response =
                          json.createObjectNode()
                              .put("id", input.path("id").textValue())
                              .put("type", input.path("type").textValue())
                              .put("protocol_version", "plowshare-v1");
                      response.set(
                          "payload",
                          json.createObjectNode().put("code", "OK").set("payload", payload));
                      socket.send(json.writeValueAsString(response));
                    } catch (IOException invalid) {
                      throw new AssertionError(invalid);
                    }
                  }
                }));
    return Plowshare.connect(
        server.url("/").toString(), "fixture", Duration.ofSeconds(2), push -> {});
  }

  @ParameterizedTest
  @ValueSource(strings = {"revision", "state", "result", "remoteTask", "error"})
  void changed_report_receipts_are_refused_without_resubmitting(String field) throws Exception {
    var reply = receipt();
    switch (field) {
      case "revision" -> reply.put(field, 3);
      case "state" -> reply.put(field, "FAILED");
      case "result" -> ((ObjectNode) reply.get(field)).put("diagnostic", "foreign result");
      case "remoteTask" -> reply.put(field, "foreign");
      case "error" -> reply.put(field, "foreign error");
    }
    var requests = new AtomicInteger();
    try (var server = new MockWebServer();
        var connection = connection(server, reply, requests)) {
      var result = new ExternalResult.IntegrationResult(null, null, null, null, "done");
      var report = new Outgoing.Report(ID, 1, "COMPLETED", null, null, result, null);
      assertThrows(IOException.class, () -> new OutgoingClient(connection).report(report));
      assertEquals(1, requests.get());
    }
  }

  @Test
  void an_empty_claim_with_an_action_is_not_a_valid_no_work_response() throws Exception {
    var payload = SdkJson.mapper().createObjectNode().putNull("work").put("action", "send");
    var requests = new AtomicInteger();
    try (var server = new MockWebServer();
        var connection = connection(server, payload, requests)) {
      assertThrows(
          IOException.class,
          () -> new OutgoingClient(connection).claim("selected", java.util.List.of("peer")));
      assertEquals(1, requests.get());
    }
  }

  @Test
  void valid_observations_and_unknown_retained_evidence_have_the_same_identity() {
    var task =
        new ExternalResult.TaskResult(
            new ExternalResult.Task(
                "task",
                "context",
                new ExternalResult.Status("TASK_STATE_WORKING", null, null),
                null,
                null,
                null));
    assertDoesNotThrow(() -> new Outgoing.Report(ID, 1, "WORKING", "task", "context", task, null));
    assertDoesNotThrow(
        () -> new Outgoing.Report(ID, 1, "UNKNOWN", "task", "context", task, "connection lost"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Outgoing.Report(ID, 1, "COMPLETED", "task", "context", task, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Outgoing.Report(ID, 1, "WORKING", "foreign", "context", task, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Outgoing.Report(ID, 1, "WORKING", "task", "foreign", task, null));
  }
}
