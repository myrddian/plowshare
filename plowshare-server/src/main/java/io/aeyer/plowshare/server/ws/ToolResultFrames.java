package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.ToolResults;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.tools.RelayToolInvocations;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Reuses the invocation owner's existing non-submitting reconciliation service. */
@Component
public final class ToolResultFrames implements FrameArea {
  private final RelayToolInvocations invocations;

  public ToolResultFrames(RelayToolInvocations invocations) {
    this.invocations = invocations;
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.of(FrameTypes.TOOL_INVOCATION_READ, this::read);
  }

  Outcome read(Map<String, Object> payload, Asking asking) {
    ToolResults.Read request;
    try {
      if (!payload.keySet().equals(Set.of("project", "invocation"))
          || !(payload.get("project") instanceof String project)
          || !(payload.get("invocation") instanceof String invocation)
          || !invocation.matches("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}"))
        throw new IllegalArgumentException();
      request = new ToolResults.Read(project, UUID.fromString(invocation));
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("Invalid tool invocation read request");
    }
    var result =
        invocations.read(
            request.project(),
            asking.requireHandle(FrameTypes.TOOL_INVOCATION_READ),
            request.invocation());
    return Outcome.ok(
        new ToolResults.Receipt(
            result.id(), result.state().name(), result.text(), result.content()));
  }
}
