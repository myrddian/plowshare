package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.EntryKind;
import io.aeyer.plowshare.server.api.LogSearchView;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.accounting.UsageOwners;
import io.aeyer.plowshare.server.reads.ReadFailedException;
import io.aeyer.plowshare.server.telemetry.WorkPerformanceReporter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.mock.env.MockEnvironment;

class ConversationSearchTest {
  private static final Home HOME = Home.of("project");
  private final EntryStore entries = mock(EntryStore.class);
  private final PassageIndex index = mock(PassageIndex.class);
  private final ConversationSearch search = new ConversationSearch(entries, index);
  private final LogSearch.Source a = source("cnv_a");
  private final LogSearch.Source b = source("cnv_b");

  private static LogSearch.Source source(String conversation) {
    return new LogSearch.Source(
        conversation, 1, 1, "utterance", null, Instant.EPOCH, "hash", 20, null);
  }

  @BeforeEach
  void setup() {
    when(entries.withinSearchScope(any(), any()))
        .thenAnswer(
            invocation -> {
              Supplier<Object> operation = invocation.getArgument(1);
              return operation.get();
            });
    when(entries.search(any(), anyString(), anyInt(), anyInt())).thenReturn(LogSearch.NONE);
    when(entries.search(any(), anyString(), anyInt(), anyInt(), anyString()))
        .thenReturn(LogSearch.NONE);
    when(entries.search(any(), anyString(), anyInt(), anyInt(), any(), eq(true)))
        .thenReturn(LogSearch.NONE);
    when(entries.searchBounded(any(), anyString(), anyInt(), anyInt(), any(), anyBoolean()))
        .thenReturn(BoundedLogSearch.NONE);
    when(index.inConversation(any())).thenReturn(index);
    when(index.capture(anyString(), any(UsageAttribution.class), eq(HOME)))
        .thenReturn(new PassageIndex.Query(new float[768], null));
    when(index.coverage(HOME, "entry")).thenReturn(new PassageIndex.Coverage(2, 2, 2, 0, 0));
    when(index.generation()).thenReturn("fixture");
    when(entries.searchReach(any(), any())).thenReturn(new LogSearch.Reach(2, 0, 0));
  }

  @Test
  void snapshotsCannotSwitchBetweenEvidenceAndFullInputs() {
    ranking(List.of());
    when(entries.searchSources(eq(HOME), anyList())).thenReturn(List.of());
    var first = search.search(HOME, "question", 0, 10, "semantic", null);
    assertThrows(
        ValidationException.class,
        () ->
            search.search(
                HOME,
                "question",
                0,
                10,
                "semantic",
                first.retrieval().snapshot(),
                null,
                null,
                true));
  }

  @Test
  void repeatedContentGetsOneVoteAndRetainsItsConversationReferences() {
    ranking(
        List.of(
            new PassageIndex.Match(a.id(), 0, "shared passage", .8, "hash"),
            new PassageIndex.Match(b.id(), 0, "shared passage", .8, "hash")));
    when(entries.searchSources(eq(HOME), anyList())).thenReturn(List.of(a, b));
    var result = search.search(HOME, "question", 0, 10, "semantic", null);
    assertEquals(1, result.total());
    assertEquals(.8, result.hits().getFirst().rank());
    assertEquals(List.of(a, b), result.hits().getFirst().sources());
  }

  @Test
  void snapshotsRecheckEveryReferenceAndCanReplaceARevokedRepresentative() {
    ranking(
        List.of(
            new PassageIndex.Match(
                a.id(),
                0,
                "shared",
                .8,
                "hash",
                List.of(
                    new PassageIndex.Occurrence(a.id(), "hash"),
                    new PassageIndex.Occurrence(b.id(), "hash")),
                2)));
    when(entries.searchSources(eq(HOME), anyList()))
        .thenReturn(List.of(a, b))
        .thenReturn(List.of(b));
    var first = search.search(HOME, "question", 0, 10, "semantic", null);
    var replay = search.search(HOME, "question", 0, 10, "semantic", first.retrieval().snapshot());
    assertEquals("cnv_b", replay.hits().getFirst().conversationId());
    assertEquals(List.of(b), replay.hits().getFirst().sources());
    assertEquals(1, replay.hits().getFirst().sourceCount());
    assertFalse(replay.retrieval().complete());
    assertEquals(0, replay.retrieval().queryEmbeddingCalls());
  }

