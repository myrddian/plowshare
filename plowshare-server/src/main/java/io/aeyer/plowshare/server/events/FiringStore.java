package io.aeyer.plowshare.server.events;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Specialist persistence contract. SQL, row decoding and guarded transitions belong to its JDBC
 * implementation.
 */
public interface FiringStore {
  String PREFIX = "fir_";
  String WAKE_EVENT = "board.wake";

  Optional<FiringRecord> arrive(
      String event,
      EventPayload data,
      String schedule,
      Instant fireAt,
      TriggerRecord trigger,
      Instant at);

  /**
   * Serializes scheduled admission and queue supersession across different publication scopes.
   * Requires the owning transaction; acquire all trigger queues in stable order before inserting.
   * No job or definition-row lock is held by this operation.
   */
  void lockScheduledQueues(List<String> triggers);

  Optional<FiringRecord> owe(String topic, String target, EventPayload data, Instant at);

  /** Read-only inventory of every retained payload, including terminal rows. */
  EventCompatibility preflight();

  int supersedeWakesBeyond(String target, int cap, String newest);

  Optional<FiringRecord> find(String id);

  boolean busy(String target);

  int supersedeBeyond(String trigger, int cap, String newest);

  Optional<FiringRecord> oldestWaiting(String target);

  boolean claimStart(String id, Instant at);

  void startedAs(String id, String jobId);

  void release(String id);

  void refuse(String id, String reason);

  void finish(String id, Instant at);

  int abandonUnfinished(String reason, Instant at);

  int refuseWaiting(String trigger, String reason);

  int refuseWakes(String topic, String reason);

  List<String> targetsWaiting();

  /** At most 100 queued scheduled targets, excluding any with an unfinished started firing. */
  List<String> scheduledTargetsWaiting();

  List<FiringRecord> list(String trigger, String status, int offset, int limit);
}
