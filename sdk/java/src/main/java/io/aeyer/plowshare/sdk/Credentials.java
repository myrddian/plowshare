package io.aeyer.plowshare.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.sdk.UserAuthentication.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;

/**
 * Optional shared native user-login store. Reload, renewal intent, HTTP rotation and persistence
 * are serialized under Node/Python's mkdir lock. Application operations and recovery UUIDs/payloads
 * remain owned by the caller.
 */
public final class Credentials {
  /** A fresh login is required; no other account or service credential is selected. */
  public static final class LoginRequired extends IOException {
    public LoginRequired() {
      super("Sign in explicitly; no saved session or renewal was interrupted");
    }
  }

  /** Local identity metadata, independent of server authority. */
  public record UserSession(String origin, String handle, Tokens tokens) {}

  private record Saved(
      int version, String origin, String handle, Tokens tokens, boolean renewing) {}

  private record Migration(int version, String origin, String account) {}

  private final String origin;
  private final String account;
  private final Path root;
  private final List<Path> legacy;
  private final Duration lockTimeout;

  public Credentials(String origin, String account, Path directory) {
    this(origin, account, directory, Duration.ofSeconds(10));
  }

  public Credentials(String origin, String account, Path directory, Duration lockTimeout) {
    this.origin = AuthState.canonicalOrigin(origin);
    this.account = account == null ? null : AuthState.account(account);
    root = directory == null ? configurationDirectory() : directory;
    if (!root.isAbsolute()
        || lockTimeout == null
        || lockTimeout.isNegative()
        || lockTimeout.isZero())
      throw new IllegalArgumentException("Use an absolute state root and positive lock timeout");
    this.lockTimeout = lockTimeout;
    var sources = new ArrayList<Path>();
    sources.add(root.resolve("credentials"));
    if (directory == null
        && !System.getenv().containsKey("PLOWSHARE_CONFIG_DIR")
        && !System.getenv().containsKey("PLOWSHARE_DESKTOP_CONFIG")) {
      Path xdg =
          Path.of(
              System.getenv()
                  .getOrDefault(
                      "XDG_CONFIG_HOME",
                      Path.of(System.getProperty("user.home"), ".config").toString()));
      if (!xdg.isAbsolute()) throw new IllegalArgumentException("XDG_CONFIG_HOME must be absolute");
      sources.add(xdg.resolve("plowshare/credentials"));
    }
    legacy = List.copyOf(sources);
  }

  private Credentials(Credentials parent, String account) {
    origin = parent.origin;
    this.account = account;
    root = parent.root;
    legacy = parent.legacy;
    lockTimeout = parent.lockTimeout;
  }

  public String origin() {
    return origin;
  }

  /** Shared environment precedence; XDG directories are migration sources only. */
  public static Path configurationDirectory() {
    Path root =
        Path.of(
            System.getenv()
                .getOrDefault(
                    "PLOWSHARE_CONFIG_DIR",
                    System.getenv()
                        .getOrDefault(
                            "PLOWSHARE_DESKTOP_CONFIG",
                            Path.of(System.getProperty("user.home"), ".plowshare").toString())));
    if (!root.isAbsolute())
      throw new IllegalArgumentException("Configure an absolute authentication directory");
    return root;
  }

  /** Node-compatible immutable origin/account key. Names never form paths. */
  public static String connectionKey(String origin, String account) {
    try {
      return AuthState.hash(
          SdkJson.mapper()
              .writeValueAsString(
                  List.of(AuthState.canonicalOrigin(origin), AuthState.account(account))));
    } catch (IOException impossible) {
      throw new IllegalStateException("Cannot encode connection identity", impossible);
    }
  }

  private Path scope() {
    return root.resolve("connections").resolve(connectionKey(origin, account));
  }

  private Path file() {
    return scope().resolve("credentials").resolve(AuthState.hash(origin) + ".json");
  }

  private void directories() throws IOException {
    for (Path path : List.of(root, root.resolve("connections"), scope(), file().getParent()))
      AuthState.directory(path);
  }

  private static void checkInterrupted() throws IOException {
    if (Thread.currentThread().isInterrupted())
      throw new IOException("Authentication cancelled; renewal may be uncertain");
  }

