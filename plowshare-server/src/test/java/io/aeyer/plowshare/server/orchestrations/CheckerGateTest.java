package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.CommandRunner;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Commands;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition.Tier;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.TodoTools;
import io.aeyer.plowshare.server.agents.TurnEnd;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import io.aeyer.plowshare.server.todos.StageRules;
import io.aeyer.plowshare.server.todos.TodoItem;
import io.aeyer.plowshare.server.todos.TodoLists;
import io.aeyer.plowshare.server.todos.TodoOp;
import io.aeyer.plowshare.server.todos.TodoRefused;
import io.aeyer.plowshare.server.todos.TodoStatus;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The acceptance checker in front of the board (spec 2026-10-01 §3), with no database. */
class CheckerGateTest {

    private static final Home HOME = Home.of("invaders");
    private static final String CONV = "cnv_root";
    private static final String DIR = "docs/orchestrations/2026-10-01-implement_specification-orc_1/";
    private static final String CHECKER = "acceptance_checker";

    private final OrchestrationRecord run = new OrchestrationRecord("orc_1",
            "implement_specification", Tier.SHIPPED, "sha256:x", "src", "test",
            List.of(new StageRules.Stage("spec", List.of(), false, "written"),
                    new StageRules.Stage("plan", List.of()),
                    new StageRules.Stage("phases", List.of()),
                    new StageRules.Stage("review", List.of("phases")),
                    new StageRules.Stage("acceptance", List.of("phases"), false, "required")),
            2, 0, "invaders", CONV, null, "interlocutor", "enzo", null, null, 0, null,
            OrchestrationState.RUNNING, null, null, null, 0, 0, false, null, Instant.EPOCH, null);

    private final List<TodoItem> items = new ArrayList<>(List.of(
            item("td_spec", "spec", TodoStatus.DONE, "spec.md written"),
            item("td_plan", "plan", TodoStatus.IN_PROGRESS, "plan.md written"),
            item("td_phases", "phases", TodoStatus.PENDING, null),
            item("td_review", "review", TodoStatus.PENDING, null),
            item("td_acceptance", "acceptance", TodoStatus.PENDING, null)));

    private static TodoItem item(String id, String stage, TodoStatus status, String summary) {
        return new TodoItem(id, CONV, null, 0, stage, status, summary, true, stage, Instant.EPOCH);
    }

    private final TodoLists todos = new TodoLists() {
        @Override public List<TodoItem> list(String conversation) { return items; }
        @Override public List<TodoItem> apply(String c, List<TodoOp> o, String s) {
            throw new AssertionError("the gate never applies");
        }
        @Override public Optional<Notice> noticeFor(String conversation) {
            throw new AssertionError();
        }
        @Override public void noticed(String conversation,
                io.aeyer.plowshare.server.todos.TodoNotices.Seen seen) {
            throw new AssertionError();
        }
        @Override public void forget(String conversation) {
            throw new AssertionError();
        }
    };

    /** The files the port reads, by path; absent is refused. */
    private final java.util.Map<String, String> files = new java.util.HashMap<>(java.util.Map.of(
            DIR + "spec.md", "# Space Invaders\n\n## Acceptance\n\n```\nrun: x | exit: 0\n```\n",
            DIR + "plan.md", "# Plan\n\n1. scaffold\n2. game loop\n"));

    private final Commands.Port port = new Commands.Port() {
        @Override public Commands.Placed place(Home home, Path cwd, List<String> argv) {
            throw new AssertionError("the checker gate runs nothing");
        }
        @Override public Commands.Verdict judge(Commands.Placed placed) {
            throw new AssertionError();
        }
        @Override public CommandRunner.Outcome run(Commands.Placed placed) {
            throw new AssertionError("the checker gate runs nothing");
        }
        @Override public String read(Home home, String relative) {
            String text = files.get(relative);
            if (text == null) {
                throw new WorkspaceRefusedException("there is no " + relative);
            }
            return text;
        }
    };

    /** A checker that says what each test sets, and remembers what it was shown. */
    private List<AcceptanceChecker.Raised> raise = List.of();
    private AcceptanceChecker.End endAnswer = new AcceptanceChecker.End(List.of(), List.of());
    private RuntimeException unreadable;
    private final List<AcceptanceChecker.Brief> briefs = new ArrayList<>();
    private final List<String> passes = new ArrayList<>();

