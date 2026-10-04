package io.aeyer.plowshare.server.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
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
 * {@link FetchedPageStore} against a real Postgres, on {@code ResultSetStoreTest}'s shape: no
 * Spring context, a Testcontainers Postgres migrated once for the class, and a fresh store over a
 * truncated table per test. There is no shared {@code PostgresTest} base in this module, so this
 * test carries its own container rather than sharing one.
 */
@Tag("full-db")
@Testcontainers
class FetchedPageStoreTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Instant T0 = Instant.parse("2026-09-09T10:00:00Z");
  private static final Duration TTL = Duration.ofHours(24);
  private static final Duration LIVE = Duration.ofMinutes(10);

  private static JdbcTemplate jdbc;

  private FetchedPageStore pages;

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
    jdbc.execute("TRUNCATE TABLE fetched_pages");
    pages = new FetchedPageStore(jdbc);
  }

  @Test
  void a_stored_page_reads_back_whole() {
    String key = UrlKey.of("https://e.example/a");
    pages.put(key, "https://e.example/a", new ExtractedPage("T", "one\n\ntwo"), T0);
    FetchedPage page = pages.find(key).orElseThrow();
    assertEquals("T", page.title());
    assertEquals("one\n\ntwo", page.text());
    assertEquals(T0, page.fetchedAt());
    assertEquals(T0, page.lastRead());
    assertEquals(8L, page.byteSize());
  }

  @Test
  void fetching_the_same_url_again_replaces_the_row_rather_than_adding_one() {
    String key = UrlKey.of("https://e.example/a");
    pages.put(key, "https://e.example/a", new ExtractedPage("T", "first"), T0);
    pages.put(
        key, "https://e.example/a", new ExtractedPage("T", "second"), T0.plus(Duration.ofHours(1)));
    assertEquals("second", pages.find(key).orElseThrow().text());
  }

  @Test
  void touching_a_read_moves_last_read_and_leaves_fetched_at_alone() {
    String key = UrlKey.of("https://e.example/a");
    pages.put(key, "https://e.example/a", new ExtractedPage("T", "x"), T0);
    pages.touchRead(key, T0.plus(Duration.ofMinutes(5)));
    FetchedPage page = pages.find(key).orElseThrow();
    assertEquals(T0, page.fetchedAt());
    assertEquals(T0.plus(Duration.ofMinutes(5)), page.lastRead());
  }

  @Test
  void a_page_past_its_ttl_is_purged() {
    String key = UrlKey.of("https://e.example/a");
    pages.put(key, "https://e.example/a", new ExtractedPage("T", "x"), T0);
    assertEquals(1, pages.purgeExpired(T0.plus(Duration.ofHours(25)), TTL, LIVE));
    assertEquals(Optional.empty(), pages.find(key));
  }

  @Test
  void a_page_being_read_is_not_purged_however_old_its_fetch_is() {
    String key = UrlKey.of("https://e.example/a");
    pages.put(key, "https://e.example/a", new ExtractedPage("T", "x"), T0);
    Instant muchLater = T0.plus(Duration.ofHours(30));
    pages.touchRead(key, muchLater.minus(Duration.ofMinutes(1)));
    assertEquals(
        0,
        pages.purgeExpired(muchLater, TTL, LIVE),
        "liveness is an eviction guard as well as a refetch guard: a reader "
            + "part-way through a page must not have it deleted underneath them");
    assertTrue(pages.find(key).isPresent());
  }

  @Test
  void a_page_whose_read_has_gone_cold_is_purged_again() {
    String key = UrlKey.of("https://e.example/a");
    pages.put(key, "https://e.example/a", new ExtractedPage("T", "x"), T0);
    Instant muchLater = T0.plus(Duration.ofHours(30));
    pages.touchRead(key, muchLater.minus(Duration.ofMinutes(11)));
    assertEquals(1, pages.purgeExpired(muchLater, TTL, LIVE));
  }
}
