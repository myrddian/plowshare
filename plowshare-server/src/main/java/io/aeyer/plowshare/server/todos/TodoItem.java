package io.aeyer.plowshare.server.todos;

import java.time.Instant;

/**
 * One item on a conversation's todo list.
 *
 * @param parent the item this one sits under, or {@code null} at the top level
 * @param position order among its siblings, from zero
 * @param summary what was done, or {@code null}; slice 3 requires one when a stage becomes done
 * @param locked written only by an orchestration; a model cannot drop, move or rename it
 * @param stageId the orchestration stage this item is, or {@code null}
 */
public record TodoItem(String id, String conversation, String parent, int position, String text,
        TodoStatus status, String summary, boolean locked, String stageId, Instant updatedAt) {

    public TodoItem withStatus(TodoStatus to, Instant at) {
        return new TodoItem(id, conversation, parent, position, text, to, summary, locked, stageId, at);
    }

    public TodoItem withText(String to, Instant at) {
        return new TodoItem(id, conversation, parent, position, to, status, summary, locked, stageId, at);
    }

    public TodoItem withSummary(String to, Instant at) {
        return new TodoItem(id, conversation, parent, position, text, status, to, locked, stageId, at);
    }

    public TodoItem withPosition(int to, Instant at) {
        return new TodoItem(id, conversation, parent, to, text, status, summary, locked, stageId, at);
    }
}
