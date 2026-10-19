package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.agents.EntryKind;
import io.aeyer.plowshare.server.cache.ProjectionCache;
import io.aeyer.plowshare.server.cache.SnapshotCache;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

/** Query composition and payload admission, independently of the coordinator's version tests. */
class EntryPageCacheTest {
  @Test
  void scopedForwardPageChecksPermissionOnlyOnceAndTailNeverReadsADiscardedFirstPage()
      throws Exception {
    var f = new Fixture();
    f.scoped.pageOfLog("conversation", 0, 20);
    assertEquals(4, f.sql.size()); // Current permission, reach, count, final rows.
    f.sql.clear();
    f.arguments.clear();
    var page = f.scoped.tailOfLog("conversation", 20);
    assertEquals(4, f.sql.size());
    assertEquals(87, page.total());
    assertEquals(100, page.through());
    var rows = f.arguments.getLast();
    assertEquals(67, rows.get(rows.size() - 2));
    assertEquals(20, rows.getLast());
    assertEquals(List.of("conversation", 0, 100), f.arguments.get(2));
  }

  @Test
  void systemScopedReadsKeepDirectSemanticsWithoutAnAuthenticatedCacheIdentity() throws Exception {
    var f = new Fixture();
    var cache = new TestCache();
    f.base.useTranscriptCache(cache).forAccount(null).pageOfLog("conversation", 0, 20);
    assertEquals(4, f.sql.size());
    assertTrue(cache.values.isEmpty());
  }

  @Test
  void warmScopedPageReusesRowsButStillPerformsCurrentAdmissionAndNeverSharesLegacyReads()
      throws Exception {
    var f = new Fixture();
    var cache = new TestCache();
    f.base.useTranscriptCache(cache);
    var scoped = f.base.forAccount("alice");
    var first = scoped.pageOfLog("conversation", 0, 20);
    assertSame(first, scoped.pageOfLog("conversation", 0, 20));
    assertEquals(6, f.sql.size()); // Five cold statements, then only current log admission.
    assertEquals("Full transcript", first.listed().getFirst().excerpt());
    assertThrows(UnsupportedOperationException.class, () -> first.listed().clear());
    assertTrue(f.sql.get(3).contains("LEFT JOIN page_rows p ON b.cache_bytes<=?"));
    f.base.pageOfLog("conversation", 0, 20);
    assertEquals(9, f.sql.size()); // Unscoped internal reads never enter the shared cache.
    assertEquals(1, cache.values.size());
    f.allowed = false;
    assertThrows(NotFoundFault.class, () -> scoped.pageOfLog("conversation", 0, 20));
    assertEquals(10, f.sql.size());
    assertEquals(1, cache.values.size());
  }

  @Test
  void keySeparatesAccountConversationFiltersDirectionOffsetReachAndTail() throws Exception {
    var f = new Fixture();
    var cache = new TestCache();
    f.base.useTranscriptCache(cache);
    var alice = f.base.forAccount("alice");
    alice.pageOfLog("conversation", 0, 20);
    alice.pageOfLog("conversation", 1, 20);
    alice.pageOfLog("conversation", 0, 21);
    alice.pageOfLog("another", 0, 20);
    f.base.forAccount("bob").pageOfLog("conversation", 0, 20);
    alice.tailOfLog("conversation", 20);
    alice.pageOfLogAfter("conversation", 1, Set.of(EntryKind.UTTERANCE), false, 0, 20);
    alice.pageOfLogAfter("conversation", 1, Set.of(EntryKind.UTTERANCE), true, 0, 20);
    alice.pageOfLogBefore("conversation", 50, EntryStore.EVERY_KIND, false, 0, 20);
    assertEquals(9, cache.values.size());
    alice.pageOfLog("conversation", 0, 20);
    assertEquals(9, cache.values.size());
  }

  @Test
  void oversizeSentinelRefusesBeforeTextAndToolJsonMappingThenPreservesOrdinaryOutput()
      throws Exception {
    var f = new Fixture();
    f.oversized = true;
    f.base.useTranscriptCache(new TestCache());
    var scoped = f.base.forAccount("alice");
    for (int i = 0; i < 2; i++)
      assertEquals(
          "Full transcript", scoped.pageOfLog("conversation", 0, 20).listed().getFirst().excerpt());
    assertEquals(16, f.sql.size()); // Admission + 3 bounded + 3 ordinary + post-load admission.
    verify(f.sentinel, never()).getString(anyString());
    verify(f.sentinel, never()).getObject("ordinal");
    assertTrue(f.sql.get(3).contains("octet_length(to_jsonb(p)::text)"));
    assertFalse(f.sql.get(6).contains("cache_bytes"));
  }

