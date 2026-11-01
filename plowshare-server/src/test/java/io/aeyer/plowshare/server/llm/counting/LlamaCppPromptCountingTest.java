package io.aeyer.plowshare.server.llm.counting;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.llm.CountingProperties;
import io.aeyer.plowshare.server.llm.OpenAiCompatible;
import io.aeyer.plowshare.server.llm.PoolProperties;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.*;
import io.aeyer.plowshare.server.llm.lmstudio.LmStudio;
import io.aeyer.plowshare.server.llm.lmstudio.LmStudioSocket;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class LlamaCppPromptCountingTest {
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  private static PoolProperties settings(MockWebServer server) {
    var properties = new PoolProperties();
    properties.setName("studio-fixture");
    properties.setBaseUrl(server.url("/proxy/v1").toString());
    properties.setApiKey("fixture-token");
    properties
        .getCounting()
        .setUrl(server.url("/proxy/v1/chat/completions/input_tokens").toString());
    properties
        .getCounting()
        .setChatProtocol(CountingProperties.ChatProtocol.LLAMA_CPP_INPUT_TOKENS);
    properties.getCounting().setRevision("fixture-model-projector-template-v1");
    properties.getCounting().setImageTokensIncluded(true);
    return properties;
  }

  private static ChatRequest request() {
    return ChatRequest.of("gemma", "system", "question")
        .withMessages(
            List.of(ChatMessage.user("inspect", List.of(image("image/png", "iVBORw0KGgo=")))))
        .withAttribution(UsageAttribution.global("alice", UsageAttribution.Operation.AGENT_CHAT));
  }

  private static Content.Image image(String mime, String bytes) {
    return new Content.Image("fixture-image", "data:" + mime + ";base64," + bytes);
  }

  private static MockResponse count(long tokens) {
    return new MockResponse()
        .setBody("{\"object\":\"response.input_tokens\",\"input_tokens\":" + tokens + "}");
  }

  @Test
  void lmstudio_http_uses_the_same_native_body_as_generation_including_tools_and_template_fields()
      throws Exception {
    try (var server = new MockWebServer()) {
      server.start();
      var properties = settings(server);
      var tool =
          ToolSchema.from("lookup", "find", Map.of("type", "object", "properties", Map.of()));
      var request =
          request()
              .withTools(List.of(tool))
              .withToolChoice(ToolChoice.REQUIRED)
              .withSampling(
                  Sampling.NONE.withMaxTokens(32).withReasoningEffort(Sampling.Effort.LOW));
      server.enqueue(count(1024));
      server.enqueue(
          new MockResponse()
              .setBody(
                  """
          {"choices":[{"message":{"content":"answer"},"finish_reason":"stop"}],
           "usage":{"prompt_tokens":1024,"completion_tokens":2,"total_tokens":1026}}
          """));
      try (var base = new OpenAiCompatible(properties, JSON)) {
        var provider = new LmStudio(base);
        var counted = provider.countChat("gemma", request);
        var generated =
            provider.complete(
                "gemma",
                request.messages(),
                request.sampling(),
                request.tools(),
                request.toolChoice(),
                InferenceObserver.NONE);
        assertEquals(PromptCount.Basis.MEASURED, counted.basis());
        assertEquals("LLAMA_CPP_CHAT_INPUT_TOKENS", counted.source());
        assertEquals("studio-fixture", counted.pool());
        assertEquals("gemma", counted.model());
        assertEquals(properties.getCounting().getRevision(), counted.revision());
        assertTrue(counted.gaps().isEmpty());
        assertEquals(generated.usage().observation().inputTokens(), counted.tokens());
        var counter = server.takeRequest();
        var inference = server.takeRequest();
        assertEquals("/proxy/v1/chat/completions/input_tokens", counter.getPath());
        assertEquals("Bearer fixture-token", counter.getHeader("Authorization"));
        assertEquals(
            JSON.readTree(inference.getBody().readUtf8()),
            JSON.readTree(counter.getBody().readUtf8()));
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"image/png", "image/jpeg", "image/webp"})
  void every_occurrence_is_counted_as_native_media_and_cache_is_scoped_to_actual_bytes(String mime)
      throws Exception {
    try (var server = new MockWebServer()) {
      server.start();
      var properties = settings(server);
      var repeated = image(mime, "AQID");
      var request =
          request().withMessages(List.of(ChatMessage.user("inspect", List.of(repeated, repeated))));
      server.enqueue(count(500));
      server.enqueue(count(501));
      try (var provider = new OpenAiCompatible(properties, JSON)) {
        assertEquals(500L, provider.countChat("gemma", request).tokens());
        assertTrue(provider.countChat("gemma", request).cached());
        var body = JSON.readTree(server.takeRequest().getBody().readUtf8());
        assertEquals(
            repeated.dataUri(),
            body.path("messages")
                .get(0)
                .path("content")
                .get(1)
                .path("image_url")
                .path("url")
                .asText());
        assertEquals(
            body.path("messages").get(0).path("content").get(1),
            body.path("messages").get(0).path("content").get(2));
        var changed =
            request.withMessages(
                List.of(ChatMessage.user("inspect", List.of(image(mime, "BAUG")))));
        assertEquals(501L, provider.countChat("gemma", changed).tokens());
        assertEquals(2, server.getRequestCount());
      }
    }
  }

  @Test
  void screenshot_larger_than_the_legacy_text_counter_bound_is_not_dropped_or_truncated()
      throws Exception {
    try (var server = new MockWebServer()) {
      server.start();
      var properties = settings(server);
      var bytes = Base64.getEncoder().encodeToString(new byte[1024 * 1024]);
      var request =
          request()
              .withMessages(
                  List.of(ChatMessage.user("inspect", List.of(image("image/png", bytes)))));
      server.enqueue(count(800));
      try (var provider = new OpenAiCompatible(properties, JSON)) {
        assertEquals(800L, provider.countChat("gemma", request).tokens());
        var posted = server.takeRequest();
        assertTrue(posted.getBodySize() > 1024 * 1024);
        assertTrue(
            JSON.readTree(posted.getBody().readUtf8())
                .path("messages")
                .get(0)
                .path("content")
                .get(1)
                .path("image_url")
                .path("url")
                .asText()
                .endsWith(bytes));
        var text =
            ChatRequest.of("gemma", null, "x".repeat(1024 * 1024))
                .withAttribution(request.attribution());
        assertEquals(PromptCount.Basis.UNKNOWN, provider.countChat("gemma", text).basis());
        assertEquals(1, server.getRequestCount(), "Text counting retains the 1 MiB request bound");
      }
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"count\":42}",
        "{\"object\":\"chat.completion\",\"input_tokens\":42}",
        "{\"object\":\"response.input_tokens\",\"input_tokens\":\"42\"}",
        "{\"object\":\"response.input_tokens\",\"input_tokens\":-1}",
        "{\"object\":\"response.input_tokens\",\"input_tokens\":1.5}",
        "{\"object\":\"response.input_tokens\",\"input_tokens\":9223372036854775808}",
        "{\"object\":\"response.input_tokens\",\"input_tokens\":42,\"error\":\"fixture\"}",
        "{\"object\":\"response.input_tokens\",\"input_tokens\":42,\"truncated\":true}",
        "{\"object\":\"response.input_tokens\",\"input_tokens\":42,\"model\":\"wrong\"}"
      })
  void incomplete_wrong_model_or_malformed_counts_stay_unknown_and_are_not_cached(String response)
      throws Exception {
    try (var server = new MockWebServer()) {
      server.start();
      server.enqueue(new MockResponse().setBody(response));
      server.enqueue(count(42));
      try (var provider = new OpenAiCompatible(settings(server), JSON)) {
        assertEquals(PromptCount.Basis.UNKNOWN, provider.countChat("gemma", request()).basis());
        assertEquals(42L, provider.countChat("gemma", request()).tokens());
        assertEquals(2, server.getRequestCount());
      }
    }
  }

  @Test
  void absent_endpoint_and_redirect_never_fall_back_to_tokenization_or_generation()
      throws Exception {
    try (var server = new MockWebServer();
        var other = new MockWebServer()) {
      server.start();
      other.start();
      server.enqueue(new MockResponse().setResponseCode(404));
      server.enqueue(
          new MockResponse().setResponseCode(302).setHeader("Location", other.url("/steal")));
      server.enqueue(count(42));
      try (var provider = new OpenAiCompatible(settings(server), JSON)) {
        assertEquals(PromptCount.Basis.UNKNOWN, provider.countChat("gemma", request()).basis());
        assertEquals(1, server.getRequestCount());
        assertEquals(PromptCount.Basis.UNKNOWN, provider.countChat("gemma", request()).basis());
        assertEquals(0, other.getRequestCount());
        assertEquals(42L, provider.countChat("gemma", request()).tokens());
        assertEquals(3, server.getRequestCount());
      }
    }
  }

  @Test
  void image_opt_in_and_lmstudio_prefill_restrictions_remain_independent() throws Exception {
    try (var server = new MockWebServer()) {
      server.start();
      var properties = settings(server);
      properties.getCounting().setImageTokensIncluded(false);
      try (var base = new OpenAiCompatible(properties, JSON)) {
        assertEquals(List.of("unsupported_images"), base.countChat("gemma", request()).gaps());
        var socket = new LmStudio(base, mock(LmStudioSocket.class));
        assertEquals(
            List.of("native_template_not_supported"), socket.countChat("gemma", request()).gaps());
        assertEquals(0, server.getRequestCount());
      }
    }
  }

  @Test
  void protocol_changes_invalidate_the_shared_cache_and_preserve_legacy_text_decoding()
      throws Exception {
    try (var server = new MockWebServer()) {
      server.start();
      var properties = settings(server);
      var text = request().withMessages(List.of(ChatMessage.user("question")));
      server.enqueue(count(42));
      server.enqueue(new MockResponse().setBody("{\"count\":43}"));
      server.enqueue(count(44));
      try (var provider = new OpenAiCompatible(properties, JSON)) {
        assertEquals("LLAMA_CPP_CHAT_INPUT_TOKENS", provider.countChat("gemma", text).source());
        assertTrue(provider.countChat("gemma", text).cached());
        properties.getCounting().setChatProtocol(CountingProperties.ChatProtocol.VLLM_TOKENIZE);
        var legacy = provider.countChat("gemma", text);
        assertEquals(43L, legacy.tokens());
        assertEquals("VLLM_TOKENIZE_0_15_1", legacy.source());
        properties
            .getCounting()
            .setChatProtocol(CountingProperties.ChatProtocol.LLAMA_CPP_INPUT_TOKENS);
        assertEquals(44L, provider.countChat("gemma", text).tokens());
        assertEquals(3, server.getRequestCount());
      }
    }
  }

  @Test
  void counter_deadline_remains_bounded_without_caching_failure() throws Exception {
    try (var server = new MockWebServer()) {
      server.start();
      var properties = settings(server);
      properties.getCounting().setTimeout(Duration.ofMillis(100));
      server.enqueue(count(42).setBodyDelay(1, TimeUnit.SECONDS));
      try (var provider = new OpenAiCompatible(properties, JSON)) {
        assertEquals(PromptCount.Basis.UNKNOWN, provider.countChat("gemma", request()).basis());
      }
    }
  }
}
