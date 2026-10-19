package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ScheduledWork;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Query budgets exclude delegated runtime stores and do not claim PostgreSQL latency. */
class JdbcScheduleDefinitionStoreTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final ScheduleStore schedules = mock(ScheduleStore.class);
  private final TriggerStore triggers = mock(TriggerStore.class);
  private final FiringStore firings = mock(FiringStore.class);
  private final ScheduleDefinitionStore.Source source =
      new ScheduleDefinitionStore.Source(1, "owner", 7L, "project", "workspace");
  private final ScheduledWork raw = ScheduleDefinitionCodecTest.definition("agent", null, null);
  private final Instant now = Instant.parse("2026-10-05T00:00:00Z");
  private final JdbcScheduleDefinitionStore store =
      new JdbcScheduleDefinitionStore(jdbc, UnitOfWork.NONE, schedules, triggers, firings);
  private final List<String> reads = new ArrayList<>();
  private String retainedName = "daily";
  private ScheduledWork retainedDefinition;
  private Boolean retainedOverride;
  private String retainedStatus;

  @BeforeEach
  void setup() throws Exception {
    retainedDefinition = raw;
    retainedStatus = "active";
    when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(List.of(1L));
    when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
        .thenAnswer(
            call -> {
              String sql = call.getArgument(0);
              reads.add(sql);
              assertTrue(sql.contains("AND name=?") || sql.contains("f.internal_name=?"), sql);
              RowMapper<Object> mapper = call.getArgument(1);
              return List.of(mapper.mapRow(row(), 0));
            });
    when(triggers.find(anyString()))
        .thenAnswer(
            call ->
                java.util.Optional.of(
                    new TriggerRecord(
                        call.getArgument(0),
                        "event",
                        "project",
                        null,
                        "agent",
                        "task",
                        null,
                        null,
                        1,
                        false,
                        "owner")));
  }

  private ResultSet row() throws Exception {
    var row = mock(ResultSet.class);
    when(row.getString("name")).thenReturn(retainedName);
    when(row.getString("internal_name")).thenReturn("scheduled-1-" + retainedName);
    when(row.getString("definition"))
        .thenReturn(
            retainedDefinition == null ? null : ScheduleDefinitionCodec.write(retainedDefinition));
    when(row.getObject("paused_override", Boolean.class)).thenReturn(retainedOverride);
    when(row.getString("status")).thenReturn(retainedStatus);
    when(row.getLong("id")).thenReturn(source.id());
    when(row.getString("account")).thenReturn(source.account());
    when(row.getObject("project_id", Long.class)).thenReturn(source.projectId());
    when(row.getString("project")).thenReturn(source.project());
    when(row.getString("source")).thenReturn(source.source());
    return row;
  }

  @Test
  void unchangedFilesEachReadOneKeyedSnapshotAndReuseItsOperationalOverride() {
    retainedOverride = true;
    for (int i = 0; i < 40; i++) {
      retainedName = "file" + i;
      var result = store.apply(source, retainedName, raw, now);
      assertTrue(result.definition().paused());
      assertEquals(".plowshare/schedules/" + retainedName + ".json", result.path());
    }
    assertEquals(40, reads.size());
    verify(jdbc, times(40)).queryForList(anyString(), eq(Long.class), any(Object[].class));
    verify(triggers, times(40)).find(anyString());
    verifyNoInteractions(schedules, firings);
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void currentSnapshotSeparatesSchemaVersionRawContentAndPauseControl() {
    retainedOverride = true;
    var snapshot = store.snapshot(source, "daily").orElseThrow();
    assertEquals(new ScheduleDefinitionStore.FileIdentity(1, "daily"), snapshot.identity());
    assertSame(source, snapshot.source());
    assertEquals(raw, snapshot.rawDefinition());
    assertFalse(snapshot.rawDefinition().paused());
    assertTrue(snapshot.effectiveDefinition().paused());
    assertTrue(snapshot.effective().definition().paused());
    assertEquals("scheduled-1-daily", snapshot.effective().internalName());
    assertEquals(1, snapshot.rawDefinition().version(), "This is the schema version only");
    assertFalse(raw.paused(), "Projection cannot mutate the source definition");
  }

  @Test
  void managedUsesOneOwnerBoundJoinWithoutLoadingSiblingFiles() {
    retainedOverride = true;
    var result = store.managed("scheduled-1-daily", "owner").orElseThrow();
    assertTrue(result.definition().paused());
    assertEquals("project", result.project());
    assertEquals(1, reads.size());
    assertTrue(reads.getFirst().contains("WHERE f.internal_name=? AND s.account=?"));
    verify(jdbc)
        .query(
            anyString(),
            ArgumentMatchers.<RowMapper<Object>>any(),
            eq("scheduled-1-daily"),
            eq("owner"));
    verifyNoMoreInteractions(jdbc);
  }

  @Test
  void changedDefinitionReturnsTheUpsertProjectionWithoutAFollowupRead() throws Exception {
    retainedOverride = true;
    var changed =
        new ScheduledWork(
            raw.version(),
            "0 30 9 * * *",
            raw.zone(),
            false,
            raw.action(),
            raw.target(),
            raw.limits());
    when(schedules.find(anyString())).thenReturn(java.util.Optional.empty());
    when(jdbc.queryForObject(
            anyString(), ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
        .thenAnswer(
            call -> {
              String sql = call.getArgument(0);
              assertTrue(
                  sql.contains(
                      "RETURNING name,internal_name,definition,paused_override,status,error"));
              assertTrue(sql.contains("schedule_files.definition=EXCLUDED.definition"));
              retainedDefinition = changed;
              retainedOverride = null;
              RowMapper<Object> mapper = call.getArgument(1);
              return mapper.mapRow(row(), 0);
            });
    var result = store.apply(source, "daily", changed, now);
    assertEquals(changed, result.definition());
    assertFalse(result.definition().paused());
    assertEquals(
        1, reads.size(), "Only the initial keyed read, no collection or post-write reload");
    verify(triggers).define(any());
  }

  @Test
  void stalePauseRefusesAfterTheKeyedReadBeforeRuntimeControl() {
    retainedOverride = true;
    assertThrows(CallerFault.class, () -> store.pause(source, "daily", raw, false, now));
    assertEquals(1, reads.size());
    verifyNoInteractions(schedules, triggers, firings);
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void diagnosticsAreBoundedBeforeRuntimeMutationAndNewlinesRemainExact() {
    for (String error : List.of("x".repeat(1001), "bad\0value")) {
      assertThrows(IllegalArgumentException.class, () -> store.reject(source, "daily", error));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new ScheduleDefinitionStore.FileSnapshot(
                  source, "daily", "scheduled-1-daily", null, null, "refused", error));
    }
    verifyNoInteractions(jdbc, schedules, triggers, firings);
    String error = "First line\nSecond line\r\nwith details";
    var refused =
        new ScheduleDefinitionStore.FileSnapshot(
            source, "daily", "scheduled-1-daily", null, null, "refused", error);
    assertEquals(error, refused.error());
    assertEquals(error, refused.effective().error());
    assertNull(refused.rawDefinition());
    assertNull(refused.effectiveDefinition());
  }

  @Test
  void snapshotIdentityRejectsUnpersistedOrMismatchedAddresses() {
    assertThrows(
        IllegalArgumentException.class, () -> new ScheduleDefinitionStore.FileIdentity(0, "daily"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ScheduleDefinitionStore.FileSnapshot(
                source, "daily", "scheduled-2-daily", raw, null, "active", null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ScheduleDefinitionStore.FileSnapshot(
                source, "daily", "scheduled-1-other", raw, null, "active", null));
  }
}
