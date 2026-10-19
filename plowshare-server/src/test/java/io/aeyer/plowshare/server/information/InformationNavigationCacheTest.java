package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.InformationNavigation.Document;
import io.aeyer.plowshare.protocol.InformationNavigation.Documents;
import io.aeyer.plowshare.server.access.KnowledgeReadScope;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.cache.*;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Repository-owned metadata reuse and SQL budgets; authority/transaction SQL needs DB tests. */
class InformationNavigationCacheTest {
  private static final InformationContext CONTEXT =
      new InformationContext("alice", InformationContext.Selection.project("research"))
          .withView(true);

  @Test
  void warmPageReusesImmutableMetadataAndRetainsTheOriginalPageOrdering() throws Exception {
    try (var fixture = new Fixture(2_000_000)) {
      var first = fixture.repository.documents(CONTEXT, null, null, null, 2, 0);
      var second = fixture.repository.documents(CONTEXT, null, null, null, 2, 0);
      assertSame(first, second);
      assertEquals(1, fixture.sql.size());
      assertEquals("Manual", first.documents().getFirst().title());
      assertThrows(UnsupportedOperationException.class, () -> first.documents().clear());
      assertThrows(
          UnsupportedOperationException.class, () -> first.documents().getFirst().tags().clear());
      assertTrue(fixture.sql.getFirst().contains("navigation_page AS MATERIALIZED"));
      assertTrue(
          fixture.sql.getFirst().contains("ORDER BY r.created_at DESC,q.id LIMIT ? OFFSET ?"));
      assertTrue(fixture.sql.getFirst().contains("ORDER BY p.sort_created_at DESC,p.resource"));
      verify(fixture.versions, times(2)).capture(anySet());
    }
  }

  @Test
  void identityContainsEverySelectionTraversalAndPaginationInput() throws Exception {
    try (var fixture = new Fixture(2_000_000)) {
      fixture.repository.documents(CONTEXT, null, null, null, 2, 0);
      var parent = UUID.randomUUID();
      fixture.repository.documents(CONTEXT, parent, null, null, 2, 0);
      fixture.repository.documents(CONTEXT, parent, "runbook", null, 2, 0);
      fixture.repository.documents(CONTEXT, parent, "runbook", "backlinks", 2, 0);
      fixture.repository.documents(CONTEXT, parent, "runbook", "outgoing", 2, 0);
      fixture.repository.documents(CONTEXT, UUID.randomUUID(), "runbook", "outgoing", 2, 0);
      fixture.repository.documents(CONTEXT, null, null, null, 1, 0);
      fixture.repository.documents(CONTEXT, null, null, null, 2, 1);
      var contexts =
          List.of(
              new InformationContext("bob", CONTEXT.selection()).withView(true),
              new InformationContext("alice", InformationContext.Selection.personal())
                  .withView(true),
              new InformationContext("alice", InformationContext.Selection.project("other"))
                  .withView(true),
              CONTEXT.withView(false),
              CONTEXT.withCorpus(InformationContext.Corpus.CODE),
              CONTEXT.within(KnowledgeReadScope.homeOnly(Home.of("research"))),
              CONTEXT.withFacets(
                  new InformationFacets(
                      "source", List.of("manual"), List.of(), null, null, null, null, null, null)));
      for (var context : contexts) fixture.repository.documents(context, null, null, null, 2, 0);
      assertEquals(8 + contexts.size(), fixture.sql.size());
      fixture.repository.documents(CONTEXT, null, null, null, 2, 0);
      assertEquals(8 + contexts.size(), fixture.sql.size());
    }
  }

  @Test
  void currentDurableDomainVersionRejectsWarmMetadataWithoutNotificationHints() throws Exception {
    try (var fixture = new Fixture(2_000_000)) {
      assertEquals(
          "Manual",
          fixture
              .repository
              .documents(CONTEXT, null, null, null, 2, 0)
              .documents()
              .getFirst()
              .title());
      fixture.title.set("Revised manual");
      fixture.token.set(token(2));
      assertEquals(
          "Revised manual",
          fixture
              .repository
              .documents(CONTEXT, null, null, null, 2, 0)
              .documents()
              .getFirst()
              .title());
      assertEquals(2, fixture.sql.size());
    }
  }

