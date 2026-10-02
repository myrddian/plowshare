package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.agents.curator.Curator;
import io.aeyer.plowshare.server.archive.PromotionQueue;
import io.aeyer.plowshare.server.archive.Proposal;
import io.aeyer.plowshare.server.requests.RequestedHome;
import io.aeyer.plowshare.server.requests.RequestedResolution;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The queue a person answers: what is waiting, and settling one.
 *
 * <p>Goes through {@link PromotionQueue} and never through {@code
 * ProposalStore} directly, which is the whole reason that class exists. The
 * ordering it holds is not a convenience: the claim on the proposal is taken
 * <em>before</em> the promotion, because {@code Archive.promote} explicitly
 * does not guard against two concurrent promotions and says the queue's unique
 * index is what does. The two cannot be one transaction — {@code promote} calls
 * the embedding model after closing its own unit of work — so a refused
 * promotion releases the claim instead. {@code resolve}, {@code note} and
 * {@code release} are package-private for exactly this reason, and this
 * controller could not bypass the ordering if it wanted to.
 */
@RestController
public class ProposalController {

    private final PromotionQueue queue;

    public ProposalController(PromotionQueue queue) {
        this.queue = queue;
    }

    /**
     * What is waiting in one tier.
     *
     * <p>One tier at a time, like every other read in this archive: a person
     * settling one project's queue must not be handed another project's.
     * Settled rows are not listed — a rejection is remembered so the curator
     * stops asking, not so a person keeps seeing it.
     */
    @GetMapping("/v1/proposals")
    public ResponseEntity<List<ProposalView>> waiting(
            @RequestParam(required = false) String project) {
        return ResponseEntity.ok(queue.waiting(RequestedHome.in(project)).stream()
                .map(ProposalView::of)
                .toList());
    }

    /**
     * {@code POST /v1/proposals/reconsider?project=} — put every ruling the
     * curator made by itself back in front of a person.
     *
     * <p><b>The escape hatch for a wrong {@code keep}</b>, left possible rather
     * than built by 3a's tasks 9 and 10, which named the predicate — {@code
     * resolved_by = Curator.BY} and {@code resolution = Curator.KEPT} — and left
     * two questions: who may pull it, and what it does to {@code ruledOn}'s
     * convergence.
     *
     * <p><b>Who: a person, here.</b> This is the only place in the system that
     * names both of those constants together; {@link PromotionQueue#reconsider}
     * takes them as arguments and never learns what a curator is. So the
     * operation is reachable from an operator's HTTP surface and from nowhere
     * else — <b>and deliberately not as an MCP tool</b>, on the rule {@code
     * POST /v1/memories/reembed} already follows: this is maintenance somebody
     * does after changing {@code promotion_judge.md}, not a judgement an agent
     * should make in the middle of its own work.
     *
     * <p><b>What it does to convergence: nothing.</b> A re-opened row leaves
     * {@code ruledOn} and enters {@code pending}, and {@code Curator.untouched}
     * subtracts both before its loop — so the memory is not judged again by any
     * pass, and the ≈400 model calls a night the {@code keep} exists to stop
     * stay stopped. What changes is that a person sees the question. That is the
     * right party: {@code ruledOn}'s soundness argument answers "the fact
     * changed" and not "the judge was wrong" or "the policy in {@code
     * promotion_judge.md} changed", and those two are human judgements. A
     * curator re-opening its own keeps would be the non-convergence with a new
     * name, which is why nothing an agent can call reaches this.
     *
     * <p>The trade 3a accepted is unchanged and is what makes an escape hatch
     * worth having rather than a hole: a wrong {@code keep} costs one project a
     * memory it did not share, and a wrong {@code promote} costs every project
     * attention forever. This gives the cheap mistake a way back without giving
     * the expensive one one.
     *
     * <p>Answers what went back and what could not, because a re-open can be
     * refused: the row's waiting place may have been taken since it was settled.
     * A count alone cannot tell a tier with nothing to re-open from one where
     * every row was blocked.
     */
    @PostMapping("/v1/proposals/reconsider")
    public ResponseEntity<PromotionQueue.Reconsidered> reconsider(
            @RequestParam(required = false) String project) {
        return ResponseEntity.ok(
                queue.reconsider(RequestedHome.in(project), Curator.BY, Curator.KEPT));
    }

    /**
     * Settle one, once.
     *
     * <p>Accepting promotes; the memory is copied into global with provenance
     * naming its origin and the project's record is retired with a forward
     * link. Rejecting records the refusal, and the refusal is the point: the
     * curator's triage subtracts what has been ruled on, so a queue that forgot
     * would ask again next week and the week after.
     *
     * <p>A stale proposal is refused rather than applied. Between proposal and
     * resolution the memory may have been superseded or invalidated, and {@code
     * Archive.promote} refuses a retired record naming its state — a promotion
     * of a fact that stopped being true would be the worst thing this queue
     * could do, because global is the tier every project reads.
     */
    @PostMapping("/v1/proposals/{id}/resolve")
    public ResponseEntity<ResolvedProposal> resolve(
            @PathVariable String id, @RequestBody ResolveProposalRequest request) {

        boolean accept = RequestedResolution.accepted(request.accept());
        String by = RequestedResolution.by(request.by());

        if (!accept) {
            Proposal rejected = queue.reject(id, request.reason(), by);
            return ResponseEntity.ok(ResolvedProposal.rejecting(rejected));
        }
        return ResponseEntity.ok(ResolvedProposal.approving(queue.approve(id, by)));
    }
}
