package io.aeyer.plowshare.server.api;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.documents.CitationStore;
import io.aeyer.plowshare.server.documents.Deliberation;
import io.aeyer.plowshare.server.documents.DocumentStore;
import io.aeyer.plowshare.server.documents.DocumentsProperties;
import io.aeyer.plowshare.server.documents.IngestService;
import io.aeyer.plowshare.server.documents.RetrievalService;
import io.aeyer.plowshare.server.documents.StructuralRef;
import io.aeyer.plowshare.server.documents.Vocabulary;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * <b>The four reads Anchor has and this port did not</b>, at the HTTP boundary.
 *
 * <p>{@code DocumentControllerTest}'s shape and a class of its own for {@code CorpusReadsTest}'s
 * reason: these are reads over one fixture, and what is under test is narrower than what the
 * pipeline does — does each route reach the right store method, does each refusal become the right
 * status, and <b>does a sentinel ever reach a JSON body</b>.
 *
 * <p>That last one is why this file exists at all rather than three more assertions in the class
 * next door. Anchor's four REST controllers each re-derive the synthetic rule from the raw flag
 * with an inline ternary, its own V5 claims {@code StructuralRef} "gates every render boundary",
 * and its shell then prints the {@code null} those ternaries produce as the four letters "null" in
 * a document's outline. This is the boundary that decision is about.
 */
class CorpusReadControllerTest {

  private static final UUID DOCUMENT = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID CHUNK = UUID.fromString("22222222-3333-4444-5555-666666666666");
  private static final UUID PARAGRAPH = UUID.fromString("33333333-4444-5555-6666-777777777777");
  private static final UUID SECTION = UUID.fromString("44444444-5555-6666-7777-888888888888");
  private static final UUID CHAPTER = UUID.fromString("55555555-6666-7777-8888-999999999999");

  private RetrievalService retrieval;
  private DocumentStore store;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    retrieval = mock(RetrievalService.class);
    store = mock(DocumentStore.class);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new DocumentController(
                    mock(IngestService.class),
                    mock(JobStore.class),
                    retrieval,
                    mock(CitationStore.class),
                    store,
                    mock(Deliberation.class),
                    new DocumentsProperties()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
  }

  // --- the fixture ----------------------------------------------------------

  private static DocumentStore.StoredDocument document() {
    return new DocumentStore.StoredDocument(
        DOCUMENT,
        "paper.md",
        "paper",
        "hash",
        "text",
        12L,
        Instant.parse("2026-09-05T09:00:00Z"),
        "test",
        "argues something",
        Vocabulary.SECTION);
  }

  private static DocumentStore.ChunkWithAncestors chunk(DocumentStore.Placement placement) {
    return new DocumentStore.ChunkWithAncestors(
        CHUNK,
        "The counterexample has eleven vertices.",
        PARAGRAPH,
        3,
        "what this paragraph claims",
        placement,
        DOCUMENT,
        "paper.md",
        "paper",
        "argues something");
  }

  private static DocumentStore.Placement named() {
    return new DocumentStore.Placement.InSection(
        SECTION,
        new StructuralRef.Named("3. Results"),
        "what this section covers",
        CHAPTER,
        new StructuralRef.Named("Part II"),
        "what this chapter covers");
  }

  private static DocumentStore.Placement synthetic() {
    return new DocumentStore.Placement.InSection(
        SECTION,
        new StructuralRef.Synthetic(),
        "what this section covers",
        CHAPTER,
        new StructuralRef.Synthetic(),
        "what this chapter covers");
  }

  // --- retrieve -------------------------------------------------------------

