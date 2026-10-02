package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.archive.Origin;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A handle on one run: what it is, whether it is still going, and what it came
 * to.
 *
 * <p><b>A handle and not a container for the run's output.</b> The curator's
 * result is memories promoted and proposals filed, which live in the archive and
 * the queue; what a job carries is how the run ended. A job that tried to hold
 * the work's product would be a second, staler copy of it.
 *
 * <p>Mutated by the virtual thread running the job and read by whoever polls it.
 * <b>Lifecycle and cancellation are one atomic word</b>, because they used to be
 * two and the guarantee {@link #requestCancel} states was not true across them:
 * a caller that read {@code RUNNING}, was descheduled while the job thread
 * finished, and then set a separate flag returned true for a run that had
 * already answered. See {@link Phase}.
 */
public final class Job {

    /**
     * Lifecycle, as a caller sees it.
     *
     * <p>Two-valued, and there is deliberately no {@code CANCELLING}:
     * cancellation is a request the run honours at its next turn boundary,
     * because an in-flight model call cannot be interrupted out of a synchronous
     * HTTP call. A state saying "cancelling" would suggest to a caller that the
     * run had stopped when it may be several seconds into generating a
     * completion. {@link #cancelRequested()} says what is actually true —
     * somebody asked — and {@code DONE} with {@link Outcome.Ending#CANCELLED}
     * says it took.
     */
    public enum State {
        RUNNING,
        DONE
    }

    /**
     * The same lifecycle plus the cancellation request, in one word.
     *
     * <p>Internal, so the public {@link State} keeps the two values the
     * paragraph above argues for. It is three-valued here because the two facts
     * have to move together: {@link #requestCancel} promises false for a run
     * that has already finished, and a boolean flag beside a state field cannot
     * keep that promise — the check and the set are two operations, and a job
     * thread finishing between them makes the answer a lie. One compare-and-set
     * on one reference makes it structural rather than something a test would
     * have to catch in a nanosecond-wide window, which is the same reasoning
     * {@code JobRuntime.stopped} rests on for a different invariant.
     */
    private enum Phase {
        RUNNING(State.RUNNING),
        CANCEL_REQUESTED(State.RUNNING),
        DONE(State.DONE);

        private final State visible;

        Phase(State visible) {
            this.visible = visible;
        }
    }

    private final String id;
    private final String agent;
    private final RunLimits limits;

    /**
     * Where this job falls in the order this process registered them, and
     * <b>nothing else</b>.
     *
     * <h2>Why a job needs one when its id is already sortable</h2>
     *
     * <p>{@code JobLog.newId} mints ten hex digits of <em>milliseconds</em>, so
     * two jobs submitted inside one millisecond — which is every tight loop, and
     * every test that submits two in a row — differ only in three random bytes.
     * Those order the ids, and they do not order the <em>runs</em>. {@code
     * JobStore.jobs()} is documented "newest last" and is the console's job-list
     * order, and a contract that held except when two things happened quickly is
     * not a contract; it is a test that fails one run in some.
     *
     * <p>{@code V6__conversations.sql} records the same caveat about {@code cnv_}
     * ids and answers it by ordering its reads on {@code created_at} first. There
     * is no such column in memory, so this is that second key.
     *
     * <p><b>It is not an identifier and must never become one.</b> That is the
     * whole distinction from the counter {@code implementation rationale} §2 removed: this
     * number restarts at zero with the process, means nothing outside it, is on
     * no wire, in no row and in no message, and is used by exactly one comparator
     * in the class that sets it. An id is what survives a restart; this orders a
     * list that does not.
     */
    private final long sequence;

    /**
     * The conversation this run speaks into, or {@code null} for a run that has
     * none.
     *
     * <h2>Why a handle needs this at all</h2>
     *
     * <p>A stopped run is continuable exactly one way: {@code POST
     * /v1/conversations/&#123;id&#125;/resume}. That endpoint takes a
     * conversation, and a job on its own names none — {@link #id} is this
     * process's handle on the run, not a row anything else can look up by. So
     * without this field, offering a continue button anywhere but the
     * conversation screen a run happened to start from is impossible: there is
     * nothing on the handle to send the grant to. <b>This is the one thing that
     * makes a jobs screen's continue button expressible at all</b>, and a later
     * reader who found it read from nowhere else and deleted it as unused would
     * silently remove that button before it was ever written.
     *
     * <p><b>Threaded from where it was opened, and not looked up later.</b>
     * {@code JobStore.submit} already opens this run's conversation — through
     * {@code Compaction.logFor} for a plain submission, or from the {@code
     * Transcript} a turn was already handed — and the id exists at that moment.
     * A second read off some other table to recover it afterwards would be a
     * fact this handle already knew, re-derived through a query that could fail
     * or disagree.
     *
     * <p><b>Null and not empty, and the two are different facts.</b> A curator's
     * ruling and a pass's per-candidate run are not one agent's turn in any
     * conversation — {@link #limits} is empty for the same runs and for the same
     * reason — and reporting {@code ""} for one would claim a conversation with
     * no name rather than say there is none. The console's wire discipline
     * (see {@code plowshare-console/src/repl/wire.ts}) is that an absence says
     * so; normalising the two into one value here would take that choice away
     * from every reader downstream.
     *
     * <p><b>Non-null does not mean resumable.</b> That was this field's first
     * bug: a {@link Origin#SUBMISSION} run has a conversation — {@code
     * JobStore}'s own submission door opens one for every run — and {@code
     * Turn.requireResumable} still refuses {@code POST
     * /v1/conversations/&#123;id&#125;/resume} for it, on the grounds that
     * method's own javadoc gives at length: it is a run nobody was going to
     * speak to again, not a person's turn, whatever its row contains. {@link
     * #conversationOrigin} is the second fact a reader needs before treating
     * this one as an invitation to continue, and {@code JobView.OutcomeView
     * .resumable} is the one field that has already done that arithmetic —
     * read that rather than {@code conversation != null}.
     */
    private final String conversation;

    /**
     * Which door {@link #conversation} was opened through, or {@code null}
     * exactly when {@link #conversation} is.
     *
     * <h2>Why this travels beside the id and not only on the row</h2>
     *
     * <p>{@code Turn.requireResumable} accepts exactly {@link Origin#TURN} and
     * refuses every other origin by name — a submission's own row included,
     * because it owns its allowance and is still not a conversation anybody
     * speaks into. {@code
     * JobView.OutcomeView.resumable} has to agree with that door before a
     * client ever knocks on it, or a jobs screen would offer a continue button
     * {@code POST /v1/conversations/&#123;id&#125;/resume} then refuses — which
     * is exactly what happened before this field existed: {@code resumable}
     * was computed from the ending and the budget alone, both blind to origin,
     * and answered {@code true} for a submission stopped at a continuable
     * ending.
     *
     * <p><b>Threaded, on {@link #conversation}'s own terms.</b> Whoever opens
     * the conversation already knows which door it came through — {@code
     * JobStore}'s own submission overload names {@code Origin.SUBMISSION} in
     * the same breath it calls {@code Compaction.logFor}, and {@code Turn}
     * names {@code Origin.TURN} at both call sites reaching this class, because
     * {@code requireSpeakable} and {@code requireResumable} have already
     * refused every conversation that is not one before either call is made.
     * Re-deriving it later by asking the row would be a second source of truth
     * for a fact this handle was already told.
     *
     * <p><b>Never non-null beside a null {@link #conversation}.</b> The
     * constructor below enforces it structurally, the way {@code
     * Transcript.Spoken}'s does for the matching pair one layer down — an
     * origin with nothing to be the origin of is not a smaller fact, it is a
     * wrong one, and a caller allowed to write it would have a place to get the
     * two out of step.
     */
    private final Origin conversationOrigin;

    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.RUNNING);
    private volatile Outcome outcome;

    Job(String id, String agent, RunLimits limits) {
        this(id, agent, limits, 0, null, null);
    }

    /**
     * @param conversation the conversation this run speaks into, or {@code
     *     null} for a run that has none. See {@link #conversation}
     * @param conversationOrigin which door {@code conversation} was opened
     *     through, or {@code null} to match a null {@code conversation}. See
     *     {@link #conversationOrigin}
     */
    Job(String id, String agent, RunLimits limits, long sequence, String conversation,
            Origin conversationOrigin) {
        this.id = Objects.requireNonNull(id, "id");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.limits = limits;
        this.sequence = sequence;
        // One fact and not two, on Transcript.Spoken's own terms: a
        // conversation names an origin or there is neither. Refused here rather
        // than left to whichever reader first trusts one without the other --
        // JobView.OutcomeView.resumable is exactly that reader.
        if ((conversation == null) != (conversationOrigin == null)) {
            throw new IllegalArgumentException(
                    "a job's conversation and its origin are one fact together or neither: this"
                            + " one names " + (conversation == null
                                    ? "an origin (" + conversationOrigin + ") with no conversation"
                                    : "conversation " + conversation + " with no origin"));
        }
        this.conversation = conversation;
        this.conversationOrigin = conversationOrigin;
    }

    /** See {@link #sequence}. Package-private: this is {@code JobStore}'s
     *  ordering key and no caller outside it has a use for a number that means
     *  nothing after a restart. */
    long sequence() {
        return sequence;
    }

    public String id() {
        return id;
    }

    /** The name of the agent this job is running, so a listing of jobs reads as
     *  something other than a column of identifiers. */
    public String agent() {
        return agent;
    }

    /** See {@link #conversation}. */
    public String conversation() {
        return conversation;
    }

    /** See {@link #conversationOrigin}. */
    public Origin conversationOrigin() {
        return conversationOrigin;
    }

    /**
     * The two bounds this run is going under, for reading and for moving.
     *
     * <p><b>The live objects and not a snapshot.</b> That is the point: an
     * operator raising a ceiling on a run that is nearly out is moving the same
     * {@link Budget} and {@link TurnCap} the run reads at its next boundary, and
     * a copy would be a number that changed nowhere.
     *
     * <p>{@link Optional} and empty for a job that is not one agent's run —
     * {@code JobStore.submit(String, Function)} takes the work itself rather
     * than a definition to run, so a curator pass's bounds are the pass's own
     * and this class never sees them. Empty rather than a pair of nulls, for the
     * reason {@link #outcome} is an {@link Optional}: the absence is ordinary
     * and a caller that must handle it should be made to.
     *
     * <p><b>Nothing here is reachable by the run itself.</b> No tool takes a
     * job, {@code JobRuntime} is handed the two objects and never this handle,
     * and the only readers are the store and the HTTP surface. See {@link
     * TurnCap} on why that separation is the property this whole configuration
     * rests on.
     */
    public Optional<RunLimits> limits() {
        return Optional.ofNullable(limits);
    }

    public State state() {
        return phase.get().visible;
    }

    /**
     * What the run came to, once it has.
     *
     * <p>{@link Optional} and not a null: a null here would write a null check
     * into every poller forever, and the one that forgets fails on a virtual
     * thread whose stack names the turn loop rather than the caller that read
     * it. The same reasoning as {@code TokenUsage.UNKNOWN} and {@code
     * Completion.toolCalls()}.
     */
    public Optional<Outcome> outcome() {
        return Optional.ofNullable(outcome);
    }

    /** Whether somebody has asked this run to stop and it has not finished yet.
     *  True does not mean it has stopped: see {@link State}. Once the run
     *  finishes this is false and the outcome's ending is what says whether the
     *  cancellation took. */
    public boolean cancelRequested() {
        return phase.get() == Phase.CANCEL_REQUESTED;
    }

    /**
     * Ask the run to stop.
     *
     * <p>One compare-and-set, and that is the whole of the guarantee. The
     * previous version tested {@code state == DONE} and then set a separate
     * flag, which is check-then-act across two independent fields: a caller
     * descheduled between the two, while the job thread ran {@link #finish},
     * returned true for a run that had already answered — precisely what this
     * javadoc said could not happen. No test could have caught it without being
     * a race that usually passes, which is worse than none; the fix is to make
     * the window unrepresentable instead.
     *
     * @return true if this call is the one that asked. False for a second
     *     caller, and false for a caller that arrives after the run has
     *     finished — a store that returned true either way would tell a caller
     *     it had stopped a run that had already answered.
     */
    boolean requestCancel() {
        return phase.compareAndSet(Phase.RUNNING, Phase.CANCEL_REQUESTED);
    }

    /**
     * Record the result and finish.
     *
     * <p><b>The outcome is written before the phase.</b> {@code outcome} is
     * volatile and {@code phase} is an atomic reference, so this order is what
     * makes {@code state() == DONE} imply {@code outcome().isPresent()} for
     * every reader: a poller that sees DONE has, by the happens-before that
     * volatile and atomic reads and writes give, already seen the write that
     * preceded it. The reverse order would let a poller see DONE with no
     * outcome and conclude the run produced nothing.
     *
     * <p>The phase is set unconditionally, from {@code RUNNING} or from {@code
     * CANCEL_REQUESTED}: a run finishes whether or not somebody asked it to.
     *
     * <p><b>No test distinguishes this order from its reverse</b>, and swapping
     * the two lines leaves the suite green — recorded here so a green suite is
     * not read as coverage of it. The window is a handful of nanoseconds wide
     * and a test for it would be a race that usually passes, which is worse than
     * no test: it would be deleted the first time it flaked, taking the reason
     * with it. Do not reorder these two lines.
     */
    void finish(Outcome result) {
        this.outcome = Objects.requireNonNull(result, "outcome");
        this.phase.set(Phase.DONE);
    }
}
