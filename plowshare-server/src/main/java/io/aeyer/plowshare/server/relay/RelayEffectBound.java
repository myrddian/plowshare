package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayCausation;

/** One effect budget for both forwarding and work; lifecycle publication consumes no extra hop. */
final class RelayEffectBound {
  private RelayEffectBound() {}

  static RelayCausation next(RelayForwardingHistory history, Relay.Publication input, int maximum) {
    var cause =
        history
            .causation(input, maximum)
            .orElseThrow(() -> new RelayReceiver.Refused("receiver.causation-unavailable"));
    if (cause.depth() < 0) throw new RelayReceiver.Refused("receiver.causation-unavailable");
    if (cause.depth() >= maximum)
      throw new RelayReceiver.Refused("receiver.causation-limit.refused");
    return cause.next(input.event().eventId(), maximum);
  }

  static boolean inspecting(RelayReceiver.Request request) {
    return request.delivery().state() == RelayDeliveries.State.UNCERTAIN
        || request.delivery().state() == RelayDeliveries.State.ABANDONED_UNCERTAIN;
  }
}
