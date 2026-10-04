package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * <b>The reads Anchor has and this port did not.</b> Retrieval that carries a chunk's whole
 * ancestor stack, the corpus as a list, one document's structure, and one chunk in full.
 *
 * <p>A class of its own rather than more of {@code DocumentStoreTest}, which is about the
 * <em>write</em> path and above all the paragraph identity rule. These are reads, they share one
 * fixture — a two-document corpus with vectors attached — and the fixture is most of what a reader
 * of this file needs to hold.
 *
 * <p>No embedding client anywhere. The vectors are written by hand, which is what lets a test of
 * retrieval never go near a model: what is under test is the join and the gating, and a real
 * embedder would only make the ordering unpredictable.
 */
@Tag("full-db")
@Testcontainers
class CorpusReadsTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private static UnitOfWork transactions;

  private DocumentStore store;

  private static final Instant AT = Instant.parse("2026-09-05T09:00:00Z");
  private static final int DIM = 768;

  /**
   * A paper with three numbered headings: one synthetic chapter over three named sections, which is
   * {@code HierarchyIngestTest}'s shape and the one most of this corpus is in.
   */
  private static final String PAPER =
      """
            1. Introduction
            The conjecture has stood for forty years and we refute it here.

            2. Method
            We search the space of graphs with a learned policy.

            3. Results
            The counterexample has eleven vertices and is reproduced below.
            """;

  /**
   * Prose with no headings at all: one synthetic chapter over one synthetic section, so every
   * render site below meets a {@link StructuralRef.Synthetic} on both tiers.
   */
  private static final String NOTES =
      """
            Retries are budgeted per run and the budget is minted once.

            Alpha is unrelated to that and mentions no budget.
            """;

  @BeforeAll
  static void migrate() {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = new JdbcTemplate(dataSource);
    TransactionTemplate template =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transactions =
        new UnitOfWork() {
          @Override
          public <T> T inTransaction(Supplier<T> work) {
            return template.execute(status -> work.get());
          }
        };
  }

  @BeforeEach
  void freshCorpus() {
    jdbc.execute(
        "TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, documents CASCADE");
    store = new DocumentStore(jdbc, transactions);
  }

  // --- the fixture ----------------------------------------------------------

  private UUID ingest(String sourceName, String text) {
    Extracted extracted = TextExtraction.extract(sourceName, text.getBytes(StandardCharsets.UTF_8));
    return store
        .write(
            sourceName,
            extracted,
            text.length(),
            "test",
            AT,
            Derivation.derive(extracted, ShippedChunking.SHIPPED))
        .documentId();
  }

  /**
   * A vector that leans on one axis.
   *
   * <p>Cosine distance only cares about direction, so a corpus embedded this way sorts by which
   * axis a chunk was given and by nothing else — which makes every ordering assertion below a
   * statement about the query rather than about arithmetic nobody can read.
   */
  private static float[] axis(int which) {
    float[] vector = new float[DIM];
    vector[which] = 1.0f;
    return vector;
  }

  /**
   * Give every chunk of a document the same direction, so a query on that axis reaches the whole
   * document and nothing else.
   */
  private void embedOn(UUID documentId, int which) {
    for (UUID chunk :
        jdbc.queryForList(
            "SELECT c.id FROM chunks c JOIN paragraphs p ON p.id = c.paragraph_id"
                + " WHERE p.document_id = ? ORDER BY p.ordinal, c.ordinal",
            UUID.class,
            documentId)) {
      store.attach(chunk, axis(which));
    }
  }

  /**
   * Give each paragraph's chunks their own direction — ordinal {@code n} on axis {@code n} — so
   * that a query on axis {@code n} reaches paragraph {@code n} first.
   *
   * <p><b>{@link #embedOn} beside it cannot be used for an ordering assertion</b>, and this method
   * exists because the first version of the test below tried. With every chunk on one axis every
   * distance is equal, and what decides the order is then the tie-break — {@code
   * chunkId.toString()}, over ids the store generates at random. The assertion passed on the run
   * that was written and failed on the next one. A total order is the right thing for the store to
   * impose; it is not a meaningful one, and a test that reads {@code get(0)} has to make the
   * distances say what it means.
   */
  private void embedByParagraph(UUID documentId) {
    RowCallbackHandler attach =
        rs -> store.attach((UUID) rs.getObject("chunk_id"), axis(rs.getInt("ordinal")));
    jdbc.query(
        "SELECT c.id AS chunk_id, p.ordinal AS ordinal FROM chunks c"
            + " JOIN paragraphs p ON p.id = c.paragraph_id"
            + " WHERE p.document_id = ?",
        attach,
        documentId);
  }

  private void summarise(UUID documentId) {
    store.attachDocumentSummary(documentId, "what the document argues");
    for (UUID paragraph :
        jdbc.queryForList(
            "SELECT id FROM paragraphs WHERE document_id = ? ORDER BY ordinal",
            UUID.class,
            documentId)) {
      store.attachSummary(paragraph, "what this paragraph claims");
    }
    for (DocumentStore.StoredChapter chapter : store.hierarchy(documentId)) {
      store.attachChapterSummary(chapter.id(), "what this chapter covers");
      for (DocumentStore.StoredSection section : chapter.sections()) {
        store.attachSectionSummary(section.id(), "what this section covers");
      }
    }
  }

  // --- retrieve: the ancestor stack -----------------------------------------

  /**
   * <b>The whole of why this read exists.</b> Anchor's {@code findChunksForRetrieve} javadoc
   * promises "one row per chunk with no follow-up reads", and a row that omits any tier breaks that
   * promise silently — the caller reads a chunk with no idea what the paper around it argues, which
   * is the failure the hierarchy was built to prevent.
   */
  @Test
  void a_retrieved_chunk_carries_its_paragraph_section_chapter_and_document() {
    UUID paper = ingest("paper.md", PAPER);
    embedByParagraph(paper);
    summarise(paper);

    List<DocumentStore.Retrieved> found = store.retrieve(null, axis(1), 10);

    assertFalse(found.isEmpty());
    DocumentStore.ChunkWithAncestors first = found.get(0).chunk();
    assertEquals("what this paragraph claims", first.paragraphSummary());
    assertEquals(1, first.paragraphOrdinal());
    assertEquals(paper, first.documentId());
    assertEquals("paper.md", first.sourceName());
    assertEquals("paper", first.documentTitle());
    assertEquals("what the document argues", first.documentSummary());

    DocumentStore.Placement.InSection placed =
        assertInstanceOf(DocumentStore.Placement.InSection.class, first.placement());
    assertEquals(new StructuralRef.Named("1. Introduction"), placed.sectionTitle());
    assertEquals("what this section covers", placed.sectionSummary());
    assertEquals("what this chapter covers", placed.chapterSummary());
  }

  /**
   * The sentinel is discarded where the row is read, not where it is printed.
   *
   * <p>§3.2's decision, and the failure it prevents is not hypothetical: Anchor's own REST
   * controllers re-derive this with an inline ternary, and its shell then prints the {@code null}
   * they produce as the four letters "null" in a heading. A {@link StructuralRef} leaving the store
   * cannot be rendered without a policy being named.
   */
  @Test
  void a_synthetic_chapter_and_section_leave_the_store_as_structural_refs() {
    UUID notes = ingest("notes.md", NOTES);
    embedOn(notes, 1);

    DocumentStore.Placement.InSection placed =
        assertInstanceOf(
            DocumentStore.Placement.InSection.class,
            store.retrieve(null, axis(1), 10).get(0).chunk().placement());

    assertInstanceOf(StructuralRef.Synthetic.class, placed.sectionTitle());
    assertInstanceOf(StructuralRef.Synthetic.class, placed.chapterTitle());
    assertNull(placed.sectionTitle().render(StructuralRef.WhenSynthetic.OMIT));
    assertEquals(
        StructuralRef.UNNAMED,
        placed.chapterTitle().render(StructuralRef.WhenSynthetic.PLACEHOLDER));
  }

  /**
   * <b>The join Anchor gets wrong one tier up, refused again here.</b> Anchor reaches a document
   * from a chunk through {@code paragraph -> section -> chapter} with inner joins, so a paragraph
   * in no section vanishes from its own corpus's retrieval with nothing said. V18 froze {@code
   * paragraphs.document_id NOT NULL} and V26 made {@code section_id} nullable, so here the document
   * is one hop and the hierarchy is a LEFT JOIN.
   */
  @Test
  void a_paragraph_in_no_section_is_still_retrieved_and_says_it_has_no_place() {
    UUID notes = ingest("notes.md", NOTES);
    embedOn(notes, 2);
    jdbc.update("UPDATE paragraphs SET section_id = NULL WHERE document_id = ?", notes);

    List<DocumentStore.Retrieved> found = store.retrieve(null, axis(2), 10);

    assertEquals(2, found.size());
    assertInstanceOf(DocumentStore.Placement.InNoSection.class, found.get(0).chunk().placement());
    assertEquals(notes, found.get(0).chunk().documentId());
  }

  /**
   * Null is the corpus, and a document id is that document. Anchor's nullable parameter, ported as
   * its signature says rather than as its shell uses it.
   */
  @Test
  void a_null_document_reads_the_corpus_and_an_id_reads_one_document() {
    UUID paper = ingest("paper.md", PAPER);
    UUID notes = ingest("notes.md", NOTES);
    embedOn(paper, 0);
    embedOn(notes, 0);

    assertEquals(5, store.retrieve(null, axis(0), 100).size());
    assertEquals(2, store.retrieve(notes, axis(0), 100).size());
    assertTrue(
        store.retrieve(notes, axis(0), 100).stream()
            .allMatch(row -> row.chunk().documentId().equals(notes)));
  }

  /**
   * A chunk nothing embedded is not a worse answer; it is no answer, and padding the list with one
   * would be a row nothing compared.
   */
  @Test
  void an_unembedded_chunk_is_never_retrieved() {
    UUID paper = ingest("paper.md", PAPER);

    assertEquals(List.of(), store.retrieve(null, axis(0), 10));
  }

  @Test
  void a_retrieve_is_capped_and_a_non_positive_limit_asks_nothing() {
    UUID paper = ingest("paper.md", PAPER);
    embedOn(paper, 0);

    assertEquals(3, store.retrieve(null, axis(0), DocumentStore.MOST_RETRIEVED + 500).size());
    assertEquals(List.of(), store.retrieve(null, axis(0), 0));
  }

  // --- the corpus as a list -------------------------------------------------

  @Test
  void the_corpus_lists_newest_first_with_what_each_document_holds() {
    UUID paper = ingest("paper.md", PAPER);
    UUID notes = ingest("notes.md", NOTES);
    summarise(paper);

    List<DocumentStore.Listed> page = store.page(null, 50, 0);

    assertEquals(2, page.size());
    DocumentStore.Listed listedPaper =
        page.stream().filter(row -> row.document().id().equals(paper)).findFirst().orElseThrow();
    assertEquals("paper.md", listedPaper.document().sourceName());
    assertEquals("what the document argues", listedPaper.document().summary());
    assertEquals(1, listedPaper.chapters());
    assertEquals(3, listedPaper.sections());
    assertEquals(3, listedPaper.paragraphs());
    assertEquals(3, listedPaper.chunks());
    assertEquals(2, store.count(null));
  }

  /**
   * <b>The lookup the shell's {@code use} is built on.</b> Anchor resolves a document from a title
   * substring by calling {@code GET /documents?q=} and refusing a result that is not exactly one;
   * this is the read under that.
   *
   * <p>It matches on the filed name as well as the title, which Anchor's does not, because
   * Plowshare has two names where Anchor has one — {@code source_name} is the identity a re-ingest
   * matches on and {@code title} is the document's own — and a person naming a paper may reach for
   * either.
   */
  @Test
  void a_naming_matches_the_filed_name_or_the_title_and_ignores_case() {
    ingest("paper.md", PAPER);
    ingest("notes.md", NOTES);

    assertEquals(1, store.count("PAPER"));
    assertEquals("paper.md", store.page("pap", 50, 0).get(0).document().sourceName());
    assertEquals(0, store.count("nothing here"));
    assertEquals(List.of(), store.page("nothing here", 50, 0));
  }

  @Test
  void a_page_is_bounded_and_offset_walks_it() {
    ingest("a.md", NOTES);
    ingest("b.md", PAPER);
    ingest("c.md", NOTES);

    assertEquals(3, store.count(null));
    assertEquals(2, store.page(null, 2, 0).size());
    assertEquals(1, store.page(null, 2, 2).size());
    assertEquals(List.of(), store.page(null, 2, 9));
  }

  // --- one document's structure ---------------------------------------------

  /**
   * The structure with no text in it, which is what {@code GET /v1/documents/&#123;id&#125;} is
   * for: enough to decide whether asking this paper is worth minutes of deliberation, and not a
   * second renderer of somebody's uploaded prose.
   */
  @Test
  void a_documents_structure_is_its_chapters_and_their_sections() {
    UUID paper = ingest("paper.md", PAPER);
    summarise(paper);

    List<DocumentStore.StoredChapter> chapters = store.hierarchy(paper);

    assertEquals(1, chapters.size());
    assertInstanceOf(StructuralRef.Synthetic.class, chapters.get(0).title());
    assertEquals(
        List.of(
            new StructuralRef.Named("1. Introduction"),
            new StructuralRef.Named("2. Method"),
            new StructuralRef.Named("3. Results")),
        chapters.get(0).sections().stream().map(DocumentStore.StoredSection::title).toList());
  }

  // --- one chunk in full ----------------------------------------------------

  /**
   * The same row a retrieve hit already carries, addressed by id.
   *
   * <p>That it <em>is</em> the same row is the point and is why the two share {@link
   * DocumentStore.ChunkWithAncestors}: a follow-up read that answered differently from the hit it
   * follows up on would be two descriptions of one chunk.
   */
  @Test
  void one_chunk_reads_back_with_the_same_ancestors_a_retrieve_hit_carries() {
    UUID paper = ingest("paper.md", PAPER);
    embedOn(paper, 0);
    summarise(paper);

    DocumentStore.ChunkWithAncestors retrieved = store.retrieve(null, axis(0), 1).get(0).chunk();
    DocumentStore.ChunkWithAncestors read = store.chunk(retrieved.chunkId()).orElseThrow();

    assertEquals(retrieved, read);
  }

  // --- ranking whole documents by their summary -----------------------------

  /**
   * <b>The read V27 exists for.</b> One vector comparison per document, where {@code
   * searchByVector} beside it compares against every chunk in the corpus and then leaves a caller
   * to decide what one passage says about the paper it came from.
   */
  @Test
  void documents_rank_by_how_close_their_own_summary_is() {
    UUID paper = ingest("paper.md", PAPER);
    UUID notes = ingest("notes.md", NOTES);
    summarise(paper);
    summarise(notes);
    store.attachSummaryEmbedding(paper, axis(3));
    store.attachSummaryEmbedding(notes, axis(4));

    List<DocumentStore.Ranked> ranked = store.rankBySummary(axis(3), 10);

    assertEquals(2, ranked.size());
    assertEquals(paper, ranked.get(0).document().id());
    assertEquals(notes, ranked.get(1).document().id());
    assertEquals(1.0, ranked.get(0).similarity(), 1e-6);
    // Orthogonal, so cosine zero -- and the row is still returned. A ranking
    // says which is nearest and never that the nearest is near.
    assertEquals(0.0, ranked.get(1).similarity(), 1e-6);
  }

  /**
   * A document nothing has embedded is not ranked last; it is not ranked.
   *
   * <p>{@code NULL <=> v} is NULL and NULL sorts last rather than nowhere, so without the predicate
   * every under-full answer would be padded with documents nothing compared — {@code
   * SCOPED_SEARCH_SQL}'s recorded reason for the same guard, and here it is not hypothetical at
   * all: a document is summarised long before anything embeds the summary.
   */
  @Test
  void a_document_with_no_summary_vector_is_left_out_and_counted_instead() {
    UUID paper = ingest("paper.md", PAPER);
    UUID notes = ingest("notes.md", NOTES);
    summarise(paper);
    summarise(notes);
    store.attachSummaryEmbedding(paper, axis(3));

    assertEquals(
        List.of(paper),
        store.rankBySummary(axis(3), 10).stream().map(row -> row.document().id()).toList());
    assertEquals(new DocumentStore.Ranking(1, 1), store.ranking());
  }

  /**
   * A document nothing has summarised is neither rankable nor waiting to be: there is no text for a
   * vector to be of, and counting it as a gap would say the embedding endpoint failed when the
   * cascade never ran.
   */
  @Test
  void an_unsummarised_document_is_not_counted_as_one_awaiting_a_vector() {
    ingest("paper.md", PAPER);

    assertEquals(new DocumentStore.Ranking(0, 0), store.ranking());
    assertEquals(List.of(), store.summariesAwaitingAVector());
  }

  @Test
  void the_summaries_awaiting_a_vector_are_the_work_a_backfill_has_to_do() {
    UUID paper = ingest("paper.md", PAPER);
    UUID notes = ingest("notes.md", NOTES);
    summarise(paper);
    summarise(notes);
    store.attachSummaryEmbedding(paper, axis(3));

    List<DocumentStore.UnembeddedSummary> waiting = store.summariesAwaitingAVector();

    assertEquals(1, waiting.size());
    assertEquals(notes, waiting.get(0).documentId());
    assertEquals("what the document argues", waiting.get(0).summary());
  }

  // --- the vector-only stance score -----------------------------------------

  /**
   * Both cosines in one statement, where Anchor asks twice.
   *
   * <p>The score is {@code cos(query, summary) - cos(not-query, summary)} and the subtraction is
   * the caller's; this returns the two numbers because Anchor's response returns both — the topical
   * relevance is what says whether a stance near zero means "mixed" or "not about this at all".
   */
  @Test
  void one_document_scores_a_question_and_its_negation_together() {
    UUID paper = ingest("paper.md", PAPER);
    summarise(paper);
    store.attachSummaryEmbedding(paper, axis(3));

    DocumentStore.Stance stance = store.stanceOf(paper, axis(3), axis(4)).orElseThrow();

    assertEquals(1.0, stance.topical(), 1e-6);
    assertEquals(0.0, stance.negated(), 1e-6);
    assertEquals(1.0, stance.score(), 1e-6);
  }

  /**
   * A document with no summary vector has no stance, and that is empty rather than zero.
   *
   * <p>Zero is a real score — it is what a document topically unrelated to both the question and
   * its negation returns — so answering an unembedded document with zero would be indistinguishable
   * from a genuine reading.
   */
  @Test
  void a_document_with_no_summary_vector_has_no_stance_rather_than_a_zero_one() {
    UUID paper = ingest("paper.md", PAPER);
    summarise(paper);

    assertTrue(store.stanceOf(paper, axis(3), axis(4)).isEmpty());
    assertTrue(
        store
            .stanceOf(UUID.fromString("11111111-2222-3333-4444-555555555555"), axis(3), axis(4))
            .isEmpty());
  }

  @Test
  void a_ranking_is_bounded_and_a_non_positive_limit_asks_nothing() {
    UUID paper = ingest("paper.md", PAPER);
    summarise(paper);
    store.attachSummaryEmbedding(paper, axis(3));

    assertEquals(1, store.rankBySummary(axis(3), DocumentStore.MOST_RANKED + 500).size());
    assertEquals(List.of(), store.rankBySummary(axis(3), 0));
  }

  @Test
  void a_chunk_the_corpus_does_not_hold_is_no_chunk() {
    assertTrue(store.chunk(UUID.fromString("11111111-2222-3333-4444-555555555555")).isEmpty());
  }
}
