package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Invalidation;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.MemoryState;
import io.aeyer.plowshare.protocol.Provenance;
import io.aeyer.plowshare.protocol.Verdict;
import io.aeyer.plowshare.protocol.VerdictKind;
import io.aeyer.plowshare.protocol.WriteResult;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.scribe.Scribe;
import io.aeyer.plowshare.server.api.MemoryController;
import io.aeyer.plowshare.server.archive.Archive;
import io.aeyer.plowshare.server.archive.ArchiveException;
import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;
import io.aeyer.plowshare.server.archive.TocEntry;
import io.aeyer.plowshare.server.llm.EmbeddingException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * All six endpoints {@code MemoryController} answers, driven twice — once over
 * HTTP and once as a frame — off one request, asserting that the two surfaces
 * said the same thing.
 *
 * <h2>What every section asserts</h2>
 *
 * <p>The happy path and <b>at least one refusal</b>. A happy path agrees by
 * construction — both surfaces hand the same arguments to the same {@link
 * Archive} method and render the same answer from what comes back — so the
 * value of this file is in the refusals, which is where two surfaces drift: a
 * status is easy to agree on and a sentence is not. All four of this
 * controller's inline refusals came down into {@code requests} in the commit
 * that added these handlers, and each is measured here through {@link
 * FrameParity#assertSameRefusal}, which compares the words.
 *
 * <p><b>One mocked {@link Archive} and one mocked {@link Scribe}, shared by
 * both surfaces</b>, matching {@code MemoryControllerTest}: what the archive
 * does with a row is {@code ArchiveTest}'s subject against a real database, and
 * what the scribe decides is {@code ScribeTest}'s against a real dispatcher.
 * What is under test here is which method each surface calls, with which
 * arguments, and what each does with what comes back — including with what is
 * thrown.
 *
 * <p><b>Nothing in this controller's constructor is runtime state</b>, which is
 * worth saying because the task before this one found the opposite: {@code
 * ProjectController} takes a {@code PresenceRegistry}, and a frame handler
 * built over the store alone would have moved a project out from under a live
 * session with nothing failing. {@code MemoryController} takes two services and
 * calls two services; there is no registry, no session table and no cache
 * behind either, so building this area over the same two beans is the whole of
 * what parity needs here.
 */
class MemoryFramesTest {

    private static final Instant FORMED_AT = Instant.parse("2026-08-16T12:00:00Z");

    private static final String PROPOSAL = """
            {"summary": "The retry budget is 4 attempts",
             "scope": "calling the payments API",
             "body": "Four attempts since the timeout change.",
             "formedBy": "a careful client",
             "formedWhere": "a session"}""";

    private Archive archive;
    private Scribe scribe;
    private MockMvc mvc;
    private FrameRouter router;

    @BeforeEach
    void setUp() {
        archive = mock(Archive.class);
        scribe = mock(Scribe.class);
        // The controller validates before it judges, so a mock answering 0 here
        // would refuse every body as oversized. The number is the shipped
        // default, as MemoryControllerTest uses it.
        when(archive.maxBodyChars()).thenReturn(8000);
        when(scribe.judge(any(), any())).thenReturn(new Scribe.Judgement(
                new Verdict(VerdictKind.NEW, null, "filed flat: a stub scribe"), null));

        // FrameParity.endpointsOf and not a bare standaloneSetup: a memory
        // carries a formedAt, and the bare harness renders an Instant as a
        // number where a deployed server sends an ISO string. That class's
        // javadoc has the argument.
        mvc = FrameParity.endpointsOf(new MemoryController(archive, scribe));
        // Through the area rather than through a literal map: this is the
        // registration the handlers are reached by in production, and a test
        // that built its own map would pass for a type nobody wired.
        router = new FrameRoutingConfig()
                .frameRouter(List.of(new MemoryFrames(archive, scribe)));
    }

    // --- memory.write --------------------------------------------------------

    /**
     * Both surfaces file the same proposal into the same tier, under the
     * verdict the scribe returned, and answer with the same result.
     *
     * <p>Verified as well as compared: two surfaces could answer identical JSON
     * while one of them judged against the wrong tier, since the answer is
     * rendered from what the mocked archive returns either way — and judging a
     * project write against global is exactly the drift {@link Asking} exists
     * to make impossible, since a frame's project can only come from its
     * payload.
     */
    @Test
    void both_surfaces_file_the_same_proposal_under_the_scribes_verdict() throws Exception {
        when(archive.applyVerdict(any(), any(), any(), any())).thenReturn(new WriteResult(
                VerdictKind.NEW, "mem_1", "first sighting", null, List.of("mem_old")));

        MockHttpServletResponse http = posted("/v1/memories", """
                {"project": "payments", "proposal": %s}""".formatted(PROPOSAL));
        Outcome outcome = route(FrameTypes.MEMORY_WRITE, """
                {"project": "payments", "proposal": %s}""".formatted(PROPOSAL));

        assertEquals(Code.OK, outcome.code());
        FrameParity.assertSameAnswer(http, outcome);
        verify(scribe, times(2)).judge(any(), eq(Home.of("payments")));
        verify(archive, times(2)).applyVerdict(any(), any(), eq(Home.of("payments")), any());
    }

    /**
     * A write that also names a verdict is refused in the same words on both
     * surfaces, and nothing is written or judged on either.
     *
     * <p>The refusal this controller most needed to keep intact: a stale client
     * that is told nothing goes away believing it retired a memory that is
     * still active and still answering recalls, which is the whole reason the
     * {@code verdict} key is a tripwire rather than an ignored field. A frame
     * surface that dropped it silently would put that bug back on a new
     * transport.
     */
    @Test
    void a_write_naming_a_verdict_is_the_same_refusal_on_both_surfaces() throws Exception {
        String body = """
                {"project": "payments",
                 "verdict": {"kind": "supersedes", "target": "mem_1", "reason": "replaced"},
                 "proposal": %s}""".formatted(PROPOSAL);

        MockHttpServletResponse http = posted("/v1/memories", body);
        Outcome outcome = route(FrameTypes.MEMORY_WRITE, body);

        assertEquals(Code.BAD_REQUEST, outcome.code());
        FrameParity.assertSameRefusal(http, outcome);
        assertTrue(outcome.said().contains("Nothing was written"), outcome.said());
        verify(scribe, never()).judge(any(), any());
        verify(archive, never()).applyVerdict(any(), any(), any(), any());
    }

    /**
     * An explicit {@code "verdict": null} is a client serialising an absent
     * field, and is written normally on both surfaces — the boundary of the
     * rule above, and not an oversight.
     */
    @Test
    void a_write_whose_verdict_key_is_null_is_written_on_both_surfaces() throws Exception {
        when(archive.applyVerdict(any(), any(), any(), any())).thenReturn(new WriteResult(
                VerdictKind.NEW, "mem_2", "first sighting", null, List.of()));

        String body = """
                {"project": null, "verdict": null, "proposal": %s}""".formatted(PROPOSAL);
        MockHttpServletResponse http = posted("/v1/memories", body);
        Outcome outcome = route(FrameTypes.MEMORY_WRITE, body);

        assertEquals(Code.OK, outcome.code());
        FrameParity.assertSameAnswer(http, outcome);
        verify(archive, times(2)).applyVerdict(any(), any(), eq(Home.global()), any());
    }

    /**
     * A malformed proposal is the same 422 in the same words, and is refused
     * before the scribe is asked on either surface.
     *
     * <p>The ordering is the point rather than the status: judging first would
     * cost an embedding call and a model call before answering 422, and a frame
     * handler that called the service in the other order would spend that bill
     * while agreeing about the eventual answer.
     */
    @Test
    void a_malformed_proposal_is_the_same_422_before_the_scribe_is_asked() throws Exception {
        String body = """
                {"proposal": {"summary": "one line\\nand a second",
                              "scope": "calling the payments API",
                              "body": "Four attempts.",
                              "formedBy": "a careful client",
                              "formedWhere": "a session"}}""";

        MockHttpServletResponse http = posted("/v1/memories", body);
        Outcome outcome = route(FrameTypes.MEMORY_WRITE, body);

        assertEquals(Code.VALIDATION_FAILED, outcome.code());
        FrameParity.assertSameRefusal(http, outcome);
        verify(scribe, never()).judge(any(), any());
    }

    /** A write into a tier named by the empty string is the same 400 in the
     *  same words, rather than folded into global on either surface. */
    @Test
    void a_write_into_a_blank_tier_is_the_same_400_on_both_surfaces() throws Exception {
        String body = """
                {"project": "  ", "proposal": %s}""".formatted(PROPOSAL);

        MockHttpServletResponse http = posted("/v1/memories", body);
        Outcome outcome = route(FrameTypes.MEMORY_WRITE, body);

        assertEquals(Code.BAD_REQUEST, outcome.code());
        FrameParity.assertSameRefusal(http, outcome);
        verify(archive, never()).applyVerdict(any(), any(), any(), any());
    }

    // --- memory.recall -------------------------------------------------------

    /**
     * Both surfaces ask the archive the same question with the same limit, and
     * echo back the same question, the same limit and the same count of what
     * could not be searched.
     *
     * <p>The limit is the one neither request named: an absent field means
     * {@code MemoryController.DEFAULT_RECALL_LIMIT} on both surfaces, which is
     * why it is a fact about the request record rather than a number written
     * out at each call site.
     *
     * <p><b>This test alone cannot tell either surface from one that reads
     * neither field</b>, which is why the test below it exists: a request
     * naming no project and no limit reaches the archive with exactly the
     * arguments a handler that ignored both would pass.
     */
    @Test
    void both_surfaces_recall_the_same_memories_and_echo_the_same_limit() throws Exception {
        when(archive.recall(eq("how many retries"), eq(Home.global()), anyInt()))
                .thenReturn(new Archive.Recall(List.of(memory("mem_1", Home.global())), 2));

        MockHttpServletResponse http = posted("/v1/memories/recall", """
                {"question": "how many retries"}""");
        Outcome outcome = route(FrameTypes.MEMORY_RECALL, """
                {"question": "how many retries"}""");

        assertEquals(Code.OK, outcome.code());
        FrameParity.assertSameAnswer(http, outcome);
        verify(archive, times(2)).recall("how many retries", Home.global(), 10);
    }

    /**
     * A recall naming a tier and a limit reaches the archive with both, on
     * both surfaces.
     *
     * <p><b>The pair of defaults above is indistinguishable from no reading at
     * all.</b> {@code RequestedHome.in(null)} is {@code Home.global()} and
     * {@code limitOrDefault()} on an absent field is the default, so a handler
     * that dropped its payload's {@code project} and its {@code limit}
     * altogether would answer that test byte for byte and satisfy its {@code
     * verify} line as well. Naming both here is what makes the two readable
     * apart.
     *
     * <p>The project half is the one this whole suite exists for: a frame that
     * recalled from {@code global} while its payload named {@code payments}
     * would be answering one tier's question out of another tier's memories,
     * across a surface whose project can only come from the payload.
     */
    @Test
    void both_surfaces_recall_from_the_named_tier_under_the_named_limit() throws Exception {
        when(archive.recall(eq("how many retries"), eq(Home.of("payments")), anyInt()))
                .thenReturn(new Archive.Recall(List.of(memory("mem_1", Home.of("payments"))), 0));

        String body = """
                {"question": "how many retries", "project": "payments", "limit": 3}""";
        MockHttpServletResponse http = posted("/v1/memories/recall", body);
        Outcome outcome = route(FrameTypes.MEMORY_RECALL, body);

        assertEquals(Code.OK, outcome.code());
        FrameParity.assertSameAnswer(http, outcome);
        verify(archive, times(2)).recall("how many retries", Home.of("payments"), 3);
    }

    /** A recall that asks nothing is the same refusal in the same words, and
     *  the archive is never asked on either surface. */
    @Test
    void a_recall_with_no_question_is_the_same_refusal_on_both_surfaces() throws Exception {
        MockHttpServletResponse http = posted("/v1/memories/recall", """
                {"question": "   ", "project": "payments"}""");
        Outcome outcome = route(FrameTypes.MEMORY_RECALL, """
                {"question": "   ", "project": "payments"}""");

        assertEquals(Code.BAD_REQUEST, outcome.code());
        FrameParity.assertSameRefusal(http, outcome);
        verify(archive, never()).recall(anyString(), any(), anyInt());
    }

    /**
     * A question that cannot be embedded is the same 503 in the same words —
     * "a side service is down", which a reader acts on differently from "the
     * archive is broken".
     */
    @Test
    void a_question_that_cannot_be_embedded_is_the_same_503_on_both_surfaces() throws Exception {
        when(archive.recall(anyString(), any(), anyInt())).thenThrow(new EmbeddingException(
                "the question could not be embedded", new IllegalStateException("no endpoint")));

        MockHttpServletResponse http = posted("/v1/memories/recall", """
                {"question": "how many retries"}""");
        Outcome outcome = route(FrameTypes.MEMORY_RECALL, """
                {"question": "how many retries"}""");

        assertEquals(Code.EMBEDDING_UNAVAILABLE, outcome.code());
        FrameParity.assertSameRefusal(http, outcome);
    }

    // --- memory.reembed ------------------------------------------------------

    /** Both surfaces repair the same tier and report both lists: "1 repaired"
     *  on its own cannot tell an operator whether the endpoint is back. */
    @Test
    void both_surfaces_repair_the_same_tier_and_report_both_lists() throws Exception {
        when(archive.reembed(Home.of("payments")))
                .thenReturn(new Archive.Repair(List.of("mem_a"), List.of("mem_b")));

        MockHttpServletResponse http = mvc.perform(
                        post("/v1/memories/reembed").param("project", "payments"))
                .andReturn().getResponse();
        Outcome outcome = route(FrameTypes.MEMORY_REEMBED, """
                {"project": "payments"}""");

        assertEquals(Code.OK, outcome.code());
        FrameParity.assertSameAnswer(http, outcome);
        verify(archive, times(2)).reembed(Home.of("payments"));
    }

    /**
     * A repair of a tier named by the empty string is the same 400 in the same
     * words.
     *
     * <p>Worth its own test rather than assumed from the write's: a query
     * parameter binds differently from a body field — Spring hands {@code ""}
     * where Jackson hands {@code null} — and the payload this frame carries is
     * a record of that query string rather than of a body, so the two are the
     * same rule read off two different shapes.
     */
    @Test
    void a_repair_of_a_blank_tier_is_the_same_400_on_both_surfaces() throws Exception {
        MockHttpServletResponse http = mvc.perform(
                        post("/v1/memories/reembed").param("project", ""))
                .andReturn().getResponse();
        Outcome outcome = route(FrameTypes.MEMORY_REEMBED, """
                {"project": ""}""");

        assertEquals(Code.BAD_REQUEST, outcome.code());
        FrameParity.assertSameRefusal(http, outcome);
        verify(archive, never()).reembed(any());
    }

    // --- memory.read ---------------------------------------------------------

    /**
     * Both surfaces read one memory through the counting path.
     *
     * <p>{@code read} and not {@code get}, verified rather than inferred: a
     * lookup by id is a use in the same sense a recall hit is, and a handler
     * that reached for the non-counting door would answer with the identical
     * memory while letting it decay as if nothing had ever asked for it.
     */
    @Test
    void both_surfaces_read_the_same_memory_through_the_counting_path() throws Exception {
        when(archive.read(List.of("mem_1")))
                .thenReturn(List.of(memory("mem_1", Home.of("payments"))));

        MockHttpServletResponse http = read("/v1/memories/mem_1");
        Outcome outcome = route(FrameTypes.MEMORY_READ, """
                {"memory": "mem_1"}""");

        assertEquals(Code.OK, outcome.code());
        FrameParity.assertSameAnswer(http, outcome);
        verify(archive, times(2)).read(List.of("mem_1"));
    }

    /** A memory nobody wrote is the same 404 in the same words, and not a 200
     *  carrying a null a caller cannot tell from an empty memory. */
    @Test
    void a_memory_nobody_wrote_is_the_same_404_on_both_surfaces() throws Exception {
        when(archive.read(anyList()))
                .thenThrow(new ArchiveException("no memory with id mem_nope"));

        MockHttpServletResponse http = read("/v1/memories/mem_nope");
        Outcome outcome = route(FrameTypes.MEMORY_READ, """
                {"memory": "mem_nope"}""");

        assertEquals(Code.NOT_FOUND, outcome.code());
        FrameParity.assertSameRefusal(http, outcome);
    }

    /** An archive that could not be reached is the same 503 and not a 404: a
     *  caller told "no such memory" stops looking for one that is there. */
    @Test
    void a_read_the_archive_could_not_be_reached_for_is_the_same_503() throws Exception {
        when(archive.read(anyList())).thenThrow(new ArchiveUnavailableException(
                "the archive could not be reached to read mem_1",
                new IllegalStateException("no connection")));

        MockHttpServletResponse http = read("/v1/memories/mem_1");
        Outcome outcome = route(FrameTypes.MEMORY_READ, """
                {"memory": "mem_1"}""");

        assertEquals(Code.ARCHIVE_UNAVAILABLE, outcome.code());
        FrameParity.assertSameRefusal(http, outcome);
    }

    /** A frame that names no memory at all is refused — a request only this
     *  surface can receive, since a URL naming none is a different URL. */
    @Test
    void a_read_naming_no_memory_is_the_frame_surfaces_own_refusal() {
        Outcome outcome = route(FrameTypes.MEMORY_READ, "{}");

        assertEquals(Code.BAD_REQUEST, outcome.code());
        assertTrue(outcome.said().contains("memory.read needs its payload to say which"),
                outcome.said());
        verify(archive, never()).read(anyList());
    }

    // --- memory.index --------------------------------------------------------

    /** Both surfaces survey the same tier. */
    @Test
    void both_surfaces_index_the_same_tier() throws Exception {
        when(archive.index(Home.of("payments")))
                .thenReturn(List.of(new TocEntry("mem_5", "a summary", "a scope", true)));

        MockHttpServletResponse http = mvc.perform(
                        get("/v1/memories/index").param("project", "payments"))
                .andReturn().getResponse();
        Outcome outcome = route(FrameTypes.MEMORY_INDEX, """
                {"project": "payments"}""");

        assertEquals(Code.OK, outcome.code());
        FrameParity.assertSameAnswer(http, outcome);
        verify(archive, times(2)).index(Home.of("payments"));
    }

    /** An index naming no tier surveys global on both surfaces, rather than
     *  refusing: an omitted project is the global tier, which is the rule
     *  {@code Home} carries and neither surface may reinvent. */
    @Test
    void an_index_naming_no_tier_surveys_global_on_both_surfaces() throws Exception {
        when(archive.index(Home.global())).thenReturn(List.of());

        MockHttpServletResponse http = read("/v1/memories/index");
        Outcome outcome = route(FrameTypes.MEMORY_INDEX, "{}");

        assertEquals(Code.OK, outcome.code());
        FrameParity.assertSameAnswer(http, outcome);
        verify(archive, times(2)).index(Home.global());
    }

    /** A tier named by the empty string is the same 400 in the same words, and
     *  is not folded into global on either surface. */
    @Test
    void an_index_of_a_blank_tier_is_the_same_400_on_both_surfaces() throws Exception {
        MockHttpServletResponse http = mvc.perform(
                        get("/v1/memories/index").param("project", ""))
                .andReturn().getResponse();
        Outcome outcome = route(FrameTypes.MEMORY_INDEX, """
                {"project": ""}""");

        assertEquals(Code.BAD_REQUEST, outcome.code());
        FrameParity.assertSameRefusal(http, outcome);
        verify(archive, never()).index(any());
    }

    // --- memory.invalidate ---------------------------------------------------

    /** Both surfaces retire the same memory with the same reason and the same
     *  signature, and answer with the memory that was kept. */
    @Test
    void both_surfaces_retire_the_same_memory_with_the_same_reason() throws Exception {
        when(archive.invalidate(eq("mem_6"), anyString(), anyString())).thenReturn(retired());

        MockHttpServletResponse http = posted("/v1/memories/mem_6/invalidate", """
                {"reason": "no longer true after the migration", "by": "curator"}""");
        Outcome outcome = route(FrameTypes.MEMORY_INVALIDATE, """
                {"memory": "mem_6", "reason": "no longer true after the migration",
                 "by": "curator"}""");

        assertEquals(Code.OK, outcome.code());
        FrameParity.assertSameAnswer(http, outcome);
        verify(archive, times(2))
                .invalidate("mem_6", "no longer true after the migration", "curator");
    }

    /** A tombstone that explains nothing is the same refusal in the same words,
     *  and nothing is retired on either surface. */
    @Test
    void an_invalidation_with_no_reason_is_the_same_refusal_on_both_surfaces() throws Exception {
        MockHttpServletResponse http = posted("/v1/memories/mem_6/invalidate", """
                {"reason": "  ", "by": "curator"}""");
        Outcome outcome = route(FrameTypes.MEMORY_INVALIDATE, """
                {"memory": "mem_6", "reason": "  ", "by": "curator"}""");

        assertEquals(Code.BAD_REQUEST, outcome.code());
        FrameParity.assertSameRefusal(http, outcome);
        verify(archive, never()).invalidate(anyString(), anyString(), anyString());
    }

    /**
     * A tombstone nobody signed is the same refusal in the same words, and a
     * different one from the reason's.
     *
     * <p>Both fields are checked on both surfaces, in the same order, so a
     * caller that got one of them wrong is told which — and a handler that
     * checked only the first would agree with the endpoint on every body that
     * names a reason.
     */
    @Test
    void an_invalidation_nobody_signed_is_the_same_refusal_on_both_surfaces() throws Exception {
        MockHttpServletResponse http = posted("/v1/memories/mem_6/invalidate", """
                {"reason": "no longer true", "by": ""}""");
        Outcome outcome = route(FrameTypes.MEMORY_INVALIDATE, """
                {"memory": "mem_6", "reason": "no longer true", "by": ""}""");

        assertEquals(Code.BAD_REQUEST, outcome.code());
        FrameParity.assertSameRefusal(http, outcome);
        assertTrue(outcome.said().contains("by"), outcome.said());
        verify(archive, never()).invalidate(anyString(), anyString(), anyString());
    }

    /** Retiring a memory nobody wrote is the same 404 in the same words. */
    @Test
    void invalidating_a_memory_nobody_wrote_is_the_same_404_on_both_surfaces() throws Exception {
        when(archive.invalidate(eq("mem_nope"), anyString(), anyString()))
                .thenThrow(new ArchiveException("no memory with id mem_nope"));

        MockHttpServletResponse http = posted("/v1/memories/mem_nope/invalidate", """
                {"reason": "stale", "by": "curator"}""");
        Outcome outcome = route(FrameTypes.MEMORY_INVALIDATE, """
                {"memory": "mem_nope", "reason": "stale", "by": "curator"}""");

        assertEquals(Code.NOT_FOUND, outcome.code());
        FrameParity.assertSameRefusal(http, outcome);
    }

    // --- the surface's own two rules, for all six types -----------------------

    /**
     * Every type this area claims ignores a field this build has never heard of
     * — spec §3.2's "the payload is tolerant", asserted once per type.
     */
    @Test
    void every_type_in_this_area_ignores_a_field_this_build_has_never_heard_of()
            throws Exception {
        when(archive.applyVerdict(any(), any(), any(), any())).thenReturn(new WriteResult(
                VerdictKind.NEW, "mem_1", "first sighting", null, List.of()));
        when(archive.recall(anyString(), any(), anyInt()))
                .thenReturn(new Archive.Recall(List.of(), 0));
        when(archive.read(anyList())).thenReturn(List.of(memory("mem_1", Home.global())));
        when(archive.index(any())).thenReturn(List.of());
        when(archive.reembed(any())).thenReturn(new Archive.Repair(List.of(), List.of()));
        when(archive.invalidate(anyString(), anyString(), anyString())).thenReturn(retired());

        for (Map.Entry<String, String> each : representativePayloads().entrySet()) {
            FrameParity.assertUnknownFieldsAreIgnored(router, each.getKey(), each.getValue());
        }
    }

    /**
     * The production routing table really does claim all six of this
     * controller's types.
     *
     * <p>Built from every {@link FrameArea} Spring would collect: an
     * unregistered type is answered with a perfectly well-formed {@code
     * NOT_FOUND}, so a handler nobody wired looks from the outside exactly like
     * a handler nobody wrote.
     */
    @Test
    void the_production_routing_table_claims_every_memory_type() {
        FrameRouter wired = FrameAreas.router();

        for (String type : representativePayloads().keySet()) {
            assertTrue(wired.types().contains(type),
                    type + " is not in the production table: " + wired.types());
        }
    }

    /**
     * The two other {@code /v1/memories/*} endpoints are not this area's, and
     * nothing here claims them.
     *
     * <p>{@code POST /v1/memories/navigate} and {@code POST
     * /v1/memories/digest} share this controller's URL prefix and belong to
     * {@code DigestController}, which the breadth plan batches with the small
     * controllers. The plan's ruling about an endpoint that embeds its refusal
     * in a 200 names {@code navigate}, so it is worth recording where that
     * ruling actually lands: not here. A later task claiming {@code
     * memory.navigate} out of this area would be claiming another controller's
     * endpoint under this noun.
     */
    @Test
    void the_digest_controllers_two_endpoints_are_not_claimed_here() {
        for (String type : new MemoryFrames(archive, scribe).frames().keySet()) {
            assertTrue(!type.equals("memory.navigate") && !type.equals("memory.digest"),
                    "DigestController's endpoints are not this area's: " + type);
        }
    }

    /** One payload per type this area claims, good enough to reach the handler. */
    private static Map<String, String> representativePayloads() {
        return Map.ofEntries(
                Map.entry(FrameTypes.MEMORY_WRITE, "{\"proposal\":" + PROPOSAL + "}"),
                Map.entry(FrameTypes.MEMORY_RECALL, "{\"question\":\"how many retries\"}"),
                Map.entry(FrameTypes.MEMORY_REEMBED, "{\"project\":\"payments\"}"),
                Map.entry(FrameTypes.MEMORY_READ, "{\"memory\":\"mem_1\"}"),
                Map.entry(FrameTypes.MEMORY_INDEX, "{\"project\":\"payments\"}"),
                Map.entry(FrameTypes.MEMORY_INVALIDATE,
                        "{\"memory\":\"mem_6\",\"reason\":\"stale\",\"by\":\"curator\"}"));
    }

    // --- fixtures ------------------------------------------------------------

    private static Memory memory(String id, Home home) {
        return new Memory(id, "The retry budget is 4 attempts", "Calling the payments API",
                new Provenance(FORMED_AT, "scribe", "during the mTLS migration"),
                MemoryState.ACTIVE, false, 0, null, "Four attempts since the timeout change.",
                null, null, null, home);
    }

    private static Memory retired() {
        return new Memory("mem_6", "The retry budget is 4 attempts", "Calling the payments API",
                new Provenance(FORMED_AT, "scribe", "during the mTLS migration"),
                MemoryState.INVALIDATED, false, 3, null, "Four attempts.", null, null,
                new Invalidation(FORMED_AT, "curator", "no longer true after the migration"),
                Home.global());
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
                .andReturn().getResponse();
    }
}
