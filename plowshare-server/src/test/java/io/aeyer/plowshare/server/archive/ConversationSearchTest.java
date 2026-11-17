package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.EntryKind;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    when(entries.search(HOME, "question", 0, 200))
        .thenReturn(new LogSearch(List.of(lexical), 1, new LogSearch.Reach(2, 0, 0)));
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
    assertThrows(
        ValidationException.class,
        () ->
            search.search(
                HOME, "question", 0, 10, "semantic", result.retrieval().snapshot(), "cnv_b"));
    assertThrows(
        ValidationException.class,
        () -> search.search(HOME, "question", 0, 10, "lexical", null, " "));
  }

  private void ranking(List<PassageIndex.Match> matches) {
    when(index.rankWindow(eq(HOME), eq("entry"), any(PassageIndex.Query.class), anyInt()))
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
    when(index.rankWindow(eq(HOME), eq("entry"), any(PassageIndex.Query.class), anyInt()))
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
        .rankWindow(
            eq(HOME),
            eq("entry"),
            any(PassageIndex.Query.class),
            eq(ConversationSearch.CANDIDATES + 1));
    verify(entries, times(4)).searchSources(eq(HOME), anyList());
    if (mode.equals("hybrid"))
      verify(entries).search(HOME, "question", 0, ConversationSearch.CANDIDATES);
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
    verify(index).rankWindow(eq(HOME), eq("entry"), any(PassageIndex.Query.class), anyInt());
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
    verify(index).rankWindow(eq(HOME), eq("entry"), any(PassageIndex.Query.class), anyInt());
  }

  @Test
  void approximateRecallDoesNotClaimCompletenessWhenThePassageWindowIsNotFull() {
    when(index.rankWindow(eq(HOME), eq("entry"), any(PassageIndex.Query.class), anyInt()))
        .thenReturn(new PassageIndex.Ranking(List.of(), true, false));
    when(entries.searchSources(eq(HOME), anyList())).thenReturn(List.of());
    var result = search.search(HOME, "question", 0, 10, "semantic", null);
    assertFalse(result.retrieval().complete());
    assertFalse(result.retrieval().truncated());
  }

  @Test
  void approximateRecallAndExhaustedPassageWindowRemainVisibleEvenAfterDeduplication() {
    when(index.rankWindow(eq(HOME), eq("entry"), any(PassageIndex.Query.class), anyInt()))
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
}
