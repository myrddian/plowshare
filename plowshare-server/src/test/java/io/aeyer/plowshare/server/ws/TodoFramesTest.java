package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.archive.ArchiveException;
import io.aeyer.plowshare.server.archive.Conversations;
import io.aeyer.plowshare.server.todos.TodoItem;
import io.aeyer.plowshare.server.todos.TodoLists;
import io.aeyer.plowshare.server.todos.TodoStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TodoFramesTest {

    private TodoLists todos;
    private Conversations conversations;
    private FrameRouter router;

    @BeforeEach
    void setUp() {
        todos = mock(TodoLists.class);
        conversations = mock(Conversations.class);
        router = new FrameRoutingConfig().frameRouter(List.of(new TodoFrames(todos, conversations)));
    }

    @Test
    void a_conversations_list_is_read_by_its_id_as_views_not_the_records() {
        TodoItem item = new TodoItem("td_1", "cnv_1", null, 0, "goal", TodoStatus.PENDING, null,
                true, "goal", Instant.parse("2026-09-13T09:00:00Z"));
        when(todos.list("cnv_1")).thenReturn(List.of(item));

        Outcome outcome = router.route(FrameParity.frame(FrameTypes.TODOS_READ,
                "{\"conversation\":\"cnv_1\"}"), new Asking("s"));

        assertEquals(Code.OK, outcome.code());
        assertEquals(List.of(TodoView.of(item)), outcome.payload());
    }

    @Test
    void a_status_is_serialized_by_its_wire_name_not_the_enum_constant() throws Exception {
        TodoItem item = new TodoItem("td_1", "cnv_1", null, 0, "goal", TodoStatus.IN_PROGRESS, null,
                true, "goal", Instant.parse("2026-09-13T09:00:00Z"));
        when(todos.list("cnv_1")).thenReturn(List.of(item));

        Outcome outcome = router.route(FrameParity.frame(FrameTypes.TODOS_READ,
                "{\"conversation\":\"cnv_1\"}"), new Asking("s"));

        String json = FrameJson.answering().writeValueAsString(outcome.payload());
        assertTrue(json.contains("\"status\":\"in_progress\""), json);
    }

    @Test
    void a_frame_naming_no_conversation_is_refused() {
        Outcome outcome = router.route(FrameParity.frame(FrameTypes.TODOS_READ, "{}"), new Asking("s"));
        assertEquals(Code.BAD_REQUEST, outcome.code());
    }

    @Test
    void a_conversation_that_does_not_exist_has_no_list() {
        doThrow(new ArchiveException("no conversation has the id cnv_x, so there is no todo list to read"))
                .when(conversations).requireExistsOrThereIsNo("cnv_x", "todo list to read");
        Outcome outcome = router.route(FrameParity.frame(FrameTypes.TODOS_READ,
                "{\"conversation\":\"cnv_x\"}"), new Asking("s"));
        assertEquals(Code.NOT_FOUND, outcome.code());
    }
}
