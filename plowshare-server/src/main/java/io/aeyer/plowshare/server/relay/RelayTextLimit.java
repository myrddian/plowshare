package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayPort;

/**
 * Deployment policy for new TEXT publications. Retained publications and admissions use the
 * portable ceiling, so lowering this allowance never prevents recovery of existing work.
 */
public record RelayTextLimit(int bytes) {
  public static final RelayTextLimit DEFAULT = new RelayTextLimit(RelayPort.DEFAULT_TEXT_BYTES);

  public RelayTextLimit {
    if (bytes < 1 || bytes > RelayPort.MAX_TEXT_BYTES)
      throw new IllegalArgumentException("Relay max-text-bytes must be 1 through 52428800");
  }

  public void validateNew(RelayPayload payload) {
    if (payload instanceof RelayPayload.Text text) RelayPort.text(text.text(), bytes);
  }
}
