package io.aeyer.plowshare.a2a.relay;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.a2a.Main;
import io.aeyer.plowshare.a2a.Receiver;
import io.aeyer.plowshare.protocol.RelayPort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import okhttp3.HttpUrl;

/** Explicit receiving deployment, channel and credentials; resolved values never print secrets. */
public record IngressConfiguration(
    String origin,
    String token,
    Path journal,
    RelayIngressBridge.Binding binding,
    Receiver.Config receiver,
    String principal) {
  /**
   * Programmatic fixtures without an asserted principal remain pinned to exact credential bytes.
   */
  public IngressConfiguration(
      String origin,
      String token,
      Path journal,
      RelayIngressBridge.Binding binding,
      Receiver.Config receiver) {
    this(origin, token, journal, binding, receiver, null);
  }

  public IngressConfiguration {
    var url = HttpUrl.parse(origin);
    if (url == null
        || !url.username().isEmpty()
        || !url.password().isEmpty()
        || url.query() != null
        || url.fragment() != null
        || !url.encodedPath().equals("/"))
      throw new IllegalArgumentException("Explicit server origin required");
    origin = url.toString();
    if (principal != null) {
      RelayPort.identity(principal);
      if (principal.matches("ps[sat]_.*"))
        throw new IllegalArgumentException("Principal must be an identity, never a credential");
    }
    if (token == null || !token.matches("[\\x21-\\x7e]{1,8192}"))
      throw new IllegalArgumentException("Configured service credential required");
    if (journal == null || !journal.isAbsolute())
      throw new IllegalArgumentException("Absolute durable receiving journal required");
    if (!receiver.project().equals(binding.project()) || !receiver.agent().equals(binding.agent()))
      throw new IllegalArgumentException(
          "Receiving listener and native route must select the same project/agent");
  }

  @Override
  public String toString() {
    return "IngressConfiguration{redacted}";
  }

  public String identityFingerprint() {
    StringBuilder value =
        new StringBuilder("a2a-ingress/1:")
            .append(origin)
            .append('\0')
            .append(binding.project())
            .append('\0')
            .append(binding.agent())
            .append('\0')
            .append(binding.requests())
            .append('\0')
            .append(binding.replies())
            .append('\0')
            .append(binding.group())
            .append('\0')
            .append(binding.consumer())
            .append('\0')
            .append(binding.publisher())
            .append('\0')
            .append(receiver.publicUrl());
    value
        .append('\0')
        .append(
            principal == null ? "credential:" + RelayPort.hash(token) : "principal:" + principal);
    receiver.clients().keySet().stream()
        .sorted()
        .forEach(client -> value.append('\0').append(client));
    return RelayPort.hash(value.toString());
  }

  public String fingerprint() {
    var value = new StringBuilder(identityFingerprint()).append('\0').append(RelayPort.hash(token));
    receiver.clients().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            client ->
                value
                    .append('\0')
                    .append(client.getKey())
                    .append('\0')
                    .append(RelayPort.hash(client.getValue())));
    return RelayPort.hash(value.toString());
  }

  public static IngressConfiguration read(Path path, Function<String, String> environment)
      throws IOException {
    var json =
        JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    try (var input = Files.newInputStream(path)) {
      byte[] bytes = input.readNBytes(65537);
      if (bytes.length > 65536)
        throw new IllegalArgumentException("Receiving configuration exceeds64KiB");
      var root = json.readTree(bytes);
      var fields = new HashSet<String>();
      root.fieldNames().forEachRemaining(fields::add);
      if (!root.isObject()
          || !fields.equals(
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
                  "receive",
                  "principal")))
        throw new IllegalArgumentException("Invalid receiving configuration fields");
      String variable = text(root, "tokenEnv");
      if (!variable.matches("[A-Z][A-Z0-9_]{0,127}"))
        throw new IllegalArgumentException("Credential environment variable required");
      String project = text(root, "project");
      var receiver = Main.receiving(root.get("receive"), project, environment);
      var binding =
          new RelayIngressBridge.Binding(
              project,
              receiver.agent(),
              text(root, "requests"),
              text(root, "replies"),
              text(root, "group"),
              text(root, "consumer"),
              text(root, "replyPublisher"),
              receiver.waitMillis());
      return new IngressConfiguration(
          text(root, "origin"),
          environment.apply(variable),
          Path.of(text(root, "journal")),
          binding,
          receiver,
          text(root, "principal"));
    } catch (IOException | RuntimeException invalid) {
      throw new IOException(
          "Invalid A2A receiving Relay configuration; verify explicit binding and credential variables");
    }
  }

  private static String text(JsonNode root, String key) {
    var node = root.get(key);
    if (node == null || !node.isTextual())
      throw new IllegalArgumentException("Text configuration required");
    String value = node.textValue();
    if (value.isBlank()
        || value.length() > 4096
        || !value.equals(value.strip())
        || value.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid receiving configuration text");
    return value;
  }
}
