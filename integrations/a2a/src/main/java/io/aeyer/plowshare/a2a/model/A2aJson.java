package io.aeyer.plowshare.a2a.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Adapter-owned peer JSON boundary. Domain records stay independent from transport property bags.
 * Required primitives and nested records are checked before construction; each codec decides its
 * closed peer shape. No public SDK codec or package-private platform implementation is exposed.
 */
final class A2aJson {
  private A2aJson() {}

  static ObjectMapper mapper() {
    ObjectMapper json =
        JsonMapper.builder(
                JsonFactory.builder()
                    .streamReadConstraints(
                        StreamReadConstraints.builder().maxStringLength(1024 * 1024).build())
                    .build())
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .build();
    for (CoercionInputShape shape :
        new CoercionInputShape[] {
          CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean
        }) json.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
    return json;
  }

  static <T> T decode(ObjectMapper json, JsonNode node, Class<T> type) throws IOException {
    JsonNode prepared = node == null ? null : node.deepCopy();
    shape(prepared, json.constructType(type), json, type.getSimpleName());
    try {
      return json.treeToValue(prepared, type);
    } catch (IllegalArgumentException invalid) {
      throw new IOException("Invalid " + type.getSimpleName(), invalid);
    }
  }

  /** Record primitives are required even when Jackson would otherwise invent zero or false. */
  private static void shape(JsonNode node, JavaType type, ObjectMapper json, String path)
      throws IOException {
    if (node == null || node.isNull()) {
      if (type.isPrimitive()) throw new IOException("Missing required " + path);
      return;
    }
    if (type.getRawClass() == UUID.class
        && (!node.isTextual()
            || !node.textValue().matches("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")))
      throw new IOException("Invalid UUID at " + path);
    if (type.getRawClass() == Instant.class) {
      if (!node.isTextual()) throw new IOException("Expected ISO timestamp at " + path);
      try {
        Instant.parse(node.textValue());
      } catch (DateTimeParseException invalid) {
        throw new IOException("Invalid ISO timestamp at " + path);
      }
      return;
    }
    if (type.isCollectionLikeType()) {
      if (!node.isArray()) throw new IOException("Expected list at " + path);
      for (JsonNode item : node) shape(item, type.getContentType(), json, path + "[]");
    } else if (type.isMapLikeType()) {
      if (!node.isObject()) throw new IOException("Expected object at " + path);
      for (JsonNode value : node) shape(value, type.getContentType(), json, path + "{}");
    } else if (type.getRawClass().isRecord()) {
      if (!node.isObject()) throw new IOException("Expected object at " + path);
      for (var component : type.getRawClass().getRecordComponents()) {
        var property = component.getAccessor().getAnnotation(JsonProperty.class);
        String name =
            json.getDeserializationConfig().introspect(type).findProperties().stream()
                .filter(field -> field.getInternalName().equals(component.getName()))
                .map(BeanPropertyDefinition::getName)
                .findFirst()
                .orElse(component.getName());
        if (!node.has(name) && property != null && property.required())
          throw new IOException("Missing required " + path + "." + name);
        if (!node.has(name) && property != null && property.defaultValue().equals("false")) {
          ((ObjectNode) node).put(name, false);
        }
        shape(
            node.get(name),
            json.constructType(component.getGenericType()),
            json,
            path + "." + name);
      }
    }
  }
}
