package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.embedding.QueryEmbeddings;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.reads.ReadDeadline;
import io.aeyer.plowshare.server.reads.ReadFailedException;
import io.aeyer.plowshare.server.reads.ReadUnavailableException;
import io.aeyer.plowshare.server.telemetry.WorkPerformanceReporter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

class ConversationSearchDeadlineTest {
  @Test
  void aCancelledSearchIsRecoverableAfterTheReadOwnerUnwinds() {
    var entries = mock(EntryStore.class);
    var index = mock(PassageIndex.class);
    var search = new ConversationSearch(entries, index);
    var cancelled =
        new ArchiveQueryCancelledException(
            "rank conversation passages", new IllegalStateException("cancelled"));
    doThrow(cancelled).when(entries).withinSearchScope(any(), any());
    var failure =
        assertThrows(
            ReadFailedException.class,
            () -> search.search(Home.global(), "failure", 0, 10, "hybrid", null));
    assertSame(cancelled, failure.getCause());
    assertEquals(ReadFailedException.Reason.QUERY_CANCELLED, failure.reason());
    assertFalse(ReadUnavailableException.class.isInstance(failure));
    verify(entries).withinSearchScope(any(), any());
    verifyNoInteractions(index);
  }

  @Test
  void genuineReadBackpressureRetainsItsDistinctAdmissionFailure() {
    var entries = mock(EntryStore.class);
    var index = mock(PassageIndex.class);
    var backpressure = new ReadUnavailableException();
    when(entries.withinSearchScope(any(), any())).thenThrow(backpressure);
    assertSame(
        backpressure,
        assertThrows(
            ReadUnavailableException.class,
            () ->
                new ConversationSearch(entries, index)
                    .search(Home.global(), "failure", 0, 10, "hybrid", null)));
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
  void nestedSqlReadsShareTheEarlierWorkUnitDeadlineRatherThanResettingIt() {
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
  void anExpiredSqlWorkUnitCannotStartSearchOrEmbeddingWork() {
    var entries = mock(EntryStore.class);
    var index = mock(PassageIndex.class);
    var search = new ConversationSearch(entries, index);
    var failure =
        assertThrows(
            ReadFailedException.class,
            () ->
                ReadDeadline.within(
                    Duration.ofNanos(1),
                    () -> search.search(Home.global(), "failure", 0, 10, "hybrid", null)));
    assertEquals(ReadFailedException.Reason.DEADLINE_EXCEEDED, failure.reason());
    verifyNoInteractions(entries, index);
  }

  @Test
  void independentQueriesAndProviderHaveNoAggregateTimerButTheRankingTransactionIsBounded() {
    var home = Home.of("provider-boundary");
    var entries = mock(EntryStore.class);
    var index = mock(PassageIndex.class);
    var selection = new AtomicBoolean();
    var phases = phases(entries, selection);
    var coveragePhases = new ArrayList<Long>();
    var refreshPhases = new ArrayList<Long>();
    var admission = mock(QueryEmbeddings.Read.class);
    when(entries.searchReach(home, null))
        .thenAnswer(
            call -> {
              assertTrue(selection.get());
              noSqlDeadline();
              return new LogSearch.Reach(0, 0, 0);
            });
    when(index.inConversation(null)).thenReturn(index);
    when(index.capture(anyString(), any(UsageAttribution.class), eq(home)))
        .thenAnswer(
            call -> {
              outsideSql(selection);
              return new PassageIndex.Query(new float[768], null, admission);
            });
    when(index.rankWindow(eq(home), eq("entry"), any(PassageIndex.Query.class), anyInt()))
        .thenAnswer(
            call -> {
              sqlDeadline(selection);
              return new PassageIndex.Ranking(List.of(), false, false);
            });
    when(index.coverage(home, "entry"))
        .thenAnswer(
            call -> {
              assertTrue(selection.get());
              noSqlDeadline();
              coveragePhases.add(phases.getLast());
              return new PassageIndex.Coverage(0, 0, 0, 0, 0);
            });
    when(entries.searchSources(eq(home), anyList()))
        .thenAnswer(
            call -> {
              assertTrue(selection.get());
              noSqlDeadline();
              refreshPhases.add(phases.getLast());
              return List.of();
            });
    doAnswer(
            call -> {
              outsideSql(selection);
              return null;
            })
        .when(admission)
        .close();
    var search = new ConversationSearch(entries, index);
    var first = search.search(home, "question", 0, 10, "semantic", null);
    outsideSql(selection);
    assertEquals(3, phases.size());
    assertEquals(phases.get(2), coveragePhases.getFirst());
    assertEquals(phases.get(2), refreshPhases.getFirst());
    assertTrue(phases.get(0) < phases.get(1));
    assertTrue(phases.get(1) < phases.get(2));
    search.search(home, "question", 10, 10, "semantic", first.retrieval().snapshot());
    assertEquals(4, phases.size());
    assertEquals(phases.get(3), coveragePhases.getLast());
    assertEquals(phases.get(3), refreshPhases.getLast());
    verify(index).capture(anyString(), any(UsageAttribution.class), eq(home));
    verify(index).rankWindow(eq(home), eq("entry"), any(PassageIndex.Query.class), anyInt());
    verify(admission).close();
  }

  @Test
  void workflowObservationClosesOnSuccessAndFailureAndSurvivesReaderBinding() {
    var entries = mock(EntryStore.class);
    var index = mock(PassageIndex.class);
    var performance = mock(WorkPerformanceReporter.class);
    var observation = mock(WorkPerformanceReporter.Observation.class);
    when(performance.watch(WorkPerformanceReporter.Kind.SEARCH, "conversation-search"))
        .thenReturn(observation);
    when(entries.forAccount("reader")).thenReturn(entries);
    when(index.forAccount("reader")).thenReturn(index);
    when(entries.withinSearchScope(any(), any()))
        .thenAnswer(
            call -> {
              Supplier<Object> read = call.getArgument(1);
              return read.get();
            });
    when(entries.search(any(), anyString(), anyInt(), anyInt())).thenReturn(LogSearch.NONE);
    var search = new ConversationSearch(entries, index, Duration.ofSeconds(2), performance);
    search.search(Home.global(), "question", 0, 10, "lexical", null);
    var cancelled =
        new ArchiveQueryCancelledException("lexical query", new IllegalStateException());
    doThrow(cancelled).when(entries).withinSearchScope(any(), any());
    assertThrows(
        ReadFailedException.class,
        () ->
            search.forAccount("reader").search(Home.global(), "question", 0, 10, "lexical", null));
    verify(performance, times(2)).watch(WorkPerformanceReporter.Kind.SEARCH, "conversation-search");
    verify(observation, times(2)).close();
  }

  @Test
  void projectSelectionReusesOnlyItsPhaseAndDoesNotInstallAWorkflowTimer() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(
            eq("SELECT id FROM projects WHERE name = ?"),
            any(PreparedStatementSetter.class),
            ArgumentMatchers.<RowMapper<Long>>any()))
        .thenReturn(List.of(7L))
        .thenReturn(List.of(8L));
    var entries = new EntryStore(jdbc);
    var home = Home.of("changing-project");
    entries.withinSearchScope(
        home,
        () -> {
          noSqlDeadline();
          assertEquals(7L, ProjectIds.toRead(jdbc, home));
          assertEquals(7L, ProjectIds.toRead(jdbc, home));
          return true;
        });
    // A later phase (for example after provider IO) must resolve current project identity again.
    entries.withinSearchScope(
        home,
        () -> {
          noSqlDeadline();
          assertEquals(8L, ProjectIds.toRead(jdbc, home));
          return true;
        });
    verify(jdbc, times(2))
        .query(
            eq("SELECT id FROM projects WHERE name = ?"),
            any(PreparedStatementSetter.class),
            ArgumentMatchers.<RowMapper<Long>>any());
  }

