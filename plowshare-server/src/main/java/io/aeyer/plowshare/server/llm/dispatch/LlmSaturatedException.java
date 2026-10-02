package io.aeyer.plowshare.server.llm.dispatch;

import java.time.Duration;

/**
 * The request could not <em>start</em> within its budget.
 *
 * <p>Not "the model was slow" — the model was never called. Anchor's pools have
 * unbounded queues and wait indefinitely, which is right for a batch ingest
 * nobody is watching and wrong for an interactive recall. Same instinct as
 * {@code unavailable}: report the infrastructure, claim nothing about the task.
 */
public final class LlmSaturatedException extends LlmException {

    public LlmSaturatedException(String pool, Lane lane, Duration budget, int queued) {
        // The pool and the lane are both named because "saturated" without
        // either is a message an operator cannot act on: which of two hosts,
        // and which of its two limits, is the whole of the fix.
        super("pool '" + pool + "' is saturated: the " + lane.wireName()
                + " lane did not start this request within " + budget.toMillis()
                + "ms, with " + queued + " already waiting");
    }
}
