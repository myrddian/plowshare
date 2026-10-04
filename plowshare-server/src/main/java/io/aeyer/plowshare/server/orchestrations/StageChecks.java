package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.protocol.CommandRunner;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Commands;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.TodoTools;
import io.aeyer.plowshare.server.agents.TurnEnd;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import io.aeyer.plowshare.server.files.WorkspaceUnavailableException;
import io.aeyer.plowshare.server.todos.StageRules;
import io.aeyer.plowshare.server.todos.TodoItem;
import io.aeyer.plowshare.server.todos.TodoLists;
import io.aeyer.plowshare.server.todos.TodoOp;
import io.aeyer.plowshare.server.todos.TodoRefused;
import io.aeyer.plowshare.server.todos.TodoStatus;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The harness deciding a checked stage is done: a write moving one to {@code done} runs the run's
 * check first and is refused unless it passes. Spec 2026-09-26 §3.
 *
 * <p><b>Only a move the board would otherwise allow is checked</b> — a locked stage item, in
 * progress, with a summary — so a command lasting minutes is never run for a write the board
 * refuses for a sentence anyway. The rest is left to the board, which says why in its own words.
 *
 * <h2>Judged on the batch's own working copy, not the list before it</h2>
 *
 * <p>{@code TodoBoard.apply} decides a locked item's move against a working copy that earlier ops
 * in the SAME batch have already mutated — not the list as it stood when the batch arrived. A batch
 * that starts a checked stage and finishes it in one write, or that gives a fresh item its summary
 * in one op and marks it done in a later one, is a move the board allows and this class must
 * therefore also see, or the check it exists to run never happens. So {@link #check} walks the ops
 * in order, simulating exactly the two fields the board's own DONE rule reads — status and a
 * stripped summary — per item id, and decides against that simulated state rather than against
 * {@code todos.list(conversation)} directly. Fix round 1, finding 2. The simulation is {@link
 * DoneMoves}, shared with {@link AcceptanceGate}.
 *
 * <p><b>A return is one such consequence, and the simulation mirrors it too.</b> {@code
 * StageMoves}' return branch (a locked stage moving DONE→IN_PROGRESS) clears that stage's own stale
 * summary and resets every stage after it, in {@code run.stages()} order, to {@code pending} with
 * no summary — {@code TodoBoard.apply} folds that consequence into its working copy before a later
 * op in the same batch ever sees it. Missing this let a stale pre-return summary survive the
 * simulation and pass a check the board itself would refuse for lacking a fresh one, and let a
 * later checked stage a return demotes to {@code pending} still read as {@code in_progress} to a
 * same-write op that tries to finish it — a check spent on a move the board was always going to
 * refuse. Fix round 2.
 *
 * <h2>Offered to, and so checked for, any live run</h2>
 *
 * <p>This is one of the tools {@code OrchestrationsConfig.runExtras} hands a conductor for any
 * non-terminal run, not only a {@code RUNNING} one — a run {@code WAITING} on a child or {@code
 * ASKING} its caller still holds its tools until its turn actually ends. Checking only a {@code
 * RUNNING} row let a write issued in a non-running state skip the check entirely; {@link #check}
 * now passes ops through only when there is no run for the conversation at all, or that run has
 * already ended ({@link OrchestrationState#terminal()}), matching the same filter {@code runExtras}
 * uses to decide whether to offer this write in the first place. Fix round 1, finding 3.
 */
public final class StageChecks implements TodoTools.BeforeApply {

  private static final int TAIL_LINES = 40;

  /**
   * Of each stream, how many last lines the person's failed-checks question shows (V69): some
   * twenty in all.
   */
  static final int QUESTION_LINES = 10;

  private final Function<String, Optional<OrchestrationRecord>> runByConversation;
  private final TodoLists todos;
  private final Function<String, Optional<OrchestrationChecks.Check>> checks;
  private final Function<String, Optional<RunApproval>> approvals;
  private final Commands.Port commands;
  private final OrchestrationRecorder recorder;
  private final CheckFailures failures;
  private final TurnEnd end;

  public StageChecks(
      Function<String, Optional<OrchestrationRecord>> runByConversation,
      TodoLists todos,
      Function<String, Optional<OrchestrationChecks.Check>> checks,
      Function<String, Optional<RunApproval>> approvals,
      Commands.Port commands) {
    this(runByConversation, todos, checks, approvals, commands, OrchestrationRecorder.NONE);
  }

  /**
   * @param recorder told of every check this runs, passed or not — spec 2026-09-28
   */
  public StageChecks(
      Function<String, Optional<OrchestrationRecord>> runByConversation,
      TodoLists todos,
      Function<String, Optional<OrchestrationChecks.Check>> checks,
      Function<String, Optional<RunApproval>> approvals,
      Commands.Port commands,
      OrchestrationRecorder recorder) {
    this(runByConversation, todos, checks, approvals, commands, recorder, CheckFailures.NONE, null);
  }

  /**
   * @param failures where each failure is counted, and the person asked once it has failed the
   *     project's {@code failed-checks} times (V69)
   * @param end the conductor's turn, ended when the person is asked; null only with {@link
   *     CheckFailures#NONE}, which never asks
   */
  public StageChecks(
      Function<String, Optional<OrchestrationRecord>> runByConversation,
      TodoLists todos,
      Function<String, Optional<OrchestrationChecks.Check>> checks,
      Function<String, Optional<RunApproval>> approvals,
      Commands.Port commands,
      OrchestrationRecorder recorder,
      CheckFailures failures,
      TurnEnd end) {
    this.runByConversation = Objects.requireNonNull(runByConversation, "runByConversation");
    this.todos = Objects.requireNonNull(todos, "todos");
    this.checks = Objects.requireNonNull(checks, "checks");
    this.approvals = Objects.requireNonNull(approvals, "approvals");
    this.commands = Objects.requireNonNull(commands, "commands");
    this.recorder = Objects.requireNonNull(recorder, "recorder");
    this.failures = Objects.requireNonNull(failures, "failures");
    if (end == null && failures != CheckFailures.NONE) {
      throw new IllegalArgumentException(
          "a check that can ask the person needs the turn it" + " ends when it does");
    }
    this.end = end;
  }

  @Override
  public List<TodoOp> check(String conversation, List<TodoOp> ops, Home home) {
    Optional<OrchestrationRecord> found = runByConversation.apply(conversation);
    if (found.isEmpty() || found.get().state().terminal()) {
      return ops;
    }
    OrchestrationRecord run = found.get();
    // The simulation that finds the move lives in DoneMoves, shared with the acceptance gate
    // (spec 2026-09-29 §1b); this class's javadoc still says why it is simulated at all.
    Optional<DoneMoves.Move> move =
        DoneMoves.first(run, todos.list(conversation), ops, StageRules.Stage::checked);
    if (move.isEmpty()) {
      return ops;
    }
    int first = move.get().index();
    int n = first + 1;
    String stage = move.get().stage();
    String checkedStage = stage;
    String checkedSummary = move.get().summary();
    OrchestrationChecks.Check check =
        checks
            .apply(run.id())
            .orElseThrow(
                () ->
                    refused(
                        n,
                        "stage '"
                            + checkedStage
                            + "' is checked, and this run has no check yet: set it"
                            + " with orchestration_check — the command that shows the work is done"));
    String shown = "`" + String.join(" ", check.argv()) + "`";
    if (OrchestrationChecks.APPROVAL.equals(check.consent())) {
      String state = approvals.apply(check.approval()).map(RunApproval::state).orElse("gone");
      if (!RunApproval.ALLOWED.equals(state) && !RunApproval.USED.equals(state)) {
        throw refused(
            n,
            "stage '"
                + stage
                + "' is checked, and the check "
                + shown
                + " is not allowed to run: its approval "
                + check.approval()
                + " is "
                + state
                + (RunApproval.ASKED.equals(state)
                    ? ", still waiting for the person"
                    : "; set a different check with orchestration_check, or ask what"
                        + " it should be with orchestration_ask"));
      }
    }
    CommandRunner.Outcome outcome;
    try {
      Commands.Placed placed = commands.place(home, Path.of(check.cwd()), check.argv());
      // Consent is for the side it was given on (final review F3). Which side a directory
      // is on is whichever provider covers it today — a client rooting the project makes it
      // local, one that stops makes it server — so a check allowed, or recorded open, for
      // one machine would otherwise run on the other.
      if (!placed.side().equals(check.side())) {
        throw refused(
            n,
            "the check "
                + shown
                + " was allowed on the "
                + check.side()
                + " side, and its directory is on the "
                + placed.side()
                + " side now;"
                + " send the move again once the project is where it was");
      }
      String forbidden =
          Commands.refusal(placed.argv(), placed.side(), placed.allowed(), placed.offBecause());
      if (forbidden != null) {
        throw refused(n, "the check " + shown + " could not run: " + forbidden);
      }
      // The run tool's hooks, judged again every time (final review F1), so a hook added
      // after the check was set still binds it. Only a deny counts here: an ask was the
      // setting's to put to a person, and the run's consent stands.
      Commands.Verdict verdict = commands.judge(placed);
      if (verdict.denied() != null) {
        throw refused(n, "the check " + shown + " could not run: " + verdict.denied());
      }
      outcome = commands.run(placed);
    } catch (WorkspaceRefusedException | WorkspaceUnavailableException unreachable) {
      throw refused(
          n,
          "the check "
              + shown
              + " could not run: "
              + unreachable.getMessage()
              + "; send the move again once it can");
    }
    // It ran, whatever it came to: recorded before the verdict is turned into a refusal, with
    // the end of its output — what a reviewing delegate is later handed (CheckFacts).
    recorder.checkRan(
        run, check.argv(), outcome.exitCode(), outcome.timedOut(), CheckFacts.output(outcome));
    String took = String.format("%.1fs", outcome.millis() / 1000.0);
    if (outcome.timedOut()) {
      throw failed(
          run,
          n,
          shown,
          stage
              + " is not done: its check "
              + shown
              + " did not"
              + " finish in "
              + took
              + ". A test that waits for input never finishes; the"
              + " check is given none",
          outcome);
    }
    if (outcome.exitCode() == null || outcome.exitCode() != 0) {
      throw failed(
          run,
          n,
          shown,
          stage
              + " is not done: its check "
              + shown
              + " exited "
              + outcome.exitCode()
              + " after "
              + took,
          outcome);
    }
    List<TodoOp> passed = new ArrayList<>(ops);
    TodoOp.Update update = (TodoOp.Update) ops.get(first);
    passed.set(
        first,
        new TodoOp.Update(
            update.id(),
            update.status(),
            update.text(),
            checkedSummary + " — check passed: " + shown + " (exit 0, " + took + ")"));
    return List.copyOf(passed);
  }

  /**
   * The checked {@code todo_write}'s step for a run whose check cannot run on this server — no
   * command port (a runtime reaching no filesystem), or no store for the check or its approval. It
   * refuses every move of a checked stage item to {@code done} and lets anything else through:
   * failing closed, where handing the run the plain write let a checked stage be marked done with
   * no check at all (final review F6).
   */
  static TodoTools.BeforeApply cannotRun(OrchestrationRecord run, TodoLists todos) {
    Set<String> checked =
        run.stages().stream()
            .filter(StageRules.Stage::checked)
            .map(StageRules.Stage::id)
            .collect(Collectors.toSet());
    return (conversation, ops, home) -> {
      Map<String, TodoItem> items =
          todos.list(conversation).stream()
              .collect(Collectors.toMap(TodoItem::id, item -> item, (a, b) -> a));
      for (int i = 0; i < ops.size(); i++) {
        if (ops.get(i) instanceof TodoOp.Update update && update.status() == TodoStatus.DONE) {
          TodoItem item = items.get(update.id());
          if (item != null && item.locked() && checked.contains(item.stageId())) {
            throw refused(
                i + 1,
                "stage '"
                    + item.stageId()
                    + "' is checked, and the"
                    + " check cannot run on this server, so a checked stage cannot be"
                    + " marked done here");
          }
        }
      }
      return ops;
    };
  }

  private static TodoRefused refused(int n, String why) {
    return new TodoRefused(
        "todo_write refused operation " + n + ": " + why + ". Nothing was changed.");
  }

  /**
   * A failed check's refusal — counted first (V69): at the project's {@code failed-checks} the
   * person is asked whether the run goes on, with the end of this failure's output, and the
   * conductor's turn ends on it instead of the move only being refused again. Measured
   * 2026-09-29/30, {@code orc_318DFD3782228160}: one phase's check failed 21 times over 183
   * minutes, and nobody was asked.
   */
  private TodoRefused failed(
      OrchestrationRecord run, int n, String shown, String why, CommandRunner.Outcome outcome) {
    if (failures.failed(run.id(), "check " + shown, Commands.tail(outcome, QUESTION_LINES))) {
      end.request(
          Outcome.Ending.AWAITING,
          "the person is asked whether the run goes on" + " after its check kept failing");
      return refusedWithOutput(
          n,
          why
              + ", and it has failed as many times as the person"
              + " allows, so they are asked whether the run goes on. Your turn ends here,"
              + " and their answer arrives as your next message",
          outcome);
    }
    return refusedWithOutput(n, why, outcome);
  }

  private static TodoRefused refusedWithOutput(int n, String why, CommandRunner.Outcome outcome) {
    return new TodoRefused(
        "todo_write refused operation "
            + n
            + ": "
            + why
            + ". Nothing was changed.\n\n"
            + Commands.tail(outcome, TAIL_LINES));
  }
}
