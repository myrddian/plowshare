package io.aeyer.plowshare.server.information;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.aeyer.plowshare.protocol.InformationContent;
import io.aeyer.plowshare.protocol.InformationRequest;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Strict authored-information transport boundary; application services receive validated DTOs. */
public final class InformationContentInputs {
  private InformationContentInputs() {}

  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  public static boolean view(Object value) {
    if (value == null) return false;
    if (!InformationContent.VIEW.equals(value)) throw new CallerFault("unsupported-view");
    return true;
  }

  public static io.aeyer.plowshare.protocol.SchemaValue.ObjectValue metadata(Object raw) {
    try {
      return io.aeyer.plowshare.protocol.DocumentMetadata.instance(
          io.aeyer.plowshare.protocol.SchemaValue.fromWire(raw));
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("Invalid bounded metadata object");
    }
  }

  /**
   * Multipart text uses a duplicate-rejecting parser before converting to the same typed intent.
   */
  public static InformationContent.Typing multipart(String text) {
    if (text == null) return null;
    if (text.length() > 32768) throw new CallerFault("typing exceeds bounds");
    try {
      Object raw =
          JSON.copy()
              .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
              .readValue(text, Object.class);
      if (!(raw instanceof Map<?, ?>)) throw new CallerFault("typing must be an object");
      return typing(Map.of("typing", raw, "view", InformationContent.VIEW));
    } catch (java.io.IOException invalid) {
      throw new CallerFault("Invalid authored-information parameters");
    }
  }

  public static UUID expected(Map<String, Object> fields) {
    if (!fields.containsKey("expectedRevision")) return null;
    return identifier(fields.get("expectedRevision"), "expectedRevision");
  }

  private static UUID identifier(Object value, String field) {
    try {
      if (!(value instanceof String text)) throw new IllegalArgumentException();
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equalsIgnoreCase(text)) throw new IllegalArgumentException();
      return parsed;
    } catch (RuntimeException invalid) {
      throw new CallerFault(field + " must be a UUID");
    }
  }

  public static InformationContent.Typing typing(Map<String, Object> fields) {
    if (!fields.containsKey("typing")) {
      if (fields.containsKey("expectedRevision"))
        throw new CallerFault("expectedRevision requires explicit typing");
      return null;
    }
    if (!InformationContent.VIEW.equals(fields.get("view")))
      throw new CallerFault("unsupported-view");
    if (!(fields.get("typing") instanceof Map<?, ?> typing))
      throw new CallerFault("typing must be an explicit alternative");
    if (typing.containsKey("metadata") && typing.get("metadata") == null
        || typing.containsKey("version") && typing.get("version") == null)
      throw new CallerFault("Typing members cannot be explicitly null");
    if (typing.containsKey("version")) identifier(typing.get("version"), "version");
    return decode(typing, InformationContent.Typing.class);
  }

  public static InformationRequest command(String operation, Map<String, Object> fields) {
    if (!InformationContent.VIEW.equals(fields.get("view")))
      throw new CallerFault("unsupported-view");
    // Optional absence is distinct from explicit null. Null remains valid inside a metadata
    // instance when its schema permits it, never as an omitted command/typing member.
    fields.forEach(
        (key, value) -> {
          if (value == null) throw new CallerFault("Command members cannot be explicitly null");
          if (java.util.Set.of(
                  "requestId", "type", "version", "attachedRevision", "expectedRevision")
              .contains(key)) identifier(value, key);
        });
    if (operation.equals("content.revise")) typing(fields);
    var body = new LinkedHashMap<>(fields);
    var scope = body.remove("scope");
    var corpus = body.remove("corpus");
    var view = body.remove("view");
    var selection = new LinkedHashMap<String, Object>();
    selection.put("scope", scope == null ? Map.of("kind", "personal") : scope);
    selection.put("corpus", corpus);
    selection.put("view", view);
    body.put("selection", selection);
    return switch (operation) {
      case "types.list" -> decode(body, InformationRequest.TypeList.class);
      case "types.get" -> decode(body, InformationRequest.TypeRead.class);
      case "types.validate" -> decode(body, InformationRequest.TypeValidate.class);
      case "types.version.create" -> decode(body, InformationRequest.TypeDraft.class);
      case "types.activate", "types.deprecate" -> {
        if (body.containsKey("activate"))
          throw new CallerFault("activate is derived from the operation");
        body.put("activate", operation.equals("types.activate"));
        yield decode(body, InformationRequest.TypeTransition.class);
      }
      case "content.get" -> decode(body, InformationRequest.ContentRead.class);
      case "content.revise" -> decode(body, InformationRequest.ContentRevise.class);
      default -> throw new IllegalArgumentException("unknown authored-information operation");
    };
  }

  private static <T> T decode(Object fields, Class<T> shape) {
    try {
      return JSON.convertValue(fields, shape);
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("Invalid authored-information parameters");
    }
  }
}
