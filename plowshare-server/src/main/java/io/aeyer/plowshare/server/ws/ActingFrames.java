package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.ActingContext;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.acting.ActingContexts;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Proof exchange is separate from Application login and every ordinary operation payload. */
@Component
public final class ActingFrames implements FrameArea {
  private final ActingContexts contexts;

  public ActingFrames(ActingContexts contexts) {
    this.contexts = contexts;
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(
        FrameTypes.ACTING_CONTEXT_OPEN,
        this::open,
        FrameTypes.ACTING_CONTEXT_CLOSE,
        this::close,
        FrameTypes.ACTING_TURN_START,
        this::selectionRequired,
        FrameTypes.ACTING_TURN_RECEIPT,
        this::selectionRequired);
  }

  private Outcome selectionRequired(Map<String, Object> payload, Asking asking) {
    throw new CallerFault("Select an acting context for this operation");
  }

  static ActingContexts.Connection connection(Asking asking) {
    try {
      return new ActingContexts.Connection(
          asking.connectionId(), asking.sessionId(), asking.requireHandle("acting context"));
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("Acting requires an authenticated Application connection");
    }
  }

  private Outcome open(Map<String, Object> payload, Asking asking) {
    fields(payload, Set.of("application", "grant", "proof"));
    if (!(payload.get("proof") instanceof Map<?, ?> proof)
        || !proof.keySet().equals(Set.of("kind", "credential"))
        || !(proof.get("kind") instanceof String kind)
        || !(proof.get("credential") instanceof String credential))
      throw new CallerFault("Specify one acting-token proof");
    ActingContext.Open request;
    try {
      request =
          new ActingContext.Open(
              text(payload, "application"),
              text(payload, "grant"),
              new ActingContext.Proof(kind, credential));
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("Invalid acting proof exchange");
    }
    return Outcome.ok(contexts.open(connection(asking), request));
  }

  private Outcome close(Map<String, Object> payload, Asking asking) {
    fields(payload, Set.of("context"));
    contexts.close(connection(asking), uuid(text(payload, "context")));
    return Outcome.ok(Map.of());
  }

  static UUID uuid(String value) {
    if (!value.matches("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}"))
      throw new CallerFault("Choose a canonical acting context UUID");
    return UUID.fromString(value);
  }

  static String text(Map<String, Object> payload, String field) {
    if (!(payload.get(field) instanceof String value))
      throw new CallerFault("Missing acting request field");
    return value;
  }

  static void fields(Map<String, Object> payload, Set<String> fields) {
    if (payload == null || !payload.keySet().equals(fields))
      throw new CallerFault("Invalid acting request fields");
  }
}
