package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.protocol.RelayPort;
import java.time.Instant;
import java.util.Objects;

/** Immutable retained message text. It has no board topic, seat or forum sequence. */
public record MessageRecord(String id, String recipient, String body, Instant postedAt) {
  public MessageRecord {
    id = MessagePayload.identifier(id, "bdm_");
    recipient = MessagePayload.identifier(recipient, "ins_");
    body = RelayPort.text(body);
    Objects.requireNonNull(postedAt);
  }
}
