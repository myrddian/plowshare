package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Messaging-owned TEXT publication and decoding. No topic name or messaging DTO enters Relay's
 * envelope, kind vocabulary or schema. The messaging send transaction uses this sole publication
 * boundary; forum storage has no role in message admission or delivery.
 */
public final class RelayMessagePublications implements MessagePublications {
  // SDK publishers are always sdk:<hash>, derived by RelayPort.publisher; no public transport can
  // select this server-owned namespace. Exact account matching is required, not just a prefix test.
  private static final String PUBLISHER = "messaging:";
  private final Relay relay;
  private final MessagePayloadCodec codec;

  public RelayMessagePublications(Relay relay, MessagePayloadCodec codec) {
    this.relay = Objects.requireNonNull(relay, "relay");
    this.codec = Objects.requireNonNull(codec, "codec");
  }

  @Override
  public Relay.Publication publish(
      Binding binding, MessagePayload payload, Instant occurredAt, RelayCausation causation) {
    return relay.publish(binding.inbox(), draft(binding, payload, occurredAt, causation));
  }

  private Relay.Draft draft(
      Binding binding, MessagePayload payload, Instant occurredAt, RelayCausation causation) {
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(payload, "payload");
    String reply = binding.reply() == null ? null : binding.reply().message();
    if (!payload.sender().equals(binding.sender().address())
        || !payload.recipient().equals(binding.recipient().address())
        || !payload.conversation().equals(binding.recipient().conversation())
        || !Objects.equals(payload.replyTo(), reply))
      throw new IllegalArgumentException("Messaging payload differs from the bound send route");
    // Complete validation/encoding before the only side effect. Do not register a topic or grant
    // an allowance on a refused payload, and do not add a second deduplication or delivery log.
    var text = new RelayPayload.Text(codec.write(payload));
    return new Relay.Draft(
        payload.message(),
        publisher(binding.sender().account()),
        occurredAt,
        null,
        null,
        text,
        causation);
  }

  @Override
  public Optional<MessagePayload> read(String account, Relay.Publication publication) {
    Objects.requireNonNull(publication, "publication");
    if (!publisher(account).equals(publication.event().publisher())) return Optional.empty();
    // A foreign row is skipped above even if its kind or JSON is malformed. A malformed row we
    // issued is corruption and must not silently shorten the feed or hide a lost message.
    if (!(publication.topic().scope() instanceof Relay.ProjectScope)
        || !(publication.event().payload() instanceof RelayPayload.Text text))
      throw new IllegalArgumentException("Invalid messaging publication carrier");
    MessagePayload payload = codec.read(text.text());
    if (!publication.event().eventId().equals(payload.message()))
      throw new IllegalArgumentException("Messaging publication differs from its message identity");
    return Optional.of(payload);
  }

  @Override
  public Optional<Relay.Publication> retained(
      Binding binding, MessagePayload payload, Instant occurredAt, RelayCausation causation) {
    var expected = draft(binding, payload, occurredAt, causation);
    var retained = relay.retained(binding.inbox(), expected.eventId());
    if (retained.isPresent()
        && (!retained.get().topic().equals(binding.inbox())
            || !retained.get().event().equals(expected)))
      throw new IllegalStateException("Retained messaging publication conflicts with send intent");
    return retained;
  }

  private static String publisher(String account) {
    return PUBLISHER + RelayPort.hash(RelayPort.identity(account));
  }
}
