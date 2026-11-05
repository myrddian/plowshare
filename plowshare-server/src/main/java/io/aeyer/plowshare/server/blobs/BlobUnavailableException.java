package io.aeyer.plowshare.server.blobs;

/** Sanitized refusal; callers must not fall back to a different location or recreate an effect. */
public final class BlobUnavailableException extends IllegalStateException {
  public BlobUnavailableException() {
    super("binary blob is unavailable or conflicts with its retained identity");
  }
}
