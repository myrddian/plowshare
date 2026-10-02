package io.aeyer.plowshare.server.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.search.AnswerStatus;
import io.aeyer.plowshare.protocol.search.CostClass;
import io.aeyer.plowshare.protocol.search.NetworkTier;
import io.aeyer.plowshare.protocol.search.ProviderFacts;
import io.aeyer.plowshare.protocol.search.SearchAnswer;
import io.aeyer.plowshare.protocol.search.SearchAsk;
import io.aeyer.plowshare.protocol.search.Verb;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

/**
 * {@link RemoteSearchProvider} against a real {@link MockWebServer}, on the
 * same reasoning {@code SearchRegistrarTest} gives for using one against
 * {@code SearchRegistrar}: the thing under test is what this class does with
 * an HTTP request and response, and a loopback server is the only double that
 * is also a real socket — a stubbed {@code OkHttpClient} would only ever
 * prove this class calls the stub the way the test told it to.
 *
 * <p>No AssertJ in this repository (see {@code build.gradle.kts}); assertions
 * below are plain JUnit 5.
 */
class RemoteSearchProviderTest {

    private final RemoteSearchProvider provider =
            new RemoteSearchProvider(new OkHttpClient(), new ObjectMapper(), Duration.ofSeconds(2));

    private static MockResponse ok(String body) {
        return new MockResponse().addHeader("Content-Type", "application/json").setBody(body);
    }

    private static Registration registrationAt(MockWebServer remote) {
        return registrationAt(remote.url("/").toString(), true);
    }

    private static Registration registrationWithoutExclusionAt(MockWebServer remote) {
        return registrationAt(remote.url("/").toString(), false);
    }

    private static Registration registrationAt(String baseUrl) {
        return registrationAt(baseUrl, true);
    }

    private static Registration registrationAt(String baseUrl, boolean domainExclusion) {
        String stripped = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        ProviderFacts facts = new ProviderFacts(
                "p", "P", "1.0", "d", Set.of(Verb.SEARCH), CostClass.FREE,
                NetworkTier.INTERNAL_NETWORK, 25, 512, domainExclusion);
        return new Registration(facts.providerKey(), stripped, facts, null, null, 0);
    }

    @Test
    void hits_come_back_as_the_provider_sent_them() throws Exception {
        try (MockWebServer remote = new MockWebServer()) {
            remote.enqueue(ok("""
                {"requestId":"r1","providerKey":"searxng","status":"SUCCESS","elapsedMs":12,
                 "hits":[{"url":"https://a.example","title":"A","snippet":"s"}]}"""));
            remote.start();

            SearchAnswer a = provider.search(registrationAt(remote), new SearchAsk("r1", "q", 10, List.of()));

            assertEquals(AnswerStatus.SUCCESS, a.status());
            assertEquals(1, a.hits().size());
            assertEquals("https://a.example", a.hits().get(0).url());
            assertEquals("/v1/search", remote.takeRequest().getPath());
        }
    }

    @Test
    void a_non_2xx_becomes_a_failed_answer_and_never_an_exception() throws Exception {
        try (MockWebServer remote = new MockWebServer()) {
            remote.enqueue(new MockResponse().setResponseCode(503));
            remote.start();
            SearchAnswer a = provider.search(registrationAt(remote), new SearchAsk("r1", "q", 10, List.of()));
            assertEquals(AnswerStatus.FAILED, a.status());
            assertTrue(a.message().contains("503"));
        }
    }

    @Test
    void a_dead_provider_becomes_a_failed_answer_carrying_the_cause() {
        SearchAnswer a = provider.search(
                registrationAt("http://127.0.0.1:1"), new SearchAsk("r1", "q", 10, List.of()));
        assertEquals(AnswerStatus.FAILED, a.status());
        assertNotNull(a.message());
        assertFalse(a.message().isBlank());
    }

    @Test
    void unparseable_json_is_a_failed_answer_rather_than_a_thrown_mapping_error() throws Exception {
        try (MockWebServer remote = new MockWebServer()) {
            remote.enqueue(new MockResponse().setBody("not json"));
            remote.start();
            SearchAnswer a = provider.search(registrationAt(remote), new SearchAsk("r1", "q", 10, List.of()));
            assertEquals(AnswerStatus.FAILED, a.status());
        }
    }

    @Test
    void the_ignore_list_travels_only_to_a_provider_that_declares_it() throws Exception {
        try (MockWebServer remote = new MockWebServer()) {
            remote.enqueue(ok("""
                {"requestId":"r1","providerKey":"p","status":"SUCCESS","elapsedMs":1,"hits":[]}"""));
            remote.start();

            provider.search(registrationWithoutExclusionAt(remote),
                    new SearchAsk("r1", "q", 10, List.of("foxnews.com")));

            assertFalse(remote.takeRequest().getBody().readUtf8().contains("foxnews.com"));
        }
    }
}
