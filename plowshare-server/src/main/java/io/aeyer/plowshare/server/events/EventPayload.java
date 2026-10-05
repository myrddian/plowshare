package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.server.board.MessageWake;
import io.aeyer.plowshare.server.board.SeatWake;
import java.time.Instant;
import java.util.Objects;

/**
 * Explicit event families. Adding a persisted family requires a DTO and codec compatibility test.
 */
public sealed interface EventPayload {
  record Empty() implements EventPayload {}

  /** Manual content is data, never an instruction or an arbitrary structured object. */
  record Text(String text) implements EventPayload {
    public Text {
      if (text == null || text.isBlank() || text.length() > 1048576 || text.indexOf('\0') >= 0)
        throw new IllegalArgumentException("event text must be bounded nonblank text");
    }
  }

  record Scheduled(String schedule, Instant fireAt) implements EventPayload {
    public Scheduled {
      schedule = identity(schedule, "schedule");
      Objects.requireNonNull(fireAt, "fireAt");
    }
  }

  record Seat(SeatWake wake) implements EventPayload {
    public Seat {
      Objects.requireNonNull(wake, "wake");
    }
  }

  record Message(MessageWake wake) implements EventPayload {
    public Message {
      Objects.requireNonNull(wake, "wake");
    }
  }

  static String identity(String value, String field) {
    if (value == null
        || value.isBlank()
        || !value.equals(value.strip())
        || value.length() > 1024
        || value
            .codePoints()
            .anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029))
      throw new IllegalArgumentException(field + " must be a bounded identity");
    return value;
  }
}
