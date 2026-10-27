package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.KnowledgeReadScope;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.cache.*;
import io.aeyer.plowshare.server.embedding.*;
import io.aeyer.plowshare.server.information.*;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

/** Owning repository query budgets and per-reader audits with mocked JDBC and real cache reuse. */
class SearchCacheTest {
  private static final InformationContext CONTEXT =
      new InformationContext("alice", InformationContext.Selection.project("research"));
  private static final float[] VECTOR = {1, 0};

  @Test
  void warmHybridSearchAvoidsAllRankingAndCoverageQueriesButAuditsEveryCaller() throws Exception {
    try (var fixture = new Fixture()) {
      var firstAudit = new CopyOnWriteArrayList<UUID>();
      var secondAudit = new CopyOnWriteArrayList<UUID>();
      var first =
          fixture
              .reader(CONTEXT)
              .audited(firstAudit::add)
              .searchSnapshot("question", VECTOR, 4, DocumentStore.SearchProjection.HYBRID);
      var second =
          fixture
              .reader(CONTEXT)
              .audited(secondAudit::add)
              .searchSnapshot("question", VECTOR, 4, DocumentStore.SearchProjection.HYBRID);

      assertSame(first, second);
      assertEquals(2, fixture.rankSql.size());
      assertEquals(1, fixture.coverage.get());
      assertEquals(List.of(fixture.document, fixture.document), firstAudit);
      assertEquals(firstAudit, secondAudit);
      verify(fixture.access, times(6)).requireSelection(CONTEXT);
      verify(fixture.access, times(3)).admitted(CONTEXT);
    }
  }

  @Test
  void everyVisibilityInputVectorQuestionDepthProjectionAndProfileSeparatesSearchKeys()
      throws Exception {
    try (var fixture = new Fixture()) {
      fixture
          .reader(CONTEXT)
          .searchSnapshot("question", VECTOR, 4, DocumentStore.SearchProjection.VECTOR);
      fixture
          .reader(CONTEXT)
          .searchSnapshot("other", VECTOR, 4, DocumentStore.SearchProjection.VECTOR);
      fixture
          .reader(CONTEXT)
          .searchSnapshot("question", new float[] {0, 1}, 4, DocumentStore.SearchProjection.VECTOR);
      fixture
          .reader(CONTEXT)
          .searchSnapshot("question", VECTOR, 3, DocumentStore.SearchProjection.VECTOR);
      fixture
          .reader(CONTEXT)
          .searchSnapshot("question", VECTOR, 4, DocumentStore.SearchProjection.LEXICAL);
      fixture
          .reader(CONTEXT)
          .profiled(profile(1))
          .searchSnapshot("question", VECTOR, 4, DocumentStore.SearchProjection.VECTOR);
      fixture
          .reader(CONTEXT)
          .profiled(profile(2))
          .searchSnapshot("question", VECTOR, 4, DocumentStore.SearchProjection.VECTOR);
      var contexts =
          List.of(
              new InformationContext("bob", CONTEXT.selection()),
              new InformationContext("alice", InformationContext.Selection.personal()),
              new InformationContext("alice", InformationContext.Selection.project("other")),
              CONTEXT.withView(true),
              CONTEXT.withCorpus(InformationContext.Corpus.CODE),
              CONTEXT.within(KnowledgeReadScope.homeOnly(Home.of("research"))),
              CONTEXT.withFacets(
                  new InformationFacets(
                      "source", List.of("tag"), List.of(), null, null, null, null, null, null)));
      for (var context : contexts)
        fixture
            .reader(context)
            .searchSnapshot("question", VECTOR, 4, DocumentStore.SearchProjection.VECTOR);
      assertEquals(7 + contexts.size(), fixture.rankSql.size());
      assertEquals(7 + contexts.size(), fixture.coverage.get());
      fixture
          .reader(CONTEXT)
          .searchSnapshot("question", VECTOR, 4, DocumentStore.SearchProjection.VECTOR);
      assertEquals(7 + contexts.size(), fixture.rankSql.size(), "Original selection remains a hit");
    }
  }

