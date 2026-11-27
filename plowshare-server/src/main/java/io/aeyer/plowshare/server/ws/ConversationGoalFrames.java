package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.goals.ConversationGoals;
import io.aeyer.plowshare.server.goals.ConversationGoals.Action;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class ConversationGoalFrames implements FrameArea {
  private final ConversationGoals goals;

  ConversationGoalFrames(ConversationGoals goals) {
    this.goals = goals;
  }

  record Start(
      String conversation,
      String requestId,
      String agent,
      String objective,
      String criteria,
      Integer maxActivations) {}

  record Get(String conversation, String requestId) {}

  record Control(String conversation, String requestId, String goal, int version) {}

  record Resume(
      String conversation,
      String requestId,
      String goal,
      int version,
      Integer maxActivations,
      Integer maxModelCalls) {}

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.CONVERSATION_GOAL_START, this::start,
        FrameTypes.CONVERSATION_GOAL_GET, this::get,
        FrameTypes.CONVERSATION_GOAL_PAUSE,
            (payload, asking) ->
                control(payload, asking, Action.PAUSE, FrameTypes.CONVERSATION_GOAL_PAUSE),
        FrameTypes.CONVERSATION_GOAL_RESUME, this::resume,
        FrameTypes.CONVERSATION_GOAL_CANCEL,
            (payload, asking) ->
                control(payload, asking, Action.CANCEL, FrameTypes.CONVERSATION_GOAL_CANCEL));
  }

  private Outcome start(Map<String, Object> payload, Asking asking) {
    var input =
        Payloads.exact(
            optional(payload, "criteria", "maxActivations"),
            Start.class,
            FrameTypes.CONVERSATION_GOAL_START);
    return Outcome.ok(
        goals.start(
            asking.requireHandle(FrameTypes.CONVERSATION_GOAL_START),
            new ConversationGoals.Start(
                input.conversation(),
                uuid(input.requestId()),
                input.agent(),
                input.objective(),
                input.criteria(),
                input.maxActivations())));
  }

  private Outcome get(Map<String, Object> payload, Asking asking) {
    var input =
        Payloads.exact(optional(payload, "requestId"), Get.class, FrameTypes.CONVERSATION_GOAL_GET);
    return Outcome.ok(
        goals.get(
            asking.requireHandle(FrameTypes.CONVERSATION_GOAL_GET),
            input.conversation(),
            input.requestId() == null ? null : uuid(input.requestId())));
  }

  private Outcome control(
      Map<String, Object> payload, Asking asking, Action action, String operation) {
    var input = Payloads.exact(payload, Control.class, operation);
    return Outcome.ok(
        goals.control(
            asking.requireHandle(operation),
            action,
            new ConversationGoals.Control(
                input.conversation(),
                uuid(input.requestId()),
                uuid(input.goal()),
                input.version(),
                null)));
  }

  private Outcome resume(Map<String, Object> payload, Asking asking) {
    if (payload.containsKey("maxModelCalls") && payload.get("maxModelCalls") == null)
      throw new CallerFault("maxModelCalls must be an integer");
    var input =
        Payloads.exact(
            optional(payload, "maxActivations", "maxModelCalls"),
            Resume.class,
            FrameTypes.CONVERSATION_GOAL_RESUME);
    return Outcome.ok(
        goals.control(
            asking.requireHandle(FrameTypes.CONVERSATION_GOAL_RESUME),
            Action.RESUME,
            new ConversationGoals.Control(
                input.conversation(),
                uuid(input.requestId()),
                uuid(input.goal()),
                input.version(),
                input.maxActivations(),
                input.maxModelCalls())));
  }

  // Exact decoding rejects every unknown field and coercion. Only documented optional fields
  // receive null defaults before the decoder checks creator properties.
  private static Map<String, Object> optional(Map<String, Object> input, String... fields) {
    var payload = new HashMap<>(input);
    for (String field : fields) payload.putIfAbsent(field, null);
    return payload;
  }

  private static UUID uuid(String input) {
    if (input != null && input.length() == 36) {
      try {
        UUID value = UUID.fromString(input);
        if (value.toString().equalsIgnoreCase(input)) return value;
      } catch (IllegalArgumentException malformed) {
        // Use the same typed refusal for every malformed identifier.
      }
    }
    throw new CallerFault("Goal and request IDs must be canonical UUIDs");
  }
}
