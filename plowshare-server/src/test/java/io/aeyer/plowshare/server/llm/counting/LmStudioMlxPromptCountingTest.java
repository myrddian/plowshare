package io.aeyer.plowshare.server.llm.counting;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.llm.CountingProperties;
import io.aeyer.plowshare.server.llm.OpenAiCompatible;
import io.aeyer.plowshare.server.llm.PoolProperties;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.Content;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class LmStudioMlxPromptCountingTest {
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  private static final String REVISION = "a".repeat(64);

  private static PoolProperties settings(MockWebServer server) {
    var pool = new PoolProperties();
    pool.setName("mlx-fixture");
    pool.setBaseUrl(server.url("/v1").toString());
    pool.setApiKey("fixture-token");
    pool.getCounting().setChatProtocol(CountingProperties.ChatProtocol.LMSTUDIO_MLX_INPUT_TOKENS);
    pool.getCounting().setUrl(server.url("/v1/chat/completions/input_tokens").toString());
    pool.getCounting().setRevision(REVISION);
    pool.getCounting().setCacheEntries(0);
    return pool;
  }

  private static ChatRequest request(String mime) {
    return ChatRequest.of("gemma", null, "inspect")
        .withMessages(
            List.of(
                ChatMessage.user(
                    "inspect",
                    List.of(new Content.Image("fixture", "data:" + mime + ";base64,AQID")))))
        .withAttribution(UsageAttribution.global("alice", UsageAttribution.Operation.AGENT_CHAT));
  }

  private static MockResponse count(long value) {
    return new MockResponse()
        .setBody(
            "{\"object\":\"response.input_tokens\",\"model\":\"gemma\",\"revision\":\""
                + REVISION
                + "\",\"input_tokens\":"
                + value
                + "}");
  }

  @ParameterizedTest
  @ValueSource(strings = {"image/png", "image/jpeg", "image/webp"})
  void counts_use_existing_native_projection_and_every_exchange_revalidates_revision(String mime)
      throws Exception {
    try (var server = new MockWebServer()) {
      server.start();
      var pool = settings(server);
      pool.getCounting().setImageTokensIncluded(true);
      server.enqueue(count(272));
      server.enqueue(count(280));
      try (var provider = new OpenAiCompatible(pool, JSON)) {
        var request = request(mime);
        var first = provider.countChat("gemma", request);
        assertEquals(272L, first.tokens());
        assertEquals("LMSTUDIO_MLX_CHAT_INPUT_TOKENS", first.source());
        assertEquals(REVISION, first.revision());
        assertEquals(280L, provider.countChat("gemma", request).tokens());
        assertFalse(first.cached());
        var posted = server.takeRequest();
        assertEquals("Bearer fixture-token", posted.getHeader("Authorization"));
        var body = JSON.readTree(posted.getBody().readUtf8());
        assertEquals(
            "data:" + mime + ";base64,AQID",
            body.path("messages")
                .get(0)
                .path("content")
                .get(1)
                .path("image_url")
                .path("url")
                .asText());
        assertEquals(2, server.getRequestCount());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"object\":\"response.input_tokens\",\"model\":\"gemma\",\"input_tokens\":15}",
        "{\"object\":\"response.input_tokens\",\"model\":\"gemma\",\"revision\":\"wrong\",\"input_tokens\":15}",
        "{\"object\":\"response.input_tokens\",\"revision\":\"REVISION\",\"input_tokens\":15}",
        "{\"object\":\"response.input_tokens\",\"model\":\"wrong\",\"revision\":\"REVISION\",\"input_tokens\":15}"
      })
  void incomplete_or_changed_bindings_remain_unknown(String response) throws Exception {
    try (var server = new MockWebServer()) {
      server.start();
      var pool = settings(server);
      pool.getCounting().setImageTokensIncluded(true);
      server.enqueue(new MockResponse().setBody(response.replace("REVISION", REVISION)));
      server.enqueue(count(272));
      try (var provider = new OpenAiCompatible(pool, JSON)) {
        assertEquals(
            PromptCount.Basis.UNKNOWN, provider.countChat("gemma", request("image/png")).basis());
        assertEquals(272L, provider.countChat("gemma", request("image/png")).tokens());
      }
    }
  }

  @Test
  void a_numeric_revision_is_not_a_valid_revision_echo() throws Exception {
    try (var server = new MockWebServer()) {
      server.start();
      var pool = settings(server);
      pool.getCounting().setImageTokensIncluded(true);
      pool.getCounting().setRevision("1".repeat(64));
      server.enqueue(
          new MockResponse()
              .setBody(
                  "{\"object\":\"response.input_tokens\",\"model\":\"gemma\",\"revision\":"
                      + "1".repeat(64)
                      + ",\"input_tokens\":272}"));
      try (var provider = new OpenAiCompatible(pool, JSON)) {
        assertEquals(
            PromptCount.Basis.UNKNOWN, provider.countChat("gemma", request("image/png")).basis());
      }
    }
  }

  @Test
  void image_admission_remains_opt_in_and_cache_or_cross_origin_configuration_is_refused()
      throws Exception {
    try (var server = new MockWebServer()) {
      server.start();
      var pool = settings(server);
      try (var provider = new OpenAiCompatible(pool, JSON)) {
        assertEquals(
            List.of("unsupported_images"),
            provider.countChat("gemma", request("image/png")).gaps());
        assertEquals(0, server.getRequestCount());
      }
      pool.getCounting().setCacheEntries(1);
      assertThrows(
          IllegalArgumentException.class, () -> pool.getCounting().validate(pool.getBaseUrl()));
      pool.getCounting().setCacheEntries(0);
      pool.getCounting().setUrl("https://count-fixture.invalid/v1/chat/completions/input_tokens");
      assertThrows(
          IllegalArgumentException.class, () -> pool.getCounting().validate(pool.getBaseUrl()));
      assertEquals(
          CountingProperties.ChatProtocol.VLLM_TOKENIZE,
          new CountingProperties().getChatProtocol());
    }
  }
}
