package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldDescription;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldReadRequest;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldReading;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldRole;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldSelection;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldStatus;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldSupport;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.Query;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.ValueType;
import io.aeyer.plowshare.protocol.IntegrationPayload.TextParameter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HomeAssistantCapabilitiesTest {
  @Test
  void typedBoundaryRejectsWrongValueShapeAndOverlargeOrDuplicateSelections() {
    var field =
        new FieldDescription(
            "attribute.current_temperature",
            "Current temperature",
            ValueType.NUMBER,
            Optional.of("°C"),
            "temperature",
            FieldRole.CURRENT,
            FieldSupport.SUPPORTED);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FieldReading(
                "r_" + "a".repeat(64),
                "climate.office",
                field,
                FieldStatus.AVAILABLE,
                Optional.of(new TextParameter("19.4")),
                Optional.empty(),
                Instant.now(),
                false));
    var selected = new FieldSelection("r_" + "a".repeat(64), List.of("state"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FieldReadRequest("a".repeat(64), List.of(selected, selected)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Query(
                "",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                1,
                20,
                Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FieldSelection("r_" + "a".repeat(64), List.of("state", "state")));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FieldSelection("r_" + "a".repeat(64), List.of("$.secret")));
  }

  @Test
  void effectiveExposureVersionCannotOverflowIntoSupportedVersion() {
    var value = Json.object().put("version", 4294967297L).put("assistant", "conversation");
    value.putArray("entities").add("climate.office");
    assertThrows(IllegalArgumentException.class, () -> HomeAssistantCapabilityWire.exposed(value));
    value.put("version", 1);
    assertEquals(Set.of("climate.office"), HomeAssistantCapabilityWire.exposed(value));
    value.put("version", 1.0);
    assertThrows(IllegalArgumentException.class, () -> HomeAssistantCapabilityWire.exposed(value));
  }
}
