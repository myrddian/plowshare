package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.Limits;
import io.aeyer.plowshare.server.api.AdjustLimitsRequest;
import io.aeyer.plowshare.server.api.JobView;
import java.util.Map;
import java.util.Objects;

/**
 * {@code job.limits} — move a running job's ceilings without restarting it. The frame equivalent of
 * {@code POST /v1/jobs/&#123;id&#125;/limits}.
 *
 * <h2>The ordering is {@link Limits#move}'s, and it is the half no status shows</h2>
 *
 * <p>The budget is applied before the turn cap, deliberately: a body that named both and had the
 * budget refused must not leave the cap applied without saying so, and the budget is the half with
 * a durable consequence. <b>A body whose budget is refused answers the same refusal whichever order
 * the two were tried in</b>, so no comparison of statuses or sentences could see a handler that
 * reordered them — only the cap's value afterwards can, which is what {@code AgentFramesTest}
 * asserts across both surfaces.
 *
 * <h2>What it refuses, none of it restated here</h2>
 *
 * <p>A body naming nothing, because a 200 having changed nothing reads as a limit that moved; a
 * budget below what has already been spent, because a conversation's row may not record spending
 * above its total — the verb for stopping a run is {@code job.cancel}, and the refusal says so; a
 * job that has already finished, which is the opposite of what a cancel does with one, because
 * raising a finished run's ceiling is asking for something that cannot happen; and a job that is
 * not one agent's run, since a curator pass builds its own budget across a whole pass and there is
 * nothing on the handle to move.
 */
public final class JobLimitsHandler implements FrameHandler {

  private final Limits limits;

  /**
   * @param limits the same service the controller is injected with, which owns the order and every
   *     one of the refusals above
   */
  public JobLimitsHandler(Limits limits) {
    this.limits = Objects.requireNonNull(limits, "limits");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String id =
        Payloads.required(
            payload,
            "job",
            FrameTypes.JOB_LIMITS,
            "the id this server named when the run was submitted. Nothing was changed.");
    AdjustLimitsRequest asked =
        Payloads.as(payload, AdjustLimitsRequest.class, FrameTypes.JOB_LIMITS);
    return Outcome.ok(
        JobView.of(
            limits.moveFor(
                id, asking.handle(), asked.maxTurns(), asked.noTurnCap(), asked.maxModelCalls())));
  }
}
