package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.goals.GoalAgents;
import io.aeyer.plowshare.server.relay.GoalRelaySources.Search;
import io.aeyer.plowshare.server.relay.GoalRelaySources.Selection;
import io.aeyer.plowshare.server.relay.RelayPayload.Kind;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class GoalAgentFrames implements FrameArea {
  private final GoalAgents goals;

  GoalAgentFrames(GoalAgents goals) {
    this.goals = goals;
  }

  record Launch(
      String project, String agent, String objective, String criteria, String requestId) {}

  record ListGoals(String project, String after, Integer limit) {}

  record GoalStatus(String project, String goal) {}

  record GoalReceipt(String project, String requestId) {}

  record Answer(
      String goal, String conversation, String question, String requestId, String answer) {}

  record Sources(
      String goal, String conversation, String prefix, Kind kind, String after, Integer limit) {}

  record Listen(
      String goal,
      String conversation,
      String topic,
      String generation,
      Integer batchCount,
      Integer maxDelaySeconds) {}

  record Unlisten(
      String goal, String conversation, String binding, int version, String requestId) {}

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.GOAL_START, this::start,
        FrameTypes.GOAL_LIST, this::list,
        FrameTypes.GOAL_STATUS, this::status,
        FrameTypes.GOAL_RECEIPT, this::receipt,
        FrameTypes.GOAL_ANSWER, this::answer,
        FrameTypes.GOAL_SOURCES, this::sources,
        FrameTypes.MESSAGE_LISTEN, this::listen,
        FrameTypes.MESSAGE_UNLISTEN, this::unlisten);
  }

  private Outcome start(Map<String, Object> payload, Asking asking) {
    var input = Payloads.exact(optional(payload, "criteria"), Launch.class, FrameTypes.GOAL_START);
    return Outcome.ok(
        goals.start(
            asking.requireHandle(FrameTypes.GOAL_START),
            new GoalAgents.Start(
                input.project(),
                input.agent(),
                input.objective(),
                input.criteria(),
                uuid(input.requestId()))));
  }

  private Outcome list(Map<String, Object> payload, Asking asking) {
    for (String field : new String[] {"after", "limit"}) {
      if (payload.containsKey(field) && payload.get(field) == null)
        throw new CallerFault("Omit an optional Goal Agent list field or give it a value");
    }
    var input =
        Payloads.exact(optional(payload, "after", "limit"), ListGoals.class, FrameTypes.GOAL_LIST);
    return Outcome.ok(
        goals.list(
            asking.requireHandle(FrameTypes.GOAL_LIST),
            new GoalAgents.Listing(
                input.project(),
                input.after() == null ? null : uuid(input.after()),
                input.limit() == null ? GoalAgents.DEFAULT_PAGE_SIZE : input.limit())));
  }

  private Outcome status(Map<String, Object> payload, Asking asking) {
    var input = Payloads.exact(payload, GoalStatus.class, FrameTypes.GOAL_STATUS);
    return Outcome.ok(
        goals.status(
            asking.requireHandle(FrameTypes.GOAL_STATUS),
            new GoalAgents.StatusRequest(input.project(), uuid(input.goal()))));
  }

  private Outcome receipt(Map<String, Object> payload, Asking asking) {
    var input = Payloads.exact(payload, GoalReceipt.class, FrameTypes.GOAL_RECEIPT);
    return Outcome.ok(
        goals.receipt(
            asking.requireHandle(FrameTypes.GOAL_RECEIPT),
            new GoalAgents.ReceiptRequest(input.project(), uuid(input.requestId()))));
  }

  private Outcome answer(Map<String, Object> payload, Asking asking) {
    var input = Payloads.exact(payload, Answer.class, FrameTypes.GOAL_ANSWER);
    return Outcome.ok(
        goals.answer(
            asking.requireHandle(FrameTypes.GOAL_ANSWER),
            new GoalAgents.Answer(
                uuid(input.goal()),
                input.conversation(),
                uuid(input.question()),
                uuid(input.requestId()),
                input.answer())));
  }

  private Outcome sources(Map<String, Object> payload, Asking asking) {
    var input =
        Payloads.exact(
            optional(payload, "prefix", "kind", "after", "limit"),
            Sources.class,
            FrameTypes.GOAL_SOURCES);
    return Outcome.ok(
        goals.sources(
            asking.requireHandle(FrameTypes.GOAL_SOURCES),
            uuid(input.goal()),
            input.conversation(),
            new Search(
                input.prefix(),
                input.kind(),
                input.after(),
                input.limit() == null ? 100 : input.limit())));
  }

  private Outcome listen(Map<String, Object> payload, Asking asking) {
    for (String field : new String[] {"batchCount", "maxDelaySeconds"}) {
      if (payload.containsKey(field) && payload.get(field) == null)
        throw new CallerFault("Relay batch fields must be integers");
    }
    var input =
        Payloads.exact(
            optional(payload, "batchCount", "maxDelaySeconds"),
            Listen.class,
            FrameTypes.MESSAGE_LISTEN);
    return Outcome.ok(
        goals.listen(
            asking.requireHandle(FrameTypes.MESSAGE_LISTEN),
            new GoalAgents.Listen(
                uuid(input.goal()),
                input.conversation(),
                new Selection(input.topic(), uuid(input.generation())),
                GoalAgents.Batch.optional(input.batchCount(), input.maxDelaySeconds()))));
  }

  private Outcome unlisten(Map<String, Object> payload, Asking asking) {
    var input = Payloads.exact(payload, Unlisten.class, FrameTypes.MESSAGE_UNLISTEN);
    if (input.version() < 1) throw new CallerFault("A positive goal version is required");
    return Outcome.ok(
        goals.unlisten(
            asking.requireHandle(FrameTypes.MESSAGE_UNLISTEN),
            new GoalAgents.Unlisten(
                uuid(input.goal()),
                input.conversation(),
                uuid(input.binding()),
                input.version(),
                uuid(input.requestId()))));
  }

  private static Map<String, Object> optional(Map<String, Object> input, String... fields) {
    var copy = new HashMap<>(input);
    for (String field : fields) copy.putIfAbsent(field, null);
    return copy;
  }

  private static UUID uuid(String input) {
    if (input != null && input.length() == 36) {
      try {
        var value = UUID.fromString(input);
        if (value.toString().equalsIgnoreCase(input) && !value.equals(new UUID(0, 0))) return value;
      } catch (IllegalArgumentException invalid) {
        // Normalize malformed UUIDs to the same caller refusal.
      }
    }
    throw new CallerFault("Supply an exact nonzero UUID");
  }
}
