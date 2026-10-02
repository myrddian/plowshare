package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import io.aeyer.plowshare.server.llm.EmbeddingException;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Recall by meaning — the change the whole port exists for.
 *
 * <p><b>Not a port.</b> Excalibur's equivalent is an agent: a small local model
 * with a turn budget, a tool set and a prompt, and its tests are tests of an
 * agent loop. Measured 2026-08-24 against a purpose-built corpus, the same
 * twenty-four questions scored 2/24 on one model and 14/24 on another, and one
 * truncated run returned the model's own internal deliberation as its answer.
 * None of that is being ported, so none of those tests are. What is asserted
 * here is that the rules the prompt used to ask for are now properties of a
 * query.
 *
 * <p><b>The embedding client is stubbed and must stay stubbed.</b> A live model
 * would make every assertion below a measurement of that model on that day:
 * Excalibur's golden-set eval scored anywhere from 2/4 to 4/4 on identical code,
 * which is a signal that cannot tell anyone whether a change helped. Every
 * vector here is chosen by the test, so what varies between a green run and a
 * red one is the code.
 */
@Testcontainers
class RecallTest {

    /** The pgvector image, not stock postgres:16: {@code <=>} is an operator the
     *  extension brings, and no stand-in has it. */
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static JdbcTemplate jdbc;

    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");

    private static final Home PAYMENTS = Home.of("payments");
    private static final Home GLOBAL = Home.global();

    private static final int MAX_BODY_CHARS = 1000;
    private static final double HALF_LIFE_DAYS = 30.0;

    /** High enough that no test here trips demotion by accident; the one test
     *  that wants a cold record demotes it by hand, where it is visible. */
    private static final int INDEX_THRESHOLD = 50;

    private Instant clock;
    private int minted;
    private MemoryStore store;
    private StubEmbeddingClient embeddings;
    private Archive archive;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void freshArchive() {
        // CASCADE because V2's `proposals` references this table: a plain
        // TRUNCATE is refused outright, and this class holds no proposals of
        // its own to lose.
        jdbc.execute("TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, memories CASCADE");
        clock = NOW;
        minted = 0;
        store = new MemoryStore(jdbc);
        embeddings = new StubEmbeddingClient();
        archive = new Archive(store, new ReasonLog(jdbc), embeddings, MAX_BODY_CHARS,
                INDEX_THRESHOLD, HALF_LIFE_DAYS, () -> clock,
                () -> String.format("mem_%06d", ++minted));
    }

    // --- the rules the prompt used to ask for ---------------------------------

    /**
     * Retired records leave the search path entirely. This is the property the
     * {@code memories/}-versus-{@code retired/} split existed to give, restated
     * for a database.
     *
     * <p>Measured in Excalibur on 2026-08-18: asked a question whose only match
     * was an invalidated memory, the librarian returned that known-false memory
     * 5 runs out of 5. Documenting the state field in the prompt fixed it — but
     * an instruction is a thing a model can skip, and a {@code WHERE} clause is
     * not.
     */
    @Test
    void recall_never_returns_a_superseded_or_invalidated_memory() {
        WriteResult live = write("live fact", PAYMENTS);
        WriteResult gone = write("dead fact", PAYMENTS);
        WriteResult old = write("old fact", PAYMENTS);
        archive.invalidate(gone.memoryId(), "wrong", "enzo");
        archive.applyVerdict(p("newer fact"),
                new Verdict(VerdictKind.SUPERSEDES, old.memoryId(), "changed"), PAYMENTS);

        List<String> ids = recallIds("anything", PAYMENTS, 10);

        assertTrue(ids.contains(live.memoryId()));
        assertFalse(ids.contains(gone.memoryId()));
        assertFalse(ids.contains(old.memoryId()));
    }

