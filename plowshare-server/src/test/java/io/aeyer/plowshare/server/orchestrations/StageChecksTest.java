package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.CommandRunner;
import io.aeyer.plowshare.protocol.EnvironmentFile;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Commands;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.TodoTools;
import io.aeyer.plowshare.server.agents.TurnEnd;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition.Tier;
import io.aeyer.plowshare.server.approvals.RunApproval;
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

class StageChecksTest {

    private static final Home HOME = Home.of("story");
    private static final String CONV = "cnv_conductor";

    private final OrchestrationRecord run = new OrchestrationRecord("orc_1", "code_implementation",
            Tier.PROJECT, "sha256:x", "src", "test",
            List.of(new StageRules.Stage("test_design", List.of()),
                    new StageRules.Stage("code", List.of(), true)),
            3, 0, "story", CONV, null, "interlocutor", "enzo", null, null, 0, null,
            OrchestrationState.RUNNING, null, null, null, 0, 0, false, null, Instant.EPOCH, null);

    private final List<TodoItem> items = new ArrayList<>(List.of(
            item("td_design", "test_design", TodoStatus.DONE, "designed"),
            item("td_code", "code", TodoStatus.IN_PROGRESS, "built it")));
    private OrchestrationChecks.Check check = new OrchestrationChecks.Check("orc_1",
            List.of("pytest", "-q"), "local", "/repo", OrchestrationChecks.OPEN, null, Instant.EPOCH);
    private RunApproval approval;
    private CommandRunner.Outcome outcome = new CommandRunner.Outcome(0, false, false, "ok", 0,
            "", 0, 1500);
    private final List<List<String>> ran = new ArrayList<>();
    /** What the run tool's hooks say about the check, as the port reports it. */
    private Commands.Verdict verdict = Commands.Verdict.ALLOWED;
    /** The side the check's directory is on now, whichever provider covers it today. */
    private String placedSide = "local";

    private static TodoItem item(String id, String stage, TodoStatus status, String summary) {
        return new TodoItem(id, CONV, null, 0, stage, status, summary, true, stage, Instant.EPOCH);
    }

    private final TodoLists todos = new TodoLists() {
        @Override public List<TodoItem> list(String conversation) { return items; }
        @Override public List<TodoItem> apply(String c, List<TodoOp> o, String s) {
            throw new AssertionError("StageChecks never applies");
        }
        @Override public Optional<Notice> noticeFor(String conversation) {
            throw new AssertionError("StageChecks never reads a notice");
        }
        @Override public void noticed(String conversation, io.aeyer.plowshare.server.todos.TodoNotices.Seen seen) {
            throw new AssertionError("StageChecks never acks a notice");
        }
        @Override public void forget(String conversation) {
            throw new AssertionError("StageChecks never forgets a notice");
        }
    };

    private final Commands.Port port = new Commands.Port() {
        @Override public Commands.Placed place(Home home, Path cwd, List<String> argv) {
            EnvironmentFile.Side d = EnvironmentFile.Side.DEFAULT;
            return new Commands.Placed(argv, cwd, null, placedSide, new EnvironmentFile.Side("ask",
                    false, d.inherit(), d.env(), d.timeout(), d.outputBytes(), d.isolation()), null);
        }
        @Override public Commands.Verdict judge(Commands.Placed placed) {
            return verdict;
        }
        @Override public CommandRunner.Outcome run(Commands.Placed placed) {
            ran.add(placed.argv());
            return outcome;
        }
    };

    private StageChecks checks() {
        return checks(run);
    }

    private StageChecks checks(OrchestrationRecord forRun) {
        return new StageChecks(c -> Optional.of(forRun), todos, id -> Optional.ofNullable(check),
                id -> Optional.ofNullable(approval), port);
    }

    private OrchestrationRecord withState(OrchestrationState state) {
        return new OrchestrationRecord(run.id(), run.definitionName(), run.tier(),
                run.definitionHash(), run.definitionSource(), run.definitionOrigin(), run.stages(),
                run.maxReturns(), run.returnsUsed(), run.project(), run.conductorConversation(),
                run.callerConversation(), run.callerAgent(), run.callerHandle(),
                run.callerSession(), run.parent(), run.depth(), run.waitingFor(), state,
                run.pendingCap(), run.result(), run.failure(), run.restarts(), run.nudges(),
                run.endedInProse(),
                run.resultDeliveredAt(), run.createdAt(), run.endedAt());
    }

