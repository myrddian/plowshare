package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.KnowledgeReadScope;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.embedding.*;
import io.aeyer.plowshare.server.information.*;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.EmbeddingException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Query budgets and freshness boundaries without a database or provider. */
class SearchSnapshotTest {
  private final InformationContext context =
      new InformationContext("alice", InformationContext.Selection.project("research"))
          .within(KnowledgeReadScope.homeOnly(Home.of("research")));
  private final EmbeddingProfile profile =
      new EmbeddingProfile(
          EmbeddingSlot.PROSE,
          new EmbeddingSpace(
              "a".repeat(64),
              new EmbeddingSpace.Definition(
                  "test-model",
                  "revision-1",
                  2,
                  "",
                  "",
                  "mean",
                  EmbeddingSpace.Normalization.NONE,
                  "none")),
          new EmbeddingSearchPolicy(
              EmbeddingSearchPolicy.Mode.EXACT, EmbeddingSearchPolicy.Distance.COSINE),
          7);

  @Test
  void admittedDualSearchReadsCoverageTwiceAndEachHybridProjectionOnce() {
    var fixture = new Search();
    var found = fixture.service.search("question", 5);

    assertEquals(List.of(fixture.hit), found.hits());
    assertEquals(new DocumentStore.Coverage(12, 0), found.coverage());
    verify(fixture.store, times(2)).coverage();
    verify(fixture.store).searchByVector(any(), eq(RetrievalService.CANDIDATE_DEPTH));
    verify(fixture.store).searchByText(eq("question"), any(), eq(RetrievalService.CANDIDATE_DEPTH));
    verify(fixture.dual).queries(eq(profile), eq(List.of("question")), any());
    verify(fixture.read).disclose(any());
    verify(fixture.read).close();
    verify(fixture.embeddings, never()).embed(anyString());
  }

  @Test
  void providerChangesAndDisclosureLockWaitCannotReusePreflightCoverage() {
    var fixture = new Search();
    var coverage = new AtomicReference<>(new DocumentStore.Coverage(12, 0));
    var hits = new AtomicReference<>(List.of(fixture.hit));
    when(fixture.store.coverage()).thenAnswer(invocation -> coverage.get());
    when(fixture.store.searchByVector(any(), anyInt())).thenAnswer(invocation -> hits.get());
    when(fixture.store.searchByText(any(), any(), anyInt())).thenAnswer(invocation -> hits.get());
    doAnswer(
            invocation -> {
              // A source changed during actual provider IO.
              coverage.set(new DocumentStore.Coverage(4, 8));
              return List.of(new EmbeddingQuery(profile, new float[] {1, 0}));
            })
        .when(fixture.dual)
        .queries(eq(profile), any(), any());
    doAnswer(
            invocation -> {
              assertEquals(new DocumentStore.Coverage(4, 8), coverage.get());
              // A second edit commits while the disclosure fence is acquiring its locks.
              coverage.set(new DocumentStore.Coverage(0, 12));
              hits.set(List.of());
              Supplier<?> work = invocation.getArgument(0);
              return work.get();
            })
        .when(fixture.read)
        .disclose(any());

    var found = fixture.service.search("question", 5);

    assertEquals(new DocumentStore.Coverage(0, 12), found.coverage());
    assertTrue(found.hits().isEmpty());
    verify(fixture.store, times(2)).coverage();
    verify(fixture.store, never()).searchByVector(any(), anyInt());
    verify(fixture.store, never()).searchByText(any(), any(), anyInt());
  }

  @Test
  void sourceUpdateDuringProviderIoIsReflectedByFinalCountsAndCandidates() {
    var fixture = new Search();
    var coverage = new AtomicReference<>(new DocumentStore.Coverage(12, 0));
    var candidates = new AtomicReference<>(List.of(fixture.hit));
    var updated = hit();
    when(fixture.store.coverage()).thenAnswer(invocation -> coverage.get());
    when(fixture.store.searchByVector(any(), anyInt())).thenAnswer(invocation -> candidates.get());
    when(fixture.store.searchByText(any(), any(), anyInt()))
        .thenAnswer(invocation -> candidates.get());
    fixture.duringProvider =
        () -> {
          coverage.set(new DocumentStore.Coverage(5, 7));
          candidates.set(List.of(updated));
        };

    var found = fixture.service.search("question", 5);

    assertEquals(new DocumentStore.Coverage(5, 7), found.coverage());
    assertEquals(List.of(updated), found.hits());
    verify(fixture.store, times(2)).coverage();
  }

