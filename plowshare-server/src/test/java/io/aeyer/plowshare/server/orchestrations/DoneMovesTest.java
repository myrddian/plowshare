package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.agents.OrchestrationDefinition.Tier;
import io.aeyer.plowshare.server.todos.StageRules;
import io.aeyer.plowshare.server.todos.TodoItem;
import io.aeyer.plowshare.server.todos.TodoOp;
import io.aeyer.plowshare.server.todos.TodoStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The moves a batch makes, as the board would make them — now the starts and returns too. */
class DoneMovesTest {

    private static final String CONV = "cnv_conductor";

    private static OrchestrationRecord run(int maxReturns, int returnsUsed) {
        return new OrchestrationRecord("orc_1", "code_implementation", Tier.PROJECT, "sha256:x",
                "src", "test",
                List.of(new StageRules.Stage("design", List.of()),
                        new StageRules.Stage("code", List.of(), true),
                        new StageRules.Stage("review", List.of("code"))),
                maxReturns, returnsUsed, "story", CONV, null, "interlocutor", "enzo", null, null, 0,
                null, OrchestrationState.RUNNING, null, null, null, 0, 0, false, null,
                Instant.EPOCH, null);
    }

    private static TodoItem item(String stage, TodoStatus status, String summary) {
        return new TodoItem("td_" + stage, CONV, null, 0, stage, status, summary, true, stage,
                Instant.EPOCH);
    }

    private static TodoOp.Update to(String stage, TodoStatus status, String summary) {
        return new TodoOp.Update("td_" + stage, status, null, summary);
    }

    @Test
    void a_start_and_its_done_move_in_one_write_are_both_seen_in_batch_order() {
        List<TodoItem> list = List.of(item("design", TodoStatus.DONE, "d"),
                item("code", TodoStatus.PENDING, null), item("review", TodoStatus.PENDING, null));

        DoneMoves.Walk walk = DoneMoves.walk(run(2, 0), list,
                List.of(to("code", TodoStatus.IN_PROGRESS, null),
                        to("code", TodoStatus.DONE, "built it")), stage -> true);

        assertEquals(List.of(new DoneMoves.Start(0, "code", false, 2)), walk.starts());
        assertEquals(List.of(new DoneMoves.Move(1, "code", "built it")), walk.done());
        assertFalse(walk.boardRefuses());
    }

    @Test
    void a_return_says_how_many_returns_are_left_once_it_stands() {
        List<TodoItem> list = List.of(item("design", TodoStatus.DONE, "d"),
                item("code", TodoStatus.DONE, "c"), item("review", TodoStatus.IN_PROGRESS, null));

        DoneMoves.Walk walk = DoneMoves.walk(run(2, 1), list,
                List.of(to("code", TodoStatus.IN_PROGRESS, null)), stage -> true);

        assertEquals(List.of(new DoneMoves.Start(0, "code", true, 0)), walk.starts());
        assertFalse(walk.boardRefuses());
    }

    @Test
    void the_moves_stage_moves_refuses_are_marked_for_the_hooks_and_leave_the_done_moves_alone() {
        List<TodoItem> busy = List.of(item("design", TodoStatus.DONE, "d"),
                item("code", TodoStatus.IN_PROGRESS, "c"), item("review", TodoStatus.PENDING, null));
        List<TodoItem> reviewing = List.of(item("design", TodoStatus.DONE, "d"),
                item("code", TodoStatus.DONE, "c"), item("review", TodoStatus.IN_PROGRESS, null));

        DoneMoves.Walk startWhileBusy = DoneMoves.walk(run(2, 0), busy,
                List.of(to("review", TodoStatus.IN_PROGRESS, null)), stage -> true);
        DoneMoves.Walk returnNotAllowed = DoneMoves.walk(run(2, 0), reviewing,
                List.of(to("design", TodoStatus.IN_PROGRESS, null)), stage -> true);
        DoneMoves.Walk noReturnsLeft = DoneMoves.walk(run(1, 1), reviewing,
                List.of(to("code", TodoStatus.IN_PROGRESS, null)), stage -> true);
        DoneMoves.Walk doneThenBadReturn = DoneMoves.walk(run(2, 0), busy,
                List.of(to("code", TodoStatus.DONE, null), to("design", TodoStatus.IN_PROGRESS, null)),
                stage -> true);

        assertTrue(startWhileBusy.boardRefuses(), "review cannot start while code is in progress");
        assertTrue(returnNotAllowed.boardRefuses(), "review does not list design in may-return-to");
        assertTrue(noReturnsLeft.boardRefuses(), "no returns left");
        assertTrue(doneThenBadReturn.boardRefuses());
        assertEquals(List.of(new DoneMoves.Move(0, "code", "c")), doneThenBadReturn.done(),
                "the done moves are what they always were: the system gates do not move");
        assertEquals(doneThenBadReturn.done(), DoneMoves.all(run(2, 0), busy,
                List.of(to("code", TodoStatus.DONE, null), to("design", TodoStatus.IN_PROGRESS, null)),
                stage -> true));
    }

    @Test
    void a_start_before_the_stages_ahead_are_done_refuses_the_whole_walk_as_before() {
        List<TodoItem> list = List.of(item("design", TodoStatus.IN_PROGRESS, "d"),
                item("code", TodoStatus.PENDING, null), item("review", TodoStatus.PENDING, null));

        DoneMoves.Walk walk = DoneMoves.walk(run(2, 0), list,
                List.of(to("code", TodoStatus.IN_PROGRESS, null)), stage -> true);

        assertTrue(walk.boardRefuses());
        assertEquals(List.of(), walk.done());
        assertEquals(List.of(), walk.starts());
    }
}
