package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.goals.GoalAgents;
import io.aeyer.plowshare.server.goals.GoalHandoffs;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Model tools bound by the skill runtime to its owning conversation and active invocation. */
final class GoalSkillTools {
  static final String SKILL = "goal";
  static final String CANDIDATES = "goal_candidates";
  static final String START = "goal_start";
  static final String CLARIFY = "goal_clarify";
  static final Set<String> NAMES = Set.of(CANDIDATES, START, CLARIFY);
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  record Invocation(UUID id, GoalHandoffs.Caller caller) {}

  private GoalSkillTools() {}

  static List<AgentTool> bind(
      GoalHandoffs goals, Home boundHome, Function<String, Invocation> active, TurnEnd end) {
    return List.of(
        tool(
            boundHome,
            CANDIDATES,
            "During the active goal skill, list authorized Goal Agents in this conversation.",
            Map.of(),
            List.of(),
            args -> {
              return "Eligible Goal Agents: " + goals.candidates(active.apply(CANDIDATES).caller());
            }),
        tool(
            boundHome,
            START,
            "During the active goal skill, hand off to an exact eligible candidate. The server binds the launch ID to this skill invocation.",
            Map.of(
                "agent", ToolArguments.string("Exact eligible Goal Agent name"),
                "objective",
                    ToolArguments.string(
                        "Objective and relevant context for the dedicated instance"),
                "criteria", ToolArguments.string("Optional completion criteria"),
                "replyExpected",
                    ToolArguments.flag(
                        "For a bot handoff, request a correlated outcome on the retained Messaging route.")),
            List.of("agent", "objective"),
            args -> {
              var invocation = active.apply(START);
              var caller = invocation.caller();
              if (end.requested()
                  .filter(request -> request.ending() == Outcome.Ending.AWAITING)
                  .isPresent()) throw bad("Continue after the user answers the retained question.");
              boolean correlated = replyExpected(args);
              var started =
                  goals.start(
                      caller,
                      new GoalAgents.Start(
                          caller.admission().home().project(),
                          exact(args, "agent", false),
                          exact(args, "objective", false),
                          exact(args, "criteria", true),
                          invocation.id(),
                          correlated));
              String receipt =
                  "Goal handoff accepted.\nGoal ID: "
                      + started.goal()
                      + "\nMessage address: "
                      + started.instance()
                      + "\nGoal conversation: "
                      + started.conversation();
              if (correlated)
                receipt += "\nCorrelated launch message: " + goals.launchMessage(caller, started);
              end.request(Outcome.Ending.ANSWERED, receipt);
              return receipt;
            }),
        tool(
            boundHome,
            CLARIFY,
            "Ask the user one necessary handoff question and retain this skill invocation for their answer.",
            Map.of("question", ToolArguments.string("Question for the user")),
            List.of("question"),
            args -> {
              active.apply(CLARIFY);
              String question = exact(args, "question", false);
              if (question.length() > 2000) throw bad("Use a question of at most 2000 characters.");
              if (!end.request(Outcome.Ending.AWAITING, question))
                throw bad("This turn already has an ending.");
              return question;
            }));
  }

  private static AgentTool tool(
      Home boundHome,
      String name,
      String description,
      Map<String, Object> properties,
      List<String> required,
      Function<JsonNode, String> action) {
    var schema = ToolSchema.from(name, description, ToolArguments.object(properties, required));
    return new AgentTool() {
      @Override
      public ToolSchema schema() {
        return schema;
      }

      @Override
      public String run(String arguments, Home home) {
        if (!boundHome.equals(home)) throw bad("The goal skill cannot change its execution home.");
        if (arguments.length() > 65536) throw bad("Goal skill arguments exceed the input limit.");
        try {
          var args =
              arguments.isBlank() && properties.isEmpty()
                  ? JSON.createObjectNode()
                  : JSON.readTree(arguments);
          if (args == null
              || !args.isObject()
              || args.properties().stream()
                  .anyMatch(field -> !properties.containsKey(field.getKey())))
            throw bad("Supply only the goal skill's declared arguments.");
          return action.apply(args);
        } catch (IOException invalid) {
          throw bad("Supply a valid goal skill argument object.");
        }
      }
    };
  }

  private static boolean replyExpected(JsonNode args) {
    var flag = args.get("replyExpected");
    if (flag == null) return false;
    if (!flag.isBoolean()) throw bad("replyExpected must be true or false.");
    return flag.booleanValue();
  }

  private static String exact(JsonNode args, String name, boolean optional) {
    var value = args.get(name);
    if (value == null && optional) return null;
    if (value == null
        || !value.isTextual()
        || value.textValue().isBlank()
        || value.textValue().indexOf(0) >= 0) throw bad("Supply nonblank text for " + name + ".");
    return value.textValue();
  }

  private static ToolArguments.BadArguments bad(String message) {
    return new ToolArguments.BadArguments(message);
  }
}
