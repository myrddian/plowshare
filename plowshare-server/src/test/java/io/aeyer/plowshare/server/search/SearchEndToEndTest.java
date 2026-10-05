package io.aeyer.plowshare.server.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.search.CostClass;
import io.aeyer.plowshare.protocol.search.NetworkTier;
import io.aeyer.plowshare.protocol.search.ProviderFacts;
import io.aeyer.plowshare.protocol.search.SearchPage;
import io.aeyer.plowshare.protocol.search.Verb;
import io.aeyer.plowshare.server.config.RuntimeConfig;
import java.time.Clock;
import java.time.Duration;
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
 * The whole slice, end to end, over a real Postgres and real {@link MockWebServer} processes
 * standing in for out-of-process providers — spec §12's own list of what this slice is not finished
 * without.
 *
 * <p>Every other test in this package proves one class against a hand-written fake of its
 * neighbours: {@code SearchLadderTest} fakes {@link SearchProvider}, {@code SearchServiceTest}
 * fakes it too, {@code RemoteSearchProviderTest} proves {@link RemoteSearchProvider} alone against
 * a loopback socket. None of them proves the assembled stack — real {@link ProviderStore}, real
 * {@link SearchLadder}, real {@link RemoteSearchProvider} dialling a real socket, real {@link
 * ResultSetStore} — behaves the way an operator running this server actually needs it to: that a
 * provider going away mid-deployment costs a fallback and not an outage, and that paging through an
 * answer already fetched costs nothing at all. That is what this class exists to pin, on {@code
 * SearchRegistrarTest} and {@code RemoteSearchProviderTest}'s own reasoning for using {@link
 * MockWebServer} rather than a stub {@link OkHttpClient}: the thing worth proving here is what this
 * stack does with a real request and a real, unpredictable response — including the response of a
 * socket that no longer accepts one.
 *
 * <p>No Spring context, on every other test in this package's precedent: the beans {@link
 * SearchConfig} wires are plain constructors over collaborators this class builds by hand, so a
 * full application context would prove nothing about this slice that assembling it directly does
 * not already prove, at a fraction of the cost.
 */
