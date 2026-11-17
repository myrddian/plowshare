package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.reads.ReadDeadline;
import io.aeyer.plowshare.server.reads.ReadUnavailableException;
import java.time.Duration;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ConversationSearchDeadlineTest {
  @Test
  void aCancelledSearchIsRecoverableAfterTheReadOwnerUnwinds() {
    var entries = mock(EntryStore.class);
    var index = mock(PassageIndex.class);
    var search = new ConversationSearch(entries, index);
    var cancelled =
        new ArchiveQueryCancelledException(
            "rank conversation passages", new IllegalStateException("cancelled"));
    when(entries.withinSearchScope(any(), any())).thenThrow(cancelled);
    var failure =
        assertThrows(
            ReadUnavailableException.class,
            () -> search.search(Home.global(), "failure", 0, 10, "hybrid", null));
    assertSame(cancelled, failure.getCause());
    assertTrue(failure.getMessage().contains("Conversation search was cancelled"));
    verify(entries).withinSearchScope(any(), any());
    verifyNoInteractions(index);
  }

  @Test
  void anUnavailableArchiveIsNotMisclassifiedAsQueryCancellation() {
    var entries = mock(EntryStore.class);
    var search = new ConversationSearch(entries, mock(PassageIndex.class));
    var unavailable =
        new ArchiveUnavailableException(
            "archive connection unavailable", new IllegalStateException("connection lost"));
    when(entries.withinSearchScope(any(), any())).thenThrow(unavailable);
    assertSame(
        unavailable,
        assertThrows(
            ArchiveUnavailableException.class,
            () -> search.search(Home.global(), "failure", 0, 10, "hybrid", null)));
  }

  @Test
  void searchSharesTheEarlierCacheAdmissionDeadlineRatherThanResettingIt() {
    var entries = mock(EntryStore.class);
    var index = mock(PassageIndex.class);
    var search = new ConversationSearch(entries, index, Duration.ofSeconds(2));
    when(entries.withinSearchScope(any(), any()))
        .thenAnswer(
            invocation -> {
              Supplier<Object> operation = invocation.getArgument(1);
              return operation.get();
            });
    ReadDeadline.within(
        Duration.ofMillis(500),
        () -> {
          long enclosing = ReadDeadline.deadlineNanos(Duration.ofSeconds(2));
          when(entries.search(any(), anyString(), anyInt(), anyInt()))
              .thenAnswer(
                  call -> {
                    assertEquals(enclosing, ReadDeadline.deadlineNanos(Duration.ofSeconds(2)));
                    return LogSearch.NONE;
                  });
          assertEquals(0, search.search(Home.global(), "failure", 0, 10, "lexical", null).total());
          return null;
        });
    verifyNoInteractions(index);
  }

  @Test
  void anExpiredAdmissionCannotStartSearchOrEmbeddingWork() {
    var entries = mock(EntryStore.class);
    var index = mock(PassageIndex.class);
    var search = new ConversationSearch(entries, index);
    assertThrows(
        ReadUnavailableException.class,
        () ->
            ReadDeadline.within(
                Duration.ofNanos(1),
                () -> search.search(Home.global(), "failure", 0, 10, "hybrid", null)));
    verifyNoInteractions(entries, index);
  }

  @Test
  void searchRequiresAPositiveBudget() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ConversationSearch(
                mock(EntryStore.class), mock(PassageIndex.class), Duration.ZERO));
    assertTrue(ReadDeadline.deadlineNanos(Duration.ofSeconds(2)) > System.nanoTime());
  }
}
