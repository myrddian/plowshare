package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ScheduledWork;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.cache.ProjectionCache;
import io.aeyer.plowshare.server.cache.SnapshotCache;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Configured UI reuse never supplies write/execution authority or transfers source identity. */
class ScheduleProjectionCacheTest {
  final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  final ScheduleStore schedules = mock(ScheduleStore.class);
  final TriggerStore triggers = mock(TriggerStore.class);
  final FiringStore firings = mock(FiringStore.class);
  final RecordingCache<Optional<ScheduleDefinitionStore.FileSnapshot>> fileCache =
      new RecordingCache<>();
  final RecordingCache<List<ScheduledWork.File>> filesCache = new RecordingCache<>();
  final JdbcScheduleDefinitionStore store =
      new JdbcScheduleDefinitionStore(
          jdbc, UnitOfWork.NONE, schedules, triggers, firings, fileCache, filesCache);
  final ScheduleDefinitionStore.Source source =
      new ScheduleDefinitionStore.Source(1, "owner", 7L, "project", "workspace");
  final ScheduledWork definition = ScheduleDefinitionCodecTest.definition("agent", null, null);
  final List<String> statements = new ArrayList<>();
  boolean sourceAvailable = true, grow, paused;
  String currentProject = "project";
  long count = 1, bytes = 500;
  int returnedFiles = 1;

  private static final class RecordingCache<T> implements ProjectionCache<T> {
    final ConcurrentMap<SnapshotCache.Key, T> values = new ConcurrentHashMap<>();
    int reads, bounded, ordinary;
    boolean lastCacheable;

    public boolean enabled() {
      return true;
    }

    public long maxWeight() {
      return 1_000_000;
    }

    public T read(
        SnapshotCache.Key key,
        boolean cacheable,
        Supplier<T> loader,
        Supplier<T> fallback,
        ToLongFunction<T> weight) {
      reads++;
      lastCacheable = cacheable;
      if (!cacheable) {
        ordinary++;
        return fallback.get();
      }
      T retained = values.get(key);
      if (retained != null) return retained;
      T value;
      try {
        bounded++;
        value = loader.get();
      } catch (ProjectionCache.Bypass oversized) {
        ordinary++;
        return fallback.get();
      }
      assertTrue(weight.applyAsLong(value) > 0);
      if (weight.applyAsLong(value) <= maxWeight()) values.put(key, value);
      return value;
    }
  }

  @BeforeEach
  void setup() throws Exception {
    when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(List.of(1L));
    when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
        .thenAnswer(
            call -> {
              String sql = call.getArgument(0);
              statements.add(sql);
              boolean sourceLookup = sourceLookup(sql);
              if (sourceLookup && !sourceAvailable) return List.of();
              boolean bounded = sql.startsWith("WITH retained");
              RowMapper<Object> mapper = call.getArgument(1);
              int rows =
                  sourceLookup || (bounded && grow) || sql.contains("AND f.name=?")
                      ? 1
                      : returnedFiles;
              var result = new ArrayList<Object>();
              for (int index = 0; index < rows; index++)
                result.add(
                    mapper.mapRow(row(bounded && grow, index == 0 ? "daily" : "sibling"), index));
              return result;
            });
  }

  private static boolean sourceLookup(String sql) {
    return !sql.contains("JOIN schedule_files") && sql.contains("WHERE s.id=?");
  }

  private ResultSet row(boolean oversized, String name) throws Exception {
    var row = mock(ResultSet.class);
    when(row.getLong("id")).thenReturn(1L);
    when(row.getString("account")).thenReturn("owner");
    when(row.getObject("project_id", Long.class)).thenReturn(7L);
    when(row.getString("project")).thenReturn(currentProject);
    when(row.getString("source")).thenReturn("workspace");
    when(row.getLong("total_files")).thenReturn(oversized ? 300L : count);
    when(row.getLong("projection_bytes")).thenReturn(bytes);
    when(row.getString("name")).thenReturn(name);
    when(row.getString("internal_name")).thenReturn("scheduled-1-" + name);
    if (oversized)
      when(row.getString("definition"))
          .thenThrow(
              new AssertionError("The oversized sentinel must be rejected before hydration"));
    else when(row.getString("definition")).thenReturn(ScheduleDefinitionCodec.write(definition));
    when(row.getObject("paused_override", Boolean.class)).thenReturn(paused ? true : null);
    when(row.getString("status")).thenReturn("active");
    return row;
  }

