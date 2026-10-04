package io.aeyer.plowshare.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Pure route policy. Held intervals belong to one runtime session and adapter epoch. */
final class ThresholdPolicy {
  private ThresholdPolicy() {}

  static boolean high(Configuration.Route route, JsonNode reading) {
    try {
      double value = Double.parseDouble(reading.path("state").asText());
      return Double.isFinite(value)
          && route.above() != null
          && value > route.above()
          && (route.unit() == null || route.unit().equals(reading.path("unit").asText()));
    } catch (NumberFormatException invalid) {
      return false;
    }
  }

  static boolean availableHigh(Configuration.Route route, JsonNode reading) {
    return reading.path("availability").asText().equals("available") && high(route, reading);
  }

  static boolean cooled(Configuration.Route route, JsonNode state, long now) {
    return !state.has("started") || now - state.path("started").asLong() >= route.cooldownSeconds();
  }

  static void cancel(ObjectNode state) {
    state.remove(List.of("hold", "edge"));
  }

  static Set<String> observe(
      Configuration.Binding binding,
      ObjectNode routes,
      JsonNode event,
      String id,
      long now,
      String session,
      boolean currentInvocation,
      boolean suppressed) {
    Set<String> eligible = new HashSet<>();
    for (var route : binding.routes().values()) {
      if (route.entity() == null) {
        if (!suppressed) eligible.add(route.name());
        continue;
      }
      JsonNode old = routes.path(route.name());
      ObjectNode next = old.isObject() ? (ObjectNode) old.deepCopy() : Json.object();
      if (route.holdSeconds() > 0 && event.path("type").asText().equals("connection.gap")) {
        cancel(next);
        routes.set(route.name(), next);
        continue;
      }
      if (!route.entity().equals(event.path("alias").asText())) continue;
      if (route.holdSeconds() > 0 && !event.path("type").asText().equals("state_changed")) continue;
      boolean high = high(route, event);
      boolean live =
          !event.path("resync").asBoolean()
              && event.path("availability").asText().equals("available");
      next.put("high", high);
      if (route.holdSeconds() == 0) {
        if (!suppressed && live && high && !old.path("high").asBoolean() && cooled(route, old, now))
          eligible.add(route.name());
      } else {
        if (!currentInvocation || suppressed) {
          cancel(next);
          routes.set(route.name(), next);
          continue;
        }
        JsonNode edge = old.path("edge");
        if (!live
            || !high
            || !session.equals(edge.path("session").asText())
            || !event.path("epoch").equals(edge.path("epoch"))) cancel(next);
        if (live
            && high
            && !old.path("high").asBoolean()
            && cooled(route, old, now)
            && event.path("epoch").isTextual()
            && !event.path("epoch").asText().isBlank()) {
          ObjectNode armed =
              Json.object()
                  .put("id", id)
                  .put("session", session)
                  .put("epoch", event.path("epoch").asText());
          next.set("edge", armed);
          next.set("hold", armed.deepCopy().put("since", now));
        }
      }
      routes.set(route.name(), next);
    }
    return eligible;
  }

  static boolean validHold(
      Configuration.Route route, JsonNode hold, JsonNode reading, String session, long now) {
    return route.holdSeconds() > 0
        && hold.isObject()
        && hold.path("since").isIntegralNumber()
        && now >= hold.path("since").asLong()
        && session.equals(hold.path("session").asText())
        && reading.path("epoch").isTextual()
        && reading.path("epoch").equals(hold.path("epoch"))
        && availableHigh(route, reading);
  }
}
