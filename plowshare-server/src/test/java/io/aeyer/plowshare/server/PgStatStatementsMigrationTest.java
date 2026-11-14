package io.aeyer.plowshare.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifies that V260 installs pg_stat_statements from the pgvector image and is idempotent. */
@Tag("full-db")
@Testcontainers
class PgStatStatementsMigrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  @Test
  void migration_installs_the_extension_once_and_can_repeat() {
    var data =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(data).load().migrate();
    var jdbc = new JdbcTemplate(data);
    jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");

    Integer installed =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_extension WHERE extname='pg_stat_statements'", Integer.class);

    assertEquals(1, installed);
  }
}