  private AutoCloseableLock lock(Path file) throws IOException {
    Path path = file.resolveSibling(file.getFileName() + ".lock");
    long start = System.nanoTime();
    for (; ; ) {
      checkInterrupted();
      try {
        if (AuthState.posix(path))
          Files.createDirectory(
              path,
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        else Files.createDirectory(path);
        return new AutoCloseableLock(path);
      } catch (FileAlreadyExistsException busy) {
        if (System.nanoTime() - start >= lockTimeout.toNanos())
          throw new IOException(
              "Credential store busy; inspect interrupted locks, never steal them");
        try {
          Thread.sleep(25);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          checkInterrupted();
        }
      }
    }
  }

  private static final class AutoCloseableLock implements AutoCloseable {
    private final Path path;
    private boolean retain;

    AutoCloseableLock(Path path) {
      this.path = path;
    }

    @Override
    public void close() throws IOException {
      if (!retain) Files.delete(path);
    }
  }

  private Saved decode(JsonNode raw) throws IOException {
    try {
      return decodeSaved(raw);
    } catch (IllegalArgumentException invalid) {
      throw new IOException("Invalid saved authentication state");
    }
  }

  private Saved decodeSaved(JsonNode raw) throws IOException {
    AuthState.fields(raw, Set.of("version", "origin", "handle", "tokens"), Set.of("renewing"));
    AuthState.version(raw);
    String server = AuthState.text(raw, "origin"), handle = AuthState.text(raw, "handle");
    if (!AuthState.canonicalOrigin(server).equals(server))
      throw new IOException("Invalid saved origin");
    AuthState.account(handle);
    var pair = raw.get("tokens");
    AuthState.fields(pair, Set.of("access", "refresh"), Set.of());
    var tokens = new Tokens(AuthState.text(pair, "access"), AuthState.text(pair, "refresh"));
    if (tokens.access().startsWith("pss_")
        || (raw.has("renewing") && !raw.get("renewing").isBoolean()))
      throw new IOException("Invalid saved user session");
    return new Saved(1, server, handle, tokens, raw.path("renewing").asBoolean(false));
  }

  private Migration migration(JsonNode raw) throws IOException {
    try {
      return decodeMigration(raw);
    } catch (IllegalArgumentException invalid) {
      throw new IOException("Invalid credential migration state");
    }
  }

  private Migration decodeMigration(JsonNode raw) throws IOException {
    AuthState.fields(raw, Set.of("version", "origin", "account"), Set.of());
    AuthState.version(raw);
    String server = AuthState.text(raw, "origin"),
        handle = AuthState.account(AuthState.text(raw, "account"));
    if (!AuthState.canonicalOrigin(server).equals(server))
      throw new IOException("Invalid migration origin");
    return new Migration(1, server, handle);
  }

  private void mark() throws IOException {
    AuthState.write(
        scope().resolve("credential-migration.json"), new Migration(1, origin, account));
  }

  private void migrate() throws IOException {
    JsonNode marker = AuthState.read(scope().resolve("credential-migration.json"));
    if (marker != null) {
      var m = migration(marker);
      if (!m.origin().equals(origin) || !m.account().equals(account))
        throw new IOException("Credential migration identity differs");
      return;
    }
    if (AuthState.read(file()) != null) return;
    for (Path folder : legacy) {
      if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) continue;
      AuthState.directory(folder);
      Path source = folder.resolve(file().getFileName());
      var held = lock(source);
      try (held) {
        var raw = AuthState.read(source);
        if (raw == null) continue;
        var s = decode(raw);
        if (!s.origin().equals(origin)) throw new IOException("Legacy origin differs");
        if (!s.handle().equals(account)) continue;
        if (s.renewing()) throw new LoginRequired();
        Files.move(source, file(), StandardCopyOption.ATOMIC_MOVE);
        AuthState.sync(file().getParent());
        mark();
        AuthState.sync(folder);
        return;
      }
    }
  }

  private Saved load() throws IOException {
    var raw = AuthState.read(file());
    if (raw == null) throw new LoginRequired();
    var s = decode(raw);
    if (!s.origin().equals(origin) || !s.handle().equals(account))
      throw new IOException("Saved identity differs");
    if (s.renewing()) throw new LoginRequired();
    return s;
  }