  @Test
  void loaderUsesCapturedVectorEvenIfCallerArrayChangesDuringCoverageRead() throws Exception {
    try (var fixture = new Fixture()) {
      float[] caller = {1, 0};
      fixture.duringCoverage = () -> caller[0] = 99;
      fixture
          .reader(CONTEXT)
          .searchSnapshot("question", caller, 4, DocumentStore.SearchProjection.VECTOR);
      assertEquals(List.of("[1.0,0.0]"), fixture.vectors);
      fixture
          .reader(CONTEXT)
          .searchSnapshot("question", new float[] {1, 0}, 4, DocumentStore.SearchProjection.VECTOR);
      assertEquals(1, fixture.rankSql.size(), "The captured exact vector also owns the cache key");
    }
  }

  @Test
  void oversizedBoundedRowFallsBackBeforeHydratingTextAndAuditsOnlyActualReturnedRows()
      throws Exception {
    try (var fixture = new Fixture()) {
      fixture.oversized.set(true);
      var audit = new CopyOnWriteArrayList<UUID>();
      var result =
          fixture
              .reader(CONTEXT)
              .audited(audit::add)
              .searchSnapshot("question", VECTOR, 4, DocumentStore.SearchProjection.VECTOR);

      assertEquals(1, result.vectorHits().size());
      assertEquals(
          2,
          fixture.rankSql.size(),
          "Bounded projection fails once, ordinary projection runs once");
      assertEquals(2, fixture.coverage.get());
      assertEquals(List.of(fixture.document), audit);
      verify(fixture.oversizedRow).getBoolean("cache_oversized");
      verify(fixture.oversizedRow, never()).getString(anyString());
      verify(fixture.oversizedRow, never()).getObject(anyString());
      assertTrue(fixture.rankSql.getFirst().contains("cache_oversized"));
      assertTrue(fixture.rankSql.getFirst().contains("CASE WHEN length(c.text)"));
      assertTrue(fixture.rankSql.getFirst().contains("ORDER BY distance"));
      assertFalse(fixture.rankSql.getLast().contains("cache_oversized"));
    }
  }

  @Test
  void warmHitStillEnforcesCurrentSelectionBeforeDisclosureAndNeverAuditsARejectedCaller()
      throws Exception {
    try (var fixture = new Fixture()) {
      fixture
          .reader(CONTEXT)
          .searchSnapshot("question", VECTOR, 4, DocumentStore.SearchProjection.VECTOR);
      var reader = fixture.reader(CONTEXT);
      var audit = new CopyOnWriteArrayList<UUID>();
      doThrow(new IllegalStateException("Selection revoked"))
          .when(fixture.access)
          .requireSelection(CONTEXT);
      assertThrows(
          IllegalStateException.class,
          () ->
              reader
                  .audited(audit::add)
                  .searchSnapshot("question", VECTOR, 4, DocumentStore.SearchProjection.VECTOR));
      assertTrue(audit.isEmpty());
      assertEquals(1, fixture.rankSql.size());
    }
  }

  private static EmbeddingProfile profile(long version) {
    return new EmbeddingProfile(
        EmbeddingSlot.PROSE,
        new EmbeddingSpace(
            "a".repeat(64),
            new EmbeddingSpace.Definition(
                "model", "revision", 2, "", "", "mean", EmbeddingSpace.Normalization.NONE, "none")),
        new EmbeddingSearchPolicy(
            EmbeddingSearchPolicy.Mode.EXACT, EmbeddingSearchPolicy.Distance.COSINE),
        version);
  }

  private static final class Fixture implements AutoCloseable {
    final UUID document = UUID.randomUUID();
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final InformationAccess access = mock(InformationAccess.class);
    final AtomicInteger coverage = new AtomicInteger();
    final AtomicBoolean oversized = new AtomicBoolean();
    final CopyOnWriteArrayList<String> rankSql = new CopyOnWriteArrayList<>();
    final CopyOnWriteArrayList<String> vectors = new CopyOnWriteArrayList<>();
    final ResultSet oversizedRow = mock(ResultSet.class);
    final ProjectionCaches caches;
    final DocumentStore store;
    Runnable duringCoverage = () -> {};

