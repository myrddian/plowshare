package io.aeyer.plowshare.integrations;

import io.aeyer.plowshare.integrations.IntegrationContracts.*;
import io.aeyer.plowshare.protocol.IntegrationPayload.Reading;
import java.util.*;

/** Pure route policy. Held intervals belong to one runtime session and adapter epoch. */
final class ThresholdPolicy {
  private ThresholdPolicy() {}

  static boolean high(Configuration.Route route, Reading reading) {
    if (reading == null || reading.state() == null) return false;
    try {
      double value = Double.parseDouble(reading.state());
      return Double.isFinite(value)
          && route.above() != null
          && value > route.above()
          && (route.unit() == null || route.unit().equals(reading.unit()));
    } catch (NumberFormatException invalid) {
      return false;
    }
  }

  static boolean availableHigh(Configuration.Route route, Reading reading) {
    return reading != null && "available".equals(reading.availability()) && high(route, reading);
  }

  static boolean cooled(Configuration.Route route, RouteState state, long now) {
    return state.started() == null || now - state.started() >= route.cooldownSeconds();
  }

  /**
   * Updates only the caller's working route map; publication occurs with the handler transaction.
   */
  static Set<String> observe(
      Configuration.Binding binding,
      Map<String, RouteState> routes,
      EventEnvelope event,
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
      RouteState old = routes.getOrDefault(route.name(), RouteState.empty()), next = old;
      if (route.holdSeconds() > 0 && event.event() instanceof Gap) {
        routes.put(route.name(), old.cancel());
        continue;
      }
      Reading reading = event.reading();
      if (reading == null || !route.entity().equals(reading.alias())) continue;
      if (route.holdSeconds() > 0 && !(event.event() instanceof StateChanged)) continue;
      boolean high = high(route, reading),
          live = !event.resync() && "available".equals(reading.availability());
      next = new RouteState(high, old.started(), old.edge(), old.hold());
      if (route.holdSeconds() == 0) {
        if (!suppressed && live && high && !old.high() && cooled(route, old, now))
          eligible.add(route.name());
      } else {
        if (!currentInvocation || suppressed) {
          routes.put(route.name(), next.cancel());
          continue;
        }
        Edge edge = old.edge();
        if (!live
            || !high
            || edge == null
            || !session.equals(edge.session())
            || !Objects.equals(reading.epoch(), edge.epoch())) next = next.cancel();
        if (live
            && high
            && !old.high()
            && cooled(route, old, now)
            && reading.epoch() != null
            && !reading.epoch().isBlank())
          next =
              new RouteState(
                  high,
                  old.started(),
                  new Edge(id, session, reading.epoch()),
                  new Hold(id, session, reading.epoch(), now));
      }
      routes.put(route.name(), next);
    }
    return eligible;
  }

  static boolean validHold(
      Configuration.Route route, Hold hold, Reading reading, String session, long now) {
    return route.holdSeconds() > 0
        && hold != null
        && reading != null
        && now >= hold.since()
        && session.equals(hold.session())
        && Objects.equals(reading.epoch(), hold.epoch())
        && availableHigh(route, reading);
  }
}
