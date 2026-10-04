package io.aeyer.plowshare.server.api;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Invalidation;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.MemoryProposal;
import io.aeyer.plowshare.protocol.MemoryState;
import io.aeyer.plowshare.protocol.Provenance;
import io.aeyer.plowshare.protocol.Verdict;
import io.aeyer.plowshare.protocol.VerdictKind;
import io.aeyer.plowshare.protocol.WriteResult;
import io.aeyer.plowshare.server.agents.scribe.Scribe;
import io.aeyer.plowshare.server.archive.Archive;
import io.aeyer.plowshare.server.archive.ArchiveException;
import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;
import io.aeyer.plowshare.server.archive.TocEntry;
import io.aeyer.plowshare.server.archive.ValidationException;
import io.aeyer.plowshare.server.llm.EmbeddingException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Pure MVC test against a mocked {@link Archive} — no Spring context, no Postgres, no embedding
 * model, matching the shape of Anchor's own controller tests (see {@code
 * IngestUploadControllerTest}). {@link Archive}'s own rules are exercised in {@code ArchiveTest}
 * against a real database; this class's job is narrower: does each endpoint call the right {@code
 * Archive} method with the right arguments, and does each thrown exception become the right status
 * code.
 */
class MemoryControllerTest {

  private Archive archive;
  private Scribe scribe;
  private MockMvc mvc;
  private ObjectMapper json;

  private static final Instant FORMED_AT = Instant.parse("2026-08-16T12:00:00Z");

