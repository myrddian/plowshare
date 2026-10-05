package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.events.EventPayloadCodec;
import io.aeyer.plowshare.server.events.FiringRecord;
import java.time.Instant;

/** v1 keeps data as a JSON string. Serialization belongs here, outside event application logic. */
record FiringView(
    String id,
    String event,
    String data,
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
  static FiringView of(FiringRecord firing) {
    return new FiringView(
        firing.id(),
        firing.event(),
        EventPayloadCodec.write(firing.data()),
        firing.schedule(),
        firing.fireAt(),
        firing.trigger(),
        firing.target(),
        firing.status(),
        firing.supersededBy(),
        firing.reason(),
        firing.jobId(),
        firing.arrivedAt(),
        firing.startedAt(),
        firing.finishedAt(),
        firing.topic());
  }
}