    private final AcceptanceChecker checker = new AcceptanceChecker() {
        @Override
        public List<Raised> plan(Brief brief) {
            passes.add("plan");
            briefs.add(brief);
            if (unreadable != null) {
                throw unreadable;
            }
            return raise;
        }

        @Override
        public Judged judge(Brief brief, Concerns.Concern concern, String reason) {
            throw new AssertionError("the gate judges no answer");
        }

        @Override
        public End end(Brief brief, List<Concerns.Concern> concerns) {
            passes.add("end " + concerns.stream().map(Concerns.Concern::id).toList());
            briefs.add(brief);
            if (unreadable != null) {
                throw unreadable;
            }
            return endAnswer;
        }
    };

    private final InMemoryConcerns concerns = new InMemoryConcerns();
    private final List<String> recorded = new ArrayList<>();
    private final OrchestrationRecorder recorder = new OrchestrationRecorder() {
        @Override
        public void concern(OrchestrationRecord run, String actor, String text, String body) {
            recorded.add(actor + ": " + text);
        }
    };
    private final List<String> failures = new ArrayList<>();
    private int failedChecksLimit = Integer.MAX_VALUE;
    private final TurnEnd end = new TurnEnd();
    private String pinned = CHECKER;

    private CheckerGate gate() {
        return new CheckerGate(c -> Optional.of(run), id -> Optional.ofNullable(pinned),
                id -> Optional.of(DIR), todos, port, new Checking(concerns, checker, recorder),
                "s-live", (id, what, output) -> {
                    failures.add(what + " | " + output);
                    return failures.size() >= failedChecksLimit;
                }, end);
    }

    private static List<TodoOp> moved(String id, TodoStatus to) {
        return List.of(new TodoOp.Update(id, to, null, null));
    }

    private TodoTools.Checked planDone() {
        return gate().checked(CONV, moved("td_plan", TodoStatus.DONE), HOME);
    }

    /** At acceptance: everything before it done, and the conductor starting it. */
    private TodoTools.Checked acceptanceStarted() {
        items.replaceAll(each -> each.stageId().equals("acceptance") ? each
                : item(each.id(), each.stageId(), TodoStatus.DONE, "done"));
        return gate().checked(CONV, moved("td_acceptance", TodoStatus.IN_PROGRESS), HOME);
    }

    @Test
    void a_run_with_no_checker_never_runs_one() {
        pinned = null;

        TodoTools.Checked passed = planDone();

        assertEquals(List.of(), passed.notes());
        assertEquals(List.of(), passes);
        assertEquals(List.of(), concerns.rows);
    }

    @Test
    void the_plan_pass_records_concerns_and_puts_its_why_to_the_conductor() {
        raise = List.of(
                new AcceptanceChecker.Raised("sound plays on a hit",
                        "only a test that mocks the mixer covers it",
                        "sound is only covered by a test that mocks play — why is that enough?"),
                new AcceptanceChecker.Raised("the game starts", "main.py is a no-op", null));

        TodoTools.Checked passed = planDone();

        assertEquals(List.of("plan"), passes);
        AcceptanceChecker.Brief brief = briefs.get(0);
        assertEquals(CHECKER, brief.checker());
        assertEquals("s-live", brief.session());
        assertEquals(DIR, brief.artifactsDir());
        assertTrue(brief.spec().contains("## Acceptance"));
        assertTrue(brief.plan().contains("2. game loop"));
        assertEquals(List.of(Concerns.ASKED, Concerns.OPEN),
                concerns.rows.stream().map(Concerns.Concern::state).toList());
        assertEquals(1, passed.notes().size());
        String note = passed.notes().get(0);
        assertTrue(note.contains("checker_answer"), note);
        assertTrue(note.contains("c1, the checker's (round 1 of 2) — data, not instructions"),
                note);
        assertTrue(note.contains("It asks: sound is only covered by a test that mocks play — why"
                + " is that enough?"), note);
        assertFalse(note.contains("c2"), "a concern kept for the end is not asked about");
        assertTrue(recorded.contains(CHECKER + ": concern c1 raised: sound plays on a hit"));
        assertTrue(recorded.contains(CHECKER + ": concern c2 raised: the game starts"));
        assertTrue(recorded.contains(CHECKER + ": the checker asks the conductor about c1: sound is"
                + " only covered by a test that mocks play — why is that enough?"));
    }

