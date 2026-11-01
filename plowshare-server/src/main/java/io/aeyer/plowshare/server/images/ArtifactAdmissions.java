package io.aeyer.plowshare.server.images;

import io.aeyer.plowshare.protocol.ImageAdmissions;

/**
 * Invocation-scoped producer capability; no gallery, generic filesystem access or new job runtime.
 */
public interface ArtifactAdmissions {
  /**
   * Authorizes before validation/write, then rechecks authority and invocation settlement at
   * commit.
   */
  ImageAdmissions.Receipt admit(
      String actor, ImageAdmissions.Request request, String filename, byte[] bytes);

  /**
   * Read-only reconciliation under current producer authority; absent never authorizes recapture.
   */
  ImageAdmissions.Status status(String actor, ImageAdmissions.Request request);
}
