package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.api.DocumentController;
import io.aeyer.plowshare.server.documents.CitationStore;
import io.aeyer.plowshare.server.documents.Deliberation;
import io.aeyer.plowshare.server.documents.DocumentStore;
import io.aeyer.plowshare.server.documents.DocumentsProperties;
import io.aeyer.plowshare.server.documents.IngestService;
import io.aeyer.plowshare.server.documents.RetrievalService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Every endpoint {@code DocumentController} answers with a frame, driven twice — once over HTTP and
 * once as a frame — off one request, asserting that the two surfaces said the same thing.
 *
 * <h2>Nine of ten, and the tenth is a ruling</h2>
 *
 * <p>{@code POST /v1/documents} is the multipart PDF upload and gets no frame: decided, not
 * deferred, and written out in {@code client.Capabilities} with the reasoning. It is therefore not
 * absent from this file by oversight and {@link #the_upload_has_no_frame_and_that_is_a_ruling()} is
 * what says so where a reader counting sections would otherwise find eight and wonder.
 *
 * <h2>What every section asserts</h2>
 *
 * <p>The happy path and <b>at least one refusal</b>, which is where the value of this file is: Task
 * 2 retired fourteen inline refusals out of this one controller, more than any other in the plan,
 * and a refusal is where two surfaces drift — a status is easy to agree on and a sentence is not.
 * {@link FrameParity#assertSameRefusal} compares the words.
 *
 * <p><b>Mocked stores and services, shared by both surfaces.</b> What a store does with a row is
 * its own Testcontainers test's subject; what is under test here is which method each surface
 * calls, with what, and what each does with what comes back — including with what is thrown.
 */
class DocumentFramesTest {

  private static final UUID DOCUMENT = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID CHUNK = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
  private static final UUID PARAGRAPH = UUID.fromString("99999999-8888-7777-6666-555555555555");
  private static final Instant INGESTED_AT = Instant.parse("2026-09-11T00:00:00Z");

  private IngestService ingest;
  private JobStore jobs;
  private RetrievalService retrieval;
  private CitationStore citations;
  private DocumentStore documents;
  private Deliberation deliberation;
  private DocumentsProperties props;
  private MockMvc mvc;
  private FrameRouter router;

  @BeforeEach
  void setUp() {
    ingest = mock(IngestService.class);
    jobs = mock(JobStore.class);
    retrieval = mock(RetrievalService.class);
    citations = mock(CitationStore.class);
    documents = mock(DocumentStore.class);
    deliberation = mock(Deliberation.class);
    props = configured();

    // FrameParity.endpointsOf and not a bare standaloneSetup: a document
    // row carries an `ingestedAt`, and the bare harness renders an Instant
    // as a number where a deployed server sends an ISO string. That class's
    // javadoc has the argument.
    mvc =
        FrameParity.endpointsOf(
            new DocumentController(
                ingest, jobs, retrieval, citations, documents, deliberation, props));
    // Through the area rather than through a literal map: this is the
    // registration the handlers are reached by in production, and a test
    // that built its own map would pass for a type nobody wired.
    router =
        new FrameRoutingConfig()
            .frameRouter(
                List.of(
                    new DocumentFrames(
                        ingest, jobs, retrieval, citations, documents, deliberation, props)));
  }

  // --- document.ask --------------------------------------------------------

  /**
   * Both surfaces start one deliberation, on one document, with one allowance, and both answer 202
   * with the same handle.
   */
  @Test
  void both_surfaces_start_the_same_deliberation() throws Exception {
    holds(DOCUMENT);
    when(jobs.submit(eq(DocumentController.ASKED_BY), any())).thenReturn("job_1");

    MockHttpServletResponse http =
        posted(
            "/v1/documents/" + DOCUMENT + "/ask",
            """
                {"question": "  what does it argue?  ", "maxModelCalls": 12}""");
    Outcome outcome =
        route(
            FrameTypes.DOCUMENT_ASK,
            """
                {"document": "%s", "question": "  what does it argue?  ",
                 "maxModelCalls": 12}"""
                .formatted(DOCUMENT));

    // ACCEPTED and not OK: what comes back is a handle to poll, and an OK
    // would tell a client its deliberation had finished.
    assertEquals(Code.ACCEPTED, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(jobs, times(2)).submit(eq(DocumentController.ASKED_BY), any());
  }

  /**
   * A body that names no allowance takes the operator's, on both surfaces.
   *
   * <p><b>A default is a place two surfaces drift silently</b>: it is not in the request, so
   * nothing in a payload comparison would show a handler that reached for a number of its own. The
   * fixture's 77 is deliberately not what {@code application.yml} ships.
   */
  @Test
  void an_ask_that_names_no_allowance_takes_the_operators_on_both_surfaces() throws Exception {
    holds(DOCUMENT);
    when(jobs.submit(eq(DocumentController.ASKED_BY), any())).thenReturn("job_1");

    posted(
        "/v1/documents/" + DOCUMENT + "/ask",
        """
                {"question": "what does it argue?"}""");
    route(
        FrameTypes.DOCUMENT_ASK,
        """
                {"document": "%s", "question": "what does it argue?"}"""
            .formatted(DOCUMENT));

    // The submitted work is run here, so that the Budget each surface closed
    // over can be looked at without a thread being involved.
    runSubmitted();
    // Captured rather than matched: Budget has no equals, so eq(Budget.of(77))
    // would compare identities and fail against two perfectly correct
    // allowances.
    ArgumentCaptor<Budget> allowance = ArgumentCaptor.captor();
    verify(deliberation, times(2))
        .ask(eq(DOCUMENT), eq("what does it argue?"), allowance.capture(), any());
    for (Budget given : allowance.getAllValues()) {
      assertEquals(
          props.getAskBudget(),
          given.limit(),
          "both surfaces asked with the operator's own default");
    }
  }

  /** A blank question is refused in the same words, and nothing is submitted by either surface. */
  @Test
  void an_ask_with_no_question_is_the_same_refusal_on_both_surfaces() throws Exception {
    MockHttpServletResponse http =
        posted(
            "/v1/documents/" + DOCUMENT + "/ask",
            """
                {"question": "   "}""");
    Outcome outcome =
        route(
            FrameTypes.DOCUMENT_ASK,
            """
                {"document": "%s", "question": "   "}"""
                .formatted(DOCUMENT));

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(jobs, never()).submit(anyString(), any());
  }

  /**
   * A document the corpus does not hold is the same 404 in the same words.
   *
   * <p>And it is answered <b>after</b> the blank-question refusal above, on both surfaces, which is
   * the ordering {@code Corpus.theOneToAsk} sits behind rather than in front of.
   */
  @Test
  void an_ask_about_a_document_nobody_uploaded_is_the_same_404_on_both_surfaces() throws Exception {
    when(documents.find(DOCUMENT)).thenReturn(Optional.empty());

    MockHttpServletResponse http =
        posted(
            "/v1/documents/" + DOCUMENT + "/ask",
            """
                {"question": "what does it argue?"}""");
    Outcome outcome =
        route(
            FrameTypes.DOCUMENT_ASK,
            """
                {"document": "%s", "question": "what does it argue?"}"""
                .formatted(DOCUMENT));

    assertEquals(Code.NOT_FOUND, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(jobs, never()).submit(anyString(), any());
  }

  /**
   * A frame that names no document at all is refused — a request only this surface can receive,
   * since a URL naming none is a different URL.
   */
  @Test
  void an_ask_naming_no_document_is_the_frame_surfaces_own_refusal() {
    Outcome outcome =
        route(
            FrameTypes.DOCUMENT_ASK,
            """
                {"question": "what does it argue?"}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    assertTrue(
        outcome.said().contains("document.ask needs its payload to say which"), outcome.said());
  }

  // --- document.retrieve ---------------------------------------------------

  /** Both surfaces retrieve the same passage and echo the same question. */
  @Test
  void both_surfaces_retrieve_the_same_passage() throws Exception {
    when(retrieval.retrieve(eq("what does it argue"), isNull(), anyInt()))
        .thenReturn(List.of(new DocumentStore.Retrieved(chunk(), 0.25)));
    String asked =
        """
                {"query": " what does it argue ", "limit": 3}""";

    MockHttpServletResponse http = posted("/v1/documents/retrieve", asked);
    Outcome outcome = route(FrameTypes.DOCUMENT_RETRIEVE, asked);

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(retrieval, times(2)).retrieve("what does it argue", null, 3);
  }

  /**
   * An empty answer is still an OK on both surfaces, and is not promoted to a refusal by either.
   */
  @Test
  void a_retrieve_that_found_nothing_is_an_answer_and_not_a_refusal() throws Exception {
    when(retrieval.retrieve(anyString(), any(), anyInt())).thenReturn(List.of());
    String asked =
        """
                {"query": "nothing is about this"}""";

    MockHttpServletResponse http = posted("/v1/documents/retrieve", asked);
    Outcome outcome = route(FrameTypes.DOCUMENT_RETRIEVE, asked);

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
  }

  /** A limit of zero is refused rather than defaulted, identically. */
  @Test
  void a_retrieve_asked_for_no_chunks_is_the_same_refusal_on_both_surfaces() throws Exception {
    String asked =
        """
                {"query": "what does it argue", "limit": 0}""";

    MockHttpServletResponse http = posted("/v1/documents/retrieve", asked);
    Outcome outcome = route(FrameTypes.DOCUMENT_RETRIEVE, asked);

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(retrieval, never()).retrieve(anyString(), any(), anyInt());
  }

  /**
   * A blank query and a limit of zero at once are answered about the query, on both surfaces.
   *
   * <p>An ordering, which is the kind of parity no payload comparison sees: a handler that read its
   * limit first would agree with the endpoint on every other input and answer a different sentence
   * for this one.
   */
  @Test
  void a_retrieve_that_gets_two_things_wrong_is_told_about_the_query_on_both_surfaces()
      throws Exception {
    String asked =
        """
                {"query": "", "limit": 0}""";

    MockHttpServletResponse http = posted("/v1/documents/retrieve", asked);
    Outcome outcome = route(FrameTypes.DOCUMENT_RETRIEVE, asked);

    assertEquals(Code.BAD_REQUEST, outcome.code());
    assertTrue(outcome.said().startsWith("a retrieve needs a `query`:"), outcome.said());
    FrameParity.assertSameRefusal(http, outcome);
  }

  /** A document field that is not an id is refused in the words that explain leaving it out. */
  @Test
  void a_retrieve_naming_something_that_is_not_an_id_is_the_same_refusal() throws Exception {
    String asked =
        """
                {"query": "what does it argue", "document": "nope"}""";

    MockHttpServletResponse http = posted("/v1/documents/retrieve", asked);
    Outcome outcome = route(FrameTypes.DOCUMENT_RETRIEVE, asked);

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
  }

  // --- document.list -------------------------------------------------------

  /** Both surfaces page the corpus the same way and report the same total. */
  @Test
  void both_surfaces_list_the_same_corpus() throws Exception {
    when(documents.page(isNull(), anyInt(), anyInt())).thenReturn(List.of(listed()));
    when(documents.count(isNull())).thenReturn(1);

    MockHttpServletResponse http = read("/v1/documents");
    Outcome outcome = route(FrameTypes.DOCUMENT_LIST, "{}");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    // The default is the endpoint's own and not a number either surface
    // invented, which a payload comparison alone would not show.
    verify(documents, times(2)).page(null, DocumentController.DEFAULT_LISTED, 0);
  }

  /** A naming filter and a page travel identically. */
  @Test
  void both_surfaces_pass_the_same_naming_and_the_same_page_through() throws Exception {
    when(documents.page(eq("cosine"), anyInt(), anyInt())).thenReturn(List.of(listed()));
    when(documents.count("cosine")).thenReturn(1);

    // Through param() rather than a query string, because MockMvc's
    // standalone builder does not percent-decode one and the padding is
    // half of what this asserts: both surfaces strip it.
    MockHttpServletResponse http =
        mvc.perform(
                get("/v1/documents")
                    .param("limit", "2")
                    .param("offset", "4")
                    .param("q", " cosine "))
            .andReturn()
            .getResponse();
    Outcome outcome =
        route(
            FrameTypes.DOCUMENT_LIST,
            """
                {"limit": 2, "offset": 4, "q": " cosine "}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(documents, times(2)).page("cosine", 2, 4);
  }

  /** A listing asked for no documents is refused identically. */
  @Test
  void a_listing_asked_for_no_documents_is_the_same_refusal_on_both_surfaces() throws Exception {
    MockHttpServletResponse http = read("/v1/documents?limit=0");
    Outcome outcome =
        route(
            FrameTypes.DOCUMENT_LIST,
            """
                {"limit": 0}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(documents, never()).page(any(), anyInt(), anyInt());
  }

  /** A negative offset is refused rather than clamped, identically. */
  @Test
  void a_listing_with_a_negative_offset_is_the_same_refusal_on_both_surfaces() throws Exception {
    MockHttpServletResponse http = read("/v1/documents?offset=-1");
    Outcome outcome =
        route(
            FrameTypes.DOCUMENT_LIST,
            """
                {"offset": -1}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(documents, never()).page(any(), anyInt(), anyInt());
  }

  // --- document.detail -----------------------------------------------------

  /** Both surfaces read one document's structure the same way. */
  @Test
  void both_surfaces_read_the_same_structure() throws Exception {
    holds(DOCUMENT);
    when(documents.hierarchy(DOCUMENT)).thenReturn(List.of());

    MockHttpServletResponse http = read("/v1/documents/" + DOCUMENT);
    Outcome outcome =
        route(
            FrameTypes.DOCUMENT_DETAIL,
            """
                {"document": "%s"}"""
                .formatted(DOCUMENT));

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
  }

  /**
   * A document the corpus does not hold is the same 404 in the same words — and they are not the
   * ask's words.
   */
  @Test
  void a_structure_the_corpus_does_not_hold_is_the_same_404_on_both_surfaces() throws Exception {
    when(documents.find(DOCUMENT)).thenReturn(Optional.empty());

    MockHttpServletResponse http = read("/v1/documents/" + DOCUMENT);
    Outcome outcome =
        route(
            FrameTypes.DOCUMENT_DETAIL,
            """
                {"document": "%s"}"""
                .formatted(DOCUMENT));

    assertEquals(Code.NOT_FOUND, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    assertTrue(outcome.said().contains("GET /v1/documents lists everything"), outcome.said());
  }

  /** Something that is not an id at all is a 400 and not a 404, on both. */
  @Test
  void a_detail_read_of_something_that_is_not_an_id_is_the_same_400_on_both_surfaces()
      throws Exception {
    MockHttpServletResponse http = read("/v1/documents/nope");
    Outcome outcome =
        route(
            FrameTypes.DOCUMENT_DETAIL,
            """
                {"document": "nope"}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
  }

  // --- document.chunk ------------------------------------------------------

  /** Both surfaces read one chunk back in the shape a retrieve hit carries. */
  @Test
  void both_surfaces_read_the_same_chunk() throws Exception {
    when(documents.chunk(CHUNK)).thenReturn(Optional.of(chunk()));

    MockHttpServletResponse http = read("/v1/documents/chunks/" + CHUNK);
    Outcome outcome =
        route(
            FrameTypes.DOCUMENT_CHUNK,
            """
                {"chunk": "%s"}"""
                .formatted(CHUNK));

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
  }

  /** A chunk the corpus does not hold says the same thing about which id here does not last. */
  @Test
  void a_chunk_the_corpus_does_not_hold_is_the_same_404_on_both_surfaces() throws Exception {
    when(documents.chunk(CHUNK)).thenReturn(Optional.empty());

    MockHttpServletResponse http = read("/v1/documents/chunks/" + CHUNK);
    Outcome outcome =
        route(
            FrameTypes.DOCUMENT_CHUNK,
            """
                {"chunk": "%s"}"""
                .formatted(CHUNK));

    assertEquals(Code.NOT_FOUND, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
  }

  // --- document.rank -------------------------------------------------------

  /** Both surfaces rank the same documents. */
  @Test
  void both_surfaces_rank_the_same_documents() throws Exception {
    when(retrieval.rank(eq("cosine similarity"), anyInt()))
        .thenReturn(
            new RetrievalService.Ranking(
                List.of(new DocumentStore.Ranked(held(), 0.1)), new DocumentStore.Ranking(1, 0)));
    String asked =
        """
                {"query": " cosine similarity ", "limit": 5}""";

    MockHttpServletResponse http = posted("/v1/documents/rank", asked);
    Outcome outcome = route(FrameTypes.DOCUMENT_RANK, asked);

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(retrieval, times(2)).rank("cosine similarity", 5);
  }

  /**
   * A corpus nothing has embedded still answers OK, saying how many documents had no vector — not a
   * refusal on either surface.
   */
  @Test
  void an_unrankable_corpus_is_an_answer_and_not_a_refusal() throws Exception {
    when(retrieval.rank(anyString(), anyInt()))
        .thenReturn(new RetrievalService.Ranking(List.of(), new DocumentStore.Ranking(0, 4)));
    String asked =
        """
                {"query": "cosine similarity"}""";

    MockHttpServletResponse http = posted("/v1/documents/rank", asked);
    Outcome outcome = route(FrameTypes.DOCUMENT_RANK, asked);

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
  }

  /** A ranking asked for no documents is refused identically. */
  @Test
  void a_ranking_asked_for_no_documents_is_the_same_refusal_on_both_surfaces() throws Exception {
    String asked =
        """
                {"query": "cosine similarity", "limit": 0}""";

    MockHttpServletResponse http = posted("/v1/documents/rank", asked);
    Outcome outcome = route(FrameTypes.DOCUMENT_RANK, asked);

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(retrieval, never()).rank(anyString(), anyInt());
  }

  /**
   * A ranking with no query is refused in words that are the ranking's own and not the search's.
   */
  @Test
  void a_ranking_with_no_query_is_refused_in_its_own_words_on_both_surfaces() throws Exception {
    MockHttpServletResponse http = posted("/v1/documents/rank", "{}");
    Outcome outcome = route(FrameTypes.DOCUMENT_RANK, "{}");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    assertTrue(outcome.said().startsWith("a ranking needs a `query`:"), outcome.said());
  }

  // --- document.stance -----------------------------------------------------

  /** Both surfaces score the same claim and say what the number was made of. */
  @Test
  void both_surfaces_score_the_same_claim() throws Exception {
    when(retrieval.stance(DOCUMENT, "the sky is blue"))
        .thenReturn(Optional.of(new DocumentStore.Stance(0.8, 0.2)));
    String asked =
        """
                {"claim": " the sky is blue "}""";

    MockHttpServletResponse http = posted("/v1/documents/" + DOCUMENT + "/stance", asked);
    Outcome outcome =
        route(
            FrameTypes.DOCUMENT_STANCE,
            """
                {"document": "%s", "claim": " the sky is blue "}"""
                .formatted(DOCUMENT));

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(retrieval, times(2)).stance(DOCUMENT, "the sky is blue");
  }

  /** A document with no summary vector is a 404 and never a zero, on both. */
  @Test
  void a_document_with_no_summary_vector_is_the_same_404_on_both_surfaces() throws Exception {
    when(retrieval.stance(eq(DOCUMENT), anyString())).thenReturn(Optional.empty());
    String asked =
        """
                {"claim": "the sky is blue"}""";

    MockHttpServletResponse http = posted("/v1/documents/" + DOCUMENT + "/stance", asked);
    Outcome outcome =
        route(
            FrameTypes.DOCUMENT_STANCE,
            """
                {"document": "%s", "claim": "the sky is blue"}"""
                .formatted(DOCUMENT));

    assertEquals(Code.NOT_FOUND, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
  }

  /** A blank claim is refused identically, and nothing is scored. */
  @Test
  void a_stance_with_no_claim_is_the_same_refusal_on_both_surfaces() throws Exception {
    MockHttpServletResponse http =
        posted(
            "/v1/documents/" + DOCUMENT + "/stance",
            """
                        {"claim": "  "}""");
    Outcome outcome =
        route(
            FrameTypes.DOCUMENT_STANCE,
            """
                {"document": "%s", "claim": "  "}"""
                .formatted(DOCUMENT));

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(retrieval, never()).stance(any(), anyString());
  }

  /**
   * A malformed id and a blank claim at once are answered about the id, on both surfaces — the
   * endpoint parses its path before it reads its body.
   */
  @Test
  void a_stance_that_gets_two_things_wrong_is_told_about_the_id_on_both_surfaces()
      throws Exception {
    MockHttpServletResponse http =
        posted(
            "/v1/documents/nope/stance",
            """
                {"claim": ""}""");
    Outcome outcome =
        route(
            FrameTypes.DOCUMENT_STANCE,
            """
                {"document": "nope", "claim": ""}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    assertTrue(outcome.said().startsWith("'nope' is not a document id"), outcome.said());
    FrameParity.assertSameRefusal(http, outcome);
  }

  // --- document.citations --------------------------------------------------

  /** Naming neither scope is the whole corpus, newest first, on both. */
  @Test
  void both_surfaces_read_the_same_recent_citations() throws Exception {
    when(citations.recent(anyInt())).thenReturn(List.of(cited()));

    MockHttpServletResponse http = read("/v1/documents/citations");
    Outcome outcome = route(FrameTypes.DOCUMENT_CITATIONS, "{}");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(citations, times(2)).recent(CitationStore.MOST_LISTED);
  }

  /** Naming a conversation asks the conversation index on both. */
  @Test
  void both_surfaces_scope_the_same_citations_to_one_conversation() throws Exception {
    when(citations.madeIn(eq("cnv_1"), anyInt())).thenReturn(List.of(cited()));

    MockHttpServletResponse http = read("/v1/documents/citations?conversation=cnv_1&limit=3");
    Outcome outcome =
        route(
            FrameTypes.DOCUMENT_CITATIONS,
            """
                {"conversation": "cnv_1", "limit": 3}""");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(citations, times(2)).madeIn("cnv_1", 3);
  }

  /** Naming a document asks the document index on both. */
  @Test
  void both_surfaces_scope_the_same_citations_to_one_document() throws Exception {
    when(citations.of(eq(DOCUMENT), anyInt())).thenReturn(List.of(cited()));

    MockHttpServletResponse http = read("/v1/documents/citations?document=" + DOCUMENT);
    Outcome outcome =
        route(
            FrameTypes.DOCUMENT_CITATIONS,
            """
                {"document": "%s"}"""
                .formatted(DOCUMENT));

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(citations, times(2)).of(DOCUMENT, CitationStore.MOST_LISTED);
  }

  /** Both scopes at once is the same refusal, and nothing is read. */
  @Test
  void two_citation_scopes_at_once_is_the_same_refusal_on_both_surfaces() throws Exception {
    MockHttpServletResponse http =
        read("/v1/documents/citations?conversation=cnv_1&document=" + DOCUMENT);
    Outcome outcome =
        route(
            FrameTypes.DOCUMENT_CITATIONS,
            """
                {"conversation": "cnv_1", "document": "%s"}"""
                .formatted(DOCUMENT));

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(citations, never()).recent(anyInt());
    verify(citations, never()).madeIn(anyString(), anyInt());
  }

  /**
   * Two scopes and a limit of zero at once are answered about the scopes, on both surfaces — the
   * order {@code RequestedCitationScope} is called in.
   */
  @Test
  void a_citations_read_that_gets_two_things_wrong_is_told_about_the_scopes() throws Exception {
    MockHttpServletResponse http =
        read("/v1/documents/citations?conversation=cnv_1&document=" + DOCUMENT + "&limit=0");
    Outcome outcome =
        route(
            FrameTypes.DOCUMENT_CITATIONS,
            """
                {"conversation": "cnv_1", "document": "%s", "limit": 0}"""
                .formatted(DOCUMENT));

    assertEquals(Code.BAD_REQUEST, outcome.code());
    assertTrue(
        outcome.said().startsWith("`conversation` and `document` are two different"),
        outcome.said());
    FrameParity.assertSameRefusal(http, outcome);
  }

  /** A limit of zero is refused identically. */
  @Test
  void a_citations_read_asked_for_none_is_the_same_refusal_on_both_surfaces() throws Exception {
    MockHttpServletResponse http = read("/v1/documents/citations?limit=0");
    Outcome outcome =
        route(
            FrameTypes.DOCUMENT_CITATIONS,
            """
                {"limit": 0}""");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(citations, never()).recent(anyInt());
  }

  // --- document.search -----------------------------------------------------

  /** Both surfaces search the corpus the same way. */
  @Test
  void both_surfaces_search_the_same_corpus() throws Exception {
    when(retrieval.search(eq("what does it argue"), anyInt())).thenReturn(found());
    String asked =
        """
                {"query": " what does it argue ", "limit": 3}""";

    MockHttpServletResponse http = posted("/v1/documents/search", asked);
    Outcome outcome = route(FrameTypes.DOCUMENT_SEARCH, asked);

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(retrieval, times(2)).search("what does it argue", 3);
  }

  /**
   * A named mode reaches the three-argument door on both surfaces, and an absent one reaches the
   * two-argument door.
   *
   * <p><b>Which door is called is the assertion</b>: a handler that resolved {@code HYBRID} for
   * itself and always called the three-argument method would produce an identical payload and a
   * second spelling of the default.
   */
  @Test
  void a_named_mode_reaches_the_other_door_on_both_surfaces() throws Exception {
    when(retrieval.search(anyString(), anyInt(), any())).thenReturn(found());
    String asked =
        """
                {"query": "what does it argue", "mode": "LeXiCaL"}""";

    MockHttpServletResponse http = posted("/v1/documents/search", asked);
    Outcome outcome = route(FrameTypes.DOCUMENT_SEARCH, asked);

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(retrieval, times(2))
        .search("what does it argue", RetrievalService.DEFAULT_HITS, RetrievalService.Mode.LEXICAL);
    verify(retrieval, never()).search(anyString(), anyInt());
  }

  /**
   * A mode nobody recognises is refused rather than answered as hybrid, in the same words on both
   * surfaces.
   */
  @Test
  void a_mode_nobody_recognises_is_the_same_refusal_on_both_surfaces() throws Exception {
    String asked =
        """
                {"query": "what does it argue", "mode": "semantic"}""";

    MockHttpServletResponse http = posted("/v1/documents/search", asked);
    Outcome outcome = route(FrameTypes.DOCUMENT_SEARCH, asked);

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(retrieval, never()).search(anyString(), anyInt(), any());
  }

  /** A search asked for no hits is refused identically. */
  @Test
  void a_search_asked_for_no_hits_is_the_same_refusal_on_both_surfaces() throws Exception {
    String asked =
        """
                {"query": "what does it argue", "limit": 0}""";

    MockHttpServletResponse http = posted("/v1/documents/search", asked);
    Outcome outcome = route(FrameTypes.DOCUMENT_SEARCH, asked);

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    verify(retrieval, never()).search(anyString(), anyInt());
  }

  /**
   * A search with no query is refused in the search's own words, which are not the retrieve's
   * although the two read the same field.
   */
  @Test
  void a_search_with_no_query_is_refused_in_its_own_words_on_both_surfaces() throws Exception {
    MockHttpServletResponse http = posted("/v1/documents/search", "{}");
    Outcome outcome = route(FrameTypes.DOCUMENT_SEARCH, "{}");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    assertTrue(outcome.said().startsWith("a search needs a `query`:"), outcome.said());
  }

  // --- the surface's own two rules, for all nine types ----------------------

  /**
   * Every type this area claims ignores a field this build has never heard of — spec §3.2's "the
   * payload is tolerant", asserted once per type.
   */
  @Test
  void every_type_in_this_area_ignores_a_field_this_build_has_never_heard_of() throws Exception {
    for (Map.Entry<String, String> each : representativePayloads().entrySet()) {
      FrameParity.assertUnknownFieldsAreIgnored(router, each.getKey(), each.getValue());
    }
  }

  /**
   * The production routing table really does claim all nine of this controller's framed types.
   *
   * <p>Built from every {@link FrameArea} Spring would collect: an unregistered type is answered
   * with a perfectly well-formed {@code NOT_FOUND}, so a handler nobody wired looks from the
   * outside exactly like a handler nobody wrote.
   */
  @Test
  void the_production_routing_table_claims_every_document_type() {
    FrameRouter wired = FrameAreas.router();

    for (String type : representativePayloads().keySet()) {
      assertTrue(
          wired.types().contains(type), type + " is not in the production table: " + wired.types());
    }
  }

  /**
   * {@code POST /v1/documents} has no frame, and nothing in this area claims one for it.
   *
   * <p><b>A ruling, not a backlog item.</b> A text frame could carry a PDF as base64 and there is
   * no precedent on this server for it — {@code FileChannelHandler} is a {@code
   * TextWebSocketHandler} that has only ever exchanged JSON — and the event channel's write queue
   * is bounded and sized for small frames. This test is what stops the absence reading as an
   * oversight: the endpoint still answers over HTTP, {@code client.Capabilities} carries the
   * reasoning, and a later task that decides to invent a binary frame shape has to delete this
   * rather than simply not notice.
   */
  @Test
  void the_upload_has_no_frame_and_that_is_a_ruling() {
    FrameRouter wired = FrameAreas.router();

    assertTrue(
        wired.types().stream()
            .noneMatch(
                type -> type.startsWith("document.upload") || type.equals("document.ingest")),
        "the multipart upload stays on HTTP: " + wired.types());
  }

  /** One payload per type this area adds, good enough to reach the handler. */
  private static Map<String, String> representativePayloads() {
    return Map.ofEntries(
        Map.entry(
            FrameTypes.DOCUMENT_ASK, "{\"document\":\"" + DOCUMENT + "\",\"question\":\"why\"}"),
        Map.entry(FrameTypes.DOCUMENT_RETRIEVE, "{\"query\":\"why\"}"),
        Map.entry(FrameTypes.DOCUMENT_LIST, "{\"limit\":2}"),
        Map.entry(FrameTypes.DOCUMENT_DETAIL, "{\"document\":\"" + DOCUMENT + "\"}"),
        Map.entry(FrameTypes.DOCUMENT_CHUNK, "{\"chunk\":\"" + CHUNK + "\"}"),
        Map.entry(FrameTypes.DOCUMENT_RANK, "{\"query\":\"why\"}"),
        Map.entry(
            FrameTypes.DOCUMENT_STANCE, "{\"document\":\"" + DOCUMENT + "\",\"claim\":\"why\"}"),
        Map.entry(FrameTypes.DOCUMENT_CITATIONS, "{\"limit\":2}"),
        Map.entry(FrameTypes.DOCUMENT_SEARCH, "{\"query\":\"why\"}"));
  }

  // --- driving the two surfaces off one request ----------------------------

  private Outcome route(String type, String payload) {
    return router.route(FrameParity.frame(type, payload), FrameParity.ASKING);
  }

  private MockHttpServletResponse read(String path) throws Exception {
    return mvc.perform(get(path)).andReturn().getResponse();
  }

  private MockHttpServletResponse posted(String path, String body) throws Exception {
    return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn()
        .getResponse();
  }

  /** The store answers with a row for {@code id}, so the existence rule passes. */
  private void holds(UUID id) {
    when(documents.find(id)).thenReturn(Optional.of(held()));
  }

  /**
   * Both functions the job store was handed, run here, so an assertion about what was submitted can
   * be made without a thread being involved.
   */
  private void runSubmitted() {
    ArgumentCaptor<Function<BooleanSupplier, io.aeyer.plowshare.server.agents.Outcome>> work =
        ArgumentCaptor.captor();
    verify(jobs, times(2)).submit(eq(DocumentController.ASKED_BY), work.capture());
    for (Function<BooleanSupplier, io.aeyer.plowshare.server.agents.Outcome> each :
        work.getAllValues()) {
      each.apply(() -> false);
    }
  }

  private static DocumentStore.StoredDocument held() {
    return new DocumentStore.StoredDocument(
        DOCUMENT,
        "paper.pdf",
        "A Paper",
        "hash",
        "text",
        12L,
        INGESTED_AT,
        "operator",
        "what it argues",
        null);
  }

  private static DocumentStore.Listed listed() {
    return new DocumentStore.Listed(held(), 1, 2, 3, 4);
  }

  private static DocumentStore.ChunkWithAncestors chunk() {
    return new DocumentStore.ChunkWithAncestors(
        CHUNK,
        "the evidence",
        PARAGRAPH,
        3,
        "what the paragraph claims",
        new DocumentStore.Placement.InNoSection(),
        DOCUMENT,
        "paper.pdf",
        "A Paper",
        "what it argues");
  }

  /** One hit, carrying the corpus counts that say which kind of empty an empty answer is. */
  private static RetrievalService.Found found() {
    return new RetrievalService.Found(
        "what does it argue",
        3,
        3,
        RetrievalService.Mode.HYBRID,
        List.of(
            new DocumentStore.Hit(
                CHUNK,
                "the evidence",
                0.25,
                PARAGRAPH,
                "the paragraph",
                3,
                DOCUMENT,
                "paper.pdf",
                "A Paper",
                "what it argues")),
        new DocumentStore.Coverage(9, 1));
  }

  /**
   * One citation that still resolves, carrying the {@code citedAt} that makes this answer a payload
   * with a timestamp in it.
   */
  private static CitationStore.Cited cited() {
    return new CitationStore.Cited(
        UUID.fromString("12121212-3434-5656-7878-909090909090"),
        PARAGRAPH,
        DOCUMENT,
        "paper.pdf",
        3,
        "cnv_1",
        2,
        "librarian",
        INGESTED_AT,
        "the paragraph",
        "A Paper");
  }

  /**
   * The allowance an operator configured, deliberately not the number {@code application.yml}
   * ships: a hard-coded default would pass against that one.
   */
  private static DocumentsProperties configured() {
    DocumentsProperties properties = new DocumentsProperties();
    properties.setAskBudget(77);
    return properties;
  }
}
