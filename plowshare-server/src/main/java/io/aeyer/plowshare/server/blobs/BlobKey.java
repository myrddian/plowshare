package io.aeyer.plowshare.server.blobs;

/** Internal content identity; neither this digest nor a storage scope grants access. */
public record BlobKey(String scope, String sha256) {
  public BlobKey {
    requireScope(scope);
    if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("invalid blob digest");
    }
  }

  public static void requireScope(String scope) {
    if (scope == null || !scope.matches("global|projects/[1-9][0-9]{0,18}")) {
      throw new IllegalArgumentException("invalid blob scope");
    }
  }
}
