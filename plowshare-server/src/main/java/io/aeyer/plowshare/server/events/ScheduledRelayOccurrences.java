package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import java.util.Objects;
import java.util.Optional;

/** Scheduler-owned identities and compatibility evidence for independent occurrence roots. */
public final class ScheduledRelayOccurrences {
  private ScheduledRelayOccurrences() {}

  static String identity(RelayPayload.ScheduleDue payload) {
    // Schedule identities cannot contain NUL. This preserves the original occurrence identity
    // independently of broker publication time, following fire time, and causation metadata.
    return "schedule:" + RelayPort.hash(payload.schedule() + "\0" + payload.fireAt());
  }

  /**
   * Recognizes a retained occurrence emitted before the scheduler carried explicit causation.
   *
   * <p>Only the scheduler publishes this typed payload on its scope-derived topic. Check its exact
   * canonical identity, occurrence time and independent shape; arbitrary missing ancestry is never
   * a root. Explicit unknown/exhausted metadata is preserved, not repaired. This interpretation is
   * read-only: it neither rewrites retained events nor authorizes resending an uncertain effect.
   */
  public static Optional<RelayCausation> legacyRoot(Relay.Publication publication) {
    Objects.requireNonNull(publication, "publication");
    var event = publication.event();
    if (event.causation() != null
        || !(event.payload() instanceof RelayPayload.ScheduleDue occurrence)
        || !publication.topic().name().equals(EventTopics.scheduled(publication.topic().scope()))
        || !event.eventId().equals(identity(occurrence))
        || !event.occurredAt().equals(occurrence.fireAt())
        || event.correlationId() != null
        || event.causationId() != null) return Optional.empty();
    return Optional.of(RelayCausation.root(event.eventId()));
  }
}
