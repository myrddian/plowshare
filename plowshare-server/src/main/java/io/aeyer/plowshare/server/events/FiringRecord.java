package io.aeyer.plowshare.server.events;

import java.time.Instant;

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
    String topic) {
  public FiringRecord {
    id = EventPayload.identity(id, "firing");
    event = EventPayload.identity(event, "event");
    java.util.Objects.requireNonNull(data, "data");
    java.util.Objects.requireNonNull(arrivedAt, "arrivedAt");
    if (!java.util.Set.of("unmatched", "queued", "started", "superseded", "refused")
        .contains(status)) throw new IllegalArgumentException("invalid firing status");
    for (String value : new String[] {schedule, trigger, target, supersededBy, jobId, topic})
      if (value != null) EventPayload.identity(value, "firing identity");
    if (reason != null && (reason.length() > 32768 || reason.indexOf('\0') >= 0))
      throw new IllegalArgumentException("invalid firing reason");
    EventPayloadCodec.require(event, topic, schedule, fireAt, data);
  }
}
