package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.curator.Passes;
import io.aeyer.plowshare.server.api.CurateRequest;
import io.aeyer.plowshare.server.api.StartedJob;
import java.util.Map;
import java.util.Objects;

/**
 * {@code agent.curate} — start a curator pass over one project. The frame equivalent of {@code POST
 * /v1/curate}.
 *
 * <h2>{@link Code#ACCEPTED} and never {@link Code#OK}</h2>
 *
 * <p>A pass is one run per candidate against one shared allowance, so what comes back is a handle
 * to poll at {@code job.status} exactly as {@code agent.run}'s is. The pass is submitted through
 * the same {@code JobStore} as any other run and is cancelled through {@code job.cancel}.
 *
 * <h2>The allowance a caller names none of is not decided here</h2>
 *
 * <p>{@link Passes#start} reads the project before the allowance — so a body naming neither is told
 * the thing it has to add rather than corrected about a number it never sent — and takes the
 * configured curator budget when the caller named none. <b>That default is the most dangerous thing
 * this handler could have re-derived</b>: it is not in the request, so nothing a client could
 * observe would show a second surface reaching for a number of its own. There is no global pass,
 * and that refusal is that method's too.
 */
public final class AgentCurateHandler implements FrameHandler {

  private final Passes passes;

  /**
   * @param passes the same service the controller is injected with, which owns the ordering and the
   *     configured default
   */
  public AgentCurateHandler(Passes passes) {
    this.passes = Objects.requireNonNull(passes, "passes");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    CurateRequest asked = Payloads.as(payload, CurateRequest.class, FrameTypes.AGENT_CURATE);
    Passes.Started started = passes.start(asked.project(), asked.maxModelCalls());
    return new Outcome(Code.ACCEPTED, null, new StartedJob(started.id(), started.agent()));
  }
}
