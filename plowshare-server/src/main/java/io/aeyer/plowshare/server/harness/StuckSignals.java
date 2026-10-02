package io.aeyer.plowshare.server.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.agents.AgentRunTool;
import io.aeyer.plowshare.server.agents.AskTool;
import io.aeyer.plowshare.server.agents.CallerOrchestrationTools;
import io.aeyer.plowshare.server.agents.ConductorTools;
import io.aeyer.plowshare.server.agents.ConversationTrajectoryTool;
import io.aeyer.plowshare.server.agents.DocumentTools;
import io.aeyer.plowshare.server.agents.FetchTool;
import io.aeyer.plowshare.server.agents.FileTools;
import io.aeyer.plowshare.server.agents.MemoryTools;
import io.aeyer.plowshare.server.agents.ResultTools;
import io.aeyer.plowshare.server.agents.RunTool;
import io.aeyer.plowshare.server.agents.SearchTool;
import io.aeyer.plowshare.server.agents.TodoTools;
import io.aeyer.plowshare.server.agents.ToolLines;
import io.aeyer.plowshare.server.events.InboxTool;
import io.aeyer.plowshare.server.hooks.Step;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The signs of a turn being stuck that {@code harness:stuck} fires on, read off the turn's own
 * steps. <b>A step count alone is never one.</b>
 *
 * <h2>Why not a step count</h2>
 *
 * <p>Measured 2026-09-29/30, one eleven-hour {@code implement_specification} run: the trap fired
 * on step count 187 times in 88 conversations -- 37 of 40 coder turns at step 4, 30 of them again
 * at step 8 -- and the advisor always found something to say. Right after a hint, 42% of coder
 * edits went to files not read in that turn (25% otherwise); one coder answered the hint as a
 * message and handed back a plan instead of the work; ten turns ended within two steps of one,
 * four with their checks still failing. A coder four steps in is usually working. What these
 * signals look for is the turn not moving: the same failure again, a run of failures, the same
 * call and answer again, a long stretch of reading with nothing done.
 *
 * <h2>The four signals</h2>
 *
 * <ul>
 *   <li><b>{@code repeat-failures}</b>: a failed call -- same tool, same salient argument, same
 *       result once numbers are blanked, so a timing or a count does not make two failures
 *       different -- seen this many times in the turn, whatever came between. Edits between do
 *       not reset it: the same failure surviving edits is the pattern. <b>A refusal counts by its
 *       words alone</b>: the same tool refusing in the same words is the same failure whatever
 *       the arguments were, since nothing ran and the words are the tool's own answer to the
 *       shape of the call. Measured 2026-09-30: a coder sent {@code run} six different heredocs,
 *       each refused in one sentence, and no two were counted alike. A refusal is an outcome of
 *       {@code refused} (the tool, the fence or the runtime said no before anything ran) or
 *       {@code denied} (a hook did); a program that ran and failed is not one, and two of those
 *       with the same output were still asked different things.
 *   <li><b>{@code failure-streak}</b>: this many failed calls in a row; one that did not fail
 *       resets it.
 *   <li><b>{@code repeat-calls}</b>: the same call -- same tool, same arguments once parsed, so
 *       key order and spacing do not count -- with the same answer this many times, with only
 *       reading between. Anything that may have changed the world between two of them (a
 *       write, a run, a delegation, a todo move that did not fail) resets it: running a check
 *       again after an edit is the work, not a loop.
 *   <li><b>{@code read-only-steps}</b>: this many steps in a row whose calls only read or
 *       searched, <b>in a run offered a tool that acts</b> -- one that writes, runs, delegates or
 *       moves an orchestration ({@link #ACTS}). Twelve reworded searches for a Fed hike "today",
 *       on a Sunday, is the failure the trap was first built for. A run that can only read is
 *       never told it has only read: measured 2026-09-30, a {@code code_reviewer} holding reads
 *       and searches alone was told after ten steps that nothing had been written or run.
 * </ul>
 *
 * <p>Zero turns a signal off. A failure is an outcome that is not {@code ok}, {@code ran} (a
 * command whose status line was cut) or {@code asked} (a question put to a person); the
 * outcome is the runtime's own when the step carries it, and otherwise {@link ToolLines#outcome}
 * over the result.
 *
 * <h2>Who owns an unbroken repeat</h2>
 *
 * <p>{@code JobRuntime.Repeats} already notes a call repeated byte for byte back to back, at
 * three and five, and ends the run at eight. <b>That note owns the unbroken run</b>: a call
 * identical to the one just before it is skipped here -- it neither counts toward a repeat nor
 * adds to or breaks a streak -- so a model repeating one call is told once, by the runtime,
 * and not a second time by the advisor. What this adds is what Repeats cannot see: the same
 * call coming back after something else, and arguments equal once parsed but not byte for byte.
 *
 * <p>Not thread-safe: one instance per trap, which is one per run, fed on the run's thread.
 */
final class StuckSignals {

    static final String REPEAT_FAILURES = "repeat-failures";
    static final String FAILURE_STREAK = "failure-streak";
    static final String REPEAT_CALLS = "repeat-calls";
    static final String READ_ONLY_STEPS = "read-only-steps";

    /** Tools that only read or search. Any other -- a tool not named here included -- ends a
     *  read-only stretch: a missed stretch costs a hint that does not fire, a false one a hint
     *  about work that is being done. */
    private static final Set<String> READS = Set.of(FileTools.READ_NAME, FileTools.STAT_NAME,
            FileTools.GLOB_NAME, FileTools.GREP_NAME, FileTools.ROOTS_NAME, SearchTool.NAME,
            FetchTool.NAME, AskTool.NAME, DocumentTools.SEARCH_NAME, DocumentTools.LIST_NAME,
            MemoryTools.RECALL_NAME, MemoryTools.READ_NAME, ResultTools.READ_NAME,
            ResultTools.LIST_NAME, TodoTools.READ_NAME, InboxTool.NAME,
            ConversationTrajectoryTool.NAME, CallerOrchestrationTools.STATUS_NAME);

    /**
     * The tools that act: a run offered none of them can only read, so a read-only stretch is its
     * work and not a sign. From the tools the run was offered, never the agent's name. An
     * orchestration's start tool is named for the orchestration, and never comes without
     * {@code orchestration_answer} and {@code orchestration_cancel} ({@code
     * CallerOrchestrationTools.forRun}), so those two stand for it.
     */
    static final Set<String> ACTS = Set.of(FileTools.EDIT_NAME, FileTools.DELETE_NAME,
            FileTools.MOVE_NAME, RunTool.NAME, AgentRunTool.NAME,
            CallerOrchestrationTools.ANSWER_NAME, CallerOrchestrationTools.CANCEL_NAME,
            ConductorTools.ASK_NAME, ConductorTools.FINISH_NAME, ConductorTools.CHECK_NAME);

    /** Outcomes that say the call was refused before anything ran; see the class note. */
    private static final Set<String> REFUSALS = Set.of(ToolLines.REFUSED, ToolLines.DENIED);

    /** Outcomes that are not a failure; see the class note. */
    private static final Set<String> FINE = Set.of(ToolLines.OK, ToolLines.RAN, ToolLines.ASKED);

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final ObjectMapper SORTED = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    /** How much of a failing result a signal's sentence quotes. */
    static final int FAILURE_EXCERPT = 200;

    /** How much of a call's arguments a sentence quotes when the tool has no salient one. */
    private static final int ARGUMENTS_EXCERPT = 120;

    /**
     * A sign that fired: which, and what the advisor and the model are told about it.
     *
     * @param key what a repeat counted, so acting on it clears that count; null for the others
     */
    record Signal(String name, String sentence, String key) {
    }

    private final int repeatFailures;
    private final int failureStreak;
    private final int repeatCalls;
    private final int readOnlySteps;

    private final Map<String, Integer> failures = new HashMap<>();
    private final Map<String, Integer> calls = new HashMap<>();
    private int streak;
    private int readOnly;
    private String lastName;
    private String lastArguments;

    StuckSignals(int repeatFailures, int failureStreak, int repeatCalls, int readOnlySteps) {
        this.repeatFailures = repeatFailures;
        this.failureStreak = failureStreak;
        this.repeatCalls = repeatCalls;
        this.readOnlySteps = readOnlySteps;
    }

    /**
     * Counts one step, and says the sign it tipped over, if any. When several did, the most
     * specific wins: a repeated failure, then a repeated call, then a streak, then a stretch.
     * See {@link #acted} for what happens to a sign nobody acted on.
     *
     * @param offered the tool names the run was offered; a stretch fires only when one acts
     */
    Optional<Signal> step(Step step, Set<String> offered) {
        Signal repeatedFailure = null;
        Signal repeatedCall = null;
        boolean onlyReads = true;
        for (int i = 0; i < step.calls().size(); i++) {
            ToolCall call = step.calls().get(i);
            String result = step.results().get(i);
            String outcome = step.outcomes().isEmpty() ? ToolLines.outcome(call.name(), result)
                    : step.outcomes().get(i);
            boolean reads = READS.contains(call.name());
            onlyReads &= reads;
            boolean continuing = call.name().equals(lastName) && call.arguments().equals(lastArguments);
            lastName = call.name();
            lastArguments = call.arguments();
            if (continuing) {
                continue; // JobRuntime.Repeats' to note; see the class note
            }
            boolean failed = !FINE.contains(outcome);
            if (failed) {
                streak++;
                // A refusal by its words alone, under a key no ordinary failure's can equal (it
                // has one field fewer); see the class note.
                boolean refused = REFUSALS.contains(outcome);
                String key = refused ? call.name() + '\0' + normal(result)
                        : call.name() + '\0' + salient(call) + '\0' + normal(result);
                int seen = failures.merge(key, 1, Integer::sum);
                if (repeatFailures > 0 && seen >= repeatFailures && repeatedFailure == null) {
                    repeatedFailure = new Signal(REPEAT_FAILURES, "the same " + (refused ? "refusal" : "failure")
                            + " has come back " + seen + " times: " + shown(call) + " → " + excerpt(result), key);
                }
                continue;
            }
            streak = 0;
            String key = call.name() + '\0' + canonical(call.arguments()) + '\0' + normal(result);
            if (!reads) {
                // It may have changed what the others would answer: only its own count survives.
                calls.keySet().retainAll(Set.of(key));
            }
            int seen = calls.merge(key, 1, Integer::sum);
            if (repeatCalls > 0 && seen >= repeatCalls && repeatedCall == null) {
                repeatedCall = new Signal(REPEAT_CALLS, "the same call has come back " + seen
                        + " times with the same answer: " + shown(call), key);
            }
        }
        readOnly = onlyReads && !step.calls().isEmpty() ? readOnly + 1 : 0;
        if (repeatedFailure != null) {
            return Optional.of(repeatedFailure);
        }
        if (repeatedCall != null) {
            return Optional.of(repeatedCall);
        }
        if (failureStreak > 0 && streak >= failureStreak) {
            return Optional.of(new Signal(FAILURE_STREAK, streak == 1
                    ? "the last tool call failed or was refused"
                    : "the last " + streak + " tool calls all failed or were refused", null));
        }
        if (readOnlySteps > 0 && readOnly >= readOnlySteps
                && offered.stream().anyMatch(ACTS::contains)) {
            return Optional.of(new Signal(READ_ONLY_STEPS, "the last " + readOnly
                    + " steps only read or searched; nothing was written, run or handed on", null));
        }
        return Optional.empty();
    }

    /**
     * The harness asked about this sign, so its count starts again: the same sign fires again
     * only after as much again. One not acted on -- a consult already out, or the turn's
     * consults spent -- keeps its count: a streak or a stretch is said again at the next step,
     * a repeat when it next comes back.
     */
    void acted(Signal signal) {
        switch (signal.name()) {
            case REPEAT_FAILURES -> failures.remove(signal.key());
            case REPEAT_CALLS -> calls.remove(signal.key());
            case FAILURE_STREAK -> streak = 0;
            case READ_ONLY_STEPS -> readOnly = 0;
            default -> throw new IllegalArgumentException("no signal is called " + signal.name());
        }
    }

    /** What a person follows the call by: its salient argument, or its arguments cut. */
    private static String salient(ToolCall call) {
        String salient = ToolLines.salient(call.name(), call.arguments());
        return salient.isEmpty() ? canonical(call.arguments()) : salient;
    }

    private static String shown(ToolCall call) {
        String salient = ToolLines.salient(call.name(), call.arguments());
        return call.name() + " " + (salient.isEmpty()
                ? StuckBrief.cut(flat(call.arguments()), ARGUMENTS_EXCERPT) : salient);
    }

    private static String excerpt(String result) {
        return StuckBrief.cut(flat(result), FAILURE_EXCERPT);
    }

    private static String flat(String text) {
        return SPACE.matcher(text == null ? "" : text).replaceAll(" ").strip();
    }

    /** A result with its numbers blanked, so a timing or a count does not make it another. */
    private static String normal(String result) {
        return NUMBER.matcher(flat(result)).replaceAll("#");
    }

    /** Arguments parsed and written back with their keys sorted; as sent when they do not parse. */
    private static String canonical(String arguments) {
        String text = arguments == null ? "" : arguments.strip();
        try {
            return SORTED.writeValueAsString(SORTED.readValue(text, Object.class));
        } catch (Exception unreadable) {
            return text;
        }
    }
}
