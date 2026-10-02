package io.aeyer.plowshare.server.agents;

/**
 * How one run went at the model: what it asked of tools, what the model said and
 * thought, how long before anything moved and how fast it came once it did.
 *
 * <p><b>Every count a model did not report is null, never zero.</b> {@code
 * TokenUsage}'s rule, carried up: a local server may omit usage, and the LM
 * Studio socket reports no reasoning count at all, so a zero here would say "it
 * thought nothing" about a model nobody asked.
 *
 * <p><b>This job's own calls only.</b> A delegating run's children are jobs of
 * their own with a pace of their own; the run tree's spend is the allowance's to
 * report, and adding the two here would count a child's tokens in two places.
 *
 * @param toolCalls how many tool calls this run's model asked for
 * @param completionTokens the model's own count of what it generated, summed over
 *     the calls that reported one; null when none did
 * @param reasoningTokens how much of that was thinking: the model's own count
 *     where it gave one, and an estimate where it streamed thinking and counted
 *     none of it — see {@code reasoningEstimated}
 * @param firstTokenMillis from sending the run's first measured call to its first
 *     thinking or answer delta — queueing and prompt processing included, because
 *     that is the wait a person has before anything moves. Null when no call
 *     streamed a delta
 * @param tokensPerSecond completion tokens over generation time — first delta to
 *     the end of the call — summed over the calls that reported both; null when
 *     none did
 * @param reasoningEstimated whether any part of {@code reasoningTokens} was
 *     estimated. An endpoint may stream reasoning and report {@code
 *     reasoning_tokens: 0} for it — measured on gpt-oss-120b, where 3 199
 *     completion tokens carried an answer of about 2 400 — and a count of zero
 *     beside a model that visibly thought is the one number here that would be
 *     wrong rather than absent. The estimate splits the call's own completion
 *     count by the characters each part streamed, so it is anchored on the
 *     model's tokenizer for that very call and not on a ratio
 */
public record Pace(
        int toolCalls,
        Integer completionTokens,
        Integer reasoningTokens,
        Long firstTokenMillis,
        Double tokensPerSecond,
        boolean reasoningEstimated) {

    /** A run that made no measured call: nothing asked, nothing counted. */
    public static final Pace NONE = new Pace(0, null, null, null, null, false);

    public Pace {
        if (toolCalls < 0) {
            throw new IllegalArgumentException("a run cannot have asked for " + toolCalls
                    + " tool calls");
        }
    }
}