  @Test
  void revokedDisclosureNeverReadsCandidatesOrReusesPreflightAsAnAnswer() {
    var fixture = new Search();
    doThrow(new IllegalStateException("Read revoked")).when(fixture.read).disclose(any());

    assertThrows(IllegalStateException.class, () -> fixture.service.search("question", 5));

    verify(fixture.store).coverage();
    verify(fixture.store, never()).searchByVector(any(), anyInt());
    verify(fixture.store, never()).searchByText(any(), any(), anyInt());
    verify(fixture.read).close();
  }

  @Test
  void profileActivationDuringProviderIoFailsInsteadOfSearchingAnotherSpace() {
    var fixture = new Search();
    var fences = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (fences.incrementAndGet() == 2)
                throw new EmbeddingException("embedding configuration changed");
              Supplier<?> work = invocation.getArgument(1);
              return work.get();
            })
        .when(fixture.dual)
        .read(eq(profile), any());

    assertThrows(EmbeddingException.class, () -> fixture.service.search("question", 5));

    verify(fixture.store).coverage();
    verify(fixture.store, never()).searchByVector(any(), anyInt());
    verify(fixture.read, never()).disclose(any());
    verify(fixture.read).close();
  }

  @Test
  void emptyDualPreflightDoesNotDispatchOrReadCandidates() {
    var fixture = new Search();
    when(fixture.store.coverage()).thenReturn(new DocumentStore.Coverage(0, 3));
    doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get())
        .when(fixture.read)
        .disclose(any());

    var found = fixture.service.search("question", 5);

    assertTrue(found.hits().isEmpty());
    assertEquals(3, found.unsearchable());
    verify(fixture.store).coverage();
    verify(fixture.dual, never()).queries(any(), any(), any());
    verify(fixture.store, never()).searchByVector(any(), anyInt());
    verify(fixture.read).disclose(any());
  }

  @Test
  void singleProjectionDoesNotFetchTheOtherCandidates() {
    var fixture = new Search();
    var found = fixture.service.search("question", 3, RetrievalService.Mode.LEXICAL);

    assertEquals(List.of(fixture.hit), found.hits());
    verify(fixture.store, times(2)).coverage();
    verify(fixture.store, never()).searchByVector(any(), anyInt());
    verify(fixture.store).searchByText(eq("question"), any(), eq(3));
  }

  @Test
  void snapshotCarriesOwningSelectionAndActualProfileWithoutInventingSourceVersions() {
    var access = mock(InformationAccess.class);
    var selected =
        context.withFacets(
            new InformationFacets(
                "source", List.of("research"), List.of(), null, null, null, null, null, null));
    var store =
        spy(
            new DocumentStore(mock(JdbcTemplate.class), UnitOfWork.NONE)
                .scoped(access, selected)
                .profiled(profile));
    var hit = hit();
    var mutable = new ArrayList<>(List.of(hit));
    doReturn(mutable).when(store).searchByVector(any(), anyInt());
    doReturn(List.of(hit)).when(store).searchByText(any(), any(), anyInt());

    var snapshot =
        store.searchSnapshot(
            "question",
            new float[] {1, 0},
            4,
            DocumentStore.SearchProjection.HYBRID,
            new DocumentStore.Coverage(3, 1));
    mutable.clear();

    assertEquals(selected, snapshot.context().orElseThrow());
    assertEquals(profile, snapshot.profile().orElseThrow());
    assertEquals(7, snapshot.profile().orElseThrow().version());
    assertTrue(snapshot.legacyFingerprint().isEmpty());
    assertEquals("question", snapshot.question());
    assertEquals(4, snapshot.depth());
    assertEquals(DocumentStore.SearchProjection.HYBRID, snapshot.projection());
    assertEquals(List.of(hit), snapshot.vectorHits());
    assertEquals(
        Set.of(
            new DocumentStore.SearchDependency(hit.documentId(), hit.paragraphId(), hit.chunkId())),
        snapshot.dependencies());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.vectorHits().clear());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.lexicalHits().clear());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.dependencies().clear());
    verify(access).requireSelection(selected);
  }

  @Test
  void snapshotRejectsUnboundedOrMismatchedCandidateProjections() {
    var hit = hit();
    assertThrows(
        IllegalArgumentException.class,
        () -> snapshot(0, DocumentStore.SearchProjection.VECTOR, List.of(), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            snapshot(
                DocumentStore.MOST_SEARCH_CANDIDATES + 1,
                DocumentStore.SearchProjection.VECTOR,
                List.of(),
                List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> snapshot(1, DocumentStore.SearchProjection.HYBRID, List.of(hit, hit), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> snapshot(1, DocumentStore.SearchProjection.VECTOR, List.of(), List.of(hit)));
    assertThrows(
        IllegalArgumentException.class,
        () -> snapshot(1, DocumentStore.SearchProjection.LEXICAL, List.of(hit), List.of()));
    assertThrows(IllegalArgumentException.class, () -> new DocumentStore.Coverage(-1, 0));
    assertThrows(IllegalArgumentException.class, () -> new DocumentStore.Coverage(0, -1));
  }

  private DocumentStore.SearchSnapshot snapshot(
      int depth,
      DocumentStore.SearchProjection projection,
      List<DocumentStore.Hit> vector,
      List<DocumentStore.Hit> lexical) {
    return new DocumentStore.SearchSnapshot(
        "question",
        depth,
        projection,
        vector,
        lexical,
        new DocumentStore.Coverage(12, 0),
        Optional.of(context),
        Optional.of(profile),
        Optional.empty());
  }

  private final class Search {
    // Real snapshot assembly requires the repository's initialized direct-cache default. Keep
    // persistence and scoped-view methods mocked so these remain service/query-budget tests.
    final DocumentStore store =
        mock(
            DocumentStore.class,
            withSettings().useConstructor(mock(JdbcTemplate.class), UnitOfWork.NONE));
    final EmbeddingClient embeddings = mock(EmbeddingClient.class);
    final DualEmbeddings dual = mock(DualEmbeddings.class);
    final QueryEmbeddings.Read read = mock(QueryEmbeddings.Read.class);
    final DocumentStore.Hit hit = hit();
    final RetrievalService service;
    Runnable duringProvider = () -> {};

    Search() {
      when(store.coverage()).thenReturn(new DocumentStore.Coverage(12, 0));
      when(store.searchByVector(any(), anyInt())).thenReturn(List.of(hit));
      when(store.searchByText(any(), any(), anyInt())).thenReturn(List.of(hit));
      doCallRealMethod()
          .when(store)
          .searchSnapshot(any(), any(), anyInt(), any(DocumentStore.SearchProjection.class));
      doCallRealMethod()
          .when(store)
          .searchSnapshot(any(), any(), anyInt(), any(DocumentStore.SearchProjection.class), any());
      var access = mock(InformationAccess.class);
      when(store.scoped(access, context)).thenReturn(store);
      when(store.profiled(profile)).thenReturn(store);
      when(store.queried(any())).thenReturn(store);
      when(embeddings.forOperation(read)).thenReturn(embeddings);
      when(dual.forQuery(read)).thenReturn(dual);
      when(dual.active(EmbeddingSlot.PROSE)).thenReturn(profile);
      var providerComplete = new AtomicBoolean();
      when(dual.queries(eq(profile), any(), any()))
          .thenAnswer(
              invocation -> {
                duringProvider.run();
                providerComplete.set(true);
                return List.of(new EmbeddingQuery(profile, new float[] {1, 0}));
              });
      when(dual.read(eq(profile), any()))
          .thenAnswer(
              invocation -> {
                Supplier<?> work = invocation.getArgument(1);
                return work.get();
              });
      when(read.disclose(any()))
          .thenAnswer(
              invocation -> {
                assertTrue(providerComplete.get());
                Supplier<?> work = invocation.getArgument(0);
                return work.get();
              });
      var queries = mock(QueryEmbeddings.class);
      when(queries.open(eq(context), any(), any())).thenReturn(read);
      var retrieval = new RetrievalService(store, embeddings, "test-model", 2);
      retrieval.useDualEmbeddings(dual);
      retrieval.useQueries(queries);
      service =
          retrieval.scoped(access, context).forQueries(QueryEmbeddings.Origin.request(() -> {}));
    }
  }

  private static DocumentStore.Hit hit() {
    return new DocumentStore.Hit(
        UUID.randomUUID(),
        "chunk",
        0.1,
        UUID.randomUUID(),
        "paragraph",
        0,
        UUID.randomUUID(),
        "source",
        "title",
        "summary");
  }
}
