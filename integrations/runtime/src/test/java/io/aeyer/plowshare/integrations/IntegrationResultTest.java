package io.aeyer.plowshare.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.aeyer.plowshare.protocol.IntegrationPayload.ActionContext;
import io.aeyer.plowshare.protocol.IntegrationPayload.NumberParameter;
import io.aeyer.plowshare.protocol.IntegrationPayload.Reading;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class IntegrationResultTest {
  @TempDir Path directory;

  static Stream<String> retainedResults() {
    return Stream.of(
        """
        {"states":{"temperature":{"alias":"temperature","state":"22.0","availability":"available",
        "attributes":{"gain":0.123456789012345678},"observed_at":"2026-11-13T00:00:00Z"}}}
        """,
        """
        {"acknowledged":true,"verification":"not_observed","context":{"id":"retained-context","parent_id":null}}
        """,
        "{\"diagnostic\":\"Retained unknown outcome; no replay.\"}");
  }

  @ParameterizedTest
  @MethodSource("retainedResults")
  void retained_private_journal_families_reopen_without_type_tags_or_evidence_rewrite(String result)
      throws Exception {
    String original =
        "{\"version\":1,\"state\":{},\"records\":{\"legacy\":{\"kind\":\"outgoing\","
            + "\"binding\":\"house\",\"status\":\"DONE\",\"revision\":2,\"state\":\"COMPLETED\",\"result\":"
            + result
            + "}}}";
    Path file = directory.resolve("journal.json");
    Files.writeString(file, original);
    IntegrationResult retained;
    try (var journal = new Journal(directory)) {
      journal.verifyDtos();
      var entry = journal.entries().getFirst().getValue();
      retained = entry.result();
      assertEquals(IntegrationCodec.read(result, IntegrationResult.class), retained);
      assertEquals(original, Files.readString(file), "Read-only validation must retain evidence");
      journal.writeEntry("legacy", entry);
    }
    try (var journal = new Journal(directory)) {
      journal.verifyDtos();
      assertEquals(retained, journal.entries().getFirst().getValue().result());
      String wire = IntegrationCodec.write(retained);
      assertFalse(wire.contains("@class"));
      assertEquals(retained, IntegrationCodec.read(wire, IntegrationResult.class));
    }
  }

  @Test
  void integration_results_reject_mixed_families_foreign_aliases_and_invalid_acknowledgements() {
    var state = reading("temperature");
    assertThrows(
        IllegalArgumentException.class,
        () -> new IntegrationResult(Map.of("other", state), null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new IntegrationResult(Map.of("temperature", state), true, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new IntegrationResult(Map.of("temperature", state), null, null, null, "mixed"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new IntegrationResult(null, false, null, new ActionContext("context", null), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new IntegrationResult(null, true, "invented", null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new IntegrationResult(null, null, "observed", null, "diagnostic"));
    assertThrows(
        IllegalArgumentException.class, () -> new IntegrationResult(null, null, null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new IntegrationResult(null, null, null, null, "x".repeat(32769)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new IntegrationResult(null, null, null, null, "invalid\0text"));
  }

  @Test
  void state_snapshots_are_bounded_immutable_and_preserve_exact_scalar_values() {
    Map<String, Reading> states = new HashMap<>();
    states.put("temperature", reading("temperature"));
    var result = new IntegrationResult(states, null, null, null, null);
    states.clear();
    assertEquals(1, result.states().size());
    assertThrows(UnsupportedOperationException.class, () -> result.states().clear());
    for (int index = 0; index < 257; index++) {
      String alias = "reading-" + index;
      states.put(alias, reading(alias));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new IntegrationResult(states, null, null, null, null));
    var gain = (NumberParameter) result.states().get("temperature").attributes().get("gain");
    assertEquals(new BigDecimal("0.123456789012345678"), gain.value());
  }

  @Test
  void owning_codec_refuses_other_result_families_unknown_fields_and_coerced_values() {
    for (String invalid :
        List.of(
            "{\"task\":{\"id\":\"remote\"}}",
            "{\"acknowledged\":\"true\"}",
            "{\"acknowledged\":true,\"account\":\"forged\"}",
            "{\"states\":{},\"diagnostic\":\"mixed\"}",
            "{\"states\":{\"temperature\":null}}"))
      assertThrows(
          IOException.class, () -> IntegrationCodec.read(invalid, IntegrationResult.class));
  }

  private static Reading reading(String alias) {
    return new Reading(
        alias,
        "22.0",
        "available",
        Map.of("gain", new NumberParameter(new BigDecimal("0.123456789012345678"))),
        null,
        null,
        null,
        null,
        null,
        "2026-11-13T00:00:00Z",
        null);
  }
}
