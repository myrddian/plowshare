package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Information;
import io.aeyer.plowshare.server.access.KnowledgeReadScope;
import io.aeyer.plowshare.server.allowances.AllowanceRepository;
import io.aeyer.plowshare.server.cache.ProjectionCache;
import io.aeyer.plowshare.server.cache.SnapshotCache;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Selection keys, exact cold statements and bounded hydration; durable fences are tested
 * separately.
 */
class InformationDiscoveryCacheTest {
  final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  final RecordingCache<List<Information.Revision>> lists = new RecordingCache<>();
  final RecordingCache<Information.Facets> facets = new RecordingCache<>();
  final AllowanceRepository allowances = mock(AllowanceRepository.class);
  final JdbcInformationCatalogueRepository repository =
      new JdbcInformationCatalogueRepository(
          jdbc,
          Clock.systemUTC(),
          allowances,
          ProjectionCache.direct(),
          ProjectionCache.direct(),
          lists,
          facets);
  final InformationContext context =
      new InformationContext("reader", InformationContext.Selection.personal()).withView(true);
  final UUID revision = UUID.randomUUID(), resource = UUID.randomUUID();
  final List<String> listSql = new ArrayList<>(), facetSql = new ArrayList<>();
  final List<Object[]> listArguments = new ArrayList<>(), facetArguments = new ArrayList<>();
  boolean largePage, emptyPage, largeFacets, databaseFailure;

