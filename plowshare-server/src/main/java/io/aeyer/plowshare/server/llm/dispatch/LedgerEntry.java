package io.aeyer.plowshare.server.llm.dispatch;

/**
 * What one model call cost, and enough context to attribute it.
 *
 * <p>A record rather than a parameter list because that is the whole point of
 * the seam: {@link TokenLedger} exists so a real implementation can land later
 * without touching a caller, and a widened method signature touches every call
 * site and every implementation at once. A new field here touches the three
 * places that can actually fill it in.
 *
 * @param pool which host served it, named as the operator wrote it. Never a
 *     base URL and never a key.
 * @param wireModel the model the endpoint was actually asked for.
 * @param specifier what the caller asked for — a model name, or a class like
 *     {@code fast}. Carried because <b>the dispatcher is the only component
 *     that holds the specifier-to-model map</b>, so once this record is handed
 *     over the association is unrecoverable system-wide. "What did the {@code
 *     fast} class cost this month" is exactly the question this seam exists to
 *     make answerable, and it cannot be reconstructed from {@code wireModel}:
 *     the map is many-to-one, and it changes when an operator edits a config.
 * @param lane which of the pool's two queues it waited in.
 * @param usage the counts, or {@link TokenUsage#UNKNOWN}. Never null.
 * @param finishReason why generation stopped, or null for an embedding and for
 *     an endpoint that omits it. Worth billing against: a {@code length}
 *     finish is an answer that was cut off mid-thought and charged for in
 *     full, and a bill that cannot distinguish those cannot explain a month
 *     that cost more than it produced.
 */
public record LedgerEntry(
        String pool,
        String wireModel,
        String specifier,
        Lane lane,
        TokenUsage usage,
        String finishReason) {}
