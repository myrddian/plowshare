package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.protocol.search.CostClass;
import io.aeyer.plowshare.protocol.search.NetworkTier;
import io.aeyer.plowshare.protocol.search.ProviderFacts;
import io.aeyer.plowshare.protocol.search.Verb;
import io.aeyer.plowshare.server.api.SearchProviderController;
import io.aeyer.plowshare.server.search.ProviderStore;
import io.aeyer.plowshare.server.search.Registration;
import io.aeyer.plowshare.server.search.SearchRegistrar;
import java.util.List;
import java.util.Map;
import java.util.Set;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The two provider endpoints that get a frame, driven twice — once over HTTP and once as a frame —
 * off one request, asserting that the two surfaces said the same thing.
 *
 * <h2>The third endpoint is not here, and that is the ruling rather than a gap</h2>
 *
 * <p>{@code POST /v1/search/providers} is operational: it is the curl-shaped target of {@code
 * bin/plowshare-searxng}, it makes this server dial a URL of the caller's choosing, and §4.1 of the
 * socket design keeps HTTP for "health, auth, and what ops genuinely needs". It gets no frame,
 * {@code client.Capabilities} says so in its own words, and {@link
 * #registering_a_provider_is_not_one_of_this_areas_types} is what stops the decision being quietly
 * reversed by somebody adding a type to the map.
 *
 * <h2>A real {@link SearchRegistrar} over a mocked {@link ProviderStore}</h2>
 *
 * <p>{@code SearchProviderControllerTest}'s own arrangement, and here it is load-bearing for a
 * second reason: the 404 a caller of {@code DELETE /v1/search/providers/&#123;key&#125;} reads is
 * the registrar's now — it came out of the controller in this commit, so that a frame handler would
 * not have had to restate its sentence — and a mocked registrar could not refuse anything to
 * compare.
 */
class ProviderFramesTest {

  private ProviderStore store;
  private MockMvc mvc;
  private FrameRouter router;

  @BeforeEach
  void setUp() {
    store = mock(ProviderStore.class);
    SearchRegistrar registrar = new SearchRegistrar(store, new OkHttpClient(), new ObjectMapper());
    mvc = FrameParity.endpointsOf(new SearchProviderController(registrar, store));
    router = new FrameRoutingConfig().frameRouter(List.of(new ProviderFrames(registrar, store)));
  }

  // --- provider.list -------------------------------------------------------

  /** Both surfaces answer the store's own order, row for row. */
  @Test
  void both_surfaces_list_every_registered_provider() throws Exception {
    when(store.all())
        .thenReturn(
            List.of(
                registered("brave", "http://localhost:8084"),
                registered("searxng", "http://localhost:8086")));

    MockHttpServletResponse http =
        mvc.perform(get("/v1/search/providers")).andReturn().getResponse();
    Outcome outcome = route(FrameTypes.PROVIDER_LIST, "{}");

    assertEquals(Code.OK, outcome.code());
    FrameParity.assertSameAnswer(http, outcome);
    verify(store, times(2)).all();
  }

  // --- provider.deregister -------------------------------------------------

  /** A row that was there is removed and neither surface sends a body. */
  @Test
  void both_surfaces_remove_the_row_and_answer_with_no_content() throws Exception {
    when(store.remove("searxng")).thenReturn(true);

    MockHttpServletResponse http =
        mvc.perform(delete("/v1/search/providers/searxng")).andReturn().getResponse();
    Outcome outcome = route(FrameTypes.PROVIDER_DEREGISTER, "{\"provider\":\"searxng\"}");

    // NO_CONTENT and not OK: Outcome.ok() would be a well-formed answer
    // carrying nothing, which is a different status from the one the
    // endpoint really sends.
    assertEquals(Code.NO_CONTENT, outcome.code());
    FrameParity.assertSameEmptyAnswer(http, outcome);
    verify(store, times(2)).remove("searxng");
  }

  /**
   * A key nothing is registered under is the same 404 in the same words on both surfaces.
   *
   * <p>The refusal that made this endpoint worth a step 1 of its own: {@code ProviderStore.remove}
   * answering {@code false} is not an error down there, and turning it into a 404 used to be the
   * controller's. A handler restating that sentence is exactly the drift this comparison catches,
   * so the decision moved into {@link SearchRegistrar#deregister} instead.
   */
  @Test
  void a_key_nothing_is_registered_under_is_the_same_404_on_both_surfaces() throws Exception {
    when(store.remove("ghost")).thenReturn(false);

    MockHttpServletResponse http =
        mvc.perform(delete("/v1/search/providers/ghost")).andReturn().getResponse();
    Outcome outcome = route(FrameTypes.PROVIDER_DEREGISTER, "{\"provider\":\"ghost\"}");

    assertEquals(Code.NOT_FOUND, outcome.code());
    FrameParity.assertSameRefusal(http, outcome);
    assertTrue(outcome.said().contains("ghost"), outcome.said());
  }

  /**
   * A frame that names no provider is refused by this surface alone, and says which field to send —
   * a URL naming no key is a different URL, which Spring answers before a controller is reached.
   */
  @Test
  void a_frame_that_names_no_provider_says_which_field_to_send() {
    Outcome outcome = route(FrameTypes.PROVIDER_DEREGISTER, "{}");

    assertEquals(Code.BAD_REQUEST, outcome.code());
    assertTrue(
        outcome.said().contains("provider.deregister needs its payload to say which"),
        outcome.said());
    verify(store, never()).remove(anyString());
  }

  // --- the endpoint that is deliberately absent ----------------------------

  /**
   * Registering a provider has no frame, by a ruling and not by an oversight.
   *
   * <p>Asserted rather than left to the {@code Capabilities} note, because the note argues the
   * decision and this is what would fail if somebody added the type anyway.
   */
  @Test
  void registering_a_provider_is_not_one_of_this_areas_types() {
    for (String type :
        new ProviderFrames(
                new SearchRegistrar(store, new OkHttpClient(), new ObjectMapper()), store)
            .frames()
            .keySet()) {
      assertFalse(
          type.contains("register") && !type.contains("deregister"),
          "POST /v1/search/providers is operational and gets no frame: " + type);
    }
  }

  // --- the surface's own two rules -----------------------------------------

  @Test
  void every_type_in_this_area_ignores_a_field_this_build_has_never_heard_of() throws Exception {
    when(store.all()).thenReturn(List.of());
    when(store.remove(anyString())).thenReturn(true);

    for (Map.Entry<String, String> each : representativePayloads().entrySet()) {
      FrameParity.assertUnknownFieldsAreIgnored(router, each.getKey(), each.getValue());
    }
  }

  @Test
  void the_production_routing_table_claims_every_provider_type() {
    FrameRouter wired = FrameAreas.router();

    for (String type : representativePayloads().keySet()) {
      assertTrue(
          wired.types().contains(type), type + " is not in the production table: " + wired.types());
    }
  }

  private static Map<String, String> representativePayloads() {
    return Map.of(
        FrameTypes.PROVIDER_LIST, "{}",
        FrameTypes.PROVIDER_DEREGISTER, "{\"provider\":\"searxng\"}");
  }

  private static Registration registered(String key, String baseUrl) {
    return new Registration(
        key,
        baseUrl,
        new ProviderFacts(
            key,
            "SearXNG",
            "0.1.0",
            "d",
            Set.of(Verb.SEARCH),
            CostClass.FREE,
            NetworkTier.INTERNAL_NETWORK,
            25,
            512,
            true),
        null,
        null,
        0);
  }

  private Outcome route(String type, String payload) {
    return router.route(FrameParity.frame(type, payload), FrameParity.ASKING);
  }
}
