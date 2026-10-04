package io.aeyer.plowshare.server.todos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

@Testcontainers
class TodoStoreTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Instant T0 = Instant.parse("2026-09-13T09:00:00Z");
  private static JdbcTemplate jdbc;
  private TodoStore store;

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    jdbc = new JdbcTemplate(ds);
  }

  @BeforeEach
  void fresh() {
    jdbc.execute("TRUNCATE TABLE todos CASCADE");
    store = new TodoStore(jdbc);
  }

  private static TodoItem item(
      String id, String conversation, String parent, int position, String text) {
    return new TodoItem(
        id, conversation, parent, position, text, TodoStatus.PENDING, null, false, null, T0);
  }

  @Test
  void a_conversation_lists_only_its_own_items_top_level_first_in_position_order() {
    store.insert(item("td_b", "cnv_1", null, 1, "second"));
    store.insert(item("td_a", "cnv_1", null, 0, "first"));
    store.insert(item("td_c", "cnv_1", "td_a", 0, "child"));
    store.insert(item("td_z", "cnv_2", null, 0, "elsewhere"));

    assertEquals(
        List.of("td_a", "td_b", "td_c"), store.list("cnv_1").stream().map(TodoItem::id).toList());
  }

  @Test
  void an_update_writes_status_text_summary_and_position() {
    store.insert(item("td_a", "cnv_1", null, 0, "first"));
    TodoItem previous = store.list("cnv_1").get(0);
    TodoItem next =
        previous
            .withStatus(TodoStatus.DONE, T0.plusSeconds(1))
            .withText("renamed", T0.plusSeconds(1))
            .withSummary("did it", T0.plusSeconds(1))
            .withPosition(3, T0.plusSeconds(1));

    int rows = store.update(previous, next);

    assertEquals(1, rows);
    TodoItem read = store.list("cnv_1").get(0);
    assertEquals(TodoStatus.DONE, read.status());
    assertEquals("renamed", read.text());
    assertEquals("did it", read.summary());
    assertEquals(3, read.position());
    assertEquals(T0.plusSeconds(1), read.updatedAt());
  }

  @Test
  void an_update_against_a_stale_updated_at_changes_nothing_and_reports_zero_rows() {
    store.insert(item("td_a", "cnv_1", null, 0, "first"));
    TodoItem original = store.list("cnv_1").get(0);
    // Someone else wrote this row after `original` was read.
    int firstWrite =
        store.update(original, original.withStatus(TodoStatus.DONE, T0.plusSeconds(1)));
    assertEquals(1, firstWrite);

    // `original` is now stale: its updatedAt no longer names the row's current value.
    int staleWrite = store.update(original, original.withText("too late", T0.plusSeconds(2)));

    assertEquals(0, staleWrite);
    TodoItem read = store.list("cnv_1").get(0);
    assertEquals(TodoStatus.DONE, read.status());
    assertEquals("first", read.text());
  }

  @Test
  void a_stage_that_is_not_locked_is_refused_by_the_schema() {
    TodoItem unlockedStage =
        new TodoItem("td_s", "cnv_1", null, 0, "goal", TodoStatus.PENDING, null, false, "goal", T0);
    assertThrows(DataIntegrityViolationException.class, () -> store.insert(unlockedStage));
  }

  @Test
  void every_status_round_trips_through_its_wire_name() {
    for (TodoStatus status : TodoStatus.values()) {
      assertEquals(status, TodoStatus.fromWire(status.wire()).orElseThrow());
    }
    assertEquals(java.util.Optional.empty(), TodoStatus.fromWire("finished"));
  }
}
