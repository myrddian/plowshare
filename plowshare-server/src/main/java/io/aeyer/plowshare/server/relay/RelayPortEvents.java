package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayLog;
import java.util.ArrayList;
import java.util.List;

/** Converts retained broker values at the public transport boundary, preserving typed families. */
final class RelayPortEvents {
  // A maximum 5 MiB TEXT can encode to 30 MiB. Reserve 1 MiB of the 32 MiB logical message for
  // envelope/inspection metadata; count encoded UTF-8 bytes, including control escaping.
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private RelayPortEvents() {}

  /** The issued cursor must cover only the returned prefix, never records omitted for size. */
  static List<RelayLog.Event> bounded(List<Relay.Publication> publications) {
    return bounded(publications, RelayReadBudget.SEGMENTED);
  }

  static List<RelayLog.Event> bounded(
      List<Relay.Publication> publications, RelayReadBudget budget) {
    return boundedEvents(publications.stream().map(RelayPortEvents::event).toList(), budget);
  }

  static List<RelayLog.Event> boundedEvents(List<RelayLog.Event> offered, RelayReadBudget budget) {
    var events = new ArrayList<RelayLog.Event>();
    int bytes = 2;
    for (var event : offered) {
      final int encoded;
      try {
        encoded = JSON.writeValueAsBytes(event).length + 1;
      } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
        throw new IllegalStateException("Retained Relay event cannot be encoded", invalid);
      }
      if (bytes + encoded > budget.bytes()) {
        if (events.isEmpty())
          throw new io.aeyer.plowshare.server.faults.CallerFault(
              "Retained Relay event exceeds the transport allowance; use segmented transport");
        break;
      }
      bytes += encoded;
      events.add(event);
    }
    return List.copyOf(events);
  }

  static RelayLog.Event event(Relay.Publication publication) {
    var draft = publication.event();
    var payload = draft.payload();
    return new RelayLog.Event(
        Long.toString(publication.position()),
        draft.eventId(),
        draft.publisher(),
        draft.occurredAt(),
        publication.publishedAt(),
        draft.correlationId(),
        draft.causationId(),
        new RelayLog.Payload(
            payload.kind().name(),
            payload instanceof RelayPayload.Text text ? text.text() : null,
            payload instanceof RelayPayload.ScheduleDue due ? due.schedule() : null,
            payload instanceof RelayPayload.ScheduleDue due ? due.emits() : null,
            payload instanceof RelayPayload.ScheduleDue due ? due.fireAt() : null,
            payload instanceof RelayPayload.Lifecycle c
                ? new RelayLog.Lifecycle(
                    c.source(), c.subject(), c.state(), c.context(), c.related())
                : null,
            payload instanceof RelayPayload.WakeRequested w
                ? new RelayLog.Wake(w.firing(), w.target(), w.type().name())
                : null),
        draft.causation());
  }
}
