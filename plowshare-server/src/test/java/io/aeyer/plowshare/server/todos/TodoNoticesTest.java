package io.aeyer.plowshare.server.todos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

@Tag("full-db")
@Testcontainers
class TodoNoticesTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private TodoNotices.Jdbc notices;

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
    jdbc.execute("TRUNCATE TABLE todo_notices");
    notices = new TodoNotices.Jdbc(jdbc);
  }

  @Test
  void seen_on_an_unknown_conversation_is_empty() {
    assertEquals(Optional.empty(), notices.seen("cnv_1"));
  }

  @Test
  void remember_then_seen_round_trips() {
    TodoNotices.Seen seen = new TodoNotices.Seen("hash-1", 3);
    notices.remember("cnv_1", seen);

    assertEquals(Optional.of(seen), notices.seen("cnv_1"));
  }

  @Test
  void a_second_remember_replaces_the_first() {
    notices.remember("cnv_1", new TodoNotices.Seen("hash-1", 3));
    notices.remember("cnv_1", new TodoNotices.Seen("hash-2", 5));

    Optional<TodoNotices.Seen> after = notices.seen("cnv_1");
    assertTrue(after.isPresent());
    assertEquals(new TodoNotices.Seen("hash-2", 5), after.get());
  }

  @Test
  void forget_deletes_the_row() {
    notices.remember("cnv_1", new TodoNotices.Seen("hash-1", 3));

    notices.forget("cnv_1");

    assertEquals(Optional.empty(), notices.seen("cnv_1"));
  }
}
