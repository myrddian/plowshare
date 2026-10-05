package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/** A client workspace is scoped to its authenticated event session, never to the account alone. */
public final class ClientProjects {
  private ClientProjects() {}

  public static boolean privateProject(String name) {
    return name != null && name.startsWith("client:");
  }

  public static String prefix(String session, String handle) {
    if (session == null || session.isBlank() || handle == null || handle.isBlank())
      throw new CallerFault("Client projects require an authenticated client session");
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((handle + "\0" + session).getBytes(StandardCharsets.UTF_8));
      return "client:" + HexFormat.of().formatHex(digest) + ":";
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static String key(String name, String session, String handle) {
    if (name == null
        || name.isBlank()
        || !name.equals(name.trim())
        || name.length() > 512
        || name.matches("(?s).*[\\r\\n\\x00].*"))
      throw new CallerFault("Use a valid client project name");
    return prefix(session, handle)
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(name.getBytes(StandardCharsets.UTF_8));
  }

  public static boolean visible(String name, String session, String handle) {
    return !privateProject(name)
        || session != null && handle != null && name.startsWith(prefix(session, handle));
  }

  public static void requireOwn(String name, String session, String handle) {
    if (!visible(name, session, handle))
      throw new ArchiveRefusedException("This project belongs to a different client session");
  }

  public static String label(String name) {
    if (!privateProject(name)) return name;
    try {
      return new String(
          Base64.getUrlDecoder().decode(name.substring(name.lastIndexOf(':') + 1)),
          StandardCharsets.UTF_8);
    } catch (IllegalArgumentException invalid) {
      return name;
    }
  }
}
