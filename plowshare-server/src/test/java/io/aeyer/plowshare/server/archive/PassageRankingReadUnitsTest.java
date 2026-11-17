package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.documents.Chunking;
import io.aeyer.plowshare.server.embedding.DualEmbeddings;
import io.aeyer.plowshare.server.embedding.EmbeddingProfile;
import io.aeyer.plowshare.server.embedding.EmbeddingQuery;
import io.aeyer.plowshare.server.embedding.QueryEmbeddings;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.EmbeddingException;
import io.aeyer.plowshare.server.reads.ReadDeadline;
import io.aeyer.plowshare.server.reads.ReadFailedException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class PassageRankingReadUnitsTest {
  private final PassageRepository repository = mock(PassageRepository.class);
  private final DualEmbeddings embeddings = mock(DualEmbeddings.class);
  private final EmbeddingProfile profile = mock(EmbeddingProfile.class);
  private final QueryEmbeddings.Read disclosure = mock(QueryEmbeddings.Read.class);
  private final PassageIndex index =
      new PassageIndex(
          repository, mock(EmbeddingClient.class), mock(Chunking.class), "fixture", 768);
  private final PassageIndex.Query query =
      new PassageIndex.Query(null, mock(EmbeddingQuery.class), disclosure);

  private void guards() {
    index.useDualEmbeddings(embeddings);
    when(query.typed().profile()).thenReturn(profile);
    when(disclosure.disclose(any()))
        .thenAnswer(
            call -> {
              Supplier<Object> read = call.getArgument(0);
              return read.get();
            });
    when(embeddings.read(eq(profile), any()))
        .thenAnswer(
            call -> {
              Supplier<Object> read = call.getArgument(1);
              return read.get();
            });
  }

  @Test
  void everyIndependentUnitGetsFreshSelectionProfileDisclosureAndSqlDeadline() {
    guards();
    var deadlines = new ArrayList<Long>();
    var selections = new ArrayList<Integer>();
    var selection =
        new PassageIndex.ReadSelection() {
          public <T> T within(Supplier<T> read) {
            assertTrue(
                ReadDeadline.currentDeadlineNanos().isPresent(),
                "project selection shares the actual disclosure/profile transaction deadline");
            selections.add(selections.size());
            return read.get();
          }
        };
    when(repository.rankWindow(any(), anyString(), any(), anyInt(), anyString(), any(), any()))
        .thenAnswer(
            call -> {
              PassageRepository.RankingReadUnit units = call.getArgument(6);
              for (int n = 0; n < 5; n++)
                units.independent(
                    () -> {
                      long deadline = ReadDeadline.currentDeadlineNanos().orElseThrow();
                      assertTrue(deadline - System.nanoTime() <= Duration.ofMillis(900).toNanos());
                      deadlines.add(deadline);
                      return true;
                    });
              return new PassageIndex.Ranking(List.of(), true, true);
            });
    index.rankSearchWindow(Home.global(), "entry", query, 201, Duration.ofMillis(900), selection);
    assertEquals(5, selections.size());
    assertEquals(5, deadlines.stream().distinct().count());
    verify(disclosure, times(5)).disclose(any());
    verify(embeddings, times(5)).read(eq(profile), any());
    assertTrue(ReadDeadline.currentDeadlineNanos().isEmpty());
  }

  @Test
  void profileActivationBetweenUnitsInvalidatesTheWholeWindowAndPreservesItsCause() {
    guards();
    var changed = new EmbeddingException("captured profile changed");
    when(embeddings.read(eq(profile), any()))
        .thenAnswer(
            call -> {
              Supplier<Object> read = call.getArgument(1);
              return read.get();
            })
        .thenThrow(changed);
    when(repository.rankWindow(any(), anyString(), any(), anyInt(), anyString(), any(), any()))
        .thenAnswer(
            call -> {
              PassageRepository.RankingReadUnit units = call.getArgument(6);
              units.independent(() -> true);
              units.independent(() -> true);
              throw new AssertionError("failed unit cannot produce a partial ranking");
            });
    var failure =
        assertThrows(
            ReadFailedException.class,
            () ->
                index.rankSearchWindow(
                    Home.global(), "entry", query, 201, Duration.ofSeconds(2), directSelection()));
    assertEquals(ReadFailedException.Reason.INVALIDATED, failure.reason());
    assertSame(changed, failure.getCause());
    verify(disclosure, times(2)).disclose(any());
    verify(embeddings, times(2)).read(eq(profile), any());
  }

  @Test
  void joinedUnitsInheritTheCallerDeadlineAndRejectExpiryBeforeGuardSql() {
    guards();
    when(repository.rankWindow(any(), anyString(), any(), anyInt(), anyString(), any(), any()))
        .thenAnswer(
            call -> {
              PassageRepository.RankingReadUnit units = call.getArgument(6);
              long enclosing = ReadDeadline.currentDeadlineNanos().orElseThrow();
              return units.joined(
                  () -> {
                    assertEquals(enclosing, ReadDeadline.currentDeadlineNanos().orElseThrow());
                    return new PassageIndex.Ranking(List.of(), true, true);
                  });
            });
    ReadDeadline.within(
        Duration.ofMillis(900),
        () ->
            index.rankSearchWindow(
                Home.global(), "entry", query, 201, Duration.ofSeconds(2), directSelection()));
    verify(disclosure).disclose(any());
    assertThrows(
        ReadFailedException.class,
        () ->
            ReadDeadline.within(
                Duration.ofNanos(1),
                () ->
                    index.rankSearchWindow(
                        Home.global(),
                        "entry",
                        query,
                        201,
                        Duration.ofSeconds(2),
                        directSelection())));
    verify(disclosure).disclose(any());
  }

  private static PassageIndex.ReadSelection directSelection() {
    return new PassageIndex.ReadSelection() {
      public <T> T within(Supplier<T> read) {
        return read.get();
      }
    };
  }
}
