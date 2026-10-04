package io.aeyer.plowshare.server.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.search.CostClass;
import io.aeyer.plowshare.protocol.search.Hit;
import io.aeyer.plowshare.protocol.search.NetworkTier;
import io.aeyer.plowshare.protocol.search.ProviderFacts;
import io.aeyer.plowshare.protocol.search.SearchAnswer;
import io.aeyer.plowshare.protocol.search.SearchPage;
import io.aeyer.plowshare.protocol.search.Verb;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * {@link SearchService} against a real Postgres — it reads through {@link ProviderStore} (via
 * {@link SearchLadder}) and {@link ResultSetStore}, both Postgres-backed, so a stand-in for either
 * would be testing the stand-in rather than the service. Follows {@code SearchLadderTest} and
 * {@code SearchPropertiesTest}: no Spring context, one Testcontainers Postgres migrated once for
 * the class, and a truncated {@code search_providers} and {@code search_result_sets} per test.
 * {@link SearchProvider} is a hand written fake, on {@code SearchLadderTest}'s own reasoning: this
 * class's job is deciding when to dial at all, not how one dial behaves.
 */
@Tag("full-db")
@Testcontainers
class SearchServiceTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;

  private ProviderStore store;
  private ResultSetStore sets;
  private SearchProperties properties;
  private TestClock clock;
  private SearchService service;

  private final Map<String, SearchAnswer> cannedAnswers = new HashMap<>();
  private final List<String> dialledKeys = new ArrayList<>();

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
    store = new ProviderStore(jdbc);
    sets = new ResultSetStore(jdbc);
    properties = new SearchProperties();
    properties.setFailureThreshold(3);
    properties.setResultSetTtl(Duration.ofHours(1));
    clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    cannedAnswers.clear();
    dialledKeys.clear();

    SearchLadder ladder = new SearchLadder(store, properties, recordingProvider());
    service = new SearchService(ladder, sets, properties, clock);
  }

  /**
   * A mutable {@link Clock} so a test can advance "now" without waiting on a wall clock — {@link
   * SearchService}'s whole reason for taking one.
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
        true);
  }

  /** Registers {@code providerKey} with zero consecutive failures. */
  private void register(String providerKey) {
    store.upsert(providerKey, "http://localhost:9", facts(providerKey));
  }

  /** Sets the ladder {@link SearchLadder} will walk. */
  private void ladderIs(String commaSeparated) {
    properties.setLadder(commaSeparated);
  }

  /**
   * The next dial for {@code providerKey} through {@code recordingProvider()} succeeds with these
   * hits.
   */
  private void answers(String providerKey, Hit... hits) {
    cannedAnswers.put(
        providerKey, SearchAnswer.success("placeholder", providerKey, List.of(hits), 1L));
  }

  /**
   * Twenty distinct hits, enough for two full pages of ten and a remainder of none — {@code Hit[]},
   * not {@code List<Hit>}, so {@link #answers}'s varargs accepts it as one argument.
   */
  private Hit[] twentyHits() {
    Hit[] hits = new Hit[20];
    for (int i = 0; i < 20; i++) {
      hits[i] = new Hit("https://" + i + ".example", "title " + i, "snippet " + i);
    }
    return hits;
  }

  /**
   * How many times {@code recordingProvider()} was actually asked to search, across every call made
   * so far in this test.
   */
  private int dials() {
    return dialledKeys.size();
  }

  /** Advances the fixture clock {@link SearchService} reads "now" from. */
  private void clockAdvances(Duration by) {
    clock.advance(by);
  }

  /**
   * The fixture clock's current instant — the same "now" {@link #service} would read if asked at
   * this moment.
   */
  private Instant now() {
    return clock.instant();
  }

  /** The TTL {@link #service} is currently configured with. */
  private Duration ttl() {
    return properties.resultSetTtlNow();
  }

  /**
   * A {@link SearchProvider} that never talks to a network: records the key it was asked for, then
   * answers from {@link #cannedAnswers} — a failure naming the fixture's own gap when nothing was
   * canned for that key, on {@code SearchLadderTest.recordingProvider}'s pattern.
   */
  private SearchProvider recordingProvider() {
    return (at, ask) -> {
      dialledKeys.add(at.providerKey());
      SearchAnswer canned = cannedAnswers.get(at.providerKey());
      if (canned == null) {
        return SearchAnswer.failed(
            ask.requestId(),
            at.providerKey(),
            "test fixture set up no canned answer for " + at.providerKey(),
            0L);
      }
      return new SearchAnswer(
          ask.requestId(),
          canned.providerKey(),
          canned.status(),
          canned.hits(),
          canned.elapsedMs(),
          canned.message());
    };
  }

  @Test
  void the_first_page_runs_the_ladder_and_stores_the_whole_set() {
    register("searxng");
    ladderIs("searxng");
    answers("searxng", twentyHits());

    SearchPage p = service.search("q", 10, 50, 1);

    assertEquals(10, p.hits().size());
    assertEquals(20, p.total());
    assertTrue(p.hasMore());
  }

  @Test
  void the_second_page_serves_from_the_stored_set_and_never_dials_the_provider() {
    register("searxng");
    ladderIs("searxng");
    answers("searxng", twentyHits());
    service.search("q", 10, 50, 1);
    int dialsBefore = dials();

    SearchPage p = service.search("q", 10, 50, 2);

    assertEquals(10, p.hits().size());
    assertFalse(p.hasMore());
    assertEquals(dialsBefore, dials());
  }

  /**
   * <b>The test that pins the behaviour group A of the branch review settled, and without it that
   * behaviour can silently revert.</b> Two identical page-one searches are two searches, and each
   * one runs the ladder: re-asking a question must be able to get a fresh answer, and nothing in
   * this design offers a caller any other way to ask for one.
   *
   * <p>Asserts the dial count is exactly {@code 2}, not merely "more than one": the failure this
   * guards against is a future edit moving {@link ResultSetStore#get} back above the {@code page >
   * 1} branch, which produces exactly {@code 1}. An assertion of "at least one" would pass against
   * that edit, and so would one that only checked the hits came back — the stored set holds the
   * same hits either way, which is the whole reason the regression is invisible from the response.
   *
   * <p>The same fact stated from the other side: this is what makes {@code search_result_sets} a
   * result set and not a cross-search cache, which spec §11 defers, spec §6 argues against by name
   * and {@code V34__search_result_sets.sql}'s own opening comment asserts as fact.
   */
  @Test
  void two_identical_page_one_searches_are_two_searches_and_run_the_ladder_twice() {
    register("searxng");
    ladderIs("searxng");
    answers("searxng", twentyHits());

    SearchPage first = service.search("q", 10, 50, 1);
    SearchPage second = service.search("q", 10, 50, 1);

    assertEquals(
        2,
        dials(),
        "page one always runs the ladder; a second identical search must not be"
            + " served from the first's stored set");
    assertEquals(10, first.hits().size());
    assertEquals(10, second.hits().size());
  }

  /**
   * The normaliser earning its keep where it still applies: a later page whose query differs from
   * page one's only in whitespace and case lands on the same stored set. Spec §12 asks for this;
   * page one no longer reads the store at all, so a <em>page-two</em> call is the only place the
   * property is observable through {@link SearchService} rather than only through {@link QueryKey}
   * in isolation.
   */
  @Test
  void a_later_page_whose_query_differs_only_in_whitespace_finds_the_same_set() {
    register("searxng");
    ladderIs("searxng");
    answers("searxng", twentyHits());
    service.search("plowshare harness", 10, 50, 1);
    int dialsBefore = dials();

    SearchPage p = service.search("  Plowshare   Harness ", 10, 50, 2);

    assertEquals(10, p.hits().size());
    assertEquals(dialsBefore, dials());
  }

  @Test
  void a_page_past_the_end_is_empty_and_says_so_without_an_error() {
    register("searxng");
    ladderIs("searxng");
    answers("searxng", twentyHits());
    service.search("q", 10, 50, 1);

    SearchPage p = service.search("q", 10, 50, 9);

    assertTrue(p.hits().isEmpty());
    assertFalse(p.hasMore());
  }

  @Test
  void a_page_against_an_expired_set_refuses_and_tells_the_caller_to_search_again() {
    register("searxng");
    ladderIs("searxng");
    answers("searxng", twentyHits());
    service.search("q", 10, 50, 1);
    clockAdvances(Duration.ofHours(2));

    SearchPage p = service.search("q", 10, 50, 2);

    assertTrue(p.refusal().contains("search again"));
  }

  @Test
  void an_expired_set_is_never_silently_refetched_because_that_changes_the_provider() {
    register("searxng");
    ladderIs("searxng");
    answers("searxng", twentyHits());
    service.search("q", 10, 50, 1);
    clockAdvances(Duration.ofHours(2));
    int dialsBefore = dials();

    service.search("q", 10, 50, 2);

    assertEquals(dialsBefore, dials());
  }

  @Test
  void an_exhausted_ladder_is_a_refusal_and_stores_nothing() {
    ladderIs("");

    SearchPage p = service.search("q", 10, 50, 1);

    assertFalse(p.refusal().isBlank());
    assertTrue(p.hits().isEmpty());
    assertTrue(sets.get(QueryKey.of("q", 50), now(), ttl()).isEmpty());
  }

  @Test
  void the_answer_never_names_the_provider_that_served_it() {
    register("searxng");
    ladderIs("searxng");
    answers("searxng", twentyHits());

    assertFalse(service.search("q", 10, 50, 1).toString().contains("searxng"));
  }

  // -- Malformed input is a 400, not a 500 or a silently different answer.
  // Each of these asserts the EXCEPTION TYPE, not merely that something
  // failed: the whole point is that a caller gets CallerFault
  // (which ApiExceptionHandler maps to 400) rather than an
  // IllegalArgumentException escaping from SearchAsk's own constructor
  // three calls down, which ApiExceptionHandler has no handler for and
  // which its Throwable fallback would misreport as a server fault.

  @Test
  void a_blank_query_is_a_bad_request_and_never_reaches_the_ladder() {
    register("searxng");
    ladderIs("searxng");
    answers("searxng", twentyHits());

    CallerFault refused = assertThrows(CallerFault.class, () -> service.search("  ", 10, 50, 1));

    assertTrue(refused.getMessage().contains("query"));
    assertEquals(0, dials());
  }

  @Test
  void a_non_positive_max_is_a_bad_request() {
    CallerFault refused = assertThrows(CallerFault.class, () -> service.search("q", 10, 0, 1));

    assertTrue(refused.getMessage().contains("max"));
  }

  @Test
  void a_page_of_zero_is_a_bad_request_rather_than_silently_aliasing_page_one() {
    register("searxng");
    ladderIs("searxng");
    answers("searxng", twentyHits());

    CallerFault refused = assertThrows(CallerFault.class, () -> service.search("q", 10, 50, 0));

    assertTrue(refused.getMessage().contains("page"));
  }

  @Test
  void a_negative_page_is_a_bad_request_too() {
    CallerFault refused = assertThrows(CallerFault.class, () -> service.search("q", 10, 50, -1));

    assertTrue(refused.getMessage().contains("page"));
  }

  @Test
  void a_non_positive_page_size_is_a_bad_request_rather_than_an_empty_page_forever() {
    CallerFault refused = assertThrows(CallerFault.class, () -> service.search("q", 0, 50, 1));

    assertTrue(refused.getMessage().contains("pageSize"));
  }
}
