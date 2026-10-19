package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.allowances.AllowanceRepository;
import io.aeyer.plowshare.server.cache.ProjectionCache;
import io.aeyer.plowshare.server.cache.SnapshotCache;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Owning projection guards and hit reuse; infrastructure independently tests durable fences. */
class InformationProjectionCacheTest {
  final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  final RecordingCache<Optional<InformationCatalogueRepository.AuthorizedMetadata>> metadata =
      new RecordingCache<>();
  final RecordingCache<Optional<InformationCatalogueRepository.AuthorizedText>> text =
      new RecordingCache<>();
  final JdbcInformationCatalogueRepository repository =
      new JdbcInformationCatalogueRepository(
          jdbc, Clock.systemUTC(), mock(AllowanceRepository.class), metadata, text);
  final InformationContext context =
      new InformationContext("reader", InformationContext.Selection.personal()).withView(true);
  final UUID revision = UUID.randomUUID(), resource = UUID.randomUUID();
  final List<String> scalarSql = new ArrayList<>(),
      metadataSql = new ArrayList<>(),
      textSql = new ArrayList<>();
  boolean readable = true, metadataGrew, textGrew;
  long textBytes = 100;

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
      T loaded;
      try {
        bounded++;
        loaded = loader.get();
      } catch (ProjectionCache.Bypass oversized) {
        ordinary++;
        return fallback.get();
      }
      long charged = weight.applyAsLong(loaded);
      assertTrue(charged > 0);
      if (charged <= maxWeight()) values.put(key, loaded);
      return loaded;
    }
  }

  @BeforeEach
  void setup() throws Exception {
    when(jdbc.queryForList(anyString(), eq(Integer.class), any(Object[].class)))
        .thenAnswer(
            call -> {
              String sql = call.getArgument(0);
              scalarSql.add(sql);
              assertTrue(sql.startsWith("SELECT 1 FROM information_revision_allowances r WHERE "));
              assertFalse(sql.contains("auto_tag"));
              assertFalse(sql.contains("extracted_text"));
              return readable ? List.of(1) : List.of();
            });
    when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
        .thenAnswer(
            call -> {
              String sql = call.getArgument(0);
              scalarSql.add(sql);
              assertTrue(sql.contains(InformationSql.read(context, "r").sql().split(" AND ")[0]));
              return readable ? List.of(textBytes) : List.of();
            });
    when(jdbc.queryForList(anyString(), any(Object[].class)))
        .thenAnswer(
            call -> {
              String sql = call.getArgument(0);
              metadataSql.add(sql);
              if (metadataGrew && sql.startsWith("WITH projected")) return List.of();
              return List.of(metadataRow());
            });
    when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
        .thenAnswer(
            call -> {
              String sql = call.getArgument(0);
              textSql.add(sql);
              if (textGrew && sql.contains("octet_length")) return List.of();
              RowMapper<Object> mapper = call.getArgument(1);
              return List.of(mapper.mapRow(textRow(), 0));
            });
  }

  private Map<String, Object> metadataRow() {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", revision);
    row.put("resource_id", resource);
    row.put("ordinal", 1);
    row.put("title", "Retained title");
    row.put("media_type", "text/plain");
    row.put("document_type", "document");
    row.put("document_subtype", "text");
    row.put("content_hash", "legacy-source");
    row.put("text_hash", "legacy-text");
    row.put("byte_size", 20);
    row.put("created_at", "2026-10-01T12:00:00Z");
    row.put("availability", "active");
    row.put("excluded", false);
    row.put("generation", 1);
    row.put("allowance_total", 10);
    row.put("allowance_spent", 0);
    row.put("source_name", "source.txt");
    row.put("kind", "source");
    row.put("author", "reader");
    row.put("tags", List.of("one"));
    return row;
  }

  private ResultSet textRow() throws Exception {
    var row = mock(ResultSet.class);
    when(row.getObject(1, UUID.class)).thenReturn(revision);
    when(row.getObject(2, UUID.class)).thenReturn(resource);
    when(row.getString(3)).thenReturn("document");
    when(row.getString(4)).thenReturn("text");
    when(row.getString(5)).thenReturn("legacy-source");
    when(row.getString(6)).thenReturn("legacy-text");
    when(row.getLong(7)).thenReturn(1L);
    when(row.getString(8)).thenReturn("retained text");
    return row;
  }

  @Test
  void warmDocumentReadsRetainTheirLivePreflightButSkipProjectionHydration() {
    var firstMetadata = repository.authorizedMetadata(context, revision);
    var firstText = repository.authorizedText(context, revision);
    assertSame(firstMetadata, repository.authorizedMetadata(context, revision));
    assertSame(firstText, repository.authorizedText(context, revision));
    assertEquals(4, scalarSql.size());
    assertEquals(1, metadataSql.size());
    assertEquals(1, textSql.size());
    assertTrue(metadataSql.getFirst().contains("MATERIALIZED"));
    assertTrue(metadataSql.getFirst().contains("octet_length(to_jsonb(projected)::text)<=?"));
    assertTrue(textSql.getFirst().contains("octet_length(r.extracted_text)"));
    assertEquals(1, metadata.bounded);
    assertEquals(1, text.bounded);
    assertEquals(context, firstText.orElseThrow().context());
  }

  @Test
  void revokedReadabilityDoesNotExposeAPreviouslyCachedSnapshot() {
    repository.authorizedMetadata(context, revision);
    repository.authorizedText(context, revision);
    readable = false;
    assertTrue(repository.authorizedMetadata(context, revision).isEmpty());
    assertTrue(repository.authorizedText(context, revision).isEmpty());
    assertEquals(1, metadata.reads);
    assertEquals(1, text.reads);
    assertEquals(1, metadataSql.size());
    assertEquals(1, textSql.size());
  }

  @Test
  void oversizePreflightBypassesWithoutRunningABoundedHydrationFirst() {
    textBytes = Long.MAX_VALUE;
    assertEquals(
        "retained text", repository.authorizedText(context, revision).orElseThrow().text());
    assertFalse(text.lastCacheable);
    assertEquals(0, text.bounded);
    assertEquals(1, text.ordinary);
    assertFalse(textSql.getFirst().contains("octet_length"));
    assertTrue(text.values.isEmpty());
  }

  @Test
  void growthAfterPreflightFallsBackOnceWithoutCachingAFalseNegativeOrTruncation() {
    metadataGrew = textGrew = true;
    assertTrue(repository.authorizedMetadata(context, revision).isPresent());
    assertEquals(
        "retained text", repository.authorizedText(context, revision).orElseThrow().text());
    assertEquals(2, metadataSql.size());
    assertEquals(2, textSql.size());
    assertEquals(1, metadata.ordinary);
    assertEquals(1, text.ordinary);
    assertTrue(metadata.values.isEmpty());
    assertTrue(text.values.isEmpty());
  }

  @Test
  void exactContextAndRevisionIdentitiesCannotCollideAcrossReads() {
    var changed =
        context.withFacets(
            new InformationFacets(
                null, List.of("changed"), List.of(), null, null, null, null, null, null));
    repository.authorizedText(context, revision);
    repository.authorizedText(changed, revision);
    repository.authorizedText(context.withView(false), revision);
    repository.authorizedText(
        new InformationContext("other", context.selection()).withView(true), revision);
    repository.authorizedText(
        context.within(
            io.aeyer.plowshare.server.access.KnowledgeReadScope.homeOnly(
                io.aeyer.plowshare.protocol.Home.of("one"))),
        revision);
    repository.authorizedText(context, UUID.randomUUID());
    assertEquals(6, text.values.size());
    assertEquals(6, text.bounded);
    assertTrue(
        text.values.keySet().stream().allMatch(key -> key.selection().matches("[a-f0-9]{64}")));
  }

  @Test
  void oldConstructorsKeepTheirSingleDirectReadAndNeedNoCacheInfrastructure() {
    var direct =
        new JdbcInformationCatalogueRepository(
            jdbc, Clock.systemUTC(), mock(AllowanceRepository.class));
    assertTrue(direct.authorizedMetadata(context, revision).isPresent());
    assertTrue(direct.authorizedText(context, revision).isPresent());
    assertTrue(scalarSql.isEmpty());
    assertEquals(1, metadataSql.size());
    assertEquals(1, textSql.size());
  }
}
