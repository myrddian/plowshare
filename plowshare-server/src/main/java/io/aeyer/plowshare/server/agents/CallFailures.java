package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.Stage;
import io.aeyer.plowshare.server.hooks.Tier;
import java.util.Set;

/**
 * A turn's call failures: replies that wrote a tool call as text instead of making it (spec
 * 2026-09-28-call-failures).
 *
 * <p><b>A call failure is the model's warning, never the person's reading.</b> Measured
 * 2026-09-28: the conductor of {@code orc_318408A44038F859} answered three turns running with
 * nothing but {@code todo_write}'s arguments, was nudged about its stage each time, and was
 * failed {@code stuck} with all seven phases built. So each written call is held and the model
 * warned, bounded by an allowance of its own rather than by the stuck rule or the call budget,
 * both of which would misname the failure.
 *
 * <p>One per turn, owned by {@code JobRuntime}'s loop, and never shared across threads.
 */
final class CallFailures {

    /** Warnings a turn may be sent in a row before the next call failure ends it. */
    static final int ALLOWANCE = 3;

    /**
     * The tools the validator may call for a model: the harness's own list, fixed here and not
     * declared by tools. Nothing that starts work, edits a file, writes a memory or reaches the
     * network is on it, and every tool but one only reads.
     *
     * <p><b>That one is {@code todo_write}, and a wrong call to it is not nothing.</b> On a
     * conductor's board it moves stages: finishing a checked stage runs the run's check command
     * (which its consent already allows, but runs now), and returning one resets every stage after
     * it to pending. It is here because a conductor writing its todo ops out is the measured case
     * (orc_318408A44038F859), and it is bounded by what makes it tolerable: the validator is asked
     * only from the second failure in a row, and for a reply that was bare arguments the call runs
     * the reply's own arguments and never the validator's ({@code JobRuntime.validated}), so the
     * validator decides whether a call happens, not what it does.
     */
    static final Set<String> SAFE = Set.of("todo_read", "todo_write", "file_read", "file_stat",
            "file_glob", "file_grep", "file_roots", "orchestration_status", "memory_read",
            "memory_recall", "memory_navigate", "result_read", "result_list");

    /** {@code Outcome.Ending.CALL_FAILURES}'s sentence; {@code JobRuntime.stopped} appends the
     *  tool trail. */
    static final String ENDED = "This run kept writing tool calls as text instead of making"
            + " them, so it was stopped.";

    static final String FAILURE_HOOK = "harness:call-failure";

    static final String VALIDATOR_HOOK = "harness:call-validator";

    private int left = ALLOWANCE;
    private int inARow;

    /** One more call failure in a row; how many in a row there now are, this one included. */
    int failed() {
        return ++inARow;
    }

    /** Whether no warning is left, so the next call failure ends the turn. */
    boolean exhausted() {
        return left == 0;
    }

    /** Takes one warning; how many are left after it. */
    int warned() {
        if (left == 0) {
            throw new IllegalStateException("no call-failure warning is left to take");
        }
        return --left;
    }

    /** A real tool call, the model's or one the validator made: the allowance is whole again
     *  and the row is broken. */
    void called() {
        left = ALLOWANCE;
        inARow = 0;
    }

    /** The warning the model is sent after its draft, {@code left} warnings remaining. */
    static String warning(String tool, int left) {
        return "[harness] Warning: your reply wrote a call to `" + tool + "` as text, so nothing"
                + " ran. Writing a tool's name or its arguments in a reply does not call it. Make"
                + " the call now as a tool call. (Warnings left before this turn ends: `" + left
                + "`.)";
    }

    /** The record of one warning. */
    static HookRecord failure(String tool, String warning) {
        return new HookRecord(FAILURE_HOOK, null, Tier.HARNESS, Stage.PROMPT_POST, tool,
                HookRecord.ADD, "the reply wrote a call to " + tool + " as text and made no tool"
                        + " call", warning, null, 0);
    }

    /**
     * The record of a call failure no warning could follow, on {@code when} -- the run's last step
     * or its last budgeted model call: {@code NOTE}, because nothing was added for the model, and
     * the reason says why the turn ended there instead.
     */
    static HookRecord unwarned(String tool, String when) {
        return new HookRecord(FAILURE_HOOK, null, Tier.HARNESS, Stage.PROMPT_POST, tool,
                HookRecord.NOTE, "the reply wrote a call to " + tool + " as text and made no tool"
                        + " call, on " + when + ": no request was left to warn on, so the turn"
                        + " ended there and the reply was not delivered", null, null, 0);
    }

    /** The record of one consult of the validator: {@code ADD} when its call was made, {@code
     *  NOTE} for any other verdict, {@code FAILED} when it threw or timed out. */
    static HookRecord validation(String tool, String decision, String reason, String arguments,
            String draft, long tookMs) {
        return new HookRecord(VALIDATOR_HOOK, null, Tier.HARNESS, Stage.PROMPT_POST, tool,
                decision, reason, arguments, draft, Math.max(0, tookMs));
    }
}
