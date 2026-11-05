package io.aeyer.plowshare.server.images;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.media.ImageDescriptor;

/** Held immutable snapshots in the existing image store; callers supply admission authority. */
public interface ImageSnapshots {
  /** Existing deployment raw-byte ceiling, applied before copying caller bytes. */
  int maxBytes();

  /**
   * Finalizes original bytes and the existing sidecar before returning. Verifies full digest before
   * deduplication; promotes workspace notes without reading their mutable source. Existing metadata
   * wins for an already-held object. Claim-aware storage registers its image pin in a short
   * transaction after disk finalization; the invocation receipt transaction remains separate.
   */
  StoredImage hold(Home home, String filename, byte[] bytes, ImageDescriptor descriptor);

  /**
   * Reconciles both held bytes and sidecar against a retained descriptor; never follows a workspace
   * path.
   */
  boolean available(Home home, String id, ImageDescriptor descriptor);
}
