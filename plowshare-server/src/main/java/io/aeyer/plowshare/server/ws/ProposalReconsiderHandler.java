package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.curator.Curator;
import io.aeyer.plowshare.server.archive.PromotionQueue;
import io.aeyer.plowshare.server.requests.RequestedHome;
import java.util.Map;
import java.util.Objects;

/**
 * {@code proposal.reconsider} — put every ruling the curator made by itself back in front of a
 * person. The frame equivalent of {@code POST /v1/proposals/reconsider}.
 *
 * <h2>The two constants are the whole of the operation, and they are not in the payload</h2>
 *
 * <p>{@link PromotionQueue#reconsider} takes the predicate as arguments and never learns what a
 * curator is — {@code Curator.BY} and {@code Curator.KEPT} are named by the caller. This surface
 * and the endpoint are the only two places in the system that name them together, and they name the
 * same pair: a handler that widened the predicate would re-open decisions a <em>person</em> already
 * made, and would answer a perfectly ordinary two-list result while doing it. That is the drift
 * {@code ProposalFramesTest} verifies rather than only compares.
 *
 * <p><b>Still not reachable by an agent</b>, which is the endpoint's own rule and survives the new
 * transport: this is maintenance somebody does after changing {@code promotion_judge.md}, not a
 * judgement a run should make in the middle of its own work. Nothing here authenticates — a frame
 * arrives under a session, not an identity — so what keeps that true is the same thing that kept it
 * true on HTTP: no tool and no verb reaches this capability.
 */
public final class ProposalReconsiderHandler implements FrameHandler {

  private final PromotionQueue queue;

  /**
   * @param queue the same queue the controller is injected with
   */
  public ProposalReconsiderHandler(PromotionQueue queue) {
    this.queue = Objects.requireNonNull(queue, "queue");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    Tiered asked = Payloads.as(payload, Tiered.class, FrameTypes.PROPOSAL_RECONSIDER);
    return Outcome.ok(
        queue.reconsider(RequestedHome.in(asked.project()), Curator.BY, Curator.KEPT));
  }
}
