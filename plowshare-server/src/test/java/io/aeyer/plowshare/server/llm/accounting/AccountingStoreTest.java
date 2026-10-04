package io.aeyer.plowshare.server.llm.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("full-db")
@Testcontainers
class AccountingStoreTest {
  @Test
  void an_enabled_idle_capture_records_its_start_without_inventing_usage() {
    try (var journal = journal(directory);
        var projector =
            new AccountingProjector(
                journal,
                store,
                new AccountingRecorder(journal, AccountingFixtures.CLOCK, 10),
                AccountingFixtures.CLOCK)) {
      assertTrue(projector.projectOnce());
      assertEquals(
          AccountingFixtures.NOW,
          jdbc.queryForObject(
                  "SELECT tracking_started_at FROM inference_accounting_journals WHERE journal_id=?",
                  java.time.OffsetDateTime.class,
                  journal.journalId())
              .toInstant());
      assertEquals(0, store.watermark(journal.journalId()));
      assertEquals(0, journal.health().pendingEvents());
      assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM inference_calls", Integer.class));
    }
  }

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static DriverManagerDataSource database;
  private JdbcTemplate jdbc;
  private AccountingStore store;
  @TempDir Path directory;

  @BeforeAll
  static void migrate() {
    database =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(database).load().migrate();
  }

  @BeforeEach
  void fresh() {
    jdbc = new JdbcTemplate(database);
    jdbc.execute(
        """
                TRUNCATE inference_accounting_events, inference_attempts, inference_calls,
                    inference_pricing_versions, inference_accounting_instances, inference_accounting_journals
                """);
    store = store(database);
  }

