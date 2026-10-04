package io.aeyer.plowshare.server.todos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one text form of a list, shared by the notice and {@code todo_read} so a model reads the same
 * thing from both. Ids are shown because {@code todo_write} addresses items by id.
 */
public final class TodoRendering {

  private TodoRendering() {}

  public static String compact(List<TodoItem> list) {
    if (list.isEmpty()) {
      return "The todo list is empty.\n";
    }
    Map<String, List<TodoItem>> children = new LinkedHashMap<>();
    for (TodoItem item : list) {
      children.computeIfAbsent(item.parent(), k -> new ArrayList<>()).add(item);
    }
    children
        .values()
        .forEach(siblings -> siblings.sort(java.util.Comparator.comparingInt(TodoItem::position)));
    StringBuilder out = new StringBuilder();
    write(out, children, null, 0);
    return out.toString();
  }

  private static void write(
      StringBuilder out, Map<String, List<TodoItem>> children, String parent, int depth) {
    for (TodoItem item : children.getOrDefault(parent, List.of())) {
      out.append("  ".repeat(depth))
          .append(marker(item.status()))
          .append(' ')
          .append(item.id())
          .append(' ')
          .append(item.text());
      if (item.locked()) {
        out.append(" (stage)");
      }
      if (item.summary() != null) {
        out.append(" — ").append(item.summary());
      }
      out.append('\n');
      write(out, children, item.id(), depth + 1);
    }
  }

  private static String marker(TodoStatus status) {
    return switch (status) {
      case PENDING -> "[ ]";
      case IN_PROGRESS -> "[>]";
      case DONE -> "[x]";
      case DROPPED -> "[-]";
    };
  }
}
