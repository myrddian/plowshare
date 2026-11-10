package io.aeyer.plowshare.server.orchestrations.scripted;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.todos.TodoItem;
import io.aeyer.plowshare.server.todos.TodoStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** The report continuation may advance stage status and save a draft from the retained evidence. */
final class ReportRepairEffects {
  private static final ObjectMapper JSON = new ObjectMapper();

  enum Effect {
    STAGE,
    DRAFT,
    FINISH
  }

  private ReportRepairEffects() {}

  static Effect validate(
      ScriptStore.Command command,
      String conversation,
      int sequence,
      List<TodoItem> todos,
      ScriptStore.Repair repair) {
    if (sequence <= repair.boundary().sequence() || !(command instanceof ScriptStore.Tool tool))
      throw refused();
    var args = decode(tool.arguments());
    return switch (tool.name()) {
      case "todo_write" -> {
        var ops = args.path("ops");
        var op = ops.path(0);
        if (!args.isObject()
            || args.size() != 1
            || !ops.isArray()
            || ops.size() != 1
            || !fields(op, Set.of("op", "id", "status", "summary"))
            || !"update".equals(op.path("op").textValue())
            || op.has("summary") && !op.path("summary").isTextual()) throw refused();
        String id = op.path("id").textValue();
        String status = op.path("status").textValue();
        if (todos.stream()
            .noneMatch(
                todo ->
                    todo.locked()
                        && todo.stageId() != null
                        && todo.id().equals(id)
                        && (todo.status() == TodoStatus.PENDING && "in_progress".equals(status)
                            || todo.status() == TodoStatus.IN_PROGRESS && "done".equals(status))))
          throw refused();
        yield Effect.STAGE;
      }
      case "information_write" -> {
        if (!fields(args, Set.of("operation", "requestId", "name", "text", "inputs"))
            || !"report".equals(args.path("operation").textValue())
            || !args.path("name").isTextual()
            || !args.path("text").isTextual()
            || !args.path("inputs").isArray()) throw refused();
        UUID expected =
            UUID.nameUUIDFromBytes(
                (conversation + ":" + sequence).getBytes(StandardCharsets.UTF_8));
        if (ScriptCommands.reportRequest(tool).filter(expected::equals).isEmpty()) throw refused();
        var inputs = new HashSet<UUID>();
        for (var input : args.path("inputs")) {
          if (!inputs.add(uuid(input))) throw refused();
        }
        if (!inputs.equals(repair.evidence())) throw refused();
        yield Effect.DRAFT;
      }
      case "orchestration_finish" -> {
        if (args.size() != 1 || !args.path("result").isTextual()) throw refused();
        yield Effect.FINISH;
      }
      default -> throw refused();
    };
  }

  static boolean savedDraft(String result) {
    if (result == null) return false;
    try {
      var receipt = decode(result);
      uuid(receipt.path("resource"));
      uuid(receipt.path("revision"));
      return true;
    } catch (IllegalStateException invalid) {
      return false;
    }
  }

  private static boolean fields(JsonNode args, Set<String> allowed) {
    if (!args.isObject()) return false;
    var names = args.fieldNames();
    while (names.hasNext()) {
      if (!allowed.contains(names.next())) return false;
    }
    return true;
  }

  private static JsonNode decode(String text) {
    try {
      var parsed = JSON.readTree(text);
      if (parsed == null) throw refused();
      return parsed;
    } catch (IOException invalid) {
      throw refused();
    }
  }

  private static UUID uuid(JsonNode node) {
    String text = node.textValue();
    if (text == null) throw refused();
    try {
      var parsed = UUID.fromString(text);
      if (!parsed.toString().equalsIgnoreCase(text)) throw refused();
      return parsed;
    } catch (IllegalArgumentException invalid) {
      throw refused();
    }
  }

  private static IllegalStateException refused() {
    return new IllegalStateException(
        "Repaired checkpoints may only advance stages, save one draft from the retained evidence, and finish.");
  }
}
