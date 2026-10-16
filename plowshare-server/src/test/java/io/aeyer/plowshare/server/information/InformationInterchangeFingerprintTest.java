package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.protocol.InformationInterchange.*;
import io.aeyer.plowshare.protocol.InformationInterchange.Provenance;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class InformationInterchangeFingerprintTest {
  final UUID id = UUID.randomUUID(), version = UUID.randomUUID();

  ImportPlan plan(boolean reverse, int bValue, String text) {
    var properties = new LinkedHashMap<String, Object>();
    var metadata = new LinkedHashMap<String, Object>();
    for (String name : reverse ? List.of("b", "a") : List.of("a", "b")) {
      properties.put(name, Map.of("type", "integer"));
      metadata.put(name, name.equals("a") ? 1 : bValue);
    }
    var schema =
        SchemaDefinition.fromWire(
            Map.of("type", "object", "properties", properties, "additionalProperties", false));
    var contract =
        new InformationContent.Contract(
            id,
            version,
            1,
            "notes",
            "Notes",
            "",
            DocumentMetadata.PROFILE,
            SchemaCodec.documentFingerprint(
                schema, DocumentMetadata.PROFILE, DocumentMetadata.empty()),
            schema,
            DocumentMetadata.empty());
    var value = DocumentMetadata.instance(SchemaValue.fromWire(metadata));
    var binding = new InformationContent.Binding(contract, SchemaCodec.fingerprint(value), value);
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    String hash = InformationInterchange.hash(bytes);
    var descriptor =
        new Descriptor(
            id,
            id,
            1,
            "Notes",
            "Notes",
            "documents/source.md",
            "text/markdown",
            hash,
            List.of(),
            binding,
            new Provenance(
                "foreign-owner",
                "source",
                "2026-10-16T00:00:00Z",
                hash,
                List.of(),
                List.of(),
                null,
                null,
                null));
    var bundle =
        new Bundle(
            InformationInterchange.FORMAT,
            id,
            List.of(new Document(descriptor, Base64.getEncoder().encodeToString(bytes))),
            List.of(),
            InformationInterchange.COVERAGE);
    return new ImportPlan(
        bundle,
        List.of(new Destination(id, "Notes", "create", null)),
        List.of(new TypeMapping(version, version)),
        List.of(),
        false);
  }

  @Test
  void receipt_identity_survives_sdk_json_schema_and_metadata_key_reordering() {
    assertEquals(
        InformationInterchangeFingerprint.of(plan(false, 2, "Body")),
        InformationInterchangeFingerprint.of(plan(true, 2, "Body")));
    assertNotEquals(
        InformationInterchangeFingerprint.of(plan(false, 2, "Body")),
        InformationInterchangeFingerprint.of(plan(true, 3, "Body")));
    assertNotEquals(
        InformationInterchangeFingerprint.of(plan(false, 2, "Body")),
        InformationInterchangeFingerprint.of(plan(false, 2, "Edited")));
  }
}
