package io.aeyer.plowshare.ext.searxng;

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
import io.aeyer.plowshare.protocol.search.Hit;
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
 * {@link SearxngController} against a real {@link MockWebServer}, on
 * {@code RemoteSearchProviderTest}'s reasoning: the thing under test is what
 * this class does with an HTTP request and response, and a loopback server is
 * the only double that is also a real socket.
 *
 * <p>No AssertJ in this repository; assertions below are plain JUnit 5.
 */
class SearxngControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static SearxngController controllerAt(String baseUrl) {
        SearxngProperties properties = new SearxngProperties(baseUrl, 2_000, 25);
        SearxngClient client = new SearxngClient(new OkHttpClient(), JSON, properties);
        return new SearxngController(client, properties);
    }

    /** Any controller works for a check that never dials out. */
    private static SearxngController controller() {
        return controllerAt("http://127.0.0.1:1");
    }

    /** Port 1 on loopback: nothing answers there, on {@code RemoteSearchProviderTest}'s trick. */
    private static SearxngController deadUpstreamController() {
        return controllerAt("http://127.0.0.1:1");
    }

    @Test
    void the_capabilities_say_what_this_provider_is() {
        ProviderFacts caps = controller().capabilities();
        assertEquals("searxng", caps.providerKey());
        assertEquals(Set.of(Verb.SEARCH), caps.verbs());
        assertTrue(caps.domainExclusion());
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
            remote.enqueue(new MockResponse().setResponseCode(403)
                    .setBody("JSON output is disabled in settings.yml"));
            remote.start();

            Logger logger = (Logger) LoggerFactory.getLogger(SearxngController.class);
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
            assertTrue(answer.message().contains("403"));
            assertTrue(answer.message().contains("JSON output is disabled"));
            String logged = warnings.list.getFirst().getFormattedMessage();
            assertTrue(logged.contains("providerKey=searxng"), logged);
            assertTrue(logged.contains("requestId=r1"), logged);
            assertTrue(logged.contains("JSON output is disabled"), logged);
            assertFalse(logged.contains("secret query"), logged);
        }
    }

    @Test
    void the_ignore_list_is_turned_into_an_upstream_exclusion() throws Exception {
        try (MockWebServer remote = new MockWebServer()) {
            remote.enqueue(new MockResponse()
                    .addHeader("Content-Type", "application/json")
                    .setBody("{\"results\":[]}"));
            remote.start();

            SearxngController controller = controllerAt(remote.url("/").toString());
            controller.search(new SearchAsk("r1", "q", 10, List.of("foxnews.com")));

            RecordedRequest recorded = remote.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(recorded);
            assertTrue(recorded.getPath().contains("foxnews.com"));
        }
    }

    @Test
    void hits_come_back_with_the_three_fields_a_model_reads() throws Exception {
        try (MockWebServer remote = new MockWebServer()) {
            remote.enqueue(new MockResponse()
                    .addHeader("Content-Type", "application/json")
                    .setBody("""
                        {"results":[{"url":"https://a.example","title":"A","content":"s"}]}"""));
            remote.start();

            SearxngController controller = controllerAt(remote.url("/").toString());
            SearchAnswer a = controller.search(new SearchAsk("r1", "q", 10, List.of()));

            assertEquals(1, a.hits().size());
            Hit hit = a.hits().get(0);
            assertFalse(hit.url().isBlank());
            assertFalse(hit.title().isBlank());
            assertNotNull(hit.snippet());
        }
    }

    /**
     * {@link SearchAsk}'s canonical constructor refuses a blank query before
     * this test could ever build one, so the only way to exercise the
     * controller's own handling of a blank query is through the JSON body a
     * real caller sends — {@link SearxngController#receive} deliberately binds
     * a plain record with no validation of its own, constructs the domain
     * {@link SearchAsk} inside a try block, and turns the constructor's
     * {@link IllegalArgumentException} into a {@link SearchAnswer#failed}
     * rather than letting Spring's message-not-readable handling turn it into
     * a 400. A 400 here would make {@code RemoteSearchProvider}'s non-2xx path
     * fire on the Plowshare side, which collapses the real message down to a
     * generic "remote returned status 400" — see that class's javadoc.
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
