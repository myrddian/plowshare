package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.EmbeddingException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The read half of the corpus, against a mocked store and a stub endpoint.
 *
 * <p><b>No container and no model.</b> What the SQL does is {@code
 * DocumentStoreTest}'s subject against a real Postgres; what this class is for
 * is narrower and is the half that has no schema behind it — is the question
 * embedded by the corpus's own client, is the bound this service's rather than
 * each surface's, and is an empty answer ever allowed to be a claim nobody
 * checked.
 */
class RetrievalServiceTest {

    private static final String MODEL = "nomic-embed-text";

    private DocumentStore store;
    private EmbeddingClient embeddings;
    private RetrievalService retrieval;

    @BeforeEach
    void setUp() {
        store = mock(DocumentStore.class);
        embeddings = mock(EmbeddingClient.class);
        when(embeddings.embed(any())).thenReturn(vector(768));
        when(store.coverage()).thenReturn(new DocumentStore.Coverage(12, 0));
        when(store.searchByVector(any(), anyInt())).thenReturn(List.of(hit("Alpha.")));
        when(store.searchByText(any(), any(), anyInt())).thenReturn(List.of());
        retrieval = new RetrievalService(store, embeddings, MODEL, 768);
    }

    @Test
    void scoped_queries_keep_requester_and_project_accounting_through_configuration_copy() {
        var access = mock(io.aeyer.plowshare.server.information.InformationAccess.class);
        var context = new io.aeyer.plowshare.server.information.InformationContext("alice",
                io.aeyer.plowshare.server.information.InformationContext.Selection.project("research"));
        when(store.scoped(access, context)).thenReturn(store);
        when(store.embeddingSpace(MODEL, 768)).thenReturn(store);
        var owner = io.aeyer.plowshare.server.llm.accounting.UsageAttribution.project("alice", "stable-project-id",
                io.aeyer.plowshare.server.llm.accounting.UsageAttribution.Operation.EMBEDDING_QUERY);
        retrieval.useUsageOwners((home, account, operation) -> {
            assertEquals("research", home.project());
            assertEquals("alice", account);
            return owner;
        });
        when(embeddings.embed("owned question", owner)).thenReturn(vector(768));
        retrieval.scoped(access, context).checkingConfiguration().search("owned question", 5);
        verify(embeddings).embed("owned question", owner);
        verify(embeddings, never()).embed("owned question");
    }

    // --- the model coupling ---------------------------------------------------

    /**
     * <b>The question is embedded here, by the corpus's own client, and the
     * vector goes straight to the store.</b>
     *
     * <p>This is the whole of the model coupling: there is no second door. The
     * store's {@code searchByVector} is package-private and this service is the
     * package's only public read, and it takes prose. A caller cannot hand over
     * a vector it computed somewhere else, which is the failure that has no
     * symptom — a query embedded by a different model is not a worse ranking, it
     * is a ranking of nothing, and every row comes back looking right.
     */
    @Test
    void the_question_is_embedded_by_the_corpus_own_client() {
        float[] answered = vector(768);
        answered[3] = 1f;
        when(embeddings.embed("where does authentication happen")).thenReturn(answered);

        retrieval.search("where does authentication happen", 5);

        ArgumentCaptor<float[]> sent = ArgumentCaptor.forClass(float[].class);
        verify(store).searchByVector(sent.capture(), anyInt());
        assertSame(answered, sent.getValue());
    }

    /**
     * A width the schema cannot hold is refused here, naming the setting.
     *
     * <p>Unreachable through the shipped {@code DispatchingEmbeddingClient},
     * which checks every batch itself — and reachable through the interface,
     * which promises nothing of the sort. Without this the failure is a {@code
     * DataAccessException} out of pgvector, several layers from the property
     * that caused it, on a read whose caller is often a model.
     */
    @Test
    void a_vector_the_schema_cannot_hold_is_refused_before_any_sql() {
        when(embeddings.embed(any())).thenReturn(vector(1024));

        EmbeddingException refused =
                assertThrows(EmbeddingException.class, () -> retrieval.search("anything", 5));

        assertTrue(refused.getMessage().contains("768"), refused.getMessage());
        assertTrue(refused.getMessage().contains("1024"), refused.getMessage());
        assertTrue(refused.getMessage().contains("plowshare.llm.embedding-dim"),
                refused.getMessage());
        assertTrue(refused.getMessage().contains(MODEL), refused.getMessage());
        verify(store, never()).searchByVector(any(), anyInt());
    }

    // --- the bound ------------------------------------------------------------

