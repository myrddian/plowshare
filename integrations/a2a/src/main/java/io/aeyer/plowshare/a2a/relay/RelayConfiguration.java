package io.aeyer.plowshare.a2a.relay;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.RelayPort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import okhttp3.HttpUrl;

/** Explicit deployment/configuration boundary. Credentials are resolved once, never serialized. */
public record RelayConfiguration(
    String origin,
    String token,
    Path journal,
    RelayAdapter.Binding binding,
    Map<String, Peer> peers) {
  public record Peer(
      String endpoint, String agentCard, String bearer, Duration timeout, String principal) {
    public Peer(String endpoint, String agentCard, String bearer, Duration timeout) {
      this(endpoint, agentCard, bearer, timeout, null);
    }

    public Peer {
      endpoint = url(endpoint, false);
      if (agentCard != null) agentCard = url(agentCard, false);
      if (bearer != null) {
        secret(bearer);
        if (principal == null)
          throw new IllegalArgumentException(
              "Configure a stable peer principal before credential rotation");
      }
      if (principal != null) {
        RelayWork.identity(principal);
        if (principal.length() > 128 || principal.matches("ps[sat]_.*"))
          throw new IllegalArgumentException("Use an opaque principal label, never a credential");
      }
      if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(5)) > 0)
        throw new IllegalArgumentException("Invalid peer timeout");
    }

    @Override
    public String toString() {
      return "Peer{redacted}";
    }
  }

  public RelayConfiguration {
    origin = url(origin, true);
    secret(token);
    if (journal == null || !journal.isAbsolute())
      throw new IllegalArgumentException("Configure an absolute durable journal directory");
    peers = Map.copyOf(peers);
    if (peers.isEmpty() || peers.size() > 64)
      throw new IllegalArgumentException("Configure 1..64 A2A peers");
    peers.keySet().forEach(RelayWork::alias);
  }

  @Override
  public String toString() {
    return "RelayConfiguration{redacted}";
  }

  /**
   * Legacy combined digest, used only to establish v1 journal ownership during one-time upgrade.
   */
  public String legacyFingerprint() {
    var value = new StringBuilder(origin).append('\0').append(RelayPort.hash(token));
    // MESSAGE_RETURN did not exist in v1. Its domain-separated digest must never match a legacy
    // observation journal and silently reinterpret an already retained publication intent.
    if (binding.encoding() != RelayAdapter.ReplyEncoding.A2A_OBSERVATION)
      value.insert(0, "a2a-nonlegacy-format:" + binding.encoding() + "\0");
    appendBinding(value);
    new TreeMap<>(peers)
        .forEach(
            (name, peer) ->
                value
                    .append('\0')
                    .append(name)
                    .append('\0')
                    .append(peer.endpoint())
                    .append('\0')
                    .append(peer.agentCard())
                    .append('\0')
                    .append(peer.bearer() == null ? "" : RelayPort.hash(peer.bearer()))
                    .append('\0')
                    .append(peer.timeout()));
    return RelayPort.hash(value.toString());
  }

  /**
   * Stable destination/identity descriptor. Mutable secret bytes and request timeouts are excluded.
   */
  public String identityFingerprint() {
    var value = new StringBuilder("a2a-identity/2:").append(origin);
    appendBinding(value);
    if (binding.encoding() != RelayAdapter.ReplyEncoding.A2A_OBSERVATION)
      value.append('\0').append(binding.encoding());
    new TreeMap<>(peers)
        .forEach(
            (name, peer) ->
                value
                    .append('\0')
                    .append(name)
                    .append('\0')
                    .append(peer.endpoint())
                    .append('\0')
                    .append(peer.agentCard())
                    .append('\0')
                    // Anonymous peers use a fixed public marker; authenticated peers require an
                    // operator-owned
                    // stable principal label, which must be independently verified during rotation.
                    .append(
                        peer.principal() == null
                            ? "credential:"
                                + RelayPort.hash(Objects.toString(peer.bearer(), "public"))
                            : "principal:" + peer.principal()));
    return RelayPort.hash(value.toString());
  }

  /** Credential changes require an explicit locked journal rotation, never automatic adoption. */
  public String fingerprint() {
    var value =
        new StringBuilder("a2a-credentials/2:")
            .append(identityFingerprint())
            .append('\0')
            .append(RelayPort.hash(token));
    new TreeMap<>(peers)
        .forEach(
            (name, peer) ->
                value
                    .append('\0')
                    .append(name)
                    .append('\0')
                    .append(peer.bearer() == null ? "" : RelayPort.hash(peer.bearer())));
    return RelayPort.hash(value.toString());
  }

  private void appendBinding(StringBuilder value) {
    value
        .append('\0')
        .append(binding.project())
        .append('\0')
        .append(binding.requests())
        .append('\0')
        .append(binding.replies())
        .append('\0')
        .append(binding.group())
        .append('\0')
        .append(binding.consumer())
        .append('\0')
        .append(binding.replyPublisher())
        .append('\0')
        .append(binding.maxDepth());
    binding.producers().stream().sorted().forEach(p -> value.append('\0').append(p));
  }

  public static RelayConfiguration read(Path file, Function<String, String> environment)
      throws IOException {
    var json =
        new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    try (var input = Files.newInputStream(file)) {
      byte[] bytes = input.readNBytes(65537);
      if (bytes.length > 65536) throw new IOException("A2A configuration exceeds 64 KiB");
      var value = json.readTree(bytes);
      fields(
          value,
          Set.of(
              "origin",
              "tokenEnv",
              "journal",
              "project",
              "requests",
              "replies",
              "group",
              "consumer",
              "replyPublisher",
              "producers",
              "maxDepth",
              "peers"),
          Set.of("replicaMode", "replyFormat"));
      if (value.has("replicaMode")
          && !text(value, "replicaMode").equals("SHARED_JOURNAL_SINGLE_OWNER"))
        throw new IllegalArgumentException("Independent journals are not a supported replica mode");
      if (!value.get("producers").isArray()
          || value.get("producers").isEmpty()
          || value.get("producers").size() > 64) throw new IllegalArgumentException();
      var producers = new HashSet<String>();
      for (var row : value.get("producers")) {
        if (!row.isTextual() || !producers.add(row.textValue()))
          throw new IllegalArgumentException();
      }
      var binding =
          new RelayAdapter.Binding(
              text(value, "project"),
              text(value, "requests"),
              text(value, "replies"),
              text(value, "group"),
              text(value, "consumer"),
              text(value, "replyPublisher"),
              producers,
              integer(value, "maxDepth", 1, 32),
              value.has("replyFormat")
                  ? RelayAdapter.ReplyEncoding.valueOf(text(value, "replyFormat"))
                  : RelayAdapter.ReplyEncoding.A2A_OBSERVATION);
      var configured = value.get("peers");
      if (!configured.isObject() || configured.isEmpty() || configured.size() > 64)
        throw new IllegalArgumentException();
      var peers = new TreeMap<String, Peer>();
      var entries = configured.fields();
      while (entries.hasNext()) {
        var entry = entries.next();
        var peer = entry.getValue();
        fields(
            peer, Set.of("endpoint"), Set.of("agentCard", "bearerEnv", "timeoutMs", "principal"));
        peers.put(
            RelayWork.alias(entry.getKey()),
            new Peer(
                text(peer, "endpoint"),
                peer.has("agentCard") ? text(peer, "agentCard") : null,
                peer.has("bearerEnv") ? credential(peer, "bearerEnv", environment) : null,
                Duration.ofMillis(
                    peer.has("timeoutMs") ? integer(peer, "timeoutMs", 1, 300000) : 20000),
                peer.has("principal") ? text(peer, "principal") : null));
      }
      return new RelayConfiguration(
          text(value, "origin"),
          credential(value, "tokenEnv", environment),
          Path.of(text(value, "journal")),
          binding,
          peers);
    } catch (IOException | RuntimeException invalid) {
      throw new IOException(
          "Invalid A2A Relay configuration; use explicit deployment values and available credential variables");
    }
  }

  private static String credential(JsonNode row, String key, Function<String, String> environment) {
    String variable = text(row, key);
    if (!variable.matches("[A-Z][A-Z0-9_]{0,127}")) throw new IllegalArgumentException();
    String value = environment.apply(variable);
    secret(value);
    return value;
  }

  private static void secret(String value) {
    if (value == null || !value.matches("[\\x21-\\x7e]{1,8192}"))
      throw new IllegalArgumentException("Invalid or missing caller credential");
  }

  private static String text(JsonNode row, String key) {
    var value = row.get(key);
    if (value == null || !value.isTextual()) throw new IllegalArgumentException();
    String result = value.textValue();
    if (result.isBlank()
        || result.length() > 4096
        || !result.equals(result.strip())
        || result.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException();
    return result;
  }

  private static int integer(JsonNode row, String key, int minimum, int maximum) {
    var value = row.get(key);
    if (!value.isIntegralNumber()
        || !value.canConvertToInt()
        || value.intValue() < minimum
        || value.intValue() > maximum) throw new IllegalArgumentException();
    return value.intValue();
  }

  private static void fields(JsonNode row, Set<String> required, Set<String> optional) {
    if (row == null || !row.isObject()) throw new IllegalArgumentException();
    var fields = new HashSet<String>();
    row.fieldNames().forEachRemaining(fields::add);
    if (!fields.containsAll(required)
        || fields.stream().anyMatch(k -> !required.contains(k) && !optional.contains(k)))
      throw new IllegalArgumentException();
  }

  private static String url(String value, boolean origin) {
    if (value == null
        || value.length() > 4096
        || !value.equals(value.strip())
        || value.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c)))
      throw new IllegalArgumentException("Explicit HTTP(S) address required");
    var parsed = HttpUrl.parse(value);
    if (parsed == null
        || !parsed.username().isEmpty()
        || !parsed.password().isEmpty()
        || parsed.query() != null
        || parsed.fragment() != null
        || origin && !parsed.encodedPath().equals("/"))
      throw new IllegalArgumentException("Invalid configured address");
    return parsed.toString();
  }
}
