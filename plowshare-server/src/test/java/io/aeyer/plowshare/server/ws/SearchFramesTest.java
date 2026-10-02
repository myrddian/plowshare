package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.protocol.search.Hit;
import io.aeyer.plowshare.protocol.search.SearchPage;
import io.aeyer.plowshare.server.api.SearchController;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.search.SearchService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The one endpoint {@code SearchController} answers, driven twice — once over
 * HTTP and once as a frame — off one request.
 *
 * <h2>One mocked {@link SearchService}, which is the whole of the behaviour</h2>
 *
 * <p>That class is a door's worth of controller over a service that carries
 * everything: the ladder, the stored sets, the refusals. What is under test
 * here is that the frame reaches the same method with the same four arguments
 * and renders what comes back the same way — including, below, the two
 * different kinds of "did not work" that travel two different ways out of it.
 */
class SearchFramesTest {

    private SearchService service;
    private MockMvc mvc;
    private FrameRouter router;

    @BeforeEach
    void setUp() {
        service = mock(SearchService.class);
        mvc = FrameParity.endpointsOf(new SearchController(service));
        router = new FrameRoutingConfig().frameRouter(List.of(new SearchFrames(service)));
    }

    /** Both surfaces ask the same question with the same paging, and answer
     *  with the same page. */
    @Test
    void both_surfaces_ask_the_same_search() throws Exception {
        when(service.search("how many retries", 10, 25, 1)).thenReturn(new SearchPage(
                List.of(new Hit("https://example.test/a", "A title", "a snippet")),
                1, 10, 1, false, null));

        String asked = """
                {"query": "how many retries", "pageSize": 10, "max": 25, "page": 1}""";
        MockHttpServletResponse http = posted(asked);
        Outcome outcome = route(asked);

        assertEquals(Code.OK, outcome.code());
        FrameParity.assertSameAnswer(http, outcome);
        verify(service, times(2)).search("how many retries", 10, 25, 1);
    }

    /**
     * <b>A domain miss is a 200 on both surfaces, and the frame does not
     * promote it to a refusal code.</b>
     *
     * <p>The breadth plan's ruling, asserted rather than only written down. An
     * exhausted ladder or an expired stored set comes back as {@code
     * SearchPage.refusal} inside an otherwise ordinary page, because that text
     * is prose a model reads and acts on — search again, accept fewer hits —
     * and not a mistake this route is refusing. A frame that answered some
     * failure {@link Code} here would be a second contract for one read.
     */
    @Test
    void a_refusal_inside_the_page_is_the_same_two_hundred_on_both_surfaces() throws Exception {
        when(service.search(anyString(), anyInt(), anyInt(), anyInt())).thenReturn(
                new SearchPage(List.of(), 3, 10, 0, false,
                        "There is no stored result set for this query, or it has expired"));

        String asked = """
                {"query": "how many retries", "pageSize": 10, "max": 25, "page": 3}""";
        MockHttpServletResponse http = posted(asked);
        Outcome outcome = route(asked);

        assertEquals(Code.OK, outcome.code(), "the refusal stays inside the body");
        FrameParity.assertSameAnswer(http, outcome);
    }

    /**
     * A request malformed on its face is the same 400 in the same words on both
     * surfaces — the other kind of "did not work", which the service raises as
     * a {@link CallerFault} before it does anything at all.
     */
    @Test
    void a_blank_query_is_the_same_refusal_on_both_surfaces() throws Exception {
        when(service.search(anyString(), anyInt(), anyInt(), anyInt()))
                .thenThrow(new CallerFault("query must not be blank"));

        String asked = """
                {"query": "   ", "pageSize": 10, "max": 25, "page": 1}""";
        MockHttpServletResponse http = posted(asked);
        Outcome outcome = route(asked);

        assertEquals(Code.BAD_REQUEST, outcome.code());
        FrameParity.assertSameRefusal(http, outcome);
    }

    @Test
    void this_type_ignores_a_field_this_build_has_never_heard_of() throws Exception {
        when(service.search(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new SearchPage(List.of(), 1, 10, 0, false, null));

        FrameParity.assertUnknownFieldsAreIgnored(router, FrameTypes.WEB_SEARCH, """
                {"query": "how many retries", "pageSize": 10, "max": 25, "page": 1}""");
    }

    @Test
    void the_production_routing_table_claims_this_type() {
        assertTrue(FrameAreas.router().types().contains(FrameTypes.WEB_SEARCH),
                FrameTypes.WEB_SEARCH + " is not in the production table");
    }

    private Outcome route(String payload) {
        return router.route(
                FrameParity.frame(FrameTypes.WEB_SEARCH, payload), FrameParity.ASKING);
    }

    private MockHttpServletResponse posted(String body) throws Exception {
        return mvc.perform(post("/v1/search")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
    }
}
