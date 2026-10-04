package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.archive.Conversations;
import io.aeyer.plowshare.server.todos.TodoLists;
import java.util.Map;
import java.util.Objects;

/**
 * {@code todos.read} — a conversation's list. The same standing as {@code conversation.turns}: a
 * socket that may read a conversation's history may read its list, and the existence rule is {@link
 * Conversations}', not restated here.
 */
public final class TodosReadHandler implements FrameHandler {

  private final TodoLists todos;
  private final Conversations conversations;

  public TodosReadHandler(TodoLists todos, Conversations conversations) {
    this.todos = Objects.requireNonNull(todos, "todos");
    this.conversations = Objects.requireNonNull(conversations, "conversations");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String conversation =
        Payloads.required(
            payload,
            "conversation",
            FrameTypes.TODOS_READ,
            "the conversation whose todo list to read. Nothing was read.");
    conversations.requireExistsOrThereIsNo(conversation, "todo list to read");
    return Outcome.ok(todos.list(conversation).stream().map(TodoView::of).toList());
  }
}
