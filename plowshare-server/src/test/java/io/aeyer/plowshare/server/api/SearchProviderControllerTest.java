package io.aeyer.plowshare.server.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.search.CostClass;
import io.aeyer.plowshare.protocol.search.NetworkTier;
import io.aeyer.plowshare.protocol.search.ProviderFacts;
import io.aeyer.plowshare.protocol.search.Verb;
import io.aeyer.plowshare.server.search.ProviderStore;
import io.aeyer.plowshare.server.search.Registration;
import io.aeyer.plowshare.server.search.SearchRegistrar;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The three operator routes, driven through MockMvc — this package's idiom for
 * a controller, following {@code ProjectControllerTest} and {@code
 * MemoryControllerTest}: no Spring context, no Postgres, and every status this
 * route can answer asserted as a status rather than inferred from a call it
 * made.
 *
 * <h2>A real {@link SearchRegistrar} over a mocked {@link ProviderStore}, not a
 * mocked registrar</h2>
 *
 * <p>{@code MemoryControllerTest} mocks its collaborator outright because
 * {@code ArchiveTest} proves the collaborator against a real database and the
 * only question left for the controller is which method it calls. That
 * division does not work here for the refusals, and getting it wrong is how
 * they came to be missing in the first place. The three cases below — a body
 * with no {@code baseUrl}, and a capabilities answer missing {@code costClass}
 * or {@code name} — used to reach {@code ApiExceptionHandler}'s {@code
 * Throwable} fallback as <b>500 "this is a fault in the server"</b>: the first
 * through {@code HttpUrl.parse(null)}, the second through {@code
 * facts.costClass().name()} inside {@link ProviderStore#upsert}, the third
 * through a {@code NOT NULL} column. A mocked registrar cannot fail that way
 * and so cannot prove it does not. The real registrar is therefore wired,
 * with only the store — the one collaborator that needs Postgres — mocked.
 *
 * <p>The provider side is a real {@link MockWebServer}, on {@code
 * SearchRegistrarTest}'s own reasoning: the thing under test is what this
 * stack does with an HTTP response, and a loopback socket is the only double
 * that is also a real socket. The store is asserted to be untouched on each
 * refusal, which is the fact that says the request was refused at the edge
 * rather than after a partial write.
 *
 * <p><b>What these tests do and do not see, measured rather than assumed.</b>
 * With the guards deleted, all three refusal tests below fail and the other
 * five still pass — so they are pinned to the guards and not to something
 * incidental. What they cannot see is <em>which</em> 500 the unguarded code
 * produced: {@code upsert} is mocked here, so the unguarded run dies one line
 * later on the read-back instead of inside the store or at a {@code NOT NULL}
 * column. The production failure modes named above are facts about the wired
 * stack, not something these assertions observe. What they observe is that a
 * caller's mistake leaves this route as a 400 naming the field.
 */
class SearchProviderControllerTest {

    private ProviderStore store;
    private MockMvc mvc;

    /** A complete capabilities answer. Each refusal test below removes exactly
     *  one field from this, so a refusal cannot pass for an unrelated reason. */
    private static final String FULL_FACTS = """
            {"providerKey":"searxng","name":"SearXNG","version":"0.1.0","description":"d",
             "verbs":["SEARCH"],"costClass":"FREE","networkTier":"INTERNAL_NETWORK",
             "maxResults":25,"maxQueryLength":512,"domainExclusion":true}""";

    private static final String NO_COST_CLASS = """
            {"providerKey":"searxng","name":"SearXNG","version":"0.1.0","description":"d",
             "verbs":["SEARCH"],"networkTier":"INTERNAL_NETWORK",
             "maxResults":25,"maxQueryLength":512,"domainExclusion":true}""";

    private static final String NO_NAME = """
            {"providerKey":"searxng","version":"0.1.0","description":"d",
             "verbs":["SEARCH"],"costClass":"FREE","networkTier":"INTERNAL_NETWORK",
             "maxResults":25,"maxQueryLength":512,"domainExclusion":true}""";

    @BeforeEach
    void setUp() {
        store = mock(ProviderStore.class);
        SearchRegistrar registrar =
                new SearchRegistrar(store, new OkHttpClient(), new ObjectMapper());
        mvc = MockMvcBuilders.standaloneSetup(new SearchProviderController(registrar, store))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockResponse capabilities(String body) {
        return new MockResponse().setBody(body).addHeader("Content-Type", "application/json");
    }

    private static Registration registered(String key, String baseUrl) {
        return new Registration(key, baseUrl,
                new ProviderFacts(key, "SearXNG", "0.1.0", "d", Set.of(Verb.SEARCH),
                        CostClass.FREE, NetworkTier.INTERNAL_NETWORK, 25, 512, true),
                null, null, 0);
    }

    private static String registerBody(String baseUrl) {
        return baseUrl == null ? "{}" : "{\"baseUrl\":\"" + baseUrl + "\"}";
    }

    // --- register ---------------------------------------------------------------

    @Test
    void registering_a_provider_that_answers_its_probe_is_201_and_the_row_it_wrote()
            throws Exception {
        try (MockWebServer provider = new MockWebServer()) {
            provider.enqueue(capabilities(FULL_FACTS));
            provider.start();
            String baseUrl = provider.url("/").toString();
            String stripped = baseUrl.substring(0, baseUrl.length() - 1);
            when(store.find("searxng")).thenReturn(Optional.of(registered("searxng", stripped)));

            mvc.perform(post("/v1/search/providers").contentType(MediaType.APPLICATION_JSON)
                            .content(registerBody(baseUrl)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.providerKey").value("searxng"))
                    .andExpect(jsonPath("$.baseUrl").value(stripped));
        }
    }

    /**
     * A body of {@code {}} — the shape a caller sends by forgetting one field,
     * and the shape that used to answer 500 through {@code
     * HttpUrl.parse(null)}.
     */
    @Test
    void a_body_with_no_base_url_is_400_and_nothing_is_written() throws Exception {
        mvc.perform(post("/v1/search/providers").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("baseUrl")));

        verify(store, never()).upsert(anyString(), anyString(), any());
    }

    @Test
    void a_base_url_that_is_not_http_is_400_and_nothing_is_written() throws Exception {
        mvc.perform(post("/v1/search/providers").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("file:///etc/passwd")))
                .andExpect(status().isBadRequest());

        verify(store, never()).upsert(anyString(), anyString(), any());
    }

    /**
     * A capabilities answer with no {@code costClass}. {@link ProviderFacts}
     * deserialises it happily — the field is simply null — and in the wired
     * stack {@link ProviderStore#upsert} then called {@code
     * facts.costClass().name()} on it, which is a {@link
     * NullPointerException} and a 500 about a fault in the server, for a
     * provider process that answered a probe wrong. See the class javadoc for
     * why that particular 500 is not what this test observes.
     */
    @Test
    void capabilities_missing_a_cost_class_is_400_and_nothing_is_written() throws Exception {
        try (MockWebServer provider = new MockWebServer()) {
            provider.enqueue(capabilities(NO_COST_CLASS));
            provider.start();

            mvc.perform(post("/v1/search/providers").contentType(MediaType.APPLICATION_JSON)
                            .content(registerBody(provider.url("/").toString())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(containsString("costClass")));

            verify(store, never()).upsert(anyString(), anyString(), any());
        }
    }

    /**
     * The other half of the same failure, which in the wired stack failed
     * differently: {@code name} is a {@code NOT NULL} column in {@code
     * V33__search_providers.sql}, so this one reached the database and came
     * back a {@code DataIntegrityViolationException} — also a 500, and with
     * none of the context an operator needs. Worth its own test rather than
     * folded into the one above, because the two arrive at {@link
     * SearchRegistrar} through different fields and a guard for one is not a
     * guard for the other.
     */
    @Test
    void capabilities_missing_a_name_is_400_and_nothing_is_written() throws Exception {
        try (MockWebServer provider = new MockWebServer()) {
            provider.enqueue(capabilities(NO_NAME));
            provider.start();

            mvc.perform(post("/v1/search/providers").contentType(MediaType.APPLICATION_JSON)
                            .content(registerBody(provider.url("/").toString())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(containsString("name")));

            verify(store, never()).upsert(anyString(), anyString(), any());
        }
    }

    // --- list -------------------------------------------------------------------

    @Test
    void listing_answers_the_stores_own_order() throws Exception {
        when(store.all()).thenReturn(List.of(
                registered("brave", "http://localhost:8084"),
                registered("searxng", "http://localhost:8086")));

        mvc.perform(get("/v1/search/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].providerKey").value("brave"))
                .andExpect(jsonPath("$[1].providerKey").value("searxng"));
    }

    // --- deregister --------------------------------------------------------------

    @Test
    void deregistering_a_registered_provider_is_204() throws Exception {
        when(store.remove("searxng")).thenReturn(true);

        mvc.perform(delete("/v1/search/providers/searxng"))
                .andExpect(status().isNoContent());
    }

    /** {@link ProviderStore#remove} answering {@code false} is not an error
     *  down there — see its javadoc — and this route is the one place it
     *  becomes a 404. */
    @Test
    void deregistering_something_that_was_never_registered_is_404() throws Exception {
        when(store.remove("ghost")).thenReturn(false);

        mvc.perform(delete("/v1/search/providers/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString("ghost")));
    }
}
