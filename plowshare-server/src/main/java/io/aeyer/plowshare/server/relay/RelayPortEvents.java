package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayLog;
import java.util.ArrayList;
import java.util.List;

/** Converts retained broker values at the public transport boundary, preserving typed families. */
final class RelayPortEvents {
  // Native SDKs accept 1 MiB frames. Reserve a quarter for the batch/envelope and serializer
  // differences; count encoded UTF-8 bytes, since character counts miss JSON control escaping.
  private static final int EVENTS_BYTES = 768 * 1024;
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private RelayPortEvents() {}

  /** The issued cursor must cover only the returned prefix, never records omitted for size. */
  static List<RelayLog.Event> bounded(List<Relay.Publication> publications) {
    var events = new ArrayList<RelayLog.Event>();
    int bytes = 2;
    for (var publication : publications) {
      var event = event(publication);
      final int encoded;
      try {
        encoded = JSON.writeValueAsBytes(event).length + 1;
      } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
        throw new IllegalStateException("Retained Relay event cannot be encoded", invalid);
      }
      if (bytes + encoded > EVENTS_BYTES) {
        if (events.isEmpty())
          throw new IllegalStateException("Retained Relay event exceeds the SDK frame budget");
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
