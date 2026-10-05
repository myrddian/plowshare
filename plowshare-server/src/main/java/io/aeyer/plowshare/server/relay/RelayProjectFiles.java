package io.aeyer.plowshare.server.relay;

import java.util.Optional;

/**
 * Authorized project configuration reads, never ambient filesystem access from guest JavaScript.
 */
public interface RelayProjectFiles {
  /** Rechecks live contributor membership and the stable project identity. */
  void requireAccess(Access access);

  /**
   * Reads a bounded UTF-8 file relative to Relay/. Missing files are empty; denied, offline,
   * excluded, malformed or nonregular files fail explicitly. Each read rechecks access.
   */
  Optional<String> read(Access access, String path);

  /** Caller identity is supplied by a trusted authenticated adapter, never by configuration. */
  record Access(String account, String project, long projectId) {
    public Access {
      account = RelayValues.identity(account, "account");
      project = RelayValues.identity(project, "project");
      if (projectId < 1) throw new IllegalArgumentException("project ID must be positive");
    }
  }

  static String path(String path) {
    if (path == null
        || path.length() > 256
        || !(path.equals("active.json")
            || path.equals("topics.json")
            || path.matches(
                "[a-z][a-z0-9-]*/(?:routes\\.js|scripts/[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*\\.js)")))
      throw new IllegalArgumentException("invalid Relay configuration path");
    return path;
  }
}
