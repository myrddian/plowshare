package io.aeyer.plowshare.a2a;

import java.io.IOException;

/** Requested A2A view exceeds the supported response cap; retained task/body evidence is intact. */
public final class IngressResponseTooLarge extends IOException {
  public IngressResponseTooLarge() {
    super(
        "Task accepted; requested history exceeds1MiB. Recover with the same messageId/payload and smaller historyLength. Bodies were not truncated.");
  }
}
