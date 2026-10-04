package io.aeyer.plowshare.server.fetch;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Spec §2.8: the migration that ships the guard empties {@code fetched_pages}, once. A page read
 * from a private address before the guard existed must not stay readable from the buffer. A page
 * stored after the migration is an ordinary cache entry, and a later boot must not throw it away.
 * This follows {@code ProjectIdBackfillTest}: migrate to the version before, seed, then migrate the
 * rest.
 */
@Testcontainers
class TheBufferIsEmptiedOnceTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Instant T0 = Instant.parse("2026-09-29T10:00:00Z");

  private static DriverManagerDataSource dataSource() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  @Test
  void the_guards_migration_empties_the_buffer_and_a_page_stored_after_it_is_kept() {
    Flyway.configure().dataSource(dataSource()).target("62").load().migrate();
    FetchedPageStore pages = new FetchedPageStore(new JdbcTemplate(dataSource()));

    String before = "http://intranet.example/read-before-the-guard";
    pages.put(UrlKey.of(before), before, new ExtractedPage("T", "internal text"), T0);
    assertTrue(pages.find(UrlKey.of(before)).isPresent());

    Flyway.configure().dataSource(dataSource()).load().migrate();
    assertTrue(
        pages.find(UrlKey.of(before)).isEmpty(),
        "a page fetched before the guard may have come from a private address, and"
            + " the buffer serves it without dialling");

    String after = "https://example.com/read-after-the-guard";
    pages.put(UrlKey.of(after), after, new ExtractedPage("T", "public text"), T0);
    Flyway.configure().dataSource(dataSource()).load().migrate();
    assertTrue(
        pages.find(UrlKey.of(after)).isPresent(),
        "emptied once, by the migration, not on every boot");
  }
}
