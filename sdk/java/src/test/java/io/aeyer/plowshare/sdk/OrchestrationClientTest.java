package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.Orchestration;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class OrchestrationClientTest {
  static Stream<String> invalidStarts() {
    return Stream.of(
        "{\"agent\":\"caller\",\"definition\":\"work\",\"request\":\"task\",\"requestId\":1}",
        "{\"agent\":\"caller\",\"definition\":\"work\",\"request\":\"task\",\"requestId\":\"bad\"}",
        "{\"agent\":\"caller\",\"definition\":\"work\",\"request\":\"task\",\"requestId\":\"00000000-0000-0000-0000-000000000001\",\"owner\":\"forged\"}",
        "{\"agent\":\"caller\\nforged\",\"definition\":\"work\",\"request\":\"task\",\"requestId\":\"00000000-0000-0000-0000-000000000001\"}");
  }

  @ParameterizedTest
  @MethodSource("invalidStarts")
  void malformed_external_inputs_are_rejected_before_submission(String input) {
    assertThrows(IOException.class, () -> OrchestrationClient.decodeStart(input));
  }

  @Test
  void a_foreign_mutation_receipt_is_rejected_without_replay() throws Exception {
    UUID requestId = UUID.randomUUID();
    AtomicInteger received = new AtomicInteger();
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
                                + "\",\"type\":\"orchestration.start\",\"protocol_version\":\"plowshare-v1\",\"payload\":{\"code\":\"ACCEPTED\",\"payload\":{\"id\":\"orc_other\",\"state\":\"running\",\"requestId\":\""
                                + UUID.randomUUID()
                                + "\"}}}");
                      } catch (IOException error) {
                        throw new AssertionError(error);
                      }
                    }
                  }));
      try (var connection =
          LegacyFixture.connect(
              server.url("/").toString(), "fixture", Duration.ofSeconds(3), null)) {
        var sdk = new OrchestrationClient(connection);
        IOException failure =
            assertThrows(
                IOException.class,
                () ->
                    sdk.start(
                        new Orchestration.Start("caller", "work", "task", null, null, requestId)));
        assertTrue(failure.getMessage().contains("outcome unconfirmed"));
        assertEquals(1, received.get());
      }
    }
  }

  @Test
  void malformed_nested_question_and_counter_fields_never_become_status_dtos() throws Exception {
    var json = SdkJson.mapper();
    var validRun =
        "{\"id\":\"orc_1\",\"definition\":\"work\",\"tier\":\"project\",\"project\":\"repo\",\"state\":\"running\",\"returnsUsed\":0,\"maxReturns\":2,\"nudges\":0,\"restarts\":0,\"depth\":0,\"conductorConversation\":\"cnv_1\",\"createdAt\":\"2026-10-04T00:00:00Z\"}";
    for (String run :
        List.of(
            validRun.replace("\"returnsUsed\":0,", ""),
            validRun.replace("\"depth\":0", "\"depth\":1.5"),
            validRun.replace("\"nudges\":0", "\"nudges\":-1"))) {
      assertThrows(
          IOException.class,
          () ->
              SdkJson.decode(
                  json,
                  json.readTree(
                      "{\"orchestration\":"
                          + run
                          + ",\"todos\":[],\"messages\":[],\"children\":[]}"),
                  Orchestration.Status.class));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Orchestration.Question(
                "Db",
                "Which?",
                false,
                List.of(new Orchestration.Option("only", "one option", null))));
  }
}
