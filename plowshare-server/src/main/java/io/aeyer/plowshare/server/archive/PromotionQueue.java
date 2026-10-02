package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Where the queue meets the archive: a human's answer, carried out.
 *
 * <p>{@link ProposalStore} holds rows and knows nothing about promotion;
 * {@link Archive} performs promotions and knows nothing about the queue. This is
 * the only place that holds both, and everything it does is about the order the
 * two are touched in.
 *
 * <h2>The claim comes before the promotion</h2>
 *
 * <p>{@link Archive#promote} says outright that two promotions of one id can
 * both pass its checks and file two global records, and that this is unguarded
 * on purpose because the spec puts staleness in the queue, "where a unique index
 * on pending rows holds it across two transactions and a Java check cannot".
 * That is a contract with this class. What makes a promotion happen once is
 * {@link ProposalStore#resolve}'s single conditional UPDATE, taken <b>first</b>:
 * exactly one caller can move a proposal out of {@code pending}, and only that
 * caller goes on to promote. Promoting first and settling afterwards puts the
 * archive's unguarded window straight back onto the path, and every ordinary
 * test of this class passes against it — {@code PromotionQueueTest
 * .approving_a_proposal_somebody_has_already_rejected_promotes_nothing} is the
 * one that does not.
 *
 * <h2>Why the two are not wrapped in one transaction</h2>
 *
 * <p>The obvious repair for a claim that is taken and then not used is to make
 * the claim and the promotion atomic. <b>That is the exact bug {@link UnitOfWork}
 * exists to prevent.</b> {@code Archive.promote} closes its own unit of work and
 * <em>then</em> calls the embedding model, on the stated reasoning that the row
 * is committed and the pooled connection is back; an enclosing transaction here
 * would still be open across that call, which is how a stalled model came to pin
 * a database connection for eighty seconds and how an unexpected exception out
 * of the model client came to roll committed writes back. The two steps stay two
 * steps, and the claim is released instead when the promotion is refused.
 *
 * <p>What that leaves is a row that can outlive its own truth, and the window is
 * wider than "a process died mid-method". Three ways, all of them recorded here
 * because an understated window is a claim the code does not support:
 *
 * <ul>
 *   <li><b>The process dies between the claim and the release.</b> The row is
 *       accepted for a promotion that did not happen. This is the only one left
 *       that no code here can answer.
 *   <li><b>The release is refused.</b> Claiming a proposal frees its {@code
 *       (memory_id, action)} waiting place, so by the time the claim is given
 *       back that place may be occupied and {@code proposals_one_pending}
 *       refuses it. {@link ProposalStore#release} names that outcome instead of
 *       leaking a {@code DuplicateKeyException}, and it travels attached to the
 *       refusal that caused it rather than replacing it — the archive's answer
 *       is the one the caller asked for. {@code
 *       ProposalStoreTest.releasing_a_claim_whose_waiting_place_was_taken_is_refused}
 *       reaches it with no concurrency at all.
 *   <li><b>The account cannot be written.</b> By then the promotion has
 *       <em>happened</em>, so failing the call with the store's error would
 *       report a promotion that did not occur. {@link #approve} says both
 *       things and names the record, so the caller can find it.
 * </ul>
 *
 * <p>All three are the price of not holding a transaction across a model call,
 * which is measured and was expensive.
 *
 * <p><b>What the catch below rests on, and what it cost to learn.</b> Releasing
 * the claim is right for a promotion that did not happen and is corruption for
 * one that did, so the wide {@code catch (RuntimeException)} is only sound if
 * nothing can throw out of {@link Archive#promote} after its commit. That was
 * not true: {@code Archive.embed} runs post-commit and caught {@link
 * io.aeyer.plowshare.server.llm.EmbeddingException} alone, so a {@code
 * DataAccessException} out of {@code saveEmbedding} escaped and reached this
 * catch — producing a global record on disk, a tombstoned origin, and the
 * proposal back on a human's screen where every later approval is refused
 * forever, which is precisely the row-that-lies this note calls worse than the
 * missing one. It could not be fixed here, because no caller can tell a
 * pre-commit throw from a post-commit one; {@code embed} swallows now, which is
 * what its own contract already promised. This class depends on that, and
 * {@code
 * PromotionQueueTest.a_promotion_that_committed_is_not_given_back_when_the_embedding_write_fails}
 * is what fails if it stops being true.
 */
public class PromotionQueue {

    private final Archive archive;
    private final ProposalStore proposals;

    public PromotionQueue(Archive archive, ProposalStore proposals) {
        this.archive = Objects.requireNonNull(archive, "archive");
        this.proposals = Objects.requireNonNull(proposals, "proposals");
    }

    /** What is waiting for a human in one tier, longest-waiting first. */
    public List<Proposal> waiting(Home home) {
        return proposals.pending(home);
    }

    /**
     * Say yes: promote the memory this proposal names, and record what happened.
     *
     * <p>Claim, promote, account — in that order, for the reasons in the class
     * note. The claim carries no account of itself because there is nothing to
     * say yet; it is written once the archive has actually done something, so
     * the row never describes a promotion that has not happened.
     *
     * <p>A refusal out of {@link Archive#promote} releases the claim and travels
     * on unchanged. Unchanged is deliberate: that message names the memory's
     * <em>state</em> rather than its tier, which is the distinction {@code
     * PromotionTest
     * .a_retired_global_memory_is_refused_for_being_retired_not_for_being_global}
     * exists to hold down, and re-wording it here would be a second sentence to
     * keep true about the same event. The proposal is left waiting, which is the
     * spec's rule: a stale proposal is refused, not applied, and nobody has
     * decided anything about it.
     *
     * @param proposalId the waiting proposal to accept
     * @param by who approved it. This is who the promotion is credited to. On
     *     the path this class was written for that is the human who decided the
     *     memory belongs everywhere, not the curator that noticed it — the
     *     curator's reason is what justifies it, and travels into the promoted
     *     record's provenance. <b>Task 9 added a second caller and the sentence
     *     above was true of one:</b> {@code Curator} files its own proposal and
     *     then approves it here under {@code Curator.BY}, so that even a
     *     confident promotion goes through the claim {@link Archive#promote}
     *     says it does not make for itself. A row settled under that name is one
     *     no person looked at, which is what the name is for.
     * @throws ValidationException if {@code by} is missing or blank
     * @throws ArchiveException if the proposal is unknown or already settled, or
     *     if the archive refuses the promotion
     */
    public Approval approve(String proposalId, String by) {
        // Read before the claim, because the claim does not report what it
        // settled and this is where the memory id and the curator's reason come
        // from. It also refuses an unknown id here, where the caller can still
        // be told which id, rather than as a row count of zero.
        Proposal waiting = proposals.get(proposalId);
        proposals.resolve(proposalId, true, null, by);

        Archive.Promotion promotion;
        try {
            promotion = archive.promote(waiting.memoryId(), waiting.reason(), by);
        } catch (RuntimeException refused) {
            // The claim bought nothing, so it goes back. Anything else leaves
            // the queue saying a human approved a promotion the archive never
            // performed — and a human reading the queue would see the question
            // as answered and never look at it again.
            try {
                proposals.release(proposalId);
            } catch (RuntimeException stuck) {
                // Suppressed rather than thrown: the caller asked the archive to
                // promote something and the archive's answer is what they need
                // first. Replacing it with "the claim could not be released"
                // would hide why the promotion was refused behind the mess that
                // followed — while dropping it would leave the row accepted for
                // work that never happened, with nothing anywhere saying so.
                refused.addSuppressed(stuck);
            }
            throw refused;
        }
        try {
            return new Approval(proposals.note(proposalId, account(promotion)), promotion);
        } catch (RuntimeException unrecorded) {
            // Deliberately not inside the try above, and deliberately not
            // silent. By this point the promotion has happened and cannot be
            // undone, so re-raising the store's error unqualified would report a
            // failure for work that succeeded — and the promoted record would be
            // reachable from nothing, because the proposal names the project
            // memory, which is now a tombstone. The id goes in the message so it
            // is findable by hand.
            throw new ArchiveRefusedException("memory " + waiting.memoryId() + " was promoted as "
                    + promotion.promoted().id() + " and the promotion stands, but proposal "
                    + proposalId + " could not be given the account of it: "
                    + unrecorded.getMessage());
        }
    }

    /**
     * Say no, and have the archive remember that somebody did.
     *
     * <p>A pass-through, and it is here rather than left to {@link
     * ProposalStore} so that the two answers to one question are one object's
     * pair. Splitting them — approve through this class, reject through the
     * store — is an invitation to call the store for both, which would promote
     * nothing while recording an acceptance.
     *
     * <p><b>The answer is not always a human's</b>, and this said "a human" when
     * it had one caller. {@code Curator} files its own question and rejects it
     * here under {@code Curator.BY} when the judge ruled a claim local — which
     * is what stops the memory being judged again on every later pass. Nothing
     * about this method changes for that caller: a rejection is a decision about
     * where a claim belongs, and it is remembered, which is the whole point.
     *
     * <p>The memory is untouched. A rejection is a decision about where a claim
     * belongs, never about whether it is true; {@code memory_invalidate} is the
     * remedy for the second, and conflating them would file a fact as false
     * because it was judged parochial.
     */
    public Proposal reject(String proposalId, String reason, String by) {
        return proposals.resolve(proposalId, false, reason, by);
    }

    /**
     * Put every ruling one settler made by itself, under one account, back in
     * front of a person.
     *
     * <p>Here rather than on {@link ProposalStore} for the reason this class
     * exists at all. {@code resolve}, {@code note} and {@code release} are
     * package-private so that nothing outside can bypass the queue's ordering —
     * which is an argument for <em>this</em> class owning the operation, not for
     * the store owning it: that class is one statement plus its refusal per
     * method, and this is a loop over a result set that has to carry a partial
     * failure report. Its own "What this class is not" hands orchestration here.
     * {@link ProposalStore#rulingsSettledUnder} answers which rows match; the
     * loop is this method's.
     *
     * <p><b>It names no policy of its own, and that is the design.</b> The
     * settler and the account arrive from the caller, so the only party that can
     * ask for "what the curator decided by itself" is the one that can name
     * {@code Curator.BY} and {@code Curator.KEPT} — the operator surface. An
     * agent cannot: no tool reaches this, and a curator re-opening its own keeps
     * would be the non-convergence the keep was introduced to fix, wearing a new
     * name.
     *
     * <h3>What it does to {@code ruledOn}'s convergence: nothing</h3>
     *
     * <p>A re-opened row leaves {@link ProposalStore#ruledOn} and enters {@link
     * ProposalStore#pending}, and {@code Curator.untouched} subtracts <b>both</b>
     * before its loop — {@code
     * PromotionQueueTest.a_settlers_own_ruling_goes_back_to_waiting_and_stops_being_ruled_on}
     * holds the first half and {@code
     * CuratorTest.a_memory_with_a_proposal_already_waiting_is_never_judged} the
     * second, and neither is a claim this file restates. So the memory is not
     * judged again by any pass — the machine's cost stays at zero, which is the
     * whole reason a {@code keep} files a row at all — and it lands in front of a
     * person instead. That is the right party: {@code ruledOn}'s immutability
     * argument answers "the fact changed" and not "the judge was wrong" or "the
     * policy changed", and both of those are human judgements.
     *
     * <p>Rows go back one at a time through {@link ProposalStore#release}, so
     * each carries its own outcome. <b>A re-open can be refused</b> — the row's
     * {@code (memory_id, action)} waiting place may have been taken since it was
     * settled, and {@code proposals_one_pending} then refuses it — and those are
     * reported rather than dropped, because an operator who asked for eleven and
     * got nine has to be able to see which two and why.
     *
     * @throws ValidationException if either predicate is missing or blank. A
     *     blank one is a caller's mistake and is refused by name at the front,
     *     before a connection is taken. <b>It would not match everything</b>, and
     *     an earlier version of this sentence said it would: both writers store
     *     an account nobody gave through {@code blankToNull}, so "gave no
     *     account" is NULL on the row and {@code p.resolution = ?} matches no
     *     NULL — measured by {@code
     *     a_ruling_settled_under_no_account_at_all_is_matched_by_nothing}. It
     *     would match nothing, which is its own kind of wrong answer: an
     *     operator would read "0 re-opened" as "there were none".
     */
    public Reconsidered reconsider(Home home, String settledBy, String resolution) {
        List<String> matching = proposals.rulingsSettledUnder(home, settledBy, resolution);

        List<String> reopened = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        for (String id : matching) {
            try {
                proposals.release(id);
                reopened.add(id);
            } catch (ArchiveException cannot) {
                // Kept going rather than stopping at the first, matching
                // Archive.reembed: an operator asked for a tier, and one row
                // whose waiting place was taken is not a reason to leave the
                // other ten settled.
                //
                // This is the one catch of ArchiveException in this codebase
                // that is MEANT to meet a refusal: release's three -- a row
                // nobody claimed, a waiting place taken, a claim somebody else
                // gave back -- are what the refused list is made of. See
                // ArchiveRefusedException, which counts the four.
                refused.add(id + " (" + cannot.getMessage() + ")");
            }
        }
        return new Reconsidered(List.copyOf(reopened), List.copyOf(refused));
    }

    /**
     * What a {@link #reconsider} pass did.
     *
     * @param reopened the proposals now waiting for a person again
     * @param refused the ones that could not go back, each with the reason.
     *     Surfaced rather than silent, on {@code Archive.Repair}'s rule: a
     *     caller told only how many succeeded cannot tell a tier with nothing to
     *     re-open from one where every row was blocked.
     */
    public record Reconsidered(List<String> reopened, List<String> refused) { }

    /**
     * What an approval did, in the words the row keeps.
     *
     * <p>The demoted ids are in here because they are the reason {@link
     * Archive.Promotion} exists: global is the tier every project reads, so a
     * promotion into a full index costs one of them a memory, and the human who
     * approved it is the one person who ought to be told. Stored on the row
     * rather than only returned, because the return value is gone the moment the
     * tool answers and the row is what anybody reads a year later.
     *
     * <p><b>Including that demotion is not deletion</b>, which {@code
     * MemoryTools} says to a model for the same ids and this used to leave out.
     * The claim that this is "rendered the way {@code memory_write} renders
     * {@code WriteResult.demoted}" was too generous by exactly that: the two
     * share a comma-joined list of ids and nothing else, and the half they did
     * not share was the reassurance. A human reading "this cost you three
     * memories" a year later, with nowhere to learn they are all still there,
     * has been told something worse than the truth.
     */
    private static String account(Archive.Promotion promotion) {
        String said = "promoted as " + promotion.promoted().id();
        if (promotion.demoted().isEmpty()) {
            return said;
        }
        return said + "; this made room in the global index by demoting "
                + String.join(", ", promotion.demoted())
                + ". They are cold, not deleted: still readable by id and still on the search"
                + " path, and only out of the index.";
    }

    /**
     * An approval: the settled row, and what the archive did.
     *
     * @param proposal the proposal as it now stands — accepted, with the account
     *     below written onto it
     * @param promotion the new global record and what making room for it cost
     */
    public record Approval(Proposal proposal, Archive.Promotion promotion) { }
}
