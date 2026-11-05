package io.aeyer.plowshare.server.images;

import io.aeyer.plowshare.server.blobs.BlobClaim;
import java.nio.file.Path;
import java.util.Optional;

/** Media-owned adapter to shared byte storage; workspace notes never become retained blobs here. */
public interface ImageBlobAccess {
  /** Validate the existing destination before a legacy writer touches it. */
  void validate(Path directory, StoredImage image);

  /** Reuse canonical held bytes, preserving image-first/BLOB-first placement and image lifetime. */
  void store(Path directory, StoredImage image, byte[] bytes, String sha256);

  /**
   * Recognize an existing managed image whose canonical file may be outside the image directory.
   */
  boolean adopted(Path directory, StoredImage image);

  /** Empty only for an unadopted image; adopted unavailability must be explicit. */
  Optional<byte[]> read(Path directory, StoredImage image);

  static BlobClaim retained(String imageId) {
    if (!ImageStore.isUid(imageId)) throw new IllegalArgumentException("invalid image id");
    return new BlobClaim("image-retention", imageId, 0, BlobClaim.Retention.PINNED);
  }
}
