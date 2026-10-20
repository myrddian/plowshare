package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.InformationNavigation.*;
import io.aeyer.plowshare.protocol.InformationNavigation.Readable;
import io.aeyer.plowshare.server.access.KnowledgeReadScope;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.cache.ProjectionCache;
import io.aeyer.plowshare.server.cache.SnapshotCache;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

/** One statement preserves graph paging/disclosure; database equivalence is tested separately. */
class InformationConnectionsCacheTest {
  private static final InformationContext CONTEXT =
      new InformationContext("alice", InformationContext.Selection.project("research"))
          .withView(true);
  private static final UUID RESOURCE = UUID.randomUUID();

  @Test
  void oneStatementBatchesReadableLockedAndUnavailableNeighboursWithoutLeakingPins()
      throws Exception {
    var f = new Fixture(false);
    var result = f.repository.relationships(CONTEXT, RESOURCE, "outgoing", "cites", null, 10, 7);
    assertEquals(1, f.sql.size());
    assertEquals(2, result.connections().size()); // Unavailable neighbours drop after the raw page.
    var readable = result.connections().getFirst();
    assertInstanceOf(Readable.class, readable.selection());
    assertEquals(3L, ((Readable) readable.selection()).document().tagsVersion());
    assertNotNull(readable.relationship().pinnedRevision());
    var locked = result.connections().get(1);
    assertEquals("Restricted document", ((Locked) locked.selection()).label());
    assertNull(locked.relationship().pinnedRevision());
    verify(f.rows.get(1), never()).getString("title");
    verify(f.rows.get(2), never()).getString("title");
    assertEquals(7, result.offset());
    assertEquals(10, result.limit());
    String sql = f.sql.getFirst();
    assertTrue(sql.contains("source AS MATERIALIZED (SELECT EXISTS("));
    assertTrue(sql.contains("knowledge_dependencies_readable(e.selected_revision,?)"));
    assertFalse(sql.contains("information_navigation_own_readable"));
    assertFalse(sql.contains("information_reference_readable"));
    assertTrue(
        sql.contains(
            "(dependency_readable OR reference_visible) AND selection_boundary ORDER BY id LIMIT ? OFFSET ?"));
    assertTrue(sql.contains("r.resource_id=q.id AND r.id=p.selected_revision"));
    assertTrue(sql.contains("WHERE d.resource IS NULL AND p.reference_visible"));
    assertEquals(
        Arrays.asList(
            "alice",
            "project",
            "research",
            true,
            true,
            RESOURCE,
            RESOURCE,
            "cites",
            "alice",
            "alice",
            "research",
            10,
            7),
        f.arguments.getFirst());
  }

  @Test
  void emptyPageAndUnavailableParentAreDifferentAndNeverCauseFollowupQueries() throws Exception {
    var f = new Fixture(false);
    f.rows.clear();
    var sentinel = mock(ResultSet.class);
    when(sentinel.getBoolean("parent_readable")).thenReturn(true);
    f.rows.add(sentinel);
    assertTrue(
        f.repository
            .relationships(CONTEXT, RESOURCE, "backlinks", null, null, 2, 0)
            .connections()
            .isEmpty());
    when(sentinel.getBoolean("parent_readable")).thenReturn(false);
    assertThrows(
        NotFoundFault.class,
        () -> f.repository.relationships(CONTEXT, RESOURCE, "backlinks", null, null, 2, 0));
    assertEquals(2, f.sql.size());
    assertTrue(f.sql.getFirst().contains("e.source AS neighbour"));
    assertTrue(f.sql.getFirst().contains("AND e.target=?"));
  }

  @Test
  void boundedWarmDtoReuseKeysEveryContextAndGraphPageInput() throws Exception {
    var f = new Fixture(true);
    var first = f.repository.relationships(CONTEXT, RESOURCE, "outgoing", null, null, 10, 0);
    assertSame(first, f.repository.relationships(CONTEXT, RESOURCE, "outgoing", null, null, 10, 0));
    assertEquals(1, f.sql.size());
    assertThrows(UnsupportedOperationException.class, () -> first.connections().clear());
    f.repository.relationships(CONTEXT, UUID.randomUUID(), "outgoing", null, null, 10, 0);
    f.repository.relationships(CONTEXT, RESOURCE, "backlinks", null, null, 10, 0);
    f.repository.relationships(CONTEXT, RESOURCE, "outgoing", "cites", null, 10, 0);
    f.repository.relationships(CONTEXT, RESOURCE, "outgoing", null, UUID.randomUUID(), 10, 0);
    f.repository.relationships(CONTEXT, RESOURCE, "outgoing", null, null, 10, 1);
    f.repository.relationships(CONTEXT, RESOURCE, "outgoing", null, null, 11, 0);
    f.repository.relationships(
        CONTEXT.within(KnowledgeReadScope.homeOnly(Home.of("research"))),
        RESOURCE,
        "outgoing",
        null,
        null,
        10,
        0);
    f.repository.relationships(
        new InformationContext("bob", CONTEXT.selection()).withView(true),
        RESOURCE,
        "outgoing",
        null,
        null,
        10,
        0);
    assertEquals(9, f.sql.size());
    assertTrue(f.sql.getFirst().contains("LEFT JOIN connection_rows p ON b.cache_bytes<=?"));
    assertEquals(9, f.cache.values.size());
  }

