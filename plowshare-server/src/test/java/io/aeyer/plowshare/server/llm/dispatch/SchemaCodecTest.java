package io.aeyer.plowshare.server.llm.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchemaCodecTest {
  @Test
  void nested_constraints_survive_typed_conversion_and_serialization() {
    var schema =
        SchemaCodec.decode(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                    "choice",
                    Map.of("type", List.of("string", "null"), "enum", List.of("yes", "no"))),
                "required",
                List.of("choice"),
                "additionalProperties",
                false,
                "allOf",
                List.of(Map.of("properties", Map.of("choice", Map.of("maxLength", 3))))));
    var json = new ObjectMapper().valueToTree(schema);
    assertFalse(json.path("additionalProperties").booleanValue());
    assertEquals("null", json.path("properties").path("choice").path("type").get(1).textValue());
    assertEquals(
        3,
        json.path("allOf").get(0).path("properties").path("choice").path("maxLength").intValue());
    assertThrows(UnsupportedOperationException.class, () -> schema.properties().clear());
  }

  @Test
  void unsupported_keywords_types_and_numeric_coercion_are_rejected() {
    for (Map<?, ?> input :
        List.of(
            Map.of("unknown", true),
            Map.of("minLength", "1"),
            Map.of("minItems", 1.5),
            Map.of("type", "anything"),
            Map.of("required", List.of(1)),
            Map.of("enum", List.of(Map.of("arbitrary", "payload"))),
            Map.of("minimum", Double.NaN),
            Map.of("multipleOf", BigDecimal.ZERO),
            Map.of("minLength", 2, "maxLength", 1),
            Map.of("pattern", "["))) {
      assertThrows(IllegalArgumentException.class, () -> SchemaCodec.decode(input));
    }
  }

  @Test
  void cycles_and_excessive_depth_fail_at_conversion() {
    Map<String, Object> cycle = new LinkedHashMap<>();
    cycle.put("items", cycle);
    assertThrows(IllegalArgumentException.class, () -> SchemaCodec.decode(cycle));
    Map<String, Object> deep = Map.of("type", "string");
    for (int i = 0; i < 70; i++) deep = Map.of("items", deep);
    Map<String, Object> input = deep;
    assertThrows(IllegalArgumentException.class, () -> SchemaCodec.decode(input));
  }
}
