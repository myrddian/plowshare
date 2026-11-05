package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.ToolContent;
import java.util.Objects;
import java.util.UUID;

/** Original provider content and the effective post-hook observation. No bytes live in history. */
public record ToolObservation(
    UUID invocation, ToolContent.Body original, ToolContent.Body effective) {
  public ToolObservation {
    Objects.requireNonNull(invocation);
    Objects.requireNonNull(original);
    if (original.images().isEmpty())
      throw new IllegalArgumentException("An observation needs an image");
    // Hooks can withhold pixels, but cannot substitute identities or invent another provider body.
    if (effective != null && !effective.equals(original))
      throw new IllegalArgumentException("Effective images must be the admitted provider result");
  }

  /** A text rewrite/redaction withholds pixels; unchanged successful hooks preserve them. */
  public ToolObservation afterHook(boolean unchanged) {
    return new ToolObservation(invocation, original, unchanged ? original : null);
  }
}
