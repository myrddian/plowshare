package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.digests.Digester;
import io.aeyer.plowshare.server.agents.digests.Digests;
import io.aeyer.plowshare.server.agents.digests.MemoryProperties;
import io.aeyer.plowshare.server.agents.digests.Navigator;
import io.aeyer.plowshare.server.api.DigestController;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Both endpoints {@code DigestController} answers, driven twice — once over
 * HTTP and once as a frame — off one request, asserting that the two surfaces
 * said the same thing.
 *
 * <h2>These two are {@code DigestController}'s, despite their path</h2>
 *
 * <p>{@code POST /v1/memories/navigate} and {@code POST /v1/memories/digest}
 * sit under {@code MemoryController}'s URL prefix and belong to this one. A
 * path prefix is not a controller; {@code MemoryFramesTest} carries the
 * matching guard from the other side, so neither area can take the other's.
 *
 * <h2>A real {@link Digests} over mocked leaves</h2>
 *
 * <p>The four decisions this controller used to hold inline live there now —
 * two budgets, an ending classification, an ordering — so a mocked {@code
 * Digests} would be a mock of the thing under test. {@code DigestsTest}
 * measures those as decisions; what this file measures is that the two surfaces
 * reach them with the same arguments and render what comes back the same way.
 * The properties are set to numbers {@link MemoryProperties} does not ship, so
 * a handler that reached for a default of its own still fails here.
 */
class DigestFramesTest {

    private Navigator navigator;
    private Digester digester;
    private JobStore jobs;
    private MemoryProperties properties;
    private MockMvc mvc;
    private FrameRouter router;

    @BeforeEach
    void setUp() {
        navigator = mock(Navigator.class);
        digester = mock(Digester.class);
        jobs = mock(JobStore.class);
        properties = new MemoryProperties();
        properties.setNavigationBudget(17);
        properties.setDigestBudget(23);

        Digests digests = new Digests(navigator, digester, properties, jobs);
        mvc = FrameParity.endpointsOf(new DigestController(digests));
        router = new FrameRoutingConfig().frameRouter(List.of(new DigestFrames(digests)));
    }

    // --- memory.navigate -----------------------------------------------------

    /** Both surfaces ask the same tier the same question, on the operator's own
     *  allowance, and answer with the same result. */
    @Test
    void both_surfaces_navigate_the_same_tier_with_the_same_question() throws Exception {
        when(navigator.navigate(any(), anyString(), any(), any()))
                .thenReturn(new Navigator.Result(
                        "root", List.of("dig_1"), "four attempts", true, 3));

        String asked = """
                {"project": "payments", "question": "how many retries"}""";
        MockHttpServletResponse http = posted("/v1/memories/navigate", asked);
        Outcome outcome = route(FrameTypes.MEMORY_NAVIGATE, asked);

        assertEquals(Code.OK, outcome.code());
        FrameParity.assertSameAnswer(http, outcome);

        // Captured rather than matched: Budget has no equals, so eq(Budget.of(17))
        // would compare identities and fail against two correct allowances.
        ArgumentCaptor<Budget> allowance = ArgumentCaptor.captor();
        verify(navigator, times(2)).navigate(
                eq(Home.of("payments")), eq("how many retries"), allowance.capture(), any());
        for (Budget given : allowance.getAllValues()) {
            assertEquals(17, given.limit(), "both surfaces asked on the operator's own budget");
        }
    }

    /**
     * <b>A navigation that found nothing is a 200 on both surfaces, and the
     * frame does not promote it to a refusal code.</b>
     *
     * <p>The breadth plan's ruling, asserted rather than only written down:
     * this endpoint answers a domain miss inside the body — an incomplete walk,
     * an empty id list, prose saying how far it got — and clients read it there.
     * A frame that answered {@code BAD_REQUEST} or invented a code for this
     * would be a second contract for one read, and the fix somebody reached for
     * later would break every client that had learned the first.
     */
    @Test
    void a_navigation_that_found_nothing_is_the_same_two_hundred_on_both_surfaces()
            throws Exception {
        when(navigator.navigate(any(), anyString(), any(), any()))
                .thenReturn(new Navigator.Result(
                        "root", List.of(), "no digest covers this yet", false, 17));

        String asked = """
                {"question": "what did we decide about mTLS"}""";
        MockHttpServletResponse http = posted("/v1/memories/navigate", asked);
        Outcome outcome = route(FrameTypes.MEMORY_NAVIGATE, asked);

        assertEquals(Code.OK, outcome.code(), "the refusal stays inside the body");
        FrameParity.assertSameAnswer(http, outcome);
    }

