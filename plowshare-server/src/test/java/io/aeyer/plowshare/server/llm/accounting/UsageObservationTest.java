package io.aeyer.plowshare.server.llm.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.llm.accounting.UsageObservation.CacheRelation;
import io.aeyer.plowshare.server.llm.accounting.UsageObservation.Coverage;
import io.aeyer.plowshare.server.llm.accounting.UsageObservation.Field;
import io.aeyer.plowshare.server.llm.accounting.UsageObservation.Issue;
import io.aeyer.plowshare.server.llm.accounting.UsageObservation.Problem;
import io.aeyer.plowshare.server.llm.accounting.UsageObservation.Source;
import io.aeyer.plowshare.server.llm.dispatch.Lane;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UsageObservationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void absent_usage_and_reported_zero_have_different_coverage() throws Exception {
        UsageObservation missing = UsageNormalizer.openAi(null);
        assertEquals(UsageObservation.UNKNOWN, missing);
        assertEquals(Coverage.UNKNOWN, missing.coverage(Lane.CHAT));
        assertNull(missing.inputTokens());
        UsageObservation zero = openAi("""
                {"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}
                """);
        assertEquals(0L, zero.inputTokens());
        assertEquals(Coverage.COMPLETE, zero.coverage(Lane.CHAT));
        assertEquals(Source.PROVIDER, zero.source());
    }

    @Test
    void large_counts_survive_without_wrapping_the_compatibility_accessors() throws Exception {
        TokenUsage usage = TokenUsage.measured(openAi("""
                {"prompt_tokens":2147483648,"completion_tokens":3,"total_tokens":2147483651}
                """));
        assertNull(usage.promptTokens());
        assertNull(usage.totalTokens());
        assertEquals(3, usage.completionTokens());
        assertEquals(2_147_483_648L, usage.observation().inputTokens());
        assertEquals(2_147_483_651L, usage.observation().providerTotalTokens());
        assertEquals(Coverage.COMPLETE, usage.observation().coverage(Lane.CHAT));
    }

    @Test
    void cache_and_reasoning_are_subsets_and_only_numeric_allowlisted_details_survive() throws Exception {
        UsageObservation usage = openAi("""
                {"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,
                 "prompt_tokens_details":{"cached_tokens":80,"unverified_cache":"secret"},
                 "completion_tokens_details":{"reasoning_tokens":12},
                 "prompt":"secret","api_key":"secret","provider_bill":999}
                """);
        assertEquals(100L, usage.inputTokens());
        assertEquals(80L, usage.cacheReadTokens());
        assertNull(usage.cacheWriteTokens());
        assertEquals(20L, usage.outputTokens());
        assertEquals(12L, usage.reasoningTokens());
        assertEquals(CacheRelation.READ_SUBSET_OF_INPUT, usage.cacheRelation());
        assertEquals(5, usage.numericDetails().size());
        assertEquals(Coverage.COMPLETE, usage.coverage(Lane.CHAT));
        assertFalse(mapper.writeValueAsString(usage).contains("secret"));
        assertEquals(usage, mapper.readValue(mapper.writeValueAsBytes(usage), UsageObservation.class));
    }

    @Test
    void malformed_counts_are_flagged_and_not_coerced_to_zero() throws Exception {
        UsageObservation usage = openAi("""
                {"prompt_tokens":-1,"completion_tokens":"secret", "total_tokens":9223372036854775808,
                 "prompt_tokens_details":{"cached_tokens":1.5}}
                """);
        assertNull(usage.inputTokens());
        assertNull(usage.outputTokens());
        assertNull(usage.providerTotalTokens());
        assertNull(usage.cacheReadTokens());
        assertEquals(List.of(new Issue(Field.INPUT, Problem.NEGATIVE),
                new Issue(Field.OUTPUT, Problem.NON_NUMERIC),
                new Issue(Field.TOTAL, Problem.OVERFLOW),
                new Issue(Field.CACHE_READ, Problem.NON_NUMERIC)), usage.issues());
        assertEquals(Coverage.INVALID, usage.coverage(Lane.CHAT));
        assertFalse(mapper.writeValueAsString(usage).contains("secret"));
    }

    @Test
    void malformed_usage_objects_and_detail_containers_are_explained() throws Exception {
        assertEquals(Coverage.INVALID, openAi("[]").coverage(Lane.CHAT));
        UsageObservation usage = openAi("""
                {"prompt_tokens":3,"completion_tokens":1,"total_tokens":4,
                 "prompt_tokens_details":"secret"}
                """);
        assertEquals(List.of(new Issue(Field.CACHE_READ, Problem.NON_NUMERIC)), usage.issues());
        assertEquals(3L, usage.inputTokens());
        assertEquals(Coverage.INVALID, usage.coverage(Lane.CHAT));
    }

    @Test
    void inconsistent_totals_and_subsets_are_retained_and_flagged() throws Exception {
        UsageObservation usage = openAi("""
                {"prompt_tokens":10,"completion_tokens":5,"total_tokens":99,
                 "prompt_tokens_details":{"cached_tokens":11},
                 "completion_tokens_details":{"reasoning_tokens":6}}
                """);
        assertEquals(99L, usage.providerTotalTokens());
        assertEquals(11L, usage.numericDetails().get("prompt_tokens_details.cached_tokens"));
        assertTrue(usage.issues().contains(new Issue(Field.CACHE_READ, Problem.EXCEEDS_PARENT)));
        assertTrue(usage.issues().contains(new Issue(Field.REASONING, Problem.EXCEEDS_PARENT)));
        assertTrue(usage.issues().contains(new Issue(Field.TOTAL, Problem.INCONSISTENT_TOTAL)));
        assertEquals(Coverage.INVALID, usage.coverage(Lane.CHAT));
    }

    @Test
    void total_comparison_does_not_overflow_long_arithmetic() throws Exception {
        UsageObservation usage = openAi("""
                {"prompt_tokens":9223372036854775807,"completion_tokens":1,"total_tokens":0}
                """);
        assertEquals(Long.MAX_VALUE, usage.inputTokens());
        assertEquals(List.of(new Issue(Field.TOTAL, Problem.INCONSISTENT_TOTAL)), usage.issues());
    }

    @Test
    void embedding_input_is_complete_without_fabricating_output_or_breakdowns() throws Exception {
        UsageObservation usage = openAi("{\"prompt_tokens\":7,\"total_tokens\":7}");
        assertNull(usage.outputTokens());
        assertNull(usage.cacheReadTokens());
        assertNull(usage.reasoningTokens());
        assertEquals(Coverage.COMPLETE, usage.coverage(Lane.EMBEDDING));
        assertEquals(Coverage.PARTIAL, usage.coverage(Lane.CHAT));
    }

    @Test
    void lm_studio_native_stats_use_the_same_long_validation() throws Exception {
        UsageObservation usage = UsageNormalizer.lmStudio(mapper.readTree("""
                {"promptTokensCount":2147483648,"predictedTokensCount":2,"totalTokensCount":2147483650}
                """));
        assertEquals(2_147_483_648L, usage.inputTokens());
        assertEquals(Coverage.COMPLETE, usage.coverage(Lane.CHAT));
        assertNull(usage.reasoningTokens());
        assertEquals(CacheRelation.UNSPECIFIED, usage.cacheRelation());
        assertEquals(Coverage.INVALID, UsageNormalizer.lmStudio(mapper.readTree("""
                {"promptTokensCount":-2,"predictedTokensCount":9223372036854775808}
                """)).coverage(Lane.CHAT));
    }

    @Test
    void disjoint_cache_categories_cannot_overlap_the_input_total() {
        UsageObservation usage = new UsageObservation(10L, 1L, 11L, 8L, 3L, null,
                Source.PROVIDER, CacheRelation.DISJOINT_INPUT_SUBSETS, UsageObservation.VERSION,
                Map.of(), List.of());
        assertEquals(List.of(new Issue(Field.CACHE_RELATION, Problem.EXCEEDS_PARENT)), usage.issues());
        assertEquals(Coverage.INVALID, usage.coverage(Lane.CHAT));
    }

    @Test
    void observation_copies_details_and_rejects_arbitrary_payload_fields() {
        var details = new HashMap<String, Long>();
        details.put("prompt_tokens", 2L);
        var issues = new ArrayList<Issue>();
        UsageObservation usage = new UsageObservation(2L, null, null, null, null, null,
                Source.PROVIDER, CacheRelation.UNSPECIFIED, UsageObservation.VERSION, details, issues);
        details.clear();
        issues.add(new Issue(Field.INPUT, Problem.OVERFLOW));
        assertEquals(Map.of("prompt_tokens", 2L), usage.numericDetails());
        assertTrue(usage.issues().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> usage.numericDetails().clear());
        assertThrows(IllegalArgumentException.class, () -> new UsageObservation(2L, null,
                null, null, null, null, Source.PROVIDER, CacheRelation.UNSPECIFIED,
                UsageObservation.VERSION, Map.of("api_key", 123L), List.of()));
    }

    @Test
    void compatibility_json_keeps_the_four_existing_fields_and_never_emits_large_numeric_counts()
            throws Exception {
        TokenUsage usage = TokenUsage.measured(openAi("""
                {"prompt_tokens":9007199254740993,"completion_tokens":1,"total_tokens":9007199254740994}
                """));
        var tree = mapper.readTree(mapper.writeValueAsBytes(usage));
        assertEquals(4, tree.size());
        assertTrue(tree.get("promptTokens").isNull());
        assertEquals(1, tree.get("completionTokens").intValue());
        assertFalse(tree.has("observation"));
    }

    private UsageObservation openAi(String json) throws Exception {
        return UsageNormalizer.openAi(mapper.readTree(json));
    }

    @Test
    void old_transcript_usage_json_remains_readable() throws Exception {
        TokenUsage usage = mapper.readValue("""
                {"promptTokens":10,"completionTokens":5,"totalTokens":15,"reasoningTokens":3}
                """, TokenUsage.class);
        assertEquals(new TokenUsage(10, 5, 15, 3), usage);
        assertEquals(10L, usage.observation().inputTokens());
        assertEquals(TokenUsage.UNKNOWN, mapper.readValue("{}", TokenUsage.class));
    }
}
