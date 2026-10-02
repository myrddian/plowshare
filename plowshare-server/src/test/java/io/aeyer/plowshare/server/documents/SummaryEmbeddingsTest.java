package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.EmbeddingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * <b>The write path V27's column has</b>, and above all what it does when the
 * embedding endpoint is not there.
 *
 * <p>That is the whole subject. Attaching a vector to a row is one statement and
 * {@code CorpusReadsTest} already pins that it reads back; what needs a class is
 * the decision underneath it — <b>a summary that cost a model call is never lost
 * to an embedding that failed</b> — and the two paths that decision has to hold
 * on: the one that runs at the end of a cascade, and the one that runs at
 * startup and finds what the first one could not finish.
 *
 * <p>No model anywhere. The client is a stub that answers with whatever the test
 * set, including by failing the way an unreachable one fails, which is the only
 * way to test a swallow.
 */
@Testcontainers
class SummaryEmbeddingsTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static JdbcTemplate jdbc;
    private static UnitOfWork transactions;

    private static final int DIM = 768;
    private static final Instant AT = Instant.parse("2026-09-05T09:00:00Z");
    private static final String MODEL = "test-embedder";

    private DocumentStore store;
    private StubEmbedder embedder;
    private SummaryEmbeddings summaryVectors;

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

    @BeforeEach
    void freshCorpus() {
        jdbc.execute("TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, documents CASCADE");
        store = new DocumentStore(jdbc, transactions);
        embedder = new StubEmbedder();
        summaryVectors = new SummaryEmbeddings(store, embedder, MODEL, DIM);
    }

    private UUID summarised(String sourceName) {
        Extracted extracted = TextExtraction.extract(sourceName,
                "Something is claimed here.".getBytes(StandardCharsets.UTF_8));
        UUID id = store.write(sourceName, extracted, 26L, "test", AT,
                Derivation.derive(extracted, ShippedChunking.SHIPPED)).documentId();
        store.attachDocumentSummary(id, "what " + sourceName + " argues");
        return id;
    }

    // --- the cascade's own write ----------------------------------------------

    @Test
    void a_summary_written_by_a_cascade_is_rankable_at_once() {
        UUID paper = summarised("paper.md");

        assertTrue(summaryVectors.attach(paper, "what paper.md argues"));

        assertEquals(new DocumentStore.Ranking(1, 0), store.ranking());
        assertEquals(List.of("what paper.md argues"), embedder.embedded);
    }

    /**
     * <b>The decision this class exists for.</b> The summary is already
     * committed and cost a model call; an unreachable embedding endpoint must
     * not take it away.
     */
    @Test
    void an_embedding_that_fails_costs_a_ranking_and_never_a_summary() {
        UUID paper = summarised("paper.md");
        embedder.failWith(new EmbeddingException("nothing is listening on 1234"));

        assertFalse(summaryVectors.attach(paper, "what paper.md argues"));

        assertEquals("what paper.md argues", store.documentSummary(paper));
        assertEquals(new DocumentStore.Ranking(0, 1), store.ranking());
    }

    /** A cascade that stopped before the document level has no summary to
     *  embed, and that is not a failure to report. */
    @Test
    void a_blank_summary_is_not_embedded_and_is_not_an_error() {
        UUID paper = summarised("paper.md");

        assertFalse(summaryVectors.attach(paper, null));
        assertFalse(summaryVectors.attach(paper, "  "));
        assertEquals(List.of(), embedder.embedded);
    }

    // --- the standing repair --------------------------------------------------

    @Test
    void the_backfill_finds_every_summary_that_has_no_vector() {
        UUID one = summarised("one.md");
        UUID two = summarised("two.md");
        summaryVectors.attach(one, "what one.md argues");

        assertEquals(1, summaryVectors.fill());

        assertEquals(new DocumentStore.Ranking(2, 0), store.ranking());
        assertEquals(List.of("what two.md argues"), embedder.batched);
    }

    @Test
    void a_backfill_with_nothing_to_do_asks_the_embedder_nothing() {
        summarised("one.md");
        summaryVectors.fill();
        embedder.forget();

        assertEquals(0, summaryVectors.fill());
        assertEquals(List.of(), embedder.batched);
    }

    /** The pass runs again next startup and the corpus says how much is owed in
     *  the meantime, which is what stops an empty ranking reading as an empty
     *  corpus. */
    @Test
    void a_backfill_that_cannot_reach_the_endpoint_leaves_the_work_and_says_so() {
        summarised("one.md");
        summarised("two.md");
        embedder.failWith(new EmbeddingException("nothing is listening on 1234"));

        assertEquals(0, summaryVectors.fill());

        assertEquals(new DocumentStore.Ranking(0, 2), store.ranking());
        assertEquals(2, store.summariesAwaitingAVector().size());
    }

    /**
     * A short answer is skipped whole rather than paired up by position.
     *
     * <p>Anchor skips it too and is right to: pairing three vectors with four
     * documents by index attaches one paper's summary vector to another paper's
     * row, which is a <em>wrong</em> answer where the skip is a missing one.
     */
    @Test
    void a_batch_that_comes_back_short_is_skipped_rather_than_paired_up() {
        summarised("one.md");
        summarised("two.md");
        embedder.answerWith(1);

        assertEquals(0, summaryVectors.fill());
        assertEquals(new DocumentStore.Ranking(0, 2), store.ranking());
    }

    /**
     * A vector of the wrong width is refused and logged rather than thrown.
     *
     * <p>{@code RetrievalService}'s three copies of this check throw, because
     * there a caller asked a question and has to be told it was never asked.
     * Nobody asked for this pass, and a wrong width is a misconfiguration that
     * would be identically wrong on every row.
     */
    @Test
    void a_vector_of_the_wrong_width_writes_nothing_and_does_not_throw() {
        UUID paper = summarised("paper.md");
        embedder.widthIs(512);

        assertFalse(summaryVectors.attach(paper, "what paper.md argues"));
        assertEquals(0, summaryVectors.fill());
        assertEquals(new DocumentStore.Ranking(0, 1), store.ranking());
    }

    // --- the stub -------------------------------------------------------------

    /** Answers with whatever the test set, including by failing the way an
     *  unreachable endpoint fails. */
    private static final class StubEmbedder implements EmbeddingClient {

        final List<String> embedded = new ArrayList<>();
        final List<String> batched = new ArrayList<>();

        private EmbeddingException failure;
        private int width = DIM;
        private Integer answerCount;

        void failWith(EmbeddingException e) {
            this.failure = e;
        }

        void widthIs(int width) {
            this.width = width;
        }

        /** Answer a batch with this many vectors however many were asked for. */
        void answerWith(int count) {
            this.answerCount = count;
        }

        void forget() {
            embedded.clear();
            batched.clear();
        }

        @Override
        public float[] embed(String text) {
            if (failure != null) {
                throw failure;
            }
            embedded.add(text);
            return new float[width];
        }

        @Override
        public List<float[]> embedAll(List<String> texts) {
            if (failure != null) {
                throw failure;
            }
            batched.addAll(texts);
            int count = answerCount == null ? texts.size() : answerCount;
            List<float[]> vectors = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                vectors.add(new float[width]);
            }
            return vectors;
        }
    }
}