    /**
     * A {@code cold} memory is still reachable by search.
     *
     * <p>This is the line between demotion and deletion, and it is the whole
     * meaning of the state: cold is <em>unused</em>, not untrue. Excalibur keeps
     * cold records in {@code memories/} for exactly this — {@code store.py} says
     * "grep is the only way back to it" — and {@link MemoryStore#loadAll}, the
     * search path, includes cold for the same reason.
     *
     * <p>Filtering recall to {@code active} would make an aggressive index
     * threshold unsafe: every promise made about demotion elsewhere in this
     * codebase ("a wrong eviction costs a slower recall, never a lost memory")
     * depends on this test.
     */
    @Test
    void recall_still_returns_a_demoted_memory() {
        WriteResult demoted = write("a fact nobody has asked about lately", PAYMENTS);
        store.save(Lifecycle.demote(archive.get(demoted.memoryId())));

        assertEquals(MemoryState.COLD, archive.get(demoted.memoryId()).state());
        assertTrue(recallIds("anything", PAYMENTS, 10).contains(demoted.memoryId()));
        // And still absent from the index, or demotion would have done nothing.
        assertFalse(archive.index(PAYMENTS).stream().map(TocEntry::id).toList()
                .contains(demoted.memoryId()));
    }

    /**
     * Project shadows global at recall, which is where the rule is observable to
     * a caller at all. Both records stay active; only the order changes.
     */
    @Test
    void recall_prefers_the_project_memory_over_the_global_one() {
        WriteResult global = write("The retry budget is 4", GLOBAL);
        WriteResult project = write("The retry budget is 3", PAYMENTS);

        List<String> ids = recallIds("retry budget", PAYMENTS, 10);

        assertEquals(project.memoryId(), ids.get(0));
        assertEquals(MemoryState.ACTIVE, archive.get(global.memoryId()).state());
    }

    /**
     * Shadowing is not supersession, stated the other way round: the global
     * record is still in the answer, just behind the project's.
     *
     * <p>The slice-1 rule is deliberately dumb — project results rank ahead of
     * global ones, and no attempt is made to decide whether the two are about
     * the same subject. That judgement is exactly what defeated Excalibur's
     * scribe. Dropping the global record would be that judgement made silently.
     */
    @Test
    void a_shadowed_global_memory_is_still_returned_behind_the_project_one() {
        WriteResult global = write("The retry budget is 4", GLOBAL);
        write("The retry budget is 3", PAYMENTS);

        assertTrue(recallIds("retry budget", PAYMENTS, 10).contains(global.memoryId()));
    }

    /**
     * A global question is answered from the global tier alone.
     *
     * <p>There is no project to shadow with, and sweeping every project's tier
     * would hand one project's local facts to a caller that asked what holds
     * everywhere — which is the two-tier model inverted.
     */
    @Test
    void recall_in_the_global_tier_never_reaches_into_a_project() {
        WriteResult global = write("Prefer small pull requests", GLOBAL);
        WriteResult project = write("Payments uses mTLS", PAYMENTS);

        List<String> ids = recallIds("anything", GLOBAL, 10);

        assertTrue(ids.contains(global.memoryId()));
        assertFalse(ids.contains(project.memoryId()));
    }

    /** Nearest first, and the ordering comes from the vectors rather than from
     *  the id. Two directions a full cosine unit apart, so a query aimed at one
     *  of them cannot pick the other by accident. */
    @Test
    void recall_returns_the_nearest_memory_first() {
        embeddings.assign("mTLS", 0);
        embeddings.assign("retry", 1);
        WriteResult mtls = write("Payments uses mTLS", PAYMENTS);
        WriteResult retries = write("The retry budget is 3", PAYMENTS);

        assertEquals(retries.memoryId(), recallIds("what is the retry budget", PAYMENTS, 10).get(0));
        assertEquals(mtls.memoryId(), recallIds("how do services authenticate, mTLS?", PAYMENTS, 10)
                .get(0));
    }

    @Test
    void recall_respects_the_limit() {
        for (int i = 0; i < 5; i++) {
            write("fact " + i, PAYMENTS);
        }

        assertEquals(2, archive.recall("fact", PAYMENTS, 2).memories().size());
    }

