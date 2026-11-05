package io.aeyer.plowshare.server.blobs;

import java.util.Objects;

/** A stable owning-record slot. Reading or retrying never creates a new claim identity. */
public record BlobClaim(String kind, String owner, int slot, Retention retention) {
  public enum Retention {
    PINNED,
    RELEASABLE
  }

  public BlobClaim {
    if (kind == null
        || !kind.matches("[a-z][a-z0-9._-]{0,63}")
        || owner == null
        || !owner.matches("[A-Za-z0-9_.:-]{1,256}")
        || slot < 0
        || slot > 65535) {
      throw new IllegalArgumentException("invalid blob claim");
    }
    Objects.requireNonNull(retention);
  }
}
