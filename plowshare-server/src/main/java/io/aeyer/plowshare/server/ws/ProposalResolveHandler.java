package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.ResolveProposalRequest;
import io.aeyer.plowshare.server.api.ResolvedProposal;
import io.aeyer.plowshare.server.archive.PromotionQueue;
import io.aeyer.plowshare.server.requests.RequestedResolution;
import java.util.Map;
import java.util.Objects;

/**
 * {@code proposal.resolve} — settle one proposal, once. The frame equivalent of
 * {@code POST /v1/proposals/&#123;id&#125;/resolve}.
 *
 * <h2>The two refusals are {@code requests}' and the branch is the endpoint's</h2>
 *
 * <p>A settlement that names no {@code accept} and one that names no {@code by}
 * are both refused by {@link RequestedResolution}, in the endpoint's own words,
 * in the endpoint's own order — which is why this handler restates neither. A
 * settled proposal is never re-opened by settling it again, so a default for
 * either field would be permanent.
 *
 * <p><b>The accept/reject branch is copied rather than moved, deliberately.</b>
 * It chooses between two service methods and two shapes of one answer, so it
 * cannot live in a view factory; and it did not go into {@link PromotionQueue}
 * because that class is mocked by both surfaces' tests, which would have put
 * the branch out of reach of the comparison that checks it. What it is safe to
 * copy on is that the branch is <em>named in the request</em>: a handler that
 * took the wrong arm answers a visibly different body, and {@code
 * ProposalFramesTest} drives both. The assembly of each answer is {@link
 * ResolvedProposal}'s own, so the two surfaces cannot disagree about whether
 * "nothing was promoted" is an empty list or a missing one.
 *
 * <p><b>The id is a payload field named {@code proposal}</b>, per {@link
 * Payloads}' convention. A frame that names none is a request only this surface
 * can receive — a URL naming no proposal is a different URL — so the refusal
 * for it is this surface's own.
 */
public final class ProposalResolveHandler implements FrameHandler {

    private final PromotionQueue queue;

    /**
     * @param queue the same queue the controller is injected with, which owns
     *     the claim-before-promotion ordering neither surface may bypass
     */
    public ProposalResolveHandler(PromotionQueue queue) {
        this.queue = Objects.requireNonNull(queue, "queue");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        String proposal = Payloads.required(payload, "proposal", FrameTypes.PROPOSAL_RESOLVE,
                "the id proposal.list answers with. Nothing was settled.");
        ResolveProposalRequest request =
                Payloads.as(payload, ResolveProposalRequest.class, FrameTypes.PROPOSAL_RESOLVE);

        boolean accept = RequestedResolution.accepted(request.accept());
        String by = RequestedResolution.by(request.by());

        if (!accept) {
            return Outcome.ok(ResolvedProposal.rejecting(
                    queue.reject(proposal, request.reason(), by)));
        }
        return Outcome.ok(ResolvedProposal.approving(queue.approve(proposal, by)));
    }
}
