package io.aeyer.plowshare.server.agents;

import java.util.function.Supplier;

/**
 * What the turn loop tells about the work a run does as it does it: a tool call starting and the
 * word it came to, a call written as text, a delegation going out and coming back. Spec
 * 2026-09-28, the orchestration record §2 — the turn loop is one of the record's sources.
 *
 * <p>Told for every run; whether a run is inside a tree anybody records is the listener's to
 * decide. Every method is a no-op by default and {@link #NONE} is all of them. A listener must not
 * throw, and {@code JobRuntime} holds it guarded anyway, because the loop has many ways out.
 */
public interface RunActivity {

    RunActivity NONE = new RunActivity() { };

    /** What to tell when a call that was started returns. */
    @FunctionalInterface
    interface Call {

        Call NONE = outcome -> { };

        /** @param outcome the call's outcome in a word: {@code ok}, {@code exit 1}, {@code
         *      refused}, {@code denied}, {@code error}, … — {@code ToolLines} names them */
        void returned(String outcome);

        /**
         * {@link #returned(String)}, with the end of what the call answered when that is the
         * fact a reader of the record needs and the word is not all of it: a {@code run} that
         * did not end ok ({@link ToolLines#tail}). Read back into the delegation facts footer, so
         * a conductor is told how a delegate's command failed from the harness's own record of
         * it — measured 2026-09-30, a footer that said only {@code → exit 2} sent three levels of
         * conductor asking each other for the output nobody had.
         *
         * @param output that tail, or null for a call with none worth keeping
         */
        default void returned(String outcome, String output) {
            returned(outcome);
        }
    }

    /**
     * A tool call is starting.
     *
     * @param conversation the conversation the run is in, or {@code null}
     * @param agent the running agent's name
     * @param tool the tool called
     * @param salient the call's salient argument, or empty — asked for only by a listener that
     *     records this conversation: it parses the call's arguments, and every tool call of every
     *     run is told, most of them in no tree anybody records
     * @return what to tell when it returns
     */
    default Call called(String conversation, String agent, String tool,
            Supplier<String> salient) {
        return Call.NONE;
    }

    /** Why a call written as text ended the turn with no warning given for it. */
    enum Unwarned {
        /** Every warning was spent: the model kept writing calls as text. */
        ALLOWANCE_SPENT,
        /** It was the run's last step, so there was no request left to warn on. */
        LAST_STEP,
        /** It was the run's last budgeted model call, likewise. */
        LAST_BUDGETED_CALL
    }

    /** A reply wrote a call to {@code tool} as text, and was warned; {@code warningsLeft} is how
     *  many warnings remain after this one, and 0 on the last. */
    default void callFailure(String conversation, String agent, String tool, int warningsLeft) {
    }

    /** A reply wrote a call to {@code tool} as text and the turn ended over it without a warning,
     *  for the reason {@code why} — told apart from {@link #callFailure}'s last warning, which
     *  also leaves none, because this one is the turn ending. */
    default void callFailureEnded(String conversation, String agent, String tool, Unwarned why) {
    }

    /**
     * {@code agent} is handing {@code task} to {@code callee}.
     *
     * @param conversation the delegating conversation
     * @param agent the delegator's name
     * @param callee the delegate's name
     * @param task what it was asked
     * @param calleeConversation the delegate's own, freshly opened conversation — named
     *     separately from {@code callee} because two delegations to the same agent can be live at
     *     once in one run (the first still {@code AWAITING} an approval when the second is
     *     dispatched), and only the conversation tells them apart; a listener that records this
     *     reads it back through {@link #delegationFacts}
     */
    default void delegated(String conversation, String agent, String callee, String task,
            String calleeConversation) {
    }

    /** {@code callee} has come back to {@code agent} with {@code outcome}. */
    default void delegateReturned(String conversation, String agent, String callee,
            Outcome outcome) {
    }

    /**
     * The facts footer for one delegation — spec 2026-09-29 §1a — or null for none: a run in no
     * tree anybody records has none.
     *
     * @param conversation the delegate's <em>own</em> conversation — not the delegator's — so
     *     that of two delegations to the same agent live at once in one run, each gets its own
     *     facts and never the other's
     * @param callee the delegate's name, for the footer's own wording
     * @return the footer, or null
     */
    default String delegationFacts(String conversation, String callee) {
        return null;
    }
}
