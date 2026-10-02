package io.aeyer.plowshare.server.llm.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.llm.PoolProperties;
import io.aeyer.plowshare.server.llm.accounting.CostResult;
import io.aeyer.plowshare.server.llm.accounting.RateCard;
import io.aeyer.plowshare.server.llm.accounting.UsageObservation;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Lane;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import java.math.BigDecimal;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class OpenAiUsageTest {

    @Test
    void blocking_content_survives_invalid_usage_with_safe_diagnostics() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(json("""
                    {"choices":[{"message":{"content":"Answer"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":-1,"completion_tokens":"secret","total_tokens":9223372036854775808}}
                    """));
            server.start();
            try (OpenAiTransport transport = transport(server)) {
                Completion answer = transport.complete("model", ChatMessage.conversation(null, "question"),
                        Sampling.NONE, List.of());
                assertEquals("Answer", answer.content());
                assertNull(answer.usage().promptTokens());
                assertEquals(UsageObservation.Coverage.INVALID, answer.usage().observation().coverage(Lane.CHAT));
                assertEquals(3, answer.usage().observation().issues().size());
                assertEquals(1, server.getRequestCount());
            }
        }
    }

    @Test
    void final_empty_choice_usage_replaces_earlier_snapshots_and_preserves_cache_details() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("""
                    data: {"choices":[{"delta":{"content":"Answer"},"finish_reason":null}],"usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105}}

                    data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                    data: {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,"prompt_tokens_details":{"cached_tokens":80},"completion_tokens_details":{"reasoning_tokens":12}}}

                    data: [DONE]

                    """));
            server.start();
            try (OpenAiTransport transport = transport(server)) {
                Completion answer = transport.stream("model", ChatMessage.conversation(null, "question"),
                        Sampling.NONE, List.of(), token -> { }, () -> false);
                assertEquals("Answer", answer.content());
                UsageObservation usage = answer.usage().observation();
                assertEquals(100L, usage.inputTokens());
                assertEquals(20L, usage.outputTokens());
                assertEquals(80L, usage.cacheReadTokens());
                assertEquals(12L, usage.reasoningTokens());
                RateCard card = new RateCard("v1", "route", "model", RateCard.Mode.TOKEN, "USD",
                        null, null, new RateCard.Rates(BigDecimal.ONE, new BigDecimal("4"),
                                new BigDecimal("0.25"), null), List.of(), null, "operator");
                CostResult cost = card.quote(usage, Lane.CHAT);
                assertEquals(CostResult.Kind.ESTIMATED, cost.kind());
                assertEquals(0, new BigDecimal("0.000120").compareTo(cost.amount()));
            }
        }
    }

    @Test
    void embedding_provider_counts_above_integer_range_remain_measured_and_priceable() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(json("""
                    {"data":[{"index":0,"embedding":[0.1,0.2]}],
                     "usage":{"prompt_tokens":2147483648,"total_tokens":2147483648}}
                    """));
            server.start();
            try (OpenAiTransport transport = transport(server)) {
                var answer = transport.embed("embed", List.of("input"));
                assertEquals(1, answer.vectors().size());
                assertNull(answer.usage().promptTokens());
                assertEquals(2_147_483_648L, answer.usage().observation().inputTokens());
                assertEquals(UsageObservation.Coverage.COMPLETE,
                        answer.usage().observation().coverage(Lane.EMBEDDING));
                RateCard card = new RateCard("v1", "route", "embed", RateCard.Mode.TOKEN, "USD",
                        null, null, new RateCard.Rates(BigDecimal.ONE, null, null, null),
                        List.of(), null, "operator");
                assertTrue(card.quote(answer.usage().observation(), Lane.EMBEDDING).amount().signum() > 0);
            }
        }
    }

    private static OpenAiTransport transport(MockWebServer server) {
        var properties = new PoolProperties();
        properties.setName("fixture");
        properties.setBaseUrl(server.url("/v1").toString());
        return new OpenAiTransport(properties, new ObjectMapper());
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
