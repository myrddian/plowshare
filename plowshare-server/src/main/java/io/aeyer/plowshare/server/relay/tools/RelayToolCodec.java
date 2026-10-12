package io.aeyer.plowshare.server.relay.tools;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.IntegrationPayload;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.protocol.ToolValue;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Versioned TEXT envelope boundary; JSON never crosses into invocation business logic. */
public final class RelayToolCodec {
  private RelayToolCodec() {}

  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .build();

  static {
    var module = new com.fasterxml.jackson.databind.module.SimpleModule();
    module.addDeserializer(
        ToolValue.class,
        new com.fasterxml.jackson.databind.JsonDeserializer<ToolValue>() {
          @Override
          public ToolValue deserialize(
              com.fasterxml.jackson.core.JsonParser parser,
              com.fasterxml.jackson.databind.DeserializationContext context)
              throws IOException {
            return ToolValue.fromWire(context.readValue(parser, Object.class));
          }

          @Override
          public ToolValue getNullValue(
              com.fasterxml.jackson.databind.DeserializationContext context) {
            return new ToolValue.Null();
          }
        });
    JSON.registerModule(module);
    for (var shape :
        new com.fasterxml.jackson.databind.cfg.CoercionInputShape[] {
          com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
          com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
          com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean
        })
      JSON.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
          .setCoercion(shape, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
  }

  /** Spring deployment properties carry schemas as JSON text, decoded before domain binding. */
  static io.aeyer.plowshare.protocol.SchemaDefinition inputSchema(String source) {
    if (source == null || source.length() > 65536)
      throw new IllegalArgumentException("Tool schema exceeds configuration bound");
    try {
      var value = JSON.readValue(source, io.aeyer.plowshare.protocol.SchemaDefinition.class);
      io.aeyer.plowshare.protocol.ToolSchemas.declaration(value);
      return value;
    } catch (IOException invalid) {
      throw new IllegalArgumentException("Invalid tool schema configuration", invalid);
    }
  }

  public static final String VERSION = "plowshare-tool/1";
  public static final String NESTED_VERSION = "plowshare-tool/2";

  public record Request(
      String schema,
      String invocationId,
      String project,
      String provider,
      String tool,
      String account,
      String run,
      String call,
      String deadline,
      Map<String, ToolValue> arguments,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          String schemaFingerprint) {
    public Request(
        String schema,
        String invocationId,
        String project,
        String provider,
        String tool,
        String account,
        String run,
        String call,
        String deadline,
        Map<String, ToolValue> arguments) {
      this(
          schema,
          invocationId,
          project,
          provider,
          tool,
          account,
          run,
          call,
          deadline,
          arguments,
          null);
    }

    public Request {
      if (!VERSION.equals(schema) && !NESTED_VERSION.equals(schema)
          || NESTED_VERSION.equals(schema)
              && (schemaFingerprint == null || !schemaFingerprint.matches("[0-9a-f]{64}"))
          || VERSION.equals(schema) && schemaFingerprint != null)
        throw new IllegalArgumentException("Invalid tool envelope version");
      RelayPort.uuid(invocationId);
      RelayPort.identity(project);
      RelayPort.identity(provider);
      RelayPort.identity(tool);
      RelayPort.identity(account);
      RelayPort.identity(run);
      RelayPort.identity(call);
      Instant.parse(deadline);
      arguments = Map.copyOf(arguments);
      ToolValue.validate(new ToolValue.ObjectValue(arguments));
      if (VERSION.equals(schema)
          && arguments.values().stream()
              .anyMatch(value -> !(value instanceof IntegrationPayload.Parameter)))
        throw new IllegalArgumentException("v1 tool arguments must be scalars");
    }
  }

  public enum State {
    COMPLETED,
    REJECTED,
    UNKNOWN
  }

  public record Result(
      String schema,
      String invocationId,
      String project,
      String provider,
      String tool,
      State state,
      String text) {
    public Result {
      if (!VERSION.equals(schema))
        throw new IllegalArgumentException("Invalid tool envelope version");
      RelayPort.uuid(invocationId);
      RelayPort.identity(project);
      RelayPort.identity(provider);
      RelayPort.identity(tool);
      java.util.Objects.requireNonNull(state);
      if (text == null || text.isBlank() || text.length() > 16384 || text.indexOf('\0') >= 0)
        throw new IllegalArgumentException("Invalid tool result text");
    }
  }

  public static RelayToolDefinition.Arguments arguments(String source) {
    try {
      if (source.length() > 32768)
        throw new IllegalArgumentException("Tool arguments exceed bound");
      var typed =
          ToolValue.fromWire(JSON.readValue(source.isEmpty() ? "{}" : source, Object.class));
      if (!(typed instanceof ToolValue.ObjectValue object))
        throw new IllegalArgumentException("Tool arguments must be an object");
      return new RelayToolDefinition.Arguments(object.values());
    } catch (IOException malformed) {
      throw new IllegalArgumentException("Invalid tool argument JSON", malformed);
    }
  }

  public static Result result(String source) {
    try {
      if (source.length() > 32768) throw new IllegalArgumentException("Tool result exceeds bound");
      return JSON.readValue(source, Result.class);
    } catch (IOException malformed) {
      throw new IllegalArgumentException("Invalid tool result envelope", malformed);
    }
  }

  static Request request(String source) {
    try {
      var shape = JSON.readTree(source);
      if (!shape.isObject()
          || shape.size() != (NESTED_VERSION.equals(shape.path("schema").asText()) ? 11 : 10))
        throw new IllegalArgumentException("Invalid retained tool request fields");
    } catch (IOException invalid) {
      throw new IllegalStateException("Invalid retained tool request", invalid);
    }
    return read(source, Request.class);
  }

  static RelayToolDefinition definition(String source) {
    return read(source, RelayToolDefinition.class);
  }

  private static <T> T read(String source, Class<T> type) {
    try {
      return JSON.readValue(source, type);
    } catch (IOException malformed) {
      throw new IllegalStateException("Invalid retained tool value", malformed);
    }
  }

  public static String write(Request value) {
    return encode(value);
  }

  public static String write(Result value) {
    return encode(value);
  }

  public static String write(RelayToolDefinition value) {
    return encode(value);
  }

  public static String write(RelayToolDefinition.Arguments value) {
    return encode(value);
  }

  private static String encode(Object record) {
    try {
      return JSON.writeValueAsString(record);
    } catch (IOException broken) {
      throw new IllegalStateException("Tool value could not be encoded", broken);
    }
  }

  /** Cross-language identity uses the same SHA-256 namespace as SDK publisher identities. */
  public static String resultId(UUID invocation) {
    return RelayPort.hash("tool-result:" + invocation);
  }

  public static UUID resultRequestId(UUID invocation) {
    var hex = resultId(invocation).substring(0, 32);
    return UUID.fromString(
        hex.substring(0, 8)
            + "-"
            + hex.substring(8, 12)
            + "-"
            + hex.substring(12, 16)
            + "-"
            + hex.substring(16, 20)
            + "-"
            + hex.substring(20));
  }
}