    /**
     * <b>The cap is this service's, and both numbers survive to the caller.</b>
     *
     * <p>One owner, because three surfaces need it: an agent tool, an HTTP
     * endpoint and an MCP tool clamping separately is the same number written
     * three times, and the copy that drifts is the one nobody reads.
     */
    @Test
    void the_cap_is_this_service_and_the_answer_carries_both_numbers() {
        RetrievalService.Found found = retrieval.search("anything", 500);

        assertEquals(500, found.asked());
        assertEquals(RetrievalService.MAX_HITS, found.limit());
        // The cap bounds what comes BACK. What is asked of each half is the
        // candidate depth, which is a different number and answers to the index
        // rather than to a prompt's size.
        verify(store).searchByVector(any(), org.mockito.ArgumentMatchers.eq(
                RetrievalService.CANDIDATE_DEPTH));
    }

    @Test
    void a_limit_within_the_cap_is_the_limit_that_was_asked_for() {
        RetrievalService.Found found = retrieval.search("anything", 3);

        assertEquals(3, found.asked());
        assertEquals(3, found.limit());
    }

    /**
     * Asking for none is refused rather than answered.
     *
     * <p>{@code MemoryTools.Recall} draws this line and the reason is the same
     * one word for word: asking for none is not the same as finding none, and
     * an answer with no hits in it is a claim about the corpus.
     */
    @Test
    void a_limit_of_none_is_refused_rather_than_reported_as_an_empty_corpus() {
        assertThrows(IllegalArgumentException.class, () -> retrieval.search("anything", 0));
        verify(store, never()).searchByVector(any(), anyInt());
    }

    @Test
    void a_question_with_nothing_in_it_is_refused_before_it_is_embedded() {
        assertThrows(IllegalArgumentException.class, () -> retrieval.search("   ", 5));
        verify(embeddings, never()).embed(any());
    }

    // --- what an empty answer is allowed to mean ------------------------------

    /**
     * What the search could not reach travels with the hits, always.
     *
     * <p>{@code Archive.Recall.unsearchable} at corpus scale. A document stored
     * while the embedding endpoint was down holds every word of its text and
     * answers no question, so a bare empty list would let a reader conclude the
     * corpus is empty of the subject rather than of the vectors.
     */
    @Test
    void every_answer_says_how_much_of_the_corpus_could_not_be_searched() {
        when(store.coverage()).thenReturn(new DocumentStore.Coverage(9, 4));

        RetrievalService.Found found = retrieval.search("anything", 5);

        assertEquals(9, found.searchable());
        assertEquals(4, found.unsearchable());
    }

    /**
     * <b>A corpus with nothing in it to search costs no model call.</b>
     *
     * <p>The coverage is read first for this: with no embedded chunk anywhere,
     * there is no ranking for a query vector to produce and paying an endpoint
     * to compute one would be a call made to answer a question already settled.
     * {@code Archive.recall}'s non-positive-limit clause is the same move for
     * the same reason.
     */
    @Test
    void an_empty_corpus_is_answered_without_calling_the_endpoint() {
        when(store.coverage()).thenReturn(new DocumentStore.Coverage(0, 0));

        RetrievalService.Found found = retrieval.search("anything", 5);

        assertTrue(found.hits().isEmpty());
        assertTrue(found.corpusIsEmpty());
        verify(embeddings, never()).embed(any());
        verify(store, never()).searchByVector(any(), anyInt());
        verify(store, never()).searchByText(any(), any(), anyInt());
    }

    /**
     * And a corpus that holds only unembedded text is <b>not</b> that case.
     *
     * <p>The two are one empty list and two entirely different facts: one is a
     * corpus nobody has filled and the other is a corpus nobody can ask. Only
     * the second is worth repairing, and a caller that could not tell them apart
     * would report the wrong one.
     */
    @Test
    void a_corpus_that_holds_only_unembedded_text_is_not_an_empty_corpus() {
        when(store.coverage()).thenReturn(new DocumentStore.Coverage(0, 7));

        RetrievalService.Found found = retrieval.search("anything", 5);

        assertTrue(found.hits().isEmpty());
        assertTrue(found.corpusIsEmpty());
        assertEquals(7, found.unsearchable());
        verify(embeddings, never()).embed(any());
    }

    // --- the two halves, and what is done with them ---------------------------

    /**
     * <b>A search asks both halves, and the question is embedded once for the
     * pair.</b>
     *
     * <p>The lexical half needs the vector too — not to rank by, but so that a
     * hit found on words carries the same cosine distance a hit found on meaning
     * does, and one fused list has one number in it. Embedding the question twice
     * would be two calls to a model endpoint for one question.
     */
    @Test
    void a_search_asks_both_halves_and_embeds_the_question_once() {
        retrieval.search("how is the retry budget refilled", 5);

        verify(embeddings, org.mockito.Mockito.times(1)).embed(any());
        verify(store).searchByVector(any(), anyInt());
        verify(store).searchByText(
                org.mockito.ArgumentMatchers.eq("how is the retry budget refilled"),
                any(), anyInt());
    }

