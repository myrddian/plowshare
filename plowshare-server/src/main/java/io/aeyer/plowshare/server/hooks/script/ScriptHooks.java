package io.aeyer.plowshare.server.hooks.script;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.protocol.EnvironmentFile;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.hooks.Addition;
import io.aeyer.plowshare.server.hooks.ApprovalAnswer;
import io.aeyer.plowshare.server.hooks.Approving;
import io.aeyer.plowshare.server.hooks.Deadline;
import io.aeyer.plowshare.server.hooks.DeliveryPre;
import io.aeyer.plowshare.server.hooks.FoldPost;
import io.aeyer.plowshare.server.hooks.Gate;
import io.aeyer.plowshare.server.hooks.Handover;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.HookFile;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.hooks.LogClosing;
import io.aeyer.plowshare.server.hooks.LogOpen;
import io.aeyer.plowshare.server.hooks.LogOpening;
import io.aeyer.plowshare.server.hooks.Notified;
import io.aeyer.plowshare.server.hooks.PromptPost;
import io.aeyer.plowshare.server.hooks.PromptPre;
import io.aeyer.plowshare.server.hooks.Stage;
import io.aeyer.plowshare.server.hooks.StageDone;
import io.aeyer.plowshare.server.hooks.StageShown;
import io.aeyer.plowshare.server.hooks.StageStart;
import io.aeyer.plowshare.server.hooks.Step;
import io.aeyer.plowshare.server.hooks.StepPost;
import io.aeyer.plowshare.server.hooks.Summarised;
import io.aeyer.plowshare.server.hooks.Tier;
import io.aeyer.plowshare.server.hooks.ToolPost;
import io.aeyer.plowshare.server.hooks.ToolPre;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * A project's own hooks, from {@code projects/<id>/hooks/}, as the second layer of
 * the chain (spec §§3–7); or, built with {@link #local}, a person's own hooks from the snapshot
 * their log opened with, as the third (spec 2026-09-30-local-hooks-are-served). One runtime, one
 * set of rules: the two differ in where a set comes from, the tier their records carry, how a
 * broken file's refusal is worded, and what a local {@code allow} is worth.
 *
 * <p><b>Never throws.</b> Every failure becomes a {@code failed} record with the
 * stage's policy applied: tool stages, {@code stage.*} and {@code approval.pre} close; prompt
 * stages and the other log stages open. That includes
 * what is not a {@link HookFailure} — an {@code UncheckedIOException} out of a
 * directory walk, a runtime exception out of a pool — each caught per hook where
 * one hook is to blame, and at the stage's entry where none is.
 */
public final class ScriptHooks implements Hooks, AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What a model is told when the project's id could not be looked up; the detail is the record's. */
    static final String LOOKUP_REFUSAL = "this project's hooks could not be looked up, so tool calls in"
            + " this project are refused until the archive is reachable";

    /** A tool.pre that failed with nothing more a model should read than that it did. */
    static final String UNJUDGED_CALL = "a hook failed while judging this call";

    /** A tool.post that failed with nothing more a model should read than that it did. */
    static final String UNJUDGED_RESULT = "the result was withheld because a hook failed while judging it";

    /** A gate that failed with no one hook to blame; the detail is the record's. */
    static final String UNJUDGED_GATE = "a hook failed while judging this, and a failed check"
            + " refuses";

    /**
     * What a local {@code allow} on a server-side command is recorded with: it is no decision
     * there (spec 2026-09-30-local-hooks-are-served decision 6). A person may pre-approve work on
     * their own machine; their laptop's files may not approve commands on the server's.
     */
    static final String LOCAL_ALLOW_ON_SERVER = "a local hook's allow counts only for a command on"
            + " the local side, and this one would run on the server, so it is no decision, and the"
            + " command meets its environment's own mode";

    /**
     * What a local {@code allow} on a local-side command is recorded with when the machine that
     * would run it is not shown to be the log owner's: it is no decision there either (spec
     * 2026-09-30-local-hooks-are-served decision 6, as ruled: "local" is the owner's own machine,
     * not any machine serving the project).
     */
    static final String LOCAL_ALLOW_ELSEWHERE = "a local hook's allow counts only for a command on"
            + " the log owner's own machine, and the machine this one would run on is not shown to"
            + " be theirs, so it is no decision, and the command meets its environment's own mode";

    private final Tier tier;
    private final Function<String, Long> projectIds;
    private final LongFunction<Path> directories;
    private final HookEngine engine;
    private final HooksProperties properties;
    private final Supplier<Instant> clock;
    private final ScheduledExecutorService timer;
    /** The local tier's sets; {@code null} on the project tier. */
    private final LocalHookSets local;
    /** Whether a session's machine is a log owner's own: {@code (log, session)}. Local tier only. */
    private final BiPredicate<String, String> ownersMachine;

    public ScriptHooks(Function<String, Long> projectIds, LongFunction<Path> directories,
            HookEngine engine, HooksProperties properties, Supplier<Instant> clock) {
        this(Tier.PROJECT, projectIds, directories, null, null, null, null, engine, properties,
                clock);
    }

    /**
     * A person's own hooks, served by their session and snapshotted per log (spec
     * 2026-09-30-local-hooks-are-served decisions 1, 3 and 10). A stage's set is the one its
     * context's conversation was pinned to when it opened; a context with no conversation, or a
     * log with no pin, has none.
     *
     * @param pinnedTo a log's pinned hash: {@code ConversationStore.localHooksOf}
     * @param ownerOf a log's owning account: {@code ConversationStore.ownerOf}. A loaded set is
     *     shared by owner and hash, never across accounts (decision 9, as amended)
     * @param stored a hash's files: {@code LocalHookSetStore.find}
     * @param ownersMachine whether a session's machine is the log's owner's: {@code (log,
     *     session)}, the session held in {@code SessionRegistry} by the account that owns the log.
     *     A local {@code allow} counts only when it says yes (decision 6)
     */
    public static ScriptHooks local(Function<String, Optional<String>> pinnedTo,
            Function<String, Optional<String>> ownerOf,
            Function<String, Optional<List<HookFile>>> stored,
            BiPredicate<String, String> ownersMachine, HookEngine engine,
            HooksProperties properties, Supplier<Instant> clock) {
        return new ScriptHooks(Tier.LOCAL, name -> null, id -> null,
                Objects.requireNonNull(pinnedTo, "pinnedTo"), Objects.requireNonNull(ownerOf, "ownerOf"),
                Objects.requireNonNull(stored, "stored"),
                Objects.requireNonNull(ownersMachine, "ownersMachine"), engine, properties, clock);
    }

    private ScriptHooks(Tier tier, Function<String, Long> projectIds, LongFunction<Path> directories,
            Function<String, Optional<String>> pinnedTo, Function<String, Optional<String>> ownerOf,
            Function<String, Optional<List<HookFile>>> stored,
            BiPredicate<String, String> ownersMachine, HookEngine engine,
            HooksProperties properties, Supplier<Instant> clock) {
        this.tier = tier;
        this.ownersMachine = ownersMachine;
        this.projectIds = Objects.requireNonNull(projectIds, "projectIds");
        this.directories = Objects.requireNonNull(directories, "directories");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        ScheduledThreadPoolExecutor limits = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "hook-time-limits");
            thread.setDaemon(true);
            return thread;
        });
        // Every call schedules a timer and nearly every call cancels it; without
        // this a cancelled timer stays queued until its deadline would have come.
        limits.setRemoveOnCancelPolicy(true);
        this.timer = limits;
        this.local = tier == Tier.LOCAL
                ? new LocalHookSets(pinnedTo, ownerOf, stored, engine, properties, clock, timer) : null;
    }

    /** How many local sets are loaded now; 0 on the project tier. For tests. */
    int loadedLocalSets() {
        return local == null ? 0 : local.loaded();
    }

    /** Retire the local sets nobody fired for, now rather than at the timer's next sweep. */
    void sweepLocalSets() {
        if (local != null) {
            local.sweep();
        }
    }

    // --- the stages ---------------------------------------------------------------------

    @Override
    public PromptPre promptPre(HookContext context, String utterance) {
        try {
            return firePromptPre(context, utterance);
        } catch (RuntimeException broken) {
            return new PromptPre(List.of(), List.of(unexpected(Stage.PROMPT_PRE, null, broken, null)));
        }
    }

    @Override
    public PromptPost promptPost(HookContext context, String reply, List<String> toolsAsked) {
        try {
            return firePromptPost(context, reply, toolsAsked);
        } catch (RuntimeException broken) {
            return new PromptPost(reply, List.of(unexpected(Stage.PROMPT_POST, null, broken, null)));
        }
    }

    @Override
    public ToolPre toolPre(HookContext context, String tool, String argumentsJson) {
        try {
            return fireToolPre(context, tool, argumentsJson);
        } catch (RuntimeException broken) {
            return new ToolPre(argumentsJson, UNJUDGED_CALL,
                    List.of(unexpected(Stage.TOOL_PRE, tool, broken, null)));
        }
    }

    @Override
    public ToolPost toolPost(HookContext context, String tool, String argumentsJson, String result) {
        try {
            return fireToolPost(context, tool, argumentsJson, result);
        } catch (RuntimeException broken) {
            return new ToolPost(UNJUDGED_RESULT,
                    List.of(unexpected(Stage.TOOL_POST, tool, broken, result)));
        }
    }

    private PromptPre firePromptPre(HookContext context, String utterance) {
        try (LocalHookSets.Lease lease = lease(context)) {
            return firePromptPre(lease.set, context, utterance);
        }
    }

    private PromptPre firePromptPre(ProjectHookSet set, HookContext context, String utterance) {
        List<Addition> additions = new ArrayList<>();
        List<HookRecord> records = startRecords(set, Stage.PROMPT_PRE, null);
        for (ProjectHookSet.Broken broken : set.broken) {
            records.add(failed(broken, Stage.PROMPT_PRE, null));
        }
        ObjectNode event = event(context);
        event.put("utterance", utterance);
        for (ProjectHookSet.Working hook : set.working) {
            if (!hook.stages().containsKey(Stage.PROMPT_PRE)) {
                continue;
            }
            Instant started = clock.get();
            try {
                Decision decision = call(hook, Stage.PROMPT_PRE, event);
                if (decision instanceof Decision.Add add) {
                    additions.add(new Addition(hook.name(), add.text(), add.mode()));
                    records.add(record(hook, Stage.PROMPT_PRE, null, HookRecord.ADD, null,
                            add.text(), null, started));
                } else {
                    records.add(record(hook, Stage.PROMPT_PRE, null, HookRecord.ALLOW, null, null,
                            null, started));
                }
            } catch (HookFailure failed) {
                records.add(record(hook, Stage.PROMPT_PRE, null, HookRecord.FAILED,
                        failed.getMessage(), null, null, started));
            } catch (RuntimeException failed) {
                records.add(record(hook, Stage.PROMPT_PRE, null, HookRecord.FAILED,
                        detail(failed), null, null, started));
            }
        }
        return new PromptPre(additions, records);
    }

    private PromptPost firePromptPost(HookContext context, String reply, List<String> toolsAsked) {
        try (LocalHookSets.Lease lease = lease(context)) {
            return firePromptPost(lease.set, context, reply, toolsAsked);
        }
    }

    private PromptPost firePromptPost(ProjectHookSet set, HookContext context, String reply,
            List<String> toolsAsked) {
        String current = reply;
        List<HookRecord> records = startRecords(set, Stage.PROMPT_POST, null);
        for (ProjectHookSet.Broken broken : set.broken) {
            records.add(failed(broken, Stage.PROMPT_POST, null));
        }
        for (ProjectHookSet.Working hook : set.working) {
            if (!hook.stages().containsKey(Stage.PROMPT_POST)) {
                continue;
            }
            ObjectNode event = event(context);
            event.put("reply", current);
            event.set("tools", JSON.valueToTree(toolsAsked));
            Instant started = clock.get();
            try {
                Decision decision = call(hook, Stage.PROMPT_POST, event);
                if (decision instanceof Decision.Redact redact) {
                    records.add(record(hook, Stage.PROMPT_POST, null, HookRecord.REDACT, null, null,
                            current, started));
                    current = redact.text();
                } else if (decision instanceof Decision.Note note) {
                    records.add(record(hook, Stage.PROMPT_POST, null, HookRecord.NOTE, null,
                            note.text(), null, started));
                } else {
                    records.add(record(hook, Stage.PROMPT_POST, null, HookRecord.ALLOW, null, null,
                            null, started));
                }
            } catch (HookFailure failed) {
                records.add(record(hook, Stage.PROMPT_POST, null, HookRecord.FAILED,
                        failed.getMessage(), null, null, started));
            } catch (RuntimeException failed) {
                records.add(record(hook, Stage.PROMPT_POST, null, HookRecord.FAILED,
                        detail(failed), null, null, started));
            }
        }
        return new PromptPost(current, records);
    }

    private ToolPre fireToolPre(HookContext context, String tool, String argumentsJson) {
        try (LocalHookSets.Lease lease = lease(context)) {
            return fireToolPre(lease.set, context, tool, argumentsJson);
        }
    }

    private ToolPre fireToolPre(ProjectHookSet set, HookContext context, String tool,
            String argumentsJson) {
        List<HookRecord> records = startRecords(set, Stage.TOOL_PRE, tool);
        if (!set.broken.isEmpty()) {
            ProjectHookSet.Broken first = set.broken.get(0);
            for (ProjectHookSet.Broken broken : set.broken) {
                records.add(failed(broken, Stage.TOOL_PRE, tool));
            }
            return new ToolPre(argumentsJson, first.lookup() ? LOOKUP_REFUSAL : whatBroke(first)
                    + ", so tool calls " + where() + " are refused" + until() + ": "
                    + first.reason(), records);
        }
        String current = argumentsJson;
        // The arguments the last counted allow judged. A rewrite that changes them voids it,
        // unless a hook at or after the rewrite allows again; Hooks.chain keeps the same rule
        // between layers (spec 2026-09-30-local-hooks-are-served, Task 7 fix round 2).
        String allowedFor = null;
        String asked = null;
        for (ProjectHookSet.Working hook : set.working) {
            if (!matches(hook, Stage.TOOL_PRE, tool)) {
                continue;
            }
            ObjectNode event = event(context);
            event.put("tool", tool);
            event.set("args", arguments(current));
            Instant started = clock.get();
            try {
                Decision decision = call(hook, Stage.TOOL_PRE, event);
                if (decision instanceof Decision.Deny deny) {
                    records.add(record(hook, Stage.TOOL_PRE, tool, HookRecord.DENY, deny.reason(),
                            null, null, started));
                    // JobRuntime already says "the call was refused by a hook: ",
                    // so the sentence here is only which hook, and why.
                    return new ToolPre(current, "'" + hook.name() + "': " + deny.reason(),
                            records);
                }
                if (decision instanceof Decision.Rewrite rewrite) {
                    records.add(record(hook, Stage.TOOL_PRE, tool, HookRecord.REWRITE, null,
                            rewrite.argumentsJson(), current, started));
                    if (!rewrite.argumentsJson().equals(current)) {
                        allowedFor = null;
                    }
                    current = rewrite.argumentsJson();
                } else if (decision instanceof Decision.Ask ask) {
                    records.add(record(hook, Stage.TOOL_PRE, tool, HookRecord.ASK, ask.reason(), null,
                            null, started));
                    if (asked == null) {
                        asked = "'" + hook.name() + "': " + ask.reason();
                    }
                } else {
                    boolean allow = decision instanceof Decision.Allow;
                    String discounted = allow ? discounted(context) : null;
                    if (allow && discounted == null) {
                        allowedFor = current;
                    }
                    // Plan choice 10: an allow that did not count is still recorded as allow;
                    // with no command to place, it has no reason.
                    records.add(record(hook, Stage.TOOL_PRE, tool, HookRecord.ALLOW,
                            discounted == null || discounted.isEmpty() ? null : discounted,
                            null, null, started));
                }
            } catch (HookFailure failed) {
                records.add(record(hook, Stage.TOOL_PRE, tool, HookRecord.FAILED,
                        failed.getMessage(), null, null, started));
                return new ToolPre(current, "the hook '" + hook.name() + "' failed, and a failed"
                        + " check refuses: " + failed.getMessage(), records);
            } catch (RuntimeException failed) {
                // Not a HookFailure's sentence, so nothing written for a model to
                // read: which hook, and that it failed. The rest is the record's.
                records.add(record(hook, Stage.TOOL_PRE, tool, HookRecord.FAILED,
                        detail(failed), null, null, started));
                return new ToolPre(current, "the hook '" + hook.name() + "' failed, and a failed"
                        + " check refuses", records);
            }
        }
        return new ToolPre(current, null, records, asked, allowedFor);
    }

    private ToolPost fireToolPost(HookContext context, String tool, String argumentsJson, String result) {
        try (LocalHookSets.Lease lease = lease(context)) {
            return fireToolPost(lease.set, context, tool, argumentsJson, result);
        }
    }

    private ToolPost fireToolPost(ProjectHookSet set, HookContext context, String tool,
            String argumentsJson, String result) {
        String current = result;
        List<HookRecord> records = startRecords(set, Stage.TOOL_POST, tool);
        if (!set.broken.isEmpty()) {
            // Tool stages close on a broken set on this side too: a result that
            // arrives while a restriction hook is not loaded is withheld rather
            // than handed over unexamined.
            ProjectHookSet.Broken first = set.broken.get(0);
            for (ProjectHookSet.Broken broken : set.broken) {
                records.add(new HookRecord("(unloaded)", broken.file(), tier,
                        Stage.TOOL_POST, tool, HookRecord.FAILED, broken.reason(), null, result, 0));
            }
            return new ToolPost(first.lookup()
                    ? "the result was withheld because this project's hooks could not be looked up"
                    : "the result was withheld because " + whatBroke(first) + ": " + first.reason(),
                    records);
        }
        for (ProjectHookSet.Working hook : set.working) {
            if (!matches(hook, Stage.TOOL_POST, tool)) {
                continue;
            }
            ObjectNode event = event(context);
            event.put("tool", tool);
            event.set("args", arguments(argumentsJson));
            event.put("result", current);
            Instant started = clock.get();
            try {
                Decision decision = call(hook, Stage.TOOL_POST, event);
                if (decision instanceof Decision.Redact redact) {
                    records.add(record(hook, Stage.TOOL_POST, tool, HookRecord.REDACT, null, null,
                            current, started));
                    current = redact.text();
                } else if (decision instanceof Decision.Note note) {
                    records.add(record(hook, Stage.TOOL_POST, tool, HookRecord.NOTE, null,
                            note.text(), null, started));
                } else {
                    records.add(record(hook, Stage.TOOL_POST, tool, HookRecord.ALLOW, null, null,
                            null, started));
                }
            } catch (HookFailure failed) {
                records.add(record(hook, Stage.TOOL_POST, tool, HookRecord.FAILED,
                        failed.getMessage(), null, current, started));
                current = "the result was withheld because the hook '" + hook.name() + "' failed: "
                        + failed.getMessage();
            } catch (RuntimeException failed) {
                records.add(record(hook, Stage.TOOL_POST, tool, HookRecord.FAILED,
                        detail(failed), null, current, started));
                current = "the result was withheld because the hook '" + hook.name() + "' failed";
            }
        }
        return new ToolPost(current, records);
    }

    @Override
    public StepPost stepPost(HookContext context, Step step) {
        try {
            return fireStepPost(context, step);
        } catch (RuntimeException failed) {
            return new StepPost(List.of(), List.of(unexpected(Stage.STEP_POST, null, failed, null)));
        }
    }

    private StepPost fireStepPost(HookContext context, Step step) {
        try (LocalHookSets.Lease lease = lease(context)) {
            return fireStepPost(lease.set, context, step);
        }
    }

    private StepPost fireStepPost(ProjectHookSet set, HookContext context, Step step) {
        List<String> notes = new ArrayList<>();
        List<HookRecord> records = startRecords(set, Stage.STEP_POST, null);
        for (ProjectHookSet.Broken broken : set.broken) {
            records.add(failed(broken, Stage.STEP_POST, null));
        }
        for (ProjectHookSet.Working hook : set.working) {
            if (!hook.stages().containsKey(Stage.STEP_POST)) {
                continue;
            }
            ObjectNode event = event(context);
            ObjectNode shown = event.putObject("step");
            shown.put("number", step.number());
            if (step.wireModel() != null) {
                shown.put("model", step.wireModel());
            }
            var calls = shown.putArray("calls");
            for (ToolCall call : step.calls()) {
                ObjectNode one = calls.addObject();
                one.put("tool", call.name());
                one.set("args", arguments(call.arguments()));
            }
            var results = shown.putArray("results");
            step.results().forEach(results::add);
            if (step.thinking() != null) {
                shown.put("thinking", step.thinking());
            }
            Instant started = clock.get();
            try {
                Decision decision = call(hook, Stage.STEP_POST, event);
                if (decision instanceof Decision.Note note) {
                    notes.add(note.text());
                    records.add(record(hook, Stage.STEP_POST, null, HookRecord.NOTE, null,
                            note.text(), null, started));
                } else {
                    records.add(record(hook, Stage.STEP_POST, null, HookRecord.ALLOW, null, null,
                            null, started));
                }
            } catch (HookFailure failed) {
                records.add(record(hook, Stage.STEP_POST, null, HookRecord.FAILED,
                        failed.getMessage(), null, null, started));
            } catch (RuntimeException failed) {
                records.add(record(hook, Stage.STEP_POST, null, HookRecord.FAILED,
                        detail(failed), null, null, started));
            }
        }
        return new StepPost(notes, records);
    }

    // --- the log stages (spec 2026-09-28-hooks-reach-the-log §3) --------------------------
    //
    // Every one fails open: a hook that throws, times out or did not load contributes nothing
    // and is recorded. None of them can hold up the transition it is told about (§2.4).

    @Override
    public LogOpen logOpen(HookContext context, LogOpening opening) {
        try {
            ObjectNode event = event(context);
            if (opening.parent() != null) {
                event.put("parent", opening.parent());
            }
            if (opening.owner() != null) {
                event.put("owner", opening.owner());
            }
            List<String> additions = new ArrayList<>();
            List<HookRecord> records = fireLog(Stage.LOG_OPEN, context, event,
                    (hook, decision, started) -> {
                        if (decision instanceof Decision.Add add) {
                            additions.add(add.text());
                            return record(hook, Stage.LOG_OPEN, null, HookRecord.ADD, null,
                                    add.text(), null, started);
                        }
                        return null;
                    });
            return new LogOpen(additions, records);
        } catch (RuntimeException broken) {
            return new LogOpen(List.of(), List.of(unexpected(Stage.LOG_OPEN, null, broken, null)));
        }
    }

    @Override
    public Notified logClose(HookContext context, LogClosing closing) {
        return notifying(Stage.LOG_CLOSE, context, () -> {
            ObjectNode event = event(context);
            event.put("ending", closing.ending());
            event.put("turns", closing.turns());
            return event;
        });
    }

    @Override
    public DeliveryPre deliveryPre(HookContext context, Handover handover) {
        try {
            List<String> notes = new ArrayList<>();
            List<HookRecord> records = fireLog(Stage.DELIVERY_PRE, context,
                    delivery(context, handover), (hook, decision, started) -> {
                        if (decision instanceof Decision.Note note) {
                            notes.add(note.text());
                            return record(hook, Stage.DELIVERY_PRE, null, HookRecord.NOTE, null,
                                    note.text(), null, started);
                        }
                        return null;
                    });
            return new DeliveryPre(notes, records);
        } catch (RuntimeException broken) {
            return new DeliveryPre(List.of(),
                    List.of(unexpected(Stage.DELIVERY_PRE, null, broken, null)));
        }
    }

    @Override
    public Notified deliveryPost(HookContext context, Handover handover) {
        return notifying(Stage.DELIVERY_POST, context, () -> {
            ObjectNode event = delivery(context, handover);
            event.put("delivered", true);
            return event;
        });
    }

    // --- the in-turn gates (spec 2026-09-28-hooks-reach-the-log §3, slices 2 and 3) -----------
    //
    // They fail closed, as tool.pre does (§2.4): a set with a file that did not load refuses, and
    // so does a hook that throws, times out or returns a decision its stage does not have — an
    // allow on approval.pre among them (decision 5). The first denial ends the stage; notes
    // collect in filename order.

    @Override
    public Gate stagePre(HookContext context, StageStart start) {
        return gate(Stage.STAGE_PRE, context, () -> {
            ObjectNode event = event(context);
            shown(event, start.stage());
            if (start.returning() != null) event.put("returning", start.returning());
            if (start.returnsLeft() != null) event.put("returnsLeft", start.returnsLeft());
            return event;
        });
    }

    @Override
    public Gate stagePost(HookContext context, StageDone done) {
        return gate(Stage.STAGE_POST, context, () -> {
            ObjectNode event = event(context);
            shown(event, done.stage());
            event.put("summary", done.summary());
            if (done.check() != null) {
                ObjectNode check = event.putObject("check");
                check.set("command", JSON.valueToTree(done.check()));
                check.put("passed", true);
            }
            return event;
        });
    }

    @Override
    public Gate approvalPre(HookContext context, Approving approving) {
        return gate(Stage.APPROVAL_PRE, context, () -> {
            ObjectNode event = event(context);
            event.set("argv", JSON.valueToTree(approving.argv()));
            event.put("cwd", approving.cwd());
            if (approving.reason() != null) {
                event.put("reason", approving.reason());
            }
            event.put("attended", approving.attended());
            event.set("scopes", JSON.valueToTree(approving.scopes()));
            return event;
        });
    }

    // --- the out-of-turn stages of slices 3 and 4: fail open, like slice 1's ------------------

    @Override
    public Notified approvalPost(HookContext context, ApprovalAnswer answer) {
        return notifying(Stage.APPROVAL_POST, context, () -> {
            ObjectNode event = event(context);
            event.put("approval", answer.approval());
            event.put("decision", answer.decision());
            if (answer.scope() != null) {
                event.put("scope", answer.scope());
            }
            return event;
        });
    }

    /**
     * The folder's summary is in the event, so a hook keeps a marker only when the folder lost it
     * (spec 2026-09-28-hooks-reach-the-log §3, amended 2026-09-29). Fails open: nothing kept and
     * nobody told. Its hooks share {@code deadline} rather than taking a limit each (decision 4).
     */
    @Override
    public FoldPost foldPost(HookContext context, Summarised summarised, Deadline deadline) {
        try {
            ObjectNode event = event(context);
            event.put("through", summarised.through());
            event.put("entries", summarised.entries());
            event.put("estimatedTokens", summarised.estimatedTokens());
            event.put("summary", summarised.summary());
            List<FoldPost.Kept> kept = new ArrayList<>();
            List<Notified.Notice> notices = new ArrayList<>();
            List<HookRecord> records = fireLog(Stage.FOLD_POST, context, event,
                    (hook, decision, started) -> {
                        if (decision instanceof Decision.Keep keep) {
                            kept.add(new FoldPost.Kept(hook.name(), hook.file(), tier,
                                    keep.text()));
                            return record(hook, Stage.FOLD_POST, null, HookRecord.KEEP, null,
                                    keep.text(), null, started);
                        }
                        if (decision instanceof Decision.Notify notify) {
                            notices.add(new Notified.Notice(hook.name(), notify.text()));
                            return record(hook, Stage.FOLD_POST, null, HookRecord.NOTIFY, null,
                                    notify.text(), null, started);
                        }
                        return null;
                    }, deadline);
            return new FoldPost(kept, notices, records);
        } catch (RuntimeException broken) {
            return new FoldPost(List.of(), List.of(),
                    List.of(unexpected(Stage.FOLD_POST, null, broken, null)));
        }
    }

    private Gate gate(Stage stage, HookContext context, Supplier<ObjectNode> event) {
        try {
            return fireGate(stage, context, event.get());
        } catch (RuntimeException broken) {
            return new Gate(UNJUDGED_GATE, List.of(),
                    List.of(unexpected(stage, null, broken, null)));
        }
    }

    private Gate fireGate(Stage stage, HookContext context, ObjectNode event) {
        try (LocalHookSets.Lease lease = lease(context)) {
            return fireGate(lease.set, stage, context, event);
        }
    }

    private Gate fireGate(ProjectHookSet set, Stage stage, HookContext context, ObjectNode event) {
        List<HookRecord> records = startRecords(set, stage, null);
        if (!set.broken.isEmpty()) {
            ProjectHookSet.Broken first = set.broken.get(0);
            for (ProjectHookSet.Broken broken : set.broken) {
                records.add(failed(broken, stage, null));
            }
            return new Gate(first.lookup()
                    ? "this project's hooks could not be looked up, so " + refused(stage)
                            + " in this project are refused until the archive is reachable"
                    : whatBroke(first) + ", so " + refused(stage) + " " + where() + " are refused"
                            + until() + ": " + first.reason(),
                    List.of(), records);
        }
        List<String> notes = new ArrayList<>();
        for (ProjectHookSet.Working hook : set.working) {
            if (!firesFor(hook, stage, context.origin())) {
                continue;
            }
            Instant started = clock.get();
            try {
                Decision decision = call(hook, stage, event);
                if (decision instanceof Decision.Deny deny) {
                    records.add(record(hook, stage, null, HookRecord.DENY, deny.reason(), null,
                            null, started));
                    return new Gate("'" + hook.name() + "': " + deny.reason(), notes, records);
                }
                if (decision instanceof Decision.Note note) {
                    notes.add(note.text());
                    records.add(record(hook, stage, null, HookRecord.NOTE, null, note.text(), null,
                            started));
                } else {
                    records.add(record(hook, stage, null, HookRecord.ALLOW, null, null, null,
                            started));
                }
            } catch (HookFailure failed) {
                records.add(record(hook, stage, null, HookRecord.FAILED, failed.getMessage(), null,
                        null, started));
                return new Gate("the hook '" + hook.name() + "' failed, and a failed check"
                        + " refuses: " + failed.getMessage(), notes, records);
            } catch (RuntimeException failed) {
                records.add(record(hook, stage, null, HookRecord.FAILED, detail(failed), null,
                        null, started));
                return new Gate("the hook '" + hook.name() + "' failed, and a failed check"
                        + " refuses", notes, records);
            }
        }
        return new Gate(null, notes, records);
    }

    /** What a broken set refuses, in the words of the stage it refuses at. */
    private static String refused(Stage stage) {
        return stage == Stage.APPROVAL_PRE ? "questions to a person" : "stage moves";
    }

    private static void shown(ObjectNode event, StageShown stage) {
        ObjectNode shown = event.putObject("stage");
        shown.put("id", stage.id());
        shown.put("title", stage.title());
        shown.put("index", stage.index());
        shown.put("count", stage.count());
    }

    /** What one log-stage decision contributes: its record, or {@code null} to record an allow. */
    @FunctionalInterface
    private interface Taken {
        HookRecord take(ProjectHookSet.Working hook, Decision decision, Instant started);
    }

    private Notified notifying(Stage stage, HookContext context, Supplier<ObjectNode> event) {
        try {
            List<Notified.Notice> notices = new ArrayList<>();
            List<HookRecord> records = fireLog(stage, context, event.get(), (hook, decision, started) -> {
                if (decision instanceof Decision.Notify notify) {
                    notices.add(new Notified.Notice(hook.name(), notify.text()));
                    return record(hook, stage, null, HookRecord.NOTIFY, null, notify.text(), null,
                            started);
                }
                return null;
            });
            return new Notified(notices, records);
        } catch (RuntimeException broken) {
            return new Notified(List.of(), List.of(unexpected(stage, null, broken, null)));
        }
    }

    /** One log stage over the project's set, failing open for each hook that breaks. */
    private List<HookRecord> fireLog(Stage stage, HookContext context, ObjectNode event,
            Taken taken) {
        return fireLog(stage, context, event, taken, null);
    }

    /**
     * The same, every hook within {@code shared} when there is one: each runs with what is left
     * of it, so a hook that answered keeps its answer however the ones after it fare, and one
     * reached with nothing left is not run and is recorded as timed out (spec
     * 2026-09-28-hooks-reach-the-log decision 4, amended 2026-09-29). Without one, each hook has
     * the whole limit, as every other stage gives it.
     *
     * <p>The deadline bounds the hooks' running, not a load: {@link #lease} (re)loading a
     * file and the pool building a fresh context each keep their own full limit, because the set
     * and the pool serve every stage and a load cut short would break a file for all of them.
     */
    private List<HookRecord> fireLog(Stage stage, HookContext context, ObjectNode event,
            Taken taken, Deadline shared) {
        try (LocalHookSets.Lease lease = lease(context)) {
            return fireLog(lease.set, stage, context, event, taken, shared);
        }
    }

    private List<HookRecord> fireLog(ProjectHookSet set, Stage stage, HookContext context,
            ObjectNode event, Taken taken, Deadline shared) {
        List<HookRecord> records = startRecords(set, stage, null);
        for (ProjectHookSet.Broken broken : set.broken) {
            records.add(failed(broken, stage, null));
        }
        for (ProjectHookSet.Working hook : set.working) {
            if (!firesFor(hook, stage, context.origin())) {
                continue;
            }
            Instant started = clock.get();
            Duration limit = shared == null ? properties.getTimeout() : shared.remaining();
            // Both shared-deadline sentences name the one limit configured, never the leftover a
            // hook was handed: "497 ms" is a limit nobody set.
            String sharedLimit = shared == null ? null : stage.wireName() + "'s shared "
                    + shared.limit().toMillis() + " ms limit";
            if (limit.isZero()) {
                records.add(record(hook, stage, null, HookRecord.FAILED, "the hook '" + hook.name()
                        + "' was not run: nothing was left of " + sharedLimit + " when it was"
                        + " reached", null, null, started));
                continue;
            }
            try {
                Decision decision = call(hook, stage, event, limit,
                        sharedLimit == null ? null : "the time left of " + sharedLimit);
                HookRecord kept = taken.take(hook, decision, started);
                records.add(kept != null ? kept
                        : record(hook, stage, null, HookRecord.ALLOW, null, null, null, started));
            } catch (HookFailure failed) {
                records.add(record(hook, stage, null, HookRecord.FAILED, failed.getMessage(), null,
                        null, started));
            } catch (RuntimeException failed) {
                records.add(record(hook, stage, null, HookRecord.FAILED, detail(failed), null, null,
                        started));
            }
        }
        return records;
    }

    /** A log stage fires for the origins a hook named, or for every origin when it named none. */
    private static boolean firesFor(ProjectHookSet.Working hook, Stage stage, String origin) {
        List<String> origins = hook.stages().get(stage);
        return origins != null && (origins.isEmpty() || origins.contains(origin));
    }

    private static ObjectNode delivery(HookContext context, Handover handover) {
        ObjectNode event = event(context);
        ObjectNode source = event.putObject("source");
        source.put("log", context.conversation());
        source.put("origin", context.origin());
        event.put("destination", handover.destination());
        event.put("text", handover.text());
        return event;
    }

    @Override
    public void close() {
        if (local != null) {
            local.close();
        }
        for (Holder holder : sets.values()) {
            ProjectHookSet held = holder.set;
            if (held != null) {
                held.close();
            }
        }
        sets.clear();
        timer.shutdownNow();
    }

    // --- loading ------------------------------------------------------------------------

    /**
     * One project's loaded set, and the lock its reload is done under.
     *
     * <p><b>Not {@code ConcurrentHashMap.compute}</b>, which is what this was: that
     * holds the map's bin lock for the whole load, and ids share bins (7 and 23 do
     * in a default-sized map), so one project's reload — every file bounded by the
     * time limit, but still up to N of them — stalled every fire of its neighbours,
     * recorded nowhere. The map only ever creates a holder; loading happens under
     * the holder's own lock, and a set that is still current is read without it.
     */
    private static final class Holder {
        private volatile ProjectHookSet set;
    }

    private final Map<Long, Holder> sets = new ConcurrentHashMap<>();

    /**
     * The set a stage runs over, held until the stage is done with it: on the local tier a lease
     * the sweep will not retire while it is held (plan choice 12, as amended); on the project tier
     * the directory's set, with nothing to release.
     */
    private LocalHookSets.Lease lease(HookContext context) {
        return local != null ? local.acquire(context) : LocalHookSets.Lease.of(setFor(context));
    }

    private ProjectHookSet setFor(HookContext context) {
        if (context.project() == null) {
            return ProjectHookSet.empty();
        }
        Long id;
        try {
            id = projectIds.apply(context.project());
        } catch (RuntimeException unreachable) {
            // Not "no hooks": that would open every tool stage the project closed
            // for as long as the archive is unavailable. The exception's message
            // is kept for the record and never shown to the model — see Broken.
            return ProjectHookSet.unreachable("this project's hooks could not be looked up: "
                    + detail(unreachable));
        }
        if (id == null) {
            return ProjectHookSet.empty();
        }
        Path directory = directories.apply(id);
        String now = ProjectHookSet.fingerprint(directory);
        Holder holder = sets.computeIfAbsent(id, key -> new Holder());
        ProjectHookSet held = holder.set;
        if (held != null && held.fingerprint.equals(now)) {
            return held;
        }
        ProjectHookSet retired = null;
        ProjectHookSet current;
        synchronized (holder) {
            current = holder.set;
            if (current == null || !current.fingerprint.equals(now)) {
                retired = current;
                current = ProjectHookSet.load(directory, engine, properties, clock, timer);
                holder.set = current;
            }
        }
        // Outside the lock: retiring closes idle contexts, and a fire waiting for
        // the new set has no reason to wait for the old one's cleanup too.
        if (retired != null) {
            retired.close();
        }
        return current;
    }

    /**
     * The records a stage starts with: the one saying this log's local snapshot could not be
     * loaded, on the first fire in the log to find it so (spec 2026-09-30-local-hooks-are-served
     * decision 7). Empty on every other fire, and always on the project tier.
     */
    private List<HookRecord> startRecords(ProjectHookSet set, Stage stage, String tool) {
        List<HookRecord> records = new ArrayList<>();
        if (set.withheld != null) {
            records.add(new HookRecord(HookFile.WHOLE_SET, null, tier, stage, tool,
                    HookRecord.FAILED, set.withheld, null, null, 0));
        }
        return records;
    }

    /** The first half of a refusal: a file that did not load, or hooks that could not be read at all. */
    private String whatBroke(ProjectHookSet.Broken broken) {
        if (broken.file() == null) {
            return "this project's hooks could not be read";
        }
        return tier == Tier.LOCAL ? "a local hook file did not load" : "a hook file did not load";
    }

    /** Where a broken set refuses: the project, or the one log a local snapshot belongs to. */
    private String where() {
        return tier == Tier.LOCAL ? "in this log" : "in this project";
    }

    /** Until when: a project file is reread when fixed; a local snapshot never is (decision 3). */
    private String until() {
        return tier == Tier.LOCAL ? ", and a fix reaches only a log opened after it"
                : " until it is fixed";
    }

    // --- calling ------------------------------------------------------------------------

    private Decision call(ProjectHookSet.Working hook, Stage stage, ObjectNode event) throws HookFailure {
        return call(hook, stage, event, properties.getTimeout(), null);
    }

    /**
     * @param limitNamed how a time-limit failure names the limit; {@code null} for "its time
     *     limit of N ms"
     */
    private Decision call(ProjectHookSet.Working hook, Stage stage, ObjectNode event,
            Duration limit, String limitNamed) throws HookFailure {
        String decided = hook.pool().call(stage, event.toString(), limit, limitNamed);
        return Decision.read(stage, decided);
    }

    /**
     * Why an {@code allow} from this tier does not approve, or null when it does: a project hook's
     * always does; a local hook's only for a command on the local side, on the log owner's own
     * machine (spec 2026-09-30-local-hooks-are-served decision 6). Empty, and no reason, when there
     * is no command to approve (plan choice 10). A lookup that fails approves nothing.
     */
    private String discounted(HookContext context) {
        if (tier != Tier.LOCAL) {
            return null;
        }
        HookContext.RunEnvironment environment = context.environment();
        if (environment == null) {
            return "";
        }
        if (!EnvironmentFile.LOCAL.equals(environment.side())) {
            return LOCAL_ALLOW_ON_SERVER;
        }
        boolean owners;
        try {
            owners = context.conversation() != null && environment.session() != null
                    && ownersMachine.test(context.conversation(), environment.session());
        } catch (RuntimeException unknowable) {
            owners = false;
        }
        return owners ? null : LOCAL_ALLOW_ELSEWHERE;
    }

    private static boolean matches(ProjectHookSet.Working hook, Stage stage, String tool) {
        List<String> tools = hook.stages().get(stage);
        if (tools == null) {
            return false;
        }
        for (String named : tools) {
            if (named.equals(tool)
                    || (named.endsWith("*") && tool.startsWith(named.substring(0, named.length() - 1)))) {
                return true;
            }
        }
        return false;
    }

    private static ObjectNode event(HookContext context) {
        ObjectNode event = JSON.createObjectNode();
        ObjectNode about = event.putObject("context");
        if (context.agent() != null) {
            about.put("agent", context.agent());
        }
        about.put("bot", context.bot());
        if (context.project() != null) {
            about.put("project", context.project());
        }
        if (context.origin() != null) {
            // A log stage's context (spec 2026-09-28-hooks-reach-the-log §3): the log and its
            // origin, and `conversation` only on a turn log, the one a person talks in.
            about.put("origin", context.origin());
            about.put("log", context.conversation());
            if ("turn".equals(context.origin())) {
                about.put("conversation", context.conversation());
            }
        } else if (context.conversation() != null) {
            about.put("conversation", context.conversation());
        }
        about.put("side", context.side());
        if (context.environment() != null) {
            ObjectNode environment = about.putObject("environment");
            environment.put("side", context.environment().side());
            environment.put("mode", context.environment().mode());
            environment.put("shells", context.environment().shells());
            environment.put("isolation", context.environment().isolation());
        }
        if (context.orchestration() != null) {
            // Spec 2026-09-28-hooks-reach-the-log §3: `orchestration?`, when one is involved.
            ObjectNode orchestration = about.putObject("orchestration");
            orchestration.put("id", context.orchestration().id());
            orchestration.put("definition", context.orchestration().definition());
            if (context.orchestration().stage() != null) {
                orchestration.put("stage", context.orchestration().stage());
            }
        }
        if (context.document() != null) {
            about.set("document", JSON.valueToTree(context.document()));
        }
        return event;
    }

    private static JsonNode arguments(String argumentsJson) {
        try {
            JsonNode parsed = JSON.readTree(argumentsJson == null || argumentsJson.isBlank()
                    ? "{}" : argumentsJson);
            return parsed != null && parsed.isObject() ? parsed : JSON.createObjectNode();
        } catch (Exception unparsable) {
            return JSON.createObjectNode();
        }
    }

    private HookRecord record(ProjectHookSet.Working hook, Stage stage, String tool, String decision,
            String reason, String added, String original, Instant started) {
        return new HookRecord(hook.name(), hook.file(), tier, stage, tool, decision, reason,
                added, original, Duration.between(started, clock.get()).toMillis());
    }

    private HookRecord failed(ProjectHookSet.Broken broken, Stage stage, String tool) {
        return new HookRecord("(unloaded)", broken.file(), tier, stage, tool,
                HookRecord.FAILED, broken.reason(), null, null, 0);
    }

    /** A failure with no one hook to blame, caught at a stage's entry. */
    private HookRecord unexpected(Stage stage, String tool, RuntimeException broken, String original) {
        return new HookRecord("(hooks)", null, tier, stage, tool, HookRecord.FAILED,
                detail(broken), null, original, 0);
    }

    /** For a record, which a person reads: the exception's kind, and its message when it has one. */
    private static String detail(RuntimeException failed) {
        String message = failed.getMessage();
        return message == null || message.isBlank() ? failed.getClass().getSimpleName()
                : failed.getClass().getSimpleName() + ": " + message;
    }
}
