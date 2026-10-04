package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
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

/** V74's shapes, each the constraint the spec's §4 argues for. */
@Tag("full-db")
@Testcontainers
class BoardSchemaTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;

  @BeforeAll
  static void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
  }

  @BeforeEach
  void fresh() {
    jdbc.execute(
        "TRUNCATE TABLE firings, board_seats, board_messages, board_topics,"
            + " user_inbox, admins CASCADE");
    jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('enzo', 'h')");
  }

  private static void root(String id) {
    jdbc.update(
        "INSERT INTO board_topics (id, project, root, depth, title, label, account,"
            + " opener_kind, opener, state, pot_total, pot_spent, reserve, opened_at)"
            + " VALUES (?, 'payments', ?, 0, 'sync', 'BAD SPEC', 'enzo', 'person', 'enzo',"
            + " 'open', 20, 0, 2, now())",
        id,
        id);
  }

  @Test
  void a_seat_conversation_is_a_root_that_names_its_agent_and_holds_no_budget() {
    ConversationRecord seat =
        new ConversationStore(jdbc)
            .log(Origin.BOARD, Home.global(), "researcher", null, null, "enzo");
    assertEquals(Origin.BOARD, seat.origin());
    assertNull(seat.budget());
    assertNull(seat.parentId());
  }

  @Test
  void a_wake_is_a_queued_firing_with_a_topic_and_no_trigger() {
    root("bdt_1");
    assertDoesNotThrow(
        () ->
            jdbc.update(
                "INSERT INTO firings (id, event, target, status,"
                    + " arrived_at, topic) VALUES ('fir_1', 'board.wake', 'conversation:c', 'queued',"
                    + " now(), 'bdt_1')"));
  }

  @Test
  void a_wake_is_never_unmatched_and_never_a_triggers() {
    root("bdt_1");
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO"
                    + " firings (id, event, status, arrived_at, topic) VALUES ('fir_1', 'board.wake',"
                    + " 'unmatched', now(), 'bdt_1')"));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO"
                    + " firings (id, event, target, status, arrived_at, topic, trigger) VALUES"
                    + " ('fir_2', 'board.wake', 'conversation:c', 'queued', now(), 'bdt_1', 't')"));
  }

  @Test
  void only_a_root_holds_the_pot_and_its_reserve_is_below_its_total() {
    root("bdt_1");
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO"
                    + " board_topics (id, project, parent, root, depth, title, label, account,"
                    + " opener_kind, opener, state, pot_total, pot_spent, reserve, opened_at) VALUES"
                    + " ('bdt_2', 'payments', 'bdt_1', 'bdt_1', 1, 'crdt', 'RESEARCH', 'enzo',"
                    + " 'member', 'researcher', 'open', 20, 0, 2, now())"));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO"
                    + " board_topics (id, project, root, depth, title, label, account, opener_kind,"
                    + " opener, state, pot_total, pot_spent, reserve, opened_at) VALUES ('bdt_3',"
                    + " 'payments', 'bdt_3', 0, 'x', 'y', 'enzo', 'person', 'enzo', 'open', 5, 0, 5,"
                    + " now())"));
  }

  @Test
  void a_topics_root_must_name_a_real_topic_while_a_roots_own_self_reference_is_legal() {
    assertDoesNotThrow(() -> root("bdt_1"));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO"
                    + " board_topics (id, project, parent, root, depth, title, label, account,"
                    + " opener_kind, opener, state, opened_at) VALUES ('bdt_2', 'payments', 'bdt_1',"
                    + " 'bdt_missing', 1, 'crdt', 'RESEARCH', 'enzo', 'member', 'researcher', 'open',"
                    + " now())"));
  }

  @Test
  void a_bot_opens_from_a_conversation_and_nobody_else_does() {
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO"
                    + " board_topics (id, project, root, depth, title, label, account, opener_kind,"
                    + " opener, state, pot_total, pot_spent, reserve, opened_at) VALUES ('bdt_4',"
                    + " 'payments', 'bdt_4', 0, 'x', 'y', 'enzo', 'bot', 'aristoxenus', 'open', 20,"
                    + " 0, 2, now())"));
  }

  /**
   * A topic's title and label reach a woken seat inside the harness's own wake line, so the
   * database bounds them as Board.open does: 120 and 60 characters — counted as Postgres's
   * char_length counts, in characters, not bytes.
   */
  @Test
  void a_topics_title_and_label_are_bounded() {
    String insert =
        "INSERT INTO board_topics (id, project, root, depth, title, label, account,"
            + " opener_kind, opener, state, pot_total, pot_spent, reserve, opened_at) VALUES"
            + " (?, 'payments', ?, 0, ?, ?, 'enzo', 'person', 'enzo', 'open', 20, 0, 2, now())";
    assertDoesNotThrow(
        () -> jdbc.update(insert, "bdt_1", "bdt_1", "é".repeat(120), "é".repeat(60)));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update(insert, "bdt_2", "bdt_2", "x".repeat(121), "L"));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update(insert, "bdt_3", "bdt_3", "t", "x".repeat(61)));
  }

  @Test
  void the_inbox_takes_a_board_item() {
    assertDoesNotThrow(
        () ->
            jdbc.update(
                "INSERT INTO user_inbox (id, handle, kind, answer,"
                    + " arrived_at) VALUES ('inb_1', 'enzo', 'board', 'resolved', now())"));
  }
}