  private static final class RecordingCache<T> implements ProjectionCache<T> {
    final ConcurrentMap<SnapshotCache.Key, T> values = new ConcurrentHashMap<>();
    int bounded, fallback, reads;

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
        Supplier<T> ordinary,
        ToLongFunction<T> weight) {
      reads++;
      if (!cacheable) {
        fallback++;
        return ordinary.get();
      }
      T retained = values.get(key);
      if (retained != null) return retained;
      T value;
      try {
        bounded++;
        value = loader.get();
      } catch (ProjectionCache.Bypass guard) {
        fallback++;
        return ordinary.get();
      }
      long charged = weight.applyAsLong(value);
      assertTrue(charged > 0);
      if (charged <= maxWeight()) values.put(key, value);
      return value;
    }
  }

  @BeforeEach
  void setup() {
    when(jdbc.queryForList(anyString(), any(Object[].class)))
        .thenAnswer(
            call -> {
              String sql = call.getArgument(0);
              listSql.add(sql);
              listArguments.add(((Object[]) call.getRawArguments()[1]).clone());
              if (databaseFailure)
                throw new DataAccessResourceFailureException("fixture database failure");
              if (sql.startsWith("WITH catalogue_page")) {
                if (largePage)
                  return List.of(Map.of("cache_bytes", Long.MAX_VALUE, "cache_rows", 1L));
                if (emptyPage) return List.of(Map.of("cache_bytes", 0L, "cache_rows", 0L));
                var row = revisionRow();
                row.put("cache_bytes", 500L);
                row.put("cache_rows", 1L);
                return List.of(row);
              }
              return emptyPage ? List.of() : List.of(revisionRow());
            });
    when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
        .thenAnswer(
            call -> {
              String sql = call.getArgument(0);
              facetSql.add(sql);
              facetArguments.add(((Object[]) call.getRawArguments()[2]).clone());
              if (databaseFailure)
                throw new DataAccessResourceFailureException("fixture database failure");
              RowMapper<Object> mapper = call.getArgument(1);
              if (largeFacets && sql.startsWith("WITH facet_rows")) {
                var sentinel = mock(ResultSet.class);
                when(sentinel.getLong("projection_bytes")).thenReturn(Long.MAX_VALUE);
                when(sentinel.getString(anyString()))
                    .thenThrow(new AssertionError("Oversized aggregate text cannot be hydrated"));
                return List.of(mapper.mapRow(sentinel, 0));
              }
              return List.of(
                  mapper.mapRow(aggregate(0, null, null, null, 2), 0),
                  mapper.mapRow(aggregate(1, "tags", null, "tag", 2), 1),
                  mapper.mapRow(aggregate(2, null, "group", "tag", 2), 2));
            });
  }

  private Map<String, Object> revisionRow() {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", revision);
    row.put("resource_id", resource);
    row.put("ordinal", 1);
    row.put("title", "Retained title");
    row.put("media_type", "text/plain");
    row.put("document_type", "document");
    row.put("document_subtype", "text");
    row.put("content_hash", "source-hash");
    row.put("text_hash", "text-hash");
    row.put("byte_size", 20L);
    row.put("created_at", "2026-10-01T12:00:00Z");
    row.put("availability", "active");
    row.put("excluded", false);
    row.put("generation", 1);
    row.put("allowance_total", 10);
    row.put("allowance_spent", 0);
    row.put("source_name", "source.txt");
    row.put("kind", "source");
    row.put("author", "reader");
    row.put("tags", List.of("tag"));
    return row;
  }

  private static ResultSet aggregate(
      int kind, String facet, String category, String value, long count) throws Exception {
    var row = mock(ResultSet.class);
    when(row.getInt("row_kind")).thenReturn(kind);
    when(row.getString("facet_name")).thenReturn(facet);
    when(row.getString("category")).thenReturn(category);
    when(row.getString("value")).thenReturn(value);
    when(row.getLong("result_count")).thenReturn(count);
    when(row.getLong("projection_bytes")).thenReturn(500L);
    return row;
  }

  @Test
  void warmListAndFacetsReuseImmutableResultsWithoutAnyDiscoveryOrCandidatePreflight() {
    var page = repository.list(context, 20, 0, null);
    var counted = repository.facets(context, null, null);
    assertSame(page, repository.list(context, 20, 0, null));
    assertSame(counted, repository.facets(context, null, null));
    assertEquals(1, listSql.size());
    assertEquals(1, facetSql.size());
    assertTrue(listSql.getFirst().contains("catalogue_page AS MATERIALIZED"));
    assertTrue(listSql.getFirst().contains("ORDER BY r.created_at DESC,r.id LIMIT ? OFFSET ?"));
    assertTrue(
        listSql.getFirst().endsWith("ORDER BY catalogue_page.created_at DESC,catalogue_page.id"));
    assertTrue(facetSql.getFirst().contains("candidates AS MATERIALIZED"));
    assertTrue(
        facetSql
            .getFirst()
            .endsWith("ORDER BY row_kind,facet_name,result_count DESC,category,value"));
    assertThrows(UnsupportedOperationException.class, page::clear);
    assertThrows(UnsupportedOperationException.class, () -> page.getFirst().tags().clear());
    assertThrows(UnsupportedOperationException.class, () -> counted.facets().get("tags").clear());
    assertThrows(UnsupportedOperationException.class, () -> counted.tagGraph().edges().clear());
    verify(jdbc).queryForList(anyString(), any(Object[].class));
    verify(jdbc).query(anyString(), ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class));
    verifyNoMoreInteractions(jdbc);
  }

  @Test
  void listKeysIncludePaginationKindAndTheEntireTypedSelection() {
    repository.list(context, 20, 0, null);
    repository.list(context, 21, 0, null);
    repository.list(context, 20, 1, null);
    repository.list(context, 20, 0, "source");
    repository.list(context.withView(false), 20, 0, null);
    repository.list(context.withCorpus(InformationContext.Corpus.CODE), 20, 0, null);
    repository.list(
        context.withFacets(
            new InformationFacets(
                null, List.of("filter"), List.of(), null, null, null, null, null, null)),
        20,
        0,
        null);
    repository.list(context.within(KnowledgeReadScope.homeOnly(Home.of("one"))), 20, 0, null);
    repository.list(
        new InformationContext("other", context.selection()).withView(true), 20, 0, null);
    repository.list(
        new InformationContext("reader", InformationContext.Selection.project("one"))
            .withView(true),
        20,
        0,
        null);
    assertEquals(10, lists.values.size());
    assertEquals(10, lists.bounded);
    assertTrue(
        lists.values.keySet().stream().allMatch(key -> key.selection().matches("[a-f0-9]{64}")));
  }

  @Test
  void listClampsItsSqlPageBeforeBoundedHydrationAndRejectsInvalidRequestsBeforeCacheAccess() {
    repository.list(context, 1000, 5, null);
    Object[] arguments = listArguments.getFirst();
    assertEquals(100, arguments[arguments.length - 3]);
    assertEquals(5, arguments[arguments.length - 2]);
    assertThrows(CallerFault.class, () -> repository.list(context, 20, -1, null));
    assertThrows(CallerFault.class, () -> repository.list(context, 20, 0, "unknown"));
    assertEquals(1, lists.reads);
  }

  @Test
  void anEmptyPageUsesTheScalarSentinelAndCanBeReusedWithoutAFallbackQuery() {
    emptyPage = true;
    var page = repository.list(context, 20, 0, null);
    assertTrue(page.isEmpty());
    assertSame(page, repository.list(context, 20, 0, null));
    assertEquals(1, listSql.size());
    assertEquals(0, lists.fallback);
  }

  @Test
  void listSizeRefusalFallsBackOnceWithoutDecodingTheSentinelOrDroppingResults() {
    largePage = true;
    var page = repository.list(context, 20, 0, null);
    assertEquals(1, page.size());
    assertEquals(revision, page.getFirst().id());
    assertEquals(2, listSql.size());
    assertEquals(1, lists.fallback);
    assertTrue(lists.values.isEmpty());
    assertFalse(listSql.getLast().startsWith("WITH catalogue_page"));
  }

  @Test
  void facetKeysDistinguishNullAndEmptyPinsExactPinIdentitiesKindAndTypedView() {
    repository.facets(context, null, null);
    repository.facets(context, null, List.of());
    repository.facets(context, null, List.of(revision));
    repository.facets(context, null, List.of(resource));
    repository.facets(context, "source", null);
    repository.facets(context.withView(false), null, null);
    assertEquals(6, facets.values.size());
    assertEquals(6, facetSql.size());
    assertTrue(facetSql.get(1).contains("r.id=ANY(CAST(? AS uuid[]))"));
    assertTrue(Arrays.asList(facetArguments.get(1)).contains("{}"));
    assertTrue(Arrays.asList(facetArguments.get(2)).contains("{" + revision + "}"));
  }

  @Test
  void oversizedPinBookkeepingKeepsAllPinsInTheDirectSqlInsteadOfInventingAPartialKey() {
    repository.facets(context, null, Collections.nCopies(1025, revision));
    assertEquals(0, facets.reads);
    assertEquals(1, facetSql.size());
    assertFalse(facetSql.getFirst().startsWith("WITH facet_rows"));
    String pins = (String) facetArguments.getFirst()[facetArguments.getFirst().length - 1];
    assertEquals(1025, pins.split(",").length);
  }

  @Test
  void facetByteRefusalHappensBeforeTextMappingAndPreservesTheCompleteDirectResult() {
    largeFacets = true;
    var counted = repository.facets(context, null, null);
    assertEquals(2, counted.total());
    assertEquals(List.of(new Information.FacetCount("tag", 2)), counted.facets().get("tags"));
    assertEquals(List.of(new Information.TagEdge("group", "tag", 2)), counted.tagGraph().edges());
    assertEquals(2, facetSql.size());
    assertEquals(1, facets.fallback);
    assertTrue(facets.values.isEmpty());
    assertFalse(facetSql.getLast().startsWith("WITH facet_rows"));
  }

  @Test
  void sharedFacetHelperUsesTheSameBoundedReadForNavigationHeads() {
    var filter = new InformationSql.Filter("q.owner_handle=?", List.of("reader"));
    var helper = new JdbcInformationFacets(jdbc, facets);
    var key =
        InformationCatalogueFingerprint.readKey(
            "navigation-facets-v1", context, Arrays.asList(resource, "cites", "outgoing"));
    var counted = helper.read(filter, JdbcInformationFacets.Candidates.HEADS, true, key);
    assertSame(counted, helper.read(filter, JdbcInformationFacets.Candidates.HEADS, true, key));
    assertEquals(1, facetSql.size());
    assertTrue(facetSql.getFirst().contains("information_navigation_head(q.id)"));
    assertEquals("reader", facetArguments.getFirst()[0]);
  }

  @Test
  void databaseErrorsAreNotReplayedAsFallbacksOrCachedAsSuccessfulEmptyResults() {
    databaseFailure = true;
    assertThrows(
        DataAccessResourceFailureException.class, () -> repository.list(context, 20, 0, null));
    assertThrows(
        DataAccessResourceFailureException.class, () -> repository.facets(context, null, null));
    assertEquals(1, listSql.size());
    assertEquals(1, facetSql.size());
    assertEquals(0, lists.fallback);
    assertEquals(0, facets.fallback);
    assertTrue(lists.values.isEmpty());
    assertTrue(facets.values.isEmpty());
  }

  @Test
  void directConstructorsPreserveTheOriginalOneQueryReadContracts() {
    var direct = new JdbcInformationCatalogueRepository(jdbc, Clock.systemUTC(), allowances);
    assertEquals(1, direct.list(context, 20, 0, null).size());
    assertEquals(2, direct.facets(context, null, null).total());
    assertEquals(1, listSql.size());
    assertEquals(1, facetSql.size());
    assertFalse(listSql.getFirst().startsWith("WITH catalogue_page"));
    assertFalse(facetSql.getFirst().startsWith("WITH facet_rows"));
    assertEquals(0, lists.reads);
    assertEquals(0, facets.reads);
  }
}
