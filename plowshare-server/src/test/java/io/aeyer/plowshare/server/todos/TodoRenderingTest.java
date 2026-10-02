package io.aeyer.plowshare.server.todos;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TodoRenderingTest {

    private static final Instant T0 = Instant.parse("2026-09-13T09:00:00Z");

    @Test
    void items_are_shown_as_a_tree_with_their_ids_status_and_summary() {
        List<TodoItem> list = List.of(
                new TodoItem("td_1", "c", null, 0, "goal", TodoStatus.DONE, "restated", true, "goal", T0),
                new TodoItem("td_2", "c", null, 1, "spec", TodoStatus.IN_PROGRESS, null, true, "spec", T0),
                new TodoItem("td_3", "c", "td_2", 0, "read the module", TodoStatus.PENDING, null, false, null, T0),
                new TodoItem("td_4", "c", null, 2, "old idea", TodoStatus.DROPPED, null, false, null, T0));

        assertEquals("""
                [x] td_1 goal (stage) — restated
                [>] td_2 spec (stage)
                  [ ] td_3 read the module
                [-] td_4 old idea
                """, TodoRendering.compact(list));
    }

    @Test
    void an_empty_list_renders_as_a_sentence_not_nothing() {
        assertEquals("The todo list is empty.\n", TodoRendering.compact(List.of()));
    }
}
