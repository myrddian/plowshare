package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.archive.Conversations;
import io.aeyer.plowshare.server.todos.TodoLists;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Conversation todo lists. {@code todos.changed} is a push, not a request type this area routes. */
@Component
public class TodoFrames implements FrameArea {

    private final TodoLists todos;
    private final Conversations conversations;

    public TodoFrames(TodoLists todos, Conversations conversations) {
        this.todos = Objects.requireNonNull(todos, "todos");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
    }

    @Override
    public Map<String, FrameHandler> frames() {
        return Map.of(FrameTypes.TODOS_READ, new TodosReadHandler(todos, conversations));
    }
}
