package io.aeyer.plowshare.server.ws;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.aeyer.plowshare.protocol.frames.Envelope;
import java.io.IOException;
import java.util.Set;

/**
 * Transport scheduling only: this allowlist never admits authority or replaces handler validation.
 * Search, await, subscriptions, cursor/lease acquisition and acting-context operations stay
 * ordered. Malformed envelopes also stay ordered; operation payload validation belongs to the
 * router and may run independently for an admitted envelope. Information status also stays ordered:
 * its hidden-read fallback acquires write-admission locks. Add a type only after auditing its
 * complete handler path for effects, including optional fields.
 */
final class EventReadPolicy {
  private static final ObjectMapper JSON =
      FrameJson.reading()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  static {
    for (var shape :
        Set.of(CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean)) {
      JSON.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
    }
  }

  private static final Set<String> INDEPENDENT =
      Set.of(
          "document.list",
          "document.detail",
          "inbox.list",
          "orchestration.list",
          "information.list",
          "information.facets",
          "information.read",
          "information.navigation.capabilities",
          "information.documents.list",
          "information.documents.facets",
          "information.documents.get",
          "information.relationships.list",
          "relay.topics",
          "relay.log",
          "schedule.list",
          "schedule.files");

  private EventReadPolicy() {}

  static boolean independent(String frame) {
    try {
      var body = JSON.readTree(frame);
      // Routing rejects an explicitly null actingFor rather than treating it as absent. Inspect
      // presence before binding so that malformed selections remain ordered barriers as well.
      if (body == null || body.has("actingFor")) return false;
      Envelope request = JSON.treeToValue(body, Envelope.class);
      return request != null
          && request.id() != null
          && !request.id().isBlank()
          && request.payload() != null
          && request.actingFor() == null
          && INDEPENDENT.contains(request.type());
    } catch (IOException | IllegalArgumentException malformed) {
      // Routing owns the refusal and correlation. Failed scheduling decode is an ordered barrier.
      return false;
    }
  }
}
