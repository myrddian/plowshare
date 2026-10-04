package io.aeyer.plowshare.server.todos;

import java.util.List;

/**
 * Told after a {@code todo_write} batch commits, with every item whose status the batch changed — a
 * consequence of a stage return included — and the list as it now stands. The orchestration
 * record's source for stage moves (spec 2026-09-28 §2). Must not throw; {@link TodoBoard} guards it
 * anyway, since the batch has already committed.
 */
@FunctionalInterface
public interface StatusMoves {

  StatusMoves NONE = (conversation, moved, list) -> {};

  /** One item's status, before the batch and after it. */
  record Move(TodoItem before, TodoItem after) {}

  void moved(String conversation, List<Move> moved, List<TodoItem> list);
}
