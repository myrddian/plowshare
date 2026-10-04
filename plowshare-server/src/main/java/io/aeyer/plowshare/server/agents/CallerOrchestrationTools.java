package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.ToolArguments.BadArguments;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The tools held by an agent with an {@code orchestrations:} grant. They are built for one run,
 * because starting records that run's conversation, session and owning account.
 */
public final class CallerOrchestrationTools {

  public static final String ANSWER_NAME = "orchestration_answer";
  public static final String STATUS_NAME = "orchestration_status";
  public static final String CANCEL_NAME = "orchestration_cancel";

  /** The part of a resolved orchestration that its caller-facing schema needs. */
  public record Offer(String name, String description, List<String> stages) {
    public Offer {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(description, "description");
      stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
    }
  }

  /** Operations already bound to one caller and its authority. */
  public interface Actions {
    String start(String name, String request, String context, boolean wait);

    default String startBound(String name, String request, java.util.UUID invocation, String hash) {
      throw new IllegalStateException("Bound orchestration dispatch is unavailable. Nothing ran.");
    }

    String answer(String id, String answer);

    /**
     * Answer a question with options by its labels (spec 2026-09-29-orchestration-studio §2.3),
     * with {@code note} as free words beside them or null.
     */
    default String choose(String id, JsonNode choices, String note) {
      return "This server cannot answer a question by its options; answer it in words.";
    }

    String status(String id);

    default String readResult(String id, int offset, int limit) {
      return "This server cannot page orchestration results; inspect the retained run record.";
    }

    String cancel(String id);
  }

  private CallerOrchestrationTools() {}

  /** One available start tool per grant, followed once by answer, status and cancel. */
  public static List<AgentTool> forRun(List<Offer> offers, Actions actions) {
    Objects.requireNonNull(offers, "offers");
    Objects.requireNonNull(actions, "actions");
    List<AgentTool> tools = new ArrayList<>();
    offers.forEach(offer -> tools.add(new Start(offer, actions)));
    tools.add(new Answer(actions));
    tools.add(new Status(actions));
    tools.add(new Cancel(actions));
    return List.copyOf(tools);
  }

  private abstract static class Tool implements AgentTool {
    final Actions actions;

    Tool(Actions actions) {
      this.actions = Objects.requireNonNull(actions, "actions");
    }

    @Override
    public final String run(String argumentsJson, Home home) {
      Objects.requireNonNull(argumentsJson, "argumentsJson");
      Objects.requireNonNull(home, "home");
      try {
        return answer(argumentsJson);
      } catch (BadArguments unusable) {
        return unusable.getMessage();
      }
    }

    abstract String answer(String argumentsJson);
  }

  private static final class Start extends Tool implements BoundCommandTool {
    private final String name;
    private final ToolSchema schema;

    Start(Offer offer, Actions actions) {
      super(actions);
      name = offer.name();
      Map<String, Object> fields = new LinkedHashMap<>();
      fields.put("request", ToolArguments.string("The work for the orchestration to do."));
      fields.put(
          "context", ToolArguments.string("Optional background the conductor should use as data."));
      fields.put(
          "wait",
          ToolArguments.flag(
              "Whether to wait for it. True, the default, ends"
                  + " your turn until it reports; false returns at once and lets you carry on."));
      schema =
          new ToolSchema(
              "orchestrate_" + name,
              oneLine(offer.description())
                  + " Stages: "
                  + String.join(", ", offer.stages())
                  + ". Returns its handle at once.",
              ToolArguments.object(fields, List.of("request")));
    }

    @Override
    public ToolSchema schema() {
      return schema;
    }

    @Override
    public String runBound(java.util.UUID invocation, String hash, String arguments, Home home) {
      return actions.startBound(name, arguments, invocation, hash);
    }

    @Override
    String answer(String argumentsJson) {
      String tool = schema.name();
      JsonNode args =
          ToolArguments.parse(
              argumentsJson, tool, "{\"request\": \"Implement the approved change\"}");
      String request =
          ToolArguments.requireText(args, "request", tool, "the work for the orchestration to do");
      String context =
          ToolArguments.optionalText(
              args,
              "context",
              value ->
                  new BadArguments(
                      tool + " needs 'context', when present, to be text; it was " + value + "."));
      boolean wait =
          ToolArguments.optionalFlag(
              args,
              "wait",
              true,
              value ->
                  new BadArguments(
                      tool
                          + " needs 'wait', when present, to be true or false; it"
                          + " was "
                          + value
                          + "."));
      return actions.start(name, request, context, wait);
    }
  }

  private static final class Answer extends Tool {
    private static final String EXAMPLE = "{\"id\": \"orc_...\", \"answer\": \"Use PostgreSQL\"}";
    private static final ToolSchema SCHEMA =
        new ToolSchema(
            ANSWER_NAME,
            "Answer the open question from an orchestration owned by this account. The first"
                + " answer wins and resumes its conductor. A question with options may be"
                + " answered with 'choices', naming each question by its header and the"
                + " options chosen by their labels; any question may be answered in words.",
            ToolArguments.object(answerFields(), List.of("id")));

