package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.server.blobs.BlobClaim;
import io.aeyer.plowshare.server.blobs.BlobKey;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.Content;
import java.util.List;

/** Independent entry-owned image references; no Relay lifecycle or filesystem work. */
public interface TrajectoryArtifacts {
  /** Text fixtures cannot silently accept image results without independent storage ownership. */
  TrajectoryArtifacts UNAVAILABLE =
      new TrajectoryArtifacts() {
        public void retain(EntryRecord entry, String job) {
          throw new IllegalStateException("Trajectory image claims are unavailable");
        }

        public List<Image> read(List<Content.StoredImage> images, UsageAttribution usage) {
          throw new IllegalStateException("Trajectory image claims are unavailable");
        }
      };

  /**
   * Joins entry append's transaction; validates exact invocation/run/call/owner and claims every
   * raw image.
   */
  void retain(EntryRecord entry, String job);

  record Image(
      Content.StoredImage address, BlobKey key, BlobClaim claim, long byteLength, String mimeType) {
    public Image {
      java.util.Objects.requireNonNull(address);
      java.util.Objects.requireNonNull(key);
      java.util.Objects.requireNonNull(claim);
      if (!io.aeyer.plowshare.protocol.ImageAdmissions.supportedMimeType(mimeType)
          || byteLength < 1
          || byteLength > Integer.MAX_VALUE
          || !claim.kind().equals("trajectory-entry")
          || !claim.owner().equals(address.conversation() + ":" + address.entry())
          || claim.slot() != address.slot()
          || claim.retention() != BlobClaim.Retention.RELEASABLE)
        throw new IllegalArgumentException("Invalid retained trajectory image");
    }
  }

  /**
   * One bounded authorized batch; only effective native observations of this conversation are
   * readable.
   */
  List<Image> read(List<Content.StoredImage> images, UsageAttribution usage);
}
