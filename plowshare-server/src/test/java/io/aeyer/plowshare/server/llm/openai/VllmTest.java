package io.aeyer.plowshare.server.llm.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.llm.OpenAiCompatible;
import io.aeyer.plowshare.server.llm.PoolProperties;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransportException;
import io.aeyer.plowshare.server.llm.dispatch.ReasoningStoppedException;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.vllm.Vllm;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class VllmTest {
  private static Sampling sampling() {
    return Sampling.NONE
        .withMaxTokens(8192)
        .withReasoningEffort(Sampling.Effort.MEDIUM)
        .withReasoning(ReasoningMonitorTest.policy());
  }

  private static PoolProperties properties(MockWebServer server) {
    PoolProperties properties = new PoolProperties();
    properties.setName("fixture");
    properties.setBaseUrl(server.url("/v1").toString());
    return properties;
  }

  @Test
  void onlyVllmSendsTheThinkingBudgetAndHostLimitsStayPrivate() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/json")
              .setBody(
                  "{\"choices\":[{\"message\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}"));
      try (Vllm driver = new Vllm(properties(server), new ObjectMapper())) {
        assertEquals(
            "done",
            driver
                .complete("fixture", List.of(ChatMessage.user("review")), sampling(), List.of())
                .content());
      }
      var request = server.takeRequest(1, TimeUnit.SECONDS);
      var body = new ObjectMapper().readTree(request.getBody().readUtf8());
      assertEquals(4096, body.path("thinking_token_budget").asInt());
      assertEquals("medium", body.path("reasoning_effort").asText());
      assertTrue(body.path("reasoning").isMissingNode());
      try (OpenAiCompatible standard =
          new OpenAiCompatible(properties(server), new ObjectMapper())) {
        assertThrows(
            IllegalArgumentException.class,
            () ->
                standard.complete(
                    "fixture", List.of(ChatMessage.user("review")), sampling(), List.of()));
      }
      assertEquals(1, server.getRequestCount());
    }
  }

  @Test
  void partialAnswersAndToolFragmentsNeverBecomeReasoningRecoveryFailures() throws Exception {
    for (boolean tool : List.of(false, true)) {
      try (MockWebServer server = new MockWebServer()) {
        String progress =
            tool
                ? "{\"tool_calls\":[{\"index\":0,\"id\":\"call\",\"type\":\"function\",\"function\":{\"name\":\"probe\",\"arguments\":\"{}\"}}]}"
                : "{\"content\":\"draft\"}";
        String body =
            "data: {\"choices\":[{\"delta\":"
                + progress
                + "}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"This is a repeated long thought without a new conclusion. \"}}]}\n\n"
                    .repeat(20)
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\""
                + (tool ? "tool_calls" : "stop")
                + "\"}]}\n\n"
                + "data: [DONE]\n\n";
        server.enqueue(
            new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body));
        try (Vllm driver = new Vllm(properties(server), new ObjectMapper())) {
          var completion =
              driver.stream(
                  "fixture",
                  List.of(ChatMessage.user("review")),
                  sampling(),
                  List.of(),
                  Deltas.DISCARDING,
                  () -> false);
          assertEquals(tool ? 1 : 0, completion.toolCalls().size());
          assertEquals(tool ? "" : "draft", completion.content());
        }
        assertEquals(1, server.getRequestCount());
      }
    }
  }

  @Test
  void expiredOriginalDeadlinePreventsAnotherProviderRequest() throws Exception {
    try (MockWebServer server = new MockWebServer();
        Vllm driver = new Vllm(properties(server), new ObjectMapper())) {
      Sampling expired =
          sampling()
              .withReasoning(
                  ReasoningMonitorTest.policy()
                      .admitted(System.nanoTime() - Duration.ofSeconds(31).toNanos()));
      LlmTransportException failure =
          assertThrows(
              LlmTransportException.class,
              () ->
                  driver.stream(
                      "fixture",
                      List.of(ChatMessage.user("review")),
                      expired,
                      List.of(),
                      Deltas.DISCARDING,
                      () -> false));
      assertTrue(failure.timedOut());
      assertThrows(
          LlmTransportException.class,
          () ->
              driver.complete("fixture", List.of(ChatMessage.user("review")), expired, List.of()));
      assertEquals(0, server.getRequestCount());
    }
  }

  @Test
  void repeatedThinkingCancelsAndDoesNotReplayAtTheProviderBoundary() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      String chunk =
          "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"Let me reconsider that same assumption once again. \"}}]}\n\n";
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "text/event-stream")
              .setBody(chunk.repeat(20))
              .setBodyDelay(1, TimeUnit.MILLISECONDS));
      try (Vllm driver = new Vllm(properties(server), new ObjectMapper())) {
        assertThrows(
            ReasoningStoppedException.class,
            () ->
                driver.stream(
                    "fixture",
                    List.of(ChatMessage.user("review")),
                    sampling(),
                    List.of(),
                    Deltas.DISCARDING,
                    () -> false));
      }
      assertEquals(1, server.getRequestCount());
      var request = server.takeRequest(1, TimeUnit.SECONDS);
      assertTrue(request.getBody().readUtf8().contains("thinking_token_budget"));
    }
  }
}
