package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.ProposalView;
import io.aeyer.plowshare.server.archive.PromotionQueue;
import io.aeyer.plowshare.server.requests.RequestedHome;
import java.util.Map;
import java.util.Objects;

/**
 * {@code proposal.list} — what is waiting on a promotion decision in one tier. The frame equivalent
 * of {@code GET /v1/proposals}.
 *
 * <h2>One tier at a time, which is the read and not a filter on it</h2>
 *
 * <p>A person settling one project's queue must not be handed another project's, so the tier is the
 * question rather than a narrowing of it. It comes off this frame's own payload — {@link Asking}
 * carries a session and never a project — through {@link RequestedHome}, which is where "null means
 * global, and blank is refused rather than folded into it" lives for both surfaces.
 *
 * <p>Settled rows are not listed, which is {@link PromotionQueue#waiting}'s decision and not this
 * handler's: a rejection is remembered so the curator stops asking, not so a person keeps seeing
 * it.
 */
public final class ProposalListHandler implements FrameHandler {

  private final PromotionQueue queue;

  /**
   * @param queue the same queue the controller is injected with, which decides what waiting means
   */
  public ProposalListHandler(PromotionQueue queue) {
    this.queue = Objects.requireNonNull(queue, "queue");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    Tiered asked = Payloads.as(payload, Tiered.class, FrameTypes.PROPOSAL_LIST);
    return Outcome.ok(
        queue.waiting(RequestedHome.in(asked.project())).stream().map(ProposalView::of).toList());
  }
}
