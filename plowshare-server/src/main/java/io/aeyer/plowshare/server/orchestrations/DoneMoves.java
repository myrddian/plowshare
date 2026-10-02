package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.server.todos.StageRules;
import io.aeyer.plowshare.server.todos.TodoItem;
import io.aeyer.plowshare.server.todos.TodoOp;
import io.aeyer.plowshare.server.todos.TodoStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The first op in a {@code todo_write} batch that the board would let move a locked stage to
 * {@code done}, judged on the batch's own working copy — {@link StageChecks}' javadoc says why,
 * findings 2 and fix round 2 included. Shared by the check and the acceptance gate, so the two
 * never disagree about which move a batch makes: a second copy of this simulation is the one
 * that would miss the next consequence the board learns.
 */
final class DoneMoves {

    /**
     * @param index the op's index in the batch
     * @param stage the stage it moves to done
     * @param summary the summary it would be done with, stripped
     */
    record Move(int index, String stage, String summary) {}

    /**
     * A locked stage moving to in progress: a start from pending, or a return from done.
     *
     * @param index the op's index in the batch
     * @param stage the stage it moves
     * @param returning whether it is a return
     * @param returnsLeft the returns the run has left once this move stands
     */
    record Start(int index, String stage, boolean returning, int returnsLeft) {}

    /**
     * What a batch does to the run's stages, as the board would do it.
     *
     * @param done the done moves, exactly as {@link #all} has always answered — what the batch
     *     <em>proposes</em>, not what the board will accept: a move the board refuses for being
     *     not in progress, having a blank summary, or having open children is still listed here.
     *     {@code walk} does not re-model those {@code StageMoves}/{@code LockedMoves} rules
     *     (ruling F4), so a {@code stage.post} hook may be shown a done move that then does not
     *     happen
     * @param starts the starts and returns, in batch order
     * @param boardRefuses whether {@code StageMoves} will refuse the batch whole — the refusal
     *     {@link #all} already stopped at, and the ones it never modelled: a start while another
     *     stage is in progress, a return with none in progress, not in {@code may-return-to}, or
     *     with no returns left. A stage hook is asked about none of a batch the board refuses
     *     whole this way (spec 2026-09-28-hooks-reach-the-log §3) — but {@code boardRefuses} is
     *     silent on a {@code done} move the board refuses for its own reasons (see {@link #done}),
     *     so a {@code stage.post} hook can still be shown one of those
     */
    record Walk(List<Move> done, List<Start> starts, boolean boardRefuses) {
        static final Walk REFUSED = new Walk(List.of(), List.of(), true);

        Walk {
            done = List.copyOf(done);
            starts = List.copyOf(starts);
        }
    }

    private DoneMoves() {
    }

    /**
     * @param run the run whose stages the batch moves
     * @param list the conductor's list as it stood before the batch
     * @param ops the batch, in order
     * @param wanted which stages' done moves are sought
     * @return the first op the board would let move a {@code wanted} stage to done, if any
     */
    static Optional<Move> first(OrchestrationRecord run, List<TodoItem> list, List<TodoOp> ops,
            Predicate<StageRules.Stage> wanted) {
        return all(run, list, ops, wanted).stream().findFirst();
    }

    /**
     * Every op in the batch the board would let move a {@code wanted} stage to done, in batch
     * order. The acceptance gate needs all of them, not the first: one batch that marks spec done
     * and then walks on to mark acceptance done would otherwise have its spec registered and its
     * acceptance let through with nothing run (Task 10 review, finding 1).
     *
     * @param run the run whose stages the batch moves
     * @param list the conductor's list as it stood before the batch
     * @param ops the batch, in order
     * @param wanted which stages' done moves are sought
     * @return those moves, in the order the board would make them; none for a batch the board
     *     refuses whole because it starts a stage before the stages ahead of it are done
     */
    static List<Move> all(OrchestrationRecord run, List<TodoItem> list, List<TodoOp> ops,
            Predicate<StageRules.Stage> wanted) {
        return walk(run, list, ops, wanted).done();
    }

