package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.RunHooks;
import io.aeyer.plowshare.server.agents.TodoTools;
import io.aeyer.plowshare.server.hooks.Gate;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.Stage;
import io.aeyer.plowshare.server.hooks.StageDone;
import io.aeyer.plowshare.server.hooks.StageShown;
import io.aeyer.plowshare.server.hooks.StageStart;
import io.aeyer.plowshare.server.todos.StageRules;
import io.aeyer.plowshare.server.todos.TodoItem;
import io.aeyer.plowshare.server.todos.TodoLists;
import io.aeyer.plowshare.server.todos.TodoOp;
import io.aeyer.plowshare.server.todos.TodoRefused;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@code stage.pre} and {@code stage.post} in front of the board, asked of the run's chain: its
 * profile's harness hooks, then the project's. The user layer, after every system gate (spec
 * 2026-09-28-hooks-reach-the-log decisions 2 and 10).
 *
 * <p><b>Last, so it sees only what the harness allows.</b> {@code PhaseRunsGate}, {@code
 * AcceptanceGate} and {@link StageChecks} have refused the batch or let it through, rewriting a
 * done move's summary as they go, before this is asked; {@code stage.post}'s summary is the one the
 * board will store, and a checked stage's check has passed. A batch the board itself will refuse
 * ({@link DoneMoves.Walk#boardRefuses}) asks no hook at all.
 *
 * <p><b>One stage hook per move, in batch order; the first denial refuses the write</b>, all or
 * none as the board is, naming the stage, the stage hook and the hook. Records are parked with the
 * run's {@link RunHooks}, so they land in the conductor's log after this {@code todo_write}'s
 * result, denial included. Notes accumulate and reach the result through {@link
 * TodoTools.Checked#notes} once the batch commits. A broken hook or a chain that throws denies
 * (§2.4): the run's {@link RunHooks} answers that as a denial, and this refuses on it like any.
 *
 * <p><b>Shown a proposal, not a certainty</b> (ruling F4). {@link DoneMoves#walk} does not re-model
 * the board's own refusals of a done move — not in progress, a blank summary, open children — so
 * {@code stage.post} may be asked about a move the board then refuses: a denial only adds a
 * refusal, but a note or a record may be left for a move that did not happen. Copying {@code
 * StageMoves}' rules here would be the copy that drifts.
 *
 * <p><b>Accepted cost: a denial after a system gate's side effects.</b> Standing last means the
 * system gates have done their work before any hook here is asked, and a denial refuses the whole
 * write, so what they did stays behind for moves that do not happen:
 *
 * <ul>
 *   <li>a {@code stage.post} denial of a root run's acceptance-stage done move leaves the commands
 *       {@code AcceptanceGate} registered, or the person it asked;
 *   <li>a {@code stage.pre} denial of a start in the same batch as such a done move leaves that
 *       move's registrations the same way;
 *   <li>any denial after {@link StageChecks} ran a checked stage's check leaves that check run, and
 *       what it cost, spent on a move that does not happen.
 * </ul>
 *
 * Nothing here undoes them: the system gates run first and are not the user layer's to reverse
 * (spec 2026-09-28-hooks-reach-the-log decision 2).
 */
public final class StageHooks implements TodoTools.BeforeApply {

  private final Function<String, Optional<OrchestrationRecord>> runByConversation;
  private final TodoLists todos;
  private final Function<String, Optional<OrchestrationChecks.Check>> checks;
  private final RunHooks hooks;

  /**
   * @param runByConversation the run a conductor conversation belongs to
   * @param todos the conductor's list, as it stood before the batch
   * @param checks a run's check, for {@code stage.post}'s {@code check}
   * @param hooks the conductor run's in-turn gates
   */
  public StageHooks(
      Function<String, Optional<OrchestrationRecord>> runByConversation,
      TodoLists todos,
      Function<String, Optional<OrchestrationChecks.Check>> checks,
      RunHooks hooks) {
    this.runByConversation = Objects.requireNonNull(runByConversation, "runByConversation");
    this.todos = Objects.requireNonNull(todos, "todos");
    this.checks = Objects.requireNonNull(checks, "checks");
    this.hooks = Objects.requireNonNull(hooks, "hooks");
  }

  @Override
  public List<TodoOp> check(String conversation, List<TodoOp> ops, Home home) {
    return checked(conversation, ops, home).ops();
  }

  @Override
  public TodoTools.Checked checked(String conversation, List<TodoOp> ops, Home home) {
    OrchestrationRecord run =
        runByConversation
            .apply(conversation)
            .filter(found -> !found.state().terminal())
            .orElse(null);
    if (run == null) {
      return new TodoTools.Checked(ops, List.of());
    }
    List<TodoItem> list = todos.list(conversation);
    DoneMoves.Walk walk = DoneMoves.walk(run, list, ops, stage -> true);
    if (walk.boardRefuses()) {
      return new TodoTools.Checked(ops, List.of());
    }
    List<String> order = run.stages().stream().map(StageRules.Stage::id).toList();
    Map<String, TodoItem> byStage =
        list.stream()
            .filter(item -> item.locked() && item.stageId() != null)
            .collect(Collectors.toMap(TodoItem::stageId, item -> item, (a, b) -> a));
    Map<Integer, DoneMoves.Start> starts =
        walk.starts().stream().collect(Collectors.toMap(DoneMoves.Start::index, start -> start));
    Map<Integer, DoneMoves.Move> dones =
        walk.done().stream().collect(Collectors.toMap(DoneMoves.Move::index, move -> move));
    List<String> notes = new ArrayList<>();
    for (int i = 0; i < ops.size(); i++) {
      DoneMoves.Start start = starts.get(i);
      DoneMoves.Move done = dones.get(i);
      if (start != null) {
        Gate gate =
            hooks.recorded(
                hooks.stagePre(
                    about(run, start.stage()),
                    new StageStart(
                        shown(start.stage(), order, byStage),
                        start.returning(),
                        start.returnsLeft())));
        notes.addAll(passed(gate, i, start.stage(), Stage.STAGE_PRE));
      } else if (done != null) {
        StageRules.Stage stage = run.stages().get(order.indexOf(done.stage()));
        List<String> check =
            stage.checked()
                ? checks.apply(run.id()).map(OrchestrationChecks.Check::argv).orElse(null)
                : null;
        Gate gate =
            hooks.recorded(
                hooks.stagePost(
                    about(run, done.stage()),
                    new StageDone(shown(done.stage(), order, byStage), done.summary(), check)));
        notes.addAll(passed(gate, i, done.stage(), Stage.STAGE_POST));
      }
    }
    return new TodoTools.Checked(ops, notes);
  }

  /** The gate's notes, or the refusal of the whole write when it denied. */
  private static List<String> passed(Gate gate, int index, String stage, Stage at) {
    if (gate.isDenied()) {
      throw new TodoRefused(
          "todo_write refused operation "
              + (index + 1)
              + ": stage '"
              + stage
              + "' was refused by a hook on "
              + at.wireName()
              + ": "
              + gate.denied()
              + ". Nothing was changed.");
    }
    return gate.notes();
  }

  private static StageShown shown(String stage, List<String> order, Map<String, TodoItem> byStage) {
    TodoItem item = byStage.get(stage);
    return new StageShown(
        stage, item == null ? stage : item.text(), order.indexOf(stage), order.size());
  }

  private static HookContext.Orchestration about(OrchestrationRecord run, String stage) {
    return new HookContext.Orchestration(run.id(), run.definitionName(), stage);
  }
}