  private Credentials chosen() throws IOException {
    if (account != null) return this;
    AuthState.directory(root);
    var accounts = new HashSet<String>();
    Path parent = root.resolve("connections");
    if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
      AuthState.directory(parent);
      try (var stream = Files.list(parent)) {
        var paths = stream.limit(1025).toList();
        if (paths.size() > 1024)
          throw new IOException("Too many identities; select account explicitly");
        for (Path path : paths) {
          if (!path.getFileName().toString().matches("[a-f0-9]{64}")) continue;
          AuthState.directory(path);
          var raw = AuthState.read(path.resolve("credential-migration.json"));
          if (raw != null) {
            var m = migration(raw);
            if (!path.getFileName().toString().equals(connectionKey(m.origin(), m.account())))
              throw new IOException("Migration key differs");
            if (m.origin().equals(origin)) accounts.add(m.account());
          }
          Path folder = path.resolve("credentials");
          if (Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            AuthState.directory(folder);
            raw = AuthState.read(folder.resolve(AuthState.hash(origin) + ".json"));
            if (raw != null) {
              var s = decode(raw);
              if (!s.origin().equals(origin)
                  || !path.getFileName().toString().equals(connectionKey(s.origin(), s.handle())))
                throw new IOException("Saved key differs");
              accounts.add(s.handle());
            }
          }
        }
      }
    }
    if (accounts.size() > 1)
      throw new IOException("Multiple saved accounts; choose an explicit account");
    if (!accounts.isEmpty()) return new Credentials(this, accounts.iterator().next());
    for (Path folder : legacy) {
      if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) continue;
      AuthState.directory(folder);
      var raw = AuthState.read(folder.resolve(AuthState.hash(origin) + ".json"));
      if (raw != null) {
        var s = decode(raw);
        if (!s.origin().equals(origin)) throw new IOException("Legacy origin differs");
        return new Credentials(this, s.handle());
      }
    }
    throw new LoginRequired();
  }

  private UserAuthentication authentication(UserAuthentication auth) throws IOException {
    UserAuthentication a = auth == null ? new HttpAuthentication(origin) : auth;
    if (!origin.equals(a.origin()))
      throw new IOException("Authentication belongs to another server");
    return a;
  }

  /** Reload local credentials under the shared lock, without network or renewal. */
  public UserSession session() throws IOException {
    var c = chosen();
    c.directories();
    var held = c.lock(c.file());
    try (held) {
      c.migrate();
      var s = c.load();
      return new UserSession(s.origin(), s.handle(), s.tokens());
    }
  }

  /** Explicit login is the recovery path for interrupted refresh; passwords are never saved. */
  public SignIn login(String handle, String password, UserAuthentication auth) throws IOException {
    AuthState.account(handle);
    if (account != null && !account.equals(handle))
      throw new IOException("Login conflicts with selected account");
    var a = authentication(auth);
    var c = new Credentials(this, handle);
    c.directories();
    var held = c.lock(c.file());
    try (held) {
      var signed = a.login(handle, password);
      checkInterrupted();
      if ((!signed.mustChangePassword() && signed.tokens().refresh() == null)
          || signed.tokens().access().startsWith("pss_"))
        throw new IOException("Ordinary user session required");
      if (!signed.mustChangePassword() && signed.tokens().refresh() != null) {
        AuthState.write(c.file(), new Saved(1, origin, handle, signed.tokens(), false));
        c.mark();
      }
      return signed;
    }
  }

  private Tokens rotate(UserAuthentication a, Saved s, AutoCloseableLock held) throws IOException {
    AuthState.write(file(), new Saved(1, origin, account, s.tokens(), true));
    Tokens pair;
    try {
      pair = a.refresh(s.tokens());
      checkInterrupted();
      if (pair == null || pair.refresh() == null || pair.access().startsWith("pss_"))
        throw new IOException("Invalid renewed pair");
    } catch (IOException | RuntimeException failed) {
      throw new LoginRequired();
    }
    try {
      AuthState.write(file(), new Saved(1, origin, account, pair, false));
    } catch (IOException | RuntimeException failed) {
      // Even if replace completed, failure to sync leaves durability unproven.
      // Keep the shared lock as a second fence until explicit operator recovery.
      held.retain = true;
      throw new IOException(
          "Credential persistence failed; inspect retained lock and sign in again");
    }
    return pair;
  }

  /**
   * Explicit rotation, or reuse a newer pair when rejectedAccess was already replaced. Every
   * failure after renewal intent remains fenced until explicit login.
   */
  public Tokens renew(UserAuthentication auth, String rejectedAccess) throws IOException {
    if (rejectedAccess != null) AuthState.secret(rejectedAccess);
    var a = authentication(auth);
    var c = chosen();
    c.directories();
    var held = c.lock(c.file());
    try (held) {
      c.migrate();
      var s = c.load();
      if (rejectedAccess != null && !s.tokens().access().equals(rejectedAccess)) return s.tokens();
      return c.rotate(a, s, held);
    }
  }

  /** Validate the saved identity. Only explicit authentication rejection renews, once. */
  public UserSession authenticated(UserAuthentication auth) throws IOException {
    var a = authentication(auth);
    var c = chosen();
    var s = c.session();
    var before = a.standing(s.tokens());
    if (before.signedIn()) {
      if (before.mustChangePassword()) throw new LoginRequired();
      return s;
    }
    var pair = c.renew(a, s.tokens().access());
    var after = a.standing(pair);
    if (!after.signedIn() || after.mustChangePassword()) throw new LoginRequired();
    return new UserSession(s.origin(), s.handle(), pair);
  }

  /**
   * Persist a rotation, revoke, mark migration, then delete. Failed revocation retains the pair.
   */
  public void logout(UserAuthentication auth) throws IOException {
    var a = authentication(auth);
    var c = chosen();
    c.directories();
    var held = c.lock(c.file());
    try (held) {
      c.migrate();
      var pair = c.rotate(a, c.load(), held);
      a.logout(pair);
      checkInterrupted();
      c.mark();
      Files.delete(c.file());
      AuthState.sync(c.file().getParent());
    }
  }

  /** Read the native named registry with explicit selector/environment precedence. */
  public static Credentials resolve(
      String origin, String account, String connection, Path directory) throws IOException {
    return AuthConnections.resolve(origin, account, connection, directory);
  }
}