    /**
     * {@link #all}'s simulation, reporting the starts and returns beside the done moves, and
     * whether the board will refuse the batch whole. {@link Walk#done} is {@link #all}'s answer
     * for every batch: the refusals this adds only set {@link Walk#boardRefuses}, so the system
     * gates that read {@link #all} do not move (spec 2026-09-28-hooks-reach-the-log decision 2).
     */
    static Walk walk(OrchestrationRecord run, List<TodoItem> list, List<TodoOp> ops,
            Predicate<StageRules.Stage> wanted) {
        Set<String> gated = run.stages().stream().filter(wanted).map(StageRules.Stage::id)
                .collect(Collectors.toSet());
        Map<String, TodoItem> items = list.stream()
                .collect(Collectors.toMap(TodoItem::id, item -> item, (a, b) -> a));
        // Per-item status and summary, simulated forward through the ops in the same order
        // TodoBoard.apply itself applies them, starting from the list as it stood before this
        // batch — the two fields its own DONE rule reads (TodoBoard.java's effectiveSummary at
        // the locked-item branch of its Update case).
        Map<String, TodoStatus> simulatedStatus = new HashMap<>();
        Map<String, String> simulatedSummary = new HashMap<>();
        for (TodoItem item : items.values()) {
            simulatedStatus.put(item.id(), item.status());
            simulatedSummary.put(item.id(), item.summary());
        }
        // The run's stage order, and which locked item is which stage — both fixed for the whole
        // batch, since only a pre-seeded stage item ever carries a stageId — used to mirror
        // StageMoves' return consequence: every stage AFTER the one returned to is reset, by this
        // order, not by where its op happens to fall in the batch.
        List<String> order = run.stages().stream().map(StageRules.Stage::id).toList();
        Map<String, TodoItem> byStageId = items.values().stream()
                .filter(candidate -> candidate.locked() && candidate.stageId() != null)
                .collect(Collectors.toMap(TodoItem::stageId, candidate -> candidate, (a, b) -> a));
        List<Move> moves = new ArrayList<>();
        List<Start> starts = new ArrayList<>();
        int returnsUsed = run.returnsUsed();
        boolean boardRefuses = false;
        for (int i = 0; i < ops.size(); i++) {
            if (!(ops.get(i) instanceof TodoOp.Update update)) {
                continue;
            }
            TodoItem item = items.get(update.id());
            if (item == null) {
                continue;
            }
            TodoStatus statusBefore = simulatedStatus.get(update.id());
            String summaryBefore = simulatedSummary.get(update.id());
            // A START the board refuses refuses the whole batch (final review). The board lets a
            // stage start only once every stage ahead of it is done, which this simulation did not
            // model: a write that walks acceptance pending → in_progress → done while spec is
            // still in progress is refused whole by the board, but was a move here, and the gate
            // ran its commands — and registered spec's — for a write that then changed nothing.
            // Judged on the batch's own working copy, so a spec marked done earlier in the same
            // write still lets acceptance start after it.
            if (update.status() == TodoStatus.IN_PROGRESS && statusBefore == TodoStatus.PENDING
                    && item.locked() && item.stageId() != null
                    && !earlierAllDone(order, byStageId, simulatedStatus, item.stageId())) {
                return Walk.REFUSED;
            }
            // A START OR A RETURN, for stage.pre (spec 2026-09-28-hooks-reach-the-log §3), with
            // the refusals StageMoves.decide makes of it that the check never needed: judged,
            // like everything here, on the batch's working copy before this op applies.
            if (update.status() == TodoStatus.IN_PROGRESS && item.locked()
                    && item.stageId() != null && (statusBefore == TodoStatus.PENDING
                            || statusBefore == TodoStatus.DONE)) {
                Optional<TodoItem> running = order.stream().map(byStageId::get)
                        .filter(Objects::nonNull)
                        .filter(stageItem -> simulatedStatus.get(stageItem.id())
                                == TodoStatus.IN_PROGRESS)
                        .findFirst();
                boolean returning = statusBefore == TodoStatus.DONE;
                if (!returning && running.isPresent()) {
                    boardRefuses = true;
                } else if (returning) {
                    StageRules.Stage from = running.map(stageItem -> run.stages()
                            .get(order.indexOf(stageItem.stageId()))).orElse(null);
                    if (from == null || !from.mayReturnTo().contains(item.stageId())
                            || returnsUsed >= run.maxReturns()) {
                        boardRefuses = true;
                    } else {
                        returnsUsed++;
                    }
                }
                starts.add(new Start(i, item.stageId(), returning,
                        Math.max(0, run.maxReturns() - returnsUsed)));
            }
            if (update.status() == TodoStatus.DONE
                    && statusBefore == TodoStatus.IN_PROGRESS && item.locked()
                    && gated.contains(item.stageId())) {
                String candidate = update.summary() != null ? update.summary().strip()
                        : summaryBefore;
                if (candidate != null && !candidate.isBlank()) {
                    moves.add(new Move(i, item.stageId(), candidate));
                }
            }
            // A return (a locked stage moving DONE -> IN_PROGRESS): StageMoves.decide clears this
            // stage's own stale summary and resets every later stage, in run order, to pending
            // with no summary — applied here BEFORE this op's own status/summary fields, exactly
            // as TodoBoard.apply folds the consequence into its working copy before the update
            // that carried it is finished being applied.
            if (update.status() == TodoStatus.IN_PROGRESS && statusBefore == TodoStatus.DONE
                    && item.locked() && item.stageId() != null) {
                simulatedSummary.put(update.id(), null);
                int index = order.indexOf(item.stageId());
                if (index >= 0) {
                    for (int later = index + 1; later < order.size(); later++) {
                        TodoItem laterItem = byStageId.get(order.get(later));
                        if (laterItem != null
                                && simulatedStatus.get(laterItem.id()) != TodoStatus.PENDING) {
                            simulatedStatus.put(laterItem.id(), TodoStatus.PENDING);
                            simulatedSummary.put(laterItem.id(), null);
                        }
                    }
                }
            }
            if (update.status() != null) {
                simulatedStatus.put(update.id(), update.status());
            }
            if (update.summary() != null) {
                simulatedSummary.put(update.id(), update.summary().strip());
            }
        }
        return new Walk(moves, starts, boardRefuses);
    }

    private static boolean earlierAllDone(List<String> order, Map<String, TodoItem> byStageId,
            Map<String, TodoStatus> simulatedStatus, String stage) {
        for (String earlier : order.subList(0, Math.max(0, order.indexOf(stage)))) {
            TodoItem item = byStageId.get(earlier);
            if (item == null || simulatedStatus.get(item.id()) != TodoStatus.DONE) {
                return false;
            }
        }
        return true;
    }
}
