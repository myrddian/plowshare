package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.MemoryProposal;
import io.aeyer.plowshare.protocol.MemoryState;
import io.aeyer.plowshare.protocol.Verdict;
import io.aeyer.plowshare.protocol.VerdictKind;
import io.aeyer.plowshare.protocol.WriteResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Where the queue meets the archive: approving a proposal actually promotes the
 * memory, and refusing one leaves it waiting.
 *
 * <h2>The claim comes before the promotion, and that is the whole design</h2>
 *
 * <p>{@code Archive.promote}'s javadoc says outright that two promotions of one
 * id can both pass its checks and file two global records, and that it is
 * unguarded on purpose because "the spec puts staleness in the proposal queue,
 * where a unique index on pending rows holds it across two transactions and a
 * Java check cannot". That sentence is a contract with this class: the thing
 * that makes a promotion happen once is <b>claiming the proposal first</b>, with
 * the single conditional UPDATE {@code ProposalStoreTest
 * .two_concurrent_resolutions_settle_a_proposal_once} pins across two open
 * transactions. Promote-first would put the archive's unguarded window back on
 * the path, and every ordinary test here would still pass.
 *
 * <p>{@link #approving_a_proposal_somebody_has_already_rejected_promotes_nothing}
 * is the one that can see the ordering, and it is deterministic rather than a
 * race: a rejected proposal's memory is still perfectly promotable, so a
 * promote-first {@code approve} promotes it and only then discovers the row was
 * settled. Run against exactly that build on 2026-08-29, it fails with a global
 * record that nobody approved. The concurrency itself is not re-tested here —
 * it is the store's guarantee and is measured there.
 *
 * <p>The embedding client is stubbed and must stay stubbed, for the reason
 * {@link StubEmbeddingClient} gives: a live model would make every assertion
 * here a measurement of that model on that day.
 */
@Testcontainers
class PromotionQueueTest {

    /** The pgvector image, not stock postgres:16: V1's first line is CREATE
     *  EXTENSION vector. */
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");
    private static final Home PAYMENTS = Home.of("payments");
    private static final String HUMAN = "enzo";

    /**
     * Who filed the fixture's proposals, and deliberately not {@link #HUMAN}.
     *
     * <p>{@code proposed_by} and {@code resolved_by} are adjacent nullable TEXT
     * columns read by one row mapper, so a mapper that read the wrong one would
     * pass every assertion if the two fixtures shared a name.
     */
    private static final String ASKED_BY = "curator";

    private static final int MAX_BODY_CHARS = 1000;
    private static final double HALF_LIFE_DAYS = 30.0;

    /** High enough that nothing here trips demotion by accident; the one test
     *  that is about demotion rebuilds the archive with a threshold of 1. */
    private static final int INDEX_THRESHOLD = 50;

    private static JdbcTemplate jdbc;

    private Instant clock;
    private int minted;
    private MemoryStore memories;
    private ProposalStore proposals;
    private Archive archive;
    private PromotionQueue queue;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void freshQueue() {
        // CASCADE because V2's `proposals` references `memories`: a plain
        // TRUNCATE of one is refused while the other has rows.
        jdbc.execute("TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, memories CASCADE");
        clock = NOW;
        minted = 0;
        memories = new MemoryStore(jdbc);
        proposals = new ProposalStore(
                jdbc, () -> clock, () -> String.format("prp_%06d", ++minted));
        rebuildArchive(INDEX_THRESHOLD);
    }

    /** One counter behind both id factories, so a memory and a proposal never
     *  share an id and every assertion can name what it means. */
    private void rebuildArchive(int threshold) {
        archive = new Archive(memories, new ReasonLog(jdbc), new StubEmbeddingClient(),
                MAX_BODY_CHARS, threshold, HALF_LIFE_DAYS, () -> clock,
                () -> String.format("mem_%06d", ++minted));
        queue = new PromotionQueue(archive, proposals);
    }

    // --- approving ------------------------------------------------------------

    @Test
    void approving_a_proposal_promotes_the_memory_and_settles_the_row() {
        WriteResult local = write("Payments uses mTLS");
        Proposal filed = proposals.propose(
                local.memoryId(), ProposalStore.PROMOTE, "three projects hit the same wall",
                ASKED_BY);
        clock = NOW.plus(Duration.ofDays(2));

        PromotionQueue.Approval approval = queue.approve(filed.id(), HUMAN);

        Memory promoted = approval.promotion().promoted();
        assertEquals(Home.global(), promoted.home());
        assertEquals("Payments uses mTLS", promoted.summary());
        assertEquals(local.memoryId(), promoted.supersedes());
        assertEquals(MemoryState.SUPERSEDED, archive.get(local.memoryId()).state());

        Proposal settled = approval.proposal();
        assertEquals(ProposalState.ACCEPTED, settled.state());
        assertEquals(HUMAN, settled.resolvedBy());
        assertEquals(clock, settled.resolvedAt());
        assertEquals(settled, proposals.get(filed.id()), "and it was written, not just returned");
    }

    /**
     * The promotion is credited to whoever approved it, and justified by why the
     * curator proposed it.
     *
     * <p>Two different facts about one record and the archive keeps both: {@code
     * formed.by} answers "who decided this belongs everywhere", and the
     * provenance prose answers "on what grounds". Crediting the curator would
     * record that a nightly pass promoted it on nobody's authority; dropping the
     * curator's reason would leave a global memory every project reads with no
     * account of why it is global.
     */
    @Test
    void the_promotion_is_credited_to_the_approver_and_justified_by_the_proposal() {
        WriteResult local = write("Payments uses mTLS");
        Proposal filed = proposals.propose(
                local.memoryId(), ProposalStore.PROMOTE, "three projects hit the same wall",
                ASKED_BY);

        Memory promoted = queue.approve(filed.id(), HUMAN).promotion().promoted();

        assertEquals(HUMAN, promoted.formed().by());
        assertTrue(promoted.formed().where().contains("three projects hit the same wall"),
                promoted.formed().where());
        assertTrue(promoted.formed().where().contains(local.memoryId()),
                promoted.formed().where());
    }

    /**
     * The settled row says what the archive actually did, not merely that
     * somebody said yes.
     *
     * <p>A queue whose accepted rows record only the decision leaves the
     * promoted record unreachable from the question that produced it: the
     * proposal names the project memory, which is now a tombstone, and nothing
     * anywhere names the global record it became.
     */
    @Test
    void the_settled_proposal_records_the_record_the_promotion_wrote() {
        WriteResult local = write("Payments uses mTLS");
        Proposal filed = proposals.propose(
                local.memoryId(), ProposalStore.PROMOTE, "it holds", ASKED_BY);

        PromotionQueue.Approval approval = queue.approve(filed.id(), HUMAN);

        String account = proposals.get(filed.id()).resolution();
        assertTrue(account.contains(approval.promotion().promoted().id()), account);
    }

    /**
     * An approval reports what making room for the promotion cost the global
     * index — and writes it into the row.
     *
     * <p>This is what {@code Archive.Promotion} exists for. Global is the tier
     * every project reads, so a promotion into a full index demotes a memory all
     * of them were relying on; before the channel existed the only trace was the
     * row, and the human who approved it was told nothing at all.
     *
     * <p>The resident is read twice so that it outscores the arrival, which
     * means it is demoted only because the arrival is exempt from its own pass.
     * That keeps this a test of the reporting rather than of the arithmetic.
     */
    @Test
    void an_approval_says_what_the_promotion_cost_the_global_index() {
        WriteResult resident = archive.applyVerdict(
                proposal("The retry budget is 4"), isNew(), Home.global());
        archive.read(List.of(resident.memoryId()));
        archive.read(List.of(resident.memoryId()));
        WriteResult local = write("Payments uses mTLS");
        Proposal filed = proposals.propose(
                local.memoryId(), ProposalStore.PROMOTE, "it holds", ASKED_BY);
        rebuildArchive(1);

        PromotionQueue.Approval approval = queue.approve(filed.id(), HUMAN);

        assertEquals(List.of(resident.memoryId()), approval.promotion().demoted());
        assertEquals(MemoryState.COLD, archive.get(resident.memoryId()).state(),
                "sanity: the id reported as demoted really is cold");
        String account = proposals.get(filed.id()).resolution();
        assertTrue(account.contains(resident.memoryId()),
                "the human who approved this was not told it cost a global memory: " + account);
        // And told what it did not cost. The ids alone read as a deletion, and
        // this row is what somebody reads a year later with no javadoc to hand.
        assertTrue(account.contains("cold, not deleted"), account);
    }

    // --- what is refused, and what it leaves behind ----------------------------

    /**
     * A memory can be superseded or invalidated between the proposal and the
     * answer, and the spec's first queue rule is that such a proposal is
     * <b>refused, not applied</b>: never promote a retired fact, and say which.
     *
     * <p>The proposal is left waiting rather than settled. Nobody has decided
     * anything about it — the memory moved underneath it — so recording an
     * answer would put a decision in the archive that no human made.
     */
    @ParameterizedTest
    @ValueSource(strings = {"superseded", "invalidated"})
    void approving_a_proposal_whose_memory_changed_is_refused_and_leaves_it_waiting(String state) {
        WriteResult local = write("Payments uses mTLS");
        Proposal filed = proposals.propose(
                local.memoryId(), ProposalStore.PROMOTE, "it holds", ASKED_BY);
        retire(local.memoryId(), state);

        ArchiveRefusedException refused = assertThrows(
                ArchiveRefusedException.class, () -> queue.approve(filed.id(), HUMAN));

        // The state, not the tier and not a bare "cannot be promoted": a human
        // has to be able to tell "this was answered elsewhere" from "the queue
        // is broken".
        assertTrue(refused.getMessage().contains(state), refused.getMessage());
        assertTrue(refused.getMessage().contains(local.memoryId()), refused.getMessage());

        Proposal after = proposals.get(filed.id());
        assertEquals(ProposalState.PENDING, after.state(),
                "the proposal was settled for a promotion that never happened");
        assertNull(after.resolvedBy());
        assertNull(after.resolvedAt());
        assertEquals(0, globalRecords(), "and nothing reached the global tier");
    }

    /**
     * A promotion the archive refuses for any other reason puts the claim back
     * too.
     *
     * <p>The claim is taken before the promotion is attempted — see the class
     * note — so every refusal out of {@code Archive.promote} arrives with the
     * row already marked accepted. Leaving it there would record an approval for
     * a promotion that did not happen, which is worse than the missing one: a
     * human reading the queue would see the question answered.
     *
     * <p>A proposal about a memory that is <em>already global</em> is the
     * deterministic way to reach that path, and the reason it is worth a test
     * rather than an admission: it needs no interleaving and no seam. The same
     * recovery is what covers the case no test here can drive — a memory retired
     * in the window between the claim and the promotion.
     */
    @Test
    void a_promotion_the_archive_refuses_puts_the_claim_back() {
        WriteResult already = archive.applyVerdict(
                proposal("The retry budget is 4"), isNew(), Home.global());
        Proposal filed = proposals.propose(
                already.memoryId(), ProposalStore.PROMOTE, "the curator should not have", ASKED_BY);

        ArchiveRefusedException refused = assertThrows(
                ArchiveRefusedException.class, () -> queue.approve(filed.id(), HUMAN));

        assertTrue(refused.getMessage().contains("global"), refused.getMessage());
        Proposal after = proposals.get(filed.id());
        assertEquals(ProposalState.PENDING, after.state(),
                "the claim was kept for a promotion that was refused");
        assertNull(after.resolvedBy());
        assertNull(after.resolution());
        assertEquals(1, globalRecords(), "and no second record was filed for the one claim");
    }

    /**
     * <b>The test that can see the ordering.</b> A rejected proposal's memory is
     * still perfectly promotable — nothing about the rejection touched it — so
     * an {@code approve} that promoted before claiming would promote it and only
     * then discover the row was settled. Against that build this fails with a
     * global record nobody approved; every other test in this file passes
     * against it.
     */
    @Test
    void approving_a_proposal_somebody_has_already_rejected_promotes_nothing() {
        WriteResult local = write("Payments uses mTLS");
        Proposal filed = proposals.propose(
                local.memoryId(), ProposalStore.PROMOTE, "it holds", ASKED_BY);
        queue.reject(filed.id(), "too niche for global", HUMAN);

        ArchiveRefusedException refused = assertThrows(
                ArchiveRefusedException.class, () -> queue.approve(filed.id(), "someone"));

        assertTrue(refused.getMessage().contains(filed.id()), refused.getMessage());
        assertEquals(0, globalRecords(),
                "a memory was promoted for a proposal somebody had already rejected");
        assertEquals(MemoryState.ACTIVE, archive.get(local.memoryId()).state());
        assertEquals(ProposalState.REJECTED, proposals.get(filed.id()).state());
        assertEquals("too niche for global", proposals.get(filed.id()).resolution(),
                "and the rejection's own account was not overwritten");
    }

    @Test
    void approving_the_same_proposal_twice_promotes_once() {
        WriteResult local = write("Payments uses mTLS");
        Proposal filed = proposals.propose(
                local.memoryId(), ProposalStore.PROMOTE, "it holds", ASKED_BY);
        queue.approve(filed.id(), HUMAN);

        assertThrows(ArchiveRefusedException.class, () -> queue.approve(filed.id(), HUMAN));

        assertEquals(1, globalRecords());
    }

    @Test
    void approving_an_unknown_proposal_names_the_id() {
        ArchiveException refused =
                assertThrows(ArchiveException.class, () -> queue.approve("prp_invented", HUMAN));

        String said = refused.getMessage();
        assertTrue(said.contains("prp_invented"), said);
        assertTrue(said.contains("no proposal with id"), said);
        // The exact class: a proposal id nobody filed is an absence, and every
        // other approve refusal in this file is now the narrower type. Without
        // this line assertThrows takes either and the file says nothing about
        // which of the two an unknown id is.
        assertEquals(ArchiveException.class, refused.getClass(),
                "an unknown proposal id is absent, never refused");
        assertEquals(0, globalRecords(), "and the refusal came before anything was promoted");
    }

    /**
     * An approval with nobody's name on it promotes nothing.
     *
     * <p>The check is {@code ProposalStore.resolve}'s and it runs before the
     * claim, which is what makes this assertion about the global tier true: an
     * approver's name is refused before anything has happened, not cleaned up
     * afterwards.
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void approving_without_a_named_approver_promotes_nothing(String by) {
        WriteResult local = write("Payments uses mTLS");
        Proposal filed = proposals.propose(
                local.memoryId(), ProposalStore.PROMOTE, "it holds", ASKED_BY);

        assertThrows(ValidationException.class, () -> queue.approve(filed.id(), by));

        assertEquals(0, globalRecords());
        assertEquals(ProposalState.PENDING, proposals.get(filed.id()).state());
    }

    // --- rejecting ------------------------------------------------------------

    @Test
    void rejecting_a_proposal_settles_it_and_promotes_nothing() {
        WriteResult local = write("Payments uses mTLS");
        Proposal filed = proposals.propose(
                local.memoryId(), ProposalStore.PROMOTE, "it holds", ASKED_BY);

        Proposal settled = queue.reject(filed.id(), "too niche for global", HUMAN);

        assertEquals(ProposalState.REJECTED, settled.state());
        assertEquals("too niche for global", settled.resolution());
        assertEquals(HUMAN, settled.resolvedBy());
        assertEquals(0, globalRecords());
        assertEquals(MemoryState.ACTIVE, archive.get(local.memoryId()).state(),
                "a rejection touches the memory not at all");
    }

    // --- when the compensation itself fails ------------------------------------

    /**
     * A promotion that is refused, whose claim then cannot be given back, says
     * both things.
     *
     * <p>Claiming a proposal frees its {@code (memory_id, action)} waiting
     * place, so a second proposal filed in that gap makes the release
     * impossible. Here the gap is opened by hand — the same memory is proposed
     * twice around a claim — which is what makes this deterministic rather than
     * a race.
     *
     * <p>The archive's refusal is what travels, because that is what the caller
     * asked about; the release failure is suppressed onto it rather than
     * replacing it or being dropped. Dropped, the row would sit {@code accepted}
     * for a promotion that never happened with nothing anywhere saying so.
     */
    @Test
    void a_refused_promotion_whose_claim_is_stuck_reports_both() {
        WriteResult already = archive.applyVerdict(
                proposal("The retry budget is 4"), isNew(), Home.global());
        Proposal filed = proposals.propose(
                already.memoryId(), ProposalStore.PROMOTE, "the curator should not have", ASKED_BY);
        // The queue will claim `filed` and then be refused by the archive. This
        // store is handed the same id and takes the waiting place back the
        // instant the claim frees it.
        ProposalStore racing = new ProposalStore(jdbc, () -> clock,
                () -> String.format("prp_%06d", ++minted)) {
            @Override
            public Proposal resolve(String id, boolean accept, String resolution, String by) {
                Proposal claimed = super.resolve(id, accept, resolution, by);
                super.propose(already.memoryId(), ProposalStore.PROMOTE, "in the gap", ASKED_BY);
                return claimed;
            }
        };

        ArchiveRefusedException refused = assertThrows(ArchiveRefusedException.class,
                () -> new PromotionQueue(archive, racing).approve(filed.id(), HUMAN));

        assertTrue(refused.getMessage().contains("global"),
                "the archive's own refusal is what the caller asked about: "
                        + refused.getMessage());
        assertEquals(1, refused.getSuppressed().length,
                "the claim could not be released and nothing said so");
        assertTrue(refused.getSuppressed()[0].getMessage().contains("waiting place"),
                refused.getSuppressed()[0].getMessage());
        assertEquals(ProposalState.ACCEPTED, proposals.get(filed.id()).state(),
                "and the row really is left settled, which is what the suppressed one is for");
    }

    /**
     * A promotion that happened and could not be recorded says so, and names the
     * record.
     *
     * <p>The account is written after the promotion, so a failure there is a
     * failure about work that already succeeded. Re-raising the store's error
     * unqualified would report a promotion that did not occur — and the promoted
     * record would be reachable from nothing, because the proposal names the
     * project memory, which is now a tombstone.
     */
    @Test
    void a_promotion_whose_account_cannot_be_written_still_says_it_happened() {
        WriteResult local = write("Payments uses mTLS");
        Proposal filed = proposals.propose(
                local.memoryId(), ProposalStore.PROMOTE, "it holds", ASKED_BY);
        ProposalStore mute = new ProposalStore(jdbc, () -> clock,
                () -> String.format("prp_%06d", ++minted)) {
            @Override
            Proposal note(String id, String account) {
                throw new ArchiveException("the account went nowhere");
            }
        };

        ArchiveRefusedException reported = assertThrows(ArchiveRefusedException.class,
                () -> new PromotionQueue(archive, mute).approve(filed.id(), HUMAN));

        String said = reported.getMessage();
        assertTrue(said.contains("the promotion stands"), said);
        assertTrue(said.contains(local.memoryId()), said);
        assertTrue(said.contains("the account went nowhere"), said);
        // The id of the record that was written has to be in there, or a
        // promotion nothing references is unfindable by hand.
        String promotedId = jdbc.queryForObject(
                "SELECT id FROM memories WHERE project_id IS NULL", String.class);
        assertTrue(said.contains(promotedId), said);
        assertEquals(MemoryState.SUPERSEDED, archive.get(local.memoryId()).state(),
                "sanity: the promotion really did happen");
    }

    /**
     * <b>A promotion that committed is never given back, however the embedding
     * that follows it fails.</b>
     *
     * <p>{@code Archive.promote} commits, and only then embeds. Everything
     * {@code approve}'s {@code catch (RuntimeException)} does — releasing the
     * claim, putting the question back on a human's screen — is right for a
     * promotion that did not happen and is <em>corruption</em> for one that did.
     * {@code Archive.embed} used to catch {@code EmbeddingException} and nothing
     * else, so a {@code DataAccessException} out of {@code saveEmbedding}'s bare
     * {@code jdbc.update} escaped after the commit and reached exactly that
     * catch.
     *
     * <p>What that produced, measured against the build before the fix: a global
     * record on disk, the origin a tombstone, the proposal back in {@code
     * pending}, and every future approval of it refused forever — because
     * {@code promote} now refuses the superseded origin. The caller got a raw
     * {@code DataAccessResourceFailureException} with nothing anywhere saying a
     * promotion had occurred. The class note calls a row that lies "worse than
     * the missing one"; this was that, in the direction the note did not
     * consider.
     *
     * <p>The fix is in {@code Archive.embed}, not in the catch, and it has to
     * be: {@code approve} cannot tell an exception raised before the commit from
     * one raised after it, and the set of post-commit types is exactly the open
     * set of "anything unexpected". Narrowing the catch cannot be made sound;
     * making the post-commit call unable to throw can, and is what {@code
     * embed}'s own contract already promised.
     */
    @Test
    void a_promotion_that_committed_is_not_given_back_when_the_embedding_write_fails() {
        WriteResult local = write("Payments uses mTLS");
        Proposal filed = proposals.propose(
                local.memoryId(), ProposalStore.PROMOTE, "it holds", ASKED_BY);
        // The JDBC half of embed(), not the model half: saveEmbedding is a bare
        // jdbc.update, and a dead Postgres there is a DataAccessException that
        // no EmbeddingException catch was ever going to see.
        Archive brittle = new Archive(
                new MemoryStore(jdbc) {
                    @Override
                    public void saveEmbedding(String id, float[] embedding) {
                        throw new DataAccessResourceFailureException("the database went away");
                    }
                },
                new ReasonLog(jdbc), new StubEmbeddingClient(), MAX_BODY_CHARS, INDEX_THRESHOLD,
                HALF_LIFE_DAYS, () -> clock, () -> String.format("mem_%06d", ++minted));

        PromotionQueue.Approval approval =
                new PromotionQueue(brittle, proposals).approve(filed.id(), HUMAN);

        assertEquals(ProposalState.ACCEPTED, proposals.get(filed.id()).state(),
                "the claim was released for a promotion that had already committed: the record is"
                        + " global, the origin is a tombstone, and the question is back on a"
                        + " human's screen where it can never be approved again");
        assertEquals(1, globalRecords());
        assertEquals(MemoryState.SUPERSEDED, archive.get(local.memoryId()).state());
        assertEquals(approval.promotion().promoted().id(),
                proposals.get(filed.id()).resolution().replaceAll(".*promoted as (\\S+).*", "$1"),
                "and the row names the record that was written");
        // Unembedded rather than absent, which is the documented cost and is
        // what reembed(Home) exists to repair.
        assertNull(jdbc.queryForObject("SELECT embedding FROM memories WHERE project_id IS NULL",
                String.class));
    }

    // --- what is waiting ------------------------------------------------------

    /**
     * The queue a human is shown is one tier's, and it holds only what is still
     * unanswered.
     *
     * <p>Both halves matter and neither is free: showing another project's
     * proposals asks somebody to rule on a tier they do not work in, and showing
     * settled ones asks them to answer a question twice.
     */
    @Test
    void what_is_waiting_is_this_tier_and_only_what_is_unanswered() {
        WriteResult mine = write("Payments uses mTLS");
        WriteResult settled = write("Payments logs in JSON");
        WriteResult theirs = archive.applyVerdict(
                proposal("The retry budget is 4"), isNew(), Home.of("ledger"));
        Proposal open = proposals.propose(
                mine.memoryId(), ProposalStore.PROMOTE, "it holds", ASKED_BY);
        Proposal answered =
                proposals.propose(settled.memoryId(), ProposalStore.PROMOTE, "and this", ASKED_BY);
        proposals.propose(theirs.memoryId(), ProposalStore.PROMOTE, "not ours", ASKED_BY);
        queue.reject(answered.id(), "too niche", HUMAN);

        assertEquals(List.of(open.id()),
                queue.waiting(PAYMENTS).stream().map(Proposal::id).toList());
    }

    // --- fixtures -------------------------------------------------------------

    // --- putting a settler's own rulings back in front of a person -------------

    /**
     * A ruling the curator answered by itself goes back to waiting, and stops
     * counting as ruled on.
     *
     * <p><b>That is the whole of what the escape hatch does to convergence.</b>
     * {@code Curator.untouched} subtracts {@code ruledOn} and {@code pending}
     * before its loop, so a re-opened row is judged again by no pass — it is a
     * person's to answer. The half about {@code pending} is {@code
     * CuratorTest.a_memory_with_a_proposal_already_waiting_is_never_judged}'s
     * and is not restated here.
     */
    @Test
    void a_settlers_own_ruling_goes_back_to_waiting_and_stops_being_ruled_on() {
        WriteResult local = write("Payments uses mTLS");
        Proposal filed = proposals.propose(
                local.memoryId(), ProposalStore.PROMOTE, "it holds", ASKED_BY);
        queue.reject(filed.id(), "kept", "curator");
        assertEquals(1, proposals.ruledOn(PAYMENTS).size(), "fixture");

        PromotionQueue.Reconsidered done = queue.reconsider(PAYMENTS, "curator", "kept");

        assertEquals(List.of(filed.id()), done.reopened());
        assertEquals(List.of(), done.refused());
        assertEquals(ProposalState.PENDING, proposals.get(filed.id()).state());
        assertNull(proposals.get(filed.id()).resolvedBy(), "and it carries no answer any more");
        assertTrue(proposals.ruledOn(PAYMENTS).isEmpty(),
                "a re-opened ruling is not a ruling; nobody has answered it now");
        assertEquals(List.of(filed.id()),
                proposals.pending(PAYMENTS).stream().map(Proposal::id).toList());
    }

    /**
     * A ruling whose waiting place has been taken since it was settled is named,
     * and the rest still go back.
     *
     * <p>Settling a proposal frees its {@code (memory_id, action)} waiting place,
     * so by the time a re-open is asked for that place may be occupied and
     * {@code proposals_one_pending} refuses it — {@code ProposalStore.release}'s
     * own documented outcome, reached here with no concurrency at all. An
     * operator who asked for two and got one has to see which and why, so the
     * loop carries on and names it.
     *
     * <p><b>This is also the catch that is meant to meet a refusal.</b> {@code
     * ArchiveRefusedException} counts four {@code catch (ArchiveException)}
     * clauses and says three of them cannot meet one; this is the fourth, and
     * the refusals are what the {@code refused} list is made of.
     */
    @Test
    void a_ruling_whose_waiting_place_was_taken_is_named_and_the_others_still_go_back() {
        WriteResult blocked = write("Payments uses mTLS");
        Proposal kept = proposals.propose(
                blocked.memoryId(), ProposalStore.PROMOTE, "it holds", ASKED_BY);
        queue.reject(kept.id(), "kept", "curator");
        Proposal occupant = proposals.propose(
                blocked.memoryId(), ProposalStore.PROMOTE, "asked again", ASKED_BY);

        WriteResult other = write("The retry budget is 4");
        Proposal free = proposals.propose(
                other.memoryId(), ProposalStore.PROMOTE, "it holds", ASKED_BY);
        queue.reject(free.id(), "kept", "curator");

        PromotionQueue.Reconsidered done = queue.reconsider(PAYMENTS, "curator", "kept");

        assertEquals(List.of(free.id()), done.reopened());
        assertEquals(1, done.refused().size(), done.refused().toString());
        assertTrue(done.refused().get(0).contains(kept.id()), done.refused().toString());
        assertTrue(done.refused().get(0).contains("waiting place"), done.refused().toString());
        assertEquals(ProposalState.REJECTED, proposals.get(kept.id()).state(),
                "the refusal leaves the row where it found it");
        assertEquals(ProposalState.PENDING, proposals.get(occupant.id()).state());
    }

    private WriteResult write(String summary) {
        return archive.applyVerdict(proposal(summary), isNew(), PAYMENTS);
    }

    private static MemoryProposal proposal(String summary) {
        return new MemoryProposal(summary, "payments auth work", "The body.", "claude-code",
                "proj/payments");
    }

    private static Verdict isNew() {
        return new Verdict(VerdictKind.NEW, null, "novel");
    }

    /** Retire a memory behind the archive's back, which is the point: this
     *  stands in for whatever happened between the proposal and the answer, and
     *  going through {@code invalidate} could only produce one of the two
     *  states. */
    private void retire(String id, String state) {
        jdbc.update("UPDATE memories SET state = ? WHERE id = ?", state, id);
    }

    private int globalRecords() {
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM memories WHERE project_id IS NULL", Integer.class);
        return rows == null ? 0 : rows;
    }
}
