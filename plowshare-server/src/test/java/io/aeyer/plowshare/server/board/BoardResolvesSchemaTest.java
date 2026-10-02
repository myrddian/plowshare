package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

/** V75's shapes: a resolution delivered once, and a title and a label one line for every writer. */
@Testcontainers
class BoardResolvesSchemaTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        var source = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @BeforeEach
    void fresh() {
        jdbc.execute("TRUNCATE TABLE firings, board_seats, board_messages, board_topics,"
                + " user_inbox, admins CASCADE");
        jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('enzo', 'h')");
    }

    private static void topic(String id, String title) {
        jdbc.update("INSERT INTO board_topics (id, project, root, depth, title, label, account,"
                + " opener_kind, opener, state, pot_total, pot_spent, reserve, opened_at)"
                + " VALUES (?, 'payments', ?, 0, ?, 'BAD SPEC', 'enzo', 'person', 'enzo',"
                + " 'open', 20, 0, 2, now())", id, id, title);
    }

    @Test
    void a_title_is_one_line_for_every_writer() {
        assertThrows(DataIntegrityViolationException.class, () -> topic("bdt_1", "two\nlines"));
        assertThrows(DataIntegrityViolationException.class, () -> topic("bdt_2", "cr\rhere"));
        assertDoesNotThrow(() -> topic("bdt_3", "one line"));
    }

    @Test
    void a_resolution_is_marked_delivered_only_once_it_exists() {
        topic("bdt_1", "sync");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE board_topics SET resolution_delivered_at = now() WHERE id = 'bdt_1'"));
    }
}