  @Test
  void warmUiReadsRefreshOnlySourceIdentityAndSkipAllDefinitionScans() {
    var first = store.snapshot(source, "daily");
    assertSame(first, store.snapshot(source, "daily"));
    var files = store.files(source);
    assertSame(files, store.files(source));
    assertEquals(6, statements.size());
    assertEquals(4, statements.stream().filter(ScheduleProjectionCacheTest::sourceLookup).count());
    assertEquals(2, statements.stream().filter(sql -> sql.startsWith("WITH retained")).count());
    assertTrue(
        statements.stream()
            .filter(ScheduleProjectionCacheTest::sourceLookup)
            .allMatch(
                sql ->
                    sql.contains(
                            "s.id=? AND s.account=? AND s.source=? AND s.project_id IS NOT DISTINCT FROM ?")
                        && !sql.contains("definition")
                        && !sql.contains("octet_length")
                        && !sql.contains("schedule_files")
                        && !sql.contains("count(")));
    assertThrows(UnsupportedOperationException.class, files::clear);
    assertEquals(1, fileCache.bounded);
    assertEquals(1, filesCache.bounded);
  }

  @Test
  void missingOrForeignSourceCannotExposeAnOldCachedProjection() {
    store.snapshot(source, "daily");
    store.files(source);
    sourceAvailable = false;
    assertTrue(store.snapshot(source, "daily").isEmpty());
    assertTrue(store.files(source).isEmpty());
    assertEquals(1, fileCache.reads);
    assertEquals(1, filesCache.reads);
    assertEquals(6, statements.size());
  }

  @Test
  void refreshedSourceLabelsParticipateInIdentityAndReturnedProjection() {
    var first = store.snapshot(source, "daily").orElseThrow();
    currentProject = "renamed";
    var next = store.snapshot(source, "daily").orElseThrow();
    assertEquals("project", first.source().project());
    assertEquals("renamed", next.source().project());
    assertEquals("renamed", next.effective().project());
    assertEquals(2, fileCache.values.size());
    assertNotEquals(first, next);
  }

  @Test
  void oversizedCollectionsUseOneBoundedRefusalThenTheCompleteOrdinaryRead() {
    count = 300;
    returnedFiles = 2;
    assertEquals(
        List.of("daily", "sibling"),
        store.files(source).stream().map(ScheduledWork.File::name).toList());
    assertTrue(filesCache.lastCacheable);
    assertEquals(1, filesCache.bounded);
    assertEquals(1, filesCache.ordinary);
    assertTrue(filesCache.values.isEmpty());
    assertEquals(3, statements.size());
    assertFalse(statements.getLast().startsWith("WITH retained"));
  }

  @Test
  void growthAfterPreflightReturnsTheFullOrdinaryResultWithoutMappingTheLargeSentinel() {
    grow = true;
    returnedFiles = 2;
    assertTrue(store.snapshot(source, "daily").isPresent());
    assertEquals(2, store.files(source).size());
    assertEquals(1, fileCache.ordinary);
    assertEquals(1, filesCache.ordinary);
    assertTrue(fileCache.values.isEmpty());
    assertTrue(filesCache.values.isEmpty());
    assertEquals(6, statements.size());
  }

  @Test
  void managedExecutionReadsRemainDirectEvenWhenUiProjectionIsWarm() {
    store.snapshot(source, "daily");
    paused = true;
    var execution = store.managed("scheduled-1-daily", "owner").orElseThrow();
    assertTrue(execution.definition().paused());
    assertEquals(1, fileCache.reads);
    assertEquals(3, statements.size());
    assertTrue(statements.getLast().contains("WHERE f.internal_name=? AND s.account=?"));
  }

  @Test
  void unchangedApplyAndPauseUseFreshLockedRowsWithoutCallingSharedCaches() {
    when(triggers.find(anyString()))
        .thenReturn(
            Optional.of(
                new TriggerRecord(
                    "scheduled-1-daily",
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
    var applied = store.apply(source, "daily", definition, Instant.parse("2026-10-01T12:00:00Z"));
    assertEquals(definition, applied.definition());
    when(schedules.find(anyString())).thenReturn(Optional.of(mock(ScheduleRecord.class)));
    store.pause(source, "daily", definition, true, Instant.parse("2026-10-01T12:00:00Z"));
    assertEquals(0, fileCache.reads);
    assertEquals(0, filesCache.reads);
    assertEquals(2, statements.size());
    assertTrue(statements.stream().allMatch(sql -> sql.startsWith("SELECT name,internal_name")));
    verify(jdbc, times(2))
        .queryForList(contains("FOR UPDATE"), eq(Long.class), any(Object[].class));
    verify(schedules).pause("scheduled-1-daily", true, "owner");
  }

  @Test
  void oldConstructorRetainsDirectReadsWithoutSourcePreflightOrCacheQueries() {
    var direct =
        new JdbcScheduleDefinitionStore(jdbc, UnitOfWork.NONE, schedules, triggers, firings);
    assertTrue(direct.snapshot(source, "daily").isPresent());
    assertEquals(1, direct.files(source).size());
    assertEquals(2, statements.size());
    assertTrue(
        statements.stream()
            .noneMatch(sql -> sql.contains("GROUP BY") || sql.startsWith("WITH retained")));
  }
}
