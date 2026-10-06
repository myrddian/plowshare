package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayCausation;
import java.util.Optional;
import java.util.OptionalInt;

/** Durable effect ancestry, independent of leases, project routes and publication retention. */
public interface RelayForwardingHistory {
  /**
   * Resolves typed ancestry or bounded legacy forwarding admissions. Missing derived ancestry never
   * authorizes a new root. Lifecycle notices must carry causation captured with owning work.
   */
  Optional<RelayCausation> causation(Relay.Publication publication, int maximum);

  default OptionalInt depth(Relay.Publication publication, int maximum) {
    var ancestry = causation(publication, maximum);
    return ancestry
        .filter(value -> value.depth() >= 0)
        .map(value -> OptionalInt.of(value.depth()))
        .orElseGet(OptionalInt::empty);
  }
}
