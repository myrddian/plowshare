package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.archive.PromotionQueue;
import io.aeyer.plowshare.server.archive.Proposal;
import java.util.List;

/**
 * What settling a proposal did.
 *
 * <p>{@code promotedId} and {@code demoted} are the acceptance half and are
 * empty on a rejection. <b>{@code demoted} is surfaced rather than silent</b>,
 * for the reason {@code WriteResult.demoted} is: an approval adds to the global
 * index, which is the tier every project reads, and a promotion that quietly
 * pushed other memories out of it would make that index shrink for a reason no
 * caller could see. Task 6 recorded the gap when {@code Archive.promote}
 * returned a bare {@code Memory}; Task 7 gave it a channel and this is where it
 * reaches a person.
 *
 * @param demoted the global memories that fell out of the index to make room.
 *     Cold, not deleted: still readable by id and still on the search path.
 */
public record ResolvedProposal(
        ProposalView proposal, String promotedId, List<String> demoted) {

    /**
     * What a rejection answers with: the settled row, and the acceptance half
     * empty.
     *
     * <p>A factory rather than two {@code new} calls at each surface, because
     * {@code POST /v1/proposals/&#123;id&#125;/resolve} and the {@code
     * proposal.resolve} frame both render this branch and "empty" has to mean
     * the same thing in both — a null {@code promotedId} and an <em>empty
     * list</em> rather than a null one, which is a distinction a caller reading
     * JSON can see and a distinction two hand-written call sites can drift on.
     *
     * @param rejected the proposal as the queue left it
     */
    public static ResolvedProposal rejecting(Proposal rejected) {
        return new ResolvedProposal(ProposalView.of(rejected), null, List.of());
    }

    /**
     * What an approval answers with: the settled row, the global record it
     * became, and what fell out of the index to make room.
     *
     * @param approval what {@link PromotionQueue#approve} did
     */
    public static ResolvedProposal approving(PromotionQueue.Approval approval) {
        return new ResolvedProposal(
                ProposalView.of(approval.proposal()),
                approval.promotion().promoted().id(),
                approval.promotion().demoted());
    }
}