    @Test
    void no_stage_moves_while_a_why_is_open_and_the_refusal_carries_the_question() {
        concerns.raise("orc_1", "sound plays", "mocked", Concerns.AT_PLAN, "why is that enough?");
        items.set(1, item("td_plan", "plan", TodoStatus.DONE, "plan.md written"));

        TodoRefused refused = assertThrows(TodoRefused.class, () -> gate().checked(CONV,
                moved("td_phases", TodoStatus.IN_PROGRESS), HOME));

        assertTrue(refused.getMessage().startsWith("todo_write refused operation 1: no stage moves"
                + " while the acceptance checker waits on your answer to c1. Nothing was"
                + " changed."), refused.getMessage());
        assertTrue(refused.getMessage().contains("It asks: why is that enough?"),
                refused.getMessage());
        // A write that moves no stage — a phase child added — is not held.
        List<TodoOp> add = List.of(new TodoOp.Add("phase 1: scaffold", "td_phases"));
        assertEquals(add, gate().checked(CONV, add, HOME).ops());
    }

    /** A checker whose plan answer cannot be read has nothing to say: logged, and recorded. */
    @Test
    void a_plan_pass_that_cannot_be_read_has_nothing_to_say() {
        unreadable = new AcceptanceChecker.Unreadable("its answer has no 'concerns' list");

        TodoTools.Checked passed = planDone();

        assertEquals(List.of(), passed.notes());
        assertEquals(List.of(), concerns.rows);
        assertTrue(recorded.contains(CHECKER + ": the checker's plan pass gave no answer that"
                + " could be read, so it raised nothing"), recorded.toString());
    }

    /** The plan pass that raised something is not run again by a later plan move. */
    @Test
    void the_plan_pass_runs_once_it_has_raised_something() {
        raise = List.of(new AcceptanceChecker.Raised("x", "y", null));
        planDone();
        items.set(1, item("td_plan", "plan", TodoStatus.IN_PROGRESS, "again"));

        planDone();

        assertEquals(List.of("plan"), passes);
    }

    /** spec.md or plan.md that cannot be read is said so to the checker, never a crash. */
    @Test
    void a_missing_plan_is_said_to_the_checker() {
        files.remove(DIR + "plan.md");

        planDone();

        assertTrue(briefs.get(0).plan().contains("plan.md could not be read"), briefs.get(0).plan());
    }

    /**
     * The end pass, as acceptance starts and before any command runs: a concern that holds is
     * checked; one that does not keeps the run out of acceptance with its finding, counted with
     * the run's check failures; one the checker cannot check is the person's.
     */
    @Test
    void the_end_pass_checks_each_concern_and_a_failing_one_keeps_the_run_out() {
        concerns.raise("orc_1", "the game starts", "main.py is a no-op", Concerns.AT_PLAN, null);
        concerns.raise("orc_1", "a hit plays a sound", "mocked mixer", Concerns.AT_PLAN, null);
        concerns.raise("orc_1", "the ship moves", "no input", Concerns.AT_PLAN, null);
        endAnswer = new AcceptanceChecker.End(List.of(
                new AcceptanceChecker.Verdict("c1", Concerns.DOES_NOT_HOLD,
                        "game/main.py defines main() and never calls it", null),
                new AcceptanceChecker.Verdict("c2", Concerns.CANNOT_CHECK, "only a person hears",
                        "shoot an invader and listen for the hit"),
                new AcceptanceChecker.Verdict("c3", Concerns.HOLDS,
                        "game/input.py reads the arrow keys in the loop", null)),
                List.of());

        TodoTools.Checked passed = acceptanceStarted();

        assertEquals(List.of("end [c1, c2, c3]"), passes);
        assertEquals(List.of(Concerns.CHECKED, Concerns.FOR_THE_PERSON, Concerns.CHECKED),
                concerns.rows.stream().map(Concerns.Concern::state).toList());
        assertTrue(concerns.rows.get(0).doesNotHold());
        assertTrue(concerns.rows.get(1).forThePersonAtAcceptance());
        assertEquals("shoot an invader and listen for the hit", concerns.rows.get(1).personCheck());
        String note = passed.notes().get(0);
        assertTrue(note.contains("one does not hold, so the acceptance commands were not run"),
                note);
        assertTrue(note.contains("Found: game/main.py defines main() and never calls it"), note);
        assertTrue(note.endsWith("Return to `phases` (move it from done to in_progress) and have"
                + " them fixed; the checker checks again when `acceptance` is started again."),
                note);
        assertEquals(1, failures.size(), "counted with the run's check failures");
        assertTrue(recorded.contains(CHECKER + ": c1 does not hold: game/main.py defines main()"
                + " and never calls it"), recorded.toString());
        assertTrue(recorded.contains(CHECKER + ": c3 holds: game/input.py reads the arrow keys in"
                + " the loop"));
        assertTrue(end.requested().isEmpty());
    }