  @Test
  void a_measured_call_projects_once_with_decimal_cost_and_full_ancestry() {
    try (var journal = journal(directory)) {
      measured(journal);
      var batch = journal.readBatch(100);
      store.project(journal.journalId(), batch);
      store.project(journal.journalId(), batch);
      assertEquals(1, count("inference_calls"));
      assertEquals(1, count("inference_attempts"));
      assertEquals(4, count("inference_accounting_events"));
      assertEquals(4, store.watermark(journal.journalId()));
      assertEquals(
          100L, jdbc.queryForObject("SELECT input_tokens FROM inference_attempts", Long.class));
      assertEquals(
          20L, jdbc.queryForObject("SELECT output_tokens FROM inference_attempts", Long.class));
      assertEquals(
          0,
          new BigDecimal("0.000180")
              .compareTo(
                  jdbc.queryForObject(
                      "SELECT cost_amount FROM inference_attempts", BigDecimal.class)));
      assertEquals(
          "SUCCEEDED", jdbc.queryForObject("SELECT lifecycle FROM inference_calls", String.class));
      assertEquals(
          "ESTIMATED",
          jdbc.queryForObject("SELECT cost_kind FROM inference_attempts", String.class));
      assertEquals(
          AccountingFixtures.PRICE.version(),
          jdbc.queryForObject("SELECT price_version FROM inference_calls", String.class));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM inference_calls WHERE 'r-no-call' = ANY(ancestor_run_ids)",
              Integer.class));
      assertEquals(
          4,
          journal.health().pendingEvents()); // Committing alone does not acknowledge the journal.
      journal.acknowledge(4);
      assertEquals(0, journal.health().pendingEvents());
    }
  }

  @Test
  void token_counts_above_javascript_integer_precision_remain_exact_in_postgresql() {
    long tokens = 9_007_199_254_740_993L;
    var original = AccountingFixtures.metadata();
    var metadata =
        new AccountingEvent.CallCreated(
            original.attribution(),
            original.specifier(),
            original.pool(),
            original.wireModel(),
            original.modelFamily(),
            original.billingRoute(),
            io.aeyer.plowshare.server.llm.dispatch.Lane.EMBEDDING,
            original.price());
    var usage =
        new UsageObservation(
            tokens,
            null,
            tokens,
            null,
            null,
            null,
            UsageObservation.Source.PROVIDER,
            UsageObservation.CacheRelation.UNSPECIFIED,
            UsageObservation.VERSION,
            java.util.Map.of(),
            List.of());
    try (var journal = journal(directory)) {
      var call = new AccountingRecorder(journal, AccountingFixtures.CLOCK, 10).begin(metadata);
      call.startAttempt();
      call.finishAttempt(
          CallLifecycle.SUCCEEDED, usage, null, 200, AccountingEvent.FinishReason.STOP, null, 20);
      call.finish(CallLifecycle.SUCCEEDED);
      store.project(journal.journalId(), journal.readBatch(100));
      assertEquals(
          tokens, jdbc.queryForObject("SELECT input_tokens FROM inference_attempts", Long.class));
      assertNull(jdbc.queryForObject("SELECT output_tokens FROM inference_attempts", Long.class));
      assertEquals(
          "COMPLETE",
          jdbc.queryForObject("SELECT usage_coverage FROM inference_attempts", String.class));
      assertEquals(
          0,
          BigDecimal.valueOf(tokens)
              .movePointLeft(6)
              .compareTo(
                  jdbc.queryForObject(
                      "SELECT cost_amount FROM inference_attempts", BigDecimal.class)));
    }
  }

  @Test
  void success_after_an_unknown_failed_attempt_keeps_both_attempts_and_the_cost_gap() {
    try (var journal = journal(directory)) {
      var call =
          new AccountingRecorder(journal, AccountingFixtures.CLOCK, 10)
              .begin(AccountingFixtures.metadata());
      call.startAttempt();
      call.finishAttempt(
          CallLifecycle.FAILED,
          UsageObservation.UNKNOWN,
          null,
          503,
          AccountingEvent.FinishReason.UNKNOWN,
          null,
          10);
      call.startAttempt();
      call.finishAttempt(
          CallLifecycle.SUCCEEDED,
          TokenUsage.of(100, 20, 120).observation(),
          null,
          200,
          AccountingEvent.FinishReason.STOP,
          3L,
          20);
      call.finish(CallLifecycle.SUCCEEDED);
      store.project(journal.journalId(), journal.readBatch(100));
      assertEquals(
          "SUCCEEDED", jdbc.queryForObject("SELECT lifecycle FROM inference_calls", String.class));
      assertEquals(2, count("inference_attempts"));
      assertEquals(
          List.of("UNKNOWN", "COMPLETE"),
          jdbc.queryForList(
              "SELECT usage_coverage FROM inference_attempts ORDER BY attempt_number",
              String.class));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM inference_attempts WHERE cost_amount IS NULL", Integer.class));
      assertEquals(
          100L,
          jdbc.queryForObject("SELECT sum(input_tokens) FROM inference_attempts", Long.class));
    }
  }

  @Test
  void crash_after_database_commit_before_ack_replays_without_duplicate_consumption() {
    UUID identity;
    try (var journal = journal(directory)) {
      measured(journal);
      identity = journal.journalId();
      store.project(identity, journal.readBatch(100));
    }
    try (var journal = journal(directory)) {
      var recorder = new AccountingRecorder(journal, AccountingFixtures.CLOCK, 10);
      try (var projector =
          new AccountingProjector(journal, store, recorder, AccountingFixtures.CLOCK)) {
        assertTrue(projector.projectOnce());
        assertEquals(identity, journal.journalId());
        assertEquals(0, journal.health().pendingEvents());
        assertEquals(1, count("inference_attempts"));
        assertEquals(
            100L,
            jdbc.queryForObject("SELECT sum(input_tokens) FROM inference_attempts", Long.class));
      }
    }
  }

  @Test
  void uncertain_append_and_buffered_retry_with_the_same_event_id_project_only_once() {
    var fail = new AtomicBoolean();
    try (var journal =
        new AccountingJournal(
            directory,
            1024 * 1024,
            8192,
            Duration.ofSeconds(1),
            AccountingFixtures.MAPPER,
            channel -> {
              if (fail.getAndSet(false)) {
                throw new java.io.IOException("injected terminal failure");
              }
              channel.force(true);
            })) {
      var recorder = new AccountingRecorder(journal, AccountingFixtures.CLOCK, 10);
      var call = recorder.begin(AccountingFixtures.metadata());
      call.startAttempt();
      fail.set(true);
      assertFalse(
          call.finishAttempt(
              CallLifecycle.SUCCEEDED,
              TokenUsage.of(100, 20, 120).observation(),
              null,
              200,
              AccountingEvent.FinishReason.STOP,
              null,
              20));
      call.finish(CallLifecycle.SUCCEEDED);
      recorder.flushPending();
      store.project(journal.journalId(), journal.readBatch(100));
      assertEquals(5, store.watermark(journal.journalId()));
      assertEquals(4, count("inference_accounting_events"));
      assertEquals(1, count("inference_attempts"));
      assertEquals(
          100L,
          jdbc.queryForObject("SELECT sum(input_tokens) FROM inference_attempts", Long.class));
    }
  }

  @Test
  void a_database_outage_preserves_admitted_metadata_and_catches_up_after_restart() {
    var offline = new AtomicBoolean(true);
    var source = new FlappingDataSource(offline);
    AccountingStore unavailable = store(source);
    try (var journal = journal(directory)) {
      var recorder = measured(journal);
      try (var projector =
          new AccountingProjector(journal, unavailable, recorder, AccountingFixtures.CLOCK)) {
        assertFalse(projector.projectOnce());
        assertEquals(AccountingProjector.Problem.DATABASE_ERROR, projector.health().problem());
        assertEquals(4, journal.health().pendingEvents());
        assertEquals(0, count("inference_calls"));
        // A database outage does not refuse new durable admission while the journal has room.
        recorder.begin(AccountingFixtures.metadata()).finish(CallLifecycle.CANCELLED);
      }
    }
    offline.set(false);
    try (var journal = journal(directory)) {
      var recorder = new AccountingRecorder(journal, AccountingFixtures.CLOCK, 10);
      try (var projector =
          new AccountingProjector(journal, unavailable, recorder, AccountingFixtures.CLOCK)) {
        assertTrue(projector.projectOnce());
        assertEquals(0, journal.health().pendingEvents());
        assertEquals(2, count("inference_calls"));
        assertEquals(1, count("inference_attempts"));
        assertEquals(
            100L,
            jdbc.queryForObject("SELECT sum(input_tokens) FROM inference_attempts", Long.class));
      }
    }
  }

  @Test
  void restart_interrupts_an_open_attempt_with_unknown_consumption_and_does_not_create_another() {
    try (var journal = journal(directory)) {
      var recorder = new AccountingRecorder(journal, AccountingFixtures.CLOCK, 10);
      recorder.begin(AccountingFixtures.metadata()).startAttempt();
    }
    try (var journal = journal(directory)) {
      var recorder = new AccountingRecorder(journal, AccountingFixtures.CLOCK, 10);
      try (var projector =
          new AccountingProjector(journal, store, recorder, AccountingFixtures.CLOCK)) {
        assertTrue(projector.projectOnce());
        assertTrue(projector.projectOnce());
        assertEquals(1, count("inference_attempts"));
        assertEquals(
            "INTERRUPTED",
            jdbc.queryForObject("SELECT lifecycle FROM inference_calls", String.class));
        assertEquals(
            "INTERRUPTED",
            jdbc.queryForObject("SELECT outcome FROM inference_attempts", String.class));
        assertEquals(
            "UNKNOWN",
            jdbc.queryForObject("SELECT usage_coverage FROM inference_attempts", String.class));
        assertNull(jdbc.queryForObject("SELECT input_tokens FROM inference_attempts", Long.class));
        assertNull(
            jdbc.queryForObject("SELECT cost_amount FROM inference_attempts", BigDecimal.class));
      }
    }
  }

  @Test
  void recovery_does_not_interrupt_another_live_servers_journal() {
    try (var otherJournal = journal(directory.resolve("other"))) {
      var other = new AccountingRecorder(otherJournal, AccountingFixtures.CLOCK, 10);
      UUID otherCall = other.begin(AccountingFixtures.metadata()).id();
      store.project(otherJournal.journalId(), otherJournal.readBatch(10));
      try (var first = journal(directory.resolve("first"))) {
        new AccountingRecorder(first, AccountingFixtures.CLOCK, 10)
            .begin(AccountingFixtures.metadata());
        store.project(first.journalId(), first.readBatch(10));
      }
      try (var restarted = journal(directory.resolve("first"))) {
        var recorder = new AccountingRecorder(restarted, AccountingFixtures.CLOCK, 10);
        try (var projector =
            new AccountingProjector(restarted, store, recorder, AccountingFixtures.CLOCK)) {
          assertTrue(projector.projectOnce());
          assertEquals(
              "QUEUED",
              jdbc.queryForObject(
                  "SELECT lifecycle FROM inference_calls WHERE call_id = ?",
                  String.class,
                  otherCall));
          assertEquals(
              1,
              jdbc.queryForObject(
                  "SELECT count(*) FROM inference_calls WHERE lifecycle = 'NOT_DISPATCHED'",
                  Integer.class));
        }
      }
    }
  }

  @Test
  void sequence_and_payload_conflicts_roll_back_the_entire_projection_batch() {
    var created = AccountingFixtures.created();
    var gap =
        new AccountingEvent(
            UUID.randomUUID(),
            created.instanceId(),
            created.callId(),
            3,
            created.at(),
            new AccountingEvent.AttemptStarted(UUID.randomUUID(), 1));
    UUID journal = UUID.randomUUID();
    assertThrows(
        AccountingStore.ProjectionConflictException.class,
        () ->
            store.project(
                journal,
                List.of(
                    new AccountingJournal.Entry(1, created), new AccountingJournal.Entry(2, gap))));
    assertEquals(0, count("inference_calls"));
    assertEquals(0, count("inference_pricing_versions"));
    assertEquals(0, store.watermark(journal));
    store.project(journal, List.of(new AccountingJournal.Entry(1, created)));
    var altered =
        new AccountingEvent(
            created.eventId(),
            created.instanceId(),
            created.callId(),
            1,
            created.at().plusSeconds(1),
            created.payload());
    assertThrows(
        AccountingStore.ProjectionConflictException.class,
        () -> store.project(journal, List.of(new AccountingJournal.Entry(1, altered))));
    assertEquals(1, count("inference_calls"));
    assertEquals(1, store.watermark(journal));
  }

  @Test
  void a_transcript_transaction_rollback_cannot_undo_committed_accounting() {
    try (var journal = journal(directory)) {
      measured(journal);
      var outer = new TransactionTemplate(new DataSourceTransactionManager(database));
      assertThrows(
          IllegalStateException.class,
          () ->
              outer.executeWithoutResult(
                  status -> {
                    store.project(journal.journalId(), journal.readBatch(100));
                    throw new IllegalStateException("fixture transcript rollback");
                  }));
      assertEquals(1, count("inference_calls"));
      assertEquals(1, count("inference_attempts"));
    }
  }

  @Test
  void source_row_cleanup_keeps_identity_snapshots_and_the_schema_refuses_negative_quantities() {
    jdbc.update("INSERT INTO projects(name) VALUES (?)", "accounting-retention-fixture");
    Long project =
        jdbc.queryForObject(
            "SELECT id FROM projects WHERE name = ?", Long.class, "accounting-retention-fixture");
    var original = AccountingFixtures.metadata();
    var owner =
        UsageAttribution.project(
            "alice", project.toString(), UsageAttribution.Operation.AGENT_CHAT);
    var metadata =
        new AccountingEvent.CallCreated(
            owner,
            original.specifier(),
            original.pool(),
            original.wireModel(),
            original.modelFamily(),
            original.billingRoute(),
            original.lane(),
            original.price());
    try (var journal = journal(directory)) {
      var recorder = new AccountingRecorder(journal, AccountingFixtures.CLOCK, 10);
      var call = recorder.begin(metadata);
      call.startAttempt();
      call.finishAttempt(
          CallLifecycle.SUCCEEDED,
          TokenUsage.of(100, 20, 120).observation(),
          null,
          200,
          AccountingEvent.FinishReason.STOP,
          null,
          20);
      call.finish(CallLifecycle.SUCCEEDED);
      store.project(journal.journalId(), journal.readBatch(100));
      jdbc.update("DELETE FROM projects WHERE id = ?", project);
      assertEquals(
          project.toString(),
          jdbc.queryForObject("SELECT project_id FROM inference_calls", String.class));
      assertEquals(
          "alice", jdbc.queryForObject("SELECT account_handle FROM inference_calls", String.class));
      assertThrows(
          DataIntegrityViolationException.class,
          () -> jdbc.update("UPDATE inference_attempts SET input_tokens = -1"));
      assertEquals(
          100L, jdbc.queryForObject("SELECT input_tokens FROM inference_attempts", Long.class));
    }
  }

  @Test
  void a_database_checkpoint_behind_reclaimed_journal_history_stops_new_admission() {
    try (var journal = journal(directory)) {
      var recorder = measured(journal);
      try (var projector =
          new AccountingProjector(journal, store, recorder, AccountingFixtures.CLOCK)) {
        assertTrue(projector.projectOnce());
        fresh(); // Simulate restoration of a database that has lost acknowledged history.
        assertFalse(projector.projectOnce());
        assertEquals(AccountingProjector.Problem.PROJECTION_CONFLICT, projector.health().problem());
        assertThrows(
            AccountingUnavailableException.class,
            () -> recorder.begin(AccountingFixtures.metadata()));
      }
    }
  }

  @Test
  void replay_accepts_old_event_payloads_without_new_nullable_timing_fields() {
    try (var journal = journal(directory)) {
      measured(journal);
      var batch = journal.readBatch(100);
      store.project(journal.journalId(), batch);
      jdbc.update(
          """
                    UPDATE inference_accounting_events SET event_data = jsonb_set(event_data, '{payload}',
                        (event_data->'payload') - 'startAccountingMillis' - 'queueMillis' - 'admissionAccountingMillis')
                    """);
      store.project(journal.journalId(), batch);
      assertEquals(1, count("inference_attempts"));
      assertEquals(4, store.watermark(journal.journalId()));
    }
  }

  @Test
  void postgres_timestamp_precision_does_not_reverse_events_from_a_submicrosecond_clock() {
    var clock =
        java.time.Clock.fixed(AccountingFixtures.NOW.plusNanos(777), java.time.ZoneOffset.UTC);
    try (var journal = journal(directory)) {
      var call = new AccountingRecorder(journal, clock, 10).begin(AccountingFixtures.metadata());
      call.startAttempt();
      call.finishAttempt(
          CallLifecycle.SUCCEEDED,
          TokenUsage.of(100, 20, 120).observation(),
          null,
          200,
          AccountingEvent.FinishReason.STOP,
          null,
          1);
      call.finish(CallLifecycle.SUCCEEDED);
      store.project(journal.journalId(), journal.readBatch(100));
      assertEquals(
          "SUCCEEDED", jdbc.queryForObject("SELECT lifecycle FROM inference_calls", String.class));
    }
  }

  private AccountingRecorder measured(AccountingJournal journal) {
    var recorder = new AccountingRecorder(journal, AccountingFixtures.CLOCK, 10);
    var call = recorder.begin(AccountingFixtures.metadata());
    call.startAttempt();
    call.finishAttempt(
        CallLifecycle.SUCCEEDED,
        TokenUsage.of(100, 20, 120).observation(),
        "req_fixture",
        200,
        AccountingEvent.FinishReason.STOP,
        3L,
        20);
    call.finish(CallLifecycle.SUCCEEDED);
    return recorder;
  }

  private static AccountingJournal journal(Path path) {
    return new AccountingJournal(
        path, 1024 * 1024, 8192, Duration.ofSeconds(1), AccountingFixtures.MAPPER);
  }

  private static AccountingStore store(javax.sql.DataSource source) {
    return new AccountingStore(
        new JdbcTemplate(source),
        new DataSourceTransactionManager(source),
        AccountingFixtures.MAPPER,
        AccountingFixtures.CLOCK);
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  private static final class FlappingDataSource extends AbstractDataSource {
    private final AtomicBoolean offline;

    FlappingDataSource(AtomicBoolean offline) {
      this.offline = offline;
    }

    @Override
    public Connection getConnection() throws SQLException {
      if (offline.get()) {
        throw new SQLException("fixture database outage");
      }
      return database.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return getConnection();
    }
  }
}