  @Test
  void revocationBetweenPreflightAndCaptureCannotPublishANewTokenWithUnauthorizedRows()
      throws Exception {
    var f = new Fixture();
    var cache = new TestCache();
    // Model the coordinator electing a fill after the public preflight. A newly captured durable
    // version can already include the revocation, so the loader must perform its own fresh gate.
    cache.beforeLoad = () -> f.allowed = false;
    f.base.useTranscriptCache(cache);
    assertThrows(
        NotFoundFault.class, () -> f.base.forAccount("alice").pageOfLog("conversation", 0, 20));
    assertTrue(cache.values.isEmpty());
    assertEquals(5, f.sql.size());
    assertTrue(f.sql.getLast().contains("information_log_readable"));
  }

  @Test
  void oversizedFallbackAlsoRechecksLiveAuthorityBeforeReturningOrPublishing() throws Exception {
    var f = new Fixture();
    f.oversized = true;
    var cache = new TestCache();
    cache.beforeLoad = () -> f.allowed = false;
    f.base.useTranscriptCache(cache);
    assertThrows(
        NotFoundFault.class, () -> f.base.forAccount("alice").pageOfLog("conversation", 0, 20));
    assertTrue(cache.values.isEmpty());
    assertEquals(8, f.sql.size());
    assertTrue(f.sql.getLast().contains("information_log_readable"));
    verify(f.sentinel, never()).getString(anyString());
  }

  @Test
  void emptyTailKeepsReachAndNeverHydratesWhileSqlFailureIsNeverReplayed() throws Exception {
    var f = new Fixture();
    f.total = 0;
    var page = f.scoped.tailOfLog("conversation", 20);
    assertEquals(100, page.through());
    assertTrue(page.listed().isEmpty());
    assertEquals(3, f.sql.size());
    when(f.jdbc.queryForObject(contains("COUNT(*)"), eq(Integer.class), any(Object[].class)))
        .thenThrow(new DataAccessResourceFailureException("unavailable"));
    assertThrows(ArchiveUnavailableException.class, () -> f.scoped.tailOfLog("conversation", 20));
  }

  private static final class Fixture {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final EntryStore base = new EntryStore(jdbc);
    final EntryStore scoped = base.forAccount("alice");
    final List<String> sql = new ArrayList<>();
    final List<List<Object>> arguments = new ArrayList<>();
    final ResultSet sentinel = mock(ResultSet.class);
    boolean allowed = true;
    boolean oversized;
    int total = 87;

    Fixture() throws Exception {
      when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
          .thenAnswer(
              call -> {
                record(call.getArgument(0), (Object[]) call.getRawArguments()[2]);
                return allowed;
              });
      when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
          .thenAnswer(
              call -> {
                String statement = call.getArgument(0);
                record(statement, (Object[]) call.getRawArguments()[2]);
                return statement.contains("COUNT(*)") ? total : 100;
              });
      var row = mock(ResultSet.class);
      when(row.getInt("ordinal")).thenReturn(1);
      when(row.getObject("ordinal")).thenReturn(1);
      when(row.getInt("turn_ordinal")).thenReturn(1);
      when(row.getString("kind")).thenReturn("utterance");
      when(row.getString("excerpt")).thenReturn("Full transcript");
      when(row.getInt("length")).thenReturn(15);
      when(sentinel.getLong("cache_bytes")).thenReturn(Long.MAX_VALUE);
      doAnswer(
              call -> {
                record(call.getArgument(0), (Object[]) call.getRawArguments()[2]);
                RowMapper<EntryPage.Row> mapper = call.getArgument(1);
                return List.of(mapper.mapRow(row, 0));
              })
          .when(jdbc)
          .query(
              anyString(),
              org.mockito.ArgumentMatchers.<RowMapper<EntryPage.Row>>any(),
              any(Object[].class));
      doAnswer(
              call -> {
                record(call.getArgument(0), (Object[]) call.getRawArguments()[2]);
                var selected = oversized ? sentinel : row;
                when(selected.next()).thenReturn(true).thenReturn(false);
                ResultSetExtractor<List<EntryPage.Row>> mapper = call.getArgument(1);
                return mapper.extractData(selected);
              })
          .when(jdbc)
          .query(
              anyString(),
              org.mockito.ArgumentMatchers.<ResultSetExtractor<List<EntryPage.Row>>>any(),
              any(Object[].class));
    }

    void record(String statement, Object[] bound) {
      sql.add(statement);
      arguments.add(Arrays.asList(bound.clone()));
    }
  }

  private static final class TestCache implements ProjectionCache<EntryPage> {
    final Map<SnapshotCache.Key, EntryPage> values = new HashMap<>();
    Runnable beforeLoad = () -> {};

    public boolean enabled() {
      return true;
    }

    public long maxWeight() {
      return 2_000_000;
    }

    public EntryPage read(
        SnapshotCache.Key key,
        boolean cacheable,
        Supplier<EntryPage> bounded,
        Supplier<EntryPage> direct,
        ToLongFunction<EntryPage> weight) {
      if (!cacheable) return direct.get();
      if (values.containsKey(key)) return values.get(key);
      try {
        beforeLoad.run();
        var page = bounded.get();
        assertTrue(weight.applyAsLong(page) <= maxWeight());
        values.put(key, page);
        return page;
      } catch (Bypass refused) {
        return direct.get();
      }
    }
  }
}