    /**
     * A project recall draws on two tiers, and the limit is still the limit.
     *
     * <p>Each tier is asked for the <em>full</em> limit, deliberately: a project
     * holding two memories must still be able to fill the rest of its answer
     * from global. What that costs is that two well-stocked tiers hand back
     * {@code 2 x limit} rows, and the truncation afterwards is the only thing
     * standing between the caller and twice what it asked for.
     *
     * <p>Nothing tested that. Every other recall test here either asks with a
     * limit at or above the row count or leaves the global tier empty, so
     * replacing the truncation with {@code found = hits;} left all 176 green —
     * while {@code limit} comes straight from the model, under a schema
     * promising "at most". Four rows across two tiers, asked for three, is the
     * smallest case in which the truncation is the only thing doing any work.
     */
    @Test
    void a_project_recall_never_returns_more_than_the_limit_across_both_tiers() {
        WriteResult projectA = write("a payments fact", PAYMENTS);
        WriteResult projectB = write("another payments fact", PAYMENTS);
        write("a fact for everyone", GLOBAL);
        write("another fact for everyone", GLOBAL);

        List<String> ids = recallIds("anything", PAYMENTS, 3);

        assertEquals(3, ids.size(), "asked for at most 3 across two tiers of two: " + ids);
        // And what survives the cut is the project's own, because the project
        // hits are concatenated first: shadowing is what the truncation must
        // not undo.
        assertEquals(List.of(projectA.memoryId(), projectB.memoryId()), ids.subList(0, 2));
    }

    /** A limit of zero asks for nothing, and gets nothing — without a round trip
     *  to the embedding endpoint or a query. A caller paginating to the end
     *  should not pay for a model call to be told what it already knows. */
    @Test
    void a_limit_of_zero_returns_nothing_and_asks_the_model_nothing() {
        write("a fact", PAYMENTS);
        embeddings.failNext();

        assertTrue(archive.recall("anything", PAYMENTS, 0).memories().isEmpty());
    }

    /**
     * And the overload that is handed a vector answers the same way.
     *
     * <p>A public entry point of its own — {@code Scribe} calls it with a vector
     * it has already paid for — so it holds for what it is handed rather than
     * for what its one caller happens to send. The {@code unsearchable} count is
     * what the assertion turns on: with the check removed the query still comes
     * back empty, because {@code LIMIT 0} does, and only the count gives the
     * transaction away.
     */
    @Test
    void a_limit_of_zero_against_a_vector_returns_nothing_and_counts_nothing() {
        // Written while the endpoint was down, so the tier really does hold
        // something a search could not look at -- which is the only thing the
        // count below can be non-zero about.
        embeddings.failNext();
        write("a fact", PAYMENTS);

        Archive.Recall answered = archive.recall(
                new Archive.Precomputed(StubEmbeddingClient.ONE, "anything"), PAYMENTS, 0);

        assertTrue(answered.memories().isEmpty());
        assertEquals(0, answered.unsearchable(),
                "nothing was searched, so nothing can have been unsearchable");
    }

    // --- never lose a write ---------------------------------------------------

    /**
     * A memory written while the embedding endpoint was down has a NULL
     * embedding. Never lose a write: it must still exist, still be {@code
     * active}, still be readable by id, and must not break the query for
     * everything else.
     */
    @Test
    void a_memory_with_no_embedding_is_skipped_by_vector_recall_but_still_readable() {
        embeddings.failNext();
        WriteResult unembedded = write("written while the endpoint was down", PAYMENTS);
        WriteResult normal = write("written normally", PAYMENTS);

        assertNotNull(archive.get(unembedded.memoryId()));
        assertEquals(MemoryState.ACTIVE, archive.get(unembedded.memoryId()).state());

        List<String> ids = recallIds("anything", PAYMENTS, 10);
        assertTrue(ids.contains(normal.memoryId()));
        assertFalse(ids.contains(unembedded.memoryId()));
    }

