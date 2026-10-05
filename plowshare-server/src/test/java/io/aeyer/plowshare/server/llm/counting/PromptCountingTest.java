package io.aeyer.plowshare.server.llm.counting;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.llm.PoolProperties;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.*;
import io.aeyer.plowshare.server.llm.openai.OpenAiTransport;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class PromptCountingTest {
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  private static final UsageAttribution ALICE =
      UsageAttribution.global("alice", UsageAttribution.Operation.AGENT_CHAT);

  private static ChatRequest request() {
    return ChatRequest.of("model", "system", "question").withAttribution(ALICE);
  }

  private static MockResponse count(long n) {
    return new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("{\"count\":" + n + ",\"max_model_len\":8192,\"tokens\":[]}");
  }

  private static PoolProperties settings(MockWebServer server) {
    var p = new PoolProperties();
    p.setName("fixture");
    p.setBaseUrl(server.url("/proxy/v1").toString());
    p.setApiKey("fixture-token");
    p.getCounting().setUrl(server.url("/proxy/tokenize").toString());
    p.getCounting().setRevision("template-v1");
    return p;
  }

  @Test
  void full_wire_messages_tools_and_template_options_match_generation() throws Exception {
    try (var server = new MockWebServer()) {
      server.start();
      var p = settings(server);
      p.setChatTemplateKwargs(new io.aeyer.plowshare.server.llm.ChatTemplateOptions(false));
      server.enqueue(count(42));
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "application/json")
              .setBody(
                  """
                    {"choices":[{"message":{"content":"answer"},"finish_reason":"stop"}],
                    "usage":{"prompt_tokens":42,"completion_tokens":2,"total_tokens":44}}
                    """));
      var tool =
          ToolSchema.from("lookup", "find", Map.of("type", "object", "properties", Map.of()));
      var r = request().withTools(List.of(tool));
      try (var transport = new OpenAiTransport(p, JSON)) {
        var counted = transport.countChat("model", r);
        var answer = transport.complete("model", r.messages(), r.sampling(), r.tools());
        assertEquals(answer.usage().observation().inputTokens(), counted.tokens());
        assertEquals(PromptCount.Basis.MEASURED, counted.basis());
        var tokenize = server.takeRequest();
        var generated = server.takeRequest();
        assertEquals("/proxy/tokenize", tokenize.getPath());
        assertEquals("Bearer fixture-token", tokenize.getHeader("Authorization"));
        var a = JSON.readTree(tokenize.getBody().readUtf8());
        var b = JSON.readTree(generated.getBody().readUtf8());
        for (String key : List.of("messages", "tools", "model", "chat_template_kwargs"))
          assertEquals(b.get(key), a.get(key));
        assertTrue(a.path("add_generation_prompt").asBoolean());
        assertFalse(a.path("add_special_tokens").asBoolean());
      }
    }
  }

  @Test
  void cache_separates_accounts_models_inputs_and_revisions() throws Exception {
    try (var server = new MockWebServer()) {
      server.start();
      var p = settings(server);
      for (int i = 0; i < 5; i++) server.enqueue(count(100 + i));
      try (var t = new OpenAiTransport(p, JSON)) {
        assertFalse(t.countChat("model", request()).cached());
        assertTrue(t.countChat("model", request()).cached());
        assertFalse(
            t.countChat(
                    "model",
                    request()
                        .withAttribution(
                            UsageAttribution.global("bob", UsageAttribution.Operation.AGENT_CHAT)))
                .cached());
        assertFalse(t.countChat("other", request()).cached());
        assertFalse(
            t.countChat("model", request().withMessages(List.of(ChatMessage.user("changed"))))
                .cached());
        p.getCounting().setRevision("template-v2");
        assertEquals(104L, t.countChat("model", request()).tokens());
        assertEquals(5, server.getRequestCount());
      }
    }
  }

  @Test
  void embedding_batch_uses_scalar_prompt_requests_and_sums_counts() throws Exception {
    try (var server = new MockWebServer()) {
      server.start();
      var p = settings(server);
      p.getCounting().setEmbeddings(true);
      server.enqueue(count(3));
      server.enqueue(count(4));
      try (var t = new OpenAiTransport(p, JSON)) {
        var r = EmbeddingRequest.of("model", List.of("a", "b")).withAttribution(ALICE);
        assertEquals(7L, t.countEmbedding("model", r).tokens());
        assertTrue(
            JSON.readTree(server.takeRequest().getBody().readUtf8()).path("prompt").isTextual());
        assertTrue(
            JSON.readTree(server.takeRequest().getBody().readUtf8())
                .path("add_special_tokens")
                .asBoolean());
      }
    }
  }

  @Test
  void unsupported_fields_do_not_issue_a_count_request() throws Exception {
    try (var server = new MockWebServer()) {
      server.start();
      try (var t = new OpenAiTransport(settings(server), JSON)) {
        var r =
            request()
                .withTools(List.of(ToolSchema.from("lookup", "find", Map.of("type", "object"))))
                .withToolChoice(ToolChoice.REQUIRED);
        assertEquals(PromptCount.Basis.UNKNOWN, t.countChat("model", r).basis());
        assertEquals(
            PromptCount.Basis.UNKNOWN,
            t.countEmbedding("model", EmbeddingRequest.of("model", List.of("a"))).basis());
        var image =
            request()
                .withMessages(
                    List.of(
                        ChatMessage.user(
                            "describe",
                            List.of(
                                new Content.Image(
                                    "fixture-image", "data:image/png;base64,AA==")))));
        assertEquals(PromptCount.Basis.UNKNOWN, t.countChat("model", image).basis());
        assertEquals(0, server.getRequestCount());
      }
    }
  }

  @Test
  void bounded_cache_evicts_and_template_auth_changes_invalidate_measured_counts()
      throws Exception {
    try (var server = new MockWebServer()) {
      server.start();
      var p = settings(server);
      p.getCounting().setCacheEntries(1);
      for (int i = 0; i < 6; i++) server.enqueue(count(10 + i));
      var changed = request().withMessages(List.of(ChatMessage.user("other input")));
      try (var t = new OpenAiTransport(p, JSON)) {
        assertEquals(10L, t.countChat("model", request()).tokens());
        assertTrue(t.countChat("model", request()).cached());
        assertEquals(11L, t.countChat("model", changed).tokens());
        assertEquals(12L, t.countChat("model", request()).tokens());
        p.setChatTemplateKwargs(new io.aeyer.plowshare.server.llm.ChatTemplateOptions(false));
        assertEquals(13L, t.countChat("model", request()).tokens());
        p.setApiKey("changed-fixture-key");
        assertEquals(14L, t.countChat("model", request()).tokens());
        p.getCounting().setCacheTtl(Duration.ZERO);
        assertEquals(15L, t.countChat("model", request()).tokens());
        server.enqueue(count(16));
        assertFalse(t.countChat("model", request()).cached());
        assertEquals(7, server.getRequestCount());
      }
    }
  }

  @Test
  void malformed_and_wrong_model_responses_are_unknown_and_not_cached() throws Exception {
    try (var server = new MockWebServer()) {
      server.start();
      server.enqueue(new MockResponse().setBody("{\"count\":-1}"));
      server.enqueue(new MockResponse().setBody("{\"count\":4,\"model\":\"wrong\"}"));
      server.enqueue(count(5));
      try (var t = new OpenAiTransport(settings(server), JSON)) {
        assertEquals(PromptCount.Basis.UNKNOWN, t.countChat("model", request()).basis());
        assertEquals(List.of("counter_model_mismatch"), t.countChat("model", request()).gaps());
        assertEquals(5L, t.countChat("model", request()).tokens());
      }
    }
  }

  @Test
  void counter_deadline_and_redirect_preserve_credentials_and_return_unknown() throws Exception {
    try (var server = new MockWebServer();
        var other = new MockWebServer()) {
      server.start();
      other.start();
      var p = settings(server);
      p.getCounting().setTimeout(Duration.ofMillis(100));
      server.enqueue(new MockResponse().setBody("{\"count\":1}").setBodyDelay(1, TimeUnit.SECONDS));
      server.enqueue(
          new MockResponse().setResponseCode(302).setHeader("Location", other.url("/steal")));
      try (var t = new OpenAiTransport(p, JSON)) {
        assertEquals(PromptCount.Basis.UNKNOWN, t.countChat("model", request()).basis());
        assertEquals(PromptCount.Basis.UNKNOWN, t.countChat("model", request()).basis());
        assertEquals(0, other.getRequestCount());
      }
    }
  }

  @Test
  void cross_origin_configuration_is_refused_and_large_counts_serialize_as_strings()
      throws Exception {
    try (var server = new MockWebServer()) {
      server.start();
      var p = settings(server);
      p.getCounting().setUrl("https://elsewhere.example/tokenize");
      assertThrows(IllegalArgumentException.class, () -> p.getCounting().validate(p.getBaseUrl()));
      var count =
          new PromptCount(
              Long.MAX_VALUE,
              PromptCount.Basis.MEASURED,
              "VLLM_TOKENIZE_0_15_1",
              "pool",
              "model",
              null,
              java.time.Instant.now(),
              0,
              false,
              List.of());
      assertEquals(
          Long.toString(Long.MAX_VALUE),
          JSON.readTree(JSON.writeValueAsString(count)).path("tokens").textValue());
    }
  }
}
