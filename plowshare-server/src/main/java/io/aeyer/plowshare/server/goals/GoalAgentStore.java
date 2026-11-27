package io.aeyer.plowshare.server.goals;

import io.aeyer.plowshare.server.events.FiringRecord.GoalSchedule;
import io.aeyer.plowshare.server.goals.GoalAgents.Batch;
import io.aeyer.plowshare.server.goals.GoalAgents.Unlisten;
import io.aeyer.plowshare.server.goals.GoalAgents.Unlistened;
import io.aeyer.plowshare.server.messaging.MessageService.Instance;
import io.aeyer.plowshare.server.relay.Relay;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Durable Goal inbox, exact-generation bindings, and per-turn input ownership. */
interface GoalAgentStore {
  enum Failure {
    SOURCE_CHANGED,
    RETENTION_GAP,
    UNSUPPORTED_PUBLICATION
  }

  enum Disposition {
    HANDLED,
    STALE_SCHEDULE,
    DISARMED,
    GOAL_ENDED,
    SOURCE_UNAVAILABLE,
    TURN_FAILED,
    UNCERTAIN
  }

  record Listener(
      UUID id, UUID goal, Relay.TopicKey topic, UUID generation, UUID cursor, Batch batch) {
    Relay.SubscriptionKey subscription() {
      return new Relay.SubscriptionKey(topic, "goal." + id);
    }
  }

  record Event(long position, String eventId, String text) {}

  record RelayBatch(UUID id, List<Event> events) {
    public RelayBatch {
      events = List.copyOf(events);
    }
  }

  record DueBatch(UUID id, UUID goal) {}

  record ListenerFailure(UUID binding, Failure reason, Relay.Gap gap) {
    String detail() {
      return "Relay listener "
          + binding
          + " stopped: "
          + reason
          + (gap == null
              ? ""
              : " (" + (gap.afterExclusive() + 1) + "–" + gap.throughInclusive() + ")");
    }
  }

  record Input(
      long sequence, String message, GoalSchedule schedule, Listener listener, RelayBatch batch) {
    boolean automatic() {
      return schedule != null || listener != null;
    }
  }

  record SourceFence(GoalSchedule schedule, Listener listener) {}

  record AddressedSettlement(long sequence, String message, Disposition disposition) {}

  <T> T transaction(Supplier<T> write);

  UUID launch(String actor, UUID request, String fingerprint, Supplier<UUID> create);

  /** Locks the exact dedicated goal before working in its inbox. */
  boolean lockOpen(UUID goal, UUID admission);

  boolean owns(String instance);

  boolean stage(Instance instance, String message);

  /** Locks and resolves the exact active inbox, or returns empty for non-Goal instances. */
  Optional<UUID> lockMailbox(Instance instance);

  boolean scheduleTarget(Instance instance);

  /** Check the exact durable firing and current binding; lock through commit when requested. */
  boolean hasSchedule(UUID goal, GoalSchedule firing, boolean lock);

  void stageSchedule(UUID goal, String message, GoalSchedule firing);

  /** The source of the exact original activation, including one already marked handled. */
  Optional<SourceFence> claimedSource(UUID goal, String job);

  /** Preserve the original schedule generation through continuation after firing completion. */
  boolean currentSchedule(UUID goal, GoalSchedule firing, boolean lock);

  /** Check the exact retained listener generation without reviving a removed binding. */
  boolean currentListener(Listener listener);

  /** Dispose a never-claimed input while the caller holds its current Goal row. */
  void disposeUnclaimed(UUID goal, long sequence, Disposition disposition);

  /** Authoritative queue backpressure while the owning goal lock is held. */
  boolean canCollect(UUID goal, int limit);

  Listener bind(UUID goal, Relay.TopicKey topic, UUID generation, Batch batch);

  void retainCursor(Listener listener, UUID generation);

  boolean standing(UUID goal);

  boolean directPending(UUID goal);

  Optional<ListenerFailure> failedListener(UUID goal);

  List<Listener> listeners(UUID after, int limit);

  /** Checks a worker selection under the Goal lock, independently of source authorization. */
  boolean collectible(Listener listener);

  boolean fail(Listener listener, ListenerFailure failure);

  /**
   * The first event's persisted deadline is fixed. Stage and advance the broker cursor atomically.
   */
  void stage(Listener listener, Relay.Publication publication, Instant now);

  List<DueBatch> dueBatches(Instant now, int limit);

  void mature(DueBatch batch, Instant now);

  Optional<Input> next(UUID goal, boolean automatic);

  default Optional<Input> next(UUID goal) {
    return next(goal, true);
  }

  /** Recheck the selected sequence, including an empty frontier, under the Goal lock. */
  boolean first(UUID goal, Long sequence, boolean automatic);

  void claim(Input input, String job);

  /** Settles only the input that the specified turn actually owns. */
  void settle(String job, Disposition disposition);

  /** Disarms future delivery and settles never-claimed inputs in the Goal transaction. */
  void end(UUID goal);

  /** Finish the cycle while keeping its persistent Goal and wake bindings. */
  void park(UUID goal);

  /** Extends the active window without replacing its identity or spending baseline. */
  void extendCycle(UUID goal, int calls);

  Optional<Unlistened> unlistenReceipt(String actor, Unlisten request);

  boolean recordUnlisten(String actor, Unlisten request);

  boolean remove(UUID goal, UUID listener);

  List<Listener> retirements(int limit);

  boolean lockRetirement(Listener listener);

  void retired(Listener listener);

  List<AddressedSettlement> addressedSettlements(int limit);

  void acknowledged(long sequence);
}
