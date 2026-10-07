package io.aeyer.plowshare.server.relay.tools;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.IntegrationPayload;
import io.aeyer.plowshare.protocol.RelayPort;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
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
    for (var shape :
        new com.fasterxml.jackson.databind.cfg.CoercionInputShape[] {
          com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
          com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
          com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean
        })
      JSON.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
          .setCoercion(shape, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
  }

  public static final String VERSION = "plowshare-tool/1";

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
      Map<String, IntegrationPayload.Parameter> arguments) {
    public Request {
      if (!VERSION.equals(schema))
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
      var node = JSON.readTree(source.isEmpty() ? "{}" : source);
      if (node == null || !node.isObject())
        throw new IllegalArgumentException("Tool arguments must be an object");
      Map<String, IntegrationPayload.Parameter> values = new LinkedHashMap<>();
      var fields = node.fields();
      while (fields.hasNext()) {
        var field = fields.next();
        var value = field.getValue();
        IntegrationPayload.Parameter typed;
        if (value.isTextual()) typed = new IntegrationPayload.TextParameter(value.textValue());
        else if (value.isBoolean())
          typed = new IntegrationPayload.BooleanParameter(value.booleanValue());
        else if (value.isNumber())
          typed = new IntegrationPayload.NumberParameter(value.decimalValue());
        else throw new IllegalArgumentException("Tool arguments must be declared scalar values");
        values.put(field.getKey(), typed);
      }
      return new RelayToolDefinition.Arguments(values);
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
