package io.aeyer.plowshare.server.harness;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HarnessConfigurationTest {
  @Test
  void configured_hook_names_do_not_coerce_numbers_or_merge_duplicate_factories() {
    var properties = new HarnessProperties();
    var factory = new HarnessTest.Failing();
    properties.setProfiles(Map.of("profile", List.of(Map.of("hook", 7))));
    assertThrows(
        IllegalStateException.class,
        () -> HarnessConfiguration.decode(properties, Map.of(), List.of(factory)));
    assertEquals(0, factory.attempts);
    properties.setProfiles(Map.of("profile", List.of(Map.of("hook", factory.name()))));
    assertThrows(
        IllegalStateException.class,
        () -> HarnessConfiguration.decode(properties, Map.of(), List.of(factory, factory)));
    assertEquals(0, factory.attempts);
  }

  @Test
  void names_and_assignments_are_validated_even_for_direct_construction() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HarnessConfiguration(Map.of("bad\n", List.of()), Map.of(), null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HarnessConfiguration(
                Map.of("profile", List.of()), Map.of("model\0", "profile"), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new HarnessConfiguration(Map.of("profile", List.of()), Map.of(), " profile "));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new HarnessConfiguration(
                Map.of("profile", List.of()), Map.of("model", "missing"), null));
  }
}
