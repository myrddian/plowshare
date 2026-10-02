package io.aeyer.plowshare.ext.brave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.aeyer.plowshare.protocol.search.AnswerStatus;
import io.aeyer.plowshare.protocol.search.CostClass;
import io.aeyer.plowshare.protocol.search.Hit;
import io.aeyer.plowshare.protocol.search.NetworkTier;
import io.aeyer.plowshare.protocol.search.ProviderFacts;
import io.aeyer.plowshare.protocol.search.SearchAnswer;
import io.aeyer.plowshare.protocol.search.SearchAsk;
import io.aeyer.plowshare.protocol.search.Verb;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@link BraveController}, written by analogy to {@code SearxngControllerTest}.
 * The differences from that class are the point of this module and are called
 * out test by test rather than left implicit: {@link CostClass#METERED} and
 * {@link NetworkTier#PUBLIC_INTERNET} instead of free and internal, and —
 * load-bearing — {@code domainExclusion} is {@code false} here, so the ignore
 * list this class is handed must never reach the upstream request, on the
 * opposite assertion {@code SearxngControllerTest} makes for a provider that
 * does declare support.
 *
 * <p>No AssertJ in this repository; assertions below are plain JUnit 5.
 */
class BraveControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static BraveController controllerAt(String baseUrl) {
        BraveProperties properties = new BraveProperties(baseUrl, 2_000, 25);
        BraveClient client = new BraveClient(new OkHttpClient(), JSON, properties, "test-key");
        return new BraveController(client, properties);
    }

    /** Any controller works for a check that never dials out. */
    private static BraveController controller() {
        return controllerAt("http://127.0.0.1:1");
    }

    /** Port 1 on loopback: nothing answers there, on {@code RemoteSearchProviderTest}'s trick. */
    private static BraveController deadUpstreamController() {
        return controllerAt("http://127.0.0.1:1");
    }

    @Test
    void the_capabilities_say_what_this_provider_is() {
        ProviderFacts caps = controller().capabilities();
        assertEquals("brave", caps.providerKey());
        assertEquals(Set.of(Verb.SEARCH), caps.verbs());
        assertEquals(CostClass.METERED, caps.costClass());
        assertEquals(NetworkTier.PUBLIC_INTERNET, caps.networkTier());
        assertFalse(caps.domainExclusion());
    }

    @Test
    void an_upstream_failure_is_a_failed_answer_and_never_a_thrown_exception() {
        SearchAnswer a = deadUpstreamController().search(new SearchAsk("r1", "q", 10, List.of()));
        assertEquals(AnswerStatus.FAILED, a.status());
        assertNotNull(a.message());
        assertFalse(a.message().isBlank());
    }

    @Test
    void an_upstream_http_failure_keeps_the_actionable_response_body() throws Exception {
        try (MockWebServer remote = new MockWebServer()) {
            remote.enqueue(new MockResponse().setResponseCode(401)
                    .setBody("{\"message\":\"API key is missing or invalid\"}"));
            remote.start();

            Logger logger = (Logger) LoggerFactory.getLogger(BraveController.class);
            ListAppender<ILoggingEvent> warnings = new ListAppender<>();
            warnings.start();
            logger.addAppender(warnings);
            SearchAnswer answer;
            try {
                answer = controllerAt(remote.url("/").toString())
                        .search(new SearchAsk("r1", "secret query", 10, List.of()));
            } finally {
                logger.detachAppender(warnings);
                warnings.stop();
            }

            assertEquals(AnswerStatus.FAILED, answer.status());
            assertTrue(answer.message().contains("401"));
            assertTrue(answer.message().contains("API key is missing or invalid"));
            String logged = warnings.list.getFirst().getFormattedMessage();
            assertTrue(logged.contains("providerKey=brave"), logged);
            assertTrue(logged.contains("requestId=r1"), logged);
            assertTrue(logged.contains("API key is missing or invalid"), logged);
            assertFalse(logged.contains("secret query"), logged);
        }
    }

    /**
     * The opposite of {@code SearxngControllerTest}'s exclusion test, and the
     * whole reason this class declares {@code domainExclusion} {@code false}:
     * Brave's API has no exclusion parameter, so an ignore list hitting this
     * provider has nowhere to go. This assertion is what makes that {@code
     * false} meaningful rather than vacuous — see the class javadoc and Task
     * 5's conditional-hint test on the Plowshare side.
     */
    @Test
    void the_ignore_list_never_reaches_the_upstream_request() throws Exception {
        try (MockWebServer remote = new MockWebServer()) {
            remote.enqueue(new MockResponse()
                    .addHeader("Content-Type", "application/json")
                    .setBody("{\"web\":{\"results\":[]}}"));
            remote.start();

            BraveController controller = controllerAt(remote.url("/").toString());
            controller.search(new SearchAsk("r1", "q", 10, List.of("foxnews.com")));

            RecordedRequest recorded = remote.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(recorded);
            assertFalse(recorded.getPath().contains("foxnews.com"));
        }
    }

    @Test
    void hits_come_back_with_the_three_fields_a_model_reads() throws Exception {
        try (MockWebServer remote = new MockWebServer()) {
            remote.enqueue(new MockResponse()
                    .addHeader("Content-Type", "application/json")
                    .setBody("""
                        {"web":{"results":[{"url":"https://a.example","title":"A","description":"s"}]}}"""));
            remote.start();

            BraveController controller = controllerAt(remote.url("/").toString());
            SearchAnswer a = controller.search(new SearchAsk("r1", "q", 10, List.of()));

            assertEquals(1, a.hits().size());
            Hit hit = a.hits().get(0);
            assertFalse(hit.url().isBlank());
            assertFalse(hit.title().isBlank());
            assertNotNull(hit.snippet());
        }
    }

    /**
     * Same reasoning as {@code SearxngControllerTest}'s equivalent: {@link
     * SearchAsk} cannot be built with a blank query, so the only way to reach
     * the controller's own handling of one is the JSON body a real caller
     * sends, and the answer must be {@code FAILED} rather than an HTTP 400.
     */
    @Test
    void a_blank_query_answers_failed_rather_than_400() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controllerAt("http://127.0.0.1:1")).build();
        String body = """
            {"requestId":"r1","query":"   ","max":10,"ignoredDomains":[]}""";

        String responseBody = mvc.perform(post("/v1/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        SearchAnswer answer = JSON.readValue(responseBody, SearchAnswer.class);
        assertEquals(AnswerStatus.FAILED, answer.status());
        assertNotNull(answer.message());
        assertFalse(answer.message().isBlank());
    }
}
