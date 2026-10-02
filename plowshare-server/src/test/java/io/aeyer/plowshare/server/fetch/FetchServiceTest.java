package io.aeyer.plowshare.server.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.fetch.FetchWindow;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.search.SearchProperties;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link FetchService} against a real Postgres and a real {@link
 * BuiltinFetcher} dialling a {@link MockWebServer} — on {@code
 * SearchServiceTest}'s own reasoning for why a hand-rolled fake would be
 * testing the fake rather than the service: this class's whole job is
 * deciding when to dial {@link PageFetcher} at all, and only a real fetcher
 * over a real (if local) socket exercises that decision honestly. Follows
 * {@code FetchedPageStoreTest}: no Spring context, one Testcontainers
 * Postgres migrated once for the class, and a truncated {@code
 * fetched_pages} per test.
 *
 * <p>{@link #dials()} counts calls made through a {@link CountingFetcher}
 * wrapper around the real {@link BuiltinFetcher} rather than {@link
 * MockWebServer#getRequestCount()} — the counter needs to reset cleanly every
 * test, and a wrapper around the port {@link FetchService} actually depends
 * on is a truer count besides: it is exactly the seam the class comment on
 * {@link PageFetcher} says a facade depends on.
 */
@Testcontainers
class FetchServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static final Instant T0 = Instant.parse("2026-09-09T10:00:00Z");

    private static JdbcTemplate jdbc;
    private static MockWebServer remote;

    // static, not an instance field: JUnit builds a fresh test instance per
    // method, so an instance counter would restart at the same path in every
    // test and rely on execution order to keep REGISTRY's entries from
    // colliding across tests sharing the one static Dispatcher installed in
    // @BeforeAll.
    private static final AtomicInteger nextPath = new AtomicInteger();

    private FetchedPageStore pages;
    private TestClock clock;
    private FetchProperties properties;
    private SearchProperties searchProperties;
    private CountingFetcher fetcher;
    private FetchService service;
    private FetchService otherService;

    @BeforeAll
    static void setUp() throws IOException {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);

        remote = new MockWebServer();
        remote.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().addHeader("Content-Type", "text/html; charset=utf-8")
                        .setBody("<html><head><title>T</title></head><body>"
                                + REGISTRY.get(request.getPath()) + "</body></html>");
            }
        });
        remote.start();
    }

    @AfterAll
    static void tearDown() throws IOException {
        remote.shutdown();
    }

    /*
     * Bodies are looked up through a static map keyed by path, because the
     * Dispatcher above is installed once in @BeforeAll and every test's
     * serve() calls add to the same registry under a path unique to that
     * call -- a fresh MockWebServer per test would work too, but starting
     * and stopping a real socket server once per test is needless cost for
     * what is otherwise a pure routing table.
     */
    private static final Map<String, String> REGISTRY = new ConcurrentHashMap<>();

    @BeforeEach
    void freshState() {
        jdbc.execute("TRUNCATE TABLE fetched_pages");
        pages = new FetchedPageStore(jdbc);
        clock = new TestClock(T0, ZoneOffset.UTC);
        properties = new FetchProperties();
        properties.setTtl(Duration.ofHours(24));
        properties.setLiveWindow(Duration.ofMinutes(10));
        properties.setWindow(8_000);
        properties.setTimeout(Duration.ofSeconds(2));
        searchProperties = new SearchProperties();
        // The mock is on loopback, a private address, so it is reached through
        // the allowlist, spelled as remote.url() spells its host (spec §4).
        fetcher = new CountingFetcher(FetchConfig.guardedFetcher(new OkHttpClient(),
                FetchAllowlist.parse(List.of(remote.getHostName() + ":" + remote.getPort())),
                Duration.ofSeconds(2)));
        service = new FetchService(fetcher, pages, properties, searchProperties, clock);
        // A second FetchService instance sharing the same store, the same
        // fetcher and the same clock -- standing in for a second agent's
        // request, since the buffer's whole justification is that it is
        // server-wide rather than scoped to one FetchService instance.
        otherService = new FetchService(fetcher, pages, properties, searchProperties, clock);
    }

    private String serve(String bodyHtml) {
        String path = "/p" + nextPath.incrementAndGet();
        REGISTRY.put(path, bodyHtml);
        return remote.url(path).toString();
    }

    private int dials() {
        return fetcher.calls();
    }

    private void ignoredDomainsAre(String domains) {
        searchProperties.setIgnoredDomains(domains);
    }

    /** A mutable {@link Clock}, {@code SearchServiceTest}'s own pattern, so a
     *  test can move "now" without waiting on a wall clock. */
    private static final class TestClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        TestClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new TestClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration by) {
            instant = instant.plus(by);
        }
    }

    private void clockAdvances(Duration by) {
        clock.advance(by);
    }

    private Instant now() {
        return clock.instant();
    }

    private Duration ttl() {
        return properties.getTtl();
    }

    private Duration liveWindow() {
        return properties.getLiveWindow();
    }

    /** Counts every call that reaches {@link #delegate}, and nothing else —
     *  see the class comment for why this, and not {@link
     *  MockWebServer#getRequestCount()}, is what {@code dials()} reads. */
    private static final class CountingFetcher implements PageFetcher {
        private final PageFetcher delegate;
        private final AtomicInteger calls = new AtomicInteger();

        CountingFetcher(PageFetcher delegate) {
            this.delegate = delegate;
        }

        @Override
        public FetchAnswer fetch(String url) {
            calls.incrementAndGet();
            return delegate.fetch(url);
        }

        int calls() {
            return calls.get();
        }
    }

    @Test
    void a_first_read_fetches_and_stores_the_whole_page() {
        String url = serve("<article><p>" + "x".repeat(20_000) + "</p></article>");
        FetchWindow first = service.read(url, 0);
        assertEquals(1, dials());
        assertTrue(first.total() >= 20_000, "the whole page is stored, not a truncated fetch");
        assertTrue(first.hasMore());
        // Important 5 (review): nothing above fails if store.put is deleted --
        // the window still comes back correctly from the in-hand FetchAnswer.
        // This is the assertion that makes the test's own name true.
        assertTrue(pages.find(UrlKey.of(url)).isPresent(),
                "a first read must store the page, not merely answer from it");
    }

    @Test
    void a_second_read_serves_from_the_buffer_and_never_dials_again() {
        String url = serve("<article><p>" + "x".repeat(20_000) + "</p></article>");
        service.read(url, 0);
        int before = dials();
        FetchWindow second = service.read(url, 8_000);
        assertEquals(before, dials(),
                "one network call per live page is the whole point of the buffer");
        assertEquals(8_000, second.offset());
    }

    @Test
    void a_second_reader_of_the_same_page_pays_nothing() {
        String url = serve("<article><p>shared</p></article>");
        service.read(url, 0);
        int before = dials();
        otherService.read(url, 0);
        assertEquals(before, dials(),
                "the buffer is server-wide: two agents reading the same page hit the "
                        + "network once, which is the opportunistic-read argument");
    }

    @Test
    void reading_a_window_keeps_the_page_alive() {
        String url = serve("<article><p>x</p></article>");
        service.read(url, 0);
        clockAdvances(Duration.ofHours(30));
        int before = dials();
        service.read(url, 0);
        // Important 2 (review): without this, an implementation that
        // refetched every row past its TTL -- rather than serving the
        // present row regardless of age, per "Step 3 serves the buffer; it
        // never refetches" -- would still pass every other assertion here,
        // since a refetch at T0+30h re-puts the row with a fresh fetched_at
        // and last_read, which also happens to survive the purgeExpired
        // check below for an unrelated reason.
        assertEquals(before, dials(),
                "a hit is served from the buffer regardless of the row's age -- it is not"
                        + " refetched merely for being old");
        assertEquals(0, pages.purgeExpired(now(), ttl(), liveWindow()),
                "a read inside the live window protects the row from eviction");
    }

    @Test
    void a_suppressed_domain_is_refused_without_dialling() {
        ignoredDomainsAre("blocked.example");
        int before = dials();
        FetchWindow refused = service.read("https://blocked.example/a", 0);
        assertEquals(before, dials());
        assertTrue(refused.refusal().contains("blocked.example"));
    }

    /**
     * Review's Important 3: the wildcard-versus-literal distinction argued in
     * {@link FetchService}'s "Domain matching" section was a real design
     * decision this task invented -- nothing had interpreted these strings
     * before -- and only the literal branch was exercised. These three tests
     * call {@link FetchService#suppressedBy} directly rather than through
     * {@link #service}'s public {@code read}: proving a host is <em>not</em>
     * suppressed through {@code read} would mean actually dialling it, and
     * this project's convention (argued on {@code
     * InvariantsTest.no_source_names_the_reference_box}) is that no test may
     * reach a real remote host, suppressed or not.
     */
    @Test
    void a_wildcard_entry_covers_the_bare_host_and_every_subdomain() {
        ignoredDomainsAre("*.blocked.example");
        assertTrue(service.suppressedBy("https://blocked.example/a").isPresent(),
                "a wildcard entry must still cover the bare domain it names");
        assertTrue(service.suppressedBy("https://deep.sub.blocked.example/a").isPresent(),
                "a wildcard entry must cover a subdomain, however deep");
    }

    @Test
    void a_literal_entry_does_not_cover_a_subdomain() {
        ignoredDomainsAre("blocked.example");
        assertTrue(service.suppressedBy("https://blocked.example/a").isPresent(),
                "a literal entry still covers the exact host it names");
        assertTrue(service.suppressedBy("https://sub.blocked.example/a").isEmpty(),
                "a literal entry names one host, not its subdomains -- an operator who means"
                        + " \"and everything under it\" writes *.blocked.example instead");
    }

    @Test
    void ignore_list_matching_is_case_insensitive_on_the_host() {
        ignoredDomainsAre("blocked.example");
        assertTrue(service.suppressedBy("https://BLOCKED.EXAMPLE/a").isPresent(),
                "DNS is case-insensitive, so a host spelled in a different case is the same"
                        + " host -- UrlKey.of's own reasoning for lower-casing only the host");
    }

    @Test
    void a_dead_host_is_a_refusal_and_stores_nothing() {
        FetchWindow refused = service.read("http://127.0.0.1:1/a", 0);
        assertTrue(refused.refusal() != null && refused.refusal().contains("REFUSED_ADDRESS"),
                "port 1 on loopback is not allowlisted, so it is refused by address before any"
                        + " socket opens. It is still an answer and not an exception: "
                        + refused.refusal());
        assertTrue(pages.find(UrlKey.of("http://127.0.0.1:1/a")).isEmpty());
    }

    /**
     * Review's Important 4: the distinction {@link FetchService}'s own
     * javadoc argues at length -- an unparseable string is a bad request, a
     * parseable URL naming an undiallable scheme is a refusal -- was argued
     * but never pinned. Without this test, a future edit adding a scheme
     * allowlist to {@code validate} and throwing {@link CallerFault}
     * for {@code file:} would collapse the two and every other test in this
     * class would still pass.
     */
    @Test
    void a_url_naming_an_undiallable_scheme_is_a_refusal_not_a_bad_request() {
        int before = dials();
        FetchWindow refused = service.read("file:///etc/passwd", 0);
        assertTrue(dials() > before,
                "this must reach the fetcher -- file:///etc/passwd parses perfectly well as a"
                        + " URI, so it is BuiltinFetcher's job to refuse it, not validate's");
        assertTrue(refused.refusal() != null && !refused.refusal().isBlank());
    }

    /**
     * The spec ruling: {@code design.md}'s §5 argues a stale offset "does not
     * arise" because reading keeps a page alive, which overclaims -- a reader
     * that goes quiet past the live window, past the TTL, and is then swept
     * by a purge leaves exactly this: a miss with a positive offset. Refusing
     * here (rather than fetching fresh and silently serving the caller's old
     * offset against a different fetch's text) is what {@link FetchService}'s
     * own "A store miss with a positive offset" section argues.
     */
    @Test
    void a_store_miss_with_a_positive_offset_refuses_rather_than_fetching_fresh() {
        String url = serve("<article><p>" + "x".repeat(20_000) + "</p></article>");
        FetchWindow first = service.read(url, 0);
        clockAdvances(Duration.ofHours(30)); // past both the live window and the TTL
        assertEquals(1, pages.purgeExpired(now(), ttl(), liveWindow()),
                "the row is now gone -- the reader went quiet and nothing kept it alive");

        int before = dials();
        FetchWindow second = service.read(url, first.nextOffset());

        assertEquals(before, dials(),
                "a stale offset must not silently trigger a fresh fetch and be spliced"
                        + " against whatever text that fetch happens to return");
        assertTrue(second.refusal() != null && !second.refusal().isBlank());
    }

    @Test
    void a_window_prefers_a_block_boundary_and_reports_where_it_actually_reached() {
        String url = serve("<article><p>" + "a".repeat(7_900) + "</p><p>next block</p></article>");
        FetchWindow window = service.read(url, 0);
        assertFalse(window.text().contains("next block"));
        assertEquals(window.nextOffset(), window.offset() + window.text().length(),
                "nextOffset is where the read actually reached, which is what a caller re-issues");
    }

    /**
     * Review-flagged Critical 1. On the pre-fix code, a boundary cut sets
     * {@code nextOffset} to the boundary itself, so the very next window opens
     * exactly on the {@code "\n\n"} that was just cut in front of. When the
     * remainder is shorter than a quarter of the configured window — exactly
     * this fixture's second read, whose remainder is 12 characters against a
     * 2,000-character quarter — {@code quarterStart} collapses to that same
     * offset, the same separator is found again, and the read returns an
     * empty window with {@code hasMore} still true: a caller paging to
     * exhaustion never terminates.
     *
     * <p>This test walks every window a real caller would, with a guard
     * counter rather than a wall-clock timeout, so a regression fails fast as
     * an assertion rather than hanging the build. It also reconstructs the
     * whole page from the windows served, pinning that the boundary
     * preference never drops or duplicates a character at the seam.
     */
    @Test
    void paging_to_exhaustion_terminates_and_reconstructs_the_stored_text() {
        String url = serve("<article><p>" + "a".repeat(7_900) + "</p><p>next block</p></article>");
        FetchWindow first = service.read(url, 0);

        StringBuilder rebuilt = new StringBuilder(first.text());
        int offset = first.nextOffset();
        int total = first.total();
        boolean hasMore = first.hasMore();

        int guard = 0;
        while (hasMore) {
            guard++;
            assertTrue(guard < 100,
                    "paging did not terminate within 100 windows -- this is the infinite loop"
                            + " a boundary cut that lands nextOffset on the separator it just cut"
                            + " produces");
            FetchWindow next = service.read(url, offset);
            assertTrue(next.nextOffset() > offset,
                    "every window must advance strictly past the offset it started from, or a"
                            + " caller paging forward never reaches the end of the page");
            rebuilt.append(next.text());
            offset = next.nextOffset();
            hasMore = next.hasMore();
        }

        assertEquals(total, rebuilt.length());
        FetchedPage stored = pages.find(UrlKey.of(url)).orElseThrow();
        assertEquals(stored.text(), rebuilt.toString(),
                "the windows served, concatenated in order, must equal the whole stored page");
    }

    @Test
    void a_blank_url_is_a_bad_request_and_never_reaches_the_fetcher() {
        int before = dials();
        assertThrows(CallerFault.class, () -> service.read("  ", 0));
        assertEquals(before, dials());
    }

    @Test
    void a_negative_offset_is_a_bad_request() {
        assertThrows(CallerFault.class, () -> service.read("https://e.example/a", -1));
    }

    /**
     * Carried forward from Task 3: {@code UrlKey.of}'s own javadoc calls its
     * {@code IllegalArgumentException} on an unparseable URL "a backstop, not
     * the primary path, because {@code FetchService.read} validates at the
     * edge first." This is the test that makes that sentence true, on the
     * same shape as the blank-url and negative-offset checks above: a caller
     * error is refused with {@link CallerFault} before {@code
     * UrlKey.of} is ever reached, and never reaches the fetcher.
     *
     * <p>An unencoded space is what makes {@code java.net.URI} refuse to
     * parse this string at all -- unlike {@code file:///etc/passwd}, which
     * parses perfectly well and names a scheme this deployment's fetcher will
     * not dial, and must stay a {@code FETCH_FAILED} refusal rather than a
     * bad request. See {@code UrlKey}'s own javadoc for why the two must not
     * be collapsed into one behaviour.
     */
    @Test
    void an_unparseable_url_is_a_bad_request_and_never_reaches_the_fetcher() {
        int before = dials();
        assertThrows(CallerFault.class,
                () -> service.read("https://e xample.com/a", 0));
        assertEquals(before, dials());
    }
}
