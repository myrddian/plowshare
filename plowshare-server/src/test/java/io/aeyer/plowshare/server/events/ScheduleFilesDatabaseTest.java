package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.time.Instant;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Actual SQL constraints, row mapping, claim authorization and atomic projection rollback. */
@Tag("full-db")
@Testcontainers
class ScheduleFilesDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private static UnitOfWork work;
  private ScheduleStore schedules;
  private TriggerStore triggers;
  private FiringStore firings;
  private ScheduleDefinitionStore store;
  private ScheduleDefinitionStore.Source source;
  private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");

  @BeforeAll
  static void migrate() {
    var data =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(data).load().migrate();
    jdbc = new JdbcTemplate(data);
    var template = new TransactionTemplate(new DataSourceTransactionManager(data));
    work =
        new UnitOfWork() {
          public <T> T inTransaction(Supplier<T> action) {
            return template.execute(status -> action.get());
          }
        };
  }

  @BeforeEach
  void fresh() {
    jdbc.execute("TRUNCATE user_inbox,firings,triggers,schedules,projects,admins CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin) VALUES ('owner','fixture',FALSE),('other','fixture',TRUE)");
    Long project =
        jdbc.queryForObject(
            "INSERT INTO projects(name) VALUES ('project') RETURNING id", Long.class);
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES (?,'owner','MANAGER')",
        project);
    schedules = new JdbcScheduleStore(jdbc);
    triggers = new JdbcTriggerStore(jdbc);
    firings = new JdbcFiringStore(jdbc);
    store = new JdbcScheduleDefinitionStore(jdbc, work, schedules, triggers, firings);
    source = store.register("owner", project, "server");
  }

  @Test
  void projectOwnerCanFireAndRevocationStopsClaims() {
    var file =
        store.apply(
            source, "daily", ScheduleDefinitionCodecTest.definition("agent", null, null), NOW);
    assertEquals(file, store.managed(file.internalName(), "owner").orElseThrow());
    assertEquals(1, schedules.due(NOW.plusSeconds(86400)).size());
    var due = schedules.find(file.internalName()).orElseThrow();
    jdbc.update("UPDATE project_members SET role='VIEWER' WHERE project_id=?", source.projectId());
    assertTrue(schedules.due(NOW.plusSeconds(86400)).isEmpty());
    assertFalse(
        schedules.claim(
            file.internalName(), due.nextFireAt(), due.nextFireAt().plusSeconds(86400)));
    assertThrows(
        RuntimeException.class, () -> store.register("other", source.projectId(), "server"));
  }

  @Test
  void noUnchangedScanResetsTimingAndRejectedFilesRecover() {
    var definition = ScheduleDefinitionCodecTest.definition("agent", null, null);
    var file = store.apply(source, "daily", definition, NOW);
    var first = schedules.find(file.internalName()).orElseThrow().nextFireAt();
    store.apply(source, "daily", definition, NOW.plusSeconds(86400));
    assertEquals(first, schedules.find(file.internalName()).orElseThrow().nextFireAt());
    store.reject(source, "daily", "invalid edit");
    assertTrue(schedules.due(NOW.plusSeconds(86400)).isEmpty());
    store.apply(source, "daily", definition, NOW.plusSeconds(86400));
    assertTrue(schedules.find(file.internalName()).orElseThrow().nextFireAt().isAfter(first));
    store.remove(source, "daily");
    assertTrue(schedules.find(file.internalName()).isEmpty());
    assertTrue(triggers.find(file.internalName()).isEmpty());
  }

  @Test
  void aFailedTriggerProjectionRollsBackItsScheduleAndFile() {
    TriggerStore broken = mock(TriggerStore.class);
    when(broken.find(anyString())).thenReturn(java.util.Optional.empty());
    when(broken.define(any())).thenThrow(new IllegalStateException("fixture failure"));
    var atomic = new JdbcScheduleDefinitionStore(jdbc, work, schedules, broken, firings);
    assertThrows(
        IllegalStateException.class,
        () ->
            atomic.apply(
                source, "daily", ScheduleDefinitionCodecTest.definition("agent", null, null), NOW));
    assertTrue(schedules.list().isEmpty());
    assertTrue(store.files(source).isEmpty());
  }

  @Test
  void deletedSourcesCannotFallThroughToGlobalAdminAuthority() {
    jdbc.update("UPDATE admins SET server_admin=TRUE WHERE handle='owner'");
    var file =
        store.apply(
            source, "daily", ScheduleDefinitionCodecTest.definition("agent", null, null), NOW);
    jdbc.update("DELETE FROM projects WHERE id=?", source.projectId());
    assertTrue(schedules.due(NOW.plusSeconds(86400)).isEmpty());
    assertTrue(store.requiresDefinition(file.internalName(), "owner"));
  }

  @Test
  void theSharedGlobalFolderCanHaveOnlyOneRegisteredOwner() {
    jdbc.update("UPDATE admins SET server_admin=TRUE");
    var global = store.register("owner", null, "server");
    assertEquals(global, store.register("owner", null, "server"));
    assertThrows(RuntimeException.class, () -> store.register("other", null, "server"));
  }

  @Test
  void operationalPauseSurvivesReconciliationAndRepositoryRestart() {
    var definition = ScheduleDefinitionCodecTest.definition("agent", null, null);
    var file = store.apply(source, "daily", definition, NOW);
    var trigger = triggers.find(file.internalName()).orElseThrow();
    var running =
        firings
            .arrive(file.internalName(), new EventPayload.Text("running"), null, null, trigger, NOW)
            .orElseThrow();
    assertTrue(firings.claimStart(running.id(), NOW));
    var waiting =
        firings
            .arrive(file.internalName(), new EventPayload.Text("waiting"), null, null, trigger, NOW)
            .orElseThrow();
    store.pause(source, "daily", definition, true, NOW);
    assertEquals("refused", firings.find(waiting.id()).orElseThrow().status());
    assertEquals("started", firings.find(running.id()).orElseThrow().status());
    assertTrue(store.files(source).getFirst().definition().paused());
    assertTrue(schedules.find(file.internalName()).orElseThrow().paused());
    assertTrue(triggers.find(file.internalName()).orElseThrow().paused());
    store = new JdbcScheduleDefinitionStore(jdbc, work, schedules, triggers, firings);
    store.apply(source, "daily", definition, NOW.plusSeconds(86400));
    assertTrue(store.files(source).getFirst().definition().paused());
    var effective = store.files(source).getFirst().definition();
    store.pause(source, "daily", effective, false, NOW.plusSeconds(86400));
    var next = schedules.find(file.internalName()).orElseThrow().nextFireAt();
    assertTrue(next.isAfter(NOW.plusSeconds(86400)));
    store.pause(source, "daily", definition, false, NOW.plusSeconds(172800));
    assertEquals(next, schedules.find(file.internalName()).orElseThrow().nextFireAt());
    store.reject(source, "daily", "temporarily unavailable");
    store.apply(source, "daily", definition, NOW.plusSeconds(172800));
    assertFalse(store.files(source).getFirst().definition().paused());
    assertFalse(triggers.find(file.internalName()).orElseThrow().paused());
    assertFalse(schedules.find(file.internalName()).orElseThrow().paused());
  }

  @Test
  void changedSourceClearsAnOverrideAndStaleControlIsRefused() {
    var definition = ScheduleDefinitionCodecTest.definition("agent", null, null);
    store.apply(source, "daily", definition, NOW);
    store.pause(source, "daily", definition, true, NOW);
    var changed =
        new io.aeyer.plowshare.protocol.ScheduledWork(
            definition.version(),
            "0 30 9 * * *",
            definition.zone(),
            false,
            definition.action(),
            definition.target(),
            definition.limits());
    store.apply(source, "daily", changed, NOW);
    assertFalse(store.files(source).getFirst().definition().paused());
    assertThrows(RuntimeException.class, () -> store.pause(source, "daily", definition, true, NOW));
    var foreign =
        new ScheduleDefinitionStore.Source(
            source.id(), "other", source.projectId(), source.project(), source.source());
    assertThrows(RuntimeException.class, () -> store.pause(foreign, "daily", changed, true, NOW));
    store.remove(source, "daily");
    store.apply(source, "daily", definition, NOW);
    assertFalse(store.files(source).getFirst().definition().paused());
  }

  @Test
  void failedOperationalPauseRollsBackOverrideScheduleAndTrigger() {
    var definition = ScheduleDefinitionCodecTest.definition("agent", null, null);
    var file = store.apply(source, "daily", definition, NOW);
    TriggerStore broken = mock(TriggerStore.class);
    when(broken.find(file.internalName())).thenReturn(triggers.find(file.internalName()));
    doThrow(new IllegalStateException("fixture failure"))
        .when(broken)
        .pause(anyString(), anyBoolean(), anyString());
    var atomic = new JdbcScheduleDefinitionStore(jdbc, work, schedules, broken, firings);
    assertThrows(
        IllegalStateException.class, () -> atomic.pause(source, "daily", definition, true, NOW));
    assertFalse(schedules.find(file.internalName()).orElseThrow().paused());
    assertFalse(store.files(source).getFirst().definition().paused());
    assertFalse(triggers.find(file.internalName()).orElseThrow().paused());
  }

  @Test
  void aPackagedPausedScheduleCanResumeAndItsSourceDefaultRemainsPaused() {
    var active = ScheduleDefinitionCodecTest.definition("agent", null, null);
    var definition =
        new io.aeyer.plowshare.protocol.ScheduledWork(
            active.version(),
            active.cron(),
            active.zone(),
            true,
            active.action(),
            active.target(),
            active.limits());
    var file = store.apply(source, "daily", definition, NOW);
    store.pause(source, "daily", definition, false, NOW);
    store.apply(source, "daily", definition, NOW.plusSeconds(86400));
    assertFalse(store.files(source).getFirst().definition().paused());
    assertFalse(schedules.find(file.internalName()).orElseThrow().paused());
    assertTrue(
        jdbc.queryForObject(
            "SELECT (definition->>'paused')::boolean FROM schedule_files WHERE source_id=? AND name=?",
            Boolean.class,
            source.id(),
            "daily"));
  }
}
