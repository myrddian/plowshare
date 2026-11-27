package io.aeyer.plowshare.server.goals;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ScheduledWork;
import io.aeyer.plowshare.server.agents.AgentTool;
import io.aeyer.plowshare.server.agents.Turn.Refused;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.goals.GoalAgents.Batch;
import io.aeyer.plowshare.server.goals.GoalExecution.Prepared;
import io.aeyer.plowshare.server.goals.GoalRepository.Binding;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import io.aeyer.plowshare.server.relay.GoalRelaySources.Search;
import io.aeyer.plowshare.server.relay.GoalRelaySources.Selection;
import io.aeyer.plowshare.server.relay.RelayPayload.Kind;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Model functions are scoped to the current dedicated root and use the operator's same selector.
 */
final class GoalListenerTools {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(
              DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
              DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
          .build();

  private static final ToolSchema SOURCES =
      ToolSchema.from(
          "goal_sources",
          "List Relay topics this Goal Agent can listen to. Copy the topic and generation together.",
          Map.of(
              "type",
              "object",
              "additionalProperties",
              false,
              "properties",
              Map.of(
                  "prefix",
                  Map.of("type", "string"),
                  "kind",
                  Map.of("type", "string", "enum", List.of("TEXT", "EMPTY")),
                  "after",
                  Map.of("type", "string"),
                  "limit",
                  Map.of("type", "integer", "minimum", 1, "maximum", 100))));
  private static final ToolSchema LISTEN =
      ToolSchema.from(
          "message_listen",
          "Attach an eligible Relay feed to this Goal Agent's persistent inbox. Supply both"
              + " batchCount and maxDelaySeconds to group arrivals, or omit both for one wake per event.",
          Map.of(
              "type",
              "object",
              "additionalProperties",
              false,
              "required",
              List.of("topic", "generation"),
              "properties",
              Map.of(
                  "topic", Map.of("type", "string"),
                  "generation", Map.of("type", "string"),
                  "batchCount", Map.of("type", "integer", "minimum", 2, "maximum", 16),
                  "maxDelaySeconds", Map.of("type", "integer", "minimum", 1, "maximum", 86400))));

  private static final ToolSchema SCHEDULE =
      ToolSchema.from(
          "goal_schedule",
          "Propose a recurring wake for this Goal Agent. Give the returned schedule.save request"
              + " to a project Manager to apply in a source owned by this Goal Agent’s mailbox account while the goal is open.",
          Map.of(
              "type",
              "object",
              "additionalProperties",
              false,
              "required",
              List.of("cron", "zone", "input"),
              "properties",
              Map.of(
                  "cron",
                      Map.of("type", "string", "description", "Six cron fields, seconds first."),
                  "zone", Map.of("type", "string", "description", "IANA zone ID or UTC."),
                  "input", Map.of("type", "string", "description", "Instruction for each wake."))));

  private record Schedule(String cron, String zone, String input) {}

  private record ScheduleProposal(String operation, ScheduledWork.Save request, String detail) {}

  private record Find(String prefix, Kind kind, String after, Integer limit) {}

  private record Listen(
      String topic, String generation, Integer batchCount, Integer maxDelaySeconds) {}

  private GoalListenerTools() {}

  static List<AgentTool> bind(DedicatedGoalAgents agents, Binding binding, Prepared prepared) {
    UUID goal = binding.id();
    return List.of(
        tool(
            SOURCES,
            Find.class,
            request ->
                agents.discover(
                    goal,
                    prepared,
                    new Search(
                        request.prefix(),
                        request.kind(),
                        request.after(),
                        request.limit() == null ? 100 : request.limit()))),
        tool(
            LISTEN,
            Listen.class,
            request -> {
              if (request.generation() == null)
                throw new IllegalArgumentException("Missing generation");
              UUID generation = UUID.fromString(request.generation());
              if (!generation.toString().equalsIgnoreCase(request.generation())
                  || generation.equals(new UUID(0, 0)))
                throw new IllegalArgumentException("Invalid topic generation");
              return agents.listen(
                  goal,
                  prepared,
                  new Selection(request.topic(), generation),
                  Batch.optional(request.batchCount(), request.maxDelaySeconds()));
            }),
        tool(
            SCHEDULE,
            Schedule.class,
            request ->
                new ScheduleProposal(
                    "schedule.save",
                    agents.proposeSchedule(
                        binding, prepared, request.cron(), request.zone(), request.input()),
                    "Ask a project Manager to apply this request while the Goal Agent is open. The schedule source must execute as the account that owns this mailbox.")));
  }

  private static <T, R> AgentTool tool(
      ToolSchema schema, Class<T> input, Function<T, R> operation) {
    return new AgentTool() {
      @Override
      public ToolSchema schema() {
        return schema;
      }

      @Override
      public String run(String args, Home home) {
        if (args.length() > (schema == SCHEDULE ? 65536 : 4096))
          return "Goal wake arguments are too long.";
        T decoded;
        try {
          var supplied = JSON.readTree(args);
          if (schema == LISTEN
              && supplied != null
              && (supplied.path("batchCount").isNull()
                  || supplied.path("maxDelaySeconds").isNull()))
            return "Supply valid Goal wake arguments.";
          decoded = JSON.treeToValue(supplied, input);
        } catch (JsonProcessingException invalid) {
          return "Supply valid Goal wake arguments.";
        }
        if (decoded == null) return "Supply a Goal wake argument object.";
        R reply;
        try {
          reply = operation.apply(decoded);
        } catch (CallerFault | IllegalArgumentException | Refused rejected) {
          return "Goal wake request refused: " + rejected.getMessage();
        }
        try {
          return JSON.writeValueAsString(reply);
        } catch (JsonProcessingException failed) {
          throw new IllegalStateException("Could not encode the Goal wake result", failed);
        }
      }
    };
  }
}
