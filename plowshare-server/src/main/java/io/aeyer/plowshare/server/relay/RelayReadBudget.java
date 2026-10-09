package io.aeyer.plowshare.server.relay;

/**
 * Server-owned response capability, never supplied by a topic publisher or treated as authority.
 */
public enum RelayReadBudget {
  LEGACY(768 * 1024),
  SEGMENTED(
      io.aeyer.plowshare.protocol.transport.SegmentedMessages.MAX_MESSAGE_BYTES - 1024 * 1024);
  private final int bytes;

  RelayReadBudget(int bytes) {
    this.bytes = bytes;
  }

  public int bytes() {
    return bytes;
  }
}
