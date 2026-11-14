package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.archive.PassageIndex.Source;
import java.util.List;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The retrieval poll must stay an index probe under the generic plan pgJDBC uses after five
 * executions, and must select the same sources in the same order as the ungated statement.
 *
 * <p>Both properties need real PostgreSQL: plan choice under {@code plan_cache_mode} and tuple
 * counts from {@code pg_stat_xact_user_tables}. One connection keeps the session setting and the
 * transaction-local counters on the same backend.
 */
@Tag("full-db")
@Testcontainers
class RetrievalPollPlanDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final String CURRENT = "m-current";

  /** The poll before the stale-generation gate: the oracle for parity and the read baseline. */
  private static final String UNGATED =
      """
      WITH due AS (
        (SELECT source_type,source_id,retry_at FROM retrieval_sources
          WHERE status IN ('pending','failed')
            AND (retry_at<=CURRENT_TIMESTAMP OR (last_error LIKE 'RepairInFlight:%' AND last_error<>?))
          ORDER BY retry_at,source_type,source_id LIMIT 8)
        UNION ALL
        (SELECT source_type,source_id,retry_at FROM retrieval_sources
          WHERE status='ready' AND (generation IS NULL OR generation<? OR generation>?)
            AND (retry_at<=CURRENT_TIMESTAMP OR (last_error LIKE 'RepairInFlight:%' AND last_error<>?))
          ORDER BY retry_at,source_type,source_id LIMIT 8)
        ORDER BY retry_at,source_type,source_id LIMIT 8
      )
      SELECT s.source_type,s.source_id,s.source_hash,CASE WHEN s.generation=? THEN s.next_position ELSE 0 END AS next_position,
             CASE WHEN s.source_type='entry' THEN e.content ELSE d.summary END AS text
      FROM due JOIN retrieval_sources s USING (source_type,source_id)
      LEFT JOIN entries e ON s.source_type='entry' AND e.conversation_id=s.conversation_id AND e.ordinal=s.ordinal
      LEFT JOIN digests d ON s.source_type='digest' AND d.id=s.source_id
      ORDER BY s.retry_at,s.source_type,s.source_id
      """;

  private static SingleConnectionDataSource source;
  private JdbcTemplate jdbc;
  private JdbcPassageRepository repository;

  @BeforeAll
  static void migrate() {
    source =
        new SingleConnectionDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    Flyway.configure().dataSource(source).load().migrate();
  }

  @AfterAll
  static void close() {
    source.destroy();
  }

  @BeforeEach
  void setup() {
    jdbc = new JdbcTemplate(source);
    jdbc.execute("RESET plan_cache_mode");
    jdbc.execute("TRUNCATE retrieval_sources CASCADE");
    repository =
        new JdbcPassageRepository(
            jdbc,
            new UnitOfWork() {
              @Override
              public <T> T inTransaction(Supplier<T> work) {
                throw new AssertionError("the poll must not open a transaction");
              }
            });
    // Thousands of current sources, as on a deployed installation in steady state.
    jdbc.update(
        "INSERT INTO retrieval_sources(source_type,source_id,source_hash,generation,status,retry_at)"
            + " SELECT 'digest','current-'||n,'hash',?,'ready',now()-interval '1 hour'"
            + " FROM generate_series(1,3000) n",
        CURRENT);
    jdbc.execute("ANALYZE retrieval_sources");
  }

  private void row(String id, String status, String generation, String retry, String error) {
    jdbc.update(
        "INSERT INTO retrieval_sources(source_type,source_id,source_hash,generation,status,retry_at,last_error,next_position)"
            + " VALUES('digest',?,'hash',?,?,now()+?::interval,?,3)",
        id,
        generation,
        status,
        retry,
        error);
  }

  private List<Source> ungated() {
    return jdbc.query(
        UNGATED,
        (r, n) ->
            new Source(
                r.getString(1),
                r.getString(2),
                r.getString(3),
                r.getString("text"),
                r.getInt("next_position")),
        "RepairInFlight:" + CURRENT,
        CURRENT,
        CURRENT,
        "RepairInFlight:" + CURRENT,
        CURRENT);
  }

  private void assertParity() {
    for (String mode : List.of("auto", "force_generic_plan", "force_custom_plan")) {
      jdbc.execute("SET plan_cache_mode=" + mode);
      assertEquals(ungated(), repository.pending(CURRENT), () -> "under plan_cache_mode=" + mode);
    }
    jdbc.execute("RESET plan_cache_mode");
  }

  /**
   * Rows of retrieval_sources one call visits, from this transaction's own counters: heap tuples
   * read by sequential scans plus index entries returned by each of the table's indexes.
   */
  private long reads(Runnable poll) {
    String sql =
        "SELECT pg_stat_get_xact_tuples_returned('retrieval_sources'::regclass)"
            + "+coalesce(sum(pg_stat_get_xact_tuples_returned(indexrelid)),0)"
            + " FROM pg_index WHERE indrelid='retrieval_sources'::regclass";
    jdbc.execute("BEGIN");
    try {
      long before = jdbc.queryForObject(sql, Long.class);
      poll.run();
      return jdbc.queryForObject(sql, Long.class) - before;
    } finally {
      jdbc.execute("ROLLBACK");
    }
  }

  @Test
  void anIdleGenericPlanPollProbesTheIndexInsteadOfEveryReadyRow() {
    jdbc.execute("SET plan_cache_mode=force_generic_plan");
    long before = reads(this::ungated);
    long after = reads(() -> assertTrue(repository.pending(CURRENT).isEmpty()));
    jdbc.execute("RESET plan_cache_mode");
    assertTrue(
        before >= 3000, () -> "the ungated baseline should walk every ready row, read " + before);
    assertTrue(after < 50, () -> "an idle poll must not walk the ready rows, read " + after);
  }

  @Test
  void theGateSelectsTheSameSourcesInTheSameOrder() {
    assertParity();
    row("pending-due", "pending", null, "-5 minutes", null);
    row("pending-later", "pending", null, "5 minutes", null);
    row("failed-due", "failed", CURRENT, "-4 minutes", "boom");
    row("in-flight-other", "pending", null, "5 minutes", "RepairInFlight:older");
    row("in-flight-current", "pending", null, "5 minutes", "RepairInFlight:" + CURRENT);
    assertParity();
    assertEquals(3, repository.pending(CURRENT).size());

    row("ready-null", "ready", null, "-3 minutes", null);
    assertParity();
    row("ready-older", "ready", "a-older", "-2 minutes", null);
    row("ready-newer", "ready", "z-newer", "-1 minutes", null);
    row("ready-older-later", "ready", "a-older", "5 minutes", null);
    row("ready-newer-in-flight", "ready", "z-newer", "5 minutes", "RepairInFlight:older");
    assertParity();
    var due = repository.pending(CURRENT).stream().map(Source::id).toList();
    assertEquals(
        List.of(
            "pending-due",
            "failed-due",
            "ready-null",
            "ready-older",
            "ready-newer",
            "in-flight-other",
            "ready-newer-in-flight"),
        due);
    assertEquals(0, repository.pending(CURRENT).get(2).next(), "a stale source restarts at zero");

    // A generation change makes every current row stale; the ready half then runs as before.
    jdbc.update("UPDATE retrieval_sources SET generation='a-previous' WHERE generation=?", CURRENT);
    assertParity();
    assertEquals(8, repository.pending(CURRENT).size());
  }
}
