package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ScheduledWork;
import io.aeyer.plowshare.server.archive.JdbcProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.cache.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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
  private static DriverManagerDataSource data;
  private static UnitOfWork work;
  private ScheduleStore schedules;
  private TriggerStore triggers;
  private FiringStore firings;
  private ScheduleDefinitionStore store;
  private ScheduleDefinitionStore.Source source;
  private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");

  @BeforeAll
  static void migrate() {
    data =
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
    io.aeyer.plowshare.server.testing.AuthorityFixtures.truncate(
        jdbc, "TRUNCATE user_inbox,firings,triggers,schedules,projects,admins CASCADE");
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
  void repeatedRefusalAndEqualSourceUpdatesDoNotInvalidateSchedulesButChangedErrorsDo() {
    store.reject(source, "invalid", "invalid definition");
    Long before =
        jdbc.queryForObject("SELECT last_value FROM snapshot_cache_schedules", Long.class);
    for (int i = 0; i < 20; i++) store.reject(source, "invalid", "invalid definition");
    jdbc.update(
        "UPDATE schedule_sources SET account=account,source=source WHERE id=?", source.id());
    assertEquals(
        before, jdbc.queryForObject("SELECT last_value FROM snapshot_cache_schedules", Long.class));
    store.reject(source, "invalid", "changed definition error");
    assertTrue(
        jdbc.queryForObject("SELECT last_value FROM snapshot_cache_schedules", Long.class)
            > before);
    assertThrows(
        RuntimeException.class,
        () ->
            store.reject(
                new ScheduleDefinitionStore.Source(
                    source.id(), "other", source.projectId(), source.project(), source.source()),
                "invalid",
                "foreign refusal"));
    assertEquals(
        "changed definition error",
        jdbc.queryForObject(
            "SELECT error FROM schedule_files WHERE source_id=? AND name='invalid'",
            String.class,
            source.id()));
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
  void keysetPagesFilterOwnershipAndProjectBeforeBoundsWhileCompleteScansRemainComplete() {
    for (var index :
        java.util.Map.of(
                "schedules_owner_name", "(defined_by, name)",
                "schedule_sources_owner_id", "(account, id)",
                "schedule_files_source_internal", "(source_id, internal_name)")
            .entrySet()) {
      String definition =
          jdbc.queryForObject("SELECT pg_get_indexdef(?::regclass)", String.class, index.getKey());
      assertTrue(definition.contains("USING btree " + index.getValue()), definition);
    }
    var definition = ScheduleDefinitionCodecTest.definition("agent", null, null);
    var expected = new java.util.ArrayList<String>();
    for (int i = 0; i < 5; i++)
      expected.add(store.apply(source, "daily-" + i, definition, NOW).internalName());
    var otherSource = store.register("other", source.projectId(), "workspace");
    expected.add(store.apply(otherSource, "different-registrar", definition, NOW).internalName());
    expected.sort(String::compareTo);
    Long outside =
        jdbc.queryForObject(
            "INSERT INTO projects(name) VALUES('outside') RETURNING id", Long.class);
    var outsideSource = store.register("owner", outside, "server");
    var outsideFile = store.apply(outsideSource, "outside", definition, NOW);
    schedules.define(
        "a-foreign-before-all", CronSchedule.parse("0 0 9 * * *", "UTC"), "event", "other", NOW);
    var owned = schedules.page("owner", null, new SchedulePage(null, 1));
    assertEquals(1, owned.size());
    assertEquals("owner", owned.getFirst().definedBy());
    assertEquals(
        expected.subList(0, 2),
        schedules.page("viewer", "project", new SchedulePage(null, 2)).stream()
            .map(ScheduleRecord::name)
            .toList());

    var selection =
        new ScheduleDefinitionStore.FileSelection(
            "viewer", "project", store.sourcesForInspection("viewer", "project"));
    var observed = new java.util.ArrayList<String>();
    String after = null;
    for (; ; ) {
      var page = store.filesPage(selection, new SchedulePage(after, 2));
      if (page.isEmpty()) break;
      page.forEach(file -> observed.add(file.internalName()));
      after = page.getLast().internalName();
    }
    assertEquals(expected, observed);
    assertFalse(observed.contains(outsideFile.internalName()));
    assertTrue(store.filesPage(selection, new SchedulePage(observed.getLast(), 2)).isEmpty());
    assertEquals(5, store.files(source).size());
    assertEquals(8, schedules.list().size());
    store.remove(source, "daily-1");
    assertEquals(
        expected.stream().filter(name -> !name.endsWith("daily-1")).toList(),
        store.filesPage(selection, new SchedulePage(null, 100)).stream()
            .map(ScheduledWork.File::internalName)
            .toList());
  }

  @Test
  void pagedCachedProjectFilesKeepViewerAndRegistrarBoundariesAndObservePauseAndRevocation() {
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin) VALUES('viewer','fixture',false)");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES(?,'viewer','VIEWER')",
        source.projectId());
    var definition = ScheduleDefinitionCodecTest.definition("agent", null, null);
    var file = store.apply(source, "daily", definition, NOW);
    var counted = new CountingJdbc();
    try (var caches = caches(counted)) {
      var configured = cached(counted, caches);
      var service =
          new FileScheduleDefinitions(
              configured,
              mock(ScheduleFiles.class),
              mock(ProjectStore.class),
              new JdbcProjectMembers(jdbc),
              mock(ScheduleDefinitions.Authority.class),
              mock(ScheduleControlAuthority.class));
      var page = new SchedulePage(null, 1);
      assertEquals(List.of(file), service.page("viewer", "project", page));
      assertEquals(List.of(file), service.page("viewer", "project", page));
      assertTrue(service.page("viewer", null, page).isEmpty());
      assertEquals(List.of(file), service.page("owner", null, page));
      assertEquals(List.of(file), service.page("owner", null, page));
      assertFalse(service.page("viewer", "project", page).getFirst().definition().paused());
      store.pause(source, "daily", definition, true, NOW);
      assertTrue(service.page("viewer", "project", page).getFirst().definition().paused());
      jdbc.update(
          "DELETE FROM project_members WHERE project_id=? AND handle='viewer'", source.projectId());
      assertThrows(RuntimeException.class, () -> service.page("viewer", "project", page));
      assertTrue(store.managed(file.internalName(), "owner").isPresent());
      jdbc.update(
          "DELETE FROM project_members WHERE project_id=? AND handle='owner'", source.projectId());
      assertTrue(service.page("owner", null, page).isEmpty());
    }
  }

  @Test
  void retainedFilePageSourceIdentityRefusesMovedOrRelabelledSourcesBeforeHydration() {
    var file =
        store.apply(
            source, "daily", ScheduleDefinitionCodecTest.definition("agent", null, null), NOW);
    var selection = new ScheduleDefinitionStore.FileSelection("owner", null, List.of(source));
    var page = new SchedulePage(null, 1);
    assertEquals(List.of(file), store.filesPage(selection, page));
    jdbc.update("UPDATE projects SET name='renamed' WHERE id=?", source.projectId());
    assertTrue(
        store.filesPage(selection, page).isEmpty(),
        "Direct fallback must retain the admitted project label");
    var counted = new CountingJdbc();
    try (var caches = caches(counted)) {
      var configured = cached(counted, caches);
      assertTrue(
          configured.filesPage(selection, page).isEmpty(),
          "Cold version capture after the move grants no new scope");
      assertEquals(1, counted.boundedLoads);
      jdbc.update("UPDATE projects SET name='project' WHERE id=?", source.projectId());
      assertEquals(List.of(file), configured.filesPage(selection, page));
      jdbc.update("UPDATE schedule_sources SET source='workspace' WHERE id=?", source.id());
      assertTrue(store.filesPage(selection, page).isEmpty());
      assertTrue(configured.filesPage(selection, page).isEmpty());
      jdbc.update("UPDATE schedule_sources SET source='server' WHERE id=?", source.id());
      Long moved =
          jdbc.queryForObject(
              "INSERT INTO projects(name) VALUES('moved') RETURNING id", Long.class);
      jdbc.update("UPDATE schedule_sources SET project_id=? WHERE id=?", moved, source.id());
      assertTrue(
          store.filesPage(selection, page).isEmpty(),
          "Source ID and account alone cannot authorize its new home");
      assertTrue(configured.filesPage(selection, page).isEmpty());
    }
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
    jdbc.update("UPDATE admins SET server_admin=TRUE WHERE account_kind='USER'");
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

  @Test
  void keyedSnapshotsAndReturningKeepRawAndEffectivePauseStateCoherent() {
    var definition = ScheduleDefinitionCodecTest.definition("agent", null, null);
    var inserted = store.apply(source, "daily", definition, NOW);
    store.apply(source, "sibling", definition, NOW);
    store.pause(source, "daily", definition, true, NOW);

    var retained = store.snapshot(source, "daily").orElseThrow();
    assertEquals(
        new ScheduleDefinitionStore.FileIdentity(source.id(), "daily"), retained.identity());
    assertEquals(source, retained.source());
    assertEquals(definition, retained.rawDefinition());
    assertFalse(retained.rawDefinition().paused());
    assertEquals(Boolean.TRUE, retained.pausedOverride());
    assertTrue(retained.effectiveDefinition().paused());
    assertEquals(
        retained.effective(), store.apply(source, "daily", definition, NOW.plusSeconds(60)));
    assertEquals(
        retained.effective(), store.managed(inserted.internalName(), "owner").orElseThrow());
    assertTrue(store.snapshot(source, "missing").isEmpty());

    var changed =
        new io.aeyer.plowshare.protocol.ScheduledWork(
            definition.version(),
            "0 30 9 * * *",
            definition.zone(),
            false,
            definition.action(),
            definition.target(),
            definition.limits());
    var returned = store.apply(source, "daily", changed, NOW);
    var current = store.snapshot(source, "daily").orElseThrow();
    assertEquals(current.effective(), returned);
    assertEquals(changed, current.rawDefinition());
    assertNull(current.pausedOverride());
    assertFalse(current.effectiveDefinition().paused());
    assertFalse(store.snapshot(source, "sibling").orElseThrow().effectiveDefinition().paused());
  }

  @Test
  void runtimeProvenanceLookupDoesNotTransferRegistrarOwnership() {
    var file =
        store.apply(
            source, "daily", ScheduleDefinitionCodecTest.definition("agent", null, null), NOW);
    assertEquals(source, store.sourceOf(file.internalName()).orElseThrow());
    assertEquals(source, store.sourceOf(file.internalName(), "owner").orElseThrow());
    assertTrue(store.sourceOf(file.internalName(), "other").isEmpty());
    assertTrue(store.managed(file.internalName(), "other").isEmpty());
    assertTrue(store.sourceOf("unregistered").isEmpty());
  }

  private static final class CountingJdbc extends JdbcTemplate {
    int boundedLoads, ordinaryLoads, preflights;
    Runnable afterPreflight = () -> {};

    CountingJdbc() {
      super(data);
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
      if (sql.startsWith("WITH retained")) boundedLoads++;
      else if (sql.contains("JOIN schedule_files f")
          && !sql.contains("GROUP BY")
          && !sql.contains("f.internal_name=?")) ordinaryLoads++;
      var rows = super.query(sql, mapper, args);
      if (!sql.contains("JOIN schedule_files") && sql.contains("WHERE s.id=?")) {
        assertFalse(sql.contains("definition"));
        assertFalse(sql.contains("octet_length"));
        assertFalse(sql.contains("count("));
        preflights++;
        var callback = afterPreflight;
        afterPreflight = () -> {};
        callback.run();
      }
      return rows;
    }
  }

  private static ProjectionCacheProperties cacheProperties() {
    return new ProjectionCacheProperties(
        256,
        33_554_432,
        8,
        67_108_864,
        256,
        64,
        8192,
        262_144,
        Duration.ofSeconds(5),
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        128,
        8,
        4);
  }

  private static ProjectionChangeSignals readySignals() {
    return new ProjectionChangeSignals() {
      public Subscription subscribe(Set<ProjectionVersions.Domain> domains, Observer observer) {
        observer.stateChanged(new State(1, true));
        return () -> {};
      }

      public void start() {}

      public boolean healthy() {
        return true;
      }

      public void close() {}
    };
  }

  private ProjectionCaches caches(CountingJdbc counted) {
    return new ProjectionCaches(
        new JdbcProjectionVersions(counted, data, 128), readySignals(), cacheProperties());
  }

  private JdbcScheduleDefinitionStore cached(CountingJdbc counted, ProjectionCaches caches) {
    var domains = Set.of(ProjectionVersions.Domain.SCHEDULES, ProjectionVersions.Domain.AUTHORITY);
    return new JdbcScheduleDefinitionStore(
        counted,
        work,
        schedules,
        triggers,
        firings,
        caches.create("file-fixture", domains),
        caches.create("files-fixture", domains));
  }

  private ScheduledWork wideDefinition() {
    var original = ScheduleDefinitionCodecTest.definition("agent", null, null);
    return new ScheduledWork(
        original.version(),
        original.cron(),
        original.zone(),
        false,
        new ScheduledWork.Action("agent", "worker", null, "x".repeat(16_000), null),
        original.target(),
        original.limits());
  }

  @Test
  void configuredCollectionGuardsMapZeroOneAndManyAndSkipWarmDefinitionHydration() {
    var counted = new CountingJdbc();
    try (var caches = caches(counted)) {
      var configured = cached(counted, caches);
      assertTrue(configured.files(source).isEmpty());
      assertTrue(configured.files(source).isEmpty());
      assertEquals(1, counted.boundedLoads);
      var definition = ScheduleDefinitionCodecTest.definition("agent", null, null);
      store.apply(source, "daily", definition, NOW);
      var first = configured.files(source);
      assertEquals(List.of("daily"), first.stream().map(ScheduledWork.File::name).toList());
      assertSame(first, configured.files(source));
      assertEquals(2, counted.boundedLoads);
      var keyed = configured.snapshot(source, "daily");
      assertSame(keyed, configured.snapshot(source, "daily"));
      assertEquals(3, counted.boundedLoads);
      store.apply(source, "sibling", definition, NOW);
      store.apply(source, "third", definition, NOW);
      var many = configured.files(source);
      assertEquals(
          List.of("daily", "sibling", "third"),
          many.stream().map(ScheduledWork.File::name).toList());
      assertSame(many, configured.files(source));
      assertEquals(4, counted.boundedLoads);
      assertEquals(0, counted.ordinaryLoads);
      assertEquals(8, counted.preflights);
    }
  }

  @Test
  void configuredScheduleGrowthReturnsAllRowsAndRefreshesProjectAndSourceIdentity() {
    var definition = ScheduleDefinitionCodecTest.definition("agent", null, null);
    store.apply(source, "daily", definition, NOW);
    var counted = new CountingJdbc();
    try (var caches = caches(counted)) {
      var configured = cached(counted, caches);
      counted.afterPreflight = () -> store.apply(source, "wide", wideDefinition(), NOW);
      assertEquals(
          List.of("daily", "wide"),
          configured.files(source).stream().map(ScheduledWork.File::name).toList());
      assertEquals(
          1, counted.boundedLoads, "Growth must be rejected by the actual CTE scalar sentinel");
      assertEquals(1, counted.ordinaryLoads);
      assertEquals(
          16_000,
          configured
              .snapshot(source, "wide")
              .orElseThrow()
              .rawDefinition()
              .action()
              .input()
              .length());
      assertEquals(
          2,
          counted.boundedLoads,
          "Oversized cold definitions are refused by their guarded loader");
      assertEquals(
          2, counted.ordinaryLoads, "Each refused cold load performs one complete fallback");
      jdbc.update("UPDATE projects SET name='renamed' WHERE id=?", source.projectId());
      var refreshed = configured.snapshot(source, "daily").orElseThrow();
      assertEquals("renamed", refreshed.source().project());
      assertEquals("renamed", refreshed.effective().project());
      var foreign =
          new ScheduleDefinitionStore.Source(
              source.id(), "other", source.projectId(), "renamed", source.source());
      assertTrue(configured.snapshot(foreign, "daily").isEmpty());
      assertTrue(configured.files(foreign).isEmpty());
      jdbc.update("DELETE FROM projects WHERE id=?", source.projectId());
      assertTrue(configured.snapshot(source, "daily").isEmpty());
      assertTrue(configured.files(source).isEmpty());
    }
  }

  @Test
  void configuredReadsObservePauseAndRevocationAndDirtyRollbackCannotPublish() {
    var definition = ScheduleDefinitionCodecTest.definition("agent", null, null);
    var file = store.apply(source, "daily", definition, NOW);
    var counted = new CountingJdbc();
    try (var caches = caches(counted)) {
      var configured = cached(counted, caches);
      assertFalse(
          configured.snapshot(source, "daily").orElseThrow().effectiveDefinition().paused());
      assertThrows(
          IllegalStateException.class,
          () ->
              work.inTransaction(
                  () -> {
                    store.pause(source, "daily", definition, true, NOW);
                    assertTrue(
                        configured
                            .snapshot(source, "daily")
                            .orElseThrow()
                            .effectiveDefinition()
                            .paused());
                    throw new IllegalStateException("fixture rollback");
                  }));
      assertFalse(
          configured.snapshot(source, "daily").orElseThrow().effectiveDefinition().paused());
      store.pause(source, "daily", definition, true, NOW);
      assertTrue(configured.snapshot(source, "daily").orElseThrow().effectiveDefinition().paused());
      assertTrue(
          configured.managed(file.internalName(), "owner").orElseThrow().definition().paused());
      var service =
          new FileScheduleDefinitions(
              configured,
              mock(ScheduleFiles.class),
              mock(ProjectStore.class),
              new JdbcProjectMembers(jdbc),
              mock(ScheduleDefinitions.Authority.class),
              mock(ScheduleControlAuthority.class));
      assertEquals(1, service.list("owner").size());
      jdbc.update(
          "DELETE FROM project_members WHERE project_id=? AND handle='owner'", source.projectId());
      assertTrue(
          service.list("owner").isEmpty(),
          "A warm UI cache does not replace live project admission");
      assertTrue(schedules.due(NOW.plusSeconds(86_400)).isEmpty());
    }
  }
}
