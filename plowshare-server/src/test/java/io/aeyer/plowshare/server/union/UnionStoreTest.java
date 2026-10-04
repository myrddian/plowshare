package io.aeyer.plowshare.server.union;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
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

@Tag("full-db")
@Testcontainers
class UnionStoreTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private UnionStore store;
  private static final Instant AT = Instant.parse("2026-09-14T10:00:00Z");

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
  }

  @BeforeEach
  void rows() {
    jdbc.update("DELETE FROM union_conflicts");
    jdbc.update("DELETE FROM projects WHERE name IN ('ledger', 'served')");
    jdbc.update(
        "INSERT INTO projects (name, machine, workspace) VALUES"
            + " ('ledger', 'laptop', '/Users/example/proj/ledger')");
    jdbc.update("INSERT INTO projects (name, workspace) VALUES ('served', '/srv/served')");
    store = new UnionStore(jdbc);
  }

  @Test
  void a_client_rooted_project_is_found_and_is_not_yet_a_union() {
    UnionStore.Union found = store.find("ledger").orElseThrow();
    assertEquals("laptop", found.machine());
    assertEquals("/Users/example/proj/ledger", found.workspace());
    assertFalse(found.enabled());
    assertEquals(List.of(), found.syncHidden());
    assertEquals(List.of(), found.exclusions());
  }

  @Test
  void a_rows_own_exclusions_are_read() {
    jdbc.update(
        "UPDATE projects SET exclusions = ARRAY['/Users/example/proj/ledger/secrets']"
            + " WHERE name = 'ledger'");

    assertEquals(
        List.of("/Users/example/proj/ledger/secrets"),
        store.find("ledger").orElseThrow().exclusions());
  }

  @Test
  void a_server_rooted_project_is_not_a_union_candidate() {
    assertTrue(store.find("served").isEmpty());
  }

  @Test
  void enabling_is_listed_and_disabling_clears_it() {
    store.enable("ledger", AT);
    assertEquals(List.of("ledger"), store.enabled().stream().map(UnionStore.Union::name).toList());
    store.disable("ledger");
    assertTrue(store.enabled().isEmpty());
  }

  @Test
  void the_database_refuses_a_union_without_a_machine() {
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE projects SET union_since = now() WHERE name = 'served'"));
  }

  @Test
  void hidden_allowlist_round_trips() {
    store.setHidden("ledger", List.of(".github/", ".eslintrc"));
    assertEquals(List.of(".github/", ".eslintrc"), store.find("ledger").orElseThrow().syncHidden());
  }

  @Test
  void conflicts_are_numbered_listed_and_resolved() {
    long id = store.find("ledger").orElseThrow().projectId();
    int first =
        store.openConflict(
            id,
            new UnionStore.NewConflict("src/a.ts", "b1", "o1", "t1", "nightly-bot", "run_1"),
            AT);
    int second =
        store.openConflict(
            id, new UnionStore.NewConflict("src/b.ts", null, "o2", null, "nightly-bot", null), AT);
    assertEquals(1, first);
    assertEquals(2, second);
    assertEquals(
        List.of("src/a.ts", "src/b.ts"),
        store.open(id).stream().map(UnionStore.Conflict::path).toList());
    assertTrue(store.resolve(id, 1, "theirs", AT));
    assertFalse(store.resolve(id, 1, "mine", AT), "an already resolved conflict stays resolved");
    assertEquals(
        List.of("src/b.ts"), store.open(id).stream().map(UnionStore.Conflict::path).toList());
  }

  @Test
  void a_notice_needs_no_conversation_but_a_run_does() {
    jdbc.update(
        "INSERT INTO admins (handle, password_hash) VALUES ('enzo', 'x') ON CONFLICT DO NOTHING");
    jdbc.update(
        "INSERT INTO user_inbox (id, handle, kind, answer, arrived_at)"
            + " VALUES ('inb_n1', 'enzo', 'sync.conflict', 'text', now())");
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO user_inbox (id, handle, kind, answer, arrived_at)"
                    + " VALUES ('inb_n2', 'enzo', 'run', 'text', now())"));
  }
}
