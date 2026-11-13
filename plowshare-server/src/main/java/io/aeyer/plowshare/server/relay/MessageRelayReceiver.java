package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.messaging.MessageRelayAccess;
import io.aeyer.plowshare.server.messaging.MessageRelayCodec;
import io.aeyer.plowshare.server.messaging.MessageRelayIngress;
import io.aeyer.plowshare.server.messaging.MessageRelayPayload;
import io.aeyer.plowshare.server.messaging.MessageRelayRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Registered owning admission, cancellation and inspection; work and authority never come from
 * TEXT.
 */
public final class MessageRelayReceiver implements RelayReceiver {
  private final String receiver;
  private final MessageRelayIngress ingress;
  private final MessageRelayAccess access;
  private final RelayPublisherAccounts publishers;
  private final RelayRepository topics;
  private final RelayForwardingHistory history;
  private final int maximum;
  private final Supplier<Instant> clock;

  public MessageRelayReceiver(
      String receiver,
      MessageRelayIngress ingress,
      MessageRelayAccess access,
      RelayPublisherAccounts publishers,
      RelayRepository topics,
      RelayForwardingHistory history,
      int maximum,
      Supplier<Instant> clock) {
    if (!Set.of("message.receive", "message.cancel", "message.inspect").contains(receiver))
      throw new IllegalArgumentException("Unsupported messaging receiver");
    this.receiver = receiver;
    this.ingress = Objects.requireNonNull(ingress);
    this.access = Objects.requireNonNull(access);
    this.publishers = Objects.requireNonNull(publishers);
    this.topics = Objects.requireNonNull(topics);
    this.history = Objects.requireNonNull(history);
    RelayValues.limit(maximum, 32);
    this.maximum = maximum;
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public void require(Request request) {
    authenticated(request);
  }

  @Override
  public Result dispatch(Request request) {
    if (request.delivery().state() != RelayDeliveries.State.DISPATCHING)
      throw new IllegalArgumentException("Native messaging requires committed dispatch intent");
    var command = authenticated(request);
    ingress.accept(command);
    return new Settled(receipt(request));
  }

  @Override
  public Optional<RelayDeliveries.Resolution> inspect(Request request) {
    if (!RelayEffectBound.inspecting(request))
      throw new IllegalArgumentException("Native receipt inspection requires uncertainty");
    var publication = request.delivery().publication();
    return ingress
        .inspect(
            request.identity(),
            request.access().projectId(),
            publication.event().publisher(),
            publication.topic().name(),
            publication.event().eventId())
        .map(snapshot -> receipt(request));
  }

  private RelayDeliveries.Accepted receipt(Request request) {
    return new RelayDeliveries.Accepted(
        new RelayDeliveries.Receipt("message.operation", request.identity().toString()));
  }

  private MessageRelayIngress.Request authenticated(Request request) {
    var delivery = request.delivery();
    var branch = delivery.branch();
    if (!receiver.equals(branch.receiver())) throw new Refused("message.receiver.mismatch");
    RelayWork.require(receiver, branch.work(), branch.handler() != null);
    var publication = delivery.publication();
    if (!(publication.event().payload() instanceof RelayPayload.Text text))
      throw new Refused("message.payload.refused");
    MessageRelayPayload.Command command;
    try {
      command = MessageRelayCodec.command(receiver, text.text());
    } catch (IllegalArgumentException invalid) {
      throw new Refused("message.payload.refused");
    }
    var execution = publishers.executionIdentity(publication);
    String account =
        execution
            .map(RelayPublisherAccounts.Execution::account)
            .orElseGet(
                () -> {
                  if (RelayExecutionPublisher.reserved(publication.event().publisher()))
                    throw new Refused("message.publisher.unavailable");
                  return publishers
                      .publisherAccount(publication.event().publisher())
                      .orElseThrow(() -> new Refused("message.publisher.unavailable"));
                });
    Relay.Topic source, reply;
    try {
      source = topics.topic(publication.topic());
      reply =
          topics.topic(
              new Relay.TopicKey(request.access().projectId(), branch.work().replyTopic()));
    } catch (IllegalStateException missing) {
      throw new Refused("message.topic.unavailable");
    }
    if (source.kind() != RelayPayload.Kind.TEXT
        || reply.kind() != RelayPayload.Kind.TEXT
        || delivery.sourceGeneration() == null
        || !delivery.sourceGeneration().equals(source.generation())
        || reply.generation() == null) throw new Refused("message.topic.refused");
    String provenance = execution.map(value -> "execution:" + value.definition()).orElse("sdk");
    // Branch/receiver names are deliberately absent: receive, inspect and cancel share one
    // operator-pinned channel family while distinct topic incarnations and producers stay apart.
    String namespace =
        "relay-"
            + RelayPort.hash(
                request.access().projectId()
                    + "\n"
                    + delivery.sourceGeneration()
                    + "\n"
                    + publication.topic().name()
                    + "\n"
                    + delivery.key().subscription().subscriber()
                    + "\n"
                    + publication.event().publisher()
                    + "\n"
                    + provenance
                    + "\n"
                    + branch.work().channel());
    var family =
        new MessageRelayRepository.Family(
            namespace,
            account,
            request.access().project(),
            request.access().projectId(),
            publication.event().publisher(),
            publication.topic().name(),
            delivery.key().subscription().subscriber(),
            delivery.sourceGeneration(),
            branch.work().channel(),
            execution.map(RelayPublisherAccounts.Execution::definition).orElse(null),
            branch.work().agent(),
            branch.work().replyTopic(),
            reply.generation());
    try {
      access.require(family);
    } catch (CallerFault denied) {
      throw new Refused("message.publisher.scope");
    }
    // Inspection only captures a notice. Exhausted or unavailable effect ancestry must never
    // prevent read-only recovery, and absent history is not evidence of an independent root.
    var causation =
        receiver.equals("message.inspect")
            ? publication.event().causation()
            : RelayEffectBound.next(history, publication, maximum);
    var intent =
        new MessageRelayRepository.Intent(
            request.identity(),
            family,
            command.client(),
            command.requestId(),
            publication.event().eventId(),
            receiver,
            RelayPort.hash(receiver + "\n" + text.text() + "\n" + publication.event().eventId()),
            causation,
            clock.get());
    return new MessageRelayIngress.Request(intent, command);
  }
}
