package io.aeyer.plowshare.server.goals;

import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.goals.ConversationGoals.Reason;
import io.aeyer.plowshare.server.goals.ConversationGoals.State;
import io.aeyer.plowshare.server.relay.GoalRelaySources.Search;
import io.aeyer.plowshare.server.relay.GoalRelaySources.Selection;
import io.aeyer.plowshare.server.relay.RelayPayload.Kind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Launch, discovery, and wake registration for a goal with a dedicated Messaging instance. */
public interface GoalAgents {
  record Start(
      String project,
      String agent,
      String objective,
      String criteria,
      UUID requestId,
      boolean replyExpected) {
    public Start(String project, String agent, String objective, String criteria, UUID requestId) {
      this(project, agent, objective, criteria, requestId, false);
    }
  }

  record Started(UUID goal, String conversation, String instance, int version, UUID requestId) {}

  int DEFAULT_PAGE_SIZE = 20;
  int MAX_PAGE_SIZE = 50;

  enum Lifecycle {
    AD_HOC,
    STANDING
  }

  enum RelayFailure {
    SOURCE_CHANGED,
    RETENTION_GAP,
    UNSUPPORTED_PUBLICATION
  }

  enum PendingState {
    OPEN,
    READY
  }

  record Listing(String project, UUID after, int limit) {
    public Listing {
      project = discoveryProject(project);
      if (after != null) discoveryId(after);
      if (limit < 1 || limit > MAX_PAGE_SIZE)
        throw new CallerFault("Goal Agent list limit must be between 1 and " + MAX_PAGE_SIZE);
    }
  }

  record StatusRequest(String project, UUID goal) {
    public StatusRequest {
      project = discoveryProject(project);
      discoveryId(goal);
    }
  }

  record ReceiptRequest(String project, UUID requestId) {
    public ReceiptRequest {
      project = discoveryProject(project);
      discoveryId(requestId);
    }
  }

  record Result(String job, int turn, String summary, List<String> evidence) {
    public Result {
      evidence = List.copyOf(evidence);
    }
  }

  record ScheduleWake(String schedule, UUID generation, boolean paused, Instant nextFireAt) {}

  record PendingBatch(UUID id, PendingState state, int memberCount, Instant deadline) {}

  record RelayWake(
      UUID binding,
      String topic,
      UUID generation,
      RelayFailure failure,
      PendingBatch pendingBatch) {}

  record Status(
      UUID goal,
      String project,
      String conversation,
      String instance,
      String agent,
      String objective,
      Lifecycle lifecycle,
      State state,
      int version,
      boolean readable,
      Reason reason,
      String detail,
      Result latestResult,
      Instant nextScheduleAt,
      List<ScheduleWake> schedules,
      List<RelayWake> relayListeners,
      boolean moreSchedules,
      boolean moreRelayListeners) {
    public Status {
      schedules = List.copyOf(schedules);
      relayListeners = List.copyOf(relayListeners);
    }
  }

  record Page(List<Status> goals, UUID next) {
    public Page {
      goals = List.copyOf(goals);
    }
  }

  private static String discoveryProject(String project) {
    var value = GoalText.require(project, "project", 1024);
    if (!value.equals(value.strip()) || value.codePoints().anyMatch(Character::isISOControl))
      throw new CallerFault("Choose an exact Goal Agent project");
    return value;
  }

  private static void discoveryId(UUID value) {
    if (value == null || value.equals(new UUID(0, 0)))
      throw new CallerFault("Supply an exact nonzero UUID");
  }

  record Answer(UUID goal, String conversation, UUID question, UUID requestId, String answer) {}

  record Answered(UUID goal, String conversation, UUID question, UUID requestId) {}

  record Source(String topic, UUID generation, Kind kind) {}

  record Sources(UUID goal, String project, List<Source> sources) {}

  /** A missing configuration retains one wake per event. Both batching bounds are required. */
  record Batch(int count, int maxDelaySeconds) {
    public Batch {
      if (count < 2 || count > 16 || maxDelaySeconds < 1 || maxDelaySeconds > 86_400)
        throw new CallerFault(
            "Relay batches require a count of 2–16 and a delay of 1–86400 seconds");
    }

    public static Batch optional(Integer count, Integer maxDelaySeconds) {
      if (count == null && maxDelaySeconds == null) return null;
      if (count == null || maxDelaySeconds == null)
        throw new CallerFault("Supply batchCount and maxDelaySeconds together");
      return new Batch(count, maxDelaySeconds);
    }
  }

  record Listen(UUID goal, String conversation, Selection source, Batch batch) {
    public Listen(UUID goal, String conversation, Selection source) {
      this(goal, conversation, source, null);
    }
  }

  record Unlisten(UUID goal, String conversation, UUID binding, int version, UUID requestId) {}

  record Unlistened(UUID goal, UUID binding, int version, UUID requestId) {}

  record Listening(UUID binding, UUID goal, String topic, UUID generation) {}

  Started start(String account, Start request);

  /** Newest first in the selected project. A next cursor denotes the last returned goal. */
  Page list(String account, Listing request);

  /** Current scoped metadata with content withheld when the conversation log is restricted. */
  Status status(String account, StatusRequest request);

  /** The immutable launch handle, looked up under its original executor and exact project. */
  Started receipt(String account, ReceiptRequest request);

  /** Commits the authenticated human's answer under the exact question generation. */
  Answered answer(String account, Answer request);

  Sources sources(String account, UUID goal, String conversation, Search search);

  Listening listen(String account, Listen request);

  /** Disarm one exact binding and return the original receipt on an identical retry. */
  Unlistened unlisten(String account, Unlisten request);
}