    Fixture() throws Exception {
      var versions = mock(ProjectionVersions.class);
      var token =
          new ProjectionVersions.Token(
              new UUID(0, 1),
              Map.of(ProjectionVersions.Domain.SEARCH, 1L, ProjectionVersions.Domain.AUTHORITY, 1L),
              List.of(),
              "999999",
              null,
              null);
      when(versions.databaseIdentity()).thenReturn(token.database());
      when(versions.capture(anySet())).thenReturn(Optional.of(token));
      when(versions.canShare()).thenReturn(true);
      when(versions.current(any())).thenReturn(true);
      ProjectionChangeSignals signals =
          new ProjectionChangeSignals() {
            public Subscription subscribe(
                Set<ProjectionVersions.Domain> domains, Observer observer) {
              observer.stateChanged(new State(1, true));
              return () -> {};
            }

            public void start() {}

            public boolean healthy() {
              return true;
            }

            public void close() {}
          };
      var properties =
          new ProjectionCacheProperties(
              32,
              8_000_000,
              4,
              8_000_000,
              16,
              8,
              128,
              2_000_000,
              Duration.ofSeconds(30),
              Duration.ofSeconds(1),
              Duration.ofSeconds(1),
              32,
              1,
              2);
      caches = new ProjectionCaches(versions, signals, properties);
      store =
          new DocumentStore(
              jdbc,
              UnitOfWork.NONE,
              caches.create(
                  "search",
                  Set.of(ProjectionVersions.Domain.SEARCH, ProjectionVersions.Domain.AUTHORITY)));
      when(access.admitted(any())).thenAnswer(invocation -> invocation.getArgument(0));
      var counts = mock(ResultSet.class);
      when(counts.next()).thenReturn(true);
      when(counts.getInt("searchable")).thenReturn(1);
      when(counts.getInt("unsearchable")).thenReturn(0);
      doAnswer(
              invocation -> {
                coverage.incrementAndGet();
                duringCoverage.run();
                ResultSetExtractor<DocumentStore.Coverage> extractor = invocation.getArgument(1);
                return extractor.extractData(counts);
              })
          .when(jdbc)
          .query(
              anyString(),
              org.mockito.ArgumentMatchers.<ResultSetExtractor<DocumentStore.Coverage>>any(),
              any(Object[].class));
      var row = mock(ResultSet.class);
      when(row.getObject("chunk_id")).thenReturn(UUID.randomUUID());
      when(row.getObject("paragraph_id")).thenReturn(UUID.randomUUID());
      when(row.getObject("document_id")).thenReturn(document);
      when(row.getString(anyString())).thenReturn("bounded text");
      when(row.getDouble("distance")).thenReturn(0.1);
      when(oversizedRow.getBoolean("cache_oversized")).thenReturn(true);
      doAnswer(
              invocation -> {
                String sql = invocation.getArgument(0);
                rankSql.add(sql);
                assertTrue(sql.contains("knowledge_readable_selection"));
                assertFalse(sql.contains("information_readable("));
                Object[] arguments = (Object[]) invocation.getRawArguments()[2];
                vectors.add(arguments[sql.startsWith("WITH cache_bounds") ? 1 : 0].toString());
                RowMapper<DocumentStore.Hit> mapper = invocation.getArgument(1);
                return List.of(
                    mapper.mapRow(
                        oversized.get() && sql.contains("cache_oversized") ? oversizedRow : row,
                        0));
              })
          .when(jdbc)
          .query(
              anyString(),
              org.mockito.ArgumentMatchers.<RowMapper<DocumentStore.Hit>>any(),
              any(Object[].class));
    }

    DocumentStore reader(InformationContext context) {
      return store.scoped(access, context);
    }

    public void close() {
      caches.close();
    }
  }
}
