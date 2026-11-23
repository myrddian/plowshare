package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HomeAssistantCapabilityConfigurationTest {
  @TempDir Path directory;

  @BeforeEach
  void privatePath() throws IOException {
    directory = directory.toRealPath();
  }

  private ObjectNode fixture() throws IOException {
    var value = HomeAssistantProviderConfigurationTest.fixture();
    value.remove("control");
    var policy =
        value
            .putObject("discovery")
            .put("mode", "fixed")
            .put("profile", "standard_measurements")
            .put("identityJournal", "private/identities");
    policy.putArray("excludedEntities");
    policy.putArray("excludedFields");
    return value;
  }

  private HomeAssistantProviderConfiguration decode(ObjectNode value) throws IOException {
    return HomeAssistantProviderConfiguration.read(
        HomeAssistantProviderConfigurationTest.write(directory, value));
  }

  @Test
  void optionalPolicyIsOfflineReadOnlyAndFingerprintIncludesFrozenAttributesAndSelectors()
      throws Exception {
    var initial = decode(fixture());
    assertTrue(initial.discovery().isPresent());
    assertTrue(initial.control().isEmpty());
    assertTrue(Files.notExists(directory.resolve("private")));
    var changed = fixture();
    ((ObjectNode) changed.path("homeAssistant").path("entities").path("lamp"))
        .putArray("attributes")
        .add("extra");
    var later = decode(changed);
    assertNotEquals(
        initial.discovery().orElseThrow().fingerprint(initial.homeAssistant()),
        later.discovery().orElseThrow().fingerprint(later.homeAssistant()));
    assertThrows(
        IOException.class,
        () ->
            decode(
                HomeAssistantProviderConfigurationTest.fixture()
                    .set("discovery", fixture().get("discovery"))));
    var absent = fixture();
    absent.remove("discovery");
    assertTrue(decode(absent).discovery().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"control", "unknown", "identityOverlap", "duplicate", "null", "field"})
  void invalidPolicyNeverCreatesOwners(String scenario) throws Exception {
    var value = fixture();
    var policy = (ObjectNode) value.get("discovery");
    switch (scenario) {
      case "control" -> policy.put("mode", "control");
      case "unknown" -> policy.put("systemPrompt", "not-supported");
      case "identityOverlap" -> policy.put("identityJournal", "private/read/nested");
      case "duplicate" -> policy.putArray("excludedEntities").add("sensor.same").add("sensor.same");
      case "null" -> policy.putNull("profile");
      case "field" -> policy.putArray("excludedFields").add("$.arbitrary.path");
      default -> throw new AssertionError();
    }
    assertThrows(IOException.class, () -> decode(value));
    assertTrue(Files.notExists(directory.resolve("private")));
  }

  @Test
  void fieldBoundUsesExactProfileProjectionRatherThanAnArbitraryReservedMargin() throws Exception {
    var value = fixture();
    ((ObjectNode) value.get("discovery")).put("profile", "explicit");
    var attributes =
        ((ObjectNode) value.path("homeAssistant").path("entities").path("lamp"))
            .putArray("attributes");
    for (int index = 0; index < 62; index++) attributes.add("field_" + index);
    assertEquals(62, decode(value).homeAssistant().entities().get("lamp").attributes().size());
    attributes.add("one_too_many");
    assertThrows(IOException.class, () -> decode(value));
  }
}
