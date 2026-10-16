package io.aeyer.plowshare.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DocumentMetadataTest {
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  private SchemaDefinition schema(String json) throws Exception {
    return JSON.readValue(json, SchemaDefinition.class);
  }

  private SchemaValue.ObjectValue metadata(String json) throws Exception {
    return DocumentMetadata.instance(JSON.readValue(json, SchemaValue.class));
  }

  @Test
  void metadata_validation_is_independent_of_tools_and_does_not_insert_defaults() throws Exception {
    var definition =
        schema(
            """
        {"type":"object","properties":{"severity":{"type":"string","enum":["high","low"]},
         "date":{"type":"string","format":"date-time"},"note":{"type":["string","null"],"default":"default"}},
         "required":["severity"],"additionalProperties":false}
        """);
    var value = metadata("{\"severity\":\"high\",\"date\":\"unvalidated date\",\"note\":null}");
    assertEquals(
        SchemaValidation.Status.VALID, DocumentMetadata.validate(definition, value).status());
    var omitted = metadata("{\"severity\":\"low\"}");
    assertEquals(
        SchemaValidation.Status.VALID, DocumentMetadata.validate(definition, omitted).status());
    assertFalse(omitted.values().containsKey("note"));
    assertEquals(
        SchemaValidation.Status.INVALID,
        DocumentMetadata.validate(definition, metadata("{}")).status());
    assertEquals(
        SchemaValidation.Status.INVALID,
        DocumentMetadata.validate(
                definition, metadata("{\"severity\":\"high\",\"unexpected\":true}"))
            .status());
  }

  @Test
  void nested_errors_have_escaped_pointers_and_do_not_echo_values() throws Exception {
    var definition =
        schema(
            """
        {"type":"object","properties":{"a/b~c":{"type":"array","items":{"type":"integer","minimum":1}}}}
        """);
    var result = DocumentMetadata.validate(definition, metadata("{\"a/b~c\":[\"private value\"]}"));
    assertEquals(SchemaValidation.Status.INVALID, result.status());
    var issue = result.issues().getFirst();
    assertEquals("/a~1b~0c/0", issue.instancePointer());
    assertEquals("/properties/a~1b~0c/items/type", issue.schemaPointer());
    assertEquals("type", issue.keyword());
    assertFalse(JSON.writeValueAsString(result).contains("private value"));
  }

  @Test
  void failed_composition_probes_do_not_make_a_valid_instance_invalid() throws Exception {
    var definition =
        schema(
            """
        {"type":"object","properties":{"value":{"anyOf":[{"type":"integer"},{"type":"string"}]},
         "other":{"not":{"type":"integer"}},"conditional":{"if":{"type":"integer"},"then":{"minimum":10},"else":{"type":"string"}}}}
        """);
    var result =
        DocumentMetadata.validate(
            definition,
            metadata("{\"value\":\"text\",\"other\":\"text\",\"conditional\":\"text\"}"));
    assertEquals(SchemaValidation.Status.VALID, result.status());
    assertTrue(result.issues().isEmpty());
  }

  @Test
  void instances_round_trip_immutably_and_identity_normalises_numbers() throws Exception {
    var first = metadata("{\"a\":1,\"aa\":null}");
    var second = metadata("{\"aa\":null,\"a\":1.0}");
    assertEquals(SchemaCodec.fingerprint(first), SchemaCodec.fingerprint(second));
    assertEquals(first, JSON.readValue(SchemaCodec.compactJson(first), SchemaValue.class));
    assertThrows(UnsupportedOperationException.class, () -> first.values().clear());
    assertThrows(
        IllegalArgumentException.class,
        () -> new SchemaValue.Number(new BigDecimal("9007199254740992")));
    assertThrows(
        IllegalArgumentException.class, () -> DocumentMetadata.instance(new SchemaValue.Null()));
    assertThrows(Exception.class, () -> metadata("{\"a\":1,\"a\":2}"));
  }

  @Test
  void byte_limits_and_presentation_paths_are_authoritative() throws Exception {
    var definition =
        schema("{\"type\":\"object\",\"properties\":{\"severity\":{\"type\":\"string\"}}}");
    var presentation =
        metadata("{\"fieldOrder\":[\"/severity\"],\"descriptions\":{\"/severity\":\"Severity\"}}");
    assertEquals(presentation, DocumentMetadata.presentation(definition, presentation));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DocumentMetadata.presentation(
                definition, metadata("{\"fieldOrder\":[\"/producer\"]}")));
    assertThrows(
        IllegalArgumentException.class,
        () -> DocumentMetadata.presentation(definition, metadata("{\"html\":\"<script>\"}")));
    var fields = new java.util.LinkedHashMap<String, SchemaValue>();
    for (int index = 0; index < 5; index++)
      fields.put("field" + index, new SchemaValue.Text("x".repeat(4096)));
    assertThrows(
        IllegalArgumentException.class,
        () -> DocumentMetadata.instance(new SchemaValue.ObjectValue(fields)));
    String contract =
        SchemaCodec.documentFingerprint(definition, DocumentMetadata.PROFILE, presentation);
    assertNotEquals(
        contract,
        SchemaCodec.documentFingerprint(
            definition, DocumentMetadata.PROFILE, DocumentMetadata.empty()));
  }

  @Test
  void document_profile_rejects_names_reserved_by_public_sdk_object_codecs() throws Exception {
    for (String name : java.util.List.of("__proto__", "prototype", "constructor")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              DocumentMetadata.instance(
                  new SchemaValue.ObjectValue(
                      Map.of(
                          "nested",
                          new SchemaValue.ObjectValue(
                              Map.of(name, new SchemaValue.Text("value")))))));
      var definition =
          schema("{\"type\":\"object\",\"properties\":{\"" + name + "\":{\"type\":\"string\"}}}");
      assertThrows(IllegalArgumentException.class, () -> DocumentMetadata.definition(definition));
    }
  }

  @Test
  void instance_work_exhaustion_is_distinct_from_constraint_mismatch() throws Exception {
    var definition =
        schema(
            "{\"type\":\"object\",\"properties\":{\"items\":{\"type\":\"array\",\"uniqueItems\":true}}}");
    var items =
        java.util.stream.IntStream.range(0, 300)
            .mapToObj(number -> (SchemaValue) new SchemaValue.Number(BigDecimal.valueOf(number)))
            .toList();
    var value = new SchemaValue.ObjectValue(Map.of("items", new SchemaValue.Array(items)));
    assertEquals(
        SchemaValidation.Status.BUDGET_EXHAUSTED,
        DocumentMetadata.validate(definition, value).status());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DocumentMetadata.definition(
                schema("{\"type\":\"object\",\"$ref\":\"https://example.test/schema\"}")));
  }
}
