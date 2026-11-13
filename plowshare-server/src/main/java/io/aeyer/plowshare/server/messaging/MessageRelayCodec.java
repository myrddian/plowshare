package io.aeyer.plowshare.server.messaging;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/** Strict owning TEXT boundary; no raw JSON node reaches admission, authority or native stores. */
public final class MessageRelayCodec {
  private static final int MAX_BYTES = 256 * 1024;
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .findAndAddModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .build();

  static {
    for (var shape :
        new CoercionInputShape[] {
          CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean
        }) JSON.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
  }

  private MessageRelayCodec() {}

  public static MessageRelayPayload.Command command(String receiver, String source) {
    try {
      JsonNode root = parse(source);
      Set<String> fields =
          switch (receiver) {
            case "message.receive" ->
                Set.of("schema", "requestId", "client", "context", "body", "command");
            case "message.cancel" -> Set.of("schema", "requestId", "client", "task");
            case "message.inspect" ->
                Set.of("schema", "requestId", "client", "task", "originalRequest", "cursor");
            default -> throw new IllegalArgumentException();
          };
      fields(root, fields);
      UUID request = uuid(root, "requestId", false);
      String client = text(root, "client", false), schema = text(root, "schema", false);
      return switch (receiver) {
        case "message.receive" ->
            new MessageRelayPayload.Receive(
                schema,
                request,
                client,
                uuid(root, "context", true),
                text(root, "body", false),
                text(root, "command", true));
        case "message.cancel" ->
            new MessageRelayPayload.Cancel(schema, request, client, uuid(root, "task", false));
        case "message.inspect" ->
            new MessageRelayPayload.Inspect(
                schema,
                request,
                client,
                uuid(root, "task", true),
                uuid(root, "originalRequest", true),
                text(root, "cursor", true));
        default -> throw new IllegalArgumentException();
      };
    } catch (IOException | RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid native messaging command");
    }
  }

  public static String write(MessageRelayPayload.Snapshot snapshot) {
    try {
      String value = JSON.writeValueAsString(snapshot);
      if (value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
        throw new IllegalArgumentException("Native task snapshot exceeds its byte contract");
      return value;
    } catch (IOException invalid) {
      throw new IllegalStateException("Native snapshot encoding failed");
    }
  }

  /** Used only at the owned persistence boundary to recover an immutable operation snapshot. */
  public static MessageRelayPayload.Snapshot stored(String source) {
    try {
      return JSON.treeToValue(parse(source), MessageRelayPayload.Snapshot.class);
    } catch (IOException | RuntimeException invalid) {
      throw new IllegalStateException("Invalid retained native snapshot");
    }
  }

  private static JsonNode parse(String source) throws IOException {
    if (source == null
        || source.length() > MAX_BYTES
        || source.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
      throw new IllegalArgumentException();
    JsonNode value = JSON.readTree(source);
    if (value == null || !value.isObject()) throw new IllegalArgumentException();
    return value;
  }

  private static void fields(JsonNode root, Set<String> expected) {
    if (root.size() != expected.size()) throw new IllegalArgumentException();
    root.fieldNames()
        .forEachRemaining(
            name -> {
              if (!expected.contains(name)) throw new IllegalArgumentException();
            });
  }

  private static String text(JsonNode root, String name, boolean nullable) {
    var value = root.get(name);
    if (value == null) throw new IllegalArgumentException();
    if (nullable && value.isNull()) return null;
    if (!value.isTextual()) throw new IllegalArgumentException();
    return value.textValue();
  }

  private static UUID uuid(JsonNode root, String name, boolean nullable) {
    String value = text(root, name, nullable);
    if (value == null) return null;
    UUID id = UUID.fromString(value);
    if (!id.toString().equals(value)) throw new IllegalArgumentException();
    return id;
  }
}
