package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.todos.TodoItem;
import java.time.Instant;

/**
 * One todo item on the wire, for {@code todos.read}. The frame equivalent of {@link
 * io.aeyer.plowshare.server.api.TurnView}: a view over the record rather than the record itself, so
 * the wire form can differ from the storage shape without either having to move to fit the other.
 *
 * <h2>Why {@code status} is not {@link TodoItem#status()}</h2>
 *
 * <p>{@code TodoStatus} is a Java enum; serialising it directly would send its constant name
 * ({@code IN_PROGRESS}) rather than the wire name every other surface already agrees on ({@code
 * in_progress}) -- the same one a model reads and writes through {@code todo_read} and {@code
 * todo_write}, and the same one {@link io.aeyer.plowshare.server.todos.TodoStore} persists. {@link
 * #of} calls {@link io.aeyer.plowshare.server.todos.TodoStatus#wire()} so a client reading this
 * frame and a model reading the tool's own rendering see the same four strings.
 */
public record TodoView(
    String id,
    String parent,
    int position,
    String text,
    String status,
    String summary,
    boolean locked,
    String stage,
    Instant updatedAt) {

  /** This view of one row, as {@link io.aeyer.plowshare.server.todos.TodoLists#list} returns it. */
  public static TodoView of(TodoItem item) {
    return new TodoView(
        item.id(),
        item.parent(),
        item.position(),
        item.text(),
        item.status().wire(),
        item.summary(),
        item.locked(),
        item.stageId(),
        item.updatedAt());
  }
}