    private static Map<String, Object> answerFields() {
      Map<String, Object> choice = new LinkedHashMap<>();
      choice.put("header", ToolArguments.string("The header of the question this answers."));
      choice.put("chosen", ToolArguments.strings("The labels of the options chosen."));
      choice.put(
          "other", ToolArguments.string("Words of your own, beside or instead of" + " a choice."));
      choice.put("note", ToolArguments.string("A note on this question's choice."));
      Map<String, Object> choices = new LinkedHashMap<>();
      choices.put("type", "array");
      choices.put("description", "For a question with options: one answer per question.");
      choices.put("items", ToolArguments.object(choice, List.of("header")));
      Map<String, Object> fields = new LinkedHashMap<>();
      fields.put("id", ToolArguments.string("The orchestration handle."));
      fields.put(
          "answer",
          ToolArguments.string(
              "The answer in words. With 'choices', an" + " optional note beside them."));
      fields.put("choices", choices);
      return fields;
    }

    Answer(Actions actions) {
      super(actions);
    }

    @Override
    public ToolSchema schema() {
      return SCHEMA;
    }

    @Override
    String answer(String argumentsJson) {
      JsonNode args = ToolArguments.parse(argumentsJson, ANSWER_NAME, EXAMPLE);
      String id = ToolArguments.requireText(args, "id", ANSWER_NAME, "the orchestration handle");
      String answer =
          ToolArguments.optionalText(
              args,
              "answer",
              value ->
                  new BadArguments(
                      ANSWER_NAME
                          + " needs 'answer', when present, to be text; it"
                          + " was "
                          + value
                          + "."));
      JsonNode choices = args.get("choices");
      if (choices == null || choices.isNull()) {
        if (answer == null || answer.isBlank()) {
          throw new BadArguments(
              ANSWER_NAME
                  + " needs 'answer', the answer in words, or"
                  + " 'choices' for a question with options. Nothing was answered.");
        }
        return actions.answer(id, answer);
      }
      return actions.choose(id, choices, answer);
    }
  }

  private static final class Status extends Tool {
    private static final String EXAMPLE = "{\"id\": \"orc_...\"}";
    private static final ToolSchema SCHEMA =
        new ToolSchema(
            STATUS_NAME,
            "Read compact state, stages, message previews and ending of an orchestration owned by this"
                + " account. Checking on a run you started while it is still working ends"
                + " your turn — its result comes to you here when it finishes. For the full retained"
                + " result, supply result_offset (UTF-16 characters, initially 0) and result_limit"
                + " (default 8192, maximum 8192), continuing from end until total.",
            ToolArguments.object(
                Map.of(
                    "id", ToolArguments.string("The orchestration handle."),
                    "result_offset",
                        ToolArguments.integer(
                            "Read a window of the retained result starting here."),
                    "result_limit",
                        ToolArguments.integer("Maximum characters to read, from 1 to 8192.")),
                List.of("id")));

    Status(Actions actions) {
      super(actions);
    }

    @Override
    public ToolSchema schema() {
      return SCHEMA;
    }

    @Override
    String answer(String argumentsJson) {
      JsonNode args = ToolArguments.parse(argumentsJson, STATUS_NAME, EXAMPLE);
      String id = ToolArguments.requireText(args, "id", STATUS_NAME, "the orchestration handle");
      if (args.hasNonNull("result_offset") || args.hasNonNull("result_limit")) {
        int offset =
            ToolArguments.optionalInt(
                args,
                "result_offset",
                0,
                value -> new BadArguments("result_offset must be a nonnegative integer."));
        int limit =
            ToolArguments.optionalInt(
                args,
                "result_limit",
                8192,
                value -> new BadArguments("result_limit must be an integer from 1 to 8192."));
        if (offset < 0 || limit < 1 || limit > 8192) {
          throw new BadArguments(
              "Result windows need a nonnegative offset and a limit from 1 to 8192.");
        }
        return actions.readResult(id, offset, limit);
      }
      return actions.status(id);
    }
  }

  private static final class Cancel extends Tool {
    private static final String EXAMPLE = "{\"id\": \"orc_...\"}";
    private static final ToolSchema SCHEMA =
        new ToolSchema(
            CANCEL_NAME,
            "Cancel an orchestration owned by this account that is asking a question or"
                + " waiting on a child. A running run can only be stopped by the person,"
                + " with /cancel.",
            oneString("id", "The orchestration handle."));

    Cancel(Actions actions) {
      super(actions);
    }

    @Override
    public ToolSchema schema() {
      return SCHEMA;
    }

    @Override
    String answer(String argumentsJson) {
      JsonNode args = ToolArguments.parse(argumentsJson, CANCEL_NAME, EXAMPLE);
      return actions.cancel(
          ToolArguments.requireText(args, "id", CANCEL_NAME, "the orchestration handle"));
    }
  }

  private static Map<String, Object> oneString(String name, String description) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put(name, ToolArguments.string(description));
    return ToolArguments.object(fields, List.of(name));
  }

  private static String oneLine(String text) {
    return text.strip().replaceAll("\\s+", " ");
  }
}
