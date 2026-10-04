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

import io.aeyer.plowshare.protocol.fetch.FetchWindow;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.FetchController;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.fetch.FetchService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The one endpoint {@code FetchController} answers, driven twice — once over HTTP and once as a
 * frame — off one request.
 *
 * <h2>{@code SearchFramesTest}'s shape, verbatim, because the endpoints are</h2>
 *
 * <p>{@code FetchController}'s own javadoc says it is {@code SearchController}'s shape word for
 * word, and the same two kinds of "did not work" travel the same two ways out of {@link
 * FetchService}: a suppressed domain or a dead host is {@link FetchWindow#refusal()} inside a 200,
 * and a blank or unparseable {@code url} is a {@link CallerFault} before the store or the fetcher
 * is touched. Both are compared below.
 */
class FetchFramesTest {

  private FetchService service;
  private MockMvc mvc;
  private FrameRouter router;

  @BeforeEach
  void setUp() {
    service = mock(FetchService.class);
    mvc = FrameParity.endpointsOf(new FetchController(service));
    router = new FrameRoutingConfig().frameRouter(List.of(new FetchFrames(service)));
  }

  /** Both surfaces read the same window of the same page. */
  @Test
  void both_surfaces_read_the_same_window() throws Exception {
    when(service.read("https://example.test/a", 2048))
        .thenReturn(
            new FetchWindow(
                "https://example.test/a",
                "A title",
                "the next few thousand characters",
                2048,
                4096,
                9000,
                true,
                null));

    String asked =
        """
                {"url": "https://example.test/a", "offset": 2048}""";
    MockHttpServletResponse http = posted(asked);
    Outcome outcome = route(asked);

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(service, times(2)).read("https://example.test/a", 2048);
  }

  /**
   * <b>A domain miss is a 200 on both surfaces, and the frame does not promote it to a refusal
   * code.</b>
   *
   * <p>The breadth plan's ruling, asserted rather than only written down: a suppressed domain, a
   * dead host, or a stored row that aged out from under a stale paging offset is prose a caller
   * reads and acts on, carried in {@link FetchWindow#refusal()}. A frame answering a failure {@link
   * Code} here would be a second contract for one read, and every client that had learned the first
   * would break when somebody later "fixed" it back.
   */
  @Test
  void a_refusal_inside_the_window_is_the_same_two_hundred_on_both_surfaces() throws Exception {
    when(service.read(anyString(), anyInt()))
        .thenReturn(
            new FetchWindow(
                "https://example.test/a", null, "", 0, 0, 0, false, "that host did not answer"));

    String asked =
        """
                {"url": "https://example.test/a", "offset": 0}""";
    MockHttpServletResponse http = posted(asked);
    Outcome outcome = route(asked);

    assertEquals(Code.OK, outcome.code(), "the refusal stays inside the body");
    FrameParity.assertSameAnswer(http, outcome);
  }

  /**
   * A URL that is not one is the same 400 in the same words on both surfaces — the other kind of
   * "did not work".
   */
  @Test
  void a_url_that_does_not_parse_is_the_same_refusal_on_both_surfaces() throws Exception {
    when(service.read(anyString(), anyInt()))
        .thenThrow(new CallerFault("url must be a URL, and 'not a url' is not one"));

    String asked =
        """
                {"url": "not a url", "offset": 0}""";
    MockHttpServletResponse http = posted(asked);
    Outcome outcome = route(asked);

    assertEquals(Code.BAD_REQUEST, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
  }

  @Test
  void this_type_ignores_a_field_this_build_has_never_heard_of() throws Exception {
    when(service.read(anyString(), anyInt()))
        .thenReturn(new FetchWindow("https://example.test/a", null, "", 0, 0, 0, false, null));

    FrameParity.assertUnknownFieldsAreIgnored(
        router,
        FrameTypes.WEB_FETCH,
        """
                {"url": "https://example.test/a", "offset": 0}""");
  }

  @Test
  void the_production_routing_table_claims_this_type() {
    assertTrue(
        FrameAreas.router().types().contains(FrameTypes.WEB_FETCH),
        FrameTypes.WEB_FETCH + " is not in the production table");
  }

  private Outcome route(String payload) {
    return router.route(FrameParity.frame(FrameTypes.WEB_FETCH, payload), FrameParity.ASKING);
  }

  private MockHttpServletResponse posted(String body) throws Exception {
    return mvc.perform(post("/v1/fetch").contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn()
        .getResponse();
  }
}
