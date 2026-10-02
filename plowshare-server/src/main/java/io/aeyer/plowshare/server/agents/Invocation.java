package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Where an answer came from: the execution provenance of one model call.
 *
 * <h2>Two facts that used to be one, and this is the half a model never sees</h2>
 *
 * <p><b>The conversational actor of every answer is the assistant.</b> Which
 * model produced it — the agent's own, or the class its definition falls back to
 * — is a fact about how the run was executed, and it lives here, on the entry,
 * in columns {@link Projection} does not read. So the next request after a
 * fallback carries an ordinary {@code assistant} message, and the log can still
 * say exactly which model wrote it. Neither is ever claimed on the other's
 * behalf: a fallback's answer is recorded with the fallback's pool and wire
 * model, as the dispatcher reported them, and never with the agent's.
 *
 * <p><b>Nothing here is derived from the entry it rides on.</b> How long the
 * call took is already {@code took_ms} (V16); this is what V16 could not carry.
 *
 * @param id minted by the runtime, one per call. Never null
 * @param agent the agent whose run made the call. Never null
 * @param dispatch whether this was the agent's own model or its fallback
 * @param specifier what the request named — a class or a wire model — as the
 *     definition spells it. Never null
 * @param pool the pool the dispatcher chose, or null when it did not say
 * @param wireModel the model that pool was asked for, or null likewise
 * @param outcome what the completion was judged to be
 * @param finishReason as the endpoint reported it, or null
 * @param usage what the call cost. Never null; {@link TokenUsage#UNKNOWN} when
 *     the endpoint did not say
 * @param sentAt when the request was handed to the dispatcher. Never null
 * @param fallbackReason why a fallback was taken — on the refusal that caused it
 *     and on every call the fallback made — or null for a call nobody refused
 * @param firstTokenMillis from sending to the first thinking or answer delta, or
 *     null for a call that streamed none and for one recorded before V41
 */
public record Invocation(
        UUID id,
        String agent,
        Dispatch dispatch,
        String specifier,
        String pool,
        String wireModel,
        CompletionOutcome outcome,
        String finishReason,
        TokenUsage usage,
        Instant sentAt,
        String fallbackReason,
        Long firstTokenMillis) {

    /** Which target a call went to. Spelled as V39's {@code entries_dispatch_is_known}. */
    public enum Dispatch {
        PRIMARY("primary"),
        FALLBACK("fallback");

        private final String wireName;

        Dispatch(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Dispatch of(String wireName) {
            for (Dispatch dispatch : values()) {
                if (dispatch.wireName.equals(wireName)) {
                    return dispatch;
                }
            }
            throw new IllegalArgumentException("no dispatch is spelled '" + wireName + "'");
        }
    }

    public Invocation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(dispatch, "dispatch");
        Objects.requireNonNull(specifier, "specifier");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(sentAt, "sentAt");
        if (firstTokenMillis != null && firstTokenMillis < 0) {
            throw new IllegalArgumentException(
                    "a first delta cannot arrive " + firstTokenMillis + " ms before its request");
        }
        if (dispatch == Dispatch.FALLBACK && fallbackReason == null) {
            // entries_a_fallback_says_why, one layer up, so the fault is a
            // stack through the runtime rather than a refused INSERT.
            throw new IllegalArgumentException(
                    "a fallback call has to carry the reason it was taken");
        }
    }

    /** One call, as the completion it returned describes it. */
    static Invocation of(String agent, Dispatch dispatch, String specifier, Completion completion,
            CompletionOutcome outcome, Instant sentAt, String fallbackReason,
            Long firstTokenMillis) {
        Completion.Served served = completion.servedBy();
        return new Invocation(UUID.randomUUID(), agent, dispatch, specifier,
                served == null ? null : served.pool(),
                served == null ? null : served.wireModel(),
                outcome, completion.finishReason(), completion.usage(), sentAt, fallbackReason,
                firstTokenMillis);
    }
}