  @Test
  void oversizeFallsBackBeforeHydratingAnyGraphFieldsAndDoesNotRetainTheFallback()
      throws Exception {
    var f = new Fixture(true);
    f.oversized = true;
    for (int i = 0; i < 2; i++)
      assertEquals(
          2,
          f.repository
              .relationships(CONTEXT, RESOURCE, "outgoing", null, null, 10, 0)
              .connections()
              .size());
    assertEquals(4, f.sql.size());
    verify(f.sentinel, never()).getString(anyString());
    verify(f.sentinel, never()).getObject(anyString(), eq(UUID.class));
    verify(f.sentinel, never()).getBoolean("parent_readable");
    assertTrue(f.cache.values.isEmpty());
  }

  private static final class Fixture {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final TestCache cache = new TestCache();
    final JdbcInformationNavigationRepository repository;
    final List<String> sql = new ArrayList<>();
    final List<List<Object>> arguments = new ArrayList<>();
    final List<ResultSet> rows = new ArrayList<>();
    final ResultSet sentinel = mock(ResultSet.class);
    boolean oversized;

    Fixture(boolean cached) throws Exception {
      repository =
          new JdbcInformationNavigationRepository(
              jdbc,
              mock(ProjectMembers.class),
              ProjectionCache.direct(),
              ProjectionCache.direct(),
              cached ? cache : ProjectionCache.direct());
      rows.add(row(true, false));
      rows.add(row(false, true));
      rows.add(row(false, false));
      when(sentinel.getLong("cache_bytes")).thenReturn(Long.MAX_VALUE);
      doAnswer(
              call -> {
                String statement = call.getArgument(0);
                sql.add(statement);
                arguments.add(Arrays.asList(((Object[]) call.getRawArguments()[2]).clone()));
                var selected =
                    oversized && statement.contains("cache_bytes") ? List.of(sentinel) : rows;
                // A cursor delegates only the current row's typed JDBC calls to that row fixture.
                var cursor =
                    mock(
                        ResultSet.class,
                        invocation -> {
                          throw new AssertionError(
                              "Unexpected cursor call " + invocation.getMethod().getName());
                        });
                var position = new java.util.concurrent.atomic.AtomicInteger(-1);
                doAnswer(ignored -> position.incrementAndGet() < selected.size())
                    .when(cursor)
                    .next();
                doAnswer(
                        invocation ->
                            selected
                                .get(position.get())
                                .getBoolean(invocation.getArgument(0, String.class)))
                    .when(cursor)
                    .getBoolean(anyString());
                doAnswer(
                        invocation ->
                            selected
                                .get(position.get())
                                .getString(invocation.getArgument(0, String.class)))
                    .when(cursor)
                    .getString(anyString());
                doAnswer(
                        invocation ->
                            selected
                                .get(position.get())
                                .getLong(invocation.getArgument(0, String.class)))
                    .when(cursor)
                    .getLong(anyString());
                doAnswer(
                        invocation ->
                            selected
                                .get(position.get())
                                .getObject(invocation.getArgument(0, String.class), UUID.class))
                    .when(cursor)
                    .getObject(anyString(), eq(UUID.class));
                doAnswer(
                        invocation ->
                            selected
                                .get(position.get())
                                .getObject(invocation.getArgument(0, String.class), Long.class))
                    .when(cursor)
                    .getObject(anyString(), eq(Long.class));
                ResultSetExtractor<Connections> extractor = call.getArgument(1);
                return extractor.extractData(cursor);
              })
          .when(jdbc)
          .query(
              anyString(),
              org.mockito.ArgumentMatchers.<ResultSetExtractor<Connections>>any(),
              any(Object[].class));
    }

    ResultSet row(boolean readable, boolean locked) throws Exception {
      var row = mock(ResultSet.class);
      when(row.getBoolean("parent_readable")).thenReturn(true);
      when(row.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
      when(row.getObject("source", UUID.class)).thenReturn(RESOURCE);
      var target = UUID.randomUUID();
      when(row.getObject("target", UUID.class)).thenReturn(target);
      when(row.getObject("neighbour", UUID.class)).thenReturn(target);
      when(row.getObject("pinned_revision", UUID.class)).thenReturn(UUID.randomUUID());
      when(row.getString("relation")).thenReturn("cites");
      when(row.getLong("version")).thenReturn(1L);
      when(row.getBoolean("locked_visible")).thenReturn(locked);
      if (locked) when(row.getString("locked_label")).thenReturn("Restricted document");
      if (readable) {
        when(row.getObject("resource", UUID.class)).thenReturn(target);
        when(row.getObject("revision", UUID.class)).thenReturn(UUID.randomUUID());
        when(row.getString("title")).thenReturn("Manual");
        when(row.getString("source_name")).thenReturn("manual.md");
        when(row.getString("owner")).thenReturn("alice");
        when(row.getString("tags")).thenReturn("[\"guide\"]");
        when(row.getObject("tags_version", Long.class)).thenReturn(3L);
        when(row.getString("visibility")).thenReturn("home");
        when(row.getString("project_name")).thenReturn("research");
      }
      return row;
    }
  }

  private static final class TestCache implements ProjectionCache<Connections> {
    final Map<SnapshotCache.Key, Connections> values = new HashMap<>();

    public boolean enabled() {
      return true;
    }

    public long maxWeight() {
      return 2_000_000;
    }

    public Connections read(
        SnapshotCache.Key key,
        boolean cacheable,
        Supplier<Connections> bounded,
        Supplier<Connections> direct,
        ToLongFunction<Connections> weight) {
      if (!cacheable) return direct.get();
      if (values.containsKey(key)) return values.get(key);
      try {
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
