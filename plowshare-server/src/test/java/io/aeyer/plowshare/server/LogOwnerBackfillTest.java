package io.aeyer.plowshare.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What {@code V63__logs_owned_by_their_tree.sql} does to logs opened before V61 recorded owners: a
 * log a run was started from or conducted in takes the run's account, a delegated child takes its
 * root's, and a log with no run in its tree stays unowned. Stops at V62, writes the rows a running
 * server held, then lets V63 land on them ({@link ProjectIdBackfillTest}'s shape).
 */
@Testcontainers
class LogOwnerBackfillTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final OffsetDateTime WHEN =
      OffsetDateTime.ofInstant(Instant.parse("2026-09-29T03:37:37Z"), ZoneOffset.UTC);

  private JdbcTemplate jdbc;

  @BeforeEach
  void schemaAtV62() {
    jdbc = new JdbcTemplate(dataSource());
    jdbc.execute("DROP SCHEMA public CASCADE");
    jdbc.execute("CREATE SCHEMA public");
    migrateTo("62");
    jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('enzo', 'x')");
    root("cnv_bot", "turn", null);
    child("cnv_bot_helper", "cnv_bot");
    child("cnv_bot_helper_helper", "cnv_bot_helper");
    root("cnv_conductor", "orchestration", "implement_specification");
    child("cnv_coder", "cnv_conductor");
    root("cnv_lonely", "turn", null);
    run("orc_1", "cnv_conductor", "cnv_bot", "enzo");
  }

  @Test
  void every_log_in_a_runs_tree_is_owned_by_the_runs_account() {
    migrateTo("63");

    assertEquals("enzo", ownerOf("cnv_bot"), "the log the run was started from");
    assertEquals("enzo", ownerOf("cnv_bot_helper"), "a child takes its root's");
    assertEquals("enzo", ownerOf("cnv_bot_helper_helper"), "and its child in turn");
    assertEquals("enzo", ownerOf("cnv_conductor"), "the run's conductor log");
    assertEquals("enzo", ownerOf("cnv_coder"), "a delegation under the conductor");
    assertNull(ownerOf("cnv_lonely"), "no run in its tree: nothing to read an owner from");
  }

  @Test
  void an_owner_already_written_is_kept() {
    jdbc.update("UPDATE conversations SET owner_handle = 'someone' WHERE id = 'cnv_bot'");

    migrateTo("63");

    assertEquals("someone", ownerOf("cnv_bot"));
    assertEquals("someone", ownerOf("cnv_bot_helper"), "the tree follows its root's owner");
  }

  private static DriverManagerDataSource dataSource() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static void migrateTo(String version) {
    Flyway.configure().dataSource(dataSource()).target(version).load().migrate();
  }

  private void root(String id, String origin, String agent) {
    jdbc.update(
        "INSERT INTO conversations (id, origin, agent, lifecycle, created_at,"
            + " budget_total, budget_spent) VALUES (?, ?, ?, 'active', ?, 100, 0)",
        id,
        origin,
        agent,
        WHEN);
  }

  private void child(String id, String parent) {
    jdbc.update(
        "INSERT INTO conversations (id, origin, agent, parent_id, lifecycle, created_at)"
            + " VALUES (?, 'delegation', 'coder', ?, NULL, ?)",
        id,
        parent,
        WHEN);
  }

  private void run(String id, String conductor, String caller, String handle) {
    jdbc.update(
        "INSERT INTO orchestrations (id, definition_name, tier, definition_hash,"
            + " stages, max_returns, conductor_conversation, caller_conversation,"
            + " caller_agent, caller_handle, state, created_at, definition_source,"
            + " definition_origin, depth) VALUES (?, 'implement_specification', 'SHIPPED',"
            + " 'h', '[]'::jsonb, 2, ?, ?, 'aristoxenus', ?, 'running', ?, 's', 'o', 0)",
        id,
        conductor,
        caller,
        handle,
        WHEN);
  }

  private String ownerOf(String id) {
    return jdbc.queryForObject(
        "SELECT owner_handle FROM conversations WHERE id = ?", String.class, id);
  }
}
