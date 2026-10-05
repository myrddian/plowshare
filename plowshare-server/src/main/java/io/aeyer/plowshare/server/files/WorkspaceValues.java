package io.aeyer.plowshare.server.files;

import io.aeyer.plowshare.protocol.Home;
import java.nio.charset.StandardCharsets;

/** Pure manifest identities and field checks; no filesystem or database access. */
final class WorkspaceValues {
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper();

  private WorkspaceValues() {}

  static String identity(String value, String field) {
    if (value == null
        || value.isBlank()
        || value.length() > 1024
        || !value.equals(value.strip())
        || value
            .codePoints()
            .anyMatch(code -> Character.isISOControl(code) || code == 0x2028 || code == 0x2029))
      throw new IllegalArgumentException("invalid code tracking " + field);
    return value;
  }

  static String path(String value, String field) {
    if (value == null
        || value.isBlank()
        || value.length() > 8192
        || value
            .codePoints()
            .anyMatch(code -> Character.isISOControl(code) || code == 0x2028 || code == 0x2029))
      throw new IllegalArgumentException("invalid code tracking " + field);
    return value;
  }

  /** Fixed-position JSON encoding preserves the already persisted scope identity. */
  static String scopeId(Home home, String owner, String agent, String session) {
    try {
      byte[] encoded =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .writeValueAsBytes(
                  new String[] {
                    home.isGlobal() ? null : home.project(),
                    owner,
                    agent,
                    home.isGlobal() ? session : null
                  });
      return FileContents.sha256(encoded);
    } catch (java.io.IOException impossible) {
      throw new IllegalStateException("cannot encode code tracking scope", impossible);
    }
  }

  static String sourceKey(String key) {
    // Cache identities encode provider, ordered roots and path with newlines. They are never a
    // filesystem path or SQL identifier; hashing must preserve the historical bytes verbatim.
    if (key == null || key.isBlank() || key.length() > 3 * 1024 * 1024 || key.indexOf('\0') >= 0)
      throw new IllegalArgumentException("invalid code tracking source key");
    return FileContents.sha256(key.getBytes(StandardCharsets.UTF_8));
  }
}
