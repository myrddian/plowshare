package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.config.RuntimeConfig;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.EmbeddingException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * One ingest end to end against a real corpus, with a stubbed endpoint.
 *
 * <p><b>No test in this project may reach a live model</b>, and this is the
 * first pipeline whose whole point is to call one a great many times. The stub
 * below is therefore not a convenience: it is what lets the assertions be about
 * how many calls are made, how large they are, what happens when one fails and
 * what a re-ingest does not pay for — none of which a real endpoint could be
 * asked for on demand.
 */
@Testcontainers
class IngestServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static JdbcTemplate jdbc;
    private static UnitOfWork transactions;

    private DocumentStore store;
    private CountingEmbeddings embeddings;
    private IngestService ingest;
    private ScriptedChat chat;
    private RuntimeConfig config;

    private static final int BATCH = 4;

    /** The key an ingest's allowance is live under. */
    private static final String BUDGET_KEY = "plowshare.documents.ingest-budget";

    /** What a cascade over {@code "Alpha.\n\nBeta."} costs when nothing stops
     *  it: one call a paragraph and one for each of the three levels above.
     *  Pinned by {@link #an_ingest_stores_embeds_and_then_summarises} one
     *  section up, and the number the budget tests below are built to sit
     *  either side of. */
    private static final int A_WHOLE_CASCADE = 5;

    private static final Instant NOW = Instant.parse("2026-09-04T09:00:00Z");

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate template =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transactions = new UnitOfWork() {
            @Override
            public <T> T inTransaction(Supplier<T> work) {
                return template.execute(status -> work.get());
            }
        };
    }

    /**
     * A fresh corpus and a fresh map, and the map is fresh for the reason
     * {@code DocumentsPropertiesTest} gives: the last-known-good fallback is
     * memory held per {@link RuntimeConfig} instance, so a shared one would
     * carry one test's read into the next.
     */
    @BeforeEach
    void freshCorpus() {
        jdbc.execute("TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, documents CASCADE");
        jdbc.execute("TRUNCATE TABLE runtime_config");
        store = new DocumentStore(jdbc, transactions);
        embeddings = new CountingEmbeddings();
        chat = new ScriptedChat();
        config = new RuntimeConfig(jdbc);
        ingest = new IngestService(store, embeddings,
                Clock.fixed(NOW, ZoneOffset.UTC), ShippedChunking.SHIPPED, BATCH);
    }

    private static final BooleanSupplier NEVER = () -> false;

    private Outcome run(String name, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return ingest.ingest(name, TextExtraction.extract(name, bytes), bytes.length, "test",
                NEVER);
    }

    // --- the round trip -------------------------------------------------------

    /**
     * <b>The whole slice in one assertion: what was ingested can be asked
     * for.</b>
     *
     * <p>Everywhere else the two halves are tested apart — {@code
     * DocumentStoreTest} searches rows somebody attached vectors to by hand, and
     * {@code RetrievalServiceTest} embeds against a mocked store. Neither can
     * catch the one failure that has no symptom, which is <b>the query and the
     * corpus ending up in different spaces</b>. Here they cannot: the ingest and
     * the search hold the same {@link EmbeddingClient} instance, exactly as
     * {@code DocumentsConfig} hands both services the server's one bean, so the
     * same text really does produce the same vector and similarity 1 is the
     * proof that it did.
     *
     * <p>The stub gives each distinct string its own axis, which is what makes
     * that number exact rather than approximate — and what would make a
     * mismatched space visibly wrong rather than subtly worse.
     */
    @Test
    void a_document_that_was_ingested_can_be_found_by_what_it_says() {
        run("retries.md", "Alpha is unrelated.\n\nRetries are budgeted per run.");

        RetrievalService.Found found =
                retrieval().search("Retries are budgeted per run.", 5);

        assertEquals(2, found.searchable());
        assertEquals(0, found.unsearchable());
        DocumentStore.Hit nearest = found.hits().get(0);
        assertEquals("Retries are budgeted per run.", nearest.chunkText());
        assertEquals("Retries are budgeted per run.", nearest.paragraphText());
        assertEquals(2, nearest.paragraphOrdinal());
        assertEquals("retries.md", nearest.sourceName());
        assertEquals(1.0, nearest.similarity(), 1e-6);
    }

    /**
     * <b>And the sentence a failed ingest ends with is a true one.</b>
     *
     * <p>{@link IngestService} tells whoever reads the outcome that the document
     * is stored, that some chunks are unsearchable, and that "ingesting it again
     * embeds those and re-derives nothing". Until there was a read, every word
     * of that was a promise no test could check. Here it is checked from the
     * far end: the corpus really does answer nothing while saying exactly how
     * much it could not look at, and the second ingest really does make the same
     * question answerable.
     */
    @Test
    void a_document_stored_while_the_endpoint_was_down_is_unsearchable_until_it_is_ingested_again() {
        embeddings.failEvery = true;
        Outcome stopped = run("retries.md", "Retries are budgeted per run.");
        assertFalse(stopped.answered(), stopped.text());

        RetrievalService retrieval = retrieval();
        RetrievalService.Found before = retrieval.search("Retries are budgeted per run.", 5);

        assertTrue(before.hits().isEmpty());
        // Not "the corpus holds nothing about this". The corpus holds the
        // answer, in full, and cannot be asked for it.
        assertTrue(before.corpusIsEmpty());
        assertEquals(1, before.unsearchable());

        embeddings.failEvery = false;
        run("retries.md", "Retries are budgeted per run.");

        RetrievalService.Found after = retrieval.search("Retries are budgeted per run.", 5);
        assertEquals(0, after.unsearchable());
        assertEquals("Retries are budgeted per run.", after.hits().get(0).chunkText());
    }

    /**
     * The corpus's own client, as {@code DocumentsConfig} wires it.
     *
     * <p>Built here from {@link #embeddings} rather than from a second stub, and
     * that is the point of the two tests above: a retrieval holding a different
     * client would be testing two spaces that happen to agree.
     */
    private RetrievalService retrieval() {
        return new RetrievalService(store, embeddings, "stub-embed", 768);
    }

    // --- the happy path -------------------------------------------------------

    @Test
    void a_document_is_stored_and_embedded_and_the_outcome_says_what_it_came_to() {
        Outcome outcome = run("notes.md", "First one.\n\nSecond one.\n\nThird one.");

        assertTrue(outcome.answered(), outcome.text());
        assertTrue(outcome.text().contains("notes.md"), outcome.text());
        assertTrue(outcome.text().contains("3"), outcome.text());
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM paragraphs", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM chunks WHERE embedding IS NULL", Integer.class));
    }

    /**
     * The batching that Anchor does not do.
     *
     * <p>{@code EmbeddingService.embedAll} submits every chunk of a document as
     * ONE HTTP request. One oversized chunk then fails the whole document, and
     * a document large enough is one request nothing can retry a part of.
     * Bounded batches mean a failure costs a batch.
     */
    @Test
    void chunks_are_embedded_in_bounded_batches_and_not_one_request_per_document() {
        run("notes.md", paragraphs(10));

        assertTrue(embeddings.batches.size() >= 3,
                "10 chunks at a batch size of " + BATCH + " is not " + embeddings.batches.size()
                        + " request(s)");
        for (List<String> batch : embeddings.batches) {
            assertTrue(batch.size() <= BATCH, "a batch of " + batch.size() + " ran past " + BATCH);
        }
        assertEquals(10, embeddings.batches.stream().mapToInt(List::size).sum());
    }

    /** Every chunk goes to the endpoint as its own input, and the vector comes
     *  back onto that chunk's row. */
    @Test
    void every_chunk_is_embedded_exactly_once() {
        run("notes.md", paragraphs(6));

        List<String> submitted = embeddings.batches.stream().flatMap(List::stream).toList();
        assertEquals(6, submitted.size());
        assertEquals(6, submitted.stream().distinct().count());
    }

    // --- what a re-ingest does not pay for ------------------------------------

    @Test
    void re_ingesting_an_unchanged_document_makes_no_embedding_call_at_all() {
        run("notes.md", "Alpha.\n\nBeta.");
        embeddings.batches.clear();

        Outcome again = run("notes.md", "Alpha.\n\nBeta.");

        assertTrue(again.answered(), again.text());
        assertEquals(List.of(), embeddings.batches,
                "an unchanged document was re-embedded, which is the whole cost of an ingest");
        assertEquals(0, again.modelCalls());
    }

    @Test
    void an_edited_document_embeds_only_what_changed() {
        run("notes.md", "Alpha.\n\nBeta.\n\nGamma.");
        embeddings.batches.clear();

        run("notes.md", "Alpha.\n\nBeta, revised.\n\nGamma.");

        List<String> submitted = embeddings.batches.stream().flatMap(List::stream).toList();
        assertEquals(List.of("Beta, revised."), submitted);
    }

    // --- when the endpoint is not there ---------------------------------------

    /**
     * The rule V1 states for memories, at corpus scale: <b>an embedding endpoint
     * being down loses the vectors and never the text.</b>
     *
     * <p>And the outcome has to say so. A document stored with no vectors is
     * invisible to every search, and an ingest that reported success would leave
     * somebody asking a corpus that cannot answer.
     */
    @Test
    void an_endpoint_that_is_down_keeps_the_document_and_says_the_vectors_are_missing() {
        embeddings.failEvery = true;

        Outcome outcome = run("notes.md", "Alpha.\n\nBeta.");

        assertFalse(outcome.answered());
        assertEquals(Outcome.Ending.UNAVAILABLE, outcome.ending());
        assertTrue(outcome.text().contains("2"), outcome.text());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM paragraphs", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM chunks WHERE embedding IS NULL", Integer.class));
    }

    /**
     * A later ingest finishes what an outage started.
     *
     * <p>This is what makes the store's "text first, vectors after" split
     * resumable rather than merely safe: the chunks awaiting a vector are found
     * from their own rows, so nothing has to be re-derived and no state outside
     * the database has to survive.
     */
    @Test
    void a_second_ingest_embeds_what_the_outage_left_behind() {
        embeddings.failEvery = true;
        run("notes.md", "Alpha.\n\nBeta.");
        embeddings.failEvery = false;
        embeddings.batches.clear();

        Outcome again = run("notes.md", "Alpha.\n\nBeta.");

        assertTrue(again.answered(), again.text());
        assertEquals(2, embeddings.batches.stream().mapToInt(List::size).sum());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM chunks WHERE embedding IS NULL", Integer.class));
    }

    // --- cancellation ---------------------------------------------------------

    /**
     * Cancelling stops at a batch boundary, and what was already done stays
     * done.
     *
     * <p>Deterministic and with no sleep: the flag is set by the stub, from
     * inside the first batch, so the second boundary is reached with the request
     * already outstanding.
     */
    @Test
    void a_cancelled_ingest_stops_at_a_batch_boundary_and_keeps_what_it_wrote() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        embeddings.onCall = () -> cancelled.set(true);
        byte[] bytes = paragraphs(10).getBytes(StandardCharsets.UTF_8);

        Outcome outcome = ingest.ingest("notes.md",
                TextExtraction.extract("notes.md", bytes), bytes.length, "test", cancelled::get);

        assertEquals(Outcome.Ending.CANCELLED, outcome.ending());
        assertEquals(1, embeddings.batches.size(), "it went on past the first boundary");
        assertEquals(10, jdbc.queryForObject("SELECT count(*) FROM paragraphs", Integer.class));
        assertEquals(6, jdbc.queryForObject(
                "SELECT count(*) FROM chunks WHERE embedding IS NULL", Integer.class));
    }

    /** Cancelling before any work is done writes nothing at all. */
    @Test
    void an_ingest_cancelled_before_it_starts_writes_nothing() {
        byte[] bytes = "Alpha.".getBytes(StandardCharsets.UTF_8);

        Outcome outcome = ingest.ingest("notes.md",
                TextExtraction.extract("notes.md", bytes), bytes.length, "test", () -> true);

        assertEquals(Outcome.Ending.CANCELLED, outcome.ending());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM documents", Integer.class));
        assertEquals(List.of(), embeddings.batches);
    }

    // --- edges ----------------------------------------------------------------

    /** A document whose text is all whitespace never reaches here — {@code
     *  TextExtraction} refuses it — and a document of one paragraph is still a
     *  document. */
    @Test
    void a_one_paragraph_document_is_a_document() {
        Outcome outcome = run("one.md", "Just the one.");

        assertTrue(outcome.answered(), outcome.text());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM paragraphs", Integer.class));
    }

    /** The ingest records who asked and when, from the clock it was given
     *  rather than from the wall. */
    @Test
    void the_document_records_who_ingested_it_and_when() {
        run("notes.md", "Alpha.");

        DocumentStore.StoredDocument stored = store.find("notes.md").orElseThrow();
        assertEquals("test", stored.ingestedBy());
        assertEquals(NOW, stored.ingestedAt());
    }

    private static String paragraphs(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            text.append("Paragraph number ").append(i).append(".\n\n");
        }
        return text.toString();
    }

    /**
     * The endpoint, stubbed: it records every batch it was handed and can be
     * made to fail or to trip a flag.
     *
     * <p>Vectors are distinct per input so nothing downstream can pass by
     * accident on every row being identical.
     */
    private static final class CountingEmbeddings implements EmbeddingClient {

        final List<List<String>> batches = new ArrayList<>();
        boolean failEvery;
        Runnable onCall;

        @Override
        public float[] embed(String text) {
            return embedAll(List.of(text)).get(0);
        }

        @Override
        public List<float[]> embedAll(List<String> texts) {
            batches.add(List.copyOf(texts));
            if (onCall != null) {
                onCall.run();
            }
            if (failEvery) {
                throw new EmbeddingException("the stub endpoint is down");
            }
            List<float[]> vectors = new ArrayList<>();
            for (String text : texts) {
                float[] vector = new float[768];
                vector[Math.floorMod(text.hashCode(), 768)] = 1.0f;
                vectors.add(vector);
            }
            return vectors;
        }
    }

    // --- and then the cascade -------------------------------------------------

    /**
     * <b>The whole pipeline: stored, embedded, and every paragraph labelled with
     * what it claims.</b>
     *
     * <p>This is the assertion the slice exists for. Before it, Documents made
     * <em>no model call at all</em> — it ran beside the substrate rather than
     * through it — so the corpus could be searched and had nothing to say about
     * itself.
     *
     * <p>Note the order: the vectors are attached before the first summariser
     * call. Anchor summarises, then embeds, then persists, and nothing is
     * readable until all of it is done; here a document is searchable within
     * seconds and gains its labels over the following half hour.
     */
    @Test
    void an_ingest_stores_embeds_and_then_summarises() {
        Outcome outcome = summarising(20).ingest("notes.md",
                TextExtraction.extract("notes.md", "Alpha.\n\nBeta.".getBytes(
                        StandardCharsets.UTF_8)), 14, "test", NEVER);

        assertTrue(outcome.answered(), outcome.text());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM chunks WHERE embedding IS NULL", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM paragraphs WHERE summary IS NULL", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM documents WHERE summary IS NULL", Integer.class));
        // Two paragraph calls and one for each of the three units above them,
        // and the outcome carries both halves: what was stored, and what it cost
        // to label.
        assertEquals(5, outcome.modelCalls() - embeddings.batches.size(), outcome.text());
        assertTrue(outcome.text().contains("Ingested 'notes.md'"), outcome.text());
        assertTrue(outcome.text().contains("model call"), outcome.text());
    }

    /**
     * <b>A dead chat endpoint loses the labels and never the document.</b>
     *
     * <p>The same rule the embedding half already keeps, one derived thing over:
     * the text is committed, the vectors are on, and the document is searchable
     * — what is missing is what every paragraph claims, and the outcome says
     * which paragraphs are still owed one rather than reporting a failed ingest
     * over a corpus that has the document in it.
     */
    @Test
    void a_cascade_that_could_not_run_leaves_a_searchable_document_behind() {
        chat.then(() -> {
            throw new io.aeyer.plowshare.server.llm.dispatch.LlmException("nothing is answering");
        });

        Outcome outcome = summarising(20).ingest("notes.md",
                TextExtraction.extract("notes.md", "Alpha.\n\nBeta.".getBytes(
                        StandardCharsets.UTF_8)), 14, "test", NEVER);

        assertEquals(Outcome.Ending.UNAVAILABLE, outcome.ending(), outcome.text());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM paragraphs", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM chunks WHERE embedding IS NULL", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM paragraphs WHERE summary IS NULL", Integer.class));
    }

    /**
     * A server with no summarisers ingests, and says so.
     *
     * <p>Reachable in production: {@code AgentsConfig} treats a missing agent
     * directory as a legal running deployment. Such an ingest is the pipeline as
     * it was before this slice, and reporting plain success over it would leave
     * somebody looking at a corpus whose paragraphs carry nothing, with no line
     * anywhere saying why.
     */
    @Test
    void an_ingest_on_a_server_with_no_cascade_says_nothing_was_summarised() {
        Outcome outcome = run("notes.md", "Alpha.\n\nBeta.");

        assertTrue(outcome.answered(), outcome.text());
        assertTrue(outcome.text().contains("Nothing was summarised"), outcome.text());
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM paragraphs WHERE summary IS NULL", Integer.class));
    }

    // --- an allowance an operator changed while the server was up -------------

    /*
     * THE TWO HALVES OF SPEC 1.3, on the one key that is live today.
     *
     * A write changes what NEW work is created with, and leaves running work
     * alone. Both halves need the same fixture and the same document, and they
     * differ only in WHEN the write lands -- before ingest() is entered, or from
     * inside it -- so they are written together and read together.
     *
     * Both are built either side of A_WHOLE_CASCADE. A budget above it is an
     * ingest that finishes; one below it stops with CALL_BUDGET, and the message
     * Summariser writes on that ending names the number it was given -- which is
     * what makes "which allowance did this ingest run on" observable from an
     * Outcome rather than inferred from a call count.
     */

    /**
     * <b>The point of the whole feature: an operator raises the allowance and
     * the next ingest spends the new one.</b>
     *
     * <p>Before this, the live accessor was read once per boot, so a write was
     * visible through the accessor and through the config API and an actual
     * ingest still ran on the number the server started with. The accessor was
     * live and the behaviour was not.
     *
     * <p>Written to be unsatisfiable by an ingest that ignored the map: the
     * bound value is well above a whole cascade and the written one is well
     * below it, so the two answers are a finished document and a stopped one.
     */
    @Test
    void an_ingest_started_after_a_write_uses_the_new_budget() {
        config.put(BUDGET_KEY, "3", "enzo");

        Outcome outcome = summarising(A_WHOLE_CASCADE * 4).ingest("notes.md",
                TextExtraction.extract("notes.md", "Alpha.\n\nBeta.".getBytes(
                        StandardCharsets.UTF_8)), 14, "test", NEVER);

        assertEquals(Outcome.Ending.CALL_BUDGET, outcome.ending(), outcome.text());
        assertTrue(outcome.text().contains("allowance of 3 model calls"), outcome.text());
    }

    /**
     * And the other half: a write does not reach an ingest already under way.
     *
     * <p>The write lands from inside the embedding stub, so it is committed
     * before the cascade starts and the ingest goes on to spend an allowance
     * the map no longer holds. <b>That is the assertion, and it is about
     * <em>where</em> the accessor is read rather than whether it is.</b> An
     * ingest is created when {@code ingest()} is entered; reading the accessor
     * at the cascade instead would put the read minutes of embedding later and
     * make "already under way" mean "has not reached the expensive part yet" —
     * on the one phase that runs for half an hour.
     *
     * <p>The last two assertions are what stop this passing vacuously. A write
     * that never landed, or a map nothing reads, would produce the same finished
     * ingest as the rule being kept.
     */
    @Test
    void an_ingest_already_running_keeps_the_budget_it_started_with() {
        DocumentsProperties props = boundTo(A_WHOLE_CASCADE * 4);
        embeddings.onCall = () -> config.put(BUDGET_KEY, "3", "enzo");

        Outcome outcome = summarising(props).ingest("notes.md",
                TextExtraction.extract("notes.md", "Alpha.\n\nBeta.".getBytes(
                        StandardCharsets.UTF_8)), 14, "test", NEVER);

        assertTrue(outcome.answered(), outcome.text());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM paragraphs WHERE summary IS NULL", Integer.class),
                "the cascade stopped short, so it was re-read against the write that landed"
                        + " mid-ingest");
        assertEquals("3", config.get(BUDGET_KEY).orElse(null),
                "the write never landed, so this test proved nothing");
        assertEquals(3, props.ingestBudgetNow(),
                "the accessor is not reading the map at all, so this test proved nothing");
    }

    /**
     * <b>Spec §1.3 whole, on one service and one write: the ingest already
     * under way keeps its allowance, and the next one through the same service
     * gets the new one.</b>
     *
     * <p><b>What this holds that the two above do not.</b> Each of those builds
     * a fresh {@link IngestService} and runs one ingest through it, so both stay
     * green against a pipeline that read the map once and kept the answer in a
     * field. Production has one {@code IngestService} — {@code DocumentsConfig}
     * builds it as a bean and every ingest this server ever runs goes through
     * that instance — so "a write changes what NEW work is created with" is a
     * claim about <em>one object answering differently twice</em>, and that is
     * what is asserted here: the same service, either side of one write.
     *
     * <p><b>"Already queued" is not a state this server has, and this test is
     * named for what it does have.</b> {@code JobStore} runs every job on {@code
     * Executors.newVirtualThreadPerTaskExecutor}, so a submitted ingest is a
     * started ingest and there is no queue for a write to land in front of. Spec
     * §2 says the rule holds "for free" for an ingest because "a job owns its
     * {@code Budget}" — which is not how this one works. {@code
     * JobStore.submit(String, Home, Function)} puts {@code null} limits on the
     * {@code Job}, and the {@link io.aeyer.plowshare.server.agents.Budget} is
     * minted inside {@code IngestService.ingest} out of a value it reads itself.
     * The rule holds because of <em>where that read is</em> — the first
     * statement of {@code ingest()}, below the cancel check — which Task 4a had
     * to move it to. It was not free.
     *
     * <p>Built either side of {@link #A_WHOLE_CASCADE} for the reason the two
     * above are: a finished document and a stopped one are the two answers, so
     * which allowance each ingest ran on is readable off its {@link Outcome}
     * rather than inferred from a call count.
     */
    @Test
    void a_write_landing_mid_ingest_reaches_the_next_ingest_and_not_that_one() {
        IngestService service = summarising(A_WHOLE_CASCADE * 4);
        embeddings.onCall = () -> config.put(BUDGET_KEY, "3", "enzo");

        Outcome under = service.ingest("first.md",
                TextExtraction.extract("first.md", "Alpha.\n\nBeta.".getBytes(
                        StandardCharsets.UTF_8)), 14, "test", NEVER);
        Outcome next = service.ingest("second.md",
                TextExtraction.extract("second.md", "Gamma.\n\nDelta.".getBytes(
                        StandardCharsets.UTF_8)), 14, "test", NEVER);

        assertEquals(Outcome.Ending.ANSWERED, under.ending(),
                "the write landed while this ingest was embedding and must not have reached"
                        + " it: " + under.text());
        assertEquals(Outcome.Ending.CALL_BUDGET, next.ending(),
                "the same service after the write, and it is still spending the number it was"
                        + " built with: " + next.text());
        assertTrue(next.text().contains("allowance of 3 model calls"), next.text());
        assertEquals("3", config.get(BUDGET_KEY).orElse(null),
                "the write never landed, so this test proved nothing");
    }

    /**
     * <b>A live allowance below one is reported as an allowance, and not as a
     * missing cascade.</b>
     *
     * <p>The two states shared one sentence, and until this key went live that
     * was honest: {@code DocumentsConfig} validated the same number it froze
     * into the constructor, so an allowance below one was unreachable except
     * through the six-argument constructor, which passes no cascade either.
     *
     * <p>A live value reaches this branch on its own. Spec §1.1 accepts that a
     * live key is checked at boot and <b>not</b> on write, so a {@code PUT} of
     * zero is permitted — and the boot check reads the value as bound, so an
     * unpinned deployment starts cleanly on a shipped 1000 with a stale zero
     * still in the map. Every ingest after that told an operator with a wired
     * cascade that their server has none, and sent them to their agent
     * directory to look for it.
     *
     * <p>Built the way the two above are: a cascade that would finish, and the
     * map holding the number that stops it, so a message about the cascade
     * being absent is the defect rather than a second reading of the same
     * state.
     *
     * <p><b>And it must not claim to know what a restart does.</b> The sentence
     * said flatly that "a restart will not clear it", which is true of the
     * unpinned deployment described above and false of a pinned one: {@code
     * RuntimeConfigSeed.operatorPinned} makes the seed rewrite the row from the
     * pin on every boot, so an operator who wrote {@code 0} through {@code PUT
     * /v1/config} on a pinned deployment has it cleared by the next restart, and
     * one told otherwise waits out a symptom that has already gone. This service
     * holds no seed and cannot ask, so the message names {@code GET /v1/config}
     * — whose {@code pinned} field exists for this question — instead of
     * guessing. Both halves are asserted: the false claim is absent and the
     * question is handed somewhere that can answer it.
     */
    @Test
    void a_live_allowance_below_one_names_the_allowance_and_not_a_missing_cascade() {
        config.put(BUDGET_KEY, "0", "enzo");

        Outcome outcome = summarising(A_WHOLE_CASCADE * 4).ingest("notes.md",
                TextExtraction.extract("notes.md", "Alpha.\n\nBeta.".getBytes(
                        StandardCharsets.UTF_8)), 14, "test", NEVER);

        assertTrue(outcome.answered(), outcome.text());
        assertTrue(outcome.text().contains(BUDGET_KEY + " is 0"), outcome.text());
        assertFalse(outcome.text().contains("no summariser cascade"),
                "the cascade is wired and the allowance is what stopped this ingest, so this"
                        + " sends the one person who can fix it to read an agent directory that"
                        + " has nothing wrong with it: " + outcome.text());
        assertTrue(outcome.text().contains("runtime config map"),
                "the number came from a row this operator cannot see in the file, so a message"
                        + " naming the key without naming the channel sends them to a knob that"
                        + " cannot move it: " + outcome.text());
        assertFalse(outcome.text().contains("a restart will not clear it"),
                "on a deployment that pinned this key the seed rewrites the row from the pin on"
                        + " every boot, so a restart DOES clear it; stating the opposite makes"
                        + " an operator wait out a symptom that is already gone: "
                        + outcome.text());
        assertTrue(outcome.text().contains("pinned outside the jar")
                        && outcome.text().contains("GET /v1/config"),
                "whether a restart clears this is RuntimeConfigSeed.operatorPinned's answer and"
                        + " this service cannot ask it, so the message has to hand the question"
                        + " to the surface that can rather than drop it: " + outcome.text());
    }

    // --- what a re-ingest does to the thing a citation is written from ----------

    /*
     * V18 and implementation rationale 1.3 both rest a decision on a property they describe
     * as "currently unexercised": that the surrogate key exists so a citation
     * survives a re-ingest. DocumentStoreTest exercises the RULE -- it hands
     * `write` a derivation it built itself -- and nothing exercised the PATH: an
     * upload's bytes, through TextExtraction, through Derivation, through the
     * whole pipeline, twice. The three below are that path, and they are here
     * rather than in the citation tests because the property is a property of
     * the ingest and is true whether or not anything cites anything.
     *
     * ALSO WORTH SAYING, because V18's own prose implies otherwise: there is no
     * dedup gate. V18 calls documents.content_hash "THE DEDUP GATE: an upload
     * whose content hash already matches the row is not a re-ingest, and the
     * whole pipeline is skipped", and `text_hash` "the second gate". Neither is
     * read anywhere -- IngestService compares no hash and StoredDocument
     * .textHash() has no caller in main or test. Every re-ingest below therefore
     * runs the whole derivation again, which is the case that actually stresses
     * the identity rule, so the absence makes these tests stronger rather than
     * weaker.
     */

    /** The property, through the pipeline rather than through the rule. */
    @Test
    void a_re_ingest_of_the_same_upload_keeps_every_paragraph_id() {
        run("notes.md", "Retries are budgeted per run.\n\nAlpha is unrelated.");
        Map<Integer, UUID> before = paragraphIds();

        run("notes.md", "Retries are budgeted per run.\n\nAlpha is unrelated.");

        assertEquals(before, paragraphIds());
    }

    /**
     * <b>Different bytes, same prose, same ids</b> — which is the re-ingest that
     * matters and the one a byte-identical upload cannot prove anything about.
     *
     * <p>{@code Derivation.split} re-flows a paragraph onto one line before
     * {@code sha256} sees it, so a document re-saved at another line width is
     * the same paragraph. Nothing above this line asserted that end to end: the
     * store's tests hash text they wrote themselves, already flat.
     */
    @Test
    void a_document_re_saved_at_a_different_line_width_keeps_its_paragraph_ids() {
        run("notes.md", "Retries are budgeted\nper run.\n\nAlpha is unrelated.");
        Map<Integer, UUID> before = paragraphIds();

        run("notes.md", "Retries are\nbudgeted per run.\n\nAlpha is\nunrelated.");

        assertEquals(before, paragraphIds());
    }

    /** And an edit moves exactly one id, so a citation to the edited paragraph
     *  resolves to nothing rather than quietly to different words. */
    @Test
    void an_edit_through_the_pipeline_moves_one_id_and_leaves_the_rest() {
        run("notes.md", "Retries are budgeted per run.\n\nAlpha is unrelated.");
        Map<Integer, UUID> before = paragraphIds();

        run("notes.md", "Retries are budgeted per turn.\n\nAlpha is unrelated.");
        Map<Integer, UUID> after = paragraphIds();

        assertNotEquals(before.get(1), after.get(1));
        assertEquals(before.get(2), after.get(2));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM paragraphs WHERE id = ?",
                Integer.class, before.get(1)));
    }

    /** Every paragraph in the corpus, ordinal to id. */
    private Map<Integer, UUID> paragraphIds() {
        Map<Integer, UUID> found = new LinkedHashMap<>();
        jdbc.query("SELECT ordinal, id FROM paragraphs ORDER BY ordinal",
                rs -> {
                    found.put(rs.getInt("ordinal"), (UUID) rs.getObject("id"));
                });
        return found;
    }

    /**
     * The pipeline with a cascade behind it, over the scripted chat endpoint.
     * {@code SummariserTest} is where the cascade itself is pinned; these are
     * about the seam.
     *
     * <p>The allowance arrives as a bound {@link DocumentsProperties} reading
     * through the real map, and not as a number: {@code ingest-budget} is live,
     * so what an ingest spends is a question asked of that object rather than a
     * number handed over at construction. Every caller but the two budget tests
     * writes nothing to the map and therefore gets exactly the allowance it
     * asked for.
     */
    private IngestService summarising(int allowance) {
        return summarising(boundTo(allowance));
    }

    /** The same pipeline over a properties object the caller keeps a handle on,
     *  for the two tests that assert what that object answers after the ingest
     *  has run. */
    private IngestService summarising(DocumentsProperties documents) {
        io.aeyer.plowshare.server.agents.JobRuntime runtime =
                new io.aeyer.plowshare.server.agents.JobRuntime(chat.dispatcher(), List.of());
        java.util.Map<String, io.aeyer.plowshare.server.agents.AgentDefinition> agents =
                new java.util.HashMap<>();
        for (String name : List.of(Summariser.PARAGRAPH, Summariser.SPAN, Summariser.SECTION,
                Summariser.CHAPTER, Summariser.DOCUMENT)) {
            agents.put(name, new io.aeyer.plowshare.server.agents.AgentDefinition(
                    name, "summarises", "fast", List.of(), List.of(), List.of(), 1, 1,
                    "You are the " + name + "."));
        }
        io.aeyer.plowshare.server.agents.AgentRegistry registry =
                new io.aeyer.plowshare.server.agents.AgentRegistry(agents);
        Summariser summariser = new Summariser(store, runtime, () -> registry, 3);
        return new IngestService(store, embeddings, Clock.fixed(NOW, ZoneOffset.UTC),
                ShippedChunking.SHIPPED, BATCH, summariser, documents);
    }

    /** A properties object bound to {@code allowance}, reading through this
     *  test's map — the shape {@code DocumentsConfig} hands the pipeline. */
    private DocumentsProperties boundTo(int allowance) {
        DocumentsProperties props = new DocumentsProperties();
        props.setIngestBudget(allowance);
        props.setLive(config);
        return props;
    }
}