  @Test
  void unsafeHistoricalOrDirtyCaptureUsesOrdinaryProjectionEveryTime() throws Exception {
    try (var fixture = new Fixture(2_000_000)) {
      fixture.safe.set(false);
      fixture.repository.documents(CONTEXT, null, null, null, 2, 0);
      fixture.repository.documents(CONTEXT, null, null, null, 2, 0);
      assertEquals(2, fixture.sql.size());
      assertTrue(fixture.sql.stream().noneMatch(sql -> sql.contains("cache_oversized")));
      fixture.repository.snapshotSingleProjection();
      fixture.repository.snapshot();
      verify(fixture.jdbc).execute("SET TRANSACTION READ ONLY");
      verify(fixture.jdbc).execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY");
    }
  }

  @Test
  void oversizedRowsFallBackBeforeHydratingTagsOrTextAndAreNeverRetained() throws Exception {
    try (var fixture = new Fixture(2_000_000)) {
      fixture.oversized.set(true);
      for (int attempt = 0; attempt < 2; attempt++)
        assertEquals(
            "Manual",
            fixture
                .repository
                .documents(CONTEXT, null, null, null, 2, 0)
                .documents()
                .getFirst()
                .title());
      assertEquals(4, fixture.sql.size());
      verify(fixture.oversizedRow, times(2)).getBoolean("cache_oversized");
      verify(fixture.oversizedRow, never()).getString(anyString());
      verify(fixture.oversizedRow, never()).getObject(anyString(), eq(UUID.class));
      assertFalse(fixture.sql.get(1).contains("cache_oversized"));
    }
  }

  @Test
  void pageThatCannotFitConfiguredBudgetBypassesBeforeVersionOrBoundedProjectionWork()
      throws Exception {
    try (var fixture = new Fixture(1_000)) {
      fixture.repository.documents(CONTEXT, null, null, null, 2, 0);
      assertEquals(1, fixture.sql.size());
      assertFalse(fixture.sql.getFirst().contains("cache_oversized"));
      verify(fixture.versions, never()).capture(anySet());
    }
  }

  @Test
  void relatedReadChecksCandidateDependenciesOnceAndParentGuardOnceWithExactBinding()
      throws Exception {
    try (var fixture = new Fixture(2_000_000)) {
      var direct =
          new JdbcInformationNavigationRepository(fixture.jdbc, mock(ProjectMembers.class));
      var context = CONTEXT.within(KnowledgeReadScope.homeOnly(Home.of("research")));
      var parent = UUID.randomUUID();
      direct.documents(context, parent, "runbook", "outgoing", 2, 5);
      String sql = fixture.sql.getFirst();
      assertEquals(1, occurrences(sql, "knowledge_dependencies_readable(r.id,?)"));
      assertFalse(sql.contains("information_navigation_own_readable(r.id"));
      assertFalse(sql.contains("information_reference_readable(r.id"));
      assertEquals(
          1, occurrences(sql, "(SELECT information_readable(information_navigation_head(?)"));
      assertTrue(
          sql.contains(
              "AND knowledge_document_in_boundary(information_navigation_head(?),?,?,?)) AND EXISTS"));
      assertTrue(sql.contains("e.source=? AND e.target=q.id AND e.relation=?"));
      assertEquals(
          List.of(
              "alice",
              "alice",
              "research",
              false,
              parent,
              "alice",
              "project",
              "research",
              true,
              parent,
              "alice",
              "research",
              false,
              parent,
              "runbook",
              2,
              5),
          fixture.arguments.getFirst());
      direct.documents(context, parent, "runbook", "backlinks", 2, 5);
      assertTrue(fixture.sql.getLast().contains("e.target=? AND e.source=q.id AND e.relation=?"));
    }
  }

