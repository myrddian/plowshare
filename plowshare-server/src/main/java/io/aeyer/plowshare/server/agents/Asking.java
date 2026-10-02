package io.aeyer.plowshare.server.agents;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * One document, asked a question, by something inside a run.
 *
 * <h2>A seam and not an import, on {@link Citing}'s and {@link Reminding}'s
 * terms</h2>
 *
 * <p>{@code documents.Deliberation} is the one implementation and it depends on
 * nearly the whole of this package — {@link JobRuntime}, {@link AgentRegistry},
 * {@link Budget}, {@link Compaction}, {@link Outcome}. A tool in this package
 * naming that class directly would close a construction cycle that {@code
 * DeliberationConfig} exists to keep open: the deliberation is built <em>after</em>
 * the runtime because it runs three agents through it, so anything the runtime
 * holds can only ever hold a supplier of it. Every other collaborator this
 * package takes from {@code documents} — {@code RetrievalService} on {@code
 * DocumentTools} — is one that does not depend back.
 *
 * <p>The second half is the test cost, and it is the half {@code Citing} names.
 * A fake of this interface is four lines; a mock of {@code Deliberation} is a
 * mock of a final class with six collaborators, and every assertion written
 * against it would be an assertion about the mock.
 *
 * <h2>The allowance is not a parameter of the agent that calls it</h2>
 *
 * <p>{@link Budget} is here so that the caller can say what a pass may spend,
 * and <b>the caller is the system rather than the calling agent</b>. {@code
 * plowshare.documents.ask-budget} is an operator's number about what a
 * deliberation costs, exactly as {@code Curator.pass} builds a budget for a pass
 * that is nobody's agent — the owner's rule that <i>"budget and things shouldnt
 * be an agent thing - thats the system"</i>. An implementation is handed one;
 * it does not read a definition to find one.
 *
 * <h2>It runs to an ending and blocks until it has one</h2>
 *
 * <p>Three model calls in series and a fourth when a critic's JSON has to be
 * asked for again, at 22–60 s each on the hardware this was measured on. So a
 * call to this takes minutes, and the caller's thread is a virtual thread
 * holding no lane — {@code JobStore}'s javadoc owns that measurement and {@code
 * a_parent_blocked_on_a_child_holds_no_lane_slot} pins the same property for
 * {@link AgentRunTool}, which blocks in exactly this shape.
 */
@FunctionalInterface
public interface Asking {

    /** An unbound fixture cannot widen access when asked to act as an account. */
    default Asking scoped(io.aeyer.plowshare.server.information.InformationAccess access,
            io.aeyer.plowshare.server.information.InformationContext context, String session) {
        return NONE;
    }

    /**
     * A server with nothing to deliberate with.
     *
     * <p>{@link Citing#NONE}'s shape and its justification: a context with no
     * corpus wired is a legal running server, and the ending says so in the one
     * place a model will read it rather than throwing out of a tool call. A
     * deployment in this state also defines no agent that could call it — the
     * tool is registered from the same configuration the implementation comes
     * from — so this is the fixture's value rather than a state a shipped boot
     * reaches.
     */
    Asking NONE = (documentId, question, budget, cancelled) -> new Outcome(
            Outcome.Ending.UNAVAILABLE,
            "This document could not be asked: this server has no per-document deliberation"
                    + " wired, so there is nothing to ask it with.",
            0, 0, "");

    /**
     * Ask one document, and come back when it has an answer or a reason.
     *
     * @param documentId the document to ask. Resolved by the caller; an id this
     *     corpus does not hold is an ordinary ending and not an exception
     * @param question what the reader wants to know, in prose
     * @param budget what this pass may spend. The system's allowance, shared by
     *     reference across the pass's three stages and the critic's retry
     * @param cancelled asked at every stage boundary, so a run cancelled while
     *     this is blocked stops at the next one rather than at the end
     * @return how the pass ended and what it has to say, whole. <b>The text is
     *     the answer plus the blocks that make it checkable</b> — which
     *     paragraphs held, which did not, and what the critic raised — and a
     *     caller that renders less than all of it has removed the point of
     *     having asked
     */
    Outcome ask(UUID documentId, String question, Budget budget, BooleanSupplier cancelled);    default Asking scoped(io.aeyer.plowshare.server.information.InformationAccess access,
            io.aeyer.plowshare.server.information.InformationContext context, String session, String parent) {
        return scoped(access, context, session);
    }

}
