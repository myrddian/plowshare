package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
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
class FiringStoreTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private FiringStore store;
  private static final Instant T0 = Instant.parse("2026-09-13T09:00:00Z");
  private static final TriggerRecord MORNING =
      new TriggerRecord(
          "morning", "daily", null, null, "bard", "summarise", null, null, 1, false, "enzo");

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
    jdbc.execute("TRUNCATE TABLE user_inbox, firings, triggers, schedules, admins CASCADE");
    jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('enzo', 'h')");
    store = new JdbcFiringStore(jdbc);
  }

  @Test
  void an_event_nobody_listens_for_is_still_recorded() {
    FiringRecord f =
        store.arrive("daily", new EventPayload.Empty(), null, null, null, T0).orElseThrow();
    assertEquals("unmatched", f.status());
    assertNull(f.trigger());
  }

  @Test
  void unknown_retained_data_blocks_recovery_and_claims_without_changing_rows() {
    jdbc.update(
        "INSERT INTO firings(id,event,data,trigger,target,status,job_id,started_at,arrived_at) VALUES(?, ?, ?::jsonb, ?, ?, 'started', ?, ?, ?)",
        "fir_unknown",
        "manual",
        "{\"legacy_custom\":{\"paid\":true}}",
        "morning",
        "trigger:morning",
        "paid_job",
        java.sql.Timestamp.from(T0),
        java.sql.Timestamp.from(T0));
    jdbc.update(
        "INSERT INTO firings(id,event,data,status,arrived_at) VALUES(?, ?, ?::jsonb, 'unmatched', ?)",
        "fir_terminal",
        "manual",
        "{\"legacy_terminal\":1}",
        java.sql.Timestamp.from(T0));
    var before = jdbc.queryForList("SELECT * FROM firings ORDER BY id");
    var inventory = store.preflight();
    assertEquals(2, inventory.scanned());
    assertEquals(2, inventory.invalid());
    assertThrows(IllegalStateException.class, inventory::requireCompatible);
    assertThrows(
        IllegalStateException.class, () -> store.abandonUnfinished("restart", T0.plusSeconds(1)));
    assertThrows(IllegalArgumentException.class, () -> store.claimStart("fir_unknown", T0));
    assertEquals(before, jdbc.queryForList("SELECT * FROM firings ORDER BY id"));
  }

  @Test
  void supported_historical_jsonb_is_decoded_without_rewriting_stored_bytes() {
    String id =
        store
            .arrive("manual", new EventPayload.Text("Retained prose"), null, null, null, T0)
            .orElseThrow()
            .id();
    var before = jdbc.queryForObject("SELECT data::text FROM firings WHERE id=?", String.class, id);
    assertEquals(new EventPayload.Text("Retained prose"), store.find(id).orElseThrow().data());
    assertEquals(0, store.preflight().invalid());
    assertEquals(
        before, jdbc.queryForObject("SELECT data::text FROM firings WHERE id=?", String.class, id));
  }

  @Test
  void simultaneous_claims_are_still_fenced_by_postgres() throws Exception {
    var a = store.arrive("daily", new EventPayload.Empty(), null, null, MORNING, T0).orElseThrow();
    var b =
        store
            .arrive("daily", new EventPayload.Empty(), null, null, MORNING, T0.plusSeconds(1))
            .orElseThrow();
    var ready = new java.util.concurrent.CyclicBarrier(2);
    try (var threads = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first =
          threads.submit(
              () -> {
                ready.await();
                return store.claimStart(a.id(), T0);
              });
      var second =
          threads.submit(
              () -> {
                ready.await();
                return store.claimStart(b.id(), T0);
              });
      int won =
          (first.get(10, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0)
              + (second.get(10, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0);
      assertEquals(1, won);
      assertEquals(1, store.list("morning", "started", 0, 10).size());
      assertEquals(1, store.list("morning", "queued", 0, 10).size());
    }
  }

  @Test
  void the_same_tick_for_the_same_trigger_arrives_once() {
    assertTrue(
        store.arrive("daily", new EventPayload.Empty(), "nine", T0, MORNING, T0).isPresent());
    assertTrue(store.arrive("daily", new EventPayload.Empty(), "nine", T0, MORNING, T0).isEmpty());
  }

  @Test
  void a_start_is_claimed_once_and_the_target_is_busy_until_finished() {
    FiringRecord f =
        store.arrive("daily", new EventPayload.Empty(), null, null, MORNING, T0).orElseThrow();
    assertTrue(store.claimStart(f.id(), T0));
    assertFalse(store.claimStart(f.id(), T0));
    store.startedAs(f.id(), "job_1");
    assertTrue(store.busy("trigger:morning"));
    store.finish(f.id(), T0.plusSeconds(60));
    assertFalse(store.busy("trigger:morning"));
  }

  @Test
  void past_the_cap_the_newest_waits_and_the_older_are_superseded_by_it() {
    FiringRecord a =
        store.arrive("daily", new EventPayload.Text("n=1"), null, null, MORNING, T0).orElseThrow();
    FiringRecord b =
        store
            .arrive("daily", new EventPayload.Text("n=2"), null, null, MORNING, T0.plusSeconds(1))
            .orElseThrow();
    assertEquals(1, store.supersedeBeyond("morning", 1, b.id()));
    assertEquals(b.id(), store.oldestWaiting("trigger:morning").orElseThrow().id());
    FiringRecord older = store.list("morning", "superseded", 0, 10).get(0);
    assertEquals(a.id(), older.id());
    assertEquals(b.id(), older.supersededBy());
  }

  @Test
  void a_second_firing_cannot_be_claimed_while_the_first_is_still_running_on_the_same_target() {
    FiringRecord a =
        store.arrive("daily", new EventPayload.Text("n=1"), null, null, MORNING, T0).orElseThrow();
    FiringRecord b =
        store
            .arrive("daily", new EventPayload.Text("n=2"), null, null, MORNING, T0.plusSeconds(1))
            .orElseThrow();
    assertTrue(store.claimStart(a.id(), T0));
    assertFalse(store.claimStart(b.id(), T0));
    assertEquals("queued", store.find(b.id()).orElseThrow().status());
  }

  @Test
  void releasing_a_started_firing_puts_it_back_to_queued() {
    FiringRecord f =
        store.arrive("daily", new EventPayload.Empty(), null, null, MORNING, T0).orElseThrow();
    store.claimStart(f.id(), T0);
    store.startedAs(f.id(), "job_1");
    store.release(f.id());
    FiringRecord after = store.find(f.id()).orElseThrow();
    assertEquals("queued", after.status());
    assertNull(after.jobId());
    assertNull(after.startedAt());
    assertFalse(store.busy("trigger:morning"));
  }

  @Test
  void a_restart_abandons_what_was_running_so_the_target_is_free_again() {
    FiringRecord f =
        store.arrive("daily", new EventPayload.Empty(), null, null, MORNING, T0).orElseThrow();
    store.claimStart(f.id(), T0);
    store.startedAs(f.id(), "job_1");
    assertEquals(1, store.abandonUnfinished("the server restarted during this run", T0));
    assertFalse(store.busy("trigger:morning"));
  }

  @Test
  void forgetting_a_trigger_refuses_what_it_had_waiting() {
    store.arrive("daily", new EventPayload.Empty(), null, null, MORNING, T0);
    assertEquals(1, store.refuseWaiting("morning", "trigger forgotten"));
    assertTrue(store.oldestWaiting("trigger:morning").isEmpty());
    assertEquals(List.of(), store.targetsWaiting());
  }

  @Test
  void a_colliding_firing_id_is_retried_until_it_succeeds() {
    // Pre-insert a firing with a specific ID to cause collision
    String collidingId = "fir_collision_test";
    jdbc.update(
        "INSERT INTO firings (id, event, data, status, arrived_at) VALUES (?, ?, ?::jsonb, ?, ?)",
        collidingId,
        "test_event",
        "{}",
        "unmatched",
        java.sql.Timestamp.from(T0));

    // Create a supplier that yields the colliding ID first, then fresh IDs
    List<String> ids = List.of(collidingId, "fir_retry_1", "fir_retry_2");
    Supplier<String> idSupplier =
        new Supplier<String>() {
          private int index = 0;

          @Override
          public String get() {
            return ids.get(index++);
          }
        };

    // Arrive with the custom ID supplier; should retry and succeed
    FiringRecord f =
        ((JdbcFiringStore) store)
            .arrive("daily", new EventPayload.Empty(), null, null, null, T0, idSupplier)
            .orElseThrow();
    assertEquals("fir_retry_1", f.id());
    assertEquals("unmatched", f.status());
  }
}
