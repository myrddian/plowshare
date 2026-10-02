package io.aeyer.plowshare.server.todos;

/** One change in a {@code todo_write} batch. A null field on {@link Update} means unchanged. */
public sealed interface TodoOp {

    /** @param parent an id already on the list, or {@code null} for the top level */
    record Add(String text, String parent) implements TodoOp {}

    record Update(String id, TodoStatus status, String text, String summary) implements TodoOp {}

    /** @param position the index among its siblings to move to, from zero */
    record Move(String id, int position) implements TodoOp {}
}
