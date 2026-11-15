package io.aeyer.plowshare.server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Requires PostgreSQL for the real upgrade constraint names and partial unique-index behavior. */
@Tag("full-db")
@Testcontainers
class MessageFailureStatusMigrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  @Test
  void status_notice_preserves_final_reply_and_is_unique_and_error_only() throws Exception {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var jdbc = new JdbcTemplate(source);
    // Reproduce the two V93 CHECK clauses in their original declaration order. The V243 table
    // rename preserves their generated names; V267 must replace the actual inherited constraint.
    jdbc.execute(
        """
        CREATE TABLE board_message_routes (
          message TEXT PRIMARY KEY, reply_to TEXT, reply_expected BOOLEAN NOT NULL,
          final BOOLEAN NOT NULL, generated BOOLEAN NOT NULL, ending TEXT,
          CHECK (NOT final OR reply_to IS NOT NULL),
          CHECK (NOT generated OR (final AND ending IS NOT NULL)));
        CREATE UNIQUE INDEX board_message_final_reply ON board_message_routes(reply_to) WHERE final;
        ALTER TABLE board_message_routes RENAME TO message_routes;
        """);
    jdbc.execute(
        new ClassPathResource("db/migration/V267__message_failure_status_notices.sql")
            .getContentAsString(StandardCharsets.UTF_8));
    jdbc.update("INSERT INTO message_routes VALUES ('request',NULL,false,false,false,NULL)");
    jdbc.update("INSERT INTO message_routes VALUES ('explicit','request',false,true,false,NULL)");
    jdbc.update(
        "INSERT INTO message_routes VALUES ('error','request',false,false,true,'UNAVAILABLE')");
    assertEquals(
        "explicit",
        jdbc.queryForObject(
            "SELECT message FROM message_routes WHERE reply_to='request' AND final", String.class));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO message_routes VALUES ('duplicate','request',false,false,true,'STUCK')"));
    for (String ending : new String[] {"ANSWERED", "AWAITING"}) {
      assertThrows(
          DataIntegrityViolationException.class,
          () ->
              jdbc.update(
                  "INSERT INTO message_routes VALUES (?, 'another', false, false, true, ?)",
                  ending,
                  ending));
    }
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO message_routes VALUES ('pingpong','another',true,false,true,'UNAVAILABLE')"));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO message_routes VALUES ('uncorrelated',NULL,false,false,true,'UNAVAILABLE')"));
  }
}
