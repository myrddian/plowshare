package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    store = new FiringStore(jdbc);
  }

  @Test
  void an_event_nobody_listens_for_is_still_recorded() {
    FiringRecord f = store.arrive("daily", "{}", null, null, null, T0).orElseThrow();
    assertEquals("unmatched", f.status());
    assertNull(f.trigger());
  }

  @Test
  void the_same_tick_for_the_same_trigger_arrives_once() {
    assertTrue(store.arrive("daily", "{}", "nine", T0, MORNING, T0).isPresent());
    assertTrue(store.arrive("daily", "{}", "nine", T0, MORNING, T0).isEmpty());
  }

  @Test
  void a_start_is_claimed_once_and_the_target_is_busy_until_finished() {
    FiringRecord f = store.arrive("daily", "{}", null, null, MORNING, T0).orElseThrow();
    assertTrue(store.claimStart(f.id(), T0));
    assertFalse(store.claimStart(f.id(), T0));
    store.startedAs(f.id(), "job_1");
    assertTrue(store.busy("trigger:morning"));
    store.finish(f.id(), T0.plusSeconds(60));
    assertFalse(store.busy("trigger:morning"));
  }

  @Test
  void past_the_cap_the_newest_waits_and_the_older_are_superseded_by_it() {
    FiringRecord a = store.arrive("daily", "{\"n\":1}", null, null, MORNING, T0).orElseThrow();
    FiringRecord b =
        store.arrive("daily", "{\"n\":2}", null, null, MORNING, T0.plusSeconds(1)).orElseThrow();
    assertEquals(1, store.supersedeBeyond("morning", 1, b.id()));
    assertEquals(b.id(), store.oldestWaiting("trigger:morning").orElseThrow().id());
    FiringRecord older = store.list("morning", "superseded", 0, 10).get(0);
    assertEquals(a.id(), older.id());
    assertEquals(b.id(), older.supersededBy());
  }

  @Test
  void a_second_firing_cannot_be_claimed_while_the_first_is_still_running_on_the_same_target() {
    FiringRecord a = store.arrive("daily", "{\"n\":1}", null, null, MORNING, T0).orElseThrow();
    FiringRecord b =
        store.arrive("daily", "{\"n\":2}", null, null, MORNING, T0.plusSeconds(1)).orElseThrow();
    assertTrue(store.claimStart(a.id(), T0));
    assertFalse(store.claimStart(b.id(), T0));
    assertEquals("queued", store.find(b.id()).orElseThrow().status());
  }

  @Test
  void releasing_a_started_firing_puts_it_back_to_queued() {
    FiringRecord f = store.arrive("daily", "{}", null, null, MORNING, T0).orElseThrow();
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
    FiringRecord f = store.arrive("daily", "{}", null, null, MORNING, T0).orElseThrow();
    store.claimStart(f.id(), T0);
    store.startedAs(f.id(), "job_1");
    assertEquals(1, store.abandonUnfinished("the server restarted during this run", T0));
    assertFalse(store.busy("trigger:morning"));
  }

  @Test
  void forgetting_a_trigger_refuses_what_it_had_waiting() {
    store.arrive("daily", "{}", null, null, MORNING, T0);
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
    FiringRecord f = store.arrive("daily", "{}", null, null, null, T0, idSupplier).orElseThrow();
    assertEquals("fir_retry_1", f.id());
    assertEquals("unmatched", f.status());
  }
}
