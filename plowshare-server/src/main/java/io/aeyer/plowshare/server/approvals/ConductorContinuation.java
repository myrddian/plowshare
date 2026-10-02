package io.aeyer.plowshare.server.approvals;

/**
 * Who continues a conversation an answered approval was raised in, when that conversation is an
 * orchestration conductor's rather than an agent's.
 *
 * <p>An approval raised under an orchestration — by its conductor's own run tool, or through
 * {@code agent_run} by a coder under it — is written against the conductor's conversation. The
 * ordinary continuation speaks to that conversation as a plain agent turn: it looks the
 * orchestration's name up as an agent, and reports the ending to nobody that routes it. The
 * orchestration engine continues it as one of its own turns instead, so the ending is routed as any
 * conductor turn's is.
 */
@FunctionalInterface
public interface ConductorContinuation {

    /** No orchestration engine: every approval takes the ordinary continuation. */
    ConductorContinuation NONE = (approval, utterance) -> false;

    /**
     * The whole approval and not its conversation alone, because which conversation is carried on
     * depends on where it was asked: one raised in the conductor's own sub-agent resumes that
     * sub-agent, whose conversation is {@link RunApproval#askedIn()} — spec 2026-09-26 §4. And in
     * the state the answer left it, {@code allowed} or {@code denied}: an answer to a run's own
     * check is spoken to its conductor in words of its own, which differ by the decision.
     *
     * @return whether {@code approval.conversation()} is a live conductor's, and so was continued
     *     here; {@code false} leaves it to the ordinary continuation
     * @throws io.aeyer.plowshare.server.agents.Turn.Refused if the conductor or the sub-agent is
     *     speaking
     */
    boolean continueApproved(RunApproval approval, String utterance);
}
