package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ConversationAliasSqlDeadlineTest {
  private final List<Runnable> alarms = new ArrayList<>();
  private final AtomicLong clock = new AtomicLong();
  private final Thread owner = mock(Thread.class);
  private final Runnable cancellation = mock(Runnable.class);

  private ConversationAliasSqlDeadline scope() {
    var scheduler = mock(ScheduledExecutorService.class);
    when(scheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS)))
        .thenAnswer(
            call -> {
              alarms.add(call.getArgument(0));
              return mock(ScheduledFuture.class);
            });
    return new ConversationAliasSqlDeadline(
        1_000_000_000L, Duration.ofMillis(500), scheduler, cancellation, clock::get, owner);
  }

  @Test
  void expiryBeforeRegistrationAbortsTheStillOwnedLateConnection() throws Exception {
    try (var scope = scope()) {
      alarms.getFirst().run();
      verify(owner).interrupt();
      Connection connection = mock(Connection.class);
      assertThrows(ArchiveUnavailableException.class, () -> scope.attach(connection));
      verify(connection).abort(any());
      verify(cancellation).run();
    }
  }

  @Test
  void transactionDetachFencesLateAbortBeforePoolReuse() throws Exception {
    try (var scope = scope()) {
      Connection connection = mock(Connection.class);
      scope.attach(connection);
      scope.detach();
      alarms.getFirst().run();
      verify(connection, never()).abort(any());
      assertThrows(ArchiveUnavailableException.class, scope::check);
    }
  }

  @Test
  void closedDeadlineCannotInterruptOrAbortTheNextOperation() throws Exception {
    var scope = scope();
    Connection connection = mock(Connection.class);
    scope.attach(connection);
    scope.detach();
    scope.close();
    alarms.getFirst().run();
    verify(owner, never()).interrupt();
    verify(connection, never()).abort(any());
  }

  @Test
  void aFinishedQueryAlarmCannotCancelTheFollowingQuery() throws Exception {
    try (var scope = scope()) {
      Connection connection = mock(Connection.class);
      scope.attach(connection);
      var first = scope.beginQuery();
      try {
        scope.check();
      } finally {
        first.close();
      }
      var second = scope.beginQuery();
      try {
        alarms.get(1).run();
        scope.check();
        verify(connection, never()).abort(any());
        alarms.get(2).run();
        verify(connection).abort(any());
        assertThrows(ArchiveUnavailableException.class, scope::check);
      } finally {
        second.close();
      }
      scope.detach();
    }
  }

  @Test
  void callerInterruptionIsRefusedWithoutClearingOrReplacingIt() {
    try (var scope = scope()) {
      when(owner.isInterrupted()).thenReturn(true);
      assertThrows(ArchiveUnavailableException.class, scope::check);
      verify(owner, never()).interrupt();
    }
  }

  @Test
  void elapsedBudgetRejectsLateResultsWithoutWaitingForTheAlarm() {
    try (var scope = scope()) {
      clock.set(1_000_000_000L);
      assertThrows(ArchiveUnavailableException.class, scope::check);
    }
  }

  @Test
  void abortFailureRetainsUncertaintyAndNeverPublishesSuccess() throws Exception {
    try (var scope = scope()) {
      Connection connection = mock(Connection.class);
      SQLException failure = new SQLException("fixture abort failure");
      doThrow(failure).when(connection).abort(any());
      scope.attach(connection);
      alarms.getFirst().run();
      assertSame(failure, assertThrows(ArchiveUnavailableException.class, scope::check).getCause());
      scope.detach();
    }
  }
}
