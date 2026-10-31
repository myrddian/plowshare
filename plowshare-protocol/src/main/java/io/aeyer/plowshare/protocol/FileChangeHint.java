package io.aeyer.plowshare.protocol;

/**
 * Optional client-to-server file-channel hint, negotiated by fileChanges=true in the ready frame.
 * No path, hash or authority is accepted from this frame; the server reconciles the authenticated
 * socket's currently rooted workspace and verifies source hashes before any indexing.
 */
public record FileChangeHint(String type) {
  public static final String TYPE = "workspace.changed";

  public FileChangeHint {
    if (!TYPE.equals(type)) throw new IllegalArgumentException("Invalid file change hint");
  }
}
