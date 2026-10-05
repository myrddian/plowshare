package io.aeyer.plowshare.server.relay;

import java.util.Objects;
import java.util.Optional;

/**
 * Same-project publication adapter with a pinned target and stable delivery-derived event identity.
 * The target must already have an explicitly configured compatible payload family and policy.
 */
public final class ForwardRelayReceiver implements RelayReceiver {
  private final Relay relay;
  private final RelayForwardingHistory history;
  private final int maxHops;

  public ForwardRelayReceiver(Relay relay, RelayForwardingHistory history, int maxHops) {
    this.relay = Objects.requireNonNull(relay);
    this.history = Objects.requireNonNull(history);
    RelayValues.limit(maxHops, 32);
    this.maxHops = maxHops;
  }

  @Override
  public void require(Request request) {
    if (!request.delivery().branch().receiver().equals("relay.publish"))
      throw new Refused("receiver.binding.invalid");
    if (request.delivery().publication().topic().equals(target(request)))
      throw new Refused("receiver.self-publication.refused");
    if (request.delivery().branch().handler() != null)
      throw new Refused("receiver.handler.unsupported");
    // Receipt reconciliation is read-only and must survive ancestry cleanup or a policy change.
    if (request.delivery().state() != RelayDeliveries.State.UNCERTAIN
        && request.delivery().state() != RelayDeliveries.State.ABANDONED_UNCERTAIN) {
      var depth = history.depth(request.delivery().publication(), maxHops);
      if (depth.isEmpty()) throw new Refused("receiver.forwarding-history.unavailable");
      if (depth.getAsInt() >= maxHops) throw new Refused("receiver.forwarding-limit.refused");
    }
    if (relay.topic(target(request)).kind()
        != request.delivery().publication().event().payload().kind())
      throw new Refused("receiver.payload-family.refused");
  }

  @Override
  public Result dispatch(Request request) {
    if (request.delivery().state() != RelayDeliveries.State.DISPATCHING)
      throw new IllegalArgumentException("publication requires prepared dispatch intent");
    require(request);
    var published = relay.publish(target(request), draft(request));
    return new Settled(new RelayDeliveries.Accepted(receipt(published)));
  }

  @Override
  public Optional<RelayDeliveries.Resolution> inspect(Request request) {
    if (request.delivery().state() != RelayDeliveries.State.UNCERTAIN
        && request.delivery().state() != RelayDeliveries.State.ABANDONED_UNCERTAIN)
      throw new IllegalArgumentException("publication inspection requires an uncertain dispatch");
    require(request);
    Relay.Draft expected = draft(request);
    return relay
        .retained(target(request), expected.eventId())
        .map(
            publication -> {
              if (!publication.event().equals(expected))
                throw new Refused("receiver.receipt.conflict");
              return new RelayDeliveries.Accepted(receipt(publication));
            });
  }

  private static Relay.TopicKey target(Request request) {
    return new Relay.TopicKey(
        request.access().projectId(), request.delivery().branch().publishTo());
  }

  private static Relay.Draft draft(Request request) {
    var original = request.delivery().publication().event();
    return new Relay.Draft(
        "relay:" + request.identity(),
        "relay:" + request.delivery().branch().receiver(),
        original.occurredAt(),
        original.correlationId(),
        original.eventId(),
        original.payload());
  }

  private static RelayDeliveries.Receipt receipt(Relay.Publication publication) {
    return new RelayDeliveries.Receipt(
        "relay.publication",
        publication.topic().projectId()
            + ":"
            + publication.topic().name()
            + ":"
            + publication.position());
  }
}
