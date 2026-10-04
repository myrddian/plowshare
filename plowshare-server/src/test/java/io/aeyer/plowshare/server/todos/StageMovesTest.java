package io.aeyer.plowshare.server.todos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StageMovesTest {

  private static final Instant T0 = Instant.parse("2026-09-14T09:00:00Z");

  private final AtomicInteger counted = new AtomicInteger();

  private StageRules rules(int returnsUsed) {
    return new StageRules(
        List.of(
            new StageRules.Stage("goal", List.of()),
            new StageRules.Stage("code", List.of()),
            new StageRules.Stage("review", List.of("code"))),
        3,
        returnsUsed,
        counted::incrementAndGet);
  }

  private static TodoItem stage(String id, int position, TodoStatus status, String summary) {
    return new TodoItem("td_" + id, "cnv_1", null, position, id, status, summary, true, id, T0);
  }

  private static List<TodoItem> list(TodoStatus goal, TodoStatus code, TodoStatus review) {
    return List.of(
        stage("goal", 0, goal, goal == TodoStatus.DONE ? "g" : null),
        stage("code", 1, code, code == TodoStatus.DONE ? "c" : null),
        stage("review", 2, review, null));
  }

  private LockedMoves.Decision decide(
      int returnsUsed, List<TodoItem> list, String id, TodoStatus to, String summary) {
    TodoItem current = list.stream().filter(i -> i.stageId().equals(id)).findFirst().orElseThrow();
    return new StageMoves(conversation -> Optional.of(rules(returnsUsed)))
        .decide(current, to, summary, list);
  }

  private static String why(LockedMoves.Decision decision) {
    return assertInstanceOf(LockedMoves.Refused.class, decision).why();
  }

  @Test
  void the_first_stage_not_done_may_start() {
    assertInstanceOf(
        LockedMoves.Allowed.class,
        decide(
            0,
            list(TodoStatus.PENDING, TodoStatus.PENDING, TodoStatus.PENDING),
            "goal",
            TodoStatus.IN_PROGRESS,
            null));
    assertInstanceOf(
        LockedMoves.Allowed.class,
        decide(
            0,
            list(TodoStatus.DONE, TodoStatus.PENDING, TodoStatus.PENDING),
            "code",
            TodoStatus.IN_PROGRESS,
            null));
  }

  @Test
  void a_later_stage_may_not_start_early_and_two_may_not_run_at_once() {
    assertEquals(
        "stage 'code' cannot start before 'goal' is done",
        why(
            decide(
                0,
                list(TodoStatus.PENDING, TodoStatus.PENDING, TodoStatus.PENDING),
                "code",
                TodoStatus.IN_PROGRESS,
                null)));
    assertEquals(
        "stage 'code' is in progress; finish it or return from it before starting another",
        why(
            decide(
                0,
                list(TodoStatus.DONE, TodoStatus.IN_PROGRESS, TodoStatus.PENDING),
                "review",
                TodoStatus.IN_PROGRESS,
                null)));
  }

  @Test
  void a_stage_in_progress_is_done_only_with_a_summary() {
    assertInstanceOf(
        LockedMoves.Allowed.class,
        decide(
            0,
            list(TodoStatus.IN_PROGRESS, TodoStatus.PENDING, TodoStatus.PENDING),
            "goal",
            TodoStatus.DONE,
            "restated"));
    assertEquals(
        "stage 'goal' needs a summary of what was done before it is done. "
            + "Put status:done and a nonblank summary in the SAME update operation; "
            + "adding a summary in a later operation does not repair this atomic batch",
        why(
            decide(
                0,
                list(TodoStatus.IN_PROGRESS, TodoStatus.PENDING, TodoStatus.PENDING),
                "goal",
                TodoStatus.DONE,
                "  ")));
    assertEquals(
        "stage 'goal' needs a summary of what was done before it is done. "
            + "Put status:done and a nonblank summary in the SAME update operation; "
            + "adding a summary in a later operation does not repair this atomic batch",
        why(
            decide(
                0,
                list(TodoStatus.IN_PROGRESS, TodoStatus.PENDING, TodoStatus.PENDING),
                "goal",
                TodoStatus.DONE,
                null)));
  }

  @Test
  void a_pending_stage_is_not_done_without_being_started() {
    assertEquals(
        "stage 'goal' must be in progress before it is done",
        why(
            decide(
                0,
                list(TodoStatus.PENDING, TodoStatus.PENDING, TodoStatus.PENDING),
                "goal",
                TodoStatus.DONE,
                "x")));
  }

  @Test
  void a_stage_goes_back_to_pending_only_by_a_return() {
    assertEquals(
        "a stage goes back to pending only when an earlier stage is returned to",
        why(
            decide(
                0,
                list(TodoStatus.IN_PROGRESS, TodoStatus.PENDING, TodoStatus.PENDING),
                "goal",
                TodoStatus.PENDING,
                null)));
  }

  @Test
  void a_done_stage_does_not_go_back_to_pending_directly() {
    assertEquals(
        "a stage goes back to pending only when an earlier stage is returned to",
        why(
            decide(
                0,
                list(TodoStatus.DONE, TodoStatus.DONE, TodoStatus.IN_PROGRESS),
                "goal",
                TodoStatus.PENDING,
                null)));
  }

  @Test
  void a_return_to_a_named_stage_resets_every_later_stage_and_counts() {
    LockedMoves.Decision decision =
        decide(
            1,
            list(TodoStatus.DONE, TodoStatus.DONE, TodoStatus.IN_PROGRESS),
            "code",
            TodoStatus.IN_PROGRESS,
            null);

    LockedMoves.Allowed allowed = assertInstanceOf(LockedMoves.Allowed.class, decision);
    assertEquals(
        List.of("td_code", "td_review"),
        allowed.consequences().stream().map(TodoItem::id).toList());
    assertEquals(TodoStatus.PENDING, allowed.consequences().get(1).status());
    allowed.effects().forEach(Runnable::run);
    assertEquals(1, counted.get());
  }

  @Test
  void a_return_clears_the_summaries_it_reopens() {
    LockedMoves.Decision decision =
        decide(
            0,
            list(TodoStatus.DONE, TodoStatus.DONE, TodoStatus.IN_PROGRESS),
            "code",
            TodoStatus.IN_PROGRESS,
            null);

    LockedMoves.Allowed allowed = assertInstanceOf(LockedMoves.Allowed.class, decision);
    assertEquals(
        List.of("td_code", "td_review"),
        allowed.consequences().stream().map(TodoItem::id).toList());
    assertTrue(
        allowed.consequences().stream().allMatch(item -> item.summary() == null),
        "every consequence of a return must carry a null summary");
  }

  @Test
  void a_return_is_refused_when_not_named_when_nothing_is_in_progress_or_when_returns_are_spent() {
    assertEquals(
        "stage 'review' does not list 'goal' in may-return-to",
        why(
            decide(
                0,
                list(TodoStatus.DONE, TodoStatus.DONE, TodoStatus.IN_PROGRESS),
                "goal",
                TodoStatus.IN_PROGRESS,
                null)));
    assertEquals(
        "no stage is in progress to return from",
        why(
            decide(
                0,
                list(TodoStatus.DONE, TodoStatus.DONE, TodoStatus.DONE),
                "code",
                TodoStatus.IN_PROGRESS,
                null)));
    assertEquals(
        "this orchestration has used all 3 of its returns",
        why(
            decide(
                3,
                list(TodoStatus.DONE, TodoStatus.DONE, TodoStatus.IN_PROGRESS),
                "code",
                TodoStatus.IN_PROGRESS,
                null)));
  }

  @Test
  void the_last_return_is_allowed_and_the_one_after_is_refused() {
    LockedMoves.Decision lastAllowed =
        decide(
            2,
            list(TodoStatus.DONE, TodoStatus.DONE, TodoStatus.IN_PROGRESS),
            "code",
            TodoStatus.IN_PROGRESS,
            null);
    LockedMoves.Allowed allowed = assertInstanceOf(LockedMoves.Allowed.class, lastAllowed);
    assertEquals(1, allowed.effects().size());

    assertEquals(
        "this orchestration has used all 3 of its returns",
        why(
            decide(
                3,
                list(TodoStatus.DONE, TodoStatus.DONE, TodoStatus.IN_PROGRESS),
                "code",
                TodoStatus.IN_PROGRESS,
                null)));
  }

  @Test
  void two_items_claiming_one_stage_are_refused() {
    List<TodoItem> duplicated =
        List.of(
            stage("goal", 0, TodoStatus.PENDING, null),
            stage("goal", 1, TodoStatus.PENDING, null),
            stage("code", 2, TodoStatus.PENDING, null),
            stage("review", 3, TodoStatus.PENDING, null));
    assertEquals(
        "stage 'goal' appears more than once on this list",
        why(decide(0, duplicated, "goal", TodoStatus.IN_PROGRESS, null)));
  }

  @Test
  void an_item_that_is_not_a_stage_here_or_a_conversation_with_no_rules_is_refused() {
    TodoItem stray =
        new TodoItem("td_x", "cnv_1", null, 5, "x", TodoStatus.PENDING, null, true, "deploy", T0);
    assertEquals(
        "td_x is not a stage of this orchestration",
        why(
            new StageMoves(c -> Optional.of(rules(0)))
                .decide(stray, TodoStatus.IN_PROGRESS, null, List.of(stray))));
    assertEquals(
        LockedMoves.REFUSE_ALL.decide(stray, TodoStatus.IN_PROGRESS, null, List.of(stray)),
        new StageMoves(c -> Optional.empty())
            .decide(stray, TodoStatus.IN_PROGRESS, null, List.of(stray)));
  }

  // --- rule 1: a stage with children is done only when they are ------------------------------

  private static TodoItem child(String text, int position, TodoStatus status) {
    return new TodoItem(
        "td_" + text, "cnv_1", "td_phases", position, text, status, null, false, null, T0);
  }

  private static StageRules phasesRules() {
    return new StageRules(
        List.of(
            new StageRules.Stage("plan", List.of()),
            new StageRules.Stage("phases", List.of(), false, null, true),
            new StageRules.Stage("review", List.of("phases"))),
        2,
        0,
        () -> {});
  }

  /**
   * Measured 2026-09-28 22:31:16, orc_31893856D8F462A1: `phases` says "done when every phase child
   * is done", and the board let it move to done with three children pending. The README phase never
   * ran.
   */
  @Test
  void the_measured_phases_move_with_three_children_pending_is_refused() {
    List<TodoItem> list =
        List.of(
            stage("plan", 0, TodoStatus.DONE, "p"),
            stage("phases", 1, TodoStatus.IN_PROGRESS, null),
            stage("review", 2, TodoStatus.PENDING, null),
            child("utils", 0, TodoStatus.DONE),
            child("inventory", 1, TodoStatus.DONE),
            child("character", 2, TodoStatus.DONE),
            child("save-load", 3, TodoStatus.PENDING),
            child("combat", 4, TodoStatus.IN_PROGRESS),
            child("readme", 5, TodoStatus.PENDING));
    TodoItem phases = list.get(1);

    LockedMoves.Decision decision =
        new StageMoves(conversation -> Optional.of(phasesRules()))
            .decide(phases, TodoStatus.DONE, "phases 1-3 built", list);

    assertEquals("`phases` has 3 not done: save-load, combat, readme", why(decision));
  }

  @Test
  void children_done_or_dropped_let_the_stage_be_done() {
    List<TodoItem> list =
        List.of(
            stage("plan", 0, TodoStatus.DONE, "p"),
            stage("phases", 1, TodoStatus.IN_PROGRESS, null),
            stage("review", 2, TodoStatus.PENDING, null),
            child("utils", 0, TodoStatus.DONE),
            child("readme", 1, TodoStatus.DROPPED));

    assertInstanceOf(
        LockedMoves.Allowed.class,
        new StageMoves(conversation -> Optional.of(phasesRules()))
            .decide(list.get(1), TodoStatus.DONE, "utils built; readme not started", list));
  }

  private static TodoItem childOf(String parent, String text, int position, TodoStatus status) {
    return new TodoItem(
        "td_" + text, "cnv_1", "td_" + parent, position, text, status, null, false, null, T0);
  }

  /**
   * Measured 2026-09-29/30, orc_318DFD3782228160: the root added its phases under `plan`, ran two,
   * and was refused eleven times "`plan` has N not done: ..." before it dropped them and re-added
   * them under `phases`. The refusal names the stage that holds phases.
   */
  @Test
  void children_left_under_a_stage_that_does_not_hold_phases_are_pointed_at_the_one_that_does() {
    List<TodoItem> list =
        List.of(
            stage("plan", 0, TodoStatus.IN_PROGRESS, null),
            stage("phases", 1, TodoStatus.PENDING, null),
            stage("review", 2, TodoStatus.PENDING, null),
            childOf("plan", "engine", 0, TodoStatus.DONE),
            childOf("plan", "combat", 1, TodoStatus.DONE),
            childOf("plan", "bullet", 2, TodoStatus.PENDING),
            childOf("plan", "readme", 3, TodoStatus.PENDING));

    LockedMoves.Decision decision =
        new StageMoves(conversation -> Optional.of(phasesRules()))
            .decide(list.get(0), TodoStatus.DONE, "plan.md written", list);

    assertEquals(
        "`plan` has 2 not done: bullet, readme. Phase items belong under `phases`,"
            + " not `plan`: drop them here and add them there",
        why(decision));
  }

  @Test
  void a_definition_with_no_stage_holding_phases_is_refused_as_before() {
    List<TodoItem> list =
        List.of(
            stage("goal", 0, TodoStatus.DONE, "g"),
            stage("code", 1, TodoStatus.IN_PROGRESS, null),
            stage("review", 2, TodoStatus.PENDING, null),
            childOf("code", "parser", 0, TodoStatus.PENDING));

    assertEquals(
        "`code` has 1 not done: parser", why(decide(0, list, "code", TodoStatus.DONE, "c")));
  }
}