    /**
     * <b>Both halves are asked for the same depth, and the number is pgvector's
     * rather than a preference.</b>
     *
     * <p>{@code hnsw.ef_search} defaults to 40 and an HNSW scan returns at most
     * that many rows, so a vector candidate pool deeper than 40 comes back short
     * with no error — there is no honest way to ask for more without moving that
     * setting first. The lexical half is asked for the same number because
     * reciprocal rank fusion over two lists of different depths is not symmetric:
     * the deeper list can award a small score to a chunk the shallower one had no
     * position left to award anything to at all.
     */
    @Test
    void both_halves_are_asked_for_the_same_depth_and_it_is_the_index_ceiling() {
        retrieval.search("anything", 5);

        verify(store).searchByVector(any(),
                org.mockito.ArgumentMatchers.eq(RetrievalService.CANDIDATE_DEPTH));
        verify(store).searchByText(any(), any(),
                org.mockito.ArgumentMatchers.eq(RetrievalService.CANDIDATE_DEPTH));
    }

    /**
     * <b>Vector-only stays reachable, and it is asked for exactly what it is to
     * return.</b>
     *
     * <p>Not a courtesy to an old caller. With one mode there is no way to tell a
     * lexical half that has regressed from one that is correctly quiet — a
     * conjunctive word search returns nothing for most well-formed questions by
     * design — so a hybrid answer and a vector answer that differ is the only
     * signal, and it needs both to exist. No fusion runs, so the depth is the
     * limit.
     */
    @Test
    void vector_only_is_still_reachable_and_asks_the_lexical_half_nothing() {
        RetrievalService.Found found =
                retrieval.search("anything", 5, RetrievalService.Mode.VECTOR);

        assertEquals(RetrievalService.Mode.VECTOR, found.mode());
        verify(store).searchByVector(any(), org.mockito.ArgumentMatchers.eq(5));
        verify(store, never()).searchByText(any(), any(), anyInt());
    }

    /** And the other half alone, which is how it is told apart from silence. */
    @Test
    void the_lexical_half_is_reachable_alone_too() {
        retrieval.search("anything", 5, RetrievalService.Mode.LEXICAL);

        verify(store).searchByText(any(), any(), org.mockito.ArgumentMatchers.eq(5));
        verify(store, never()).searchByVector(any(), anyInt());
    }

    /**
     * <b>When one half returns nothing the fused order is the other half's,
     * unchanged.</b>
     *
     * <p>This is the property reciprocal rank fusion was chosen for. Each hit's
     * score is a sum of {@code 1 / (k + rank)} terms, one per list it appears in,
     * and that term is strictly decreasing in rank — so a hit present in exactly
     * one list is ordered by its position in that list and nothing else. An empty
     * lexical half is therefore not a degraded search: it is today's search,
     * exactly, which is what makes the lexical half safe to add to a read three
     * surfaces already depend on.
     */
    @Test
    void an_empty_lexical_half_leaves_the_vector_order_untouched() {
        DocumentStore.Hit first = hit("Alpha.");
        DocumentStore.Hit second = hit("Beta.");
        DocumentStore.Hit third = hit("Gamma.");
        when(store.searchByVector(any(), anyInt())).thenReturn(List.of(first, second, third));
        when(store.searchByText(any(), any(), anyInt())).thenReturn(List.of());

        RetrievalService.Found found = retrieval.search("anything", 5);

        assertEquals(List.of(first, second, third), found.hits());
    }

    /** And the same in the other direction. */
    @Test
    void an_empty_vector_half_leaves_the_lexical_order_untouched() {
        DocumentStore.Hit first = hit("Alpha.");
        DocumentStore.Hit second = hit("Beta.");
        when(store.searchByVector(any(), anyInt())).thenReturn(List.of());
        when(store.searchByText(any(), any(), anyInt())).thenReturn(List.of(first, second));

        assertEquals(List.of(first, second), retrieval.search("anything", 5).hits());
    }

    /**
     * <b>Agreement between the two halves beats a first place on one of them.</b>
     *
     * <p>The whole of what fusion buys, in one assertion. Gamma is third on both
     * lists and scores {@code 1/63 + 1/63}; Alpha is first on one and absent from
     * the other and scores {@code 1/61}. Gamma wins, and it should: two
     * independent readings of the question both put it near the top, where Alpha
     * has one reading that liked it and one that did not retrieve it at all.
     */
    @Test
    void a_chunk_both_halves_liked_outranks_one_that_only_a_single_half_led_with() {
        DocumentStore.Hit alpha = hit("Alpha.");
        DocumentStore.Hit beta = hit("Beta.");
        DocumentStore.Hit gamma = hit("Gamma.");
        DocumentStore.Hit delta = hit("Delta.");
        DocumentStore.Hit epsilon = hit("Epsilon.");
        when(store.searchByVector(any(), anyInt())).thenReturn(List.of(alpha, beta, gamma));
        when(store.searchByText(any(), any(), anyInt()))
                .thenReturn(List.of(delta, epsilon, gamma));

        RetrievalService.Found found = retrieval.search("anything", 5);

        assertEquals(gamma, found.hits().get(0));
    }