    /** The unembedded memory is still in the index, not merely still in the
     *  table. An agent is shown the index before it asks anything, so a write
     *  that vanished from it would be a write lost in every way that matters
     *  even though the row survived. */
    @Test
    void a_memory_written_with_no_embedding_is_still_in_the_index() {
        embeddings.failNext();
        WriteResult unembedded = write("written while the endpoint was down", PAYMENTS);

        assertTrue(archive.index(PAYMENTS).stream().map(TocEntry::id).toList()
                .contains(unembedded.memoryId()));
    }

    // --- the archive says when it could not search itself ----------------------

    /**
     * <b>The finding this section exists for.</b> An archive holding exactly one
     * memory, unembedded, used to answer "nothing is close to that question" —
     * and mean it, as far as any caller could tell. The write said it succeeded,
     * the index said the memory was there, and recall said the archive held
     * nothing. The only trace was a {@code log.warn} on the server; no endpoint,
     * no tool and no rendered field exposed the state.
     *
     * <p>An agent told "nothing matched" tries another question. It is right to:
     * that is what the sentence means. So the sentence has to stop being said
     * when it is not true. The empty result is still empty — there is genuinely
     * nothing to return — but it now arrives with the count of what could not be
     * looked at, which is the difference between a question worth rephrasing and
     * an archive worth repairing.
     */
    @Test
    void a_recall_that_found_nothing_says_how_much_it_could_not_search() {
        embeddings.failNext();
        write("the answer, written while the endpoint was down", PAYMENTS);

        Archive.Recall recalled = archive.recall("anything", PAYMENTS, 10);

        assertTrue(recalled.memories().isEmpty(), "the row has no vector, so nothing matches");
        assertEquals(1, recalled.unsearchable(),
                "the one memory the archive holds was skipped, and must be reported");
    }

    /**
     * The ordinary case reports zero, which is what makes the number above worth
     * reading. A count that were always non-zero, or always absent, would tell a
     * caller nothing either way.
     */
    @Test
    void a_recall_over_a_fully_embedded_archive_reports_nothing_unsearchable() {
        write("a fact", PAYMENTS);
        write("a fact for everyone", GLOBAL);

        assertEquals(0, archive.recall("anything", PAYMENTS, 10).unsearchable());
        assertEquals(0, archive.recall("anything", GLOBAL, 10).unsearchable());
    }

    /**
     * A partial answer is the more dangerous case, and it is counted too.
     *
     * <p>A caller handed one memory has no reason to suspect a second was
     * unreachable, and will act on the one it got. The count is what gives it
     * the reason.
     */
    @Test
    void a_recall_that_found_something_still_reports_what_it_skipped() {
        write("written normally", PAYMENTS);
        embeddings.failNext();
        write("written while the endpoint was down", PAYMENTS);

        Archive.Recall recalled = archive.recall("anything", PAYMENTS, 10);

        assertEquals(1, recalled.memories().size());
        assertEquals(1, recalled.unsearchable());
    }

    /**
     * A project recall counts both tiers, because it searched both.
     *
     * <p>Reporting only the project's own would understate exactly the case a
     * project asks about: a global memory it would have drawn on, silently
     * missing from its answer.
     */
    @Test
    void a_project_recall_counts_the_unsearchable_memories_in_global_too() {
        embeddings.failNext();
        write("a project fact written while the endpoint was down", PAYMENTS);
        embeddings.failNext();
        write("a global fact written while the endpoint was down", GLOBAL);

        assertEquals(2, archive.recall("anything", PAYMENTS, 10).unsearchable());
        // And a global question counts global alone: there is no project tier in
        // that answer, so a project's broken row is not that caller's problem.
        assertEquals(1, archive.recall("anything", GLOBAL, 10).unsearchable());
    }

