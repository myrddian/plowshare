package io.aeyer.plowshare.server.blobs;

import java.util.Objects;

/** Trusted disk-adapter input, never a public request DTO. Location is relative to DataLayout. */
public record BlobSource(BlobKey key, String file, long byteLength) {
  public BlobSource {
    Objects.requireNonNull(key);
    if (file == null
        || file.length() > 1024
        || !file.startsWith(key.scope() + "/")
        || !file.matches("[A-Za-z0-9_./-]+")
        || java.util.Arrays.stream(file.split("/", -1))
            .anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))
        || byteLength < 1
        || byteLength > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("invalid blob location or length");
    }
  }
}
