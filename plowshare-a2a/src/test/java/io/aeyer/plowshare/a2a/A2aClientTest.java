package io.aeyer.plowshare.a2a;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.time.Duration;
import java.util.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;

class A2aClientTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void sends_1_0_message_polls_task_and_cancels_with_opaque_artifacts() throws Exception {
    try (var server = new MockWebServer()) {
      var methods = new ArrayList<String>();
      server.setDispatcher(
          new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
              try {
                JsonNode rpc = JSON.readTree(request.getBody().readUtf8());
                String method = rpc.path("method").asText();
                methods.add(method);
                assertEquals("1.0", request.getHeader("A2A-Version"));
                assertEquals("Bearer peer-token", request.getHeader("Authorization"));
                Object task =
                    Map.of(
                        "id",
                        "remote-task",
                        "contextId",
                        "remote-context",
                        "status",
                        Map.of(
                            "state",
                            method.equals("CancelTask")
                                ? "TASK_STATE_CANCELED"
                                : "TASK_STATE_WORKING"),
                        "artifacts",
                        List.of(
                            Map.of(
                                "artifactId",
                                "output",
                                "parts",
                                List.of(
                                    Map.of(
                                        "url",
                                        "https://example.invalid/output.txt",
                                        "filename",
                                        "output.txt")))));
                if (method.equals("SendMessage")) {
                  assertTrue(
                      rpc.path("params")
                          .path("configuration")
                          .path("returnImmediately")
                          .asBoolean());
                  assertFalse(rpc.path("params").path("configuration").has("blocking"));
                  assertEquals(
                      "ROLE_USER", rpc.path("params").path("message").path("role").asText());
                  assertEquals(
                      3,
                      rpc.path("params").path("message").path("parts").get(1).path("data").size());
                } else assertEquals("remote-task", rpc.path("params").path("id").asText());
                return new MockResponse()
                    .setBody(
                        JSON.writeValueAsString(
                            Map.of(
                                "jsonrpc",
                                "2.0",
                                "id",
                                rpc.get("id"),
                                "result",
                                method.equals("SendMessage") ? Map.of("task", task) : task)));
              } catch (Exception failed) {
                throw new AssertionError(failed);
              }
            }
          });
      try (var client =
          new A2aClient(server.url("/rpc").toString(), "peer-token", Duration.ofSeconds(3))) {
        var work =
            client.send(
                UUID.randomUUID(),
                Map.of(
                    "parts", List.of(Map.of("text", "hello"), Map.of("data", List.of(1, 2, 3)))));
        assertEquals("WORKING", work.state());
        assertEquals("remote-context", work.context());
        assertTrue(
            JSON.valueToTree(work.result())
                .path("task")
                .path("artifacts")
                .get(0)
                .path("parts")
                .get(0)
                .has("url"));
        assertEquals("WORKING", client.status(work.task(), work.context()).state());
        assertEquals("CANCELED", client.cancel(work.task(), work.context()).state());
        assertEquals(List.of("SendMessage", "GetTask", "CancelTask"), methods);
      }
    }
  }

  @Test
  void invalid_input_performs_no_remote_call() throws Exception {
    try (var server = new MockWebServer();
        var client = new A2aClient(server.url("/rpc").toString(), null, Duration.ofSeconds(3))) {
      assertThrows(
          A2aClient.InvalidMessage.class,
          () ->
              client.send(
                  UUID.randomUUID(),
                  Map.of("parts", List.of(Map.of("text", "one", "url", "two")))));
      assertEquals(0, server.getRequestCount());
    }
  }

  @Test
  void retry_after_zero_cannot_replay_paid_work() throws Exception {
    try (var server = new MockWebServer();
        var client = new A2aClient(server.url("/rpc").toString(), null, Duration.ofSeconds(3))) {
      server.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "0"));
      server.enqueue(new MockResponse().setBody("a second request must never reach this response"));
      assertThrows(
          java.io.IOException.class,
          () -> client.send(UUID.randomUUID(), Map.of("parts", List.of(Map.of("text", "hello")))));
      assertEquals(1, server.getRequestCount());
    }
  }

  @Test
  void direct_messages_complete_and_foreign_task_ids_are_refused() throws Exception {
    try (var server = new MockWebServer()) {
      server.setDispatcher(
          new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
              try {
                JsonNode rpc = JSON.readTree(request.getBody().readUtf8());
                Object result =
                    rpc.path("method").asText().equals("SendMessage")
                        ? Map.of(
                            "message",
                            Map.of(
                                "messageId",
                                "answer",
                                "role",
                                "ROLE_AGENT",
                                "parts",
                                List.of(Map.of("text", "hello"))))
                        : Map.of(
                            "id", "foreign", "status", Map.of("state", "TASK_STATE_COMPLETED"));
                return new MockResponse()
                    .setBody(
                        JSON.writeValueAsString(
                            Map.of("jsonrpc", "2.0", "id", rpc.get("id"), "result", result)));
              } catch (Exception failed) {
                throw new AssertionError(failed);
              }
            }
          });
      try (var client = new A2aClient(server.url("/rpc").toString(), null, Duration.ofSeconds(3))) {
        assertEquals(
            "COMPLETED",
            client
                .send(UUID.randomUUID(), Map.of("parts", List.of(Map.of("text", "hello"))))
                .state());
        assertThrows(java.io.IOException.class, () -> client.status("ours", null));
      }
    }
  }

  @Test
  void lost_reply_is_not_retried_or_redirected() throws Exception {
    try (var server = new MockWebServer();
        var client = new A2aClient(server.url("/rpc").toString(), null, Duration.ofMillis(250))) {
      server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
      assertThrows(
          java.io.IOException.class,
          () -> client.send(UUID.randomUUID(), Map.of("parts", List.of(Map.of("text", "hello")))));
      assertEquals(1, server.getRequestCount());
      server.enqueue(
          new MockResponse().setResponseCode(307).setHeader("Location", server.url("/elsewhere")));
      try (var redirectClient =
          new A2aClient(server.url("/rpc").toString(), null, Duration.ofSeconds(3))) {
        assertThrows(
            java.io.IOException.class,
            () ->
                redirectClient.send(
                    UUID.randomUUID(), Map.of("parts", List.of(Map.of("text", "another")))));
      }
      assertEquals(2, server.getRequestCount());
    }
  }
}