    /** A chunk both halves returned is one hit and not two. */
    @Test
    void a_chunk_found_by_both_halves_appears_once() {
        DocumentStore.Hit alpha = hit("Alpha.");
        when(store.searchByVector(any(), anyInt())).thenReturn(List.of(alpha));
        when(store.searchByText(any(), any(), anyInt())).thenReturn(List.of(alpha));

        assertEquals(List.of(alpha), retrieval.search("anything", 5).hits());
    }

    /**
     * The fused list is cut to the caller's limit, and the cut is the last thing
     * that happens.
     *
     * <p>Both halves are read to the candidate depth first — a chunk that is
     * ninth on one list and second on the other is exactly the hit fusion exists
     * to promote, and it could not be promoted by a pair of lists already
     * truncated to five.
     */
    @Test
    void the_fused_answer_is_cut_to_the_limit_and_not_before() {
        List<DocumentStore.Hit> many = List.of(hit("a"), hit("b"), hit("c"), hit("d"), hit("e"));
        when(store.searchByVector(any(), anyInt())).thenReturn(many);
        when(store.searchByText(any(), any(), anyInt())).thenReturn(List.of());

        assertEquals(2, retrieval.search("anything", 2).hits().size());
    }

    /** Hybrid is what a caller that says nothing gets. */
    @Test
    void a_search_that_names_no_mode_is_hybrid() {
        assertEquals(RetrievalService.Mode.HYBRID, retrieval.search("anything", 5).mode());
    }

    // --- the per-document read the deliberation asks for ----------------------

    /**
     * <b>Scoped, and it is the same embedding client.</b>
     *
     * <p>The whole of this class's argument applies unchanged one scope down: a
     * question embedded by a model other than the corpus's does not fail, it
     * ranks nothing. So the deliberation does not hold an {@code
     * EmbeddingClient} of its own — it asks here, and {@code
     * DocumentStore.searchWithinDocument} stays package-private with this as its
     * one caller.
     */
    @Test
    void a_scoped_question_is_embedded_by_the_corpus_own_client_and_filtered_to_one_document() {
        UUID document = UUID.randomUUID();
        float[] answered = vector(768);
        answered[7] = 1f;
        when(embeddings.embed("what is the bound")).thenReturn(answered);
        when(store.searchWithinDocument(any(), any(), anyInt())).thenReturn(List.of());

        retrieval.within(document, "what is the bound", 15);

        ArgumentCaptor<float[]> asked = ArgumentCaptor.forClass(float[].class);
        verify(store).searchWithinDocument(org.mockito.ArgumentMatchers.eq(document),
                asked.capture(), org.mockito.ArgumentMatchers.eq(15));
        assertSame(answered, asked.getValue());
    }

    /**
     * <b>The corpus-wide coverage gate is not asked here, and that is the
     * point.</b>
     *
     * <p>{@code coverage} reports one number for what a search could reach
     * corpus-wide, and this read is about one document: a corpus that is mostly
     * unembedded says nothing about whether <em>this</em> paper is searchable,
     * and short-circuiting on it would refuse an answerable ask. {@code
     * DocumentStore.countUnembedded} is the per-document counterpart and it is
     * the deliberation's to ask.
     */
    @Test
    void a_scoped_read_does_not_ask_the_corpus_wide_coverage() {
        when(store.searchWithinDocument(any(), any(), anyInt())).thenReturn(List.of());

        retrieval.within(UUID.randomUUID(), "anything", 15);

        verify(store, never()).coverage();
    }

    /** The width check is this service's and applies to every question it
     *  embeds, not only the corpus-wide ones. */
    @Test
    void a_scoped_question_that_comes_back_the_wrong_width_is_refused() {
        when(embeddings.embed(any())).thenReturn(vector(384));

        assertThrows(EmbeddingException.class,
                () -> retrieval.within(UUID.randomUUID(), "anything", 15));
        verify(store, never()).searchWithinDocument(any(), any(), anyInt());
    }

    @Test
    void a_scoped_search_needs_a_question() {
        assertThrows(IllegalArgumentException.class,
                () -> retrieval.within(UUID.randomUUID(), "  ", 15));
    }

    private static float[] vector(int width) {
        float[] embedding = new float[width];
        embedding[0] = 1f;
        return embedding;
    }

    private static DocumentStore.Hit hit(String text) {
        return new DocumentStore.Hit(UUID.randomUUID(), text, 0.1, UUID.randomUUID(), text, 1,
                UUID.randomUUID(), "notes.md", "notes", null);
    }
}
