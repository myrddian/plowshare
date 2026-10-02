package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Commands;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.TodoTools;
import io.aeyer.plowshare.server.agents.TurnEnd;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import io.aeyer.plowshare.server.files.WorkspaceUnavailableException;
import io.aeyer.plowshare.server.todos.StageRules;
import io.aeyer.plowshare.server.todos.TodoLists;
import io.aeyer.plowshare.server.todos.TodoOp;
import io.aeyer.plowshare.server.todos.TodoRefused;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The acceptance checker in front of the board (spec 2026-10-01, the acceptance checker §3), for a
 * run whose definition names a {@code checker:}. First of the stage gates, so what it says rides
 * the same {@code todo_write} that moved the stage.
 *
 * <ul>
 *   <li><b>The plan pass</b>, on the done move of the {@code plan} stage — or, in a definition with
 *       none, of the {@code acceptance: written} stage — once the plan and the acceptance both
 *       exist: the checker raises its concerns, and every WHY it asks goes back to the conductor
 *       with the move's result.
 *   <li><b>No stage moves while a WHY is open</b>: any start, return or done move is refused, with
 *       every open question whole, until the conductor has answered each with {@code
 *       checker_answer}. Its turn is not done until it has.
 *   <li><b>The end pass</b>, on the move of the {@code acceptance: required} stage to in progress,
 *       before any {@code run:} line runs: the checker checks its concerns against the finished
 *       project. A concern that does not hold keeps the run out of acceptance with its finding.
 * </ul>
 *
 * <h2>The move into acceptance stands, and acceptance cannot be done</h2>
 *
 * <p>Refusing the start itself would strand the run: with {@code review} done and nothing in
 * progress, the board has no stage to return from. So a failing end pass lets the start stand,
 * runs nothing, says each finding and the way back with the move, and the acceptance gate refuses
 * the done move for as long as a concern does not hold — the run goes back as a failed acceptance
 * does, from {@code acceptance} to the stage its {@code may-return-to} names. Each failing end pass
 * is counted with the run's check failures, so a loop of them reaches the person.
 */
public final class CheckerGate implements TodoTools.BeforeApply {

    private final Function<String, Optional<OrchestrationRecord>> runByConversation;
    private final Function<String, Optional<String>> checkerOf;
    private final Function<String, Optional<String>> artifactsDir;
    private final TodoLists todos;
    private final Commands.Port commands;
    private final Checking checking;
    private final String session;
    private final CheckFailures failures;
    private final TurnEnd end;

    /**
     * @param checkerOf the checker a run pinned at start, or empty for none
     * @param artifactsDir a run's artifacts directory, where its spec.md and plan.md are
     * @param commands the run's own port, which reads spec.md and plan.md where they are
     * @param session the session whose machine the project is on, or null
     * @param failures where a failing end pass is counted, and the person asked at the limit
     * @param end the conductor's turn, ended when the person is asked
     */
    public CheckerGate(Function<String, Optional<OrchestrationRecord>> runByConversation,
            Function<String, Optional<String>> checkerOf,
            Function<String, Optional<String>> artifactsDir, TodoLists todos,
            Commands.Port commands, Checking checking, String session, CheckFailures failures,
            TurnEnd end) {
        this.runByConversation = Objects.requireNonNull(runByConversation, "runByConversation");
        this.checkerOf = Objects.requireNonNull(checkerOf, "checkerOf");
        this.artifactsDir = Objects.requireNonNull(artifactsDir, "artifactsDir");
        this.todos = Objects.requireNonNull(todos, "todos");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.checking = Objects.requireNonNull(checking, "checking");
        this.session = session;
        this.failures = Objects.requireNonNull(failures, "failures");
        this.end = Objects.requireNonNull(end, "end");
    }

    @Override
    public List<TodoOp> check(String conversation, List<TodoOp> ops, Home home) {
        return checked(conversation, ops, home).ops();
    }

