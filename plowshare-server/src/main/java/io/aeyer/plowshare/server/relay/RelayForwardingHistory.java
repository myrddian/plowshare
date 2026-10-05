package io.aeyer.plowshare.server.relay;

import java.util.OptionalInt;

/** Bounded durable ancestry for built-in publication forwarding, independent of topic retention. */
public interface RelayForwardingHistory {
  /**
   * Counts broker forwarding ancestors up to the supplied maximum. Non-forwarded input has depth
   * zero. Missing or inconsistent retained ancestry returns empty and cannot authorize another hop.
   */
  OptionalInt depth(Relay.Publication publication, int maximum);
}