  @Test
  void hybridVotesDoNotLendAnotherConversationsReferencesToADifferentExcerpt() {
    var lexical =
        new LogSearch.Hit(
            "cnv_a",
            1,
            1,
            EntryKind.UTTERANCE,
            1,
            "[common] passage",
            20,
            null,
            null,
            Instant.EPOCH,
            "hash",
            List.of(a, b),
            2);
    when(entries.searchBounded(HOME, "question", 0, 200, null, true))
        .thenReturn(
            new BoundedLogSearch(
                new LogSearch(List.of(lexical), 1, new LogSearch.Reach(2, 0, 0)), false));
    ranking(List.of(new PassageIndex.Match(a.id(), 0, "restricted distinct excerpt", .8, "hash")));
    when(entries.searchSources(eq(HOME), anyList()))
        .thenReturn(List.of(a, b))
        .thenReturn(List.of(b));
    var first = search.search(HOME, "question", 0, 10, "hybrid", null);
    assertEquals(List.of(a), first.hits().getFirst().sources());
    var replay = search.search(HOME, "question", 0, 10, "hybrid", first.retrieval().snapshot());
    assertEquals(List.of(), replay.hits());
  }

  @Test
  void semanticSelectionBindsRankingCoverageAndSnapshotIdentity() {
    ranking(List.of());
    var result = search.search(HOME, "question", 0, 10, "semantic", null, "cnv_a");
    verify(index, times(2)).inConversation("cnv_a");
    verify(entries).searchReach(HOME, "cnv_a");
    verify(entries, never()).search(any(), anyString(), anyInt(), anyInt(), anyString());
    verify(entries, never()).search(any(), anyString(), anyInt(), anyInt());
    verify(entries, never())
        .searchBounded(any(), anyString(), anyInt(), anyInt(), any(), anyBoolean());
    assertThrows(
        ValidationException.class,
        () ->
            search.search(
                HOME, "question", 0, 10, "semantic", result.retrieval().snapshot(), "cnv_b"));
    assertThrows(
        ValidationException.class,
        () -> search.search(HOME, "question", 0, 10, "lexical", null, " "));
  }

  @ParameterizedTest
  @ValueSource(strings = {"semantic", "hybrid"})
  void invalidatedCandidateUnitsCannotBecomeLexicalFallbackOrAPartialSnapshot(String mode) {
    var failure = ReadFailedException.invalidated(new IllegalStateException("profile changed"));
    when(index.rankSearchWindow(
            eq(HOME),
            eq("entry"),
            any(PassageIndex.Query.class),
            anyInt(),
            any(Duration.class),
            any(PassageIndex.ReadSelection.class)))
        .thenThrow(failure);
    assertSame(
        failure,
        assertThrows(
            ReadFailedException.class, () -> search.search(HOME, "question", 0, 10, mode, null)));
    verify(entries, never()).searchSources(any(), anyList());
  }

  private void ranking(List<PassageIndex.Match> matches) {
    when(index.rankSearchWindow(
            eq(HOME),
            eq("entry"),
            any(PassageIndex.Query.class),
            anyInt(),
            any(Duration.class),
            any(PassageIndex.ReadSelection.class)))
        .thenReturn(new PassageIndex.Ranking(matches, false, false));
  }

