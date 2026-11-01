package io.aeyer.plowshare.server.media;

import io.aeyer.plowshare.protocol.ImageFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validated file properties, independent of storage identity, owner, model support and token cost.
 * SHA-256 covers the exact original file, including metadata; it is not a disclosure capability.
 */
public record ImageDescriptor(
    ImageFormat format, int width, int height, long byteLength, String sha256) {
  private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

  public ImageDescriptor {
    Objects.requireNonNull(format, "format");
    if (width < 1 || height < 1 || byteLength < 1) {
      throw new IllegalArgumentException("image dimensions and byte length must be positive");
    }
    if (sha256 == null || !DIGEST.matcher(sha256).matches()) {
      throw new IllegalArgumentException("image SHA-256 must be 64 lowercase hexadecimal digits");
    }
  }

  /** The exact detected MIME, using the existing shared image-format contract. */
  public String mimeType() {
    return format.mediaType();
  }
}