    @Override
    public TodoTools.Checked checked(String conversation, List<TodoOp> ops, Home home) {
        OrchestrationRecord run = runByConversation.apply(conversation)
                .filter(found -> !found.state().terminal()).orElse(null);
        String checker = run == null ? null : checkerOf.apply(run.id()).orElse(null);
        if (checker == null) {
            return new TodoTools.Checked(ops, List.of());
        }
        DoneMoves.Walk walk = DoneMoves.walk(run, todos.list(conversation), ops, stage -> true);
        if (walk.boardRefuses() || (walk.done().isEmpty() && walk.starts().isEmpty())) {
            return new TodoTools.Checked(ops, List.of());
        }
        List<Concerns.Concern> waiting = checking.waiting(run.id());
        if (!waiting.isEmpty()) {
            int first = Math.min(walk.done().stream().mapToInt(DoneMoves.Move::index).min()
                            .orElse(Integer.MAX_VALUE),
                    walk.starts().stream().mapToInt(DoneMoves.Start::index).min()
                            .orElse(Integer.MAX_VALUE));
            throw new TodoRefused("todo_write refused operation " + (first + 1) + ": no stage"
                    + " moves while the acceptance checker waits on your answer to "
                    + waiting.stream().map(Concerns.Concern::id).collect(Collectors.joining(", "))
                    + ". Nothing was changed.\n\n" + Checking.whyQuestions(waiting));
        }
        String planStage = planStage(run);
        String acceptanceStage = run.stages().stream()
                .filter(stage -> OrchestrationDefinition.ACCEPTANCE_REQUIRED
                        .equals(stage.acceptance()))
                .map(StageRules.Stage::id).findFirst().orElse(null);
        List<String> notes = new ArrayList<>();
        for (DoneMoves.Move move : walk.done()) {
            if (move.stage().equals(planStage) && !checking.plannedAlready(run.id())) {
                notes.addAll(checking.planPass(run, brief(run, checker, home)));
            }
        }
        for (DoneMoves.Start start : walk.starts()) {
            if (!start.returning() && start.stage().equals(acceptanceStage)) {
                List<Concerns.Concern> failing = checking.endPass(run,
                        brief(run, checker, home));
                if (!failing.isEmpty()) {
                    notes.add(failingEnd(run, acceptanceStage, failing, start.returnsLeft()));
                }
            }
        }
        return new TodoTools.Checked(ops, notes);
    }

    /** The findings, counted as a failure of the run's checks; the person asked at the limit. */
    private String failingEnd(OrchestrationRecord run, String acceptanceStage,
            List<Concerns.Concern> failing, int returnsLeft) {
        StageRules.Stage stage = run.stages().stream()
                .filter(each -> each.id().equals(acceptanceStage)).findFirst().orElseThrow();
        String back = stage.mayReturnTo().isEmpty() ? null : stage.mayReturnTo().get(0);
        String said = Checking.findings(failing, back, returnsLeft);
        String what = "acceptance checker (" + failing.stream().map(Concerns.Concern::id)
                .collect(Collectors.joining(", ")) + " " + (failing.size() == 1 ? "does" : "do")
                + " not hold)";
        String output = failing.stream().map(each -> each.id() + ": " + each.finding())
                .collect(Collectors.joining("\n"));
        if (failures.failed(run.id(), what, output)) {
            end.request(Outcome.Ending.AWAITING, "the person is asked whether the run goes on"
                    + " after its acceptance kept failing");
            return said + "\n\nThe run's checks have failed as many times as the person allows,"
                    + " so they are asked whether it goes on. Your turn ends here, and their"
                    + " answer arrives as your next message.";
        }
        return said;
    }

    /** The stage whose done move runs the plan pass: {@code plan}, or the written acceptance's. */
    static String planStage(OrchestrationRecord run) {
        return run.stages().stream().map(StageRules.Stage::id).filter("plan"::equals).findFirst()
                .orElseGet(() -> run.stages().stream()
                        .filter(stage -> OrchestrationDefinition.ACCEPTANCE_WRITTEN
                                .equals(stage.acceptance()))
                        .map(StageRules.Stage::id).findFirst().orElse(null));
    }

    private AcceptanceChecker.Brief brief(OrchestrationRecord run, String checker, Home home) {
        String dir = artifactsDir.apply(run.id()).orElse(null);
        return new AcceptanceChecker.Brief(run.id(), checker, home, session, dir,
                read(home, dir, "spec.md"), read(home, dir, "plan.md"));
    }

    /** A file of the artifacts directory, or a sentence saying why it could not be read. */
    private String read(Home home, String dir, String name) {
        if (dir == null) {
            return "(this run names no artifacts directory, so there is no " + name + ")";
        }
        String slash = dir.endsWith("/") ? dir : dir + "/";
        try {
            return commands.read(home, slash + name);
        } catch (WorkspaceRefusedException | WorkspaceUnavailableException
                | UnsupportedOperationException unreadable) {
            return "(" + slash + name + " could not be read: " + unreadable.getMessage() + ")";
        }
    }
}