  @ParameterizedTest
  @ValueSource(strings = {"semantic", "hybrid"})
  void indexedSnapshotPaginatesWithoutRepeatingEmbeddingOrAnnSelection(String mode) {
    var sources = IntStream.range(0, 25).mapToObj(i -> source("cnv_%02d".formatted(i))).toList();
    var matches =
        IntStream.range(0, sources.size())
            .mapToObj(
                i ->
                    new PassageIndex.Match(
                        sources.get(i).id(), 0, "passage " + i, 1 - i * .01, "hash"))
            .toList();
    when(index.rankSearchWindow(
            eq(HOME),
            eq("entry"),
            any(PassageIndex.Query.class),
            anyInt(),
            any(Duration.class),
            any(PassageIndex.ReadSelection.class)))
        .thenReturn(new PassageIndex.Ranking(matches, true, true));
    when(entries.searchSources(eq(HOME), anyList())).thenReturn(sources);

    var first = search.search(HOME, "question", 0, 10, mode, null);
    String token = first.retrieval().snapshot();
    var second = search.search(HOME, "question", 10, 10, mode, token);
    var last = search.search(HOME, "question", 20, 10, mode, token);
    var end = search.search(HOME, "question", 30, 10, mode, token);

    assertEquals(
        List.of(10, 10, 5, 0),
        List.of(first.hits().size(), second.hits().size(), last.hits().size(), end.hits().size()));
    var ids = new ArrayList<String>();
    for (var page : List.of(first, second, last, end)) {
      assertEquals(25, page.total());
      assertEquals(token, page.retrieval().snapshot());
      assertEquals(true, page.retrieval().truncated());
      assertFalse(page.retrieval().complete());
      page.hits().forEach(hit -> ids.add(hit.conversationId()));
    }
    assertEquals(sources.stream().map(LogSearch.Source::conversationId).toList(), ids);
    assertEquals(1, first.retrieval().queryEmbeddingCalls());
    for (var page : List.of(second, last, end))
      assertEquals(0, page.retrieval().queryEmbeddingCalls());
    verify(index).capture(eq("question"), any(UsageAttribution.class), eq(HOME));
    verify(index)
        .rankSearchWindow(
            eq(HOME),
            eq("entry"),
            any(PassageIndex.Query.class),
            eq(ConversationSearch.CANDIDATES + 1),
            any(Duration.class),
            any(PassageIndex.ReadSelection.class));
    verify(entries, times(4)).searchSources(eq(HOME), anyList());
    if (mode.equals("hybrid"))
      verify(entries).searchBounded(HOME, "question", 0, ConversationSearch.CANDIDATES, null, true);
  }

  @ParameterizedTest
  @ValueSource(strings = {"semantic", "hybrid"})
  void withdrawnSnapshotSlotsDoNotHideSurvivingTailPages(String mode) {
    var sources = IntStream.range(0, 25).mapToObj(i -> source("cnv_%02d".formatted(i))).toList();
    ranking(
        IntStream.range(0, sources.size())
            .mapToObj(
                i ->
                    new PassageIndex.Match(
                        sources.get(i).id(), 0, "passage " + i, 1 - i * .01, "hash"))
            .toList());
    when(entries.searchSources(eq(HOME), anyList())).thenReturn(sources);
    var first = search.search(HOME, "question", 0, 10, mode, null);
    String token = first.retrieval().snapshot();

    when(entries.searchSources(eq(HOME), anyList())).thenReturn(sources.subList(20, 25));
    var middle = search.search(HOME, "question", 10, 10, mode, token);
    var tail = search.search(HOME, "question", 20, 10, mode, token);
    assertEquals(List.of(), middle.hits());
    assertEquals(25, middle.total());
    assertEquals(25, tail.total());
    assertEquals(
        sources.subList(20, 25).stream().map(LogSearch.Source::conversationId).toList(),
        tail.hits().stream().map(hit -> hit.conversationId()).toList());
    assertFalse(middle.retrieval().complete());
    assertEquals("bounded snapshot slots", middle.retrieval().totalMeaning());

    when(entries.searchSources(eq(HOME), anyList())).thenReturn(sources.subList(20, 24));
    var shorter = search.search(HOME, "question", 20, 10, mode, token);
    assertEquals(24, shorter.total());
    assertEquals(4, shorter.hits().size());
    when(entries.searchSources(eq(HOME), anyList())).thenReturn(List.of());
    var withdrawn = search.search(HOME, "question", 30, 10, mode, token);
    assertEquals(0, withdrawn.total());
    assertEquals(List.of(), withdrawn.hits());
    for (var page : List.of(middle, tail, shorter, withdrawn)) {
      assertEquals(token, page.retrieval().snapshot());
      assertEquals(0, page.retrieval().queryEmbeddingCalls());
    }
    verify(index).capture(eq("question"), any(UsageAttribution.class), eq(HOME));
    verify(index)
        .rankSearchWindow(
            eq(HOME),
            eq("entry"),
            any(PassageIndex.Query.class),
            anyInt(),
            any(Duration.class),
            any(PassageIndex.ReadSelection.class));
  }

