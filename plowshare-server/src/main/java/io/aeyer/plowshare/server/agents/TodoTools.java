package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import io.aeyer.plowshare.server.todos.TodoLists;
import io.aeyer.plowshare.server.todos.TodoOp;
import io.aeyer.plowshare.server.todos.TodoRefused;
import io.aeyer.plowshare.server.todos.TodoRendering;
import io.aeyer.plowshare.server.todos.TodoStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code todo_read} and {@code todo_write}: the run's own conversation's list.
 *
 * <p>Built per run, on {@link ResultTools}' reasoning: the list is keyed by the conversation, which
 * only the run's {@link Transcript} knows, and no instance shared by every job could. The session
 * goes with {@code todo_write} only so a committed batch can tell that account's clients.
 */
public final class TodoTools {

  public static final String READ_NAME = "todo_read";
  public static final String WRITE_NAME = "todo_write";
  public static final Set<String> NAMES = Set.of(READ_NAME, WRITE_NAME);

  static final String NO_CONVERSATION =
      "There is no todo list: this run is in no conversation,"
          + " so there is nowhere to keep one.";

  private static final String EXAMPLE = "{\"ops\":[{\"op\":\"add\",\"text\":\"write the spec\"}]}";

  // Ordered maps throughout, never Map.of: Map.of iterates in no fixed order, so the schema a model
  // is sent would change bytes between boots — which ModelSurfaceTest caught the day a shipped
  // agent
  // first declared these, and which would miss the prompt cache on every restart besides.
  private static final ToolSchema READ_SCHEMA =
      new ToolSchema(
          READ_NAME,
          "Read this conversation's todo list: each item's id, status ([ ] pending, [>] in"
              + " progress, [x] done, [-] dropped), text and summary, as a tree.",
          ToolArguments.object(new LinkedHashMap<>(), List.of()));

  private static final ToolSchema WRITE_SCHEMA =
      new ToolSchema(
          WRITE_NAME,
          "Change this conversation's todo list with a batch of operations, applied all or none."
              + " add: {op:add, text, parent?}. update: {op:update, id, status?, text?, summary?}."
              + " move: {op:move, id, position}. Drop an item by setting status to dropped."
              + " Items marked (stage) are locked: they cannot be renamed, moved or dropped."
              + " To finish a stage, the SAME update must include status:done and a nonblank"
              + " summary. A later operation cannot repair an earlier refused update.",
          writeParameters());

  private static Map<String, Object> writeParameters() {
    Map<String, Object> op = new LinkedHashMap<>();
    op.put("type", "string");
    op.put("enum", List.of("add", "update", "move"));
    Map<String, Object> status = new LinkedHashMap<>();
    status.put("type", "string");
    status.put("enum", List.of("pending", "in_progress", "done", "dropped"));
    Map<String, Object> position = new LinkedHashMap<>();
    position.put("type", "integer");
    position.put("minimum", 0);
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("op", op);
    fields.put("id", typed("string"));
    fields.put("parent", typed("string"));
    fields.put("text", typed("string"));
    Map<String, Object> summary = new LinkedHashMap<>(typed("string"));
    summary.put(
        "description",
        "For a stage update to done, include a nonblank summary in this"
            + " same operation, not a later operation.");
    fields.put("summary", summary);
    fields.put("status", status);
    fields.put("position", position);
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("type", "object");
    item.put("properties", fields);
    item.put("required", List.of("op"));
    Map<String, Object> ops = new LinkedHashMap<>();
    ops.put("type", "array");
    ops.put("items", item);
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("ops", ops);
    return ToolArguments.object(properties, List.of("ops"));
  }

