package io.aeyer.plowshare.server.agents;

/**
 * The one thing anything says to the learner: <em>there is new folded
 * material</em>.
 *
 * <h2>It carries nothing, and that is the interface</h2>
 *
 * <p>Not a conversation id, not a span, not a reason. A caller that named the
 * conversation it was ringing about would be choosing what gets mined, and
 * <b>choosing is the window provider's</b> — it is the only place it can be,
 * because a tree marked for ejection has to overtake a conversation that folded
 * a moment ago and no single caller is in a position to know that. See {@code
 * learner.LearningWindow}, which reads the urgency off the tree's own lifecycle
 * rather than off whoever rang the bell.
 *
 * <h2>The design's three triggers, and where each of them actually is</h2>
 *
 * <p>The design names three — a fold, a conversation being archived, and one
 * being marked for ejection — and they are not three call sites. <b>Only the
 * fold rings this bell.</b> The other two are the same design realised where it
 * belongs, in the provider's ordering:
 *
 * <ul>
 *   <li><b>fold</b> — material has just become invisible, so a pass is started
 *       here, on the fold's own thread. It is the only moment in this server
 *       that is both asynchronous and about material that has just changed
 *       state;
 *   <li><b>{@code archived}</b> and <b>{@code to_be_ejected}</b> — a tree in
 *       either state goes to the front of the next window, ahead of everything
 *       live, <b>and everything it holds becomes eligible for one</b>, live rows
 *       included: a tree that takes no further turn has no later window to be
 *       mined in. That second half is what makes them more than an ordering,
 *       and without it a run that never folded had no row in the queue for them
 *       to order. <b>Not a call from the endpoint that sets the state</b>, and
 *       the reason is that
 *       both of those are synchronous request paths — {@code PUT
 *       /v1/conversations/&#123;id&#125;/lifecycle} and {@code POST
 *       /v1/retention/sweep} — and a pass makes a model call. Ringing from
 *       either would put a model call, with its own timeout, inside an
 *       operator's HTTP request in order to be a few folds early.
 * </ul>
 *
 * <p><b>The consequence, stated rather than discovered: on a server where
 * nothing folds, nothing is mined.</b> Marking a tree for ejection makes its
 * material eligible, but the queue holding it is only drained when some
 * conversation somewhere folds. <b>That is now a property of the trigger alone
 * and no longer of the queue</b> — an archived run that never folded is in the
 * queue and waiting, where before it was not in the queue at all — so a fourth
 * trigger, anything that calls this from a thread of its own, is one line and
 * is all a deployment needs for it to be sooner.
 *
 * <h2>An implementation must not throw and must not block its caller for long</h2>
 *
 * <p>The fold is the caller this rule is written for. Compaction is dial-tone —
 * the owner has been explicit — and learning is another model call with its own
 * failure surface, so the fold commits <b>first</b> and this runs after it, on
 * the fold's own thread, where a failure stalls nothing but itself. {@code
 * Compaction.foldIfItWouldNotFit} calls it inside a {@code catch} of its own
 * anyway, on the standing rule that a collaborator's plumbing must not surface
 * as a broken turn — but an implementation that leans on that is one whose
 * failures are somebody else's log line.
 */
@FunctionalInterface
public interface Learning {

    /**
     * A learner that does nothing, for a wiring that has none.
     *
     * <p>{@code Transcript.NONE}'s shape and its justification: a server with no
     * learner is a legal, running server whose folds simply do not start a pass.
     * Every {@code @Bean} in {@code AgentsConfig} supplies a real one; what this
     * is for is the fixtures that build a {@code Compaction} to assert on folding
     * and have no archive to propose into.
     */
    Learning NONE = () -> { };

    /**
     * There is material the learner has not been given.
     *
     * <p>May be called from any thread and at any rate, including while a pass
     * is already running — {@code learner.Learner} single-flights and drops the
     * second, on the fold's own reasoning: a dropped pass costs nothing, because
     * the queue only grows and the next window is simply wider.
     */
    void thereIsMaterial();
}
