package io.aeyer.plowshare.server.llm.accounting;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.server.llm.accounting.UsageObservation.CacheRelation;
import io.aeyer.plowshare.server.llm.accounting.UsageObservation.Field;
import io.aeyer.plowshare.server.llm.accounting.UsageObservation.Issue;
import io.aeyer.plowshare.server.llm.accounting.UsageObservation.Problem;
import io.aeyer.plowshare.server.llm.accounting.UsageObservation.Source;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads only verified numeric fields; malformed usage never invalidates successful content. */
public final class UsageNormalizer {

    private UsageNormalizer() { }

    /** OpenAI Chat Completions usage, also used for compatible embedding responses. */
    public static UsageObservation openAi(JsonNode usage) {
        if (absent(usage)) {
            return UsageObservation.UNKNOWN;
        }
        if (!usage.isObject()) {
            return invalidObject();
        }
        var issues = new ArrayList<Issue>();
        var details = new LinkedHashMap<String, Long>();
        Long input = read(usage.get("prompt_tokens"), "prompt_tokens", Field.INPUT, details, issues);
        Long output = read(usage.get("completion_tokens"), "completion_tokens", Field.OUTPUT, details, issues);
        Long total = read(usage.get("total_tokens"), "total_tokens", Field.TOTAL, details, issues);
        Long cached = detail(usage.get("prompt_tokens_details"), "cached_tokens",
                "prompt_tokens_details.cached_tokens", Field.CACHE_READ, details, issues);
        Long reasoning = detail(usage.get("completion_tokens_details"), "reasoning_tokens",
                "completion_tokens_details.reasoning_tokens", Field.REASONING, details, issues);
        return observation(input, output, total, cached, null, reasoning,
                CacheRelation.READ_SUBSET_OF_INPUT, details, issues);
    }

    /** The native LM Studio stream's measured stats, not its text-length estimates. */
    public static UsageObservation lmStudio(JsonNode stats) {
        if (absent(stats)) {
            return UsageObservation.UNKNOWN;
        }
        if (!stats.isObject()) {
            return invalidObject();
        }
        var issues = new ArrayList<Issue>();
        var details = new LinkedHashMap<String, Long>();
        Long input = read(stats.get("promptTokensCount"), "promptTokensCount", Field.INPUT, details, issues);
        Long output = read(stats.get("predictedTokensCount"), "predictedTokensCount", Field.OUTPUT, details, issues);
        Long total = read(stats.get("totalTokensCount"), "totalTokensCount", Field.TOTAL, details, issues);
        return observation(input, output, total, null, null, null, CacheRelation.UNSPECIFIED,
                details, issues);
    }

    /** Compatibility construction for fixtures and previously persisted transcript counts. */
    public static UsageObservation legacy(Integer input, Integer output, Integer total, Integer reasoning) {
        var issues = new ArrayList<Issue>();
        return observation(legacyCount(input, Field.INPUT, issues), legacyCount(output, Field.OUTPUT, issues),
                legacyCount(total, Field.TOTAL, issues), null, null,
                legacyCount(reasoning, Field.REASONING, issues), CacheRelation.UNSPECIFIED, Map.of(), issues);
    }

    private static Long legacyCount(Integer value, Field field, List<Issue> issues) {
        if (value == null) {
            return null;
        }
        if (value < 0) {
            issues.add(new Issue(field, Problem.NEGATIVE));
            return null;
        }
        return value.longValue();
    }

    private static UsageObservation observation(Long input, Long output, Long total, Long cached,
            Long written, Long reasoning, CacheRelation relation, Map<String, Long> details, List<Issue> issues) {
        if (input == null && output == null && total == null && cached == null && written == null
                && reasoning == null && issues.isEmpty()) {
            return UsageObservation.UNKNOWN;
        }
        return new UsageObservation(input, output, total, cached, written, reasoning, Source.PROVIDER,
                relation, UsageObservation.VERSION, details, issues);
    }

    private static Long detail(JsonNode parent, String key, String name, Field field,
            Map<String, Long> details, List<Issue> issues) {
        if (absent(parent)) {
            return null;
        }
        if (!parent.isObject()) {
            issues.add(new Issue(field, Problem.NON_NUMERIC));
            return null;
        }
        return read(parent.get(key), name, field, details, issues);
    }

    private static Long read(JsonNode value, String name, Field field,
            Map<String, Long> details, List<Issue> issues) {
        if (absent(value)) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            issues.add(new Issue(field, Problem.NON_NUMERIC));
            return null;
        }
        if (value.bigIntegerValue().signum() < 0) {
            issues.add(new Issue(field, Problem.NEGATIVE));
            return null;
        }
        if (!value.canConvertToLong()) {
            issues.add(new Issue(field, Problem.OVERFLOW));
            return null;
        }
        long count = value.longValue();
        details.put(name, count);
        return count;
    }

    private static boolean absent(JsonNode value) {
        return value == null || value.isMissingNode() || value.isNull();
    }

    private static UsageObservation invalidObject() {
        return new UsageObservation(null, null, null, null, null, null, Source.PROVIDER,
                CacheRelation.UNSPECIFIED, UsageObservation.VERSION, Map.of(),
                List.of(new Issue(Field.USAGE, Problem.NON_NUMERIC)));
    }
}
