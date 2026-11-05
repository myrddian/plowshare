package io.aeyer.plowshare.server.media;

/**
 * A typed, sanitized refusal. Parser messages, paths, metadata and bytes never escape this
 * boundary.
 */
public final class MediaInspectionException extends RuntimeException {
  /** Distinguishes resource/format refusal from malformed input and retryable capacity pressure. */
  public enum Reason {
    EMPTY,
    TOO_LARGE,
    UNSUPPORTED_FORMAT,
    INVALID_IMAGE,
    ANIMATION,
    DIMENSIONS,
    TOO_COMPLEX,
    BUSY
  }

  private final Reason reason;

  MediaInspectionException(Reason reason) {
    super(
        switch (reason) {
          case EMPTY -> "media inspection requires nonempty bytes";
          case TOO_LARGE -> "media exceeds plowshare.images.max-bytes";
          case UNSUPPORTED_FORMAT -> "media inspection supports still PNG, JPEG and WebP images";
          case INVALID_IMAGE -> "media is not a complete valid supported image";
          case ANIMATION -> "animated or multi-picture files are not supported for image admission";
          case DIMENSIONS -> "image exceeds the configured inspection dimensions or pixel limit";
          case TOO_COMPLEX -> "image exceeds the inspection structure limit";
          case BUSY ->
              "media inspection capacity is busy; retry without repeating external effects";
        });
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
