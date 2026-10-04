package io.aeyer.plowshare.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the database the rest of this project assumes actually exists: a Postgres a test can
 * reach, carrying the pgvector extension.
 */
@Testcontainers
class DatabaseSmokeTest {

  /**
   * The pgvector image, not a stock postgres:16 — stock Postgres has no vector.so to load, so
   * CREATE EXTENSION fails there however the migration is written.
   */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  /**
   * Not ceremony: pgvector is an extension, not a built-in, and a build that discovers this at the
   * first embedding write discovers it a long way from the cause.
   */
  @Test
  void the_vector_extension_is_available() throws Exception {
    try (var conn =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      conn.createStatement().execute("CREATE EXTENSION IF NOT EXISTS vector");
      var rs =
          conn.createStatement()
              .executeQuery("SELECT extname FROM pg_extension WHERE extname = 'vector'");
      assertTrue(rs.next());
      assertEquals("vector", rs.getString("extname"));
    }
  }
}
