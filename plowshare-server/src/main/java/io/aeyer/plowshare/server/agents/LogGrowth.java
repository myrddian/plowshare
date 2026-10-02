package io.aeyer.plowshare.server.agents;

/**
 * Told when entries have been committed to a conversation's log: at the end of each turn, and
 * after anything appended outside one — a fold, a learning pass's note.
 * {@code implementation rationale} §3.
 *
 * <p>Implementations must not throw and must not block for long: the end-of-turn call runs on
 * the job's own thread, inside {@code JobStore.finish}, before the job reports it has ended.
 */
@FunctionalInterface
public interface LogGrowth {

    LogGrowth NONE = conversation -> { };

    void appended(String conversationId);
}
