package io.aeyer.plowshare.protocol;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Invocation-bound image admission, independent of the ordinary image upload response. */
public final class ImageAdmissions {
  public static final String PROFILE = "invocation-image/1";
  public static final int MAX_ARTIFACTS = 16;

  private ImageAdmissions() {}

  /** Stable identity for one retained invocation slot; a retry must keep the same bytes. */
  public record Request(UUID invocation, int ordinal, UUID requestId) {
    public Request {
      Objects.requireNonNull(invocation, "invocation");
      if (ordinal < 0 || ordinal >= MAX_ARTIFACTS) {
        throw new IllegalArgumentException("artifact ordinal must be between 0 and 15");
      }
      if (!ImageAdmissions.requestId(invocation, ordinal).equals(requestId)) {
        throw new IllegalArgumentException("artifact request identity does not match its slot");
      }
    }
  }

  public static UUID requestId(UUID invocation, int ordinal) {
    Objects.requireNonNull(invocation, "invocation");
    if (ordinal < 0 || ordinal >= MAX_ARTIFACTS)
      throw new IllegalArgumentException("invalid artifact ordinal");
    String hash = RelayPort.hash(PROFILE + "\n" + invocation + "\n" + ordinal).substring(0, 32);
    return UUID.fromString(
        hash.substring(0, 8)
            + "-"
            + hash.substring(8, 12)
            + "-"
            + hash.substring(12, 16)
            + "-"
            + hash.substring(16, 20)
            + "-"
            + hash.substring(20));
  }

  /** No bytes, filename, path, URL or access token; the original invocation owns this receipt. */
  public record Receipt(
      Request request,
      String project,
      String owner,
      String producer,
      String imageId,
      String mimeType,
      int width,
      int height,
      long byteLength,
      String sha256,
      String admittedAt) {
    public Receipt {
      Objects.requireNonNull(request);
      if (Home.of(project).isGlobal())
        throw new IllegalArgumentException("artifact requires a project");
      ProviderCredentials.subject(owner);
      ProviderCredentials.subject(producer);
      if (imageId == null
          || !imageId.matches("img_[0-9a-f]{32}")
          || sha256 == null
          || !sha256.matches("[0-9a-f]{64}")
          || !imageId.equals("img_" + sha256.substring(0, 32))
          || !supportedMimeType(mimeType)
          || width < 1
          || width > 32768
          || height < 1
          || height > 32768
          || (long) width * height > 64_000_000
          || byteLength < 1
          || byteLength > Integer.MAX_VALUE) {
        throw new IllegalArgumentException("invalid admitted image descriptor");
      }
      Instant.parse(admittedAt);
    }
  }

  /** Formats with complete bounded validation in the invocation admission path. */
  public static boolean supportedMimeType(String mimeType) {
    return "image/png".equals(mimeType)
        || "image/jpeg".equals(mimeType)
        || "image/webp".equals(mimeType);
  }

  public enum State {
    COMMITTED,
    ABSENT,
    UNAVAILABLE
  }

  /** ABSENT means no committed receipt; it does not prove no disk write occurred. */
  public record Status(State state, Receipt receipt) {
    public Status {
      Objects.requireNonNull(state);
      if ((state == State.ABSENT) != (receipt == null)) {
        throw new IllegalArgumentException("artifact status receipt does not match its state");
      }
    }
  }
}