    /** A navigation with no question is the same refusal on both surfaces, and
     *  the navigator is never asked on either. */
    @Test
    void a_navigation_with_no_question_is_the_same_refusal_on_both_surfaces() throws Exception {
        MockHttpServletResponse http = posted("/v1/memories/navigate", """
                {"project": "payments"}""");
        Outcome outcome = route(FrameTypes.MEMORY_NAVIGATE, """
                {"project": "payments"}""");

        assertEquals(Code.BAD_REQUEST, outcome.code());
        FrameParity.assertSameRefusal(http, outcome);
        assertEquals("A question is required", outcome.said());
        verify(navigator, never()).navigate(any(), anyString(), any(), any());
    }

    /** A blank tier is refused rather than read as global, in the same words. */
    @Test
    void a_navigation_of_a_blank_tier_is_the_same_refusal_on_both_surfaces() throws Exception {
        String asked = """
                {"project": "   ", "question": "anything"}""";
        MockHttpServletResponse http = posted("/v1/memories/navigate", asked);
        Outcome outcome = route(FrameTypes.MEMORY_NAVIGATE, asked);

        assertEquals(Code.BAD_REQUEST, outcome.code());
        FrameParity.assertSameRefusal(http, outcome);
        verify(navigator, never()).navigate(any(), anyString(), any(), any());
    }

    // --- memory.digest -------------------------------------------------------

    /** Both surfaces submit the same pass and answer 202 with the same handle. */
    @Test
    void both_surfaces_start_the_same_digest_pass() throws Exception {
        when(jobs.submit(eq(Digests.AGENT), any())).thenReturn("job_9");

        String asked = """
                {"project": "payments", "question": "ignored by this verb"}""";
        MockHttpServletResponse http = posted("/v1/memories/digest", asked);
        Outcome outcome = route(FrameTypes.MEMORY_DIGEST, asked);

        // ACCEPTED and not OK: a pass is many model calls in series, and what
        // comes back is a handle to poll.
        assertEquals(Code.ACCEPTED, outcome.code());
        FrameParity.assertSameAnswer(http, outcome);
        verify(jobs, times(2)).submit(eq(Digests.AGENT), any());
    }

    /**
     * A blank tier is refused before anything is submitted, on both surfaces.
     *
     * <p>The ordering, not the status: resolution happens on the calling thread
     * so a blank project fails fast. A handler that resolved inside the
     * submitted closure would answer 202 and lose the refusal on a worker,
     * which is a drift no comparison of two bodies can see — hence the
     * {@code never()} below.
     */
    @Test
    void a_digest_of_a_blank_tier_is_refused_before_either_surface_submits() throws Exception {
        String asked = """
                {"project": "   ", "question": "anything"}""";
        MockHttpServletResponse http = posted("/v1/memories/digest", asked);
        Outcome outcome = route(FrameTypes.MEMORY_DIGEST, asked);

        assertEquals(Code.BAD_REQUEST, outcome.code());
        FrameParity.assertSameRefusal(http, outcome);
        verify(jobs, never()).submit(anyString(), any());
    }

    // --- the surface's own rules ---------------------------------------------

    @Test
    void every_type_in_this_area_ignores_a_field_this_build_has_never_heard_of()
            throws Exception {
        when(navigator.navigate(any(), anyString(), any(), any()))
                .thenReturn(new Navigator.Result("root", List.of(), "text", true, 0));
        when(jobs.submit(eq(Digests.AGENT), any())).thenReturn("job_9");

        for (Map.Entry<String, String> each : representativePayloads().entrySet()) {
            FrameParity.assertUnknownFieldsAreIgnored(router, each.getKey(), each.getValue());
        }
    }

    @Test
    void the_production_routing_table_claims_both_digest_types() {
        FrameRouter wired = FrameAreas.router();

        for (String type : representativePayloads().keySet()) {
            assertTrue(wired.types().contains(type),
                    type + " is not in the production table: " + wired.types());
        }
    }

    /**
     * This area claims exactly the two endpoints its controller answers, and
     * nothing of {@code MemoryController}'s.
     *
     * <p>The other half of {@code MemoryFramesTest}'s guard. The two files
     * together are what keeps a shared URL prefix from becoming a shared area
     * the next time somebody reads the paths rather than the controllers.
     */
    @Test
    void this_area_claims_neither_of_the_memory_controllers_types() {
        Map<String, FrameHandler> claimed =
                new DigestFrames(new Digests(navigator, digester, properties, jobs)).frames();

        assertEquals(representativePayloads().keySet(), claimed.keySet());
    }

    private static Map<String, String> representativePayloads() {
        return Map.of(
                FrameTypes.MEMORY_NAVIGATE, "{\"question\":\"how many retries\"}",
                FrameTypes.MEMORY_DIGEST, "{\"project\":\"payments\"}");
    }

    private Outcome route(String type, String payload) {
        return router.route(FrameParity.frame(type, payload), FrameParity.ASKING);
    }

    private MockHttpServletResponse posted(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
    }
}
