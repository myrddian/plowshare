package io.aeyer.plowshare.server.llm;

import java.util.List;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;

/**
 * Turns text into a vector. The whole of Plowshare's use of a model.
 *
 * <p>This interface is the change that deletes the librarian. Excalibur recalls
 * by spawning a small local model with a turn budget and a tool set, letting it
 * grep the archive and choose; that agent has an iteration cap, a truncation
 * mode, a degraded mode, and a prompt whose instructions one model follows and
 * another ignores. Measured 2026-08-24 against a purpose-built corpus, the same
 * questions scored 2/24 on one model and 14/24 on another, and one truncated
 * run returned the model's own internal deliberation as the answer. Recall here
 * is {@code ORDER BY embedding <=> ?}: no loop, no cap, no deliberation to leak,
 * and the rules — retired records excluded, project ahead of global — are
 * applied by ordinary code afterwards.
 *
 * <p><b>An interface, not the OkHttp class.</b> Tests stub it. A recall test
 * that reached a live model would be measuring the model rather than the rules,
 * and Excalibur's golden-set eval scored anywhere from 2/4 to 4/4 on identical
 * code — a signal that cannot tell you whether a change helped.
 *
 * <p>Implementations must be safe to call from several threads at once.
 */
public interface EmbeddingClient {

    /**
     * The vector for one string.
     *
     * @throws EmbeddingException if the endpoint is unreachable, refuses, or
     *     answers with something that is not an embedding. Callers on the
     *     <em>write</em> path must catch this: a memory written while the
     *     endpoint is down keeps a NULL embedding, and never loses the write.
     */
    float[] embed(String text);

    /** Owned single input. Production implementations must carry this snapshot to the dispatcher. */
    default float[] embed(String text, UsageAttribution owner) {
        java.util.Objects.requireNonNull(owner, "owner");
        return embed(text);
    }

    /**
     * The vectors for several strings, in the order they were given.
     *
     * <p>One round trip, not {@code n}. The OpenAI embeddings endpoint takes an
     * array for exactly this reason, and paying a request per string is the cost
     * Excalibur's {@code validate/quick} lane was written to avoid.
     *
     * @throws EmbeddingException as {@link #embed}; the batch fails whole, since
     *     a partially embedded batch would silently leave some rows unsearchable
     *     with nothing recording which.
     */
    List<float[]> embedAll(List<String> texts);

    /** Compatibility dispatch for disabled services; enabled admissions reject legacy owners. */
    static float[] owned(EmbeddingClient client, String text, UsageAttribution owner) {
        java.util.Objects.requireNonNull(owner, "owner");
        return owner.status() == UsageAttribution.Status.LEGACY_UNATTRIBUTED
                ? client.embed(text) : client.embed(text, owner);
    }

    /** One atomic batch with an explicit owner, or the disabled capability overload. */
    static List<float[]> owned(EmbeddingClient client, List<String> texts, UsageAttribution owner) {
        java.util.Objects.requireNonNull(owner, "owner");
        return owner.status() == UsageAttribution.Status.LEGACY_UNATTRIBUTED
                ? client.embedAll(texts) : client.embedAll(texts, owner);
    }

    /** One billing owner per batch; callers must split inputs belonging to different owners. */
    default List<float[]> embedAll(List<String> texts, UsageAttribution owner) {
        java.util.Objects.requireNonNull(owner, "owner");
        return embedAll(texts);
    }
}
