package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.ImageAdmissions;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.images.ArtifactAdmissions;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Closed producer-status transport; it exposes no image listing or binary read. */
@Component
public final class ArtifactFrames implements FrameArea {
  private final ArtifactAdmissions admissions;

  public ArtifactFrames(ArtifactAdmissions admissions) {
    this.admissions = admissions;
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(FrameTypes.ARTIFACT_ADMISSION_STATUS, this::status);
  }

  Outcome status(Map<String, Object> payload, Asking asking) {
    ImageAdmissions.Request request;
    try {
      if (!payload.keySet().equals(Set.of("invocation", "ordinal", "requestId"))
          || !(payload.get("invocation") instanceof String invocation)
          || !(payload.get("requestId") instanceof String requestId)
          || !(payload.get("ordinal") instanceof Integer ordinal)
          || !invocation.matches("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")
          || !requestId.matches("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}"))
        throw new IllegalArgumentException();
      request =
          new ImageAdmissions.Request(
              UUID.fromString(invocation), ordinal, UUID.fromString(requestId));
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("invalid artifact admission status request");
    }
    return Outcome.ok(
        admissions.status(asking.requireHandle(FrameTypes.ARTIFACT_ADMISSION_STATUS), request));
  }
}