  @Test
  void laterPagesRequireTheOriginalSnapshotQuestionModeHomeAndReader() {
    ranking(List.of(new PassageIndex.Match(a.id(), 0, "passage", .8, "hash")));
    when(entries.searchSources(eq(HOME), anyList())).thenReturn(List.of(a));
    var first = search.search(HOME, "question", 0, 1, "semantic", null);
    String token = first.retrieval().snapshot();
    assertThrows(
        ValidationException.class, () -> search.search(HOME, "question", 1, 1, "semantic", null));
    assertThrows(
        ValidationException.class,
        () -> search.search(HOME, "different question", 1, 1, "semantic", token));
    assertThrows(
        ValidationException.class, () -> search.search(HOME, "question", 1, 1, "hybrid", token));
    assertThrows(
        ValidationException.class,
        () -> search.search(Home.global(), "question", 1, 1, "semantic", token));
    when(entries.forAccount("other")).thenReturn(entries);
    when(index.forAccount("other")).thenReturn(index);
    assertThrows(
        ValidationException.class,
        () -> search.forAccount("other").search(HOME, "question", 1, 1, "semantic", token));
    verify(index).capture(eq("question"), any(UsageAttribution.class), eq(HOME));
    verify(index)
        .rankSearchWindow(
            eq(HOME),
            eq("entry"),
            any(PassageIndex.Query.class),
            anyInt(),
            any(Duration.class),
            any(PassageIndex.ReadSelection.class));
  }

  @Test
  void approximateRecallDoesNotClaimCompletenessWhenThePassageWindowIsNotFull() {
    when(index.rankSearchWindow(
            eq(HOME),
            eq("entry"),
            any(PassageIndex.Query.class),
            anyInt(),
            any(Duration.class),
            any(PassageIndex.ReadSelection.class)))
        .thenReturn(new PassageIndex.Ranking(List.of(), true, false));
    when(entries.searchSources(eq(HOME), anyList())).thenReturn(List.of());
    var result = search.search(HOME, "question", 0, 10, "semantic", null);
    assertFalse(result.retrieval().complete());
    assertFalse(result.retrieval().truncated());
  }

  @Test
  void approximateRecallAndExhaustedPassageWindowRemainVisibleEvenAfterDeduplication() {
    when(index.rankSearchWindow(
            eq(HOME),
            eq("entry"),
            any(PassageIndex.Query.class),
            anyInt(),
            any(Duration.class),
            any(PassageIndex.ReadSelection.class)))
        .thenReturn(
            new PassageIndex.Ranking(
                List.of(new PassageIndex.Match(a.id(), 0, "shared", .8, "hash")), true, true));
    when(entries.searchSources(eq(HOME), anyList())).thenReturn(List.of(a));
    var result = search.search(HOME, "question", 0, 10, "semantic", null);
    assertEquals(1, result.total());
    assertFalse(result.retrieval().complete());
    assertEquals(true, result.retrieval().truncated());
    assertEquals("bounded approximate snapshot slots", result.retrieval().totalMeaning());
    var replay = search.search(HOME, "question", 0, 10, "semantic", result.retrieval().snapshot());
    assertFalse(replay.retrieval().complete());
    assertEquals(true, replay.retrieval().truncated());
  }

  // --- lexical engine selection and bounded totals ---------------------------------------------

  private static final String EXACT_TOTAL = "distinct matching passage groups";
  private static final String EXACT_PROVENANCE =
      "Scoped BM25 (k1=1.2, b=0.75); repeated excerpts grouped before pagination; "
          + "explicit supplied context excluded; legacy inputs remain unclassified";

  private LogSearch.Hit lexicalHit() {
    return new LogSearch.Hit(
        "cnv_a",
        1,
        1,
        EntryKind.UTTERANCE,
        1,
        "[common] passage",
        20,
        null,
        null,
        Instant.EPOCH,
        "hash",
        List.of(a),
        1);
  }

  private LogSearch onePage() {
    return new LogSearch(List.of(lexicalHit()), 1, new LogSearch.Reach(2, 0, 0));
  }

  private ConversationSearch engine(LexicalEngine engine) {
    return new ConversationSearch(
        entries, index, Duration.ofSeconds(2), WorkPerformanceReporter.NONE, engine);
  }