  @Test
  void invalidPageAndTraversalInputsPerformNoProjectionOrCacheProbe() throws Exception {
    try (var fixture = new Fixture(2_000_000)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.repository.documents(CONTEXT, null, null, null, 0, 0));
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.repository.documents(CONTEXT, null, null, null, 101, 0));
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.repository.documents(CONTEXT, null, null, null, 1, -1));
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.repository.documents(CONTEXT, null, "runbook", null, 1, 0));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              fixture.repository.documents(CONTEXT, UUID.randomUUID(), "invalid name", null, 1, 0));
      assertThrows(
          IllegalArgumentException.class,
          () -> fixture.repository.documents(CONTEXT, UUID.randomUUID(), null, "invalid", 1, 0));
      assertTrue(fixture.sql.isEmpty());
      verify(fixture.versions, never()).capture(anySet());
    }
  }

  private static int occurrences(String sql, String fragment) {
    return (sql.length() - sql.replace(fragment, "").length()) / fragment.length();
  }

  private static ProjectionVersions.Token token(long documents) {
    return new ProjectionVersions.Token(
        new UUID(0, 1),
        Map.of(
            ProjectionVersions.Domain.DOCUMENTS,
            documents,
            ProjectionVersions.Domain.AUTHORITY,
            1L),
        List.of(),
        null,
        null);
  }

  private static final class Fixture implements AutoCloseable {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final ProjectionVersions versions = mock(ProjectionVersions.class);
    final AtomicReference<ProjectionVersions.Token> token = new AtomicReference<>(token(1));
    final AtomicBoolean safe = new AtomicBoolean(true);
    final AtomicBoolean oversized = new AtomicBoolean();
    final AtomicReference<String> title = new AtomicReference<>("Manual");
    final CopyOnWriteArrayList<String> sql = new CopyOnWriteArrayList<>();
    final CopyOnWriteArrayList<List<Object>> arguments = new CopyOnWriteArrayList<>();
    final ResultSet oversizedRow = mock(ResultSet.class);
    final ProjectionCaches caches;
    final JdbcInformationNavigationRepository repository;

    Fixture(long maxValue) throws Exception {
      when(versions.databaseIdentity()).thenReturn(token.get().database());
      when(versions.capture(anySet()))
          .thenAnswer(invocation -> safe.get() ? Optional.of(token.get()) : Optional.empty());
      when(versions.canShare()).thenAnswer(invocation -> safe.get());
      when(versions.current(any()))
          .thenAnswer(
              invocation ->
                  token
                      .get()
                      .sourceVersion()
                      .equals(
                          ((ProjectionVersions.Token) invocation.getArgument(0)).sourceVersion()));
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
      caches =
          new ProjectionCaches(
              versions,
              signals,
              new ProjectionCacheProperties(
                  32,
                  8_000_000,
                  4,
                  8_000_000,
                  16,
                  8,
                  128,
                  maxValue,
                  Duration.ofSeconds(30),
                  Duration.ofSeconds(1),
                  Duration.ofSeconds(1),
                  32,
                  1,
                  2));
      ProjectionCache<Documents> cache =
          caches.create(
              "navigation-documents",
              Set.of(ProjectionVersions.Domain.DOCUMENTS, ProjectionVersions.Domain.AUTHORITY));
      repository =
          new JdbcInformationNavigationRepository(
              jdbc, mock(ProjectMembers.class), cache, ProjectionCache.direct());
      var row = mock(ResultSet.class);
      when(row.getObject("resource", UUID.class)).thenReturn(UUID.randomUUID());
      when(row.getObject("revision", UUID.class)).thenReturn(UUID.randomUUID());
      when(row.getString("title")).thenAnswer(invocation -> title.get());
      when(row.getString("source_name")).thenReturn("manual.md");
      when(row.getString("owner")).thenReturn("alice");
      when(row.getString("tags")).thenReturn("[\"manual\"]");
      when(row.getString("visibility")).thenReturn("home");
      when(row.getString("project_name")).thenReturn("research");
      when(oversizedRow.getBoolean("cache_oversized")).thenReturn(true);
      doAnswer(
              invocation -> {
                String query = invocation.getArgument(0);
                sql.add(query);
                Object[] bound = (Object[]) invocation.getRawArguments()[2];
                arguments.add(Arrays.asList(bound.clone()));
                RowMapper<Document> mapper = invocation.getArgument(1);
                return List.of(
                    mapper.mapRow(
                        oversized.get() && query.contains("cache_oversized") ? oversizedRow : row,
                        0));
              })
          .when(jdbc)
          .query(
              anyString(),
              org.mockito.ArgumentMatchers.<RowMapper<Document>>any(),
              any(Object[].class));
    }

    public void close() {
      caches.close();
    }
  }
}
