package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.archive.ArchiveException;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
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
class TriggerStoreTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private TriggerStore store;

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
    jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('enzo', 'h'), ('mara', 'h')");
    store = new TriggerStore(jdbc);
  }

  private static TriggerRecord trigger(String name, String event, boolean paused) {
    return new TriggerRecord(
        name, event, null, null, "bard", "summarise the night", null, null, 1, paused, "enzo");
  }

  @Test
  void a_trigger_listens_for_its_event_until_it_is_paused() {
    store.define(trigger("morning", "daily", false));
    store.define(trigger("other", "hourly", false));
    assertEquals(
        List.of("morning"), store.listening("daily").stream().map(TriggerRecord::name).toList());
    store.pause("morning", true, "enzo");
    assertTrue(store.listening("daily").isEmpty());
  }

  @Test
  void defining_the_same_name_again_replaces_every_field() {
    store.define(trigger("morning", "daily", false));
    store.define(
        new TriggerRecord(
            "morning", "hourly", "payments", null, "scribe", "new task", 40, 5, 3, false, "enzo"));
    TriggerRecord found = store.find("morning").orElseThrow();
    assertEquals("hourly", found.event());
    assertEquals("payments", found.project());
    assertEquals(40, found.maxModelCalls());
    assertEquals(3, found.queueCap());
  }

  @Test
  void the_target_is_the_conversation_when_there_is_one_and_the_trigger_otherwise() {
    assertEquals("trigger:morning", trigger("morning", "daily", false).target());
    assertEquals(
        "conversation:cnv_1",
        new TriggerRecord("m", "daily", null, "cnv_1", "bard", "t", null, null, 1, false, "enzo")
            .target());
  }

  @Test
  void forgetting_a_trigger_that_does_not_exist_is_not_found() {
    assertThrows(ArchiveException.class, () -> store.forget("nope", "enzo"));
  }

  @Test
  void another_account_cannot_redefine_a_trigger_it_did_not_define() {
    store.define(trigger("morning", "daily", false));
    CallerFault refused =
        assertThrows(
            CallerFault.class,
            () ->
                store.define(
                    new TriggerRecord(
                        "morning",
                        "hourly",
                        null,
                        null,
                        "scribe",
                        "take it over",
                        null,
                        null,
                        1,
                        false,
                        "mara")));
    assertTrue(refused.getMessage().contains("belongs to another account"), refused.getMessage());
    TriggerRecord kept = store.find("morning").orElseThrow();
    assertEquals("daily", kept.event());
    assertEquals("enzo", kept.definedBy());
  }

  @Test
  void another_accounts_trigger_cannot_be_paused_or_forgotten_and_looks_like_none() {
    store.define(trigger("morning", "daily", false));
    ArchiveException pause =
        assertThrows(ArchiveException.class, () -> store.pause("morning", true, "mara"));
    ArchiveException forget =
        assertThrows(ArchiveException.class, () -> store.forget("morning", "mara"));
    assertEquals(
        assertThrows(ArchiveException.class, () -> store.forget("nope", "mara"))
            .getMessage()
            .replace("nope", "morning"),
        forget.getMessage());
    assertEquals(forget.getMessage(), pause.getMessage());
    assertEquals(
        List.of("morning"), store.listening("daily").stream().map(TriggerRecord::name).toList());
  }
}
