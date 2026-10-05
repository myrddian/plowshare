package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.server.events.EventPayload;
import io.aeyer.plowshare.server.events.FiringRecord;
import java.util.Optional;

/** Typed projection only: JSON decoding belongs to the firing repository. */
final class MessageWakeCodec {
  private MessageWakeCodec() {}

  static Optional<MessageWake> read(FiringRecord firing) {
    return firing.data() instanceof EventPayload.Message message
        ? Optional.of(message.wake())
        : Optional.empty();
  }
}