    /**
     * A tombstone with no embedding is not counted. It is excluded from the
     * answer on purpose, not missing from it — counting it would report a
     * problem that is not one, and a count that cries wolf is a count nobody
     * reads.
     */
    @Test
    void an_unembedded_tombstone_is_not_reported_as_unsearchable() {
        embeddings.failNext();
        WriteResult unembedded = write("written while the endpoint was down", PAYMENTS);
        archive.invalidate(unembedded.memoryId(), "it was never true", "enzo");

        assertEquals(0, archive.recall("anything", PAYMENTS, 10).unsearchable());
    }

    /**
     * The index marks the line, which is what turns a count into a repair.
     *
     * <p>"Two memories could not be searched" is actionable only if somebody can
     * find out which two. The index is the projection an agent is shown before
     * it asks anything, and it is the one place the mark costs nothing to carry.
     */
    @Test
    void the_index_marks_the_memory_recall_cannot_reach() {
        WriteResult normal = write("written normally", PAYMENTS);
        embeddings.failNext();
        WriteResult unembedded = write("written while the endpoint was down", PAYMENTS);

        List<TocEntry> index = archive.index(PAYMENTS);

        assertFalse(entry(index, normal.memoryId()).unsearchable());
        assertTrue(entry(index, unembedded.memoryId()).unsearchable());
    }

    // --- and the repair -------------------------------------------------------

    /**
     * Re-embedding gives the vector back, and the memory becomes findable.
     *
     * <p>The repair is what makes the report worth acting on. Note what it does
     * <em>not</em> do: nothing is rewritten, nothing changes state, and a memory
     * that already had a vector is not asked for a new one.
     */
    @Test
    void reembedding_makes_a_memory_written_without_a_vector_findable_again() {
        embeddings.failNext();
        WriteResult unembedded = write("written while the endpoint was down", PAYMENTS);
        assertFalse(recallIds("anything", PAYMENTS, 10).contains(unembedded.memoryId()));

        Archive.Repair repair = archive.reembed(PAYMENTS);

        assertEquals(List.of(unembedded.memoryId()), repair.repaired());
        assertEquals(List.of(), repair.failed());
        assertTrue(recallIds("anything", PAYMENTS, 10).contains(unembedded.memoryId()));
        assertEquals(0, archive.recall("anything", PAYMENTS, 10).unsearchable());
    }

    /**
     * A repair that cannot reach the endpoint reports the memory as still owed
     * one, rather than as repaired.
     *
     * <p>The whole value of the two lists is that an operator running this
     * because an endpoint was down can tell whether it is back. A pass that
     * reported every id as repaired would be worse than no pass at all: it would
     * say the archive is searchable when it is not.
     */
    @Test
    void a_repair_that_still_cannot_embed_says_which_memories_are_still_owed_a_vector() {
        embeddings.failNext();
        WriteResult unembedded = write("written while the endpoint was down", PAYMENTS);
        embeddings.failNext();

        Archive.Repair repair = archive.reembed(PAYMENTS);

        assertEquals(List.of(), repair.repaired());
        assertEquals(List.of(unembedded.memoryId()), repair.failed());
        // And the memory is exactly where it was: never lose a write.
        assertEquals(MemoryState.ACTIVE, archive.get(unembedded.memoryId()).state());
        assertEquals(1, archive.recall("anything", PAYMENTS, 10).unsearchable());
    }

