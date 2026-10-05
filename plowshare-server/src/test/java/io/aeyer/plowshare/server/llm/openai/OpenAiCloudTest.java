package io.aeyer.plowshare.server.llm.openai;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.llm.*;
import io.aeyer.plowshare.server.llm.accounting.*;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Timeout(20)
class OpenAiCloudTest {
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  @TempDir Path directory;

  private static PoolProperties cloud(MockWebServer server) {
    var p = new PoolProperties();
    p.setName("azure");
    p.setBaseUrl(server.url("/openai/v1/").toString());
    p.setApiKey("fixture-azure-key");
    p.setApiAuth(PoolProperties.ApiAuth.AZURE_API_KEY);
    p.setRequestStyle(PoolProperties.RequestStyle.CLOUD);
    p.setBillingRoute("azure-test");
    p.setModels(List.of("deployment-alias"));
    p.setModelFamilies(Map.of("deployment-alias", "declared-family"));
    return p;
  }

  private static MockResponse answer() {
    return new MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
            {"choices":[{"message":{"content":"answer"},"finish_reason":"stop"}],
            "usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}
            """);
  }

  @Test
  void azure_v1_preserves_deployment_path_auth_tools_final_usage_family_and_exact_price_identity()
      throws Exception {
    try (var server = new MockWebServer()) {
      server.start();
      var p = cloud(server);
      var family = new RequestCapabilities();
      family.setStructuredOutput(true);
      family.setTools(true);
      p.setRequestCapabilities(Map.of("declared-family", family));
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "text/event-stream")
              .setHeader("apim-request-id", "fixture-request")
              .setBody(
                  """
                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call1","type":"function","function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":null}]}

                    data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

                    data: {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,"prompt_tokens_details":{"cached_tokens":80},"completion_tokens_details":{"reasoning_tokens":12}}}

                    data: [DONE]

                    """));
      var price = new PriceProperties();
      price.setRevision("fixture-v1");
      price.setBillingRoute("azure-test");
      price.setModel("deployment-alias");
      price.setCurrency("USD");
      price.setRatesPerMillion(
          Map.of(
              "input",
              BigDecimal.ONE,
              "output",
              new BigDecimal("4"),
              "cache-read",
              new BigDecimal("0.25")));
      var properties = new LlmProperties();
      properties.setPools(List.of(p));
      properties.setPricing(Map.of("fixture", price));
      try (var journal =
          new AccountingJournal(directory, 1024 * 1024, 4096, Duration.ofSeconds(1), JSON)) {
        var accounting =
            new DurableInferenceAccounting(
                new AccountingRecorder(journal, Clock.systemUTC(), 10),
                PricingCatalog.from(properties),
                Clock.systemUTC());
        try (var dispatcher =
            new LlmDispatcher(
                List.of(
                    new LlmPool(
                        "azure",
                        p.getModels(),
                        Map.of(),
                        1,
                        1,
                        Duration.ofSeconds(5),
                        new OpenAiCompatible(p, JSON))),
                new NoOpTokenLedger(),
                ignored -> "",
                accounting)) {
          var r =
              ChatRequest.of("deployment-alias", "system", "question")
                  .withTools(
                      List.of(
                          ToolSchema.from(
                              "lookup",
                              "find",
                              Map.of(
                                  "type",
                                  "object",
                                  "properties",
                                  Map.of(),
                                  "additionalProperties",
                                  false))))
                  .withToolChoice(ToolChoice.REQUIRED)
                  .withSampling(Sampling.NONE.withTopK(20).withMaxTokens(1000))
                  .withAttribution(
                      UsageAttribution.global("alice", UsageAttribution.Operation.AGENT_CHAT));
          var completed = dispatcher.stream(r, token -> {});
          assertEquals(100L, completed.usage().observation().inputTokens());
          assertEquals(12L, completed.usage().observation().reasoningTokens());
          assertEquals("lookup", completed.toolCalls().getFirst().name());
          var request = server.takeRequest();
          assertEquals("/openai/v1/chat/completions", request.getPath());
          assertEquals("fixture-azure-key", request.getHeader("api-key"));
          assertNull(request.getHeader("Authorization"));
          var body = JSON.readTree(request.getBody().readUtf8());
          assertEquals("deployment-alias", body.path("model").asText());
          assertFalse(body.has("top_k"));
          assertFalse(body.has("max_tokens"));
          assertEquals(1000, body.path("max_completion_tokens").asInt());
          assertEquals("required", body.path("tool_choice").asText());
          assertTrue(body.path("stream_options").path("include_usage").asBoolean());
          var events = journal.readBatch(100).stream().map(AccountingJournal.Entry::event).toList();
          var created = (AccountingEvent.CallCreated) events.getFirst().payload();
          assertEquals("declared-family", created.modelFamily());
          assertEquals("deployment-alias", created.price().model());
          assertEquals("azure-test", created.billingRoute());
          var finished =
              events.stream()
                  .map(AccountingEvent::payload)
                  .filter(AccountingEvent.AttemptFinished.class::isInstance)
                  .map(AccountingEvent.AttemptFinished.class::cast)
                  .findFirst()
                  .orElseThrow();
          assertEquals("fixture-request", finished.providerRequestId());
          assertEquals(0, new BigDecimal("0.000120").compareTo(finished.cost().amount()));
        }
      }
    }
  }

  @Test
  void declared_reasoning_family_removes_local_and_unsupported_sampling_and_uses_output_limit()
      throws Exception {
    try (var server = new MockWebServer()) {
      server.start();
      var p = cloud(server);
      var c = new RequestCapabilities();
      c.setTemperature(false);
      c.setTopP(false);
      c.setReasoningEfforts(
          Set.of(Sampling.Effort.LOW, Sampling.Effort.MEDIUM, Sampling.Effort.HIGH));
      c.setStructuredOutput(true);
      p.setRequestCapabilities(Map.of("declared-family", c));
      server.enqueue(answer());
      try (var t = new OpenAiTransport(p, JSON)) {
        var sampling =
            Sampling.NONE
                .withTemperature(.7)
                .withTopP(.9)
                .withTopK(20)
                .withReasoningEffort(Sampling.Effort.LOW)
                .withResponseFormat(
                    JsonSchema.from(
                        "answer",
                        Map.of(
                            "type",
                            "object",
                            "properties",
                            Map.of(),
                            "additionalProperties",
                            false)));
        t.complete(
            "deployment-alias", ChatMessage.conversation(null, "question"), sampling, List.of());
        var body = JSON.readTree(server.takeRequest().getBody().readUtf8());
        assertFalse(body.has("temperature"));
        assertFalse(body.has("top_p"));
        assertFalse(body.has("top_k"));
        assertEquals("low", body.path("reasoning_effort").asText());
        assertEquals(4096, body.path("max_completion_tokens").asInt());
        assertEquals("json_schema", body.path("response_format").path("type").asText());
        assertEquals(
            io.aeyer.plowshare.server.llm.counting.PromptCount.Basis.UNKNOWN,
            t.countChat("deployment-alias", ChatRequest.of("deployment-alias", null, "question"))
                .basis());
      }
    }
  }

  @Test
  void dispatcher_refuses_an_undeclared_reasoning_effort_before_it_can_be_silently_omitted()
      throws Exception {
    try (var server = new MockWebServer()) {
      server.start();
      var p = cloud(server);
      try (var dispatcher =
          new LlmDispatcher(
              List.of(
                  new LlmPool(
                      "azure",
                      p.getModels(),
                      Map.of(),
                      1,
                      1,
                      Duration.ofSeconds(5),
                      new OpenAiCompatible(p, JSON))),
              new NoOpTokenLedger())) {
        var request =
            ChatRequest.of("deployment-alias", null, "question")
                .withSampling(Sampling.NONE.withReasoningEffort(Sampling.Effort.HIGH));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.complete(request));
        assertEquals(0, server.getRequestCount());
      }
    }
  }

  @Test
  void conditional_sampling_and_tools_require_explicit_none_for_declared_family() throws Exception {
    try (var server = new MockWebServer()) {
      server.start();
      var p = cloud(server);
      var c = new RequestCapabilities();
      c.setTools(true);
      c.setReasoningEfforts(Set.of(Sampling.Effort.NONE, Sampling.Effort.HIGH));
      c.setSamplingOnlyWithoutReasoning(true);
      c.setToolsOnlyWithoutReasoning(true);
      p.setRequestCapabilities(Map.of("declared-family", c));
      try (var t = new OpenAiTransport(p, JSON)) {
        var high = Sampling.NONE.withTemperature(.5).withReasoningEffort(Sampling.Effort.HIGH);
        assertFalse(t.carries("deployment-alias", high).contains(Sampling.Parameter.TEMPERATURE));
        assertTrue(
            t.carries("deployment-alias", Sampling.NONE.withReasoningEffort(Sampling.Effort.NONE))
                .contains(Sampling.Parameter.TEMPERATURE));
        assertThrows(
            IllegalArgumentException.class,
            () ->
                t.complete(
                    "deployment-alias",
                    ChatMessage.conversation(null, "question"),
                    high,
                    List.of(ToolSchema.from("lookup", "find", Map.of("type", "object")))));
        assertEquals(0, server.getRequestCount());
        server.enqueue(answer());
        t.complete(
            "deployment-alias",
            ChatMessage.conversation(null, "question"),
            Sampling.NONE.withReasoningEffort(Sampling.Effort.NONE),
            List.of(ToolSchema.from("lookup", "find", Map.of("type", "object"))));
        assertEquals(
            "none",
            JSON.readTree(server.takeRequest().getBody().readUtf8())
                .path("reasoning_effort")
                .asText());
      }
    }
  }

  @Test
  void legacy_output_field_and_static_bearer_are_explicit_capabilities() throws Exception {
    try (var server = new MockWebServer()) {
      server.start();
      var p = cloud(server);
      p.setApiAuth(PoolProperties.ApiAuth.BEARER);
      p.getDefaultCapabilities().setOutputLimit(RequestCapabilities.OutputLimit.MAX_TOKENS);
      server.enqueue(answer());
      try (var t = new OpenAiTransport(p, JSON)) {
        t.complete(
            "deployment-alias",
            ChatMessage.conversation(null, "question"),
            Sampling.NONE.withMaxTokens(123),
            List.of());
        var req = server.takeRequest();
        assertEquals("Bearer fixture-azure-key", req.getHeader("Authorization"));
        assertNull(req.getHeader("api-key"));
        assertEquals(123, JSON.readTree(req.getBody().readUtf8()).path("max_tokens").asInt());
      }
    }
  }
}
