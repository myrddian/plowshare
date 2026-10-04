package io.aeyer.plowshare.server.api;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.documents.CitationStore;
import io.aeyer.plowshare.server.documents.Deliberation;
import io.aeyer.plowshare.server.documents.DocumentStore;
import io.aeyer.plowshare.server.documents.DocumentsProperties;
import io.aeyer.plowshare.server.documents.Extracted;
import io.aeyer.plowshare.server.documents.IngestService;
import io.aeyer.plowshare.server.documents.Pdfs;
import io.aeyer.plowshare.server.documents.RetrievalService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The one door a document comes in by.
 *
 * <p>Pure MVC against a mocked {@link IngestService} and {@link JobStore}, matching {@code
 * MemoryControllerTest}'s shape: what the pipeline does is {@code IngestServiceTest}'s subject
 * against a real corpus, and this class's job is narrower — is the upload turned into the right
 * job, and does each refusal become the right status.
 *
 * <p>The job store is mocked, so the submitted work never runs on its own. {@link #submittedWork()}
 * takes the function the controller handed over and runs it here, which is how an assertion about
 * <em>what</em> was submitted is made without a thread being involved.
 */
class DocumentControllerTest {

  /** The document every ask in this file is about. */
  private static final UUID DOCUMENT = UUID.fromString("11111111-2222-3333-4444-555555555555");

  private IngestService ingest;
  private JobStore jobs;
  private RetrievalService retrieval;
  private CitationStore citations;
  private DocumentStore store;
  private Deliberation deliberation;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    ingest = mock(IngestService.class);
    when(ingest.ingest(anyString(), any(), anyLong(), anyString(), any()))
        .thenReturn(new Outcome(Outcome.Ending.ANSWERED, "done", 1, 1, ""));
    jobs = mock(JobStore.class);
    when(jobs.submit(anyString(), any())).thenReturn("job_000001");
    retrieval = mock(RetrievalService.class);
    citations = mock(CitationStore.class);
    store = mock(DocumentStore.class);
    when(store.find(any(UUID.class)))
        .thenReturn(
            java.util.Optional.of(
                new DocumentStore.StoredDocument(
                    DOCUMENT,
                    "paper.md",
                    "paper",
                    "hash",
                    "text",
                    12L,
                    java.time.Instant.EPOCH,
                    "test",
                    "argues something",
                    io.aeyer.plowshare.server.documents.Vocabulary.SECTION)));
    deliberation = mock(Deliberation.class);
    when(deliberation.ask(any(), any(), any(), any()))
        .thenReturn(new Outcome(Outcome.Ending.ANSWERED, "answered", 3, 3, ""));
    DocumentsProperties props = new DocumentsProperties();
    props.setAskBudget(4);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new DocumentController(
                    ingest, jobs, retrieval, citations, store, deliberation, props))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
  }

  private static MockMultipartFile upload(String name, String text) {
    return new MockMultipartFile("file", name, "text/plain", text.getBytes(StandardCharsets.UTF_8));
  }

  // --- the accepted case ----------------------------------------------------

  /**
   * An ingest is a job like any other, and the response is the handle.
   *
   * <p>202 and not 200: the work has not been done when this answers, and {@code GET
   * /v1/jobs/&#123;id&#125;} is where it is found — the same shape {@code POST /v1/curate} already
   * has, which is what lets one job listing serve both.
   */
  @Test
  void an_upload_is_accepted_as_a_job() throws Exception {
    mvc.perform(multipart("/v1/documents").file(upload("notes.md", "Alpha.\n\nBeta.")))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value("job_000001"))
        .andExpect(jsonPath("$.agent").value("ingest"));
  }

  /**
   * What the job actually does when it runs.
   *
   * <p>Extraction has already happened by this point — it is on the request thread, so a format
   * this server does not read is answered at once rather than by a job that has to be polled to
   * find out.
   */
  @Test
  void the_job_ingests_the_extracted_document_under_the_name_it_was_filed_as() {
    performUpload(upload("notes.md", "Alpha.\n\nBeta."));

    submittedWork().apply(() -> false);

    ArgumentCaptor<Extracted> extracted = ArgumentCaptor.forClass(Extracted.class);
    // 13 bytes: the size recorded on the row is the size of the UPLOAD, not
    // of the extracted text, which is what makes it reconcilable against a
    // disk.
    verify(ingest).ingest(eq("notes.md"), extracted.capture(), eq(13L), anyString(), any());
    assertEquals("Alpha.\n\nBeta.", extracted.getValue().text());
    assertEquals("notes", extracted.getValue().title());
  }

  /**
   * The name the corpus files it under is the uploaded filename unless the caller says otherwise:
   * the identity of a document is a decision somebody can take rather than an accident of what a
   * file was called on a disk.
   */
  @Test
  void a_caller_may_name_the_document_something_other_than_the_filename() {
    performUpload(upload("tmp-42.md", "Alpha."), "name", "The Retry Budget");

    submittedWork().apply(() -> false);

    verify(ingest).ingest(eq("The Retry Budget"), any(), anyLong(), anyString(), any());
  }

  // --- the refusals ---------------------------------------------------------

  /**
   * 415 and not 400: the request was well formed and what it carried is a format this server does
   * not read. And no job is started — a caller told to poll a job that was never going to run is
   * worse than a refusal.
   *
   * <p>A DOCX and not a PDF, which is what this used to be. The PDF is read now, and a refusal for
   * one is a statement about <em>that document</em> rather than about the format — which is the
   * case below.
   */
  @Test
  void a_format_this_server_does_not_read_is_refused_at_once() throws Exception {
    MockMultipartFile docx =
        new MockMultipartFile(
            "file",
            "paper.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            new byte[] {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x06, 0x00});

    mvc.perform(multipart("/v1/documents").file(docx))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.error").value("unsupported_document"))
        .andExpect(jsonPath("$.detail").value(containsString("zip container")));

    verify(jobs, never()).submit(anyString(), any());
  }

  /**
   * A PDF this server cannot parse is still a 415, and it still arrives on the request thread
   * rather than as a job that fails later.
   */
  @Test
  void a_pdf_that_cannot_be_parsed_is_refused_at_once_and_says_so() throws Exception {
    MockMultipartFile torn =
        new MockMultipartFile(
            "file",
            "paper.pdf",
            "application/pdf",
            "%PDF-1.7\nbinary".getBytes(StandardCharsets.ISO_8859_1));

    mvc.perform(multipart("/v1/documents").file(torn))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.error").value("unsupported_document"))
        .andExpect(jsonPath("$.detail").value(containsString("could not read")));

    verify(jobs, never()).submit(anyString(), any());
  }

  /**
   * And a PDF that <em>can</em> be parsed goes all the way through the door this class is about —
   * 202, a job handle, and the text off its pages.
   *
   * <p>Anchor's corpus is PDFs of academic papers, so this is the route the whole port has to come
   * in by.
   */
  @Test
  void a_pdf_is_ingested_like_any_other_document() throws Exception {
    MockMultipartFile paper =
        new MockMultipartFile(
            "file",
            "paper.pdf",
            "application/pdf",
            Pdfs.outlined(
                List.of("Introduction", "Method"),
                List.of(Pdfs.Page.of(Pdfs.Paragraph.of("The opening paragraph.")))));

    mvc.perform(multipart("/v1/documents").file(paper))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value("job_000001"));

    submittedWork().apply(() -> false);

    ArgumentCaptor<Extracted> extracted = ArgumentCaptor.forClass(Extracted.class);
    verify(ingest).ingest(eq("paper.pdf"), extracted.capture(), anyLong(), anyString(), any());
    assertEquals("The opening paragraph.", extracted.getValue().text().strip());
    assertEquals(List.of("Introduction", "Method"), extracted.getValue().outlineTopLevel());
  }

  @Test
  void an_empty_upload_is_refused_at_once() throws Exception {
    mvc.perform(multipart("/v1/documents").file(upload("empty.md", "")))
        .andExpect(status().isUnsupportedMediaType());

    verify(jobs, never()).submit(anyString(), any());
  }

  /**
   * A blank override is a caller that meant to name the document and sent the field empty, which
   * would otherwise file it under nothing.
   */
  @Test
  void a_blank_name_is_a_bad_request_and_not_a_silent_fallback() throws Exception {
    mvc.perform(multipart("/v1/documents").file(upload("notes.md", "Alpha.")).param("name", " "))
        .andExpect(status().isBadRequest());

    verify(jobs, never()).submit(anyString(), any());
  }

  // --- asking the corpus ----------------------------------------------------

  /**
   * The hit is a chunk and every row carries the paragraph to cite.
   *
   * <p>The wire shape is Anchor's {@code RetrieveResponse} without the ancestor stack: there are no
   * chapters and no sections here, because V18 declined to store a hierarchy derived by ~40
   * hand-curated strings fitted to English chemistry PDFs. What survives the port is the part that
   * is about identity — the paragraph id, which is stable across a re-ingest that left the text
   * alone.
   */
  @Test
  void a_search_answers_with_hits_that_cite_a_paragraph() throws Exception {
    when(retrieval.search("what is the retry budget", 5))
        .thenReturn(
            new RetrievalService.Found(
                "what is the retry budget",
                5,
                5,
                RetrievalService.Mode.HYBRID,
                List.of(hit("Retries are budgeted.")),
                new DocumentStore.Coverage(40, 0)));

    mvc.perform(
            post("/v1/documents/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"what is the retry budget\",\"limit\":5}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.query").value("what is the retry budget"))
        .andExpect(jsonPath("$.limit").value(5))
        .andExpect(jsonPath("$.searchable").value(40))
        .andExpect(jsonPath("$.unsearchable").value(0))
        .andExpect(jsonPath("$.hits[0].text").value("Retries are budgeted."))
        .andExpect(jsonPath("$.hits[0].paragraphId").isNotEmpty())
        .andExpect(jsonPath("$.hits[0].paragraphText").value("Retries are budgeted."))
        .andExpect(jsonPath("$.hits[0].paragraphOrdinal").value(1))
        .andExpect(jsonPath("$.hits[0].sourceName").value("notes.md"))
        .andExpect(jsonPath("$.hits[0].title").value("notes"))
        .andExpect(jsonPath("$.hits[0].similarity").value(0.9));
  }

  /**
   * No limit is the service's default, and the answer says which was used — a caller that sent
   * nothing cannot otherwise tell a default from a cap.
   */
  @Test
  void a_search_with_no_limit_takes_the_default_and_the_answer_says_so() throws Exception {
    when(retrieval.search(anyString(), anyInt()))
        .thenReturn(
            found(
                List.of(),
                RetrievalService.DEFAULT_HITS,
                RetrievalService.DEFAULT_HITS,
                new DocumentStore.Coverage(3, 0)));

    mvc.perform(
            post("/v1/documents/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"anything\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limit").value(RetrievalService.DEFAULT_HITS));

    verify(retrieval).search("anything", RetrievalService.DEFAULT_HITS);
  }

  /**
   * <b>What the search could not reach is on the wire whether or not anything was found.</b>
   *
   * <p>{@code RecallResponse.unsearchable}'s reason, at corpus scale: without it an empty {@code
   * hits} is the same wire shape whether the corpus holds nothing about the question or holds the
   * answer with no vector on it, and only one of those is worth ingesting a document again for.
   */
  @Test
  void an_empty_answer_says_how_much_of_the_corpus_could_not_be_searched() throws Exception {
    when(retrieval.search(anyString(), anyInt()))
        .thenReturn(found(List.of(), 5, 5, new DocumentStore.Coverage(2, 118)));

    mvc.perform(
            post("/v1/documents/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"anything\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hits").isEmpty())
        .andExpect(jsonPath("$.searchable").value(2))
        .andExpect(jsonPath("$.unsearchable").value(118));
  }

  @Test
  void a_blank_query_is_a_bad_request_and_nothing_is_embedded() throws Exception {
    mvc.perform(
            post("/v1/documents/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"  \"}"))
        .andExpect(status().isBadRequest());

    verify(retrieval, never()).search(anyString(), anyInt());
  }

  /**
   * Asking for none is refused rather than answered, for {@code RetrievalService}'s reason: an
   * empty list is a claim about the corpus.
   */
  @Test
  void a_limit_of_none_is_a_bad_request() throws Exception {
    mvc.perform(
            post("/v1/documents/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"anything\",\"limit\":0}"))
        .andExpect(status().isBadRequest());

    verify(retrieval, never()).search(anyString(), anyInt());
  }

  /**
   * An over-large limit is capped rather than refused, and the answer carries the number that was
   * used.
   *
   * <p>The bound is {@code RetrievalService}'s and this endpoint does not repeat it — a second
   * spelling of a cap is the copy that drifts. What this asserts is that the caller's number
   * reaches the service unchanged and the service's answer reaches the caller unchanged.
   */
  @Test
  void an_over_large_limit_is_the_service_to_cap_and_the_answer_says_what_it_used()
      throws Exception {
    when(retrieval.search(anyString(), anyInt()))
        .thenReturn(
            found(List.of(), 500, RetrievalService.MAX_HITS, new DocumentStore.Coverage(9, 0)));

    mvc.perform(
            post("/v1/documents/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"anything\",\"limit\":500}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limit").value(RetrievalService.MAX_HITS));

    verify(retrieval).search("anything", 500);
  }

  // --- which halves were read ----------------------------------------------

  /**
   * <b>A request that names no mode leaves the choice to the service</b>, the way one that names no
   * cap does.
   *
   * <p>This endpoint holds no copy of the default and does not resolve one: it calls the
   * two-argument read, which is the door whose default is documented. A second spelling of "hybrid"
   * in this file would be the copy that went on saying hybrid after the other one moved — {@code
   * an_over_large_limit_is_the_service_to_cap} makes the identical argument about the identical
   * thing one field over.
   */
  @Test
  void a_search_that_names_no_mode_leaves_the_choice_to_the_service() throws Exception {
    when(retrieval.search(anyString(), anyInt()))
        .thenReturn(found(List.of(), 5, 5, new DocumentStore.Coverage(3, 0)));

    mvc.perform(
            post("/v1/documents/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"anything\",\"limit\":5}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("hybrid"));

    verify(retrieval).search("anything", 5);
    verify(retrieval, never()).search(anyString(), anyInt(), any());
  }

  /**
   * <b>And vector-only is reachable here, which is the only place it is reachable from.</b>
   *
   * <p>Not offered to a model — {@code DocumentTools}' schema has no such field — because an agent
   * choosing between this server's indexes is being asked a question nothing in its context can
   * answer. An operator comparing the two answers to one question is the entire use, and it is how
   * a lexical half that has broken is told apart from one that is correctly quiet.
   */
  @Test
  void vector_only_is_reachable_over_http_and_the_answer_says_which_halves_it_read()
      throws Exception {
    when(retrieval.search(anyString(), anyInt(), any()))
        .thenReturn(
            new RetrievalService.Found(
                "anything",
                5,
                5,
                RetrievalService.Mode.VECTOR,
                List.of(),
                new DocumentStore.Coverage(3, 0)));

    mvc.perform(
            post("/v1/documents/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"anything\",\"limit\":5,\"mode\":\"VECTOR\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("vector"));

    verify(retrieval).search("anything", 5, RetrievalService.Mode.VECTOR);
  }

  /**
   * A mode this server does not have is refused, and the refusal names the ones it does.
   *
   * <p>Never silently defaulted to hybrid: a caller comparing two answers to one question, which is
   * what the field exists for, would be comparing an answer against itself and concluding the
   * halves agree.
   */
  @Test
  void a_mode_this_server_does_not_have_is_refused_and_the_others_are_named() throws Exception {
    mvc.perform(
            post("/v1/documents/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"anything\",\"mode\":\"bm25\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("hybrid")))
        .andExpect(jsonPath("$.detail").value(containsString("vector")))
        .andExpect(jsonPath("$.detail").value(containsString("lexical")));

    verify(retrieval, never()).search(anyString(), anyInt());
    verify(retrieval, never()).search(anyString(), anyInt(), any());
  }

  /**
   * Blank is a caller that meant to choose and sent nothing, which is the distinction a blank
   * {@code name} on an upload is held to.
   */
  @Test
  void a_blank_mode_is_a_bad_request_rather_than_the_default() throws Exception {
    mvc.perform(
            post("/v1/documents/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"anything\",\"mode\":\"  \"}"))
        .andExpect(status().isBadRequest());

    verify(retrieval, never()).search(anyString(), anyInt());
  }

  // --- the ask ---------------------------------------------------------------

  /**
   * <b>A pass is a job like any other, and the response is the handle.</b>
   *
   * <p>202 and not 200, on the upload's split and for the same arithmetic: three model calls in
   * series on a local model is minutes, and {@code GET /v1/jobs/&#123;id&#125;} is where the answer
   * is found. That is also what makes cancellation real — Anchor's cancel endpoint claims the
   * orchestrator discards a cancelled result and there is no such check in its code.
   */
  @Test
  void an_ask_is_accepted_as_a_job() throws Exception {
    mvc.perform(
            post("/v1/documents/" + DOCUMENT + "/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"is the bound tight\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.id").value("job_000001"))
        .andExpect(jsonPath("$.agent").value("ask"));
  }

  /**
   * And what the job does when it runs is deliberate over that document, with the question stripped
   * and the configured allowance.
   */
  @Test
  void the_job_deliberates_over_the_document_the_path_named() throws Exception {
    mvc.perform(
            post("/v1/documents/" + DOCUMENT + "/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"  is the bound tight  \"}"))
        .andExpect(status().isAccepted());

    askedWork().apply(() -> false);

    ArgumentCaptor<Budget> budget = ArgumentCaptor.forClass(Budget.class);
    verify(deliberation).ask(eq(DOCUMENT), eq("is the bound tight"), budget.capture(), any());
    assertEquals(4, budget.getValue().limit());
  }

  /** A caller with an allowance of its own is given it, on {@code POST /v1/curate}'s precedent. */
  @Test
  void a_pass_may_be_given_its_own_allowance() throws Exception {
    mvc.perform(
            post("/v1/documents/" + DOCUMENT + "/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"anything\",\"maxModelCalls\":9}"))
        .andExpect(status().isAccepted());

    askedWork().apply(() -> false);

    ArgumentCaptor<Budget> budget = ArgumentCaptor.forClass(Budget.class);
    verify(deliberation).ask(any(), any(), budget.capture(), any());
    assertEquals(9, budget.getValue().limit());
  }

  @Test
  void an_allowance_of_none_is_a_bad_request_rather_than_a_pass_that_cannot_start()
      throws Exception {
    mvc.perform(
            post("/v1/documents/" + DOCUMENT + "/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"anything\",\"maxModelCalls\":0}"))
        .andExpect(status().isBadRequest());

    verify(jobs, never()).submit(eq("ask"), any());
  }

  @Test
  void an_ask_with_no_question_is_a_bad_request() throws Exception {
    mvc.perform(
            post("/v1/documents/" + DOCUMENT + "/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"   \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("question")));

    verify(jobs, never()).submit(eq("ask"), any());
  }

  /**
   * <b>A document the corpus does not hold is refused now and not by a job.</b>
   *
   * <p>The upload's stated rule one route over: a caller handed a job id and told to poll it in
   * order to discover it named a document that never existed is worse off than one refused, and
   * this is knowable in microseconds. What is <em>not</em> refused here is everything the
   * deliberation has to say in its own words — an unsummarised document, an unembedded one, a
   * paragraph in no section — because saying those twice in two vocabularies is how the two come to
   * disagree.
   */
  @Test
  void an_ask_about_a_document_the_corpus_does_not_hold_is_a_404() throws Exception {
    when(store.find(any(UUID.class))).thenReturn(java.util.Optional.empty());

    mvc.perform(
            post("/v1/documents/" + DOCUMENT + "/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"anything\"}"))
        .andExpect(status().isNotFound());

    verify(jobs, never()).submit(eq("ask"), any());
  }

  /**
   * And something that is not an id at all is a 400: answering "no such document" would say the
   * corpus was asked when nothing was.
   */
  @Test
  void an_ask_whose_path_is_not_an_id_is_a_bad_request() throws Exception {
    mvc.perform(
            post("/v1/documents/not-a-uuid/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"anything\"}"))
        .andExpect(status().isBadRequest());

    verify(store, never()).find(any(UUID.class));
    verify(jobs, never()).submit(eq("ask"), any());
  }

  @SuppressWarnings("unchecked")
  private Function<BooleanSupplier, Outcome> askedWork() {
    ArgumentCaptor<Function<BooleanSupplier, Outcome>> work =
        ArgumentCaptor.forClass(Function.class);
    verify(jobs).submit(eq("ask"), work.capture());
    return work.getValue();
  }

  private static RetrievalService.Found found(
      List<DocumentStore.Hit> hits, int asked, int limit, DocumentStore.Coverage coverage) {
    return new RetrievalService.Found(
        "q", asked, limit, RetrievalService.Mode.HYBRID, hits, coverage);
  }

  private static DocumentStore.Hit hit(String text) {
    return new DocumentStore.Hit(
        UUID.randomUUID(),
        text,
        0.1,
        UUID.randomUUID(),
        text,
        1,
        UUID.randomUUID(),
        "notes.md",
        "notes",
        null);
  }

  private void performUpload(MockMultipartFile file, String... params) {
    try {
      var request = multipart("/v1/documents").file(file);
      for (int i = 0; i + 1 < params.length; i += 2) {
        request.param(params[i], params[i + 1]);
      }
      mvc.perform(request).andExpect(status().isAccepted());
    } catch (Exception unexpected) {
      throw new IllegalStateException(unexpected);
    }
  }

  @SuppressWarnings("unchecked")
  private Function<BooleanSupplier, Outcome> submittedWork() {
    ArgumentCaptor<Function<BooleanSupplier, Outcome>> work =
        ArgumentCaptor.forClass(Function.class);
    verify(jobs).submit(eq("ingest"), work.capture());
    return work.getValue();
  }
}
