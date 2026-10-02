package io.aeyer.plowshare.server.hooks;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Every place a hook can intercept, in the words hook authors write: five inside a run, and nine
 * on the transitions between runs, named for the log they happen to (spec
 * 2026-09-28-hooks-reach-the-log §2.3; {@code fold.pre} was removed on 2026-09-29, and {@link
 * #of} says so to a file that still names it).
 *
 * <p>The wire names are the TypeScript contract's ({@code plowshare-hooks/index.ts}), and {@code
 * HookContractMirrorTest} holds the two together. A log stage is filtered by {@code origins:}, a
 * tool stage by {@code tools:}; {@link #onALog} says which kind a stage is.
 *
 * <p>Every stage has a seam: slice 1 of that spec gave {@link #LOG_OPEN}, {@link #LOG_CLOSE},
 * {@link #DELIVERY_PRE} and {@link #DELIVERY_POST} theirs, and slices 2–4 the other five.
 */
public enum Stage {
    /** Before the request goes to the model; may add context. */
    PROMPT_PRE("prompt.pre", false),
    /** After the model replies; may note or redact. */
    PROMPT_POST("prompt.post", false),
    /** Before a tool runs; may allow, deny or rewrite its arguments. */
    TOOL_PRE("tool.pre", false),
    /** After a tool returns; may note or redact its result. */
    TOOL_POST("tool.post", false),
    /** After a step that asked for tools, once its results are in the history. */
    STEP_POST("step.post", false),
    /** A log's row is committed and no turn has started; may add to its fixed opening. */
    LOG_OPEN("log.open", true),
    /** A log will take no more turns; may notify. */
    LOG_CLOSE("log.close", true),
    /** An orchestration stage is about to start or be returned to; may deny or note. */
    STAGE_PRE("stage.pre", true),
    /** An orchestration stage is about to be marked done; may deny or note. */
    STAGE_POST("stage.post", true),
    /** A person is about to be asked to approve; may deny or note, never allow. */
    APPROVAL_PRE("approval.pre", true),
    /** An approval was answered or revoked; may notify. */
    APPROVAL_POST("approval.post", true),
    /** The folder has summarised a span and the fold is not yet saved; may keep text or notify. */
    FOLD_POST("fold.post", true),
    /** A result is about to be delivered; may note. */
    DELIVERY_PRE("delivery.pre", true),
    /** A result was delivered; may notify. */
    DELIVERY_POST("delivery.post", true);

    private final String wireName;
    private final boolean log;

    Stage(String wireName, boolean log) {
        this.wireName = wireName;
        this.log = log;
    }

    public String wireName() {
        return wireName;
    }

    /** Whether this stage is a transition of a log, filtered by {@code origins:} (spec §2.6). */
    public boolean onALog() {
        return log;
    }

    public static Stage of(String wireName) {
        for (Stage stage : values()) {
            if (stage.wireName.equals(wireName)) {
                return stage;
            }
        }
        if ("fold.pre".equals(wireName)) {
            // Amended 2026-09-29: a file written for fold.pre is told where its keep went, not
            // that it named something that was never a stage.
            throw new IllegalArgumentException("'fold.pre' was removed: fold.post now sees the"
                    + " summary, and keeps text or notifies from there");
        }
        throw new IllegalArgumentException("'" + wireName + "' is not a stage; the stages are "
                + Arrays.stream(values()).map(Stage::wireName).collect(Collectors.joining(", ")));
    }
}
