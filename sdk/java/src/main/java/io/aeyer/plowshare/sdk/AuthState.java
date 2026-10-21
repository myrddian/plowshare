package io.aeyer.plowshare.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import okhttp3.HttpUrl;

/** Private codec/filesystem boundary shared by the optional authentication store. */
final class AuthState {
  private AuthState() {}

  static String account(String account) {
    if (account == null || !account.matches("[A-Za-z0-9_.-]{1,64}"))
      throw new IllegalArgumentException("Choose a valid account handle");
    return account;
  }

  static String secret(String value) {
    if (value == null || value.isEmpty() || value.length() > 4096)
      throw new IllegalArgumentException("Invalid authentication credential");
    for (char c : value.toCharArray())
      if (c < 33 || c > 126 || ";,\"\\".indexOf(c) >= 0)
        throw new IllegalArgumentException("Invalid authentication credential");
    return value;
  }

  static String canonicalOrigin(String value) {
    if (value == null
        || value.length() > 2048
        || value.indexOf('\\') >= 0
        || value.indexOf('?') >= 0
        || value.indexOf('#') >= 0)
      throw new IllegalArgumentException("Use an explicit HTTP(S) origin");
    for (char c : value.toCharArray())
      if (c <= 32 || c >= 127) throw new IllegalArgumentException("Use an ASCII HTTP(S) origin");
    // Validate the input spelling before the URL library normalizes paths or
    // alternate IPv4 forms. Cross-language identity must not depend on a parser.
    java.net.URI raw;
    try {
      raw = java.net.URI.create(value);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Invalid HTTP(S) origin");
    }
    String authority = raw.getRawAuthority();
    if (authority == null
        || authority.contains("@")
        || authority.contains("%")
        || (raw.getRawPath() != null
            && !raw.getRawPath().isEmpty()
            && !raw.getRawPath().equals("/")))
      throw new IllegalArgumentException("Use an origin without credentials or path");
    String inputHost =
        authority.startsWith("[")
            ? authority.substring(1, authority.indexOf(']'))
            : authority.split(":", -1)[0].toLowerCase(java.util.Locale.ROOT);
    if (!inputHost.contains(":")) {
      if (!inputHost.matches("[a-zA-Z0-9._-]+"))
        throw new IllegalArgumentException("Invalid hostname");
      String cleaned = inputHost.replaceFirst("\\.$", "");
      String last = cleaned.substring(cleaned.lastIndexOf('.') + 1);
      if (last.matches("(?:[0-9]+|0[xX][0-9a-fA-F]+)")) {
        if (!inputHost.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+"))
          throw new IllegalArgumentException("Use canonical IPv4");
        for (String part : inputHost.split("\\.")) {
          int octet;
          try {
            octet = Integer.parseInt(part);
          } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Use canonical IPv4");
          }
          if (octet > 255 || !Integer.toString(octet).equals(part))
            throw new IllegalArgumentException("Use canonical IPv4");
        }
      }
    }
    HttpUrl parsed = HttpUrl.parse(value);
    if (parsed == null
        || !parsed.username().isEmpty()
        || !parsed.password().isEmpty()
        || !parsed.encodedPath().equals("/")
        || parsed.query() != null
        || parsed.fragment() != null)
      throw new IllegalArgumentException("Use an HTTP(S) origin without credentials or path");
    String host = parsed.host();
    if (inputHost.contains(":") && !host.contains(":")) {
      // Java/OkHttp collapse mapped IPv6 to Inet4Address. The shared store must
      // retain WHATWG's IPv6 spelling, rather than selecting an IPv4 account key.
      String[] octets = host.split("\\.");
      host =
          "::ffff:"
              + Integer.toHexString(Integer.parseInt(octets[0]) * 256 + Integer.parseInt(octets[1]))
              + ":"
              + Integer.toHexString(
                  Integer.parseInt(octets[2]) * 256 + Integer.parseInt(octets[3]));
    }
    String address = host.contains(":") ? "[" + host + "]" : host;
    return parsed.scheme()
        + "://"
        + address
        + (parsed.port() == HttpUrl.defaultPort(parsed.scheme()) ? "" : ":" + parsed.port());
  }

  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  static boolean posix(Path path) {
    return path.getFileSystem().supportedFileAttributeViews().contains("posix");
  }

  static void directory(Path path) throws IOException {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      if (posix(path))
        Files.createDirectories(
            path,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      else Files.createDirectories(path);
    }
    if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Authentication needs real private directories");
    owner(path);
    if (posix(path))
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
  }

  static void owner(Path path) throws IOException {
    if (posix(path)) {
      UserPrincipal current =
          path.getFileSystem()
              .getUserPrincipalLookupService()
              .lookupPrincipalByName(System.getProperty("user.name"));
      if (!Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(current))
        throw new IOException("Authentication state belongs to another OS user");
    }
  }

  static JsonNode read(Path path) throws IOException {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
    try {
      var before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!before.isRegularFile() || before.size() > 2000000)
        throw new IOException("Invalid authentication file");
      owner(path);
      try (var input =
          Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
        if (posix(path))
          Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        var buffer = ByteBuffer.allocate(2000001);
        while (buffer.hasRemaining() && input.read(buffer) >= 0) {}
        if (buffer.position() > 2000000) throw new IOException("Authentication file too large");
        var raw =
            SdkJson.mapper().readTree(java.util.Arrays.copyOf(buffer.array(), buffer.position()));
        if (raw == null || !raw.isObject()) throw new IOException("Invalid authentication state");
        return raw;
      }
    } catch (IOException | RuntimeException failure) {
      throw new IOException("Authentication state unreadable; sign in explicitly");
    }
  }

  static void sync(Path folder) throws IOException {
    if (posix(folder))
      try (var channel = FileChannel.open(folder, StandardOpenOption.READ)) {
        channel.force(true);
      }
  }

  static void write(Path path, Object value) throws IOException {
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        throw new IOException("Invalid authentication destination");
      owner(path);
    }
    Path temp = path.resolveSibling(path.getFileName() + "." + UUID.randomUUID());
    try {
      if (posix(path))
        Files.createFile(
            temp,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      else Files.createFile(temp);
      try (var output = FileChannel.open(temp, StandardOpenOption.WRITE)) {
        var bytes = ByteBuffer.wrap(SdkJson.mapper().writeValueAsBytes(value));
        while (bytes.hasRemaining()) output.write(bytes);
        output.force(true);
      }
      Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      sync(path.getParent());
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  static void fields(JsonNode node, Set<String> required, Set<String> optional) throws IOException {
    if (node == null || !node.isObject()) throw new IOException("Invalid authentication object");
    for (String name : required)
      if (!node.hasNonNull(name)) throw new IOException("Missing authentication field");
    var names = node.fieldNames();
    while (names.hasNext()) {
      String name = names.next();
      if (!required.contains(name) && !optional.contains(name))
        throw new IOException("Unknown authentication field");
    }
  }

  static String text(JsonNode node, String field) throws IOException {
    var value = node.get(field);
    if (value == null || !value.isTextual()) throw new IOException("Invalid authentication field");
    return value.textValue();
  }

  static void version(JsonNode node) throws IOException {
    var value = node.get("version");
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToInt()
        || value.intValue() != 1) throw new IOException("Unsupported authentication state version");
  }
}
