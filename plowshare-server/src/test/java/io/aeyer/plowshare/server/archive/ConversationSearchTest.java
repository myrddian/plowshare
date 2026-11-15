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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.EntryKind;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    when(entries.search(any(), anyString(), anyInt(), anyInt())).thenReturn(LogSearch.NONE);
    when(entries.search(any(), anyString(), anyInt(), anyInt(), anyString()))
        .thenReturn(LogSearch.NONE);
    when(index.inConversation(any())).thenReturn(index);
    when(index.capture(anyString(), any(UsageAttribution.class), eq(HOME)))
        .thenReturn(new PassageIndex.Query(new float[768], null));
    when(index.coverage(HOME, "entry")).thenReturn(new PassageIndex.Coverage(2, 2, 2, 0, 0));
    when(index.generation()).thenReturn("fixture");
  }

  @Test
  void repeatedContentGetsOneVoteAndRetainsItsConversationReferences() {
    when(index.rank(eq(HOME), eq("entry"), any(PassageIndex.Query.class), anyInt()))
        .thenReturn(
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
    when(index.rank(eq(HOME), eq("entry"), any(PassageIndex.Query.class), anyInt()))
        .thenReturn(
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
    when(index.rank(eq(HOME), eq("entry"), any(PassageIndex.Query.class), anyInt()))
        .thenReturn(
            List.of(new PassageIndex.Match(a.id(), 0, "restricted distinct excerpt", .8, "hash")));
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
    when(index.rank(eq(HOME), eq("entry"), any(PassageIndex.Query.class), anyInt()))
        .thenReturn(List.of());
    var result = search.search(HOME, "question", 0, 10, "semantic", null, "cnv_a");
    verify(index, times(2)).inConversation("cnv_a");
    verify(entries).search(HOME, "question", 0, 1, "cnv_a");
    assertThrows(
        ValidationException.class,
        () ->
            search.search(
                HOME, "question", 0, 10, "semantic", result.retrieval().snapshot(), "cnv_b"));
    assertThrows(
        ValidationException.class,
        () -> search.search(HOME, "question", 0, 10, "lexical", null, " "));
  }
}
