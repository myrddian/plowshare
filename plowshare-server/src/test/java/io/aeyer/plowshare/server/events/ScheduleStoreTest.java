package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.archive.ArchiveException;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ScheduleStoreTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private ScheduleStore store;
  private static final Instant NOW = Instant.parse("2026-09-13T08:30:00Z");
  private static final CronSchedule NINE = CronSchedule.parse("0 0 9 * * *", "UTC");

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
    jdbc.update(
        "INSERT INTO admins (handle, password_hash, server_admin) VALUES ('enzo', 'h', TRUE), ('mara', 'h', TRUE)");
    store = new ScheduleStore(jdbc);
  }

  @Test
  void a_defined_schedule_is_due_at_its_next_fire_time_after_now() {
    ScheduleRecord defined = store.define("nine", NINE, "daily", "enzo", NOW);
    assertEquals(Instant.parse("2026-09-13T09:00:00Z"), defined.nextFireAt());
    assertTrue(store.due(Instant.parse("2026-09-13T08:59:59Z")).isEmpty());
    assertEquals(1, store.due(Instant.parse("2026-09-13T09:00:00Z")).size());
  }

  @Test
  void defining_the_same_name_again_replaces_it() {
    store.define("nine", NINE, "daily", "enzo", NOW);
    store.define("nine", CronSchedule.parse("0 0 10 * * *", "UTC"), "later", "enzo", NOW);
    assertEquals(1, store.list().size());
    assertEquals("later", store.find("nine").orElseThrow().emits());
  }

  @Test
  void a_tick_is_claimed_by_exactly_one_of_two_claimants() {
    store.define("nine", NINE, "daily", "enzo", NOW);
    Instant due = Instant.parse("2026-09-13T09:00:00Z");
    Instant following = Instant.parse("2026-09-14T09:00:00Z");
    assertTrue(store.claim("nine", due, following));
    assertFalse(store.claim("nine", due, following));
  }

  @Test
  void a_paused_schedule_is_never_due() {
    store.define("nine", NINE, "daily", "enzo", NOW);
    store.pause("nine", true, "enzo");
    assertTrue(store.due(Instant.parse("2026-09-20T00:00:00Z")).isEmpty());
  }

  @Test
  void rolling_forward_moves_a_past_fire_time_to_the_future_without_a_claim() {
    store.define("nine", NINE, "daily", "enzo", NOW);
    Instant later = Instant.parse("2026-09-15T12:00:00Z");
    int moved =
        store.rollForward(later, s -> CronSchedule.parse(s.cron(), s.zone()).nextAfter(later));
    assertEquals(1, moved);
    assertEquals(
        Instant.parse("2026-09-16T09:00:00Z"), store.find("nine").orElseThrow().nextFireAt());
  }

  @Test
  void forgetting_or_pausing_a_schedule_that_does_not_exist_is_not_found() {
    assertThrows(ArchiveException.class, () -> store.forget("nope", "enzo"));
    assertThrows(ArchiveException.class, () -> store.pause("nope", true, "enzo"));
  }

  @Test
  void another_account_cannot_redefine_a_schedule_it_did_not_define() {
    store.define("nine", NINE, "daily", "enzo", NOW);
    CallerFault refused =
        assertThrows(
            CallerFault.class,
            () ->
                store.define(
                    "nine", CronSchedule.parse("0 0 10 * * *", "UTC"), "hijacked", "mara", NOW));
    assertTrue(refused.getMessage().contains("belongs to another account"), refused.getMessage());
    ScheduleRecord kept = store.find("nine").orElseThrow();
    assertEquals("daily", kept.emits());
    assertEquals("enzo", kept.definedBy());
  }

  @Test
  void another_accounts_schedule_cannot_be_paused_or_forgotten_and_looks_like_none() {
    store.define("nine", NINE, "daily", "enzo", NOW);
    ArchiveException pause =
        assertThrows(ArchiveException.class, () -> store.pause("nine", true, "mara"));
    ArchiveException forget =
        assertThrows(ArchiveException.class, () -> store.forget("nine", "mara"));
    assertEquals(
        assertThrows(ArchiveException.class, () -> store.forget("nope", "mara"))
            .getMessage()
            .replace("nope", "nine"),
        forget.getMessage());
    assertEquals(forget.getMessage(), pause.getMessage());
    assertFalse(store.find("nine").orElseThrow().paused());
  }

  @Test
  void an_emitters_disabled_or_demoted_owner_cannot_supply_a_tick() {
    store.define("nine", NINE, "daily", "enzo", NOW);
    Instant due = Instant.parse("2026-09-13T09:00:00Z");
    Instant following = Instant.parse("2026-09-14T09:00:00Z");
    jdbc.update("UPDATE admins SET server_admin=FALSE WHERE handle='enzo'");
    assertTrue(store.due(due).isEmpty());
    org.junit.jupiter.api.Assertions.assertFalse(store.claim("nine", due, following));
    jdbc.update("UPDATE admins SET server_admin=TRUE,enabled=FALSE WHERE handle='enzo'");
    assertTrue(store.due(due).isEmpty());
    org.junit.jupiter.api.Assertions.assertFalse(store.claim("nine", due, following));
    jdbc.update("UPDATE admins SET enabled=TRUE WHERE handle='enzo'");
    assertEquals(1, store.due(due).size());
    assertTrue(store.claim("nine", due, following));
  }
}