  @Test
  void cancelledRankingUnwindsItsSqlPhaseAndQueryOwnerBeforeReturningRecoverableFailure() {
    var home = Home.of("cancelled-ranking");
    var entries = mock(EntryStore.class);
    var index = mock(PassageIndex.class);
    var selection = new AtomicBoolean();
    phases(entries, selection);
    var admission = mock(QueryEmbeddings.Read.class);
    when(entries.searchReach(home, null)).thenReturn(new LogSearch.Reach(0, 0, 0));
    when(index.inConversation(null)).thenReturn(index);
    when(index.capture(anyString(), any(UsageAttribution.class), eq(home)))
        .thenReturn(new PassageIndex.Query(new float[768], null, admission));
    var cancelled =
        new ArchiveQueryCancelledException(
            "rank conversation passages", new IllegalStateException("cancelled"));
    when(index.rankWindow(eq(home), eq("entry"), any(PassageIndex.Query.class), anyInt()))
        .thenAnswer(
            call -> {
              sqlDeadline(selection);
              throw cancelled;
            });
    doAnswer(
            call -> {
              outsideSql(selection);
              return null;
            })
        .when(admission)
        .close();
    var failure =
        assertThrows(
            ReadFailedException.class,
            () ->
                new ConversationSearch(entries, index)
                    .search(home, "question", 0, 10, "semantic", null));
    assertSame(cancelled, failure.getCause());
    assertEquals(ReadFailedException.Reason.QUERY_CANCELLED, failure.reason());
    outsideSql(selection);
    verify(admission).close();
    verify(entries, times(2)).withinSearchScope(eq(home), any());
    verify(index, never()).coverage(any(), anyString());
  }

  private static List<Long> phases(EntryStore entries, AtomicBoolean selection) {
    var phases = new ArrayList<Long>();
    when(entries.withinSearchScope(any(), any()))
        .thenAnswer(
            call -> {
              assertFalse(
                  selection.getAndSet(true),
                  "project selection cannot span independent SQL phases");
              try {
                phases.add(System.nanoTime());
                Supplier<Object> read = call.getArgument(1);
                return read.get();
              } finally {
                selection.set(false);
              }
            });
    return phases;
  }

  private static long sqlDeadline(AtomicBoolean selection) {
    assertTrue(selection.get());
    long deadline = ReadDeadline.deadlineNanos(Duration.ofSeconds(30));
    long remaining = deadline - System.nanoTime();
    assertTrue(remaining > 0 && remaining <= Duration.ofSeconds(2).toNanos());
    return deadline;
  }

  private static void outsideSql(AtomicBoolean selection) {
    assertFalse(selection.get());
    noSqlDeadline();
  }

  private static void noSqlDeadline() {
    // Without an installed SQL deadline the fallback is returned from now. This observes the
    // boundary without spending two seconds in a fake provider or relying on a timer race.
    assertTrue(
        ReadDeadline.deadlineNanos(Duration.ofSeconds(30)) - System.nanoTime()
            > Duration.ofSeconds(20).toNanos());
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