  @BeforeEach
  void setUp() {
    archive = mock(Archive.class);
    // Mocked, like the archive, and for the same reason: this class asks
    // whether the endpoint calls the right thing with the right arguments.
    // What the scribe decides, and what it does when it cannot decide, is
    // ScribeTest's whole subject and is measured against a real dispatcher
    // there. The default here is the one every judgement produces when
    // nothing is deployed to make it.
    // The controller validates before it judges, so a mock answering 0 here
    // refuses every body as oversized and turns each of these into a 422.
    // The number is the shipped default from application.yml.
    when(archive.maxBodyChars()).thenReturn(8000);
    scribe = mock(Scribe.class);
    when(scribe.judge(any(), any()))
        .thenReturn(
            new Scribe.Judgement(
                new Verdict(VerdictKind.NEW, null, "filed flat: a stub scribe"), null));
    // findAndRegisterModules() picks up jackson-datatype-jsr310 off the
    // classpath, the same way Spring Boot's own auto-configured
    // ObjectMapper would — without it, any response carrying a Memory's
    // Instant fields fails to serialise, since a standalone MockMvc setup
    // builds its own converter rather than reusing the app context's.
    json =
        new ObjectMapper()
            .findAndRegisterModules()
            // And FAIL_ON_UNKNOWN_PROPERTIES off, which the line above does
            // NOT do and which this harness was silently wrong about.
            // Measured while writing the verdict tripwire below: a plain
            // ObjectMapper has it ON, Spring Boot's auto-configured one has
            // it OFF, so a request carrying an unknown key was refused here
            // and accepted by the real server. That is exactly the direction
            // that hides the bug the tripwire exists for — the whole
            // argument is that this server tolerates unknown keys, and this
            // harness could not have shown it either way.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    mvc =
        MockMvcBuilders.standaloneSetup(new MemoryController(archive, scribe))
            .setControllerAdvice(new ApiExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
            .build();
  }

  private Memory sampleMemory(String id, Home home) {
    return new Memory(
        id,
        "The retry budget is 4 attempts",
        "Calling the payments API",
        new Provenance(FORMED_AT, "scribe", "during the mTLS migration"),
        MemoryState.ACTIVE,
        false,
        0,
        null,
        "Four attempts since the timeout change.",
        null,
        null,
        null,
        home);
  }

  // --- the plan's required test --------------------------------------------

  /**
   * An unknown id is 404 rather than 200-with-null. A caller that cannot tell "no such memory" from
   * "a memory with no content" will eventually report the wrong one to a user.
   */
  @Test
  void an_unknown_memory_is_404() throws Exception {
    when(archive.read(anyList())).thenThrow(new ArchiveException("no memory with id mem_nope"));

    mvc.perform(get("/v1/memories/mem_nope")).andExpect(status().isNotFound());
  }

  // --- GET /v1/memories/{id} -------------------------------------------------

  /**
   * The endpoint calls {@code read}, not {@code get}: a lookup by id counts as a use, the same way
   * a recall hit does, matching Excalibur's harness-facing {@code memory_read}.
   */
  @Test
  void get_by_id_reads_through_the_counting_path_and_returns_the_wire_state() throws Exception {
    Memory memory = sampleMemory("mem_1", Home.of("payments"));
    when(archive.read(List.of("mem_1"))).thenReturn(List.of(memory));

    mvc.perform(get("/v1/memories/mem_1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("mem_1"))
        // "active", not "ACTIVE": proves MemoryState serialises through
        // wireName() rather than Jackson's name()-based enum default.
        .andExpect(jsonPath("$.state").value("active"))
        .andExpect(jsonPath("$.summary").value("The retry budget is 4 attempts"));

    verify(archive).read(List.of("mem_1"));
  }

  // --- POST /v1/memories ------------------------------------------------------

  @Test
  void write_resolves_an_explicit_project_and_echoes_the_result() throws Exception {
    WriteResult result =
        new WriteResult(VerdictKind.NEW, "mem_2", "first sighting", null, List.of());
    when(archive.applyVerdict(any(), any(), eq(Home.of("payments")), any())).thenReturn(result);

    String body =
        json.writeValueAsString(
            new WriteMemoryRequest(
                "payments",
                new MemoryProposal(
                    "The retry budget is 4 attempts",
                    "Calling the payments API",
                    "Four attempts since the timeout change.",
                    "scribe",
                    "during the mTLS migration"),
                null));

    mvc.perform(post("/v1/memories").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.memoryId").value("mem_2"))
        // "new", not "NEW": same wire-contract check as above, for
        // VerdictKind this time.
        .andExpect(jsonPath("$.kind").value("new"));
  }

  /**
   * The verdict the archive files under is the one the scribe returned.
   *
   * <p>The whole of the endpoint's new behaviour. With the field gone from the request there is
   * nothing on the wire that could have carried this, so a controller that dropped the scribe's
   * answer and passed {@code NEW} would look identical from outside on every write the scribe files
   * flat — which is every write in a deployment with no registry.
   */
  @Test
  void write_files_under_the_verdict_the_scribe_returned() throws Exception {
    Verdict judged = new Verdict(VerdictKind.SUPERSEDES, "mem_9", "the budget changed");
    when(scribe.judge(any(), any())).thenReturn(new Scribe.Judgement(judged, null));
    when(archive.applyVerdict(any(), any(), any(), any()))
        .thenReturn(
            new WriteResult(
                VerdictKind.SUPERSEDES, "mem_10", "the budget changed", "mem_9", List.of()));

    String body =
        """
                {"proposal": {"summary": "s", "scope": "sc", "body": "b",
                               "formedBy": "scribe", "formedWhere": ""}}""";

    mvc.perform(post("/v1/memories").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk());

    ArgumentCaptor<Verdict> filed = ArgumentCaptor.forClass(Verdict.class);
    verify(archive).applyVerdict(any(), filed.capture(), any(), any());
    assertEquals(judged, filed.getValue());
  }

  /**
   * The scribe is asked about the tier the write is for, and about the proposal that was sent — not
   * about a default. A project write judged against global would be judged against memories it
   * cannot supersede.
   */
  @Test
  void the_scribe_judges_the_proposal_against_the_tier_the_write_is_for() throws Exception {
    when(archive.applyVerdict(any(), any(), any(), any()))
        .thenReturn(new WriteResult(VerdictKind.NEW, "mem_2", "r", null, List.of()));

    String body =
        """
                {"project": "payments",
                 "proposal": {"summary": "the retry budget", "scope": "sc", "body": "b",
                               "formedBy": "scribe", "formedWhere": ""}}""";

    mvc.perform(post("/v1/memories").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk());

    ArgumentCaptor<MemoryProposal> judged = ArgumentCaptor.forClass(MemoryProposal.class);
    verify(scribe).judge(judged.capture(), eq(Home.of("payments")));
    assertEquals("the retry budget", judged.getValue().summary());
  }

  /**
   * No {@code project} key at all resolves to global — the same as an explicit {@code null} would,
   * since Jackson cannot tell the two apart once the body is bound.
   */
  @Test
  void write_with_no_project_field_resolves_to_global() throws Exception {
    WriteResult result =
        new WriteResult(VerdictKind.NEW, "mem_3", "first sighting", null, List.of());
    when(archive.applyVerdict(any(), any(), eq(Home.global()), any())).thenReturn(result);

    String body =
        """
                {"proposal": {"summary": "s", "scope": "sc", "body": "b",
                               "formedBy": "scribe", "formedWhere": ""}}""";

    mvc.perform(post("/v1/memories").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk());

    verify(archive).applyVerdict(any(), any(), eq(Home.global()), any());
  }

  /**
   * <b>Finding B.</b> An {@code IllegalArgumentException} from below the API is a server fault, and
   * must not come back as a complaint about the caller's proposal.
   *
   * <p>The handler used to map every {@code IllegalArgumentException} to 400, describing that in
   * its own javadoc as "deliberately narrow: this is the exception {@code Home.of} throws for a
   * blank project name". It was not narrow — it caught the type, from anywhere. An operator who set
   * {@code plowshare.llm.base-url} to {@code localhost:1234/v1} with no scheme made OkHttp throw
   * exactly this, and a write that failed because the server was misconfigured came back {@code 400
   * bad_request}. An agent told its proposal was rejected rewrites the proposal; it cannot fix a
   * base URL, and nothing in that answer would ever teach it that is what needs fixing.
   *
   * <p>The 400 rule now maps {@link BadRequestException}, which only this package throws and only
   * for something the caller can correct. Everything else is a 500 that says the server broke — and
   * says which exception, since an unmapped failure escaping to Spring's own handling produces a
   * document whose {@code message} is blank unless {@code server.error.include-message} is set.
   */
  @Test
  void an_illegal_argument_from_below_the_api_is_a_500_and_not_the_callers_fault()
      throws Exception {

    when(archive.applyVerdict(any(), any(), any(), any()))
        .thenThrow(
            new IllegalArgumentException(
                "Expected URL scheme 'http' or 'https' but no scheme was found for"
                    + " localhost:1234/v1"));

    String body =
        """
                {"proposal": {"summary": "s", "scope": "sc", "body": "b",
                               "formedBy": "scribe", "formedWhere": ""}}""";

    mvc.perform(post("/v1/memories").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error").value("internal_error"))
        // Names the type and the message, so the operator has something
        // to grep for rather than a bare number.
        .andExpect(jsonPath("$.detail").value(containsString("IllegalArgumentException")))
        .andExpect(jsonPath("$.detail").value(containsString("localhost:1234/v1")));
  }

  /**
   * And a request that really is malformed is still 400, so the test above is not passing by having
   * demoted everything.
   *
   * <p>A malformed JSON body specifically: a catch-all {@code Throwable} handler in a bare advice
   * class outranks Spring's own resolver and would turn this into a 500 — which is why {@code
   * ApiExceptionHandler} extends {@code ResponseEntityExceptionHandler}, whose more specific
   * handlers keep the standard MVC exceptions at their proper statuses.
   */
  @Test
  void a_body_that_is_not_json_is_still_the_callers_fault() throws Exception {
    mvc.perform(
            post("/v1/memories")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ this is not json"))
        .andExpect(status().isBadRequest());
  }

  /**
   * A blank project is refused rather than folded into global: an unset form field or a stray
   * client default sends {@code ""}, and treating that as global would silently promote a project
   * memory into the tier every project reads.
   */
  @Test
  void write_with_a_blank_project_is_400() throws Exception {
    String body =
        """
                {"project": "  ",
                 "proposal": {"summary": "s", "scope": "sc", "body": "b",
                               "formedBy": "scribe", "formedWhere": ""}}""";

    mvc.perform(post("/v1/memories").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }

  /**
   * A verdict naming a bad target — {@code Archive.applyVerdict}'s own refusal — reaches the caller
   * as 404, not a stack trace.
   *
   * <p>No caller can produce that verdict through this endpoint any more: {@code Scribe} only ever
   * names a memory it was shown in the tier being written to. The mapping is kept, and tested,
   * because {@code applyVerdict} is a public method with other callers and this is the right answer
   * if one of them arrives here.
   */
  @Test
  void write_naming_an_unknown_target_is_404() throws Exception {
    when(archive.applyVerdict(any(), any(), any(), any()))
        .thenThrow(new ArchiveException("verdict 'supersedes' names unknown target mem_ghost"));

    String body =
        """
                {"proposal": {"summary": "s", "scope": "sc", "body": "b",
                               "formedBy": "scribe", "formedWhere": ""}}""";

    mvc.perform(post("/v1/memories").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isNotFound());
  }

  /**
   * A malformed proposal — {@code Validation.check}'s own refusal — is 422, matching Anchor's
   * convention for well-formed JSON that fails a domain rule.
   *
   * <p>The stub below is what {@code applyVerdict} would throw had the proposal reached it. That
   * makes this test blind to <em>which</em> layer refused, which is deliberate — the status is its
   * subject — and is why {@code a_malformed_proposal_is_refused_before_the_scribe_is_asked} exists
   * beside it: a mutation deleting the controller's own check left this one green.
   */
  @Test
  void write_with_an_invalid_proposal_is_422() throws Exception {
    when(archive.applyVerdict(any(), any(), any(), any()))
        .thenThrow(new ValidationException("summary must be a single line"));

    String body =
        """
                {"proposal": {"summary": "line one\\nline two", "scope": "sc", "body": "b",
                               "formedBy": "scribe", "formedWhere": ""}}""";

    mvc.perform(post("/v1/memories").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnprocessableEntity());
  }

  /**
   * A malformed proposal costs no model call.
   *
   * <p>The controller validates before it judges, and this is the assertion that can see the order.
   * {@code Validation.check} has five refusals; the scribe's own blank-summary guard covers one of
   * them, and an oversized body — the most expensive prompt of the lot — is not among them. Judging
   * first spent an embedding call and a model call, with a person waiting, on a write that was
   * always going to come back 422.
   *
   * <p>Asserted as "the scribe was never asked" rather than as a status, because the status is 422
   * either way: the archive refuses the same proposal one layer down. Deleting the controller's
   * check leaves every other test in this class green.
   */
  @Test
  void a_malformed_proposal_is_refused_before_the_scribe_is_asked() throws Exception {
    String body =
        """
                {"proposal": {"summary": "line one\\nline two", "scope": "sc", "body": "b",
                               "formedBy": "scribe", "formedWhere": ""}}""";

    mvc.perform(post("/v1/memories").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnprocessableEntity());

    verify(scribe, never()).judge(any(), any());
    verify(archive, never()).applyVerdict(any(), any(), any(), any());
  }

  /**
   * The same, for the refusal the scribe's own guard does not cover at all: a body over the
   * archive's limit is the largest prompt this path can build, and it is built before anything
   * checks the length.
   */
  @Test
  void an_oversized_body_is_refused_before_the_scribe_is_asked() throws Exception {
    String body =
        json.writeValueAsString(
            new WriteMemoryRequest(
                null, new MemoryProposal("s", "sc", "x".repeat(8001), "scribe", ""), null));

    mvc.perform(post("/v1/memories").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnprocessableEntity());

    verify(scribe, never()).judge(any(), any());
  }

  // --- POST /v1/memories/recall -----------------------------------------------

  @Test
  void recall_defaults_the_limit_and_resolves_global_when_project_is_omitted() throws Exception {
    Memory hit = sampleMemory("mem_4", Home.global());
    when(archive.recall(
            eq("how many retries"), eq(Home.global()), eq(MemoryController.DEFAULT_RECALL_LIMIT)))
        .thenReturn(new Archive.Recall(List.of(hit), 0));

    mvc.perform(
            post("/v1/memories/recall")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"question": "how many retries"}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.limit").value(MemoryController.DEFAULT_RECALL_LIMIT))
        .andExpect(jsonPath("$.memories[0].id").value("mem_4"))
        // Present even at zero, which is the point: a field a caller has
        // to check the existence of is a field a caller forgets to check.
        .andExpect(jsonPath("$.unsearchable").value(0));
  }

  /**
   * An empty recall carries the count of what could not be searched, so "the archive holds nothing
   * close" and "the archive holds the answer with no vector on it" are not the same bytes on the
   * wire.
   *
   * <p>They were. {@code {"memories": []}} was the whole answer either way, and an agent reading it
   * concludes the archive is empty — stops asking, and writes back what was already there.
   */
  @Test
  void an_empty_recall_reports_how_many_memories_could_not_be_searched() throws Exception {
    when(archive.recall(anyString(), any(), anyInt())).thenReturn(new Archive.Recall(List.of(), 3));

    mvc.perform(
            post("/v1/memories/recall")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"question": "how many retries", "project": "payments"}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.memories").isEmpty())
        .andExpect(jsonPath("$.unsearchable").value(3));
  }

  @Test
  void recall_with_a_blank_question_is_400() throws Exception {
    mvc.perform(
            post("/v1/memories/recall")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"question": "   "}"""))
        .andExpect(status().isBadRequest());
  }

  /**
   * A recall whose question cannot be embedded is 503, not 500.
   *
   * <p>{@code EmbeddingException} was unmapped, so this reached the model as a bare 500 carrying
   * Spring's error document — which reads as "the archive is broken", a conclusion an agent acts on
   * by giving up. It is not broken: a side service is down, and the same request works once it is
   * back. 503 is the one status that says so, and the detail has to name the endpoint's own
   * complaint or the reader cannot tell which service to go and look at.
   */
  @Test
  void a_recall_whose_question_cannot_be_embedded_is_503_and_says_why() throws Exception {
    when(archive.recall(anyString(), any(), anyInt()))
        .thenThrow(new EmbeddingException("no route to host: localhost:1234"));

    mvc.perform(
            post("/v1/memories/recall")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"question": "how many retries"}"""))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("embedding_unavailable"))
        .andExpect(jsonPath("$.detail").value(containsString("no route to host: localhost:1234")));
  }

  // --- the stale client's verdict ------------------------------------------------

  /**
   * A client that names a verdict is refused, not quietly obeyed by halves.
   *
   * <p>Recorded by Task 8 and owed to Task 10. {@code WriteMemoryRequest} lost its {@code verdict}
   * component when the scribe took the judgement, but Jackson's unknown-property tolerance is on by
   * default under Spring Boot — so a stale client sending {@code "verdict": {"kind": "supersedes",
   * ...}} got a 200 and went away believing it had retired a memory that is still active and still
   * answering recalls. The field was never <em>honoured</em>, so the design held; what failed was
   * that nobody was told.
   *
   * <p>Refused before anything is written, and the archive is never touched: a caller told "no"
   * about a write that half happened is worse off than one told nothing.
   */
  @Test
  void a_write_naming_a_verdict_is_refused_rather_than_silently_dropped() throws Exception {
    mvc.perform(
            post("/v1/memories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"project": "payments",
                                 "verdict": {"kind": "supersedes", "target": "mem_1",
                                             "reason": "replaced"},
                                 "proposal": {"summary": "The retry budget is 6",
                                              "scope": "calling payments",
                                              "body": "Raised after the incident.",
                                              "formedBy": "an old client",
                                              "formedWhere": "a stale session"}}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("names a verdict")))
        .andExpect(jsonPath("$.detail").value(containsString("Nothing was written")));

    verify(archive, never()).applyVerdict(any(), any(), any(), any());
    verify(scribe, never()).judge(any(), any());
  }

  /**
   * An explicit {@code null} is a client serialising an absent field, which claims nothing.
   * Refusing it would refuse a caller already doing the right thing — and {@code HttpServerClient}
   * writes exactly this shape for the {@code project} key one line above, so it is not a
   * hypothetical.
   */
  @Test
  void a_write_whose_verdict_key_is_null_is_written_normally() throws Exception {
    when(archive.applyVerdict(any(), any(), any(), any()))
        .thenReturn(new WriteResult(VerdictKind.NEW, "mem_2", "first sighting", null, List.of()));

    mvc.perform(
            post("/v1/memories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"project": null, "verdict": null,
                                 "proposal": {"summary": "The retry budget is 6",
                                              "scope": "calling payments",
                                              "body": "Raised after the incident.",
                                              "formedBy": "a careful client",
                                              "formedWhere": "a session"}}"""))
        .andExpect(status().isOk());

    verify(archive).applyVerdict(any(), any(), eq(Home.global()), any());
  }

  /**
   * Every other unknown key is still tolerated, and that is the boundary of the rule rather than an
   * oversight.
   *
   * <p>Forward compatibility is what tolerance is for: a newer client sending a key this server has
   * not learned yet must not be refused by an older one. The rule is "a key that used to change
   * what happened must not be ignorable", and no other key ever did.
   */
  @Test
  void a_write_carrying_some_other_unknown_key_is_written_normally() throws Exception {
    when(archive.applyVerdict(any(), any(), any(), any()))
        .thenReturn(new WriteResult(VerdictKind.NEW, "mem_2", "first sighting", null, List.of()));

    mvc.perform(
            post("/v1/memories")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"project": "payments", "urgency": "high",
                                 "proposal": {"summary": "The retry budget is 6",
                                              "scope": "calling payments",
                                              "body": "Raised after the incident.",
                                              "formedBy": "a newer client",
                                              "formedWhere": "a session"}}"""))
        .andExpect(status().isOk());

    verify(archive).applyVerdict(any(), any(), eq(Home.of("payments")), any());
  }

  /**
   * A database nobody could reach is 503 and never 404.
   *
   * <p>The pair with {@code an_unknown_memory_is_404} above is the whole argument for {@code
   * ArchiveUnavailableException} being an unrelated type rather than a subclass. 404 means "the
   * archive does not hold that", which is an answer a caller can act on by writing the memory
   * instead. If a dead database gave the same status, a caller would act on an answer nobody gave.
   */
  @Test
  void a_read_the_archive_could_not_be_reached_for_is_503_and_not_404() throws Exception {
    when(archive.read(anyList()))
        .thenThrow(
            new ArchiveUnavailableException(
                "the archive could not be reached to read a memory by id",
                new IllegalStateException("Connection to localhost:5432 refused")));

    mvc.perform(get("/v1/memories/mem_000001"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("archive_unavailable"))
        .andExpect(jsonPath("$.detail").value(containsString("read a memory by id")))
        .andExpect(
            jsonPath("$.detail").value(containsString("says nothing about what is remembered")));
  }

  // --- POST /v1/memories/reembed ------------------------------------------------

  @Test
  void reembed_repairs_one_tier_and_reports_both_outcomes() throws Exception {
    when(archive.reembed(Home.of("payments")))
        .thenReturn(new Archive.Repair(List.of("mem_a"), List.of("mem_b")));

    mvc.perform(post("/v1/memories/reembed").param("project", "payments"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.repaired[0]").value("mem_a"))
        // Both lists reach the wire: "1 repaired" on its own cannot tell
        // an operator whether the endpoint is back.
        .andExpect(jsonPath("$.failed[0]").value("mem_b"));
  }

  // --- GET /v1/memories/index --------------------------------------------------

  @Test
  void index_with_a_project_param_resolves_that_project() throws Exception {
    when(archive.index(Home.of("payments")))
        .thenReturn(List.of(new TocEntry("mem_5", "s", "sc", false)));

    mvc.perform(get("/v1/memories/index").param("project", "payments"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("mem_5"));
  }

  @Test
  void index_with_no_project_param_resolves_global() throws Exception {
    when(archive.index(Home.global())).thenReturn(List.of());

    mvc.perform(get("/v1/memories/index")).andExpect(status().isOk());

    verify(archive).index(Home.global());
  }

  /**
   * A query param binds differently from a JSON body field — Spring gives {@code ""} for {@code
   * ?project=} rather than {@code null} — so the blank-is-refused rule is checked again here rather
   * than assumed from the write endpoint's own test of the same rule.
   */
  @Test
  void index_with_a_blank_project_param_is_400() throws Exception {
    mvc.perform(get("/v1/memories/index").param("project", "")).andExpect(status().isBadRequest());
  }

  // --- POST /v1/memories/{id}/invalidate ---------------------------------------

  @Test
  void invalidate_records_the_reason_and_returns_the_kept_memory() throws Exception {
    Memory dead =
        new Memory(
            "mem_6",
            "s",
            "sc",
            new Provenance(FORMED_AT, "scribe", "where"),
            MemoryState.INVALIDATED,
            false,
            3,
            null,
            "body",
            null,
            null,
            new Invalidation(FORMED_AT, "curator", "no longer true after the migration"),
            Home.global());
    when(archive.invalidate("mem_6", "no longer true after the migration", "curator"))
        .thenReturn(dead);

    mvc.perform(
            post("/v1/memories/mem_6/invalidate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"reason": "no longer true after the migration", "by": "curator"}"""))
        .andExpect(status().isOk())
        // "invalidated", not "INVALIDATED" — the memory is kept, its
        // state changes, and the wire spelling matches the contract.
        .andExpect(jsonPath("$.state").value("invalidated"))
        // Not deleted: the use count from before invalidation survives.
        .andExpect(jsonPath("$.uses").value(3));
  }

  @Test
  void invalidate_with_a_blank_reason_is_400() throws Exception {
    mvc.perform(
            post("/v1/memories/mem_6/invalidate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"reason": "  ", "by": "curator"}"""))
        .andExpect(status().isBadRequest());
  }

  @Test
  void invalidate_of_an_unknown_id_is_404() throws Exception {
    when(archive.invalidate(eq("mem_nope"), any(), any()))
        .thenThrow(new ArchiveException("no memory with id mem_nope"));

    mvc.perform(
            post("/v1/memories/mem_nope/invalidate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {"reason": "stale", "by": "curator"}"""))
        .andExpect(status().isNotFound());
  }

  /**
   * {@code demoted} is not dropped on the way to the wire: a write that pushes memories out of the
   * index must let the caller see which ones, or the index shrinks for a reason nothing surfaces.
   */
  @Test
  void write_surfaces_demoted_ids() throws Exception {
    WriteResult result =
        new WriteResult(
            VerdictKind.NEW, "mem_7", "first sighting", null, List.of("mem_old_1", "mem_old_2"));
    when(archive.applyVerdict(any(), any(), any(), any())).thenReturn(result);

    ArgumentCaptor<Verdict> verdictCaptor = ArgumentCaptor.forClass(Verdict.class);
    String body =
        """
                {"proposal": {"summary": "s", "scope": "sc", "body": "b",
                               "formedBy": "scribe", "formedWhere": ""}}""";

    mvc.perform(post("/v1/memories").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.demoted[0]").value("mem_old_1"))
        .andExpect(jsonPath("$.demoted[1]").value("mem_old_2"));

    verify(archive).applyVerdict(any(), verdictCaptor.capture(), any(), any());
    assertEquals(VerdictKind.NEW, verdictCaptor.getValue().kind());
  }
}
