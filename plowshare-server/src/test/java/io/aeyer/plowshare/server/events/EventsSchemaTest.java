package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The seam for V40: that {@code schedules}, {@code triggers}, {@code firings} and {@code
 * user_inbox} exist with the shape the design asks for, and that {@code Origin.EVENT} really is a
 * conversation origin the schema knows.
 *
 * <p>This is a schema test, not a store test — there is no {@code EventStore} yet for any of these
 * tables, so every assertion here talks to the database directly with a {@link JdbcTemplate}, the
 * same shape {@code AdminStoreTest} uses for the table it introduces.
 */
@Tag("full-db")
@Testcontainers
class EventsSchemaTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;

  @BeforeAll
  static void migrate() {
    Flyway.configure().dataSource(dataSource()).load().migrate();
    jdbc = new JdbcTemplate(dataSource());
  }

  private static DriverManagerDataSource dataSource() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  @BeforeEach
  void fresh() {
    jdbc.execute(
        "TRUNCATE TABLE user_inbox, firings, triggers, schedules, admins,"
            + " entries, turns, conversations CASCADE");
    jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('enzo', 'h')");
  }

  @Test
  void a_conversation_can_be_opened_with_the_event_origin_and_its_own_allowance() {
    // No `home` column exists on `conversations` -- V14 replaced the text
    // `project` column with a `project_id BIGINT` FK to `projects(id)`, and
    // the global tier is the absence of a value rather than a name for it
    // (V1's rule, restated at V6 and V14). Omitting the column is how a
    // conversation lands in the global tier.
    jdbc.update(
        "INSERT INTO conversations (id, origin, agent, created_at,"
            + " budget_total, budget_spent) VALUES ('cnv_1', 'event', 'bard',"
            + " now(), 10, 0)");
    assertEquals(
        "event",
        jdbc.queryForObject("SELECT origin FROM conversations WHERE id = 'cnv_1'", String.class));
  }

  @Test
  void an_event_conversation_without_an_allowance_is_refused() {
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO conversations (id, origin, agent, created_at)"
                    + " VALUES ('cnv_2', 'event', 'bard', now())"));
  }

  @Test
  void a_trigger_may_not_name_both_a_project_and_a_conversation() {
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO triggers (name, event, project, conversation, agent, task,"
                    + " defined_by) VALUES ('t', 'e', 'p', 'cnv_x', 'bard', 'do it', 'enzo')"));
  }

  @Test
  void a_tick_can_be_claimed_once_per_schedule_and_instant() {
    String insert =
        "INSERT INTO firings (id, event, data, schedule, fire_at, status, target,"
            + " arrived_at) VALUES (?, 'daily', '{}'::jsonb, 'nine', '2026-09-13T09:00:00Z',"
            + " 'unmatched', NULL, now())";
    jdbc.update(insert, "fir_1");
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, "fir_2"));
  }

  @Test
  void an_inbox_item_belongs_to_an_account_that_exists() {
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO user_inbox (id, handle, firing, conversation, ending, answer,"
                    + " arrived_at) VALUES ('inb_1', 'nobody', NULL, 'cnv_1', 'ANSWERED',"
                    + " 'hi', now())"));
  }
}