  @Test
  void thePrunedPassageEngineLetsTheRepositorySkipMatchesAndTheDefaultDoesNot() {
    var pruned = engine(LexicalEngine.PASSAGES_PRUNED);
    ranking(List.of());
    when(entries.searchSources(eq(HOME), anyList())).thenReturn(List.of());

    pruned.search(HOME, "question", 3, 10, null, null);
    pruned.search(HOME, "question", 0, 10, "lexical", null, "cnv_a");
    pruned.search(HOME, "question", 0, 10, "hybrid", null);
    verify(entries).searchBounded(HOME, "question", 3, 10, null, false);
    verify(entries).searchBounded(HOME, "question", 0, 10, "cnv_a", false);
    verify(entries).searchBounded(HOME, "question", 0, ConversationSearch.CANDIDATES, null, false);
    verify(entries, never()).searchBounded(any(), anyString(), anyInt(), anyInt(), any(), eq(true));

    engine(LexicalEngine.PASSAGES).search(HOME, "question", 3, 10, null, null);
    engine(LexicalEngine.PASSAGES).search(HOME, "question", 0, 10, "hybrid", null);
    verify(entries).searchBounded(HOME, "question", 3, 10, null, true);
    verify(entries).searchBounded(HOME, "question", 0, ConversationSearch.CANDIDATES, null, true);
    verify(entries, never()).search(any(), anyString(), anyInt(), anyInt());
  }

  @Test
  void thePassageEngineIsTheDefaultAndAnswersLexicalRetrievalThroughTheBoundedContract() {
    search.search(HOME, "question", 3, 10, "lexical", null);
    search.search(HOME, "question", 0, 10, null, null, "cnv_a");
    verify(entries).searchBounded(HOME, "question", 3, 10, null, true);
    verify(entries).searchBounded(HOME, "question", 0, 10, "cnv_a", true);
    verify(entries, never()).search(any(), anyString(), anyInt(), anyInt());
    verify(entries, never()).search(any(), anyString(), anyInt(), anyInt(), anyString());
    verify(entries, never())
        .searchBounded(any(), anyString(), anyInt(), anyInt(), any(), eq(false));
    verify(index, never()).capture(anyString(), any(UsageAttribution.class), any(Home.class));
  }

  @Test
  void theLegacyEngineUsesTheOriginalSearchForLexicalAndHybridAndNeverTheBoundedOne() {
    var legacy = engine(LexicalEngine.LEGACY);
    when(entries.search(HOME, "question", 3, 10)).thenReturn(onePage());
    ranking(List.of());
    when(entries.searchSources(eq(HOME), anyList())).thenReturn(List.of(a));

    var explicit = legacy.search(HOME, "question", 3, 10, "lexical", null);
    legacy.search(HOME, "question", 0, 10, null, null, "cnv_a");
    legacy.search(HOME, "question", 0, 10, "hybrid", null);

    verify(entries).search(HOME, "question", 3, 10);
    verify(entries).search(HOME, "question", 0, 10, "cnv_a");
    verify(entries).search(HOME, "question", 0, ConversationSearch.CANDIDATES);
    verify(entries, never())
        .searchBounded(any(), anyString(), anyInt(), anyInt(), any(), anyBoolean());
    assertEquals(1, explicit.total());
    assertEquals(EXACT_TOTAL, explicit.retrieval().totalMeaning());
    assertFalse(explicit.retrieval().truncated());
    assertTrue(explicit.retrieval().complete());
  }

  @ParameterizedTest
  @EnumSource(LexicalEngine.class)
  void fullInputSearchKeepsTheOriginalQueryUnderEitherEngine(LexicalEngine selected) {
    var chosen = engine(selected);
    ranking(List.of());
    when(entries.searchSources(eq(HOME), anyList())).thenReturn(List.of());
    when(index.includingInputs(true)).thenReturn(index);

    var lexical = chosen.search(HOME, "question", 0, 10, "lexical", null, null, null, true);
    chosen.search(HOME, "question", 0, 10, "hybrid", null, null, "cnv_a", true);

    verify(entries).search(HOME, "question", 0, 10, null, true);
    verify(entries).search(HOME, "question", 0, ConversationSearch.CANDIDATES, "cnv_a", true);
    verify(entries, never())
        .searchBounded(any(), anyString(), anyInt(), anyInt(), any(), anyBoolean());
    assertFalse(lexical.retrieval().truncated());
    assertTrue(lexical.retrieval().complete());
    assertEquals(EXACT_TOTAL, lexical.retrieval().totalMeaning());
  }

