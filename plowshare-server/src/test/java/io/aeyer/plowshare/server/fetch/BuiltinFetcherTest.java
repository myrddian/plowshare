package io.aeyer.plowshare.server.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.List;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link BuiltinFetcher} as {@code FetchConfig} builds it: guarded, with
 * redirects followed by hand.
 *
 * <p>Every positive-path test dials a {@link MockWebServer} on loopback, which is
 * a private address. It reaches it by allowlisting the mock's own {@code
 * host:port}, so every one of those tests exercises the allowlist as well (spec
 * §4). The entry is {@link MockWebServer#getHostName()}, not {@code 127.0.0.1}:
 * {@link MockWebServer#url} spells the host that way, and an entry matches the
 * hop's host as written.
 *
 * <p>The {@code https} → {@code http} downgrade is pinned in {@code
 * RedirectsTest}. This test classpath has no TLS server to redirect from.
 */
class BuiltinFetcherTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    /** A fetcher that allowlists {@code remote} exactly as its own {@code url()} spells it. */
    private static BuiltinFetcher allowing(MockWebServer remote) {
        return allowing(new OkHttpClient(), remote.getHostName() + ":" + remote.getPort());
    }

    private static BuiltinFetcher allowing(OkHttpClient base, String... entries) {
        return FetchConfig.guardedFetcher(base, FetchAllowlist.parse(List.of(entries)), TIMEOUT);
    }

    private static BuiltinFetcher strict(OkHttpClient base) {
        return FetchConfig.guardedFetcher(base, FetchAllowlist.empty(), TIMEOUT);
    }

    /** Every name resolves to {@code address}: a public-looking name that points somewhere private. */
    private static OkHttpClient resolvingEverythingTo(String address) {
        return new OkHttpClient.Builder()
                .dns(hostname -> List.of(InetAddress.getByName(address)))
                .build();
    }

    /** A mock bound to 127.0.0.1 specifically, for tests that name it by literal or by fake DNS. */
    private static MockWebServer onLoopback() throws IOException {
        MockWebServer remote = new MockWebServer();
        remote.start(InetAddress.getByName("127.0.0.1"), 0);
        return remote;
    }

    private static MockResponse html(String body) {
        return new MockResponse().addHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html><head><title>T</title></head><body>" + body + "</body></html>");
    }

    private static MockResponse redirectTo(String location) {
        return new MockResponse().setResponseCode(302).addHeader("Location", location);
    }

    // --- what fetch did before the guard, through an allowlisted mock ---------

    @Test
    void a_page_comes_back_extracted() throws Exception {
        try (MockWebServer remote = new MockWebServer()) {
            remote.enqueue(html("<article><p>the sentence</p></article>"));
            remote.start();
            FetchAnswer answer = allowing(remote).fetch(remote.url("/a").toString());
            assertTrue(answer.isOk());
            assertEquals("T", answer.page().title());
            assertTrue(answer.page().text().contains("the sentence"));
        }
    }

    @Test
    void a_non_2xx_is_a_remote_status_failure_carrying_the_code() throws Exception {
        try (MockWebServer remote = new MockWebServer()) {
            remote.enqueue(new MockResponse().setResponseCode(503));
            remote.start();
            FetchAnswer answer = allowing(remote).fetch(remote.url("/a").toString());
            assertFalse(answer.isOk());
            assertEquals(FetchFailure.REMOTE_STATUS, answer.failure());
            assertTrue(answer.message().contains("503"));
        }
    }

    @Test
    void a_dead_host_on_loopback_is_a_refused_address_and_never_an_exception() {
        FetchAnswer answer = strict(new OkHttpClient()).fetch("http://127.0.0.1:1/a");
        assertFalse(answer.isOk());
        assertEquals(FetchFailure.REFUSED_ADDRESS, answer.failure(),
                "port 1 is not allowlisted, so the address is refused before the host can be"
                        + " found dead");
        assertEquals("refused: 127.0.0.1 is a private address", answer.message());
    }

    @Test
    void an_allowlisted_dead_host_is_still_a_fetch_failure() {
        FetchAnswer answer = allowing(new OkHttpClient(), "127.0.0.1:1").fetch("http://127.0.0.1:1/a");
        assertFalse(answer.isOk());
        assertEquals(FetchFailure.FETCH_FAILED, answer.failure());
        assertTrue(answer.message() != null && !answer.message().isBlank());
    }

    @Test
    void a_pdf_is_refused_as_an_unsupported_content_type_without_being_parsed() throws Exception {
        try (MockWebServer remote = new MockWebServer()) {
            remote.enqueue(new MockResponse().addHeader("Content-Type", "application/pdf")
                    .setBody("%PDF-1.4 not html"));
            remote.start();
            FetchAnswer answer = allowing(remote).fetch(remote.url("/a.pdf").toString());
            assertEquals(FetchFailure.UNSUPPORTED_CONTENT_TYPE, answer.failure());
        }
    }

    @Test
    void a_non_http_scheme_is_refused_before_anything_is_dialled() {
        FetchAnswer answer = strict(new OkHttpClient()).fetch("file:///etc/passwd");
        assertFalse(answer.isOk());
        assertEquals(FetchFailure.FETCH_FAILED, answer.failure());
        assertTrue(answer.message().toLowerCase().contains("http"));
    }

    @Test
    void a_2xx_with_no_content_type_and_a_non_html_body_is_refused() throws Exception {
        try (MockWebServer remote = new MockWebServer()) {
            // Default response code is 200 and no Content-Type header is set at
            // all -- unlike the PDF test above, which refuses a *present*
            // wrong content type. A successful response that states nothing
            // about what it is must not fall through to PageExtractor on the
            // assumption that "unstated" means "assume HTML".
            remote.enqueue(new MockResponse().setBody("%PDF-1.4 not html"));
            remote.start();
            FetchAnswer answer = allowing(remote).fetch(remote.url("/a").toString());
            assertFalse(answer.isOk());
            assertEquals(FetchFailure.UNSUPPORTED_CONTENT_TYPE, answer.failure());
        }
    }

    @Test
    void the_request_identifies_as_a_browser_because_bot_agents_are_refused() throws Exception {
        try (MockWebServer remote = new MockWebServer()) {
            remote.enqueue(html("<article><p>x</p></article>"));
            remote.start();
            allowing(remote).fetch(remote.url("/a").toString());
            String ua = remote.takeRequest().getHeader("User-Agent");
            assertTrue(ua != null && ua.contains("Mozilla"),
                    "spec §3 and cost 2: an honest plowshare/... agent string is what the "
                            + "reference implementation abandoned because sites reject or time "
                            + "out on it. Got: " + ua);
        }
    }

    // --- the guard (spec §4) ---------------------------------------------------

    @Test
    void a_loopback_literal_is_refused_before_a_socket_opens() throws Exception {
        try (MockWebServer remote = onLoopback()) {
            FetchAnswer answer = strict(new OkHttpClient())
                    .fetch("http://127.0.0.1:" + remote.getPort() + "/a");
            assertEquals(FetchFailure.REFUSED_ADDRESS, answer.failure());
            assertEquals(0, remote.getRequestCount());
        }
    }

    @Test
    void a_name_that_resolves_to_loopback_is_refused_and_the_address_is_never_named()
            throws Exception {
        try (MockWebServer remote = onLoopback()) {
            FetchAnswer answer = strict(resolvingEverythingTo("127.0.0.1"))
                    .fetch("http://intranet.example:" + remote.getPort() + "/a");
            assertEquals(FetchFailure.REFUSED_ADDRESS, answer.failure());
            assertEquals("refused: intranet.example is a private address", answer.message(),
                    "the host and the tier, and never the address it resolved to");
            assertEquals(0, remote.getRequestCount());
        }
    }

    @Test
    void an_ipv4_mapped_loopback_is_refused() throws Exception {
        try (MockWebServer remote = onLoopback()) {
            FetchAnswer answer = strict(new OkHttpClient())
                    .fetch("http://[::ffff:127.0.0.1]:" + remote.getPort() + "/a");
            assertEquals(FetchFailure.REFUSED_ADDRESS, answer.failure());
            assertEquals(0, remote.getRequestCount());
        }
    }

    @Test
    void a_redirect_from_an_allowlisted_host_to_one_that_is_not_is_refused_at_the_second_hop()
            throws Exception {
        try (MockWebServer first = onLoopback(); MockWebServer second = onLoopback()) {
            first.enqueue(redirectTo("http://second.example:" + second.getPort() + "/b"));
            second.enqueue(html("<p>must not be read</p>"));
            BuiltinFetcher fetcher = allowing(resolvingEverythingTo("127.0.0.1"),
                    "first.example:" + first.getPort());

            FetchAnswer answer = fetcher.fetch("http://first.example:" + first.getPort() + "/a");

            assertEquals(FetchFailure.REFUSED_ADDRESS, answer.failure());
            assertEquals("refused: second.example is a private address", answer.message());
            assertEquals(1, first.getRequestCount());
            assertEquals(0, second.getRequestCount());
        }
    }

    /**
     * The same socket under a second name: only {@code first.example:P} is
     * allowlisted, and the redirect goes to {@code second.example:P}, the same
     * port. The exemption is per port, so only choosing the client again for
     * each hop refuses this. A fetcher that kept the first hop's exempt client
     * would dial it.
     */
    @Test
    void a_redirect_to_another_name_on_the_allowlisted_port_is_refused_at_the_second_hop()
            throws Exception {
        try (MockWebServer remote = onLoopback()) {
            int port = remote.getPort();
            remote.enqueue(redirectTo("http://second.example:" + port + "/b"));
            remote.enqueue(html("<p>must not be read</p>"));
            BuiltinFetcher fetcher = allowing(resolvingEverythingTo("127.0.0.1"),
                    "first.example:" + port);

            FetchAnswer answer = fetcher.fetch("http://first.example:" + port + "/a");

            assertEquals(FetchFailure.REFUSED_ADDRESS, answer.failure(), answer.message());
            assertEquals("refused: second.example is a private address", answer.message());
            assertEquals(1, remote.getRequestCount(), "the second hop never reaches the socket");
        }
    }

    @Test
    void five_redirects_are_followed() throws Exception {
        try (MockWebServer remote = new MockWebServer()) {
            for (int i = 1; i <= 5; i++) {
                remote.enqueue(redirectTo("/r" + (i + 1)));
            }
            remote.enqueue(html("<article><p>arrived</p></article>"));
            remote.start();
            FetchAnswer answer = allowing(remote).fetch(remote.url("/r1").toString());
            assertTrue(answer.isOk(), answer.message());
            assertTrue(answer.page().text().contains("arrived"));
            assertEquals(6, remote.getRequestCount());
        }
    }

    @Test
    void a_sixth_redirect_is_a_fetch_failure_naming_the_limit() throws Exception {
        try (MockWebServer remote = new MockWebServer()) {
            for (int i = 1; i <= 6; i++) {
                remote.enqueue(redirectTo("/r" + (i + 1)));
            }
            remote.enqueue(html("<p>never reached</p>"));
            remote.start();
            FetchAnswer answer = allowing(remote).fetch(remote.url("/r1").toString());
            assertEquals(FetchFailure.FETCH_FAILED, answer.failure());
            assertTrue(answer.message().contains("5 redirects"), answer.message());
            assertEquals(6, remote.getRequestCount(), "the seventh request is never made");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void each_redirect_status_is_followed(int code) throws Exception {
        try (MockWebServer remote = new MockWebServer()) {
            remote.enqueue(new MockResponse().setResponseCode(code).addHeader("Location", "/b"));
            remote.enqueue(html("<article><p>arrived</p></article>"));
            remote.start();
            FetchAnswer answer = allowing(remote).fetch(remote.url("/a").toString());
            assertTrue(answer.isOk(), answer.message());
            assertEquals(2, remote.getRequestCount());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {300, 304})
    void a_3xx_that_is_not_a_redirect_is_the_remote_status_it_is(int code) throws Exception {
        try (MockWebServer remote = new MockWebServer()) {
            remote.enqueue(new MockResponse().setResponseCode(code).addHeader("Location", "/b"));
            remote.enqueue(html("<p>must not be read</p>"));
            remote.start();
            FetchAnswer answer = allowing(remote).fetch(remote.url("/a").toString());
            assertEquals(FetchFailure.REMOTE_STATUS, answer.failure());
            assertTrue(answer.message().contains(String.valueOf(code)), answer.message());
            assertEquals(1, remote.getRequestCount(), "a Location on a " + code + " is not followed");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void a_redirect_with_a_blank_location_is_a_fetch_failure_and_never_an_exception(
            String location) throws Exception {
        try (MockWebServer remote = new MockWebServer()) {
            remote.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", location));
            remote.start();
            FetchAnswer answer = allowing(remote).fetch(remote.url("/a").toString());
            assertEquals(FetchFailure.FETCH_FAILED, answer.failure());
            assertTrue(answer.message().contains("302") && answer.message().contains("Location"),
                    answer.message());
            assertEquals(1, remote.getRequestCount());
        }
    }

    @Test
    void a_redirect_with_no_location_is_a_fetch_failure_and_never_an_exception()
            throws Exception {
        try (MockWebServer remote = new MockWebServer()) {
            remote.enqueue(new MockResponse().setResponseCode(301));
            remote.start();
            FetchAnswer answer = allowing(remote).fetch(remote.url("/a").toString());
            assertEquals(FetchFailure.FETCH_FAILED, answer.failure());
            assertTrue(answer.message().contains("301") && answer.message().contains("Location"),
                    answer.message());
            assertEquals(1, remote.getRequestCount());
        }
    }

    /**
     * A closed port, not port 1: port 1 can be a real listener on some hosts, and
     * this needs a reliable {@link ConnectException}. Binding to port 0 and
     * closing it right away hands back a port nothing is listening on.
     */
    private static int closedPortOnLoopback() throws IOException {
        try (java.net.ServerSocket probe =
                new java.net.ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            return probe.getLocalPort();
        }
    }

    @Test
    void a_connect_failure_on_an_allowlisted_name_names_the_host_never_the_resolved_address()
            throws Exception {
        int port = closedPortOnLoopback();
        BuiltinFetcher fetcher =
                allowing(resolvingEverythingTo("127.0.0.1"), "closed.example:" + port);

        FetchAnswer answer = fetcher.fetch("http://closed.example:" + port + "/a");

        assertEquals(FetchFailure.FETCH_FAILED, answer.failure(), answer.message());
        assertTrue(answer.message().contains("closed.example"), answer.message());
        assertFalse(answer.message().contains("127.0.0.1"), answer.message());
    }

    @Test
    void a_decimal_ipv4_literal_is_refused_without_dialling() throws Exception {
        try (MockWebServer remote = onLoopback()) {
            // 2130706433 is 127.0.0.1 as one decimal integer -- InetAddress
            // parses it, so a host neither AddressPolicy's literal patterns nor
            // the early check's regexes recognise as an IP still resolves to
            // loopback at connect time, and the connect-time check catches it.
            FetchAnswer answer = strict(new OkHttpClient())
                    .fetch("http://2130706433:" + remote.getPort() + "/a");
            assertEquals(FetchFailure.REFUSED_ADDRESS, answer.failure(), answer.message());
            assertEquals(0, remote.getRequestCount());
        }
    }

    @Test
    void a_hex_ipv4_literal_is_never_dialled_whether_or_not_it_parses() throws Exception {
        try (MockWebServer remote = onLoopback()) {
            // 0x7f.1 is another spelling of 127.0.0.1 some parsers accept.
            // Checked: HttpUrl.parse admits it as an opaque host ("0x7f.1"),
            // but InetAddress does not resolve it the way it resolves a plain
            // decimal integer, so it never reaches a concrete address at all --
            // the fetch fails as an unresolvable name rather than a refusal.
            // Either way the mock must not be dialled, which is what this case
            // actually pins down.
            FetchAnswer answer = strict(new OkHttpClient())
                    .fetch("http://0x7f.1:" + remote.getPort() + "/a");
            assertFalse(answer.isOk(), answer.message());
            assertEquals(0, remote.getRequestCount());
        }
    }

    @Test
    void an_allowlisted_name_on_the_metadata_address_is_still_refused() {
        BuiltinFetcher fetcher =
                allowing(resolvingEverythingTo("169.254.169.254"), "metadata.example:80");
        FetchAnswer answer = fetcher.fetch("http://metadata.example/latest/meta-data/");
        assertEquals(FetchFailure.REFUSED_ADDRESS, answer.failure());
        assertEquals("refused: metadata.example is an address this server never fetches",
                answer.message());
    }

    @Test
    void fetch_clients_are_derived_once_and_leave_the_shared_client_as_search_needs_it() {
        OkHttpClient shared = new OkHttpClient();
        BuiltinFetcher fetcher = allowing(shared, "docs.internal:8080");
        OkHttpClient strictClient = fetcher.clientFor(HttpUrl.get("http://a.example/"));
        OkHttpClient entryClient = fetcher.clientFor(HttpUrl.get("http://docs.internal:8080/"));

        assertTrue(shared.followRedirects(), "search's client is untouched");
        assertNull(shared.proxy());
        assertFalse(shared.socketFactory() instanceof GuardedSockets);

        for (OkHttpClient client : List.of(strictClient, entryClient)) {
            assertFalse(client.followRedirects());
            assertFalse(client.followSslRedirects());
            assertEquals(Proxy.NO_PROXY, client.proxy());
            assertInstanceOf(GuardedSockets.class, client.socketFactory());
            assertNotSame(shared.connectionPool(), client.connectionPool());
        }
        assertNotSame(strictClient, entryClient);
        assertNotSame(strictClient.connectionPool(), entryClient.connectionPool(),
                "OkHttp's Address does not compare socket factories, so a shared pool could hand"
                        + " the strict client a connection an entry's exempt socket opened");
        assertSame(strictClient, fetcher.clientFor(HttpUrl.get("http://docs.internal:8081/")),
                "another port on the same host is the strict client");
        assertSame(entryClient, fetcher.clientFor(HttpUrl.get("http://docs.internal:8080/other")),
                "built once, not per call");
    }

    @Test
    void a_refusal_is_recognised_wherever_okhttp_leaves_it() {
        RefusedAddress refusal = new RefusedAddress(AddressPolicy.Tier.PRIVATE);
        assertSame(refusal, BuiltinFetcher.refusalIn(refusal).orElseThrow());
        assertSame(refusal,
                BuiltinFetcher.refusalIn(new IOException("wrapped", refusal)).orElseThrow());
        IOException firstRoute = new ConnectException("the first route was down");
        firstRoute.addSuppressed(refusal);
        assertSame(refusal, BuiltinFetcher.refusalIn(firstRoute).orElseThrow(),
                "OkHttp throws the first route's failure, with later routes' failures suppressed");
        assertTrue(BuiltinFetcher.refusalIn(new IOException("dead host")).isEmpty());
    }
}
