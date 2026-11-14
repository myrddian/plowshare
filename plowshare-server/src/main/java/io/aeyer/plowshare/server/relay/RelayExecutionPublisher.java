package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayPort;

/** Server-owned execution provenance, disjoint from authenticated external SDK publishers. */
public final class RelayExecutionPublisher {
  private static final String PREFIX = "execution:";

  private RelayExecutionPublisher() {}

  /** Stable account/definition coordinates reveal neither account names nor credentials. */
  public static String identity(String account, String agent) {
    return PREFIX
        + RelayPort.hash(RelayPort.identity(account))
        + ":"
        + RelayPort.hash(RelayPort.identity(agent));
  }

  /**
   * Recognizes the reserved namespace, including malformed claims that must never be human actors.
   */
  public static boolean reserved(String publisher) {
    return publisher != null && publisher.startsWith(PREFIX);
  }
}