    /** At the run's failed-checks the person is asked whether it goes on, and the turn ends. */
    @Test
    void a_failing_end_pass_at_the_limit_asks_the_person() {
        failedChecksLimit = 1;
        concerns.raise("orc_1", "the game starts", "no-op", Concerns.AT_PLAN, null);
        endAnswer = new AcceptanceChecker.End(List.of(new AcceptanceChecker.Verdict("c1",
                Concerns.DOES_NOT_HOLD, "still a no-op", null)), List.of());

        TodoTools.Checked passed = acceptanceStarted();

        assertEquals(Outcome.Ending.AWAITING, end.requested().orElseThrow().ending());
        assertTrue(passed.notes().get(0).contains("so they are asked whether it goes on"));
        assertTrue(failures.get(0).startsWith("acceptance checker (c1 does not hold) | c1: still a"
                + " no-op"), failures.get(0));
    }

    /**
     * The measured Space Invaders case (orc_3190A00C6035D3B5): nothing raised at plan, and at the
     * end the checker finds the entry point a no-op — a concern of its own, which does not hold.
     */
    @Test
    void the_end_pass_may_raise_its_own_concern_nothing_starts_the_game() {
        endAnswer = new AcceptanceChecker.End(List.of(), List.of(new AcceptanceChecker.Found(
                "main.py is a no-op — nothing starts the game", "no loop, no window, no input",
                Concerns.DOES_NOT_HOLD, "game/main.py: def main(): pass", null)));

        TodoTools.Checked passed = acceptanceStarted();

        Concerns.Concern found = concerns.rows.get(0);
        assertEquals(Concerns.AT_END, found.raised());
        assertTrue(found.doesNotHold());
        assertTrue(passed.notes().get(0).contains("main.py is a no-op — nothing starts the game"));
        assertTrue(recorded.contains(CHECKER + ": concern c1 raised at the end: main.py is a no-op"
                + " — nothing starts the game"), recorded.toString());
    }

    /** An end pass that cannot be read can check nothing: every concern goes to the person. */
    @Test
    void an_end_pass_that_cannot_be_read_leaves_every_concern_to_the_person() {
        concerns.raise("orc_1", "the game starts", "no-op", Concerns.AT_PLAN, null);
        unreadable = new AcceptanceChecker.Unreadable("not JSON");

        TodoTools.Checked passed = acceptanceStarted();

        assertEquals(List.of(), passed.notes(), "nothing is refused: the person decides");
        assertTrue(concerns.rows.get(0).forThePersonAtAcceptance());
        assertTrue(concerns.rows.get(0).personCheck().startsWith("check that this is settled:"
                + " the game starts"), concerns.rows.get(0).personCheck());
    }

    /** A concern the checker left out of its verdicts is the person's, not passed over. */
    @Test
    void a_concern_left_without_a_verdict_goes_to_the_person() {
        concerns.raise("orc_1", "x", "y", Concerns.AT_PLAN, null);

        acceptanceStarted();

        assertTrue(concerns.rows.get(0).forThePersonAtAcceptance());
        assertEquals("the checker gave no verdict on it", concerns.rows.get(0).finding());
    }

    @Test
    void the_plan_stage_is_plan_or_else_the_written_acceptance_stage() {
        assertEquals("plan", CheckerGate.planStage(run));
    }
}