    private OrchestrationRecord withStages(List<StageRules.Stage> stages) {
        return new OrchestrationRecord(run.id(), run.definitionName(), run.tier(),
                run.definitionHash(), run.definitionSource(), run.definitionOrigin(), stages,
                run.maxReturns(), run.returnsUsed(), run.project(), run.conductorConversation(),
                run.callerConversation(), run.callerAgent(), run.callerHandle(),
                run.callerSession(), run.parent(), run.depth(), run.waitingFor(), run.state(),
                run.pendingCap(), run.result(), run.failure(), run.restarts(), run.nudges(),
                run.endedInProse(),
                run.resultDeliveredAt(), run.createdAt(), run.endedAt());
    }

    private List<TodoOp> done(String id) {
        return List.of(new TodoOp.Update(id, TodoStatus.DONE, null, null));
    }

    @Test
    void a_passing_check_lets_the_move_through_and_says_so_in_the_summary() {
        List<TodoOp> after = checks().check(CONV, done("td_code"), HOME);

        assertEquals(List.of(List.of("pytest", "-q")), ran);
        String summary = ((TodoOp.Update) after.get(0)).summary();
        assertTrue(summary.startsWith("built it — check passed: `pytest -q` (exit 0, 1.5s)"),
                summary);
    }

    @Test
    void a_failing_check_refuses_the_move_with_the_end_of_its_output() {
        outcome = new CommandRunner.Outcome(1, false, false, "1 failed", 0, "", 0, 3000);

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> checks().check(CONV, done("td_code"), HOME));

