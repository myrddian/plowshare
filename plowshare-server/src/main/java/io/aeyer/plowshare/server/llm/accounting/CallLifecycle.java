package io.aeyer.plowshare.server.llm.accounting;

/** Execution outcome, separate from whether usage was measured. */
public enum CallLifecycle {
    QUEUED, RUNNING, SUCCEEDED, REFUSED, FAILED, CANCELLED, INTERRUPTED, NOT_DISPATCHED;

    public boolean terminal() {
        return this != QUEUED && this != RUNNING;
    }
}
