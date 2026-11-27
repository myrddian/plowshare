package io.aeyer.plowshare.server.events;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record FiringRecord(
    String id,
    String event,
    EventPayload data,
    String schedule,
    Instant fireAt,
    String trigger,
    String target,
    String status,
    String supersededBy,
    String reason,
    String jobId,
    Instant arrivedAt,
    Instant startedAt,
    Instant finishedAt,
    String topic,
    @JsonIgnore UUID scheduleGeneration) {
  public FiringRecord(
      String id,
      String event,
      EventPayload data,
      String schedule,
      Instant fireAt,
      String trigger,
      String target,
      String status,
      String supersededBy,
      String reason,
      String jobId,
      Instant arrivedAt,
      Instant startedAt,
      Instant finishedAt,
      String topic) {
    this(
        id,
        event,
        data,
        schedule,
        fireAt,
        trigger,
        target,
        status,
        supersededBy,
        reason,
        jobId,
        arrivedAt,
        startedAt,
        finishedAt,
        topic,
        null);
  }

  /** Internal evidence from a scheduler claim; never accepted from a message body. */
  public record GoalSchedule(String schedule, String firing, UUID generation, Instant fireAt) {
    public GoalSchedule {
      schedule = EventPayload.identity(schedule, "schedule");
      firing = EventPayload.identity(firing, "firing");
      Objects.requireNonNull(generation, "schedule generation");
      Objects.requireNonNull(fireAt, "fire time");
    }
  }

  public GoalSchedule goalSchedule() {
    return scheduleGeneration == null
        ? null
        : new GoalSchedule(schedule, id, scheduleGeneration, fireAt);
  }

  public FiringRecord {
    id = EventPayload.identity(id, "firing");
    event = EventPayload.identity(event, "event");
    Objects.requireNonNull(data, "data");
    Objects.requireNonNull(arrivedAt, "arrivedAt");
    if (!Set.of("unmatched", "queued", "started", "superseded", "refused").contains(status))
      throw new IllegalArgumentException("invalid firing status");
    for (String value : new String[] {schedule, trigger, target, supersededBy, jobId, topic})
      if (value != null) EventPayload.identity(value, "firing identity");
    if (reason != null && (reason.length() > 32768 || reason.indexOf('\0') >= 0))
      throw new IllegalArgumentException("invalid firing reason");
    EventPayloadCodec.require(event, topic, schedule, fireAt, data);
    if (scheduleGeneration != null
        && (!(data instanceof EventPayload.Scheduled) || !schedule.equals(trigger)))
      throw new IllegalArgumentException("A Goal schedule occurrence requires its own trigger");
  }
}
