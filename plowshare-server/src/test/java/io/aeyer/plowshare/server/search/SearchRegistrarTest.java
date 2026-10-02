package io.aeyer.plowshare.server.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.faults.CallerFault;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link SearchRegistrar} against a real Postgres, following {@code
 * ProviderStoreTest}'s shape: no Spring context, a Testcontainers Postgres
 * migrated once for the class, and a fresh store per test. The provider side
 * of {@code /v1/search/capabilities} is a real {@link MockWebServer} rather
 * than a stand-in for OkHttp, on the same reasoning {@code
 * OpenAiTransportTest} gives for using it against the LLM transport: the
 * thing under test is what this class does with an HTTP response, and a
 * loopback server is the only double that is also a real socket.
 */
@Testcontainers
class SearchRegistrarTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static JdbcTemplate jdbc;

    private ProviderStore store;
    private SearchRegistrar registrar;

    private static final String FACTS = """
        {"providerKey":"searxng","name":"SearXNG","version":"0.1.0","description":"d",
         "verbs":["SEARCH"],"costClass":"FREE","networkTier":"INTERNAL_NETWORK",
         "maxResults":25,"maxQueryLength":512,"domainExclusion":true}""";

    private static MockResponse facts(String body) {
        return new MockResponse().setBody(body).addHeader("Content-Type", "application/json");
    }

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void emptyRegistry() {
        jdbc.execute("TRUNCATE TABLE search_providers");
        store = new ProviderStore(jdbc);
        registrar = new SearchRegistrar(store, new OkHttpClient(), new ObjectMapper());
    }

    @Test
    void registering_asks_the_provider_what_it_is_and_files_the_answer() throws Exception {
        try (MockWebServer provider = new MockWebServer()) {
            provider.enqueue(facts(FACTS));
            provider.start();

            Registration r = registrar.register(provider.url("/").toString());

            assertEquals("searxng", r.providerKey());
            assertEquals(25, r.facts().maxResults());
            assertEquals("/v1/search/capabilities", provider.takeRequest().getPath());
        }
    }

    @Test
    void a_trailing_slash_is_stripped_before_the_row_is_written() throws Exception {
        try (MockWebServer provider = new MockWebServer()) {
            provider.enqueue(facts(FACTS));
            provider.start();
            assertFalse(registrar.register(provider.url("/").toString()).baseUrl().endsWith("/"));
        }
    }

    @Test
    void a_provider_that_does_not_answer_is_refused_at_registration_and_not_at_search() {
        CallerFault refused = assertThrows(CallerFault.class,
                () -> registrar.register("http://127.0.0.1:1"));
        assertTrue(refused.getMessage().contains("did not answer"));
        assertTrue(store.all().isEmpty());
    }

    @Test
    void a_provider_advertising_no_key_is_refused() throws Exception {
        try (MockWebServer provider = new MockWebServer()) {
            provider.enqueue(facts("{\"name\":\"anonymous\"}"));
            provider.start();
            CallerFault refused = assertThrows(CallerFault.class,
                    () -> registrar.register(provider.url("/").toString()));
            assertTrue(refused.getMessage().contains("providerKey"));
        }
    }

    @Test
    void a_provider_that_declares_no_search_verb_is_refused() throws Exception {
        try (MockWebServer provider = new MockWebServer()) {
            provider.enqueue(facts(FACTS.replace("\"SEARCH\"", "\"FETCH\"")));
            provider.start();
            CallerFault refused = assertThrows(CallerFault.class,
                    () -> registrar.register(provider.url("/").toString()));
            assertTrue(refused.getMessage().contains("SEARCH"));
        }
    }

    @Test
    void a_base_url_that_is_not_http_is_refused_before_anything_is_dialled() {
        CallerFault refused = assertThrows(CallerFault.class,
                () -> registrar.register("file:///etc/passwd"));
        assertTrue(refused.getMessage().contains("http"));
    }

    // -- The four refusals added after the branch review. Each of these
    // reached ApiExceptionHandler's Throwable fallback as a 500 saying "this
    // is a fault in the server, not in the request" -- false for every one of
    // them, and the one claim that handler's own javadoc says a status must
    // never make. SearchProviderControllerTest proves the status; these prove
    // the refusal happens against a REAL ProviderStore, which is what says
    // the row was never written rather than written and rolled back.

    /** {@code POST /v1/search/providers} with a body of {@code {}} arrives
     *  here as a null, and {@code HttpUrl.parse(null)} throws rather than
     *  answering null. */
    @Test
    void a_null_base_url_is_refused_rather_than_dereferenced() {
        CallerFault refused =
                assertThrows(CallerFault.class, () -> registrar.register(null));

        assertTrue(refused.getMessage().contains("baseUrl"));
        assertTrue(store.all().isEmpty());
    }

    @Test
    void a_blank_base_url_is_refused_the_same_way() {
        assertThrows(CallerFault.class, () -> registrar.register("   "));
        assertTrue(store.all().isEmpty());
    }

    /** Reached {@code facts.costClass().name()} inside {@code
     *  ProviderStore.upsert} — a {@link NullPointerException} on a real
     *  store, which is why this test uses one. */
    @Test
    void a_provider_declaring_no_cost_class_is_refused_and_no_row_is_written() throws Exception {
        try (MockWebServer provider = new MockWebServer()) {
            provider.enqueue(facts(FACTS.replace("\"costClass\":\"FREE\",", "")));
            provider.start();

            CallerFault refused = assertThrows(CallerFault.class,
                    () -> registrar.register(provider.url("/").toString()));

            assertTrue(refused.getMessage().contains("costClass"));
            assertTrue(store.all().isEmpty());
        }
    }

    @Test
    void a_provider_declaring_no_network_tier_is_refused_and_no_row_is_written()
            throws Exception {
        try (MockWebServer provider = new MockWebServer()) {
            provider.enqueue(facts(FACTS.replace("\"networkTier\":\"INTERNAL_NETWORK\",", "")));
            provider.start();

            CallerFault refused = assertThrows(CallerFault.class,
                    () -> registrar.register(provider.url("/").toString()));

            assertTrue(refused.getMessage().contains("networkTier"));
            assertTrue(store.all().isEmpty());
        }
    }

    /**
     * {@code name} and {@code version} are {@code NOT NULL} columns in {@code
     * V33__search_providers.sql}, so before the guard this reached the
     * database and came back a {@code DataIntegrityViolationException} —
     * which {@code ApiExceptionHandler} does not classify either. The real
     * store here is load-bearing: a mocked one cannot tell this test apart
     * from a guard that does nothing.
     */
    @Test
    void a_provider_answering_with_no_name_is_refused_before_the_not_null_column_is()
            throws Exception {
        try (MockWebServer provider = new MockWebServer()) {
            provider.enqueue(facts(FACTS.replace("\"name\":\"SearXNG\",", "")));
            provider.start();

            CallerFault refused = assertThrows(CallerFault.class,
                    () -> registrar.register(provider.url("/").toString()));

            assertTrue(refused.getMessage().contains("name"));
            assertTrue(store.all().isEmpty());
        }
    }

    @Test
    void a_provider_answering_with_a_blank_version_is_refused_too() throws Exception {
        try (MockWebServer provider = new MockWebServer()) {
            provider.enqueue(facts(FACTS.replace("\"version\":\"0.1.0\"", "\"version\":\"  \"")));
            provider.start();

            CallerFault refused = assertThrows(CallerFault.class,
                    () -> registrar.register(provider.url("/").toString()));

            assertTrue(refused.getMessage().contains("version"));
            assertTrue(store.all().isEmpty());
        }
    }
}
