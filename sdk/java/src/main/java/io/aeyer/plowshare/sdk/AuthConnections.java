package io.aeyer.plowshare.sdk;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Strict native registry projection; never writes or selects an alternate profile. */
final class AuthConnections {
  private AuthConnections() {}

  private record Entry(String key, String name, String server, String account) {}

  static Credentials resolve(String origin, String account, String connection, Path directory)
      throws IOException {
    Path root = directory == null ? Credentials.configurationDirectory() : directory;
    if (!root.isAbsolute()) throw new IOException("Configure an absolute authentication directory");
    AuthState.directory(root);
    var raw = AuthState.read(root.resolve("config.json"));
    var entries = new ArrayList<Entry>();
    String selected = null;
    if (raw != null) {
      AuthState.fields(
          raw,
          Set.of("version", "connections"),
          Set.of("selected", "fileStoreDefault", "connectionSetupPending"));
      AuthState.version(raw);
      var rows = raw.get("connections");
      if (!rows.isArray() || rows.size() > 100) throw new IOException("Invalid connection list");
      var names = new HashSet<String>();
      var keys = new HashSet<String>();
      for (var row : rows) {
        AuthState.fields(row, Set.of("key", "name", "server", "account", "reconnect"), Set.of());
        String server = AuthState.text(row, "server"),
            handle = AuthState.account(AuthState.text(row, "account")),
            name = AuthState.text(row, "name"),
            key = AuthState.text(row, "key");
        if (!server.equals(AuthState.canonicalOrigin(server))
            || !key.equals(Credentials.connectionKey(server, handle))
            || !row.get("reconnect").isBoolean()
            || !validName(name)
            || !names.add(name)
            || !keys.add(key)) throw new IOException("Invalid named identity");
        entries.add(new Entry(key, name, server, handle));
      }
      if (raw.has("selected")) {
        selected = AuthState.text(raw, "selected");
        if (!keys.contains(selected)) throw new IOException("Missing selected connection");
      }
      if (raw.has("fileStoreDefault")) {
        var d = raw.get("fileStoreDefault");
        AuthState.fields(d, Set.of("alias", "root"), Set.of());
        String alias = AuthState.text(d, "alias"), path = AuthState.text(d, "root");
        if (!alias.matches("[a-z][a-z0-9_-]{0,63}")
            || !Path.of(path).isAbsolute()
            || path.length() > 4096
            || path.chars().anyMatch(c -> c < 32 || c == 127))
          throw new IOException("Invalid FileStore default");
      }
      if (raw.has("connectionSetupPending")
          && (!raw.get("connectionSetupPending").isBoolean()
              || !raw.get("connectionSetupPending").booleanValue()
              || !entries.isEmpty()
              || selected != null
              || !raw.has("fileStoreDefault")))
        throw new IOException("Invalid pending connection setup");
    }
    var env = System.getenv();
    if (connection == null) connection = env.get("PLOWSHARE_CONNECTION");
    if (origin == null) origin = env.get("PLOWSHARE_URL");
    if (account == null)
      account = env.getOrDefault("PLOWSHARE_ACCOUNT", env.get("PLOWSHARE_HANDLE"));
    Entry chosen = null;
    if (connection != null) {
      if (!validName(connection)) throw new IOException("Invalid connection name");
      for (var entry : entries)
        if (entry.name().equals(connection)) {
          chosen = entry;
          break;
        }
      if (chosen == null) throw new IOException("Unknown named connection");
    } else if (origin == null && account == null) {
      for (var entry : entries)
        if (entry.key().equals(selected)) {
          chosen = entry;
          break;
        }
    }
    if (chosen != null) {
      if ((origin != null && !AuthState.canonicalOrigin(origin).equals(chosen.server()))
          || (account != null && !account.equals(chosen.account())))
        throw new IOException("Connection conflicts with explicit server/account");
      origin = chosen.server();
      account = chosen.account();
    }
    if (origin == null) throw new IOException("Select an explicit server or named connection");
    return new Credentials(origin, account, directory);
  }

  private static boolean validName(String name) {
    return !name.isEmpty()
        && name.equals(name.strip())
        && name.length() <= 100
        && name.chars().noneMatch(c -> c < 32 || c == 127);
  }
}
