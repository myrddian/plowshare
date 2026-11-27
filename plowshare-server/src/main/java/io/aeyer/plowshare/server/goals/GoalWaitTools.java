package io.aeyer.plowshare.server.goals;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.GoalTurn.WaitTool;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import io.aeyer.plowshare.server.agents.Transcript;
import io.aeyer.plowshare.server.agents.TurnEnd;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exposes only the dedicated root's authorized start-and-wait operation. */
final class GoalWaitTools {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(
              DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
              DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
              DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
          .build();

  static {
    JSON.coercionConfigFor(LogicalType.Textual)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
  }

  @FunctionalInterface
  interface Start {
    void start(int turn, int answer, String call, String name, String request);
  }

  private record Request(String name, String request) {}

  private record Question(String question) {}

  @FunctionalInterface
  interface Ask {
    void retain(int turn, int answer, String call);
  }

  private GoalWaitTools() {}

  static WaitTool question(Ask ask) {
    var schema =
        ToolSchema.from(
            "goal_ask",
            "Send one necessary question over this Goal Agent's retained return route "
                + "and wait for its exact answer. Use this tool by itself.",
            Map.of(
                "type",
                "object",
                "additionalProperties",
                false,
                "required",
                List.of("question"),
                "properties",
                Map.of("question", Map.of("type", "string", "maxLength", 4000))));
    return new WaitTool() {
      private TurnEnd end;
      private int turn;
      private int answer;
      private String call;

      @Override
      public ToolSchema schema() {
        return schema;
      }

      @Override
      public void calledAt(TurnEnd end, Transcript.Spoken turn, int answer, String call) {
        this.end = Objects.requireNonNull(end);
        this.turn = turn.turnOrdinal();
        this.answer = answer;
        this.call = call;
      }

      @Override
      public String run(String arguments, Home home) {
        try {
          if (arguments.length() > 65536)
            throw new CallerFault("The question exceeds its input bound");
          var parsed = JSON.readValue(arguments, Question.class);
          var question =
              GoalText.require(parsed == null ? null : parsed.question(), "question", 4000);
          if (end.requested().isPresent()) return "This Goal turn already has a pending ending.";
          ask.retain(turn, answer, call);
          end.request(Ending.AWAITING, question);
          return question;
        } catch (JsonProcessingException invalid) {
          return "Supply a valid Goal question.";
        } catch (CallerFault refused) {
          return refused.getMessage();
        }
      }
    };
  }

  static WaitTool orchestration(List<String> names, Start start) {
    var schema =
        ToolSchema.from(
            "goal_orchestrate",
            "Start one granted orchestration and wait for its result. Use this tool by itself.",
            Map.of(
                "type",
                "object",
                "additionalProperties",
                false,
                "required",
                List.of("name", "request"),
                "properties",
                Map.of(
                    "name", Map.of("type", "string", "enum", List.copyOf(names)),
                    "request", Map.of("type", "string", "maxLength", 16000))));
    return new WaitTool() {
      private TurnEnd end;
      private Transcript.Spoken turn;
      private int answer;
      private String call;

      @Override
      public ToolSchema schema() {
        return schema;
      }

      @Override
      public void calledAt(TurnEnd end, Transcript.Spoken turn, int answer, String call) {
        this.end = Objects.requireNonNull(end);
        this.turn = Objects.requireNonNull(turn);
        this.answer = answer;
        this.call = call;
      }

      @Override
      public String run(String arguments, Home home) {
        Request request;
        try {
          if (arguments.length() > 65536)
            throw new CallerFault("The request exceeds its input bound");
          request = JSON.readValue(arguments, Request.class);
          if (request == null || !names.contains(request.name()))
            throw new CallerFault("Choose an orchestration declared by this Goal Agent");
          GoalText.require(request.request(), "request", 16000);
          if (end.requested().isPresent()) return "This Goal turn already has a pending ending.";
          start.start(turn.turnOrdinal(), answer, call, request.name(), request.request());
        } catch (JsonProcessingException invalid) {
          return "Supply an exact granted orchestration name and a bounded request.";
        } catch (CallerFault refused) {
          return refused.getMessage();
        }
        end.request(Ending.AWAITING, "Waiting for the retained orchestration result.");
        return "The harness retained the orchestration invocation.";
      }
    };
  }
}