@Tag("full-db")
@Testcontainers
class SearchEndToEndTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;

  private ProviderStore store;
  private ResultSetStore sets;
  private RuntimeConfig runtimeConfig;
  private SearchProperties properties;
  private final OkHttpClient http = new OkHttpClient();
  private final ObjectMapper mapper = new ObjectMapper();

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
    jdbc.execute("TRUNCATE TABLE search_providers");
    jdbc.execute("TRUNCATE TABLE search_result_sets");
    jdbc.execute("TRUNCATE TABLE runtime_config");
    store = new ProviderStore(jdbc);
    sets = new ResultSetStore(jdbc);
    runtimeConfig = new RuntimeConfig(jdbc);
    properties = new SearchProperties();
    properties.setLive(runtimeConfig);
    properties.setFailureThreshold(3);
    properties.setResultSetTtl(Duration.ofHours(1));
  }

  private static ProviderFacts facts(String key, boolean domainExclusion) {
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
        domainExclusion);
  }

  /**
   * {@code base_url} carries no trailing slash — {@code
   * search_providers_a_base_url_has_no_trailing_slash} refuses one at the row, and {@link
   * MockWebServer#url} always hands back one with a trailing {@code "/"}.
   */
  private static String strip(MockWebServer server) {
    String url = server.url("/").toString();
    return url.substring(0, url.length() - 1);
  }

  private void register(String providerKey, MockWebServer server, boolean domainExclusion) {
    store.upsert(providerKey, strip(server), facts(providerKey, domainExclusion));
  }

  private static MockResponse jsonBody(String body) {
    return new MockResponse().addHeader("Content-Type", "application/json").setBody(body);
  }

  private static MockResponse success(String providerKey, String hitsJson) {
    return jsonBody(
        """
                {"requestId":"r","providerKey":"%s","status":"SUCCESS","elapsedMs":1,
                 "hits":[%s]}"""
            .formatted(providerKey, hitsJson));
  }

  private static String hitJson(String url) {
    return """
                {"url":"%s","title":"title for %s","snippet":"snippet for %s"}
                """
        .formatted(url, url, url)
        .strip();
  }

  /**
   * {@code n} distinct hits as a JSON array body, comma-joined — enough to page through in {@link
   * #paging_survives_the_provider_going_away}.
   */
  private static String hitsJson(int n) {
    StringBuilder joined = new StringBuilder();
    for (int i = 0; i < n; i++) {
      if (i > 0) {
        joined.append(',');
      }
      joined.append(hitJson("https://" + i + ".example"));
    }
    return joined.toString();
  }

  private SearchService serviceOver(SearchProvider provider) {
    SearchLadder ladder = new SearchLadder(store, properties, provider);
    return new SearchService(ladder, sets, properties, Clock.systemUTC());
  }

  private SearchService remoteService(Duration timeout) {
    return serviceOver(new RemoteSearchProvider(http, timeout));
  }

  /**
   * The ladder proven the way spec §12 actually cares about: not that {@link SearchLadder} can skip
   * an unhealthy rung against a fake — {@code SearchLadderTest} already pins that with hand-written
   * doubles — but that the assembled stack, dialling a real socket, actually falls over to the
   * second registered provider once the first one stops accepting connections, and that the page it
   * hands back still names neither rung.
   *
   * <p>The second search uses a <b>different query</b> than the first, and it is worth saying why
   * that is still deliberate now that it no longer has to be. This javadoc used to argue that
   * {@link SearchService} answered page 1 of a repeated query out of {@link ResultSetStore} without
   * dialling, so a repeated query here would have proved nothing about the ladder. That is no
   * longer how page one behaves — page one always runs the ladder, and a repeated query would reach
   * the second rung too. The distinct query is kept anyway, because it keeps this test about one
   * thing: a failover proven here cannot be explained by anything the stored set does or does not
   * hold, whichever way that behaviour is settled.
   */
  @Test
  void the_ladder_falls_over_when_the_first_provider_stops() throws Exception {
    MockWebServer first = new MockWebServer();
    MockWebServer second = new MockWebServer();
    boolean firstClosedEarly = false;
    try {
      first.start();
      second.start();
      register("first", first, true);
      register("second", second, true);
      properties.setLadder("first,second");

      // Hit URLs deliberately do not contain "first" or "second" --
      // otherwise the no-provider-named assertions below could pass
      // for the wrong reason, catching a leaked hit rather than a
      // leaked provider key.
      first.enqueue(success("first", hitJson("https://alpha.example")));
      SearchService service = remoteService(Duration.ofSeconds(5));

      SearchPage page1 = service.search("first query", 10, 10, 1);

      assertEquals(1, page1.hits().size());
      assertEquals("https://alpha.example", page1.hits().get(0).url());
      assertEquals(1, first.getRequestCount());

      // MockWebServer, not a fake: this is a real socket no longer
      // accepting connections, not a double telling the ladder it
      // failed.
      first.close();
      firstClosedEarly = true;
      second.enqueue(success("second", hitJson("https://beta.example")));

      SearchPage page2 = service.search("second query, unrelated to the first", 10, 10, 1);

      assertEquals(1, page2.hits().size());
      assertEquals("https://beta.example", page2.hits().get(0).url());
      assertEquals(1, second.getRequestCount());
      assertFalse(page2.toString().contains("first"));
      assertFalse(page2.toString().contains("second"));
    } finally {
      if (!firstClosedEarly) {
        first.close();
      }
      second.close();
    }
  }

  /**
   * The property that makes the whole design worth building: one provider request per search, ever,
   * and every later page of the same search costs nothing but a Postgres read. Proven here by
   * making a second provider request physically impossible — the provider is shut down before pages
   * 2 and 3 are asked for — so a passing test cannot be explained by the provider quietly having
   * answered again.
   */
  @Test
  void paging_survives_the_provider_going_away() throws Exception {
    MockWebServer only = new MockWebServer();
    boolean closedEarly = false;
    try {
      only.start();
      register("only", only, true);
      properties.setLadder("only");
      only.enqueue(success("only", hitsJson(25)));
      SearchService service = remoteService(Duration.ofSeconds(5));

      SearchPage page1 = service.search("paging query", 10, 50, 1);
      assertEquals(10, page1.hits().size());
      assertEquals(1, only.getRequestCount());

      // A real socket, closed for good: pages 2 and 3 below can only
      // pass because ResultSetStore served them, since a second dial
      // is not merely undesired here but physically impossible.
      only.close();
      closedEarly = true;

      SearchPage page2 = service.search("paging query", 10, 50, 2);
      assertEquals(10, page2.hits().size());
      assertTrue(page2.refusal() == null || page2.refusal().isBlank());
      assertTrue(page2.hasMore());

      SearchPage page3 = service.search("paging query", 10, 50, 3);
      assertEquals(5, page3.hits().size());
      assertTrue(page3.refusal() == null || page3.refusal().isBlank());
      assertFalse(page3.hasMore());

      // Still one — the socket that already closed would have refused a
      // second dial outright, but this pins the intended behaviour
      // (never re-dialling for a later page), not merely its
      // unavailable-cannot-be-reached consequence.
      assertEquals(1, only.getRequestCount());
    } finally {
      if (!closedEarly) {
        only.close();
      }
    }
  }

  /**
   * Spec §4 and cost 7: {@link SearchProperties} writes policy through a {@link RuntimeConfig} map
   * that takes any text and validates nothing on the way in (see that class's own class comment),
   * so an operator's typo in {@code plowshare.search.ladder} is accepted at write time and only
   * ever surfaces later, as a refusal out of {@link SearchLadder}, once a search actually tries to
   * walk a rung that names nothing. This test writes that typo the same way an operator's {@code
   * PUT /v1/config} would — through {@link RuntimeConfig#put}, at runtime, against a {@link
   * SearchProperties} already wired live — and pins that the resulting refusal names the entry an
   * operator would need to see to fix it.
   */
  @Test
  void a_ladder_naming_a_provider_nobody_registered_refuses_and_names_the_entry() {
    runtimeConfig.put("plowshare.search.ladder", "ghost-provider", "test");
    SearchService service = remoteService(Duration.ofSeconds(5));

    SearchPage page = service.search("q", 10, 10, 1);

    assertTrue(page.hits().isEmpty());
    assertTrue(page.refusal().contains("ghost-provider"));
    assertTrue(page.refusal().contains("not registered"));
  }

  /**
   * Spec §7: {@code ignoredDomains} is advisory capability data about what <em>this deployment's
   * own fetcher</em> can load — Fox News refuses a headless fetch and serves a desktop browser the
   * same page, so the list names a limitation of one fetch tier, not a rule about what a search
   * result is allowed to contain. A provider that declares {@code domainExclusion} receives the
   * list (see {@link RemoteSearchProvider}'s own javadoc on why it is withheld from a provider that
   * has not declared it) and is free to spend its result budget avoiding those hosts, but nothing
   * downstream ever re-filters on the list itself — a provider returning a suppressed domain anyway
   * is not misbehaving, and the answer it gave is still the answer.
   *
   * <p><b>This test is deliberate. Do not "fix" it into asserting the domain was filtered out.</b>
   * A hard filter here would defeat the reason tiered fetch exists at all: a better fetcher
   * shrinking the ignore list is supposed to widen what future searches can return, which only
   * works if nothing between the provider and the model quietly narrows it back down again. If a
   * suppressed domain must never reach a model, that is a new, explicit policy for a later slice to
   * add and name — not a side effect of this list, and not something this test should start
   * assuming on the strength of one write.
   */
  @Test
  void a_provider_that_ignores_the_hint_still_reaches_the_model() throws Exception {
    try (MockWebServer provider = new MockWebServer()) {
      provider.start();
      register("honest-about-declaring-it", provider, true);
      properties.setLadder("honest-about-declaring-it");
      properties.setIgnoredDomains("suppressed.example");
      provider.enqueue(
          success("honest-about-declaring-it", hitJson("https://suppressed.example/page")));
      SearchService service = remoteService(Duration.ofSeconds(5));

      SearchPage page = service.search("q", 10, 10, 1);

      assertEquals(1, page.hits().size());
      assertEquals("https://suppressed.example/page", page.hits().get(0).url());

      // The provider really was told about the ignore list -- it just
      // chose not to honour it -- so this is a test of an ignored hint,
      // not an untransmitted one.
      assertTrue(provider.takeRequest().getBody().readUtf8().contains("suppressed.example"));
    }
  }
}