  private static Map<String, Object> typed(String type) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", type);
    return schema;
  }

  private TodoTools() {}

  public static final class Read implements AgentTool {

    private final TodoLists lists;
    private final Transcript transcript;

    public Read(TodoLists lists, Transcript transcript) {
      this.lists = Objects.requireNonNull(lists, "lists");
      this.transcript = Objects.requireNonNull(transcript, "transcript");
    }

    @Override
    public ToolSchema schema() {
      return READ_SCHEMA;
    }

    @Override
    public String run(String argumentsJson, Home home) {
      Objects.requireNonNull(argumentsJson, "argumentsJson");
      Objects.requireNonNull(home, "home");
      String conversation = transcript.conversationId();
      if (conversation == null) {
        return NO_CONVERSATION;
      }
      return TodoRendering.compact(lists.list(conversation));
    }
  }

  /**
   * What the steps in front of the board passed: the ops to apply, and words for the model's result
   * once the batch commits — a stage hook's note (spec 2026-09-28-hooks-reach-the-log §3). A batch
   * the board refuses carries none.
   */
  public record Checked(List<TodoOp> ops, List<String> notes) {
    public Checked {
      ops = List.copyOf(ops);
      notes = List.copyOf(notes);
    }
  }

  /**
   * A step a run may put in front of the board: it sees the parsed ops before anything is applied,
   * outside the board's transaction, and may rewrite them or refuse them with a {@link
   * TodoRefused}. A conductor's checked stages are the one user — spec 2026-09-26.
   */
  @FunctionalInterface
  public interface BeforeApply {
    BeforeApply NONE = (conversation, ops, home) -> ops;

    List<TodoOp> check(String conversation, List<TodoOp> ops, Home home);

    /** {@link #check}, with words for the result; a step with none has no need to override. */
    default Checked checked(String conversation, List<TodoOp> ops, Home home) {
      return new Checked(check(conversation, ops, home), List.of());
    }

    /**
     * Several steps in front of the board, in order: each sees the ops the one before it returned,
     * and a refusal from any refuses the write.
     *
     * @param steps the steps, first to last
     * @return one step that runs them all
     */
    static BeforeApply of(List<BeforeApply> steps) {
      List<BeforeApply> each = List.copyOf(steps);
      return new BeforeApply() {
        @Override
        public List<TodoOp> check(String conversation, List<TodoOp> ops, Home home) {
          return checked(conversation, ops, home).ops();
        }

        @Override
        public Checked checked(String conversation, List<TodoOp> ops, Home home) {
          List<TodoOp> passed = ops;
          List<String> notes = new ArrayList<>();
          for (BeforeApply step : each) {
            Checked one = step.checked(conversation, passed, home);
            passed = one.ops();
            notes.addAll(one.notes());
          }
          return new Checked(passed, notes);
        }
      };
    }
  }

  public static final class Write implements AgentTool {

    private final TodoLists lists;
    private final Transcript transcript;
    private final String sessionId;
    private final BeforeApply before;

    public Write(TodoLists lists, Transcript transcript, String sessionId) {
      this(lists, transcript, sessionId, BeforeApply.NONE);
    }

    public Write(TodoLists lists, Transcript transcript, String sessionId, BeforeApply before) {
      this.lists = Objects.requireNonNull(lists, "lists");
      this.transcript = Objects.requireNonNull(transcript, "transcript");
      this.sessionId = sessionId;
      this.before = Objects.requireNonNull(before, "before");
    }

    private boolean scripted;

    Write scripted() {
      var copy = new Write(lists, transcript, sessionId, before);
      copy.scripted = true;
      return copy;
    }

    private String refused(String reason) {
      if (!scripted) return reason;
      return new com.fasterxml.jackson.databind.ObjectMapper()
          .createObjectNode()
          .put("applied", false)
          .put("reason", reason)
          .toString();
    }

    @Override
    public ToolSchema schema() {
      return WRITE_SCHEMA;
    }

    @Override
    public String run(String argumentsJson, Home home) {
      Objects.requireNonNull(argumentsJson, "argumentsJson");
      Objects.requireNonNull(home, "home");
      String conversation = transcript.conversationId();
      if (conversation == null) {
        return NO_CONVERSATION;
      }
      try {
        JsonNode args = ToolArguments.parse(argumentsJson, WRITE_NAME, EXAMPLE);
        Checked passed = before.checked(conversation, ops(args.path("ops")), home);
        String done =
            "Done. The list is now:\n"
                + TodoRendering.compact(lists.apply(conversation, passed.ops(), sessionId));
        if (passed.notes().isEmpty()) {
          return done;
        }
        return (done.endsWith("\n") ? done : done + "\n")
            + "\n"
            + String.join("\n\n", passed.notes());
      } catch (ToolArguments.BadArguments | TodoRefused refused) {
        return refused(refused.getMessage());
      }
    }

    private static List<TodoOp> ops(JsonNode node) {
      if (!node.isArray()) {
        throw new TodoRefused(
            "todo_write needs 'ops': a list of operations, like "
                + EXAMPLE
                + ". Nothing was changed.");
      }
      List<TodoOp> ops = new ArrayList<>();
      for (int i = 0; i < node.size(); i++) {
        ops.add(op(node.get(i), i + 1));
      }
      return ops;
    }

    private static TodoOp op(JsonNode op, int n) {
      String kind = text(op, "op");
      return switch (kind == null ? "" : kind) {
        case "add" -> new TodoOp.Add(text(op, "text"), text(op, "parent"));
        case "update" ->
            new TodoOp.Update(text(op, "id"), status(op, n), text(op, "text"), text(op, "summary"));
        case "move" -> {
          JsonNode position = op.path("position");
          // canConvertToInt() alone accepts 2.7: a DoubleNode reports convertible for
          // anything in int range regardless of a fractional part, and asInt() would
          // then silently truncate it. isIntegralNumber() is what rules out the
          // fraction.
          if (!position.isIntegralNumber() || !position.canConvertToInt()) {
            throw refused(n, "a move needs 'position', a whole number from zero");
          }
          yield new TodoOp.Move(text(op, "id"), position.asInt());
        }
        default -> throw refused(n, "'op' must be add, update or move, not '" + kind + "'");
      };
    }

    private static TodoStatus status(JsonNode op, int n) {
      String wire = text(op, "status");
      if (wire == null) {
        return null;
      }
      return TodoStatus.fromWire(wire)
          .orElseThrow(
              () ->
                  refused(
                      n,
                      "'status' must be pending, in_progress, done or dropped, not '"
                          + wire
                          + "'"));
    }

    private static String text(JsonNode op, String field) {
      JsonNode value = op.path(field);
      return value.isTextual() ? value.asText() : null;
    }

    private static TodoRefused refused(int n, String why) {
      return new TodoRefused(
          "todo_write refused operation " + n + ": " + why + ". Nothing was changed.");
    }
  }
}
