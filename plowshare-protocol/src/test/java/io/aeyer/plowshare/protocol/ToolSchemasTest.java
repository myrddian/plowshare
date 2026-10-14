package io.aeyer.plowshare.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolSchemasTest {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  @Test
  void shared_executable_profile_and_canonical_fingerprints() throws Exception {
    var fixture =
        JSON.readTree(Files.readString(Path.of("../test-support/contracts/tool-schemas.json")));
    for (var row : fixture.path("cases")) {
      String name = row.path("name").textValue();
      if (!row.path("validSchema").booleanValue()) {
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ToolSchemas.declaration(
                    SchemaCodec.decode(
                        JSON.readValue(row.path("schemaSource").textValue(), Map.class))),
            name);
        continue;
      }
      var schema = JSON.readValue(row.path("schemaSource").textValue(), SchemaDefinition.class);
      ToolSchemas.declaration(schema);
      assertEquals(
          JSON.readTree(row.path("schemaSource").textValue()),
          JSON.readTree(JSON.writeValueAsString(schema)),
          name);
      assertEquals(row.path("fingerprint").textValue(), SchemaCodec.fingerprint(schema), name);
      assertTrue(SchemaCodec.matchesFingerprint(schema, row.path("fingerprint").textValue()), name);
      assertFalse(SchemaCodec.matchesFingerprint(schema, "0".repeat(64)), name);
      if (row.has("legacyFingerprint")) {
        String legacy = row.path("legacyFingerprint").textValue();
        assertNotEquals(legacy, SchemaCodec.fingerprint(schema), name);
        assertTrue(SchemaCodec.matchesFingerprint(schema, legacy), name);
        var changed = new java.util.LinkedHashMap<>(SchemaCodec.wire(schema));
        changed.put("description", "changed");
        assertFalse(SchemaCodec.matchesFingerprint(SchemaCodec.decode(changed), legacy), name);
      }
      if (row.path("validArguments").booleanValue())
        ToolSchemas.validate(
            schema,
            ToolValue.fromWire(
                JSON.readValue(row.path("argumentsSource").textValue(), Object.class)));
      else
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ToolSchemas.validate(
                    schema,
                    ToolValue.fromWire(
                        JSON.readValue(row.path("argumentsSource").textValue(), Object.class))),
            name);
    }
  }

  @Test
  void recursive_values_are_immutable_and_integration_parameters_stay_scalar() {
    var value = (ToolValue.ObjectValue) ToolValue.fromWire(Map.of("values", List.of(1, true)));
    assertThrows(
        UnsupportedOperationException.class, () -> value.values().put("x", new ToolValue.Null()));
    assertThrows(
        IllegalArgumentException.class,
        () -> IntegrationPayload.Parameter.fromWire(Map.of("nested", true)));
    assertThrows(IllegalArgumentException.class, () -> IntegrationPayload.Parameter.fromWire(null));
    var fields = new java.util.LinkedHashMap<String, ToolValue>();
    fields.put("missing", new ToolValue.Null());
    var copy = new ToolValue.ObjectValue(fields);
    fields.clear();
    assertTrue(copy.values().containsKey("missing"));
  }
}