  @Test
  void anExactLexicalAnswerKeepsItsOriginalResponse() {
    when(entries.searchBounded(HOME, "question", 0, 10, null, true))
        .thenReturn(new BoundedLogSearch(onePage(), false));

    var unasked = search.search(HOME, "question", 0, 10, null, null);
    var explicit = search.search(HOME, "question", 0, 10, "lexical", null);

    // The whole records, so that no field of an exact answer can drift unnoticed.
    assertEquals(LogSearchView.of(onePage(), 0, 10), unasked);
    assertEquals(null, unasked.retrieval());
    assertEquals(
        new LogSearchView(
            unasked.hits(),
            1,
            0,
            10,
            unasked.reach(),
            new LogSearchView.Retrieval(
                "lexical",
                "lexical",
                EXACT_TOTAL,
                null,
                false,
                true,
                null,
                null,
                null,
                0,
                EXACT_PROVENANCE)),
        explicit);
  }

  @Test
  void aTruncatedDefaultLexicalAnswerSaysItsTotalIsALowerBound() {
    when(entries.searchBounded(HOME, "question", 0, 10, null, true))
        .thenReturn(new BoundedLogSearch(onePage(), true));

    var result = search.search(HOME, "question", 0, 10, null, null);

    assertEquals(1, result.total());
    assertEquals(LogSearchView.of(onePage(), 0, 10).hits(), result.hits());
    var retrieval = result.retrieval();
    assertEquals("lexical", retrieval.requestedMode());
    assertEquals("lexical", retrieval.effectiveMode());
    assertTrue(retrieval.truncated());
    assertFalse(retrieval.complete());
    assertEquals(ConversationSearch.LOWER_BOUND_TOTAL, retrieval.totalMeaning());
    assertTrue(retrieval.totalMeaning().contains("lower bound"));
    assertEquals(null, retrieval.snapshot());
    assertEquals(0, retrieval.queryEmbeddingCalls());
    assertTrue(retrieval.provenance().contains("source counts are lower bounds"));
  }

