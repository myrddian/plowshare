package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.approvals.ToolCallTraces;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Map;

/** A bounded debug read behind the same conversation visibility as Trajectory. */
public final class ConversationToolTraceHandler implements FrameHandler {
  private final ToolCallTraces traces;

  public ConversationToolTraceHandler(ToolCallTraces traces) {
    this.traces = traces;
  }

  record Request(String conversation, String invocationId) {}

  record Reply(ToolCallTraces.Trace trace) {}

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    var request = Payloads.exact(payload, Request.class, FrameTypes.CONVERSATION_TOOL_TRACE);
    if (request.conversation() == null
        || request.conversation().isBlank()
        || request.invocationId() == null
        || !request.invocationId().matches("[A-Za-z0-9_-]{1,128}"))
      throw new CallerFault("A conversation and a valid retained invocation ID are required");
    return Outcome.ok(
        new Reply(traces.read(request.conversation(), request.invocationId()).orElse(null)));
  }
}
