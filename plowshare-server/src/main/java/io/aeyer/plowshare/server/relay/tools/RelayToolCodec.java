package io.aeyer.plowshare.server.relay.tools;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.IntegrationPayload;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.protocol.ToolContent;
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
    // Shared schema/value work budgets still apply; one transport chunk is not a schema ceiling.
    RelayPort.text(source);
    try {
      var value = JSON.readValue(source, io.aeyer.plowshare.protocol.SchemaDefinition.class);
      io.aeyer.plowshare.protocol.ToolSchemas.declaration(value);
      return value;
    } catch (IOException invalid) {
      throw new IllegalArgumentException("Invalid tool schema configuration", invalid);
    }
  }

  public static final String VERSION = "plowshare-tool/1";
  public static final String CONTENT_VERSION = "plowshare-tool/3";
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
          String schemaFingerprint,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          String resultProfile) {
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
          null,
          null);
    }

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
        Map<String, ToolValue> arguments,
        String schemaFingerprint) {
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
          schemaFingerprint,
          null);
    }

    public Request {
      if (!VERSION.equals(schema)
              && !NESTED_VERSION.equals(schema)
              && !CONTENT_VERSION.equals(schema)
          || NESTED_VERSION.equals(schema)
              && (schemaFingerprint == null || !schemaFingerprint.matches("[0-9a-f]{64}"))
          || VERSION.equals(schema) && schemaFingerprint != null)
        throw new IllegalArgumentException("Invalid tool envelope version");
      if (CONTENT_VERSION.equals(schema) != ToolContent.PROFILE.equals(resultProfile)
          || resultProfile != null && !ToolContent.PROFILE.equals(resultProfile)
          || CONTENT_VERSION.equals(schema)
              && schemaFingerprint != null
              && !schemaFingerprint.matches("[0-9a-f]{64}"))
        throw new IllegalArgumentException("Invalid tool result negotiation");
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
      String text,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          ToolContent.Body content) {
    public Result(
        String schema,
        String invocationId,
        String project,
        String provider,
        String tool,
        State state,
        String text) {
      this(schema, invocationId, project, provider, tool, state, text, null);
    }

    public Result {
      if (!VERSION.equals(schema) && !CONTENT_VERSION.equals(schema)
          || CONTENT_VERSION.equals(schema) != (content != null))
        throw new IllegalArgumentException("Invalid tool envelope version");
      RelayPort.uuid(invocationId);
      RelayPort.identity(project);
      RelayPort.identity(provider);
      RelayPort.identity(tool);
      java.util.Objects.requireNonNull(state);
      if (text == null
          || text.length() > 16384
          || text.indexOf('\0') >= 0
          || content == null && text.isBlank()
          || content != null
              && (!text.equals(content.text())
                  || state != State.COMPLETED && !content.images().isEmpty()))
        throw new IllegalArgumentException("Invalid tool result content");
    }
  }

  /** v3 stores content once; the legacy text accessor is its exact derived projection. */
  private record ContentResult(
      String schema,
      String invocationId,
      String project,
      String provider,
      String tool,
      State state,
      ToolContent.Body content) {
    Result result() {
      if (!CONTENT_VERSION.equals(schema) || content == null)
        throw new IllegalArgumentException("Invalid content result");
      return new Result(
          schema, invocationId, project, provider, tool, state, content.text(), content);
    }
  }

  static String write(HostedInvocation value) {
    return encode(value);
  }

  static HostedInvocation hosted(String source) {
    if (source == null) return null;
    try {
      return JSON.readValue(source, HostedInvocation.class);
    } catch (IOException invalid) {
      throw new IllegalStateException("Invalid retained hosted invocation");
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
      var shape = JSON.readTree(source);
      if (CONTENT_VERSION.equals(shape.path("schema").asText()))
        return JSON.treeToValue(shape, ContentResult.class).result();
      if (shape.has("content"))
        throw new IllegalArgumentException("Legacy tool results cannot contain content");
      return JSON.readValue(source, Result.class);
    } catch (IOException malformed) {
      throw new IllegalArgumentException("Invalid tool result envelope", malformed);
    }
  }

  static Request request(String source) {
    try {
      var shape = JSON.readTree(source);
      int count =
          CONTENT_VERSION.equals(shape.path("schema").asText())
              ? 11 + (shape.has("schemaFingerprint") ? 1 : 0)
              : NESTED_VERSION.equals(shape.path("schema").asText()) ? 11 : 10;
      if (!shape.isObject()
          || shape.size() != count
          || shape.has("schemaFingerprint") && !shape.get("schemaFingerprint").isTextual())
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
    if (value.content() != null)
      return encode(
          new ContentResult(
              value.schema(),
              value.invocationId(),
              value.project(),
              value.provider(),
              value.tool(),
              value.state(),
              value.content()));
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