  @Test
  void aTruncatedExplicitLexicalAnswerReportsTheBoundInsteadOfCompleteness() {
    when(entries.searchBounded(HOME, "question", 0, 10, "cnv_a", true))
        .thenReturn(new BoundedLogSearch(onePage(), true));

    var retrieval = search.search(HOME, "question", 0, 10, "lexical", null, "cnv_a").retrieval();

    assertTrue(retrieval.truncated());
    assertFalse(retrieval.complete());
    assertEquals(ConversationSearch.LOWER_BOUND_TOTAL, retrieval.totalMeaning());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void hybridFoldsLexicalTruncationIntoItsOwn(boolean lexicalTruncated) {
    when(entries.searchBounded(HOME, "question", 0, ConversationSearch.CANDIDATES, null, true))
        .thenReturn(new BoundedLogSearch(onePage(), lexicalTruncated));
    ranking(List.of());
    when(entries.searchSources(eq(HOME), anyList())).thenReturn(List.of(a));

    var first = search.search(HOME, "question", 0, 10, "hybrid", null);
    var replay = search.search(HOME, "question", 0, 10, "hybrid", first.retrieval().snapshot());

    assertEquals(1, first.hits().size());
    for (var page : List.of(first, replay)) {
      assertEquals(lexicalTruncated, page.retrieval().truncated());
      assertEquals(!lexicalTruncated, page.retrieval().complete());
      assertEquals("bounded snapshot slots", page.retrieval().totalMeaning());
      assertEquals(
          lexicalTruncated,
          page.retrieval().provenance().contains("source counts are lower bounds"));
    }
  }

  @Test
  void semanticModeIsUnaffectedByTheLexicalEngine() {
    ranking(List.of(new PassageIndex.Match(a.id(), 0, "passage", .8, "hash")));
    when(entries.searchSources(eq(HOME), anyList())).thenReturn(List.of(a));

    var passages = engine(LexicalEngine.PASSAGES).search(HOME, "question", 0, 10, "semantic", null);
    var legacy = engine(LexicalEngine.LEGACY).search(HOME, "question", 0, 10, "semantic", null);

    assertEquals(passages.hits(), legacy.hits());
    assertEquals(passages.retrieval().provenance(), legacy.retrieval().provenance());
    assertEquals(passages.retrieval().truncated(), legacy.retrieval().truncated());
    assertEquals(passages.retrieval().complete(), legacy.retrieval().complete());
    verify(entries, never())
        .searchBounded(any(), anyString(), anyInt(), anyInt(), any(), anyBoolean());
    verify(entries, never()).search(any(), anyString(), anyInt(), anyInt());
  }

  @Test
  void aReaderBoundSearchKeepsTheConfiguredEngine() {
    when(entries.forAccount("reader")).thenReturn(entries);
    when(index.forAccount("reader")).thenReturn(index);

    engine(LexicalEngine.LEGACY).forAccount("reader").search(HOME, "question", 0, 10, null, null);

    verify(entries).search(HOME, "question", 0, 10);
    verify(entries, never())
        .searchBounded(any(), anyString(), anyInt(), anyInt(), any(), anyBoolean());
  }

  @Test
  void aCancelledBoundedQueryIsTheSameRecoverableReadFailure() {
    var cancelled =
        new ArchiveQueryCancelledException(
            "search conversation evidence passages", new IllegalStateException("cancelled"));
    when(entries.searchBounded(any(), anyString(), anyInt(), anyInt(), any(), anyBoolean()))
        .thenThrow(cancelled);

    var failure =
        assertThrows(
            ReadFailedException.class, () -> search.search(HOME, "question", 0, 10, null, null));

    assertSame(cancelled, failure.getCause());
    assertEquals(ReadFailedException.Reason.QUERY_CANCELLED, failure.reason());
  }

  @Test
  void theEngineIsConfiguredByNameAndAnUnknownNameIsRefused() {
    assertEquals(LexicalEngine.PASSAGES, LexicalEngine.configured(null));
    assertEquals(LexicalEngine.PASSAGES, LexicalEngine.configured(" "));
    assertEquals(LexicalEngine.PASSAGES, LexicalEngine.configured("passages"));
    assertEquals(LexicalEngine.PASSAGES_PRUNED, LexicalEngine.configured("passages-pruned"));
    assertEquals(LexicalEngine.PASSAGES_PRUNED, LexicalEngine.configured(" Passages-Pruned"));
    assertEquals(LexicalEngine.LEGACY, LexicalEngine.configured("LEGACY "));
    var refused =
        assertThrows(IllegalArgumentException.class, () -> LexicalEngine.configured("lucene"));
    assertTrue(refused.getMessage().contains("plowshare.retrieval.lexical-engine"));
    assertTrue(refused.getMessage().contains("passages-pruned"));
    assertThrows(IllegalArgumentException.class, () -> LexicalEngine.configured("passages_pruned"));
    assertThrows(IllegalArgumentException.class, () -> LexicalEngine.configured("passage"));
  }

  @Test
  void compositionSelectsTheEngineFromConfigurationAndRefusesAnInvalidValue() {
    var config = new RetrievalIndexConfig();
    var legacy =
        config.conversationSearch(
            entries,
            index,
            new MockEnvironment().withProperty("plowshare.retrieval.lexical-engine", "legacy"),
            WorkPerformanceReporter.NONE);
    legacy.search(HOME, "question", 0, 10, null, null);
    verify(entries).search(HOME, "question", 0, 10);
    verify(entries, never())
        .searchBounded(any(), anyString(), anyInt(), anyInt(), any(), anyBoolean());

    config
        .conversationSearch(entries, index, new MockEnvironment(), WorkPerformanceReporter.NONE)
        .search(HOME, "question", 0, 10, null, null);
    verify(entries).searchBounded(HOME, "question", 0, 10, null, true);

    config
        .conversationSearch(
            entries,
            index,
            new MockEnvironment()
                .withProperty("plowshare.retrieval.lexical-engine", "passages-pruned"),
            WorkPerformanceReporter.NONE)
        .search(HOME, "question", 0, 10, null, null);
    verify(entries).searchBounded(HOME, "question", 0, 10, null, false);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            config.conversationSearch(
                entries,
                index,
                new MockEnvironment().withProperty("plowshare.retrieval.lexical-engine", "fast"),
                WorkPerformanceReporter.NONE));
  }

