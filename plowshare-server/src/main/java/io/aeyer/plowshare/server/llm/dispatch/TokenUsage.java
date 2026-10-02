package io.aeyer.plowshare.server.llm.dispatch;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.aeyer.plowshare.server.llm.accounting.UsageNormalizer;
import io.aeyer.plowshare.server.llm.accounting.UsageObservation;
import java.util.Objects;

/**
 * What a call cost, when the endpoint says.
 *
 * <p>Each count is nullable because a local OpenAI-compatible server may omit
 * {@code usage} entirely, but the record itself never is: a null usage would
 * put a null check in every ledger implementation forever, and the one that
 * forgot would fail at the moment someone finally wired up billing.
 *
 * @param promptTokens what the prompt was charged as. The one count something
 *     already reads: {@code Compaction} decides folds from it and {@code Turn}
 *     persists it, and {@code turns_prompt_tokens_are_a_measurement} refuses a
 *     zero — so an absent count has to stay null rather than become one.
 * @param completionTokens what the generation was charged as, reasoning
 *     included. The endpoint's own arithmetic, not this record's.
 * @param totalTokens what the endpoint said the two came to. Not derived here:
 *     a server that reports a total we did not compute is reporting its own
 *     billing, and recomputing it would hide a disagreement worth seeing.
 * @param observation validated 64-bit measurements and safe provenance for accounting.
 *     Kept off existing transcript JSON; dedicated usage frames will serialize decimal strings.
 *     A count exceeding the compatibility integer range leaves that accessor null rather
 *     than truncating it. It remains available in this observation.
 * @param reasoningTokens how much of {@code completionTokens} was thinking,
 *     from {@code completion_tokens_details.reasoning_tokens}, or null when the
 *     endpoint does not break it out.
 *
 *     <p><b>Here because thinking is the largest thing this system pays for and
 *     the only one it could not see.</b> Measured 2026-09-02 against
 *     qwen3.5-9b: a 51-token prompt spent 2 997 completion tokens, nearly all
 *     of them reasoning, and 96 seconds of wall clock. The text of that
 *     reasoning is dropped — see {@code OpenAiTransport.stream} for the
 *     argument — and dropping it is only defensible while its cost is still
 *     reported, which is what this count is for. A separate field and not
 *     folded into {@code completionTokens}, because those are the endpoint's
 *     figures and a reader comparing a bill against them must see what the
 *     endpoint sent.
 */
public record TokenUsage(
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Integer reasoningTokens,
        @JsonIgnore UsageObservation observation) {

    public TokenUsage {
        Objects.requireNonNull(observation, "observation");
        promptTokens = compatible(observation.inputTokens());
        completionTokens = compatible(observation.outputTokens());
        totalTokens = compatible(observation.providerTotalTokens());
        reasoningTokens = compatible(observation.reasoningTokens());
    }

    /** Source-compatible construction; normalized counts never silently wrap. */
    @JsonCreator
    public TokenUsage(@JsonProperty("promptTokens") Integer promptTokens,
            @JsonProperty("completionTokens") Integer completionTokens,
            @JsonProperty("totalTokens") Integer totalTokens,
            @JsonProperty("reasoningTokens") Integer reasoningTokens) {
        this(promptTokens, completionTokens, totalTokens, reasoningTokens,
                UsageNormalizer.legacy(promptTokens, completionTokens, totalTokens, reasoningTokens));
    }

    public static TokenUsage measured(UsageObservation observation) {
        return new TokenUsage(null, null, null, null, observation);
    }

    private static Integer compatible(Long count) {
        return count == null || count > Integer.MAX_VALUE ? null : count.intValue();
    }

    /** The endpoint said nothing about cost. */
    public static final TokenUsage UNKNOWN = new TokenUsage(null, null, null, null);

    /**
     * The three counts every OpenAI-compatible endpoint reports, with no
     * reasoning breakdown.
     *
     * <p>Kept so that a caller building a usage by hand — every one of them is a
     * test — does not have to write a fourth null to say something it has no
     * opinion about. {@code null} and not zero for the same reason {@link
     * #reasoningTokens} is nullable at all: a model that did not think and an
     * endpoint that did not say are different facts.
     */
    public static TokenUsage of(Integer promptTokens, Integer completionTokens,
            Integer totalTokens) {
        return new TokenUsage(promptTokens, completionTokens, totalTokens, null);
    }
}
