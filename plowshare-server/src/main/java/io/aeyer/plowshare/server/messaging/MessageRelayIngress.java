package io.aeyer.plowshare.server.messaging;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Owns transactional native admission/controls and immutable operation inspection. */
public interface MessageRelayIngress {
  record Request(MessageRelayRepository.Intent intent, MessageRelayPayload.Command command) {
    public Request {
      Objects.requireNonNull(intent);
      Objects.requireNonNull(command);
      if (!intent.requestId().equals(command.requestId())
          || !intent.client().equals(command.client()))
        throw new IllegalArgumentException("Native command differs from its authenticated intent");
    }
  }

  MessageRelayPayload.Snapshot accept(Request request);

  Optional<MessageRelayPayload.Snapshot> inspect(
      UUID delivery, long project, String publisher, String topic, String event);
}
