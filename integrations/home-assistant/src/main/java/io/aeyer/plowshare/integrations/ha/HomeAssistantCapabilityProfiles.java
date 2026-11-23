package io.aeyer.plowshare.integrations.ha;

import java.util.Set;

/**
 * Established read-only HA measurement attributes, independent of entity names or device targets.
 */
final class HomeAssistantCapabilityProfiles {
  private HomeAssistantCapabilityProfiles() {}

  static Set<String> attributes(String domain) {
    return switch (domain) {
      case "climate" ->
          Set.of("current_temperature", "temperature", "current_humidity", "humidity");
      case "water_heater" -> Set.of("current_temperature", "temperature");
      case "humidifier" -> Set.of("current_humidity", "humidity");
      case "weather" -> Set.of("temperature", "humidity", "pressure", "wind_speed");
      default -> Set.of();
    };
  }
}
