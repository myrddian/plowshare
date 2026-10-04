package io.aeyer.plowshare.server.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.fetch.FetchWindow;
import io.aeyer.plowshare.protocol.search.CostClass;
import io.aeyer.plowshare.protocol.search.NetworkTier;
import io.aeyer.plowshare.protocol.search.ProviderFacts;
import io.aeyer.plowshare.protocol.search.SearchPage;
import io.aeyer.plowshare.protocol.search.Verb;
import io.aeyer.plowshare.server.search.ProviderStore;
import io.aeyer.plowshare.server.search.RemoteSearchProvider;
import io.aeyer.plowshare.server.search.ResultSetStore;
import io.aeyer.plowshare.server.search.SearchLadder;
import io.aeyer.plowshare.server.search.SearchProperties;
import io.aeyer.plowshare.server.search.SearchProvider;
import io.aeyer.plowshare.server.search.SearchService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The whole fetch-and-the-agent-surface slice, end to end, on {@code SearchEndToEndTest}'s own
 * reasoning for why this proof does not live beside any one unit's own tests: {@code
 * FetchServiceTest} proves {@link FetchService} against a fake registered provider is never in the
 * picture, and every search-package test proves the ladder against a hand-written {@link
 * SearchProvider} rather than a real fetch on the other end of a hit. None of them proves that a
 * hit a real, registered provider returns is a URL {@link FetchService} can actually read — which
 * is the whole reason this slice was built rather than shipping search and fetch as two features
 * that happen to share a database.
 *
 * <p>No Spring context, on both {@code SearchEndToEndTest} and {@code FetchServiceTest}'s own
 * precedent: every collaborator here is a plain constructor call over a real Postgres and a real
 * {@link MockWebServer}, so a full application context would prove nothing this class does not
 * already prove more cheaply.
 *
 * <h2>Why one {@link MockWebServer} per test, not one shared across the class</h2>
 *
 * <p>{@link MockWebServer#getRequestCount()} accumulates for the life of the server instance, and
 * three of these four tests assert an exact count — {@code 1}, {@code 2}, or {@code 0}. A server
 * shared across tests would make every count a running total contaminated by whichever tests ran
 * first rather than a fact about the one test reading it, so each test starts and stops its own.
 *
 * <h2>What test 4 does not assert, and why</h2>
 *
 * <p>The design brief for this slice adds, to the suppressed-domain case, "and a provider on a
 * higher rung would not be bound by it". That half is not testable here: no higher-rung fetch
 * provider — one carrying a browser engine rather than {@link BuiltinFetcher}'s bare HTTP client —
 * exists in this codebase to register and dial. It is a design property, and {@link FetchService}'s
 * own class javadoc already argues it in full, in prose nothing in this file repeats: the ignore
 * list "describes a limitation of {@code PageFetcher#fetch}'s current implementation" rather than a
 * prohibition on the page, so a fetch provider registered on a higher rung later is free to reach
 * every domain on it without {@link FetchService} changing at all. A test that asserted this anyway
 * would be asserting a comment, not behaviour, so {@link
 * #a_suppressed_domain_is_refused_without_a_dial()} below pins only what is actually testable: the
 * refusal names the domain, it carries the exact "this deployment's fetcher is blocked at" wording
 * rather than anything implying the page itself is forbidden, and no dial was spent.
 */
@Tag("full-db")
@Testcontainers
class FetchEndToEndTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Instant T0 = Instant.parse("2026-09-09T09:00:00Z");

  private static JdbcTemplate jdbc;

  private final OkHttpClient http = new OkHttpClient();
  private final ObjectMapper mapper = new ObjectMapper();

  private FetchedPageStore pages;
  private TestClock clock;
  private FetchProperties fetchProperties;
  private SearchProperties searchProperties;
  private ProviderStore providerStore;
  private ResultSetStore resultSetStore;

  @BeforeAll
  static void migrate() {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = new JdbcTemplate(dataSource);
  }

  @BeforeEach
  void freshState() {
    jdbc.execute("TRUNCATE TABLE fetched_pages");
    jdbc.execute("TRUNCATE TABLE search_providers");
    jdbc.execute("TRUNCATE TABLE search_result_sets");
    jdbc.execute("TRUNCATE TABLE runtime_config");

    pages = new FetchedPageStore(jdbc);
    clock = new TestClock(T0, ZoneOffset.UTC);

    fetchProperties = new FetchProperties();
    fetchProperties.setTtl(Duration.ofHours(24));
    fetchProperties.setLiveWindow(Duration.ofMinutes(10));
    fetchProperties.setWindow(8_000);
    fetchProperties.setTimeout(Duration.ofSeconds(2));

    searchProperties = new SearchProperties();
    searchProperties.setFailureThreshold(3);
    searchProperties.setResultSetTtl(Duration.ofHours(1));

    providerStore = new ProviderStore(jdbc);
    resultSetStore = new ResultSetStore(jdbc);
  }

  /**
   * A fresh {@link FetchService} over the shared store, properties and clock, but its own {@link
   * BuiltinFetcher} -- standing in for a second agent's own request path, since the buffer's whole
   * justification ({@link FetchService}'s own "liveness stamp" section) is that it is server-wide
   * rather than scoped to one instance. {@code server} is on loopback, so it is reached through the
   * allowlist, spelled as {@link MockWebServer#url} spells its host.
   */
  private FetchService newFetchService(MockWebServer server) {
    return fetchServiceOver(
        FetchAllowlist.parse(List.of(server.getHostName() + ":" + server.getPort())));
  }

  /**
   * Fetch's guarded clients derived from {@link #http}, the very client {@link
   * RemoteSearchProvider} is handed below, as FetchConfig derives them from SearchConfig's one
   * bean.
   */
  private FetchService fetchServiceOver(FetchAllowlist allowlist) {
    BuiltinFetcher fetcher =
        FetchConfig.guardedFetcher(http, allowlist, fetchProperties.getTimeout());
    return new FetchService(fetcher, pages, fetchProperties, searchProperties, clock);
  }

  private SearchService searchServiceOver(SearchProvider provider) {
    SearchLadder ladder = new SearchLadder(providerStore, searchProperties, provider);
    return new SearchService(ladder, resultSetStore, searchProperties, Clock.systemUTC());
  }

  /**
   * {@code base_url} carries no trailing slash -- {@code
   * search_providers_a_base_url_has_no_trailing_slash} refuses one at the row, and {@link
   * MockWebServer#url} always hands back one with a trailing {@code "/"}. Carried from {@code
   * SearchEndToEndTest}.
   */
  private static String strip(MockWebServer server) {
    String url = server.url("/").toString();
    return url.substring(0, url.length() - 1);
  }

  private static ProviderFacts facts(String key) {
    return new ProviderFacts(
        key,
        key,
        "0.1.0",
        "d",
        Set.of(Verb.SEARCH),
        CostClass.FREE,
        NetworkTier.INTERNAL_NETWORK,
        25,
        512,
        false);
  }

  private static MockResponse jsonBody(String body) {
    return new MockResponse().addHeader("Content-Type", "application/json").setBody(body);
  }

  private static MockResponse success(String providerKey, String hitJson) {
    return jsonBody(
        """
                {"requestId":"r","providerKey":"%s","status":"SUCCESS","elapsedMs":1,
                 "hits":[%s]}"""
            .formatted(providerKey, hitJson));
  }

  private static String hitJson(String url) {
    return """
                {"url":"%s","title":"title for %s","snippet":"snippet for %s"}
                """
        .formatted(url, url, url)
        .strip();
  }

  private static MockResponse htmlPage(String bodyHtml) {
    return new MockResponse()
        .addHeader("Content-Type", "text/html; charset=utf-8")
        .setBody("<html><head><title>T</title></head><body>" + bodyHtml + "</body></html>");
  }

  /**
   * Test 1 of the brief: a registered search provider returns a hit, the URL that hit names is
   * fetched from a real socket, and the page's own text -- not the hit's title or snippet -- is
   * what comes back windowed. This is, in the brief's own words, "the slice's reason to exist":
   * search and fetch were built as two separate features over the same slice, and this is the one
   * test that proves a hit either of them can produce is a URL the other can actually read.
   *
   * <p>One {@link MockWebServer} plays both roles -- the registered search provider {@link
   * RemoteSearchProvider} dials, and the page host {@link BuiltinFetcher} dials next -- because
   * nothing in either contract cares whether those are the same origin, and two sockets would prove
   * nothing a request-count assertion on one cannot already show: exactly one request answers the
   * search, and exactly one more answers the fetch.
   */
  @Test
  void a_search_hit_is_the_url_fetch_reads_and_its_text_comes_back_windowed() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      providerStore.upsert("wired-provider", strip(server), facts("wired-provider"));
      searchProperties.setLadder("wired-provider");

      String pageUrl = server.url("/article").toString();
      server.enqueue(success("wired-provider", hitJson(pageUrl)));
      server.enqueue(htmlPage("<article><p>the needle this window must contain</p></article>"));

      SearchService searchService =
          searchServiceOver(new RemoteSearchProvider(http, mapper, Duration.ofSeconds(5)));
      SearchPage searchPage = searchService.search("plowshare fetch composition", 10, 10, 1);

      assertEquals(1, searchPage.hits().size(), "the ladder must actually return the hit");
      String urlFromHit = searchPage.hits().get(0).url();
      assertEquals(pageUrl, urlFromHit);

      FetchService fetchService = newFetchService(server);
      FetchWindow window = fetchService.read(urlFromHit, 0);

      assertTrue(
          window.refusal() == null,
          "a URL a search hit names must be a page fetch can actually read: " + window.refusal());
      assertTrue(
          window.text().contains("the needle this window must contain"),
          "the fetched page's own text, not the hit's title or snippet, is what a"
              + " window carries");
      assertEquals(
          2,
          server.getRequestCount(),
          "one dial to answer the search, one more to answer the fetch -- neither"
              + " step should cost more than the one request it needs");
    }
  }

  /**
   * Test 2 of the brief: the opportunistic-read guarantee, proven with two independent {@link
   * FetchService} instances -- standing in for two separate agents -- over one database and one
   * socket. {@link FetchService}'s own class javadoc calls this "the whole reason a server-wide
   * buffer exists rather than one scoped to a conversation", and this test is what makes that claim
   * checkable: if the buffer were ever accidentally scoped per-instance, this is the test that
   * would catch it, since {@code getRequestCount()} would read {@code 2} instead of {@code 1}.
   */
  @Test
  void a_second_agent_reading_the_same_page_pays_nothing() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(htmlPage("<article><p>shared between two agents</p></article>"));
      String url = server.url("/shared").toString();

      FetchService agentOne = newFetchService(server);
      FetchService agentTwo = newFetchService(server);

      FetchWindow first = agentOne.read(url, 0);
      FetchWindow second = agentTwo.read(url, 0);

      assertTrue(
          first.refusal() == null, "the first agent's read must succeed: " + first.refusal());
      assertTrue(
          second.refusal() == null, "the second agent's read must succeed: " + second.refusal());
      assertEquals(
          1,
          server.getRequestCount(),
          "the buffer is server-wide: a second agent reading the same page must not"
              + " spend a second dial");
    }
  }

  /**
   * Test 3 of the brief: the liveness guard proven end to end, on {@link
   * FetchedPageStore#purgeExpired}'s own {@code AND} of two conditions rather than one. A page
   * fetched once, read again after its {@code ttl} has elapsed, must not be swept by a purge that
   * runs immediately after that read -- the read itself renews {@code last_read}, and {@code
   * purgeExpired} only deletes a row that is <em>both</em> past its {@code ttl} <em>and</em> not
   * read inside {@code liveWindow}. This test drives every step by hand: fetch, advance the clock,
   * read (to prove the read still serves an old row rather than refusing it), purge, and then read
   * once more to prove the row that survived is still a row that serves.
   */
  @Test
  void a_page_being_read_survives_a_purge_past_its_ttl() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      server.enqueue(htmlPage("<article><p>" + "z".repeat(20_000) + "</p></article>"));
      String url = server.url("/longlived").toString();
      FetchService fetchService = newFetchService(server);

      FetchWindow first = fetchService.read(url, 0);
      assertTrue(first.refusal() == null, "the initial fetch must succeed: " + first.refusal());

      clock.advance(fetchProperties.getTtl().plus(Duration.ofHours(1)));

      FetchWindow second = fetchService.read(url, 0);
      assertTrue(
          second.refusal() == null,
          "a hit is served regardless of the row's age -- it is not refetched merely"
              + " for being past its ttl: "
              + second.refusal());

      int purged =
          pages.purgeExpired(
              clock.instant(), fetchProperties.getTtl(), fetchProperties.getLiveWindow());
      assertEquals(
          0,
          purged,
          "the read just above renewed last_read, so this row is being read right now"
              + " and purgeExpired's own AND must not delete it");
      assertTrue(pages.find(UrlKey.of(url)).isPresent(), "the row must survive the purge");

      FetchWindow third = fetchService.read(url, second.nextOffset());
      assertTrue(
          third.refusal() == null,
          "the window must still serve after surviving the purge: " + third.refusal());
      assertEquals(
          1,
          server.getRequestCount(),
          "every read above was served from the buffer -- only the first fetch ever"
              + " dialled the network");
    }
  }

  /**
   * Test 4 of the brief: a domain on {@code plowshare.search.ignored-domains} is refused before a
   * dial is ever spent, and the refusal carries {@link FetchService}'s own wording -- "this
   * deployment's fetcher is blocked at" -- rather than language implying the page itself is
   * forbidden. That wording is pinned deliberately, not incidentally: it is the whole distinction
   * the class javadoc argues, that the ignore list is capability data about {@link BuiltinFetcher}
   * specifically and not a prohibition this class enforces on anyone's behalf. See this class's own
   * javadoc for why the brief's second clause -- a higher-rung provider not being bound by this
   * list -- is not asserted here.
   */
  @Test
  void a_suppressed_domain_is_refused_without_a_dial() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      String host = server.url("/").host();
      searchProperties.setIgnoredDomains(host);
      String url = server.url("/blocked").toString();

      FetchService fetchService = newFetchService(server);
      FetchWindow refused = fetchService.read(url, 0);

      assertTrue(refused.refusal() != null, "a suppressed domain must be refused, not served");
      assertTrue(
          refused.refusal().contains(host),
          "the refusal must name the domain an operator would need to see to diagnose"
              + " it: "
              + refused.refusal());
      assertTrue(
          refused.refusal().contains("this deployment's fetcher is blocked at"),
          "pin FetchService's own wording -- capability data about one fetcher, not a"
              + " prohibition on the page: "
              + refused.refusal());
      assertEquals(
          0,
          server.getRequestCount(),
          "a suppressed domain must be refused before a dial is ever spent");
    }
  }

  /**
   * Spec §4, "search is untouched": the guard is on fetch's own clients and not on the shared bean.
   * A registered provider on loopback still answers the search with no allowlist entry anywhere.
   * The same origin, read through a fetch with no allowlist, is refused by address before a socket
   * opens.
   */
  @Test
  void search_still_reaches_a_loopback_provider_while_fetch_is_refused_there() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();
      providerStore.upsert("loopback-provider", strip(server), facts("loopback-provider"));
      searchProperties.setLadder("loopback-provider");
      String pageUrl = server.url("/article").toString();
      server.enqueue(success("loopback-provider", hitJson(pageUrl)));
      server.enqueue(htmlPage("<article><p>must not be read</p></article>"));

      SearchPage searchPage =
          searchServiceOver(new RemoteSearchProvider(http, mapper, Duration.ofSeconds(5)))
              .search("plowshare fetch guard", 10, 10, 1);
      assertEquals(
          1,
          searchPage.hits().size(),
          "search dials its loopback provider through the shared client, which the"
              + " guard never touched");

      FetchWindow refused = fetchServiceOver(FetchAllowlist.empty()).read(pageUrl, 0);
      assertTrue(
          refused.refusal() != null && refused.refusal().contains("REFUSED_ADDRESS"),
          "fetch derives its own guarded clients, so loopback is refused to it: "
              + refused.refusal());
      assertEquals(
          1,
          server.getRequestCount(),
          "one dial answered the search, and the fetch never reached the socket");
    }
  }

  /**
   * A mutable {@link Clock}, carried from {@code FetchServiceTest} and {@code SearchServiceTest}'s
   * own pattern, so a test can move "now" forward without waiting on a wall clock.
   */
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
}