        assertTrue(refused.getMessage().contains("code is not done: its check `pytest -q` exited 1"),
                refused.getMessage());
        assertTrue(refused.getMessage().contains("1 failed"));
    }

    /** Each failure counted; one the count says to ask about, the run's turn ends on (V69). */
    @Test
    void a_failure_is_counted_and_the_one_the_person_is_asked_about_ends_the_turn() {
        outcome = new CommandRunner.Outcome(1, false, false, "1 failed", 0,
                "pygame.error: No available audio device", 0, 3000);
        List<String> counted = new ArrayList<>();
        boolean[] ask = {false};
        TurnEnd end = new TurnEnd();
        StageChecks counting = new StageChecks(c -> Optional.of(run), todos,
                id -> Optional.ofNullable(check), id -> Optional.ofNullable(approval), port,
                OrchestrationRecorder.NONE, (orchestration, what, output) -> {
                    counted.add(orchestration + " " + what + " | " + output);
                    return ask[0];
                }, end);

        TodoRefused first = assertThrows(TodoRefused.class,
                () -> counting.check(CONV, done("td_code"), HOME));
        assertTrue(end.requested().isEmpty(), "not asked: refused as before");
        assertTrue(first.getMessage().contains("exited 1"), first.getMessage());

        ask[0] = true;
        TodoRefused asked = assertThrows(TodoRefused.class,
                () -> counting.check(CONV, done("td_code"), HOME));

        assertEquals(List.of("orc_1 check `pytest -q` | --- stdout ---\n1 failed\n--- stderr ---\n"
                + "pygame.error: No available audio device"), counted.subList(1, 2));
        assertEquals(Outcome.Ending.AWAITING, end.requested().orElseThrow().ending());
        assertTrue(asked.getMessage().contains("so they are asked whether the run goes on. Your"
                + " turn ends here"), asked.getMessage());
        assertTrue(asked.getMessage().contains("No available audio device"));
    }

    @Test
    void a_passing_check_is_not_counted() {
        List<String> counted = new ArrayList<>();
        StageChecks counting = new StageChecks(c -> Optional.of(run), todos,
                id -> Optional.ofNullable(check), id -> Optional.ofNullable(approval), port,
                OrchestrationRecorder.NONE, (orchestration, what, output) -> counted.add(what),
                new TurnEnd());

        counting.check(CONV, done("td_code"), HOME);

        assertEquals(List.of(), counted);
    }

    @Test
    void a_check_that_waits_for_input_is_refused_as_a_timeout() {
        outcome = new CommandRunner.Outcome(null, true, false, "Allocate point to which stat?", 0,
                "", 0, 300_000);

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> checks().check(CONV, done("td_code"), HOME));

        assertTrue(refused.getMessage().contains("did not finish"), refused.getMessage());
        assertTrue(refused.getMessage().contains("given none"));
    }

    @Test
    void no_check_set_is_refused_with_the_way_to_set_one() {
        check = null;

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> checks().check(CONV, done("td_code"), HOME));

        assertTrue(refused.getMessage().contains("orchestration_check"));
        assertEquals(List.of(), ran);
    }

    @Test
    void an_unanswered_approval_is_refused_without_running_anything() {
        check = new OrchestrationChecks.Check("orc_1", List.of("pytest"), "local", "/repo",
                OrchestrationChecks.APPROVAL, "apr_1", Instant.EPOCH);
        approval = new RunApproval("apr_1", 7L, CONV, CONV, "enzo", "code_implementation",
                "local", List.of("pytest"), "/repo", null, RunApproval.ASKED, null, null, null,
                null, null, Instant.EPOCH);

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> checks().check(CONV, done("td_code"), HOME));

        assertTrue(refused.getMessage().contains("apr_1"), refused.getMessage());
        assertEquals(List.of(), ran);
    }

    @Test
    void a_write_that_moves_no_checked_stage_to_done_runs_nothing() {
        List<TodoOp> ops = List.of(new TodoOp.Update("td_code", TodoStatus.IN_PROGRESS, null, null));

        assertEquals(ops, checks().check(CONV, ops, HOME));
        assertEquals(List.of(), ran);
    }

    /**
     * Fix round 1, finding 2(a): the board decides a locked item's move against the WORKING copy
     * as ops apply in order (TodoBoard.apply), not the list as it was before the batch. A batch
     * that starts a checked stage and finishes it in the very same write is a move the board would
     * allow — {@code td_code} is {@code pending} going in, {@code in_progress} by the second op —
     * and had been invisible to {@link StageChecks}, which only ever looked at the pre-batch list.
     */
    @Test
    void a_stage_started_and_finished_in_the_same_write_is_still_checked() {
        items.clear();
        items.add(item("td_design", "test_design", TodoStatus.DONE, "designed"));
        items.add(item("td_code", "code", TodoStatus.PENDING, null));
        outcome = new CommandRunner.Outcome(1, false, false, "1 failed", 0, "", 0, 900);
        List<TodoOp> ops = List.of(
                new TodoOp.Update("td_code", TodoStatus.IN_PROGRESS, null, null),
                new TodoOp.Update("td_code", TodoStatus.DONE, null, "x"));

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> checks().check(CONV, ops, HOME));

        assertTrue(refused.getMessage().contains("code is not done: its check `pytest -q` exited 1"),
                refused.getMessage());
        assertEquals(List.of(List.of("pytest", "-q")), ran);
    }

    /**
     * Fix round 1, finding 2(b): a summary an earlier op in the same batch gives a fresh, summary-
     * less item (a fresh seed, or one a return just cleared) is what the board's DONE rule reads
     * for a later op in the SAME batch that carries no summary of its own — {@code
     * effectiveSummary} at TodoBoard.java:145-146 reads {@code current.summary()}, and {@code
     * current} there is the working copy, already carrying the earlier op's write.
     */
    @Test
    void a_summary_given_earlier_in_the_same_write_still_counts_at_the_later_done_move() {
        items.clear();
        items.add(item("td_design", "test_design", TodoStatus.DONE, "designed"));
        items.add(item("td_code", "code", TodoStatus.IN_PROGRESS, null));
        outcome = new CommandRunner.Outcome(1, false, false, "1 failed", 0, "", 0, 900);
        List<TodoOp> ops = List.of(
                new TodoOp.Update("td_code", null, null, "x"),
                new TodoOp.Update("td_code", TodoStatus.DONE, null, null));

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> checks().check(CONV, ops, HOME));

        assertTrue(refused.getMessage().contains("code is not done: its check `pytest -q` exited 1"),
                refused.getMessage());
        assertEquals(List.of(List.of("pytest", "-q")), ran);
    }

    /**
     * Fix round 1, finding 3: the conductor tools — including this checked {@code todo_write} —
     * are offered to any non-terminal run, not only a {@code RUNNING} one
     * ({@code OrchestrationsConfig.runExtras} filters on {@code !state().terminal()}). A write
     * issued while the run is {@code WAITING} or {@code ASKING} (in the same response that starts
     * a wait, for instance) had skipped the check entirely because {@link StageChecks} only ever
     * ran it for a {@code RUNNING} row.
     */
    @Test
    void a_waiting_runs_done_move_of_a_checked_stage_still_runs_the_check() {
        List<TodoOp> after = checks(withState(OrchestrationState.WAITING))
                .check(CONV, done("td_code"), HOME);

        assertEquals(List.of(List.of("pytest", "-q")), ran);
        String summary = ((TodoOp.Update) after.get(0)).summary();
        assertTrue(summary.contains("check passed"), summary);
    }

    /**
     * Fix round 2: {@code StageMoves}' return branch (DONE→IN_PROGRESS on the returned-to stage)
     * clears that stage's own stale summary and resets every later stage to pending with no
     * summary — a consequence {@code TodoBoard.apply} folds into its working copy before a later
     * op in the same batch sees it. The round-1 simulation tracked only each op's own explicit
     * status/summary and missed this: a batch that returns to a checked stage and then marks it
     * done again, in the same write, with no fresh summary, would have reused the stale pre-return
     * summary ("impl A") to pass a check the board itself refuses ("needs a summary of what was
     * done") — the check step silently defeating the board's own rule. Fixed by mirroring
     * exactly what {@code StageMoves} resets: no check must run and the board is left to refuse
     * this batch itself, for a reason a fresh {@code summary} on op 2 would have fixed.
     */
    @Test
    void a_return_in_the_same_write_clears_the_stale_summary_the_check_would_otherwise_reuse() {
        OrchestrationRecord returning = withStages(List.of(
                new StageRules.Stage("code", List.of(), true),
                new StageRules.Stage("review", List.of("code"), false)));
        items.clear();
        items.add(item("td_code", "code", TodoStatus.DONE, "impl A"));
        items.add(item("td_review", "review", TodoStatus.IN_PROGRESS, null));
        List<TodoOp> ops = List.of(
                new TodoOp.Update("td_code", TodoStatus.IN_PROGRESS, null, null),
                new TodoOp.Update("td_code", TodoStatus.DONE, null, null));

        assertEquals(ops, checks(returning).check(CONV, ops, HOME));
        assertEquals(List.of(), ran);
    }

    /**
     * Mirror of the above: a return also resets a LATER checked stage to pending, even though it
     * had been in progress before the batch. A same-write op then trying to mark that stage done
     * — bypassing the in-progress it never actually re-entered after the return — must not spend
     * a check on a move the board is going to refuse anyway ("must be in progress before it is
     * done"): a wasted check for a batch the board never accepts.
     */
    @Test
    void a_return_resets_a_later_checked_stage_so_it_is_not_wastefully_checked() {
        OrchestrationRecord returning = withStages(List.of(
                new StageRules.Stage("code", List.of(), false),
                new StageRules.Stage("polish", List.of("code"), true)));
        items.clear();
        items.add(item("td_code", "code", TodoStatus.DONE, "impl A"));
        items.add(item("td_polish", "polish", TodoStatus.IN_PROGRESS, null));
        List<TodoOp> ops = List.of(
                new TodoOp.Update("td_code", TodoStatus.IN_PROGRESS, null, null),
                new TodoOp.Update("td_polish", TodoStatus.DONE, null, "finished polishing"));

        assertEquals(ops, checks(returning).check(CONV, ops, HOME));
        assertEquals(List.of(), ran);
    }

    // --- final review ---------------------------------------------------------------------------

    /**
     * F1: the run tool's hooks bind the check every time it runs, so a hook added after the check
     * was set still refuses it.
     */
    @Test
    void a_run_hook_that_denies_the_check_refuses_the_move_without_running_it() {
        verdict = new Commands.Verdict("no pytest on Fridays", null);

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> checks().check(CONV, done("td_code"), HOME));

        assertTrue(refused.getMessage().contains(
                "the check `pytest -q` could not run: no pytest on Fridays"), refused.getMessage());
        assertEquals(List.of(), ran);
    }

    /** F1: an ask at check time does not ask again — the run's consent stands. */
    @Test
    void a_run_hook_that_asks_about_the_check_does_not_stop_it_running() {
        verdict = new Commands.Verdict(null, "pytest touches the network");

        checks().check(CONV, done("td_code"), HOME);

        assertEquals(List.of(List.of("pytest", "-q")), ran);
    }

    /**
     * F3: consent is for the side it was given on. A directory that has since moved to the other
     * side — a client rooted it, or stopped rooting it — is not run there.
     */
    @Test
    void a_check_whose_directory_is_on_the_other_side_now_is_refused_without_running() {
        placedSide = "server";

        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> checks().check(CONV, done("td_code"), HOME));

        assertTrue(refused.getMessage().contains("was allowed on the local side, and its"
                + " directory is on the server side now"), refused.getMessage());
        assertEquals(List.of(), ran);
    }

    private void approvalIn(String state) {
        check = new OrchestrationChecks.Check("orc_1", List.of("pytest"), "local", "/repo",
                OrchestrationChecks.APPROVAL, "apr_1", Instant.EPOCH);
        approval = state == null ? null : new RunApproval("apr_1", 7L, CONV, CONV, "enzo",
                "code_implementation", "local", List.of("pytest"), "/repo", null, state,
                RunApproval.ONCE, null, null, null, null, Instant.EPOCH);
    }

    /** Ledger: the approval is read by id and accepted while allowed or used — never consumed. */
    @Test
    void an_allowed_or_used_approval_lets_the_check_run() {
        for (String state : List.of(RunApproval.ALLOWED, RunApproval.USED)) {
            ran.clear();
            approvalIn(state);

            checks().check(CONV, done("td_code"), HOME);

            assertEquals(List.of(List.of("pytest")), ran, state);
        }
    }

    /**
     * Ledger + F2: a denied, revoked or vanished approval refuses the move, names the state, and
     * tells the conductor it may set a different check.
     */
    @Test
    void a_denied_revoked_or_gone_approval_refuses_the_move_naming_the_state() {
        for (String state : java.util.Arrays.asList(RunApproval.DENIED, RunApproval.REVOKED, null)) {
            ran.clear();
            approvalIn(state);

            TodoRefused refused = assertThrows(TodoRefused.class,
                    () -> checks().check(CONV, done("td_code"), HOME));

            String named = state == null ? "gone" : state;
            assertTrue(refused.getMessage().contains("apr_1 is " + named), refused.getMessage());
            assertTrue(refused.getMessage().contains("set a different check with"
                    + " orchestration_check"), refused.getMessage());
            assertEquals(List.of(), ran, named);
        }
    }

    /** F6: with no way to run the check, a checked done move is refused and nothing else is. */
    @Test
    void a_run_that_cannot_run_its_check_refuses_only_a_checked_done_move() {
        TodoTools.BeforeApply cannot = StageChecks.cannotRun(run, todos);
        List<TodoOp> other = List.of(new TodoOp.Update("td_code", null, null, "more"),
                new TodoOp.Update("td_design", TodoStatus.DONE, null, null));

        assertEquals(other, cannot.check(CONV, other, HOME));
        TodoRefused refused = assertThrows(TodoRefused.class,
                () -> cannot.check(CONV, done("td_code"), HOME));
        assertTrue(refused.getMessage().contains("the check cannot run on this server"),
                refused.getMessage());
        assertEquals(List.of(), ran);
    }

    private final List<String> recorded = new ArrayList<>();

    private StageChecks recording() {
        return new StageChecks(c -> Optional.of(run), todos, id -> Optional.ofNullable(check),
                id -> Optional.ofNullable(approval), port, new OrchestrationRecorder() {
                    @Override
                    public void checkRan(OrchestrationRecord forRun, List<String> argv,
                            Integer exitCode, boolean timedOut) {
                        recorded.add(forRun.id() + " " + String.join(" ", argv) + " exit="
                                + exitCode + " timedOut=" + timedOut);
                    }
                });
    }

    @Test
    void a_check_that_ran_is_recorded_once_whether_it_passed_or_failed() {
        recording().check(CONV, done("td_code"), HOME);
        outcome = new CommandRunner.Outcome(1, false, false, "1 failed", 0, "", 0, 3000);
        assertThrows(TodoRefused.class, () -> recording().check(CONV, done("td_code"), HOME));

        assertEquals(List.of("orc_1 pytest -q exit=0 timedOut=false",
                "orc_1 pytest -q exit=1 timedOut=false"), recorded);
    }

    /** The end of what the check printed goes to the record with its result, bounded as {@link
     *  CheckFacts#output} bounds it: it is what a reviewing delegate is later handed. */
    @Test
    void a_check_that_ran_is_recorded_with_the_end_of_its_output() {
        List<String> outputs = new ArrayList<>();
        outcome = new CommandRunner.Outcome(1, false, false, "1 failed, 25 passed\n", 0, "", 0,
                3000);
        StageChecks checks = new StageChecks(c -> Optional.of(run), todos,
                id -> Optional.ofNullable(check), id -> Optional.ofNullable(approval), port,
                new OrchestrationRecorder() {
                    @Override
                    public void checkRan(OrchestrationRecord forRun, List<String> argv,
                            Integer exitCode, boolean timedOut, String output) {
                        outputs.add(output);
                    }
                });

        assertThrows(TodoRefused.class, () -> checks.check(CONV, done("td_code"), HOME));

        assertEquals(List.of(CheckFacts.output(outcome)), outputs);
        assertEquals("--- stdout ---\n1 failed, 25 passed\n--- stderr ---\n(nothing)",
                outputs.get(0));
    }

    @Test
    void a_check_that_never_ran_records_nothing() {
        check = null;

        assertThrows(TodoRefused.class, () -> recording().check(CONV, done("td_code"), HOME));

        assertEquals(List.of(), recorded);
    }
}
