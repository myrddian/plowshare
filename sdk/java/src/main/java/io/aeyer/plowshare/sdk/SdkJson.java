package io.aeyer.plowshare.sdk;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.IOException;

/** Strict SDK codec. Optional future response fields are ignored, never scalar-coerced. */
final class SdkJson {
  private SdkJson() {}

  static ObjectMapper mapper() {
    ObjectMapper json =
        JsonMapper.builder(
                com.fasterxml.jackson.core.JsonFactory.builder()
                    .streamReadConstraints(
                        com.fasterxml.jackson.core.StreamReadConstraints.builder()
                            .maxStringLength(io.aeyer.plowshare.protocol.RelayPort.MAX_TEXT_BYTES)
                            .build())
                    .build())
            .findAndAddModules()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
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
    if (type.isCollectionLikeType()) {
      if (!node.isArray()) throw new IOException("Expected list at " + path);
      for (JsonNode item : node) shape(item, type.getContentType(), json, path + "[]");
    } else if (type.isMapLikeType()) {
      if (!node.isObject()) throw new IOException("Expected object at " + path);
      for (JsonNode value : node) shape(value, type.getContentType(), json, path + "{}");
    } else if (type.getRawClass().isRecord()) {
      if (!node.isObject()) throw new IOException("Expected object at " + path);
      for (var component : type.getRawClass().getRecordComponents()) {
        var property =
            component
                .getAccessor()
                .getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
        String name =
            json.getDeserializationConfig().introspect(type).findProperties().stream()
                .filter(field -> field.getInternalName().equals(component.getName()))
                .map(com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition::getName)
                .findFirst()
                .orElse(component.getName());
        if (!node.has(name) && property != null && property.defaultValue().equals("false")) {
          ((com.fasterxml.jackson.databind.node.ObjectNode) node).put(name, false);
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
