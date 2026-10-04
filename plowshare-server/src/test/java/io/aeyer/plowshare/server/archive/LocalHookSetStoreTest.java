package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.hooks.HookFile;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

/** Spec 2026-09-30-local-hooks-are-served §3: sets stored by hash, and a log pinned to one once. */
@Testcontainers
class LocalHookSetStoreTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Instant T0 = Instant.parse("2026-09-30T09:00:00Z");
  private static final String UNKNOWN = "sha256:" + "0".repeat(64);
  private static final HookFile GUARD =
      new HookFile("10-guard.ts", "export default { name: 'guard' }\n// ü, and a second line");
  private static final HookFile NOTES =
      new HookFile("20-notes.js", "export default { name: 'notes' }");

  private static JdbcTemplate jdbc;

  private LocalHookSetStore sets;
  private ConversationStore conversations;

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
  }

  @BeforeEach
  void fresh() {
    jdbc.execute("TRUNCATE TABLE entries, turns, conversations, local_hook_sets CASCADE");
    sets = new LocalHookSetStore(jdbc, () -> T0);
    conversations = new ConversationStore(jdbc);
  }

  @Test
  void a_set_is_stored_once_under_its_hash_whatever_order_it_came_in() {
    String first = sets.remember(List.of(NOTES, GUARD));
    String again = sets.remember(List.of(GUARD, NOTES));

    assertEquals(first, again);
    assertEquals(HookFile.hashOf(List.of(GUARD, NOTES)), first);
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM local_hook_sets", Integer.class));
  }

  @Test
  void a_set_reads_back_in_file_name_order_with_its_text_exact() {
    String hash = sets.remember(List.of(NOTES, GUARD));

    assertEquals(Optional.of(List.of(GUARD, NOTES)), sets.find(hash));
    assertEquals(Optional.empty(), sets.find(UNKNOWN));
  }

  @Test
  void a_log_is_pinned_once_and_never_moved() {
    String log = conversations.open(Home.of("ledger"), Budget.of(5)).id();
    String first = sets.remember(List.of(GUARD));
    String second = sets.remember(List.of(NOTES));

    assertEquals(Optional.empty(), conversations.localHooksOf(log));
    assertTrue(conversations.pinLocalHooks(log, first));
    assertFalse(conversations.pinLocalHooks(log, second), "written once, like opening_block");
    assertEquals(Optional.of(first), conversations.localHooksOf(log));
  }

  @Test
  void a_log_names_only_a_set_the_table_holds() {
    String log = conversations.open(Home.of("ledger"), Budget.of(5)).id();

    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update("UPDATE conversations SET local_hooks = ? WHERE id = ?", UNKNOWN, log));

    assertTrue(
        refused.getMessage().contains("conversations_local_hooks_are_a_set_this_table_holds"),
        refused.getMessage());
  }

  @Test
  void a_hash_is_a_sha256_and_the_files_are_a_list() {
    DataIntegrityViolationException badHash =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "INSERT INTO local_hook_sets (hash, files, created_at)"
                        + " VALUES ('md5:1', '[]'::jsonb, now())"));
    DataIntegrityViolationException notAList =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "INSERT INTO local_hook_sets (hash, files, created_at)"
                        + " VALUES (?, '{}'::jsonb, now())",
                    "sha256:" + "a".repeat(64)));

    assertTrue(
        badHash.getMessage().contains("local_hook_sets_a_hash_is_a_sha256"), badHash.getMessage());
    assertTrue(
        notAList.getMessage().contains("local_hook_sets_files_are_a_list"), notAList.getMessage());
  }
}
