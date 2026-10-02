package io.aeyer.plowshare.server.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.Step;
import io.aeyer.plowshare.server.hooks.StepPost;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StuckTrapTest {

    /** A coder's run: it reads, and it can also write and run. */
    private static final HookContext CONTEXT = new HookContext("aristoxenus", true, java.util.Set.of("search",
            "file_read", "file_grep", "file_glob", "file_edit", "run", "todo_write"), null, "cnv_1",
            HookContext.SERVER);

    /** A reviewer's run: offered reads and searches, and nothing that writes, runs or hands on. */
    private static final HookContext READER = new HookContext("code_reviewer", false, java.util.Set.of(
            "file_read", "file_grep", "file_glob", "file_stat", "search", "todo_write"), null, "cnv_2",
            HookContext.SERVER);
    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-09-13T10:15:00+10:00[Australia/Melbourne]");

    /** The shipped guided profile's signal keys, as application.yml sets them. */
    private static final Map<String, Object> GUIDED = Map.of("repeat-failures", "2", "failure-streak", "4",
            "repeat-calls", "3", "read-only-steps", "10", "most-per-turn", "2");

    /** Only a failed call fires, at the first one: the generic trigger for the consult's own tests. */
    private static final Map<String, Object> ON_ANY_FAILURE = Map.of("failure-streak", "1",
            "repeat-failures", "0", "repeat-calls", "0", "read-only-steps", "0");

    private static final String NOTE = "`check` fails the same way after each edit to parse.txt, so the"
            + " edits are not reaching the failing line; read the line the failure names before editing again.";

    /** Runs submitted work only when told to, so a test decides when advice arrives. */
    static final class Held implements Executor {
        final List<Runnable> queued = new ArrayList<>();
        @Override public void execute(Runnable command) { queued.add(command); }
        void runAll() { List<Runnable> now = List.copyOf(queued); queued.clear(); now.forEach(Runnable::run); }
    }

    static final class Scripted implements StuckTrap.Advisor {
        final List<String> briefs = new ArrayList<>();
        final List<String> specifiers = new ArrayList<>();
        RuntimeException failure;
        String text = "{\"note\": \"" + NOTE + "\"}";
        Runnable during = () -> {};
        final List<java.util.function.BooleanSupplier> abandoned = new ArrayList<>();
        @Override public StuckTrap.Advice advise(String specifier, String brief,
                java.util.function.BooleanSupplier abandoned) {
            this.abandoned.add(abandoned);
            specifiers.add(specifier);
            briefs.add(brief);
            during.run();
            if (failure != null) throw failure;
            return new StuckTrap.Advice(text, "openai/gpt-oss-120b", 42);
        }
    }

    private static StuckTrap trap(Map<String, Object> set, StuckTrap.Advisor advisor, Executor executor) {
        return new StuckTrap(Parameters.read(StuckTrap.NAME, StuckTrapFactory.PARAMETERS, set), advisor, executor,
                () -> NOW, () -> 0L);
    }

    private static Map<String, Object> with(Map<String, Object> base, Object... more) {
        Map<String, Object> joined = new HashMap<>(base);
        for (int i = 0; i < more.length; i += 2) {
            joined.put((String) more[i], more[i + 1]);
        }
        return joined;
    }

    // --- steps a coder takes ----------------------------------------------------

    /** Builds a turn's steps, numbering them. */
    static final class Turn {
        int n;

        Step call(String tool, String arguments, String result) {
            n++;
            return new Step(n, "openai/gpt-oss-120b", List.of(new ToolCall("c" + n, tool, arguments)),
                    List.of(result), "thinking " + n);
        }

        Step read(String path) {
            return call("file_read", "{\"path\":\"" + path + "\"}", "1\tthe first line of " + path);
        }

        Step missing(String path) {
            return call("file_read", "{\"path\":\"" + path + "\"}", "there is no file at " + path);
        }

        Step edit(String path) {
            return call("file_edit", "{\"path\":\"" + path + "\",\"old\":\"a" + n + "\",\"new\":\"b\"}",
                    "Replaced the one occurrence of the text in " + path);
        }

        Step check(String result) {
            return call("run", "{\"command\":[\"check\"]}", result);
        }

        Step failing() {
            return missing("gone-" + (n + 1) + ".txt");
        }
    }

    private static String failed(String seconds) {
        return "exit 1 after " + seconds + "s\nFAILED parse_reads_the_header: expected 3, got 2 (line 41)";
    }

    private static final String PASSED = "exit 0 after 1.2s\n14 passed";

    /** Feeds each step, running any consult at once, and counts consults. */
    private static int consults(StuckTrap trap, Scripted advisor, Held executor, List<Step> steps) {
        return consults(CONTEXT, trap, advisor, executor, steps);
    }

    private static int consults(HookContext context, StuckTrap trap, Scripted advisor, Held executor,
            List<Step> steps) {
        trap.promptPre(context, "make the parser read the header");
        for (Step step : steps) {
            trap.stepPost(context, step);
            executor.runAll();
        }
        return advisor.briefs.size();
    }

    private static HookContext offered(String... tools) {
        return new HookContext("some_agent", false, java.util.Set.of(tools), null, "cnv_3", HookContext.SERVER);
    }

    /** Steps that only read, as many as asked. */
    private static List<Step> reading(Turn turn, int total) {
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            steps.add(i % 2 == 0 ? turn.read("f" + i + ".txt")
                    : turn.call("file_grep", "{\"pattern\":\"q" + i + "\"}", "f" + i + ".txt:1: q" + i));
        }
        return steps;
    }

    /** What a coder was measured sending: a heredoc as an argument, each time a different one. */
    private static final String SHELL_REFUSAL = "run has no shell: `<<PY` would reach the program as an"
            + " argument, not as its input. Give a program its input with `stdin`, and run one program"
            + " per call.";

    private static Step heredoc(Turn turn, int i) {
        return turn.call("run", "{\"command\":[\"tool\",\"- <<PY\\nprint(" + i + ")\\nPY\"]}", SHELL_REFUSAL);
    }

    // --- step count alone never fires -------------------------------------------

    @Test
    void ordinary_read_edit_run_steps_never_fire_however_many_there_are() {
        for (int total : new int[] {8, 12, 20}) {
            for (Map<String, Object> profile : List.of(Map.<String, Object>of(), GUIDED)) {
                Scripted advisor = new Scripted();
                Held executor = new Held();
                Turn turn = new Turn();
                List<Step> steps = new ArrayList<>();
                for (int i = 0; steps.size() < total; i++) {
                    steps.add(turn.read("src/part" + i + ".txt"));
                    steps.add(turn.edit("src/part" + i + ".txt"));
                    steps.add(turn.check(PASSED));
                }
                assertEquals(0, consults(trap(profile, advisor, executor), advisor, executor,
                        steps.subList(0, total)), total + " steps, " + profile);
            }
        }
    }

    @Test
    void the_measured_coder_turn_read_read_edit_fail_read_edit_pass_gets_no_hint() {
        Scripted advisor = new Scripted();
        Held executor = new Held();
        Turn turn = new Turn();

        int asked = consults(trap(GUIDED, advisor, executor), advisor, executor, List.of(
                turn.read("parse.txt"), turn.read("parse_test.txt"), turn.edit("parse.txt"),
                turn.check(failed("0.31")), turn.read("parse.txt"), turn.edit("parse.txt"),
                turn.check(PASSED)));

        assertEquals(0, asked);
    }

    // --- each signal at its threshold, and not before ---------------------------

    @Test
    void the_same_failure_coming_back_fires_at_repeat_failures_and_not_before() {
        Scripted advisor = new Scripted();
        Held executor = new Held();
        StuckTrap trap = trap(Map.of(), advisor, executor);
        Turn turn = new Turn();
        trap.promptPre(CONTEXT, "make the parser read the header");

        for (Step step : List.of(turn.check(failed("0.31")), turn.edit("parse.txt"),
                turn.check(failed("0.29")), turn.edit("parse.txt"))) {
            trap.stepPost(CONTEXT, step);
            executor.runAll();
        }
        assertEquals(0, advisor.briefs.size(), "twice is a retry");
        trap.stepPost(CONTEXT, turn.check(failed("0.35")));
        executor.runAll();

        assertEquals(1, advisor.briefs.size(), "the timings differ; the failure does not");
        String brief = advisor.briefs.get(0);
        assertTrue(brief.startsWith("Why the harness is asking: the same failure has come back 3 times: run"
                + " check → exit 1 after 0.35s FAILED parse_reads_the_header: expected 3, got 2 (line 41)."),
                brief);
        StepPost next = trap.stepPost(CONTEXT, turn.read("parse.txt"));
        assertEquals(List.of("[Runtime note from the harness, not from the person and not a tool's answer."
                + " Keep working on your task; this note needs no reply. The same failure has come back"
                + " 3 times: run check → exit 1 after 0.35s FAILED parse_reads_the_header: expected 3, got 2"
                + " (line 41). A second model that looked at the recent steps suggests: " + NOTE + "]"),
                next.notes());
    }

    @Test
    void a_call_repeated_back_to_back_is_left_to_the_identical_call_note() {
        Scripted advisor = new Scripted();
        Held executor = new Held();
        Turn turn = new Turn();
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            steps.add(turn.check(failed("0.31")));
        }

        assertEquals(0, consults(trap(Map.of(), advisor, executor), advisor, executor, steps),
                "JobRuntime.Repeats notes an unbroken run of one call; the trap does not double it");
    }

    @Test
    void a_streak_of_failed_calls_fires_at_failure_streak_and_not_before() {
        Scripted advisor = new Scripted();
        Held executor = new Held();
        StuckTrap trap = trap(Map.of(), advisor, executor);
        Turn turn = new Turn();
        trap.promptPre(CONTEXT, "q");

        for (int i = 0; i < 3; i++) {
            trap.stepPost(CONTEXT, turn.failing());
            executor.runAll();
        }
        assertEquals(0, advisor.briefs.size());
        trap.stepPost(CONTEXT, turn.failing());
        executor.runAll();

        assertEquals(1, advisor.briefs.size());
        assertTrue(advisor.briefs.get(0).startsWith(
                "Why the harness is asking: the last 4 tool calls all failed or were refused."),
                advisor.briefs.get(0));
    }

    @Test
    void a_success_breaks_the_streak() {
        Scripted advisor = new Scripted();
        Held executor = new Held();
        Turn turn = new Turn();

        assertEquals(0, consults(trap(Map.of(), advisor, executor), advisor, executor, List.of(
                turn.failing(), turn.failing(), turn.failing(), turn.read("a.txt"), turn.failing(),
                turn.failing(), turn.failing())));
    }

    @Test
    void the_runtimes_told_outcome_is_what_counts_as_failed() {
        Scripted advisor = new Scripted();
        Held executor = new Held();
        StuckTrap trap = trap(ON_ANY_FAILURE, advisor, executor);
        trap.promptPre(CONTEXT, "q");

        // A hook's denial reads as nothing in particular; the runtime says what it was.
        trap.stepPost(CONTEXT, new Step(1, null, List.of(new ToolCall("c1", "probe", "{}")),
                List.of("the call was refused by a hook: not today"), null, List.of("ok")));
        executor.runAll();
        assertEquals(0, advisor.briefs.size(), "told ok, whatever the text");
        trap.stepPost(CONTEXT, new Step(2, null, List.of(new ToolCall("c2", "probe", "{\"a\":1}")),
                List.of("fine"), null, List.of("denied")));
        executor.runAll();

        assertEquals(1, advisor.briefs.size(), "told denied, whatever the text");
    }

    @Test
    void the_same_call_with_the_same_answer_fires_at_repeat_calls_and_not_before() {
        Scripted advisor = new Scripted();
        Held executor = new Held();
        StuckTrap trap = trap(Map.of(), advisor, executor);
        Turn turn = new Turn();
        trap.promptPre(CONTEXT, "q");

        for (Step step : List.of(turn.call("file_read", "{\"path\":\"a.txt\",\"offset\":0}", "1\tsame"),
                turn.call("file_grep", "{\"pattern\":\"x\"}", "a.txt:1: x"),
                turn.call("file_read", "{\"offset\":0,\"path\":\"a.txt\"}", "1\tsame"),
                turn.call("file_glob", "{\"pattern\":\"*.txt\"}", "a.txt"))) {
            trap.stepPost(CONTEXT, step);
            executor.runAll();
        }
        assertEquals(0, advisor.briefs.size());
        trap.stepPost(CONTEXT, turn.call("file_read", "{\"path\":\"a.txt\", \"offset\": 0}", "1\tsame"));
        executor.runAll();

        assertEquals(1, advisor.briefs.size(), "key order and spacing are not a different call");
        assertTrue(advisor.briefs.get(0).startsWith("Why the harness is asking: the same call has come back"
                + " 3 times with the same answer: file_read a.txt."), advisor.briefs.get(0));
    }

    @Test
    void a_call_repeated_after_a_change_is_not_a_repeat() {
        Scripted advisor = new Scripted();
        Held executor = new Held();
        Turn turn = new Turn();

        assertEquals(0, consults(trap(Map.of(), advisor, executor), advisor, executor, List.of(
                turn.read("a.txt"), turn.edit("b.txt"), turn.read("a.txt"), turn.edit("b.txt"),
                turn.read("a.txt"), turn.edit("b.txt"), turn.read("a.txt"))));
    }

    @Test
    void a_long_read_only_stretch_fires_at_read_only_steps_and_not_before() {
        Scripted advisor = new Scripted();
        Held executor = new Held();
        StuckTrap trap = trap(Map.of(), advisor, executor);
        Turn turn = new Turn();
        trap.promptPre(CONTEXT, "q");

        for (int i = 0; i < 11; i++) {
            trap.stepPost(CONTEXT, i % 2 == 0 ? turn.read("f" + i + ".txt")
                    : turn.call("search", "{\"query\":\"q" + i + "\"}", "results " + i));
            executor.runAll();
        }
        assertEquals(0, advisor.briefs.size());
        trap.stepPost(CONTEXT, turn.read("f11.txt"));
        executor.runAll();

        assertEquals(1, advisor.briefs.size());
        assertTrue(advisor.briefs.get(0).startsWith("Why the harness is asking: the last 12 steps only"
                + " read or searched; nothing was written, run or handed on."), advisor.briefs.get(0));
    }

    /** Measured 2026-09-30: a code_reviewer, whose job is reading, was told after ten reads that
     *  nothing had been written or run, and to run something it had no tool to run. */
    @Test
    void a_read_only_stretch_never_fires_for_a_run_offered_only_reads() {
        for (Map<String, Object> profile : List.of(Map.<String, Object>of(), GUIDED)) {
            Scripted advisor = new Scripted();
            Held executor = new Held();

            assertEquals(0, consults(READER, trap(profile, advisor, executor), advisor, executor,
                    reading(new Turn(), 30)), profile.toString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"file_edit", "file_delete", "file_move", "run", "agent_run", "orchestration_answer",
            "orchestration_cancel", "orchestration_ask", "orchestration_finish", "orchestration_check"})
    void a_read_only_stretch_fires_for_a_run_offered_a_tool_that_acts(String acting) {
        Scripted advisor = new Scripted();
        Held executor = new Held();
        StuckTrap trap = trap(GUIDED, advisor, executor);
        HookContext context = offered("file_read", "file_grep", acting);
        Turn turn = new Turn();

        assertEquals(0, consults(context, trap, advisor, executor, reading(turn, 9)));
        trap.stepPost(context, turn.read("f9.txt"));
        executor.runAll();

        assertEquals(1, advisor.briefs.size(), acting);
        assertTrue(advisor.briefs.get(0).startsWith("Why the harness is asking: the last 10 steps only"
                + " read or searched"), advisor.briefs.get(0));
    }

    // --- a refusal repeated word for word -----------------------------------------

    /** Measured 2026-09-30: six different heredocs sent to run, each refused in the same words; no
     *  two calls were alike, so the same failure never counted as coming back. */
    @Test
    void the_same_refusal_to_different_arguments_fires_at_repeat_failures_and_not_before() {
        Scripted advisor = new Scripted();
        Held executor = new Held();
        StuckTrap trap = trap(Map.of("failure-streak", "0", "most-per-turn", "2"), advisor, executor);
        Turn turn = new Turn();
        trap.promptPre(CONTEXT, "q");

        List<Integer> consultedAt = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            trap.stepPost(CONTEXT, heredoc(turn, i));
            executor.runAll();
            trap.stepPost(CONTEXT, turn.read("f" + i + ".txt"));
            executor.runAll();
            if (advisor.briefs.size() > consultedAt.size()) {
                consultedAt.add(i);
            }
        }

        assertEquals(List.of(3, 6), consultedAt, "at the threshold, and again after as many again");
        assertTrue(advisor.briefs.get(0).startsWith("Why the harness is asking: the same refusal has come back"
                + " 3 times: run tool - <<PY print(3) PY → " + SHELL_REFUSAL), advisor.briefs.get(0));
    }

    @Test
    void the_same_refusal_told_by_the_runtime_counts_as_it_does_read_off_the_text() {
        Scripted advisor = new Scripted();
        Held executor = new Held();
        StuckTrap trap = trap(Map.of("failure-streak", "0"), advisor, executor);
        trap.promptPre(CONTEXT, "q");

        for (int i = 1; i <= 3; i++) {
            trap.stepPost(CONTEXT, new Step(i, null, List.of(new ToolCall("c" + i, "probe", "{\"a\":\"" + (char) ('a' + i)
                    + "\"}")), List.of("the call was refused by a hook: not today"), null, List.of("denied")));
            executor.runAll();
        }

        assertEquals(1, advisor.briefs.size(), "a hook's denial is a refusal too: nothing ran");
    }

    @Test
    void ordinary_failures_with_the_same_text_to_different_arguments_are_not_a_repeat() {
        Scripted advisor = new Scripted();
        Held executor = new Held();
        Turn turn = new Turn();
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            steps.add(turn.call("run", "{\"command\":[\"check\",\"case" + (char) ('a' + i) + "\"]}",
                    "exit 1 after 0.2s\nerror: no such case"));
            steps.add(turn.read("f" + i + ".txt"));
        }

        assertEquals(0, consults(trap(Map.of("failure-streak", "0"), advisor, executor), advisor, executor,
                steps), "a program that ran and failed was asked something different each time");
    }

    @Test
    void a_write_run_or_todo_move_breaks_a_read_only_stretch() {
        Scripted advisor = new Scripted();
        Held executor = new Held();
        Turn turn = new Turn();
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            steps.add(i % 10 == 9 ? turn.call("todo_write", "{\"ops\":[{\"done\":" + i + "}]}",
                    "Done. The list is now: " + i + " done")
                    : turn.read("f" + i + ".txt"));
        }

        assertEquals(0, consults(trap(Map.of(), advisor, executor), advisor, executor, steps));
    }

    @Test
    void a_signal_set_to_zero_never_fires() {
        Scripted advisor = new Scripted();
        Held executor = new Held();
        Turn turn = new Turn();
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            steps.add(turn.failing());
        }

        assertEquals(0, consults(trap(Map.of("failure-streak", "0", "repeat-failures", "0", "repeat-calls", "0",
                "read-only-steps", "0"), advisor, executor), advisor, executor, steps));
    }

    @Test
    void most_per_turn_caps_the_consults() {
        Scripted advisor = new Scripted();
        Held executor = new Held();
        Turn turn = new Turn();
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            steps.add(turn.failing());
        }

        assertEquals(2, consults(trap(with(ON_ANY_FAILURE, "most-per-turn", "2"), advisor, executor),
                advisor, executor, steps));
    }

    // --- parameters -------------------------------------------------------------

    @Test
    void the_signal_keys_parse_with_their_defaults() {
        Parameters defaults = Parameters.read(StuckTrap.NAME, StuckTrapFactory.PARAMETERS, Map.of());
        assertEquals(3, defaults.integer("repeat-failures"));
        assertEquals(4, defaults.integer("failure-streak"));
        assertEquals(3, defaults.integer("repeat-calls"));
        assertEquals(12, defaults.integer("read-only-steps"));
        assertEquals(1, defaults.integer("most-per-turn"));
        assertEquals(600, defaults.integer("advice-most"));

        Parameters guided = Parameters.read(StuckTrap.NAME, StuckTrapFactory.PARAMETERS, GUIDED);
        assertEquals(2, guided.integer("repeat-failures"));
        assertEquals(10, guided.integer("read-only-steps"));
    }

    @Test
    void the_shipped_profiles_build_the_trap_with_their_signal_keys() throws Exception {
        org.springframework.core.env.StandardEnvironment environment = new org.springframework.core.env.StandardEnvironment();
        new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("shipped", new org.springframework.core.io.ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        HarnessProperties shipped = new org.springframework.boot.context.properties.bind.Binder(
                org.springframework.boot.context.properties.source.ConfigurationPropertySources.get(environment),
                new org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver(environment))
                .bind("plowshare.harness", HarnessProperties.class).get();
        List<Parameters> built = new ArrayList<>();
        try (StuckTrapFactory real = new StuckTrapFactory(null, () -> null)) {
            HarnessHookFactory capturing = new HarnessHookFactory() {
                @Override public String name() { return real.name(); }
                @Override public List<Parameter> parameters() { return real.parameters(); }
                @Override public io.aeyer.plowshare.server.hooks.HarnessHook create(Parameters parameters) {
                    built.add(parameters);
                    return real.create(parameters);
                }
            };
            HarnessRun run = new Harness(shipped, Map.of("m-guided", "guided", "m-standard", "standard",
                    "m-minimal", "minimal"), List.of(capturing)).begin();
            run.forModel("m-guided");
            run.forModel("m-standard");
            run.forModel("m-minimal");
        }

        assertEquals(2, built.size(), "minimal builds nothing");
        Parameters guided = built.get(0);
        assertEquals(List.of(2, 4, 3, 10, 2), List.of(guided.integer("repeat-failures"),
                guided.integer("failure-streak"), guided.integer("repeat-calls"),
                guided.integer("read-only-steps"), guided.integer("most-per-turn")));
        Parameters standard = built.get(1);
        assertEquals(List.of(3, 4, 3, 12, 1), List.of(standard.integer("repeat-failures"),
                standard.integer("failure-streak"), standard.integer("repeat-calls"),
                standard.integer("read-only-steps"), standard.integer("most-per-turn")));
    }

    @Test
    void a_step_count_key_is_refused_naming_the_keys_there_are() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> Parameters.read(StuckTrap.NAME, StuckTrapFactory.PARAMETERS, Map.of("after-steps", "4")));

        assertTrue(refused.getMessage().contains("'after-steps'"), refused.getMessage());
        assertTrue(refused.getMessage().contains("repeat-failures"), refused.getMessage());
    }

    // --- what the advisor says, and what reaches the model -----------------------

    @Test
    void a_consult_runs_off_the_run_and_its_note_is_the_next_steps() {
        Scripted advisor = new Scripted();
        Held executor = new Held();
        StuckTrap trap = trap(with(ON_ANY_FAILURE, "advisor", "reasoning"), advisor, executor);
        Turn turn = new Turn();
        trap.promptPre(CONTEXT, "why did the Fed hike today?");

        StepPost atSignal = trap.stepPost(CONTEXT, turn.failing());
        assertEquals(List.of(), atSignal.notes(), "the run does not wait");
        executor.runAll();
        StepPost next = trap.stepPost(CONTEXT, turn.read("a.txt"));

        assertEquals(List.of("reasoning"), advisor.specifiers);
        assertTrue(advisor.briefs.get(0).contains("why did the Fed hike today?"));
        assertTrue(advisor.briefs.get(0).contains("Sunday 13 September 2026"));
        assertTrue(advisor.briefs.get(0).contains("1. file_read"));
        assertEquals(1, next.notes().size());
        assertTrue(next.notes().get(0).contains("The last tool call failed or was refused."),
                next.notes().get(0));
        assertTrue(next.notes().get(0).endsWith(NOTE + "]"), next.notes().get(0));
        HookRecord noted = next.records().get(0);
        assertEquals(StuckTrap.NAME, noted.hook());
        assertEquals(HookRecord.NOTE, noted.decision());
        assertEquals(NOTE, noted.added());
        assertTrue(noted.original().contains("why did the Fed hike today?"), "the brief is recorded");
        assertTrue(noted.reason().contains("openai/gpt-oss-120b"), noted.reason());
        assertTrue(noted.reason().contains("failure-streak"), noted.reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"note\": null}",
            "{\"note\":null}",
            "```json\n{\"note\": null}\n```",
            "{}",
            "{\"note\": \"\"}",
            "{\"note\": \"   \"}",
            "",
            "  \n ",
            "The model keeps running the same check.",
            "{\"note\": 3}",
            "{\"note\": \"Let's request file_read on parse.txt. We cannot request new tools? The coder should look.\"}",
            "{\"note\": \"We need to tell it to read the failing line.\"}",
            "{\"note\": \"I think the brief shows a loop; per my instructions I should say so.\"}",
            "{\"note\": \"Step back and make a concrete change instead of reading.\"}",
            "{\"note\": \"Stop reading and make a concrete change.\"}",
    })
    void an_answer_that_is_not_advice_is_swallowed_and_consulting_goes_on(String answer) {
        Scripted advisor = new Scripted();
        advisor.text = answer;
        Held executor = new Held();
        StuckTrap trap = trap(with(ON_ANY_FAILURE, "most-per-turn", "3"), advisor, executor);
        Turn turn = new Turn();
        trap.promptPre(CONTEXT, "q");
        trap.stepPost(CONTEXT, turn.failing());
        executor.runAll();

        StepPost after = trap.stepPost(CONTEXT, turn.failing());
        executor.runAll();

        assertEquals(List.of(), after.notes(), "nothing is sent down to the model");
        assertEquals(1, after.records().size());
        HookRecord swallowed = after.records().get(0);
        assertEquals(HookRecord.SWALLOWED, swallowed.decision());
        assertEquals(StuckTrap.NAME, swallowed.hook());
        assertTrue(swallowed.reason().contains("failure-streak"), swallowed.reason());
        assertEquals(answer, swallowed.added(), "the whole answer is kept for a person to audit");
        assertEquals(2, advisor.briefs.size(), "a swallowed answer is not a failure: the next signal consults");
    }

    // --- the advisor knows the tools ---------------------------------------------

    @Test
    void the_brief_names_the_tools_the_run_was_offered() {
        Scripted advisor = new Scripted();
        Held executor = new Held();

        consults(READER, trap(ON_ANY_FAILURE, advisor, executor), advisor, executor, List.of(new Turn().failing()));

        assertTrue(advisor.briefs.get(0).contains("\n\nThe tools it may call, and no others: file_glob, file_grep,"
                + " file_read, file_stat, search, todo_write\n\n"), advisor.briefs.get(0));
    }

    /** Measured 2026-09-30: told to run a check, a reviewer with no run tool called one it made up. */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"note\": \"import_game_check.py keeps coming back the same; try it with run_python instead.\"}",
            "{\"note\": \"file_read of import_game_check.py keeps answering the same; call file_edit on it.\"}",
            "{\"note\": \"The same read keeps coming back; call `run` via shell_exec on import_game_check.py.\"}",
    })
    void a_note_naming_a_tool_the_run_was_not_offered_is_swallowed(String answer) {
        Scripted advisor = new Scripted();
        advisor.text = answer;
        Held executor = new Held();
        StuckTrap trap = trap(ON_ANY_FAILURE, advisor, executor);
        Turn turn = new Turn();
        trap.promptPre(READER, "review the import");
        trap.stepPost(READER, turn.missing("import_game_check.py"));
        executor.runAll();

        StepPost next = trap.stepPost(READER, turn.read("a.txt"));

        assertEquals(List.of(), next.notes());
        HookRecord swallowed = next.records().get(0);
        assertEquals(HookRecord.SWALLOWED, swallowed.decision());
        assertTrue(swallowed.reason().contains("not a tool this run was offered"), swallowed.reason());
        assertEquals(answer, swallowed.added());
    }

    @Test
    void a_tool_the_model_called_without_being_offered_it_is_still_not_offered() {
        Scripted advisor = new Scripted();
        advisor.text = "{\"note\": \"Call run_python on import_game_check.py.\"}";
        Held executor = new Held();
        StuckTrap trap = trap(ON_ANY_FAILURE, advisor, executor);
        trap.promptPre(READER, "review the import");
        // Told refused, as the runtime tells a name no tool answers to.
        trap.stepPost(READER, new Step(1, null, List.of(new ToolCall("c1", "run_python",
                "{\"path\":\"import_game_check.py\"}")), List.of("there is no tool called 'run_python'; the"
                + " tools you may call are [file_read]."), "I will use run_python", List.of("refused")));
        executor.runAll();

        StepPost next = trap.stepPost(READER, new Turn().read("a.txt"));

        assertEquals(List.of(), next.notes());
        assertTrue(next.records().get(0).reason().contains("run_python"), next.records().get(0).reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Tools it was offered.
            "file_read of import_game_check.py keeps answering the same; file_grep it for the import instead.",
            // A file's name, and a name the steps themselves show: evidence, not a tool.
            "parse_reads_the_header fails the same way after each edit to parse_utils.py; read line 41 of it.",
    })
    void a_note_naming_only_offered_tools_files_and_what_the_steps_show_is_sent(String note) {
        Scripted advisor = new Scripted();
        advisor.text = "{\"note\": \"" + note + "\"}";
        Held executor = new Held();
        StuckTrap trap = trap(ON_ANY_FAILURE, advisor, executor);
        Turn turn = new Turn();
        trap.promptPre(READER, "review the import");
        trap.stepPost(READER, turn.call("file_read", "{\"path\":\"parse_test.txt\"}",
                "there is no file at parse_test.txt; parse_reads_the_header lives elsewhere"));
        executor.runAll();

        StepPost next = trap.stepPost(READER, turn.read("a.txt"));

        assertEquals(1, next.notes().size(), next.records().toString());
        assertTrue(next.notes().get(0).endsWith(note + "]"), next.notes().get(0));
    }

    @Test
    void an_answer_longer_than_advice_most_is_swallowed_not_cut() {
        Scripted advisor = new Scripted();
        advisor.text = "{\"note\": \"" + "a".repeat(601) + "\"}";
        Held executor = new Held();
        StuckTrap trap = trap(ON_ANY_FAILURE, advisor, executor);
        Turn turn = new Turn();
        trap.promptPre(CONTEXT, "q");
        trap.stepPost(CONTEXT, turn.failing());
        executor.runAll();

        StepPost next = trap.stepPost(CONTEXT, turn.read("a.txt"));

        assertEquals(List.of(), next.notes());
        assertEquals(HookRecord.SWALLOWED, next.records().get(0).decision());
        assertTrue(next.records().get(0).reason().contains("600"), next.records().get(0).reason());
    }

    @Test
    void a_note_of_exactly_advice_most_is_sent() {
        Scripted advisor = new Scripted();
        advisor.text = "{\"note\": \"" + "a".repeat(600) + "\"}";
        Held executor = new Held();
        StuckTrap trap = trap(ON_ANY_FAILURE, advisor, executor);
        Turn turn = new Turn();
        trap.promptPre(CONTEXT, "q");
        trap.stepPost(CONTEXT, turn.failing());
        executor.runAll();

        StepPost next = trap.stepPost(CONTEXT, turn.read("a.txt"));

        assertEquals(1, next.notes().size());
        assertTrue(next.notes().get(0).endsWith("a".repeat(600) + "]"));
    }

    @Test
    void the_note_is_whatever_the_note_key_holds_even_fenced_or_prefaced() {
        Scripted advisor = new Scripted();
        advisor.text = "Here it is:\n```json\n{\"note\": \"" + NOTE + "\"}\n```";
        Held executor = new Held();
        StuckTrap trap = trap(ON_ANY_FAILURE, advisor, executor);
        Turn turn = new Turn();
        trap.promptPre(CONTEXT, "q");
        trap.stepPost(CONTEXT, turn.failing());
        executor.runAll();

        StepPost next = trap.stepPost(CONTEXT, turn.read("a.txt"));

        assertTrue(next.notes().get(0).endsWith(NOTE + "]"), next.notes().get(0));
    }

    // --- the consult's own life, unchanged by the trigger -----------------------

    @Test
    void a_consult_can_see_it_was_abandoned_once_the_turn_ends() {
        Scripted advisor = new Scripted();
        Held executor = new Held();
        StuckTrap trap = trap(ON_ANY_FAILURE, advisor, executor);
        trap.promptPre(CONTEXT, "q");
        trap.stepPost(CONTEXT, new Turn().failing());
        List<Boolean> whileRunning = new ArrayList<>();
        advisor.during = () -> whileRunning.add(advisor.abandoned.get(0).getAsBoolean());
        executor.runAll();

        assertEquals(List.of(false), whileRunning, "a consult nobody left is not abandoned");
        trap.finish();
        assertTrue(advisor.abandoned.get(0).getAsBoolean(), "after finish, a stream would stop");
    }

    @Test
    void a_consult_cancelled_before_its_advisor_returns_reads_abandoned_and_is_recorded_unused() {
        Held executor = new Held();
        java.util.concurrent.atomic.AtomicReference<StuckTrap> held = new java.util.concurrent.atomic.AtomicReference<>();
        List<HookRecord> finished = new ArrayList<>();
        StuckTrap.Advisor streaming = (specifier, brief, abandoned) -> {
            finished.addAll(held.get().finish());
            if (abandoned.getAsBoolean()) {
                throw new io.aeyer.plowshare.server.llm.dispatch.CallerAbandonedException("advisor-pool");
            }
            return new StuckTrap.Advice("too late", "m", 1);
        };
        StuckTrap trap = trap(ON_ANY_FAILURE, streaming, executor);
        held.set(trap);
        trap.promptPre(CONTEXT, "q");
        trap.stepPost(CONTEXT, new Turn().failing());

        executor.runAll();

        assertEquals(1, finished.size());
        assertEquals(HookRecord.UNUSED, finished.get(0).decision(), "abandoned, not a new failure");
    }

    @Test
    void advice_that_arrives_after_the_turn_ends_is_recorded_unused() {
        Scripted advisor = new Scripted();
        Held executor = new Held();
        StuckTrap trap = trap(ON_ANY_FAILURE, advisor, executor);
        trap.promptPre(CONTEXT, "q");
        trap.stepPost(CONTEXT, new Turn().failing());
        executor.runAll();

        List<HookRecord> finished = trap.finish();

        assertEquals(1, finished.size());
        assertEquals(HookRecord.UNUSED, finished.get(0).decision());
        assertTrue(finished.get(0).added().contains("parse.txt"));
    }

    @Test
    void a_consult_still_running_when_the_turn_ends_is_abandoned_and_recorded_unused() {
        Scripted advisor = new Scripted();
        Held executor = new Held();
        StuckTrap trap = trap(ON_ANY_FAILURE, advisor, executor);
        trap.promptPre(CONTEXT, "q");
        trap.stepPost(CONTEXT, new Turn().failing());

        List<HookRecord> finished = trap.finish();
        executor.runAll();

        assertEquals(HookRecord.UNUSED, finished.get(0).decision());
        assertEquals(0, advisor.briefs.size(), "an abandoned consult never reaches the advisor");
    }

    @Test
    void a_failed_consult_adds_no_note_records_the_failure_and_stops_consulting() {
        Scripted advisor = new Scripted();
        advisor.failure = new IllegalStateException("pool down");
        Held executor = new Held();
        StuckTrap trap = trap(with(ON_ANY_FAILURE, "most-per-turn", "3"), advisor, executor);
        Turn turn = new Turn();
        trap.promptPre(CONTEXT, "q");
        trap.stepPost(CONTEXT, turn.failing());
        executor.runAll();

        StepPost after = trap.stepPost(CONTEXT, turn.failing());
        executor.runAll();
        trap.stepPost(CONTEXT, turn.failing());
        executor.runAll();

        assertEquals(List.of(), after.notes());
        assertEquals(HookRecord.FAILED, after.records().get(0).decision());
        assertTrue(after.records().get(0).reason().contains("pool down"));
        assertEquals(1, advisor.briefs.size());
    }

    @Test
    void a_consult_that_failed_before_the_turn_ended_is_recorded_failed_not_unused() {
        Scripted advisor = new Scripted();
        advisor.failure = new IllegalStateException("pool down");
        Held executor = new Held();
        StuckTrap trap = trap(ON_ANY_FAILURE, advisor, executor);
        trap.promptPre(CONTEXT, "q");
        trap.stepPost(CONTEXT, new Turn().failing());
        executor.runAll();

        List<HookRecord> finished = trap.finish();

        assertEquals(1, finished.size());
        assertEquals(HookRecord.FAILED, finished.get(0).decision());
        assertTrue(finished.get(0).reason().contains("pool down"), finished.get(0).reason());
    }

    @Test
    void a_consult_that_times_out_adds_no_note_records_the_timeout_and_stops_consulting() {
        Scripted advisor = new Scripted();
        Held executor = new Held();
        StuckTrap trap = trap(with(ON_ANY_FAILURE, "most-per-turn", "3", "timeout", "1ms"), advisor, executor);
        Turn turn = new Turn();
        trap.promptPre(CONTEXT, "q");
        trap.stepPost(CONTEXT, turn.failing());

        // The held consult never runs, so only orTimeout's own timer can settle it. Poll for
        // that, bounded, rather than sleeping a guessed interval.
        long giveUpAt = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!trap.consultSettled() && System.nanoTime() < giveUpAt) {
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
        }
        assertTrue(trap.consultSettled(), "the timeout fired");
        StepPost after = trap.stepPost(CONTEXT, turn.failing());
        trap.stepPost(CONTEXT, turn.failing());

        assertEquals(List.of(), after.notes());
        assertEquals(HookRecord.FAILED, after.records().get(0).decision());
        assertEquals("no advice within 1 ms", after.records().get(0).reason());
        assertEquals(1, executor.queued.size(), "no further consult was submitted");
        assertEquals(List.of(), trap.finish());
    }

    @Test
    void took_ms_is_the_advisors_own_time_not_the_wait_for_the_next_step() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000);
        Scripted advisor = new Scripted();
        advisor.during = () -> clock.addAndGet(7);
        Held executor = new Held();
        StuckTrap trap = new StuckTrap(Parameters.read(StuckTrap.NAME, StuckTrapFactory.PARAMETERS,
                ON_ANY_FAILURE), advisor, executor, () -> NOW, clock::get);
        Turn turn = new Turn();
        trap.promptPre(CONTEXT, "q");
        trap.stepPost(CONTEXT, turn.failing());
        executor.runAll();
        clock.addAndGet(5_000); // the run's next model call

        StepPost next = trap.stepPost(CONTEXT, turn.read("a.txt"));

        assertEquals(7, next.records().get(0).tookMs());
    }

    @Test
    void an_unfinished_consult_abandoned_at_finish_took_the_time_until_finish() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000);
        Held executor = new Held();
        StuckTrap trap = new StuckTrap(Parameters.read(StuckTrap.NAME, StuckTrapFactory.PARAMETERS,
                ON_ANY_FAILURE), new Scripted(), executor, () -> NOW, clock::get);
        trap.promptPre(CONTEXT, "q");
        trap.stepPost(CONTEXT, new Turn().failing());
        clock.addAndGet(300);

        assertEquals(300, trap.finish().get(0).tookMs());
    }

    @Test
    void a_completion_with_no_usage_or_stamp_is_advice_with_no_tokens() {
        StuckTrap.Advice advice = StuckTrapFactory.advice(new io.aeyer.plowshare.server.llm.dispatch.Completion(
                "{\"note\": null}", "stop", null, List.of()));

        assertEquals(new StuckTrap.Advice("{\"note\": null}", null, null), advice);
        assertFalse(advice.text().isBlank());
    }
}