    private static TocEntry entry(List<TocEntry> index, String id) {
        return index.stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError(id + " is not in the index: " + index));
    }

    /**
     * A recall whose <em>question</em> cannot be embedded fails loudly.
     *
     * <p>The opposite call from the write path, and deliberately so. A write with
     * no vector still keeps the memory, because the memory is the thing of
     * value. A recall with no query vector has nothing to rank by at all, and
     * handing back whatever rows some fallback ordering produced would be
     * indistinguishable, to the caller, from a search that worked.
     */
    @Test
    void a_recall_that_cannot_embed_the_question_is_refused_rather_than_faked() {
        write("a fact", PAYMENTS);
        embeddings.failNext();

        assertThrows(EmbeddingException.class, () -> archive.recall("anything", PAYMENTS, 10));
    }

    // --- recall must not eat the archive --------------------------------------

    /**
     * Recalling a memory must not erase its vector.
     *
     * <p>Recall counts a use and saves the record back, so if {@link
     * MemoryStore#save} ever listed the {@code embedding} column the archive
     * would lose the vector of every memory anyone actually recalled and get
     * <em>less</em> searchable the more it was used.
     * {@code MemoryStoreTest.saving_a_memory_again_does_not_erase_its_embedding}
     * pins the store's half of that; this pins it end to end, through the method
     * that does the saving.
     */
    @Test
    void recalling_a_memory_twice_still_finds_it_the_second_time() {
        WriteResult only = write("a fact worth keeping", PAYMENTS);

        assertTrue(recallIds("anything", PAYMENTS, 10).contains(only.memoryId()));
        assertTrue(recallIds("anything", PAYMENTS, 10).contains(only.memoryId()));
        assertNotNull(jdbc.queryForObject(
                "SELECT embedding FROM memories WHERE id = ?", String.class, only.memoryId()));
    }

    /**
     * Recall counts a use, because it hands back bodies.
     *
     * <p>Excalibur does the same thing one layer up — its librarian ends {@code
     * recall} by calling {@code archive.read(ids)}. Without it {@link Scoring}
     * would never see a signal from the one operation the archive exists to
     * serve, and decay would rank memories purely by age. {@code index} still
     * counts nothing: it returns no bodies, so surveying is not using.
     */
    @Test
    void recall_counts_a_use_and_the_index_does_not() {
        WriteResult only = write("a fact", PAYMENTS);

        assertEquals(0, archive.get(only.memoryId()).uses());
        archive.index(PAYMENTS);
        assertEquals(0, archive.get(only.memoryId()).uses());

        archive.recall("anything", PAYMENTS, 10);

        assertEquals(1, archive.get(only.memoryId()).uses());
        assertEquals(NOW, archive.get(only.memoryId()).lastUsed());
    }

    // --- what gets embedded ---------------------------------------------------

    /**
     * The summary and the scope are what get embedded; the body is not.
     *
     * <p>The scope is the sentence saying when a memory applies, which is what a
     * question should match against; bodies are long, varied, and full of
     * incidental detail that drags the vector toward whatever the body happened
     * to mention.
     *
     * <p>Two memories, and a marker that appears in one's <em>body</em> and the
     * other's <em>summary</em>. A question aimed at the marker must land on the
     * memory that is actually about it. Were the body embedded, both would sit
     * at distance zero and the tie-break on id would hand back the wrong one —
     * which is the whole failure this choice avoids, in miniature.
     */
    @Test
    void the_body_is_not_part_of_what_gets_embedded() {
        embeddings.assign("ZZQUUX", 0);
        embeddings.assign("mTLS", 1);
        WriteResult mentionsItInPassing = archive.applyVerdict(
                new MemoryProposal("Payments uses mTLS", "payments auth work",
                        "Rolled out during ZZQUUX; see ticket ZZQUUX-9.",
                        "claude-code", "proj/payments"),
                isNew(), PAYMENTS);
        WriteResult isAboutIt = write("ZZQUUX is the rollout codename", PAYMENTS);

        assertEquals(isAboutIt.memoryId(), recallIds("tell me about ZZQUUX", PAYMENTS, 10).get(0));
        assertEquals("Payments uses mTLS\npayments auth work",
                Archive.embeddedText(archive.get(mentionsItInPassing.memoryId())));
    }

    // --- survey: the same search, without the counter --------------------------

    /**
     * The whole reason {@code survey} exists, stated as the pair.
     *
     * <p>{@code Curator}'s triage runs one near-neighbour query per candidate
     * against global, so on {@code recall} a nightly pass over a 200-memory
     * project put up to five use counters up per candidate — a thousand global
     * memories marked as used by a pass that read none of them. Those counters
     * feed {@link Scoring} and so feed demotion, so triage was reordering the
     * tier every project reads.
     *
     * <p>Both halves are asserted here rather than in two tests, because the
     * claim is a difference: a mutant that stopped {@code recall} counting would
     * pass a survey-only test and leave {@code Scoring} with no signal at all.
     */
    @Test
    void a_survey_counts_no_use_where_a_recall_counts_one() {
        WriteResult only = write("a fact", PAYMENTS);

        archive.survey("anything", PAYMENTS, 10);
        assertEquals(0, archive.get(only.memoryId()).uses(),
                "a survey read no bodies, so it used nothing");
        assertNull(archive.get(only.memoryId()).lastUsed(),
                "a survey must not move lastUsed either, or decay reads as a use");

        archive.recall("anything", PAYMENTS, 10);
        assertEquals(1, archive.get(only.memoryId()).uses());
    }

    /** Fifty surveys and the counter is still zero: the pass is repeatable and
     *  the cost does not accumulate, which is the property the curator needs. */
    @Test
    void surveying_the_same_memory_fifty_times_still_counts_nothing() {
        WriteResult only = write("a fact", PAYMENTS);

        for (int i = 0; i < 50; i++) {
            archive.survey("anything", PAYMENTS, 10);
        }

        assertEquals(0, archive.get(only.memoryId()).uses());
    }

    /** Same rows, same order. The tier logic is shared, and this is what says so
     *  — a survey that searched differently would rule out different things from
     *  the ones a recall would have found. */
    @Test
    void a_survey_finds_what_a_recall_finds_in_the_same_order() {
        embeddings.assign("mTLS", 0);
        embeddings.assign("retry", 1);
        write("Payments uses mTLS", PAYMENTS);
        write("The retry budget is 3", PAYMENTS);
        write("Retries must be bounded", GLOBAL);

        List<String> surveyed = archive.survey("what is the retry budget", PAYMENTS, 10).found()
                .stream().map(TocEntry::id).toList();

        assertEquals(recallIds("what is the retry budget", PAYMENTS, 10), surveyed);
        assertFalse(surveyed.isEmpty(), "an empty list would make the equality vacuous");
    }

    /**
     * A project survey puts the project's own tier first, exactly as a recall
     * does. Asserted separately from the equality above because that test would
     * still pass if both had the wrong order.
     */
    @Test
    void a_project_survey_puts_the_projects_own_tier_ahead_of_global() {
        WriteResult global = write("The retry budget is 4", GLOBAL);
        WriteResult project = write("The retry budget is 3", PAYMENTS);

        List<String> ids = archive.survey("retry budget", PAYMENTS, 10).found()
                .stream().map(TocEntry::id).toList();

        assertEquals(List.of(project.memoryId(), global.memoryId()), ids);
    }

    /** A global survey answers from global alone, so triage cannot be shown one
     *  project's local facts while deciding another project's promotion. */
    @Test
    void a_global_survey_never_reaches_into_a_project() {
        WriteResult global = write("Prefer small pull requests", GLOBAL);
        WriteResult project = write("Payments uses mTLS", PAYMENTS);

        List<String> ids = archive.survey("anything", GLOBAL, 10).found()
                .stream().map(TocEntry::id).toList();

        assertTrue(ids.contains(global.memoryId()));
        assertFalse(ids.contains(project.memoryId()));
    }

    /** A retired memory is not a thing global still holds, so it must not rule a
     *  promotion out. The state filter is in the shared query; this is the
     *  survey side of it. */
    @Test
    void a_survey_never_returns_a_retired_memory() {
        WriteResult gone = write("dead fact", PAYMENTS);
        archive.invalidate(gone.memoryId(), "no longer true", "operator");

        assertTrue(archive.survey("dead fact", PAYMENTS, 10).found().stream()
                .noneMatch(entry -> entry.id().equals(gone.memoryId())));
    }

    @Test
    void a_survey_respects_the_limit_across_both_tiers() {
        write("The retry budget is 4", GLOBAL);
        write("Retries must be bounded", GLOBAL);
        write("The retry budget is 3", PAYMENTS);
        write("Payments retries on 503", PAYMENTS);

        assertEquals(3, archive.survey("retry", PAYMENTS, 3).found().size());
    }

    /**
     * The rule-out is only as good as the search behind it, and a survey says
     * when the search was partial. A global memory written while the embedding
     * endpoint was down has no vector, so "global does not already hold this" is
     * a weaker claim than it reads — and the curator's account of a pass is
     * where a person can see that.
     */
    @Test
    void a_survey_reports_what_it_could_not_search() {
        embeddings.failNext();
        write("written while the endpoint was down", GLOBAL);
        write("written normally", GLOBAL);

        Archive.Survey surveyed = archive.survey("anything", GLOBAL, 10);

        assertEquals(1, surveyed.found().size());
        assertEquals(1, surveyed.unsearchable());
    }

    @Test
    void a_survey_over_a_fully_embedded_archive_reports_nothing_unsearchable() {
        write("a fact", PAYMENTS);
        write("a fact for everyone", GLOBAL);

        assertEquals(0, archive.survey("anything", PAYMENTS, 10).unsearchable());
        assertEquals(0, archive.survey("anything", GLOBAL, 10).unsearchable());
    }

    /**
     * Every entry a survey returns is searchable by construction, because {@code
     * MemoryStore.searchByVector} filters {@code embedding IS NOT NULL}. The
     * flag is on {@link TocEntry} for {@code index}'s sake and would be a lie
     * here if it were ever true.
     */
    @Test
    void every_line_a_survey_returns_is_marked_searchable() {
        embeddings.failNext();
        write("written while the endpoint was down", PAYMENTS);
        write("written normally", PAYMENTS);

        List<TocEntry> found = archive.survey("anything", PAYMENTS, 10).found();

        assertFalse(found.isEmpty());
        assertTrue(found.stream().noneMatch(TocEntry::unsearchable));
    }

    /** Summaries and scopes, and nothing else. A survey that carried bodies
     *  would be a recall that forgot to count, and "returning bodies is what a
     *  use means here" would stop being true of this archive. */
    @Test
    void a_survey_carries_the_summary_and_the_scope_and_no_body() {
        WriteResult only = write("a fact", PAYMENTS);

        TocEntry line = archive.survey("anything", PAYMENTS, 10).found().get(0);

        assertEquals(only.memoryId(), line.id());
        assertEquals("a fact", line.summary());
        assertEquals("payments auth work", line.scope());
        assertEquals("a fact — the long form.", archive.get(only.memoryId()).body(),
                "the body is still there; it is this projection that leaves it out");
    }

    /** A limit of zero asks for nothing and pays for nothing, like recall's. */
    @Test
    void a_survey_with_a_limit_of_zero_asks_the_model_nothing() {
        write("a fact", PAYMENTS);
        embeddings.failNext();

        assertTrue(archive.survey("anything", PAYMENTS, 0).found().isEmpty());
    }

    /**
     * A survey that cannot embed its question is refused, not faked — {@code
     * recall}'s rule and for its reason. Triage would otherwise rule nothing out
     * on the strength of a search that never ran, in front of the one decision
     * that writes into every project's recall.
     */
    @Test
    void a_survey_that_cannot_embed_the_question_is_refused_rather_than_faked() {
        write("a fact", PAYMENTS);
        embeddings.failNext();

        assertThrows(EmbeddingException.class, () -> archive.survey("anything", PAYMENTS, 10));
    }

    // --- helpers ---------------------------------------------------------------

    private static Verdict isNew() {
        return new Verdict(VerdictKind.NEW, null, "novel");
    }

    private static MemoryProposal p(String summary) {
        return new MemoryProposal(
                summary, "payments auth work", summary + " — the long form.",
                "claude-code", "proj/payments");
    }

    private WriteResult write(String summary, Home home) {
        return archive.applyVerdict(p(summary), isNew(), home);
    }

    private List<String> recallIds(String question, Home home, int limit) {
        return archive.recall(question, home, limit).memories().stream().map(Memory::id).toList();
    }
}
