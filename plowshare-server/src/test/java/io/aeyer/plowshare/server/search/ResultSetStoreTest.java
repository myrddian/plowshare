package io.aeyer.plowshare.server.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.search.Hit;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link ResultSetStore} against a real Postgres, on {@code ProviderStoreTest}'s shape: no Spring
 * context, a Testcontainers Postgres migrated once for the class, and a fresh store over a
 * truncated table per test. There is no shared {@code PostgresTest} base in this module — see that
 * test's own javadoc — so each store's test carries its own container.
 *
 * <p>Keys below are real {@link QueryKey#of} outputs, not arbitrary literals like {@code "k"}:
 * {@code search_result_sets_a_key_is_a_sha_256} rejects anything that is not 64 lowercase hex
 * characters, on {@code V34__search_result_sets.sql}'s argument that a caller which skipped the
 * hashing must be caught here rather than let write a query's plaintext into this table unnoticed.
 * {@link #a_real_query_is_hashed_by_query_key_and_round_trips_through_the_store()} exercises the
 * two classes together end to end; every other test uses a {@link QueryKey#of} value too, so none
 * of them could pass against a schema that dropped or weakened that constraint.
 */
@Testcontainers
class ResultSetStoreTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Duration TTL = Duration.ofMinutes(30);
  private static final Instant T0 = Instant.parse("2026-09-08T10:00:00Z");

  private static final String KEY = QueryKey.of("plowshare harness", 25);
  private static final String OTHER_KEY = QueryKey.of("a different search", 25);

  private static JdbcTemplate jdbc;

  private ResultSetStore sets;

  @BeforeAll
  static void migrate() {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = new JdbcTemplate(dataSource);
  }

  @BeforeEach
  void emptyStore() {
    jdbc.execute("TRUNCATE TABLE search_result_sets");
    sets = new ResultSetStore(jdbc);
  }

  @Test
  void a_real_query_is_hashed_by_query_key_and_round_trips_through_the_store() {
    String key = QueryKey.of("  Plowshare   Harness ", 25);

    sets.put(key, "searxng", List.of(new Hit("https://a", "A", "s")), T0);

    StoredSet s = sets.get(key, T0, TTL).orElseThrow();
    assertEquals("searxng", s.providerKey());

    // The same search asked with different whitespace and case hashes to
    // the same key, so it finds the set the first asking wrote -- the
    // normalisation QueryKey.of itself argues for.
    assertEquals(key, QueryKey.of("plowshare harness", 25));
    assertTrue(sets.get(QueryKey.of("plowshare harness", 25), T0, TTL).isPresent());
  }

  @Test
  void a_stored_set_reads_back_whole_and_in_order() {
    sets.put(
        KEY,
        "searxng",
        List.of(new Hit("https://a", "A", "s"), new Hit("https://b", "B", "s")),
        T0);

    StoredSet s = sets.get(KEY, T0, TTL).orElseThrow();

    assertEquals("searxng", s.providerKey());
    assertEquals(List.of("https://a", "https://b"), s.hits().stream().map(Hit::url).toList());
  }

  @Test
  void a_set_past_its_ttl_is_absent_rather_than_stale() {
    sets.put(KEY, "searxng", List.of(new Hit("https://a", "A", "s")), T0);
    assertTrue(sets.get(KEY, T0.plus(Duration.ofMinutes(31)), TTL).isEmpty());
  }

  /**
   * {@code GET}'s {@code fetched_at > ?} is strict, so a set exactly {@code ttl} old is already
   * expired rather than surviving one more instant — the boundary {@code get}'s own comment claims
   * but the other tests never exercise, since 31 minutes against a 30-minute TTL and a
   * 0-vs-2-minute age both land well clear of it.
   *
   * <p>The margin is one MICROSECOND, not one nanosecond, and that is a finding of its own rather
   * than a rounder number picked for looks: {@code TIMESTAMPTZ} and the driver's {@code
   * OffsetDateTime} binding both hold microsecond resolution, so a one-nanosecond margin is rounded
   * away before Postgres ever compares it — this test failed at that margin first, both sides
   * reading as the same instant, before being widened to the coarsest unit either side of this
   * boundary can actually represent.
   */
  @Test
  void a_set_exactly_at_the_ttl_boundary_is_absent_and_one_microsecond_earlier_is_present() {
    sets.put(KEY, "searxng", List.of(new Hit("https://a", "A", "s")), T0);

    assertTrue(
        sets.get(KEY, T0.plus(TTL), TTL).isEmpty(),
        "a set fetched exactly ttl ago has already expired");
    assertTrue(
        sets.get(KEY, T0.plus(TTL).minusNanos(1_000), TTL).isPresent(),
        "one microsecond short of ttl, the set has not expired yet");
  }

  @Test
  void searching_the_same_thing_again_replaces_the_set_rather_than_adding_one() {
    sets.put(KEY, "searxng", List.of(new Hit("https://a", "A", "s")), T0);
    sets.put(KEY, "brave", List.of(new Hit("https://b", "B", "s")), T0);
    assertEquals("brave", sets.get(KEY, T0, TTL).orElseThrow().providerKey());
  }

  @Test
  void purging_removes_only_what_has_expired() {
    sets.put(KEY, "p", List.of(new Hit("https://a", "A", "s")), T0);
    sets.put(
        OTHER_KEY, "p", List.of(new Hit("https://b", "B", "s")), T0.plus(Duration.ofMinutes(29)));

    assertEquals(1, sets.purgeExpired(T0.plus(Duration.ofMinutes(31)), TTL));
    assertTrue(sets.get(OTHER_KEY, T0.plus(Duration.ofMinutes(31)), TTL).isPresent());
    assertFalse(sets.get(KEY, T0.plus(Duration.ofMinutes(31)), TTL).isPresent());
  }

  /**
   * {@code PURGE_EXPIRED}'s {@code fetched_at <= ?} is inclusive, the exact complement of {@code
   * GET}'s strict {@code >} above — a row exactly {@code ttl} old is expired to both, on the same
   * boundary, so neither ever disagrees with the other about a set that old. Pinned the same way as
   * the {@code get} boundary, for the same reason: the surrounding tests all land comfortably clear
   * of the edge, and with the same one- microsecond margin — see that test's own comment for why
   * one nanosecond is not representable here.
   */
  @Test
  void purging_reaches_a_row_exactly_at_the_ttl_boundary_but_not_one_microsecond_short_of_it() {
    sets.put(KEY, "p", List.of(new Hit("https://a", "A", "s")), T0);
    assertEquals(
        0,
        sets.purgeExpired(T0.plus(TTL).minusNanos(1_000), TTL),
        "one microsecond short of ttl, the row has not expired yet");
    assertTrue(
        sets.get(KEY, T0, TTL).isPresent(), "the row purgeExpired left alone is still there");

    assertEquals(
        1,
        sets.purgeExpired(T0.plus(TTL), TTL),
        "a row fetched exactly ttl ago is expired, the same instant get() treats as expired");
  }

  /**
   * Proves {@code search_result_sets_a_key_is_a_sha_256} actually bites, rather than merely
   * trusting that every other test in this class happens to pass a real {@link QueryKey#of} value.
   * A caller that skipped the hashing — passing a query's raw text, or any other non-hash string,
   * straight into {@link ResultSetStore#put} — is refused by the database itself, which is the one
   * guard standing between a one-line mistake and a query's plaintext landing in this table.
   */
  @Test
  void a_key_that_is_not_a_sha_256_is_refused_by_the_database() {
    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> sets.put("not-a-hash", "p", List.of(new Hit("https://a", "A", "s")), T0));

    assertTrue(
        refused.getMessage().contains("search_result_sets_a_key_is_a_sha_256"),
        refused.getMessage());
  }
}
