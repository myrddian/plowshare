package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;

/**
 * Server-owned provenance for held message reviews. SDK replies retain this root but cannot
 * authorize native work or forwarding: starting filtered work from a review would create another
 * review with a fresh budget. Ordinary independent SDK ingress uses UUID roots outside this
 * namespace.
 */
public final class RelayReviewCausation {
  private static final String PREFIX = "filter-review:";

  private RelayReviewCausation() {}

  /** Creates the reserved root for a validated review request UUID. */
  public static RelayCausation root(String requestId) {
    RelayPort.uuid(requestId);
    return RelayCausation.root(PREFIX + requestId);
  }

  static boolean isReview(RelayCausation cause) {
    return cause.rootId().startsWith(PREFIX);
  }
}