  @Test
  void a_retrieve_answers_with_every_tier_above_the_chunk() throws Exception {
    when(retrieval.retrieve(anyString(), isNull(), anyInt()))
        .thenReturn(List.of(new DocumentStore.Retrieved(chunk(named()), 0.25)));

    mvc.perform(
            post("/v1/documents/retrieve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"eleven vertices\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.query").value("eleven vertices"))
        .andExpect(jsonPath("$.limit").value(RetrievalService.DEFAULT_RETRIEVED))
        .andExpect(jsonPath("$.document").value(nullValue()))
        .andExpect(jsonPath("$.hits[0].score").value(0.75))
        .andExpect(jsonPath("$.hits[0].chunk.chunkId").value(CHUNK.toString()))
        .andExpect(jsonPath("$.hits[0].chunk.paragraphId").value(PARAGRAPH.toString()))
        .andExpect(jsonPath("$.hits[0].chunk.paragraphOrdinal").value(3))
        .andExpect(jsonPath("$.hits[0].chunk.paragraphSummary").value("what this paragraph claims"))
        .andExpect(jsonPath("$.hits[0].chunk.section.title").value("3. Results"))
        .andExpect(jsonPath("$.hits[0].chunk.section.synthetic").value(false))
        .andExpect(jsonPath("$.hits[0].chunk.chapter.title").value("Part II"))
        .andExpect(jsonPath("$.hits[0].chunk.sourceName").value("paper.md"))
        .andExpect(jsonPath("$.hits[0].chunk.documentSummary").value("argues something"));
  }

  /**
   * <b>The assertion this whole file is for.</b> A sentinel reaching an API response is the failure
   * {@code SyntheticTitles} exists to make impossible, and {@code title: null} beside {@code
   * synthetic: true} is what a reader is owed instead — told there is no title rather than told a
   * title that is not one.
   */
  @Test
  void a_synthetic_unit_is_a_null_title_and_a_flag_and_never_a_sentinel() throws Exception {
    when(retrieval.retrieve(anyString(), isNull(), anyInt()))
        .thenReturn(List.of(new DocumentStore.Retrieved(chunk(synthetic()), 0.5)));

    String body =
        mvc.perform(
                post("/v1/documents/retrieve")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"query\":\"anything\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hits[0].chunk.section.title").value(nullValue()))
            .andExpect(jsonPath("$.hits[0].chunk.section.synthetic").value(true))
            .andExpect(jsonPath("$.hits[0].chunk.chapter.title").value(nullValue()))
            .andExpect(jsonPath("$.hits[0].chunk.chapter.synthetic").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertEquals(-1, body.indexOf("__SYNTHETIC"), body);
  }

  /**
   * A chunk the hierarchy cannot place says so, where Anchor's inner join would have dropped the
   * row.
   */
  @Test
  void a_chunk_in_no_section_carries_no_section_and_no_chapter() throws Exception {
    when(retrieval.retrieve(anyString(), isNull(), anyInt()))
        .thenReturn(
            List.of(
                new DocumentStore.Retrieved(
                    chunk(new DocumentStore.Placement.InNoSection()), 0.5)));

    mvc.perform(
            post("/v1/documents/retrieve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"anything\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hits[0].chunk.section").value(nullValue()))
        .andExpect(jsonPath("$.hits[0].chunk.chapter").value(nullValue()))
        .andExpect(jsonPath("$.hits[0].chunk.chunkId").value(CHUNK.toString()));
  }

  @Test
  void a_retrieve_can_name_a_document_and_the_answer_echoes_it() throws Exception {
    when(retrieval.retrieve(anyString(), eq(DOCUMENT), anyInt())).thenReturn(List.of());

    mvc.perform(
            post("/v1/documents/retrieve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\",\"document\":\"" + DOCUMENT + "\",\"limit\":3}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.document").value(DOCUMENT.toString()))
        .andExpect(jsonPath("$.limit").value(3))
        .andExpect(jsonPath("$.hits").isEmpty());

    verify(retrieval).retrieve("q", DOCUMENT, 3);
  }

  @Test
  void a_retrieve_with_no_query_is_refused_rather_than_answered_with_nothing() throws Exception {
    mvc.perform(
            post("/v1/documents/retrieve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"  \"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void a_retrieve_asked_for_no_chunks_is_refused() throws Exception {
    mvc.perform(
            post("/v1/documents/retrieve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\",\"limit\":0}"))
        .andExpect(status().isBadRequest());
  }

  /**
   * A 400 and not a 404: a caller that sent something which is not an id has made a mistake it can
   * correct, and "no such document" would say the corpus was asked when nothing was. {@code
   * RequestedDocument.askedAbout}'s rule, applied to a body field rather than a path segment.
   */
  @Test
  void a_retrieve_naming_something_that_is_not_an_id_is_refused() throws Exception {
    mvc.perform(
            post("/v1/documents/retrieve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\",\"document\":\"the blue one\"}"))
        .andExpect(status().isBadRequest());
  }

  // --- the corpus as a list -------------------------------------------------

  @Test
  void the_corpus_lists_what_it_holds_and_how_much_of_each() throws Exception {
    when(store.page(isNull(), anyInt(), anyInt()))
        .thenReturn(List.of(new DocumentStore.Listed(document(), 1, 3, 12, 30)));
    when(store.count(isNull())).thenReturn(1);

    mvc.perform(get("/v1/documents"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.limit").value(DocumentController.DEFAULT_LISTED))
        .andExpect(jsonPath("$.offset").value(0))
        .andExpect(jsonPath("$.documents[0].documentId").value(DOCUMENT.toString()))
        .andExpect(jsonPath("$.documents[0].sourceName").value("paper.md"))
        .andExpect(jsonPath("$.documents[0].title").value("paper"))
        .andExpect(jsonPath("$.documents[0].summary").value("argues something"))
        .andExpect(jsonPath("$.documents[0].chapters").value(1))
        .andExpect(jsonPath("$.documents[0].sections").value(3))
        .andExpect(jsonPath("$.documents[0].paragraphs").value(12))
        .andExpect(jsonPath("$.documents[0].chunks").value(30));
  }

  @Test
  void a_listing_passes_its_naming_and_its_page_through() throws Exception {
    when(store.page(anyString(), anyInt(), anyInt())).thenReturn(List.of());
    when(store.count(anyString())).thenReturn(0);

    mvc.perform(get("/v1/documents?q=graph&limit=5&offset=10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.naming").value("graph"))
        .andExpect(jsonPath("$.documents").isEmpty());

    verify(store).page("graph", 5, 10);
    verify(store).count("graph");
  }

  @Test
  void a_listing_asked_for_no_documents_is_refused() throws Exception {
    mvc.perform(get("/v1/documents?limit=0")).andExpect(status().isBadRequest());
    mvc.perform(get("/v1/documents?offset=-1")).andExpect(status().isBadRequest());
  }

  // --- one document's structure ---------------------------------------------

  @Test
  void a_document_reads_back_as_its_chapters_and_their_sections() throws Exception {
    when(store.find(DOCUMENT)).thenReturn(Optional.of(document()));
    when(store.hierarchy(DOCUMENT))
        .thenReturn(
            List.of(
                new DocumentStore.StoredChapter(
                    CHAPTER,
                    new StructuralRef.Synthetic(),
                    "what this chapter covers",
                    List.of(
                        new DocumentStore.StoredSection(
                            SECTION,
                            new StructuralRef.Named("1. Introduction"),
                            "what this section covers")))));

    mvc.perform(get("/v1/documents/" + DOCUMENT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.documentId").value(DOCUMENT.toString()))
        .andExpect(jsonPath("$.sourceName").value("paper.md"))
        .andExpect(jsonPath("$.summary").value("argues something"))
        .andExpect(jsonPath("$.vocabulary").value("SECTION"))
        .andExpect(jsonPath("$.chapters[0].title").value(nullValue()))
        .andExpect(jsonPath("$.chapters[0].synthetic").value(true))
        .andExpect(jsonPath("$.chapters[0].summary").value("what this chapter covers"))
        .andExpect(jsonPath("$.chapters[0].sections[0].title").value("1. Introduction"))
        .andExpect(jsonPath("$.chapters[0].sections[0].synthetic").value(false));
  }

  /**
   * {@code /v1/documents/citations} is a route and not a document id, and Spring is asked to prove
   * it prefers the literal.
   */
  @Test
  void the_citations_route_is_not_read_as_a_document_id() throws Exception {
    mvc.perform(get("/v1/documents/citations")).andExpect(status().isOk());
  }

  @Test
  void a_document_the_corpus_does_not_hold_is_a_404() throws Exception {
    when(store.find(DOCUMENT)).thenReturn(Optional.empty());

    mvc.perform(get("/v1/documents/" + DOCUMENT)).andExpect(status().isNotFound());
  }

  @Test
  void a_path_that_is_not_a_document_id_is_refused_rather_than_missing() throws Exception {
    mvc.perform(get("/v1/documents/not-an-id")).andExpect(status().isBadRequest());
  }

  // --- one chunk in full ----------------------------------------------------

  /**
   * The follow-up read answers with the same object a retrieve hit carries.
   *
   * <p>Not a nicety: two shapes for one chunk are two things that can drift, and the whole claim of
   * Anchor's retrieve is that the follow-up read is unnecessary. Here it is the same record, so it
   * cannot say anything different.
   */
  @Test
  void one_chunk_reads_back_in_the_shape_a_retrieve_hit_carries() throws Exception {
    when(store.chunk(CHUNK)).thenReturn(Optional.of(chunk(named())));

    mvc.perform(get("/v1/documents/chunks/" + CHUNK))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.chunkId").value(CHUNK.toString()))
        .andExpect(jsonPath("$.paragraphId").value(PARAGRAPH.toString()))
        .andExpect(jsonPath("$.section.title").value("3. Results"))
        .andExpect(jsonPath("$.chapter.summary").value("what this chapter covers"))
        .andExpect(jsonPath("$.documentId").value(DOCUMENT.toString()));
  }

  // --- ranking whole documents ----------------------------------------------

  @Test
  void a_ranking_answers_with_documents_and_what_each_one_argues() throws Exception {
    when(retrieval.rank(anyString(), anyInt()))
        .thenReturn(
            new RetrievalService.Ranking(
                List.of(new DocumentStore.Ranked(document(), 0.25)),
                new DocumentStore.Ranking(1, 0)));

    mvc.perform(
            post("/v1/documents/rank")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"graph counterexamples\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.query").value("graph counterexamples"))
        .andExpect(jsonPath("$.limit").value(RetrievalService.DEFAULT_RANKED))
        .andExpect(jsonPath("$.documents[0].documentId").value(DOCUMENT.toString()))
        .andExpect(jsonPath("$.documents[0].sourceName").value("paper.md"))
        .andExpect(jsonPath("$.documents[0].summary").value("argues something"))
        .andExpect(jsonPath("$.documents[0].score").value(0.75))
        .andExpect(jsonPath("$.rankable").value(1))
        .andExpect(jsonPath("$.unranked").value(0));
  }

  /**
   * <b>The field that keeps an empty ranking honest.</b> A document is summarised before anything
   * embeds the summary, so an empty answer over a corpus full of papers is the ordinary state five
   * minutes after an ingest — and reporting it as "nothing is close" would be a claim about the
   * papers rather than about this server.
   */
  @Test
  void an_empty_ranking_says_how_many_documents_had_no_vector_to_compare() throws Exception {
    when(retrieval.rank(anyString(), anyInt()))
        .thenReturn(new RetrievalService.Ranking(List.of(), new DocumentStore.Ranking(0, 7)));

    mvc.perform(
            post("/v1/documents/rank")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"anything\",\"limit\":3}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.documents").isEmpty())
        .andExpect(jsonPath("$.rankable").value(0))
        .andExpect(jsonPath("$.unranked").value(7))
        .andExpect(jsonPath("$.limit").value(3));

    verify(retrieval).rank("anything", 3);
  }

  @Test
  void a_ranking_with_no_query_or_no_documents_asked_for_is_refused() throws Exception {
    mvc.perform(
            post("/v1/documents/rank")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\" \"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/v1/documents/rank")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"q\",\"limit\":0}"))
        .andExpect(status().isBadRequest());
  }

  // --- the vector-only stance -----------------------------------------------

  /** The reading, and the field that stops it being read as a judgment. */
  @Test
  void a_stance_carries_both_numbers_and_says_what_it_was_made_of() throws Exception {
    when(retrieval.stance(eq(DOCUMENT), anyString()))
        .thenReturn(Optional.of(new DocumentStore.Stance(0.62, 0.31)));

    mvc.perform(
            post("/v1/documents/" + DOCUMENT + "/stance")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"claim\":\"the conjecture holds\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.documentId").value(DOCUMENT.toString()))
        .andExpect(jsonPath("$.claim").value("the conjecture holds"))
        .andExpect(jsonPath("$.topical").value(0.62))
        // The difference, derived rather than sent: the two cosines are
        // what the store answers with, and a wire that carried only the
        // difference could not say whether a zero was "mixed" or "not
        // about this".
        .andExpect(jsonPath("$.stance").value(closeTo(0.31, 1e-9)))
        .andExpect(jsonPath("$.basis").value(DocumentStanceResponse.VECTOR_ONLY));
  }

  /**
   * A document with no summary vector is a 404 and never a zero.
   *
   * <p>Zero is a real reading — it is what a paper unrelated to both the claim and its negation
   * scores — so answering an unembedded document with one would be indistinguishable from a genuine
   * result.
   */
  @Test
  void a_document_with_no_summary_vector_is_not_scored_as_zero() throws Exception {
    when(retrieval.stance(eq(DOCUMENT), anyString())).thenReturn(Optional.empty());

    mvc.perform(
            post("/v1/documents/" + DOCUMENT + "/stance")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"claim\":\"anything\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void a_stance_with_no_claim_or_a_path_that_is_not_an_id_is_refused() throws Exception {
    mvc.perform(
            post("/v1/documents/" + DOCUMENT + "/stance")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"claim\":\"\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/v1/documents/not-an-id/stance")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"claim\":\"q\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void a_chunk_the_corpus_does_not_hold_is_a_404() throws Exception {
    when(store.chunk(any(UUID.class))).thenReturn(Optional.empty());

    mvc.perform(get("/v1/documents/chunks/" + CHUNK)).andExpect(status().isNotFound());
  }
}