  @ParameterizedTest
  @ValueSource(strings = {"semantic", "hybrid"})
  void anEmbeddingSearchResolvesItsOwnerOnceAndALexicalSearchNever(String mode) {
    UsageOwners owners = mock(UsageOwners.class);
    var owner = UsageAttribution.system("project", UsageAttribution.Operation.EMBEDDING_QUERY);
    when(owners.in(HOME, null, UsageAttribution.Operation.EMBEDDING_QUERY)).thenReturn(owner);
    search.useUsageOwners(owners);
    ranking(List.of());
    when(entries.searchSources(eq(HOME), anyList())).thenReturn(List.of());

    search.search(HOME, "question", 0, 10, null, null);
    search.search(HOME, "question", 0, 10, "lexical", null, "cnv_a");
    verifyNoInteractions(owners);

    search.search(HOME, "question", 0, 10, mode, null);
    verify(owners).in(HOME, null, UsageAttribution.Operation.EMBEDDING_QUERY);
    verify(index).capture("question", owner, HOME);
  }

  // --- how a default request reaches this service ----------------------------------------------

  @Test
  void aDefaultRequestToTheStoreIsAnsweredByTheServiceBoundToItsReader() {
    ConversationSearch root = mock(ConversationSearch.class);
    ConversationSearch bound = mock(ConversationSearch.class);
    when(root.forAccount("reader")).thenReturn(bound);
    var answer = LogSearchView.of(onePage(), 0, 10);
    when(bound.search(HOME, "question", 0, 10, null, null, null, null, false)).thenReturn(answer);
    EntryStore store = new EntryStore(mock(JdbcTemplate.class)).retrieving(() -> root);

    assertSame(answer, store.forAccount("reader").searchView(HOME, "question", 0, 10, null, null));

    // The reader's own copy answered; the unbound service and the store's query did not.
    verify(root).forAccount("reader");
    verify(bound).search(HOME, "question", 0, 10, null, null, null, null, false);
    verify(root, never())
        .search(any(), any(), anyInt(), anyInt(), any(), any(), any(), any(), anyBoolean());
  }

  @Test
  void aStoreComposedWithoutRetrievalAnswersADefaultRequestFromTheLegacyQuery() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(
            anyString(),
            any(PreparedStatementSetter.class),
            ArgumentMatchers.<ResultSetExtractor<LogSearch>>any()))
        .thenReturn(onePage());
    EntryStore store = new EntryStore(jdbc);

    var view = store.searchView(Home.global(), "question", 0, 10, null, null);

    assertEquals(LogSearchView.of(onePage(), 0, 10), view);
    assertEquals(null, view.retrieval());
    verify(jdbc)
        .query(
            eq(EntryStore.SEARCH_SQL.replace("/*information*/", "")),
            any(PreparedStatementSetter.class),
            ArgumentMatchers.<ResultSetExtractor<LogSearch>>any());
    // An explicit mode has no service to go to, and is refused instead of quietly served.
    assertThrows(
        ValidationException.class,
        () -> store.searchView(Home.global(), "question", 0, 10, "lexical", null));
  }

  @Test
  void aRepositoryWithoutABoundedSearchAnswersExactlyThroughItsOriginalOne() {
    var page = onePage();
    ConversationSearchRepository plain =
        new ConversationSearchRepository() {
          @Override
          public ConversationSearchRepository forAccount(String account) {
            return this;
          }

          @Override
          public LogSearch search(
              Home home, String question, int skip, int most, String conversation) {
            return conversation == null ? page : LogSearch.NONE;
          }

          @Override
          public List<LogSearch.Source> searchSources(
              Home home, List<LogSearch.Reference> expected) {
            return List.of();
          }

          @Override
          public LogSearch.Reach searchReach(Home home, String conversation) {
            return page.reach();
          }
        };

    assertEquals(
        new BoundedLogSearch(page, false),
        plain.searchBounded(HOME, "question", 0, 10, null, true));
    assertEquals(
        BoundedLogSearch.NONE.page(),
        plain.searchBounded(HOME, "question", 0, 10, "cnv", false).page());
    var view = new ConversationSearch(plain, index).search(HOME, "question", 0, 10, null, null);
    assertEquals(1, view.total());
    assertEquals(null, view.retrieval());
  }
}
