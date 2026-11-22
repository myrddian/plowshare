package io.aeyer.plowshare.server.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.server.faults.Faults;
import io.aeyer.plowshare.server.pool.AdmissionThrottle.Admission;
import io.aeyer.plowshare.server.reads.JdbcReadDeadline;
import io.aeyer.plowshare.server.reads.ReadFailedException;
import io.aeyer.plowshare.server.reads.ReadUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.jdbc.PgStatement;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionSystemException;

class ScopedSqlWorkBudgetTest {
  private static ScopedSqlWorkBudget fakeBudget(
      SqlWorkBudgetProperties properties, MeterRegistry registry) {
    return new ScopedSqlWorkBudget(
        properties,
        registry,
        connection ->
            new SqlSessionDeadlines.Session() {
              @Override
              public void beforeStatement(SqlSessionDeadlines.Remaining remaining)
                  throws SQLException {
                remaining.millis();
              }

              @Override
              public void restore(SqlSessionDeadlines.Remaining remaining) {}
            });
  }

  private static SqlWorkBudgetProperties limits() {
    return new SqlWorkBudgetProperties(Duration.ofMillis(100), Duration.ofMillis(100), 4, 2, 8, 2);
  }

  @Test
  void unscopedHandlesAreUnchangedAndInvalidBoundsAreRejected() throws Exception {
    DataSource source = mock(DataSource.class);
    Connection raw = mock(Connection.class);
    when(source.getConnection()).thenReturn(raw);
    try (var budget = fakeBudget(limits(), new SimpleMeterRegistry())) {
      assertSame(raw, budget.decorate(source).getConnection());
      assertFalse(SqlWorkBudget.privateCacheMisses());
      assertThrows(
          IllegalArgumentException.class, () -> budget.within(Duration.ZERO, () -> "never"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new SqlWorkBudgetProperties(
                  Duration.ofMillis(2001), Duration.ofSeconds(1), 1, 1, 1, 1));
      verify(raw, never()).abort(any());
    }
  }

  @Test
  void scopedResultsKeepOwnedHandlesAndSuccessfulClosePreventsLateAbort() throws Exception {
    DataSource source = mock(DataSource.class);
    Connection raw = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    when(source.getConnection()).thenReturn(raw);
    when(raw.prepareStatement("query")).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(rows);
    when(rows.next()).thenReturn(true, false);
    when(rows.getString(1)).thenReturn("value");
    var budget = fakeBudget(limits(), new SimpleMeterRegistry());
    try {
      String actual =
          budget.within(
              Duration.ofSeconds(1),
              () -> {
                assertTrue(SqlWorkBudget.privateCacheMisses());
                try (Connection connection = budget.decorate(source).getConnection();
                    PreparedStatement selected = connection.prepareStatement("query");
                    ResultSet values = selected.executeQuery()) {
                  assertSame(connection, selected.getConnection());
                  assertSame(selected, values.getStatement());
                  assertSame(connection, connection.unwrap(Connection.class));
                  assertTrue(values.next());
                  return values.getString(1);
                } catch (SQLException failed) {
                  throw new AssertionError(failed);
                }
              });
      assertEquals("value", actual);
      assertFalse(SqlWorkBudget.privateCacheMisses());
      verify(raw).close();
      budget.close();
      verify(raw, never()).abort(any());
    } finally {
      budget.close();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void readDeadlineComposesWithoutNativeEscapeAndPreservesAnEarlierDriverTimer(boolean vendor)
      throws Exception {
    DataSource source = mock(DataSource.class);
    Connection raw = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    PgStatement postgres = mock(PgStatement.class);
    when(source.getConnection()).thenReturn(raw);
    when(raw.prepareStatement("read")).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(rows);
    when(statement.isWrapperFor(PgStatement.class)).thenReturn(vendor);
    when(statement.unwrap(PgStatement.class)).thenReturn(postgres);
    when(postgres.getQueryTimeoutMs()).thenReturn(125L);
    var bounded =
        new SqlWorkBudgetProperties(Duration.ofSeconds(1), Duration.ofMillis(100), 4, 2, 8, 2);
    try (var budget = fakeBudget(bounded, null)) {
      budget.within(
          Duration.ofSeconds(2),
          () -> {
            try (Connection connection = budget.decorate(source).getConnection();
                PreparedStatement selected = connection.prepareStatement("read")) {
              assertTrue(selected.isWrapperFor(JdbcReadDeadline.StatementDeadline.class));
              assertFalse(selected.isWrapperFor(PgStatement.class));
              assertThrows(
                  SQLFeatureNotSupportedException.class, () -> selected.unwrap(PgStatement.class));
              JdbcReadDeadline.apply(
                  selected, System.nanoTime() + Duration.ofMillis(800).toNanos());
              try (ResultSet ignored = selected.executeQuery()) {
                assertSame(selected, ignored.getStatement());
              }
              return true;
            } catch (SQLException failed) {
              throw new AssertionError(failed);
            }
          });
      verify(raw, atLeastOnce())
          .setNetworkTimeout(any(), intThat(value -> value > 0 && value <= 800));
      if (vendor) {
        verify(postgres).setQueryTimeoutMs(125);
        verify(statement, never()).setQueryTimeout(anyInt());
      } else {
        verify(statement).setQueryTimeout(1);
        verify(statement, never()).unwrap(PgStatement.class);
      }
      verify(raw, never()).abort(any());
    }
  }

  @Test
  void reusingAStatementCannotExtendItsPreviouslyAppliedAbsoluteReadDeadline() throws Exception {
    DataSource source = mock(DataSource.class);
    Connection raw = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    when(source.getConnection()).thenReturn(raw);
    when(raw.prepareStatement("read")).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(rows);
    AtomicLong remaining = new AtomicLong(Long.MAX_VALUE);
    var bounded =
        new SqlWorkBudgetProperties(Duration.ofSeconds(1), Duration.ofMillis(100), 4, 2, 8, 2);
    try (var budget =
        new ScopedSqlWorkBudget(
            bounded,
            null,
            connection ->
                new SqlSessionDeadlines.Session() {
                  @Override
                  public void beforeStatement(SqlSessionDeadlines.Remaining selected)
                      throws SQLException {
                    remaining.set(selected.millis());
                  }

                  @Override
                  public void restore(SqlSessionDeadlines.Remaining selected) {}
                })) {
      budget.within(
          Duration.ofSeconds(2),
          () -> {
            try (Connection connection = budget.decorate(source).getConnection();
                PreparedStatement selected = connection.prepareStatement("read")) {
              long first = System.nanoTime() + Duration.ofMillis(700).toNanos();
              JdbcReadDeadline.apply(selected, first);
              try (ResultSet values = selected.executeQuery()) {
                assertSame(selected, values.getStatement());
              }
              JdbcReadDeadline.apply(selected, System.nanoTime() + Duration.ofSeconds(2).toNanos());
              try (ResultSet values = selected.executeQuery()) {
                assertSame(selected, values.getStatement());
              }
              assertTrue(remaining.get() > 0 && remaining.get() <= 700);
              return true;
            } catch (SQLException failed) {
              throw new AssertionError(failed);
            }
          });
      verify(statement, times(2)).executeQuery();
      verify(raw, never()).abort(any());
    }
  }

  @Test
  void lateAcquisitionIsRefusedWithoutInterruptingTheCallerOrAnotherBorrower() throws Exception {
    DataSource source = mock(DataSource.class);
    CountDownLatch acquiring = new CountDownLatch(1);
    Connection late = mock(Connection.class);
    when(source.getConnection())
        .thenAnswer(
            invocation -> {
              acquiring.countDown();
              platformRead(150);
              return late;
            });
    try (var budget = fakeBudget(limits(), new SimpleMeterRegistry());
        var calls = Executors.newVirtualThreadPerTaskExecutor()) {
      var pending =
          calls.submit(
              () ->
                  budget.within(
                      Duration.ofMillis(80),
                      () -> {
                        try {
                          return budget.decorate(source).getConnection();
                        } catch (SQLException failed) {
                          throw new AssertionError(failed);
                        }
                      }));
      assertTrue(acquiring.await(1, TimeUnit.SECONDS));
      var failed = assertThrows(ExecutionException.class, () -> pending.get(1, TimeUnit.SECONDS));
      assertInstanceOf(ReadFailedException.class, failed.getCause());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void materializationIsTimedAndExpiredPhysicalCleanupRemainsOwned(boolean readDeadline)
      throws Exception {
    DataSource source = mock(DataSource.class);
    Connection raw = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    CountDownLatch materializing = new CountDownLatch(1);
    CountDownLatch aborted = new CountDownLatch(1);
    CountDownLatch releasePhysical = new CountDownLatch(1);
    when(source.getConnection()).thenReturn(raw);
    when(raw.prepareStatement("query")).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(rows);
    when(rows.next())
        .thenAnswer(
            invocation -> {
              materializing.countDown();
              try {
                new CountDownLatch(1).await(150, TimeUnit.MILLISECONDS);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
              return true;
            });
    doAnswer(
            invocation -> {
              aborted.countDown();
              assertTrue(releasePhysical.await(2, TimeUnit.SECONDS));
              return null;
            })
        .when(raw)
        .abort(any());
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    SqlWorkBudgetProperties bounded =
        readDeadline
            ? new SqlWorkBudgetProperties(Duration.ofSeconds(1), Duration.ofMillis(100), 4, 2, 8, 2)
            : limits();
    try (var budget = fakeBudget(bounded, registry);
        var calls = Executors.newVirtualThreadPerTaskExecutor()) {
      var pending =
          calls.submit(
              () ->
                  budget.within(
                      Duration.ofSeconds(1),
                      () -> {
                        try (Connection connection = budget.decorate(source).getConnection();
                            PreparedStatement selected = connection.prepareStatement("query")) {
                          if (readDeadline)
                            JdbcReadDeadline.apply(
                                selected, System.nanoTime() + Duration.ofMillis(100).toNanos());
                          try (ResultSet values = selected.executeQuery()) {
                            return values.next();
                          }
                        } catch (SQLException failed) {
                          throw new AssertionError(failed);
                        }
                      }));
      try {
        assertTrue(materializing.await(1, TimeUnit.SECONDS));
        assertInstanceOf(
            ReadFailedException.class,
            assertThrows(ExecutionException.class, () -> pending.get(1, TimeUnit.SECONDS))
                .getCause());
        assertTrue(aborted.await(1, TimeUnit.SECONDS));
        assertEquals(1, registry.get("plowshare.sql.work.owned.scopes").gauge().value());
        verify(raw, never()).close();
      } finally {
        releasePhysical.countDown();
      }
    }
    verify(raw).close();
  }

  @Test
  void nestedDeadlineIsEarlierAndCallerInterruptIsNotCleared() throws Exception {
    try (var budget = fakeBudget(limits(), null);
        var calls = Executors.newVirtualThreadPerTaskExecutor()) {
      var pending =
          calls.submit(
              () ->
                  budget.within(
                      Duration.ofSeconds(1),
                      () ->
                          budget.within(
                              Duration.ofMillis(50),
                              () -> {
                                try {
                                  new CountDownLatch(1).await(150, TimeUnit.MILLISECONDS);
                                } catch (InterruptedException interrupted) {
                                  Thread.currentThread().interrupt();
                                }
                                return "late";
                              })));
      assertInstanceOf(
          ReadFailedException.class,
          assertThrows(ExecutionException.class, () -> pending.get(1, TimeUnit.SECONDS))
              .getCause());
      var interrupted =
          calls.submit(
              () -> {
                Thread.currentThread().interrupt();
                assertThrows(
                    ReadFailedException.class,
                    () -> budget.within(Duration.ofSeconds(1), () -> "never"));
                return Thread.currentThread().isInterrupted();
              });
      assertTrue(interrupted.get(1, TimeUnit.SECONDS));
    }
  }

  @Test
  void escapingLeaseIsUnavailableAndShutdownRejectsNewScopes() throws Exception {
    DataSource source = mock(DataSource.class);
    Connection raw = mock(Connection.class);
    when(source.getConnection()).thenReturn(raw);
    var budget = fakeBudget(limits(), null);
    try {
      assertThrows(
          IllegalStateException.class,
          () ->
              budget.within(
                  Duration.ofSeconds(1),
                  () -> {
                    try {
                      return budget.decorate(source).getConnection();
                    } catch (SQLException failed) {
                      throw new AssertionError(failed);
                    }
                  }));
      budget.close();
      assertThrows(
          ReadFailedException.class, () -> budget.within(Duration.ofSeconds(1), () -> "never"));
    } finally {
      budget.close();
    }
    verify(raw).abort(any());
    verify(raw).close();
  }

  @Test
  void resourceBoundsRefuseBeforeAdditionalCheckoutAndDoNotBecomeCapacityRetry() throws Exception {
    DataSource source = mock(DataSource.class);
    AtomicInteger borrowed = new AtomicInteger();
    when(source.getConnection())
        .thenAnswer(
            invocation -> {
              borrowed.incrementAndGet();
              return mock(Connection.class);
            });
    try (var budget = fakeBudget(limits(), null)) {
      assertThrows(
          IllegalStateException.class,
          () ->
              budget.within(
                  Duration.ofSeconds(1),
                  () -> {
                    try (Connection one = budget.decorate(source).getConnection();
                        Connection two = budget.decorate(source).getConnection()) {
                      assertTrue(one != two);
                      return budget.decorate(source).getConnection();
                    } catch (SQLException failed) {
                      throw new AssertionError(failed);
                    }
                  }));
      assertEquals(2, borrowed.get());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void queryAndFetchRefreshInheritedNetworkDeadlineWhenPhysicalCleanupWorkerIsOccupied(
      boolean fetch) throws Exception {
    long fixtureStarted = System.nanoTime();
    var phases = new ConcurrentLinkedQueue<String>();
    Consumer<String> phase =
        value ->
            phases.add(
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - fixtureStarted) + "ms " + value);
    DataSource source = mock(DataSource.class);
    Connection first = mock(Connection.class);
    Connection second = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    when(source.getConnection()).thenReturn(first, second);
    when(second.prepareStatement("late")).thenReturn(statement);
    AtomicInteger socketTimeout = new AtomicInteger(1000);
    AtomicInteger initialTimeout = new AtomicInteger();
    AtomicInteger captured = new AtomicInteger();
    CountDownLatch cleanupBlocked = new CountDownLatch(1);
    CountDownLatch cleanupRelease = new CountDownLatch(1);
    CountDownLatch socketRead = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              phase.accept("first abort entered");
              cleanupBlocked.countDown();
              assertTrue(cleanupRelease.await(2, TimeUnit.SECONDS));
              return null;
            })
        .when(first)
        .abort(any());
    doAnswer(
            invocation -> {
              socketTimeout.set(invocation.getArgument(1));
              phase.accept("second network timeout " + socketTimeout.get());
              return null;
            })
        .when(second)
        .setNetworkTimeout(any(), anyInt());
    when(statement.executeQuery())
        .thenAnswer(
            invocation -> {
              if (!fetch) {
                captured.set(socketTimeout.get());
                phase.accept("raw execute timeout " + captured.get());
                socketRead.countDown();
                platformRead(socketTimeout.get() + 1);
                phase.accept("raw execute completed");
              }
              return rows;
            });
    when(rows.next())
        .thenAnswer(
            invocation -> {
              captured.set(socketTimeout.get());
              phase.accept("raw fetch timeout " + captured.get());
              socketRead.countDown();
              platformRead(socketTimeout.get() + 1);
              phase.accept("raw fetch completed");
              return true;
            });
    var settings =
        new SqlWorkBudgetProperties(Duration.ofSeconds(1), Duration.ofMillis(100), 4, 2, 8, 1);
    // Setup has its own finite allowance. Neither cold lease setup nor a scheduled wakeup must
    // land inside the tested 120ms window. The SQL query ceiling and cleanup budget stay unchanged.
    Duration setupBudget = Duration.ofSeconds(5);
    try (var budget = fakeBudget(settings, null);
        var calls = Executors.newSingleThreadExecutor()) {
      try {
        assertThrows(
            ReadFailedException.class,
            () ->
                budget.within(
                    setupBudget,
                    () -> {
                      try {
                        Connection held = budget.decorate(source).getConnection();
                        // Intentionally leave the aborted handle owned: finish must retain it
                        // until the one physical cleanup worker passes the release barrier.
                        held.abort(Runnable::run);
                        throw ReadFailedException.queryCancelled(null);
                      } catch (SQLException failed) {
                        throw new AssertionError(failed);
                      }
                    }));
        assertTrue(cleanupBlocked.await(1, TimeUnit.SECONDS), phases::toString);
        verify(first, never()).close();
        var pending =
            calls.submit(
                () ->
                    budget.within(
                        setupBudget,
                        () -> {
                          try {
                            Connection connection = budget.decorate(source).getConnection();
                            PreparedStatement selected = connection.prepareStatement("late");
                            ResultSet values = fetch ? selected.executeQuery() : null;
                            initialTimeout.set(socketTimeout.get());
                            assertTrue(initialTimeout.get() > 150, phases::toString);
                            var owningScope = ScopedSqlWorkBudget.current();
                            // Nested SQL units narrow the existing Scope.end. Both execute and
                            // an already-open result must re-clamp at Query.check, the same path
                            // that subtracts elapsed time from an aging scope's absolute deadline.
                            try {
                              return budget.within(
                                  Duration.ofMillis(120),
                                  () -> {
                                    assertSame(owningScope, ScopedSqlWorkBudget.current());
                                    // Close before restoring the setup deadline: canceled SQL must
                                    // not wait again using the longer fixture setup allowance.
                                    try (connection;
                                        selected) {
                                      if (fetch) {
                                        try (values) {
                                          return values.next();
                                        }
                                      }
                                      try (var result = selected.executeQuery()) {
                                        return result.next();
                                      }
                                    } catch (SQLException failed) {
                                      throw new AssertionError(failed);
                                    }
                                  });
                            } finally {
                              phase.accept("narrowed scope and resource close finished");
                            }
                          } catch (SQLException failed) {
                            throw new AssertionError(failed);
                          }
                        }));
        if (!socketRead.await(1, TimeUnit.SECONDS)) {
          try {
            pending.get(10, TimeUnit.MILLISECONDS);
          } catch (ExecutionException | TimeoutException failed) {
            throw new AssertionError("SQL fixture phases: " + phases, failed);
          }
          throw new AssertionError("SQL fixture phases: " + phases);
        }
        assertTrue(captured.get() > 0 && captured.get() <= 120, phases::toString);
        assertTrue(captured.get() < initialTimeout.get(), phases::toString);
        // This observes worker settlement; it is not the SQL deadline. A host can deschedule the
        // socket fixture past its requested sleep, while the applied network limit stays <=120ms.
        var failure =
            assertInstanceOf(
                ReadFailedException.class,
                assertThrows(
                        ExecutionException.class,
                        () -> pending.get(1, TimeUnit.SECONDS),
                        phases::toString)
                    .getCause());
        assertEquals(ReadFailedException.Reason.DEADLINE_EXCEEDED, failure.reason());
        assertEquals(1, cleanupRelease.getCount());
        verify(first, never()).close();
        verify(second, never()).abort(any());
        verify(second, never()).close();
      } finally {
        cleanupRelease.countDown();
      }
    }
  }

  /** A platform socket fixture ignores interruption until its configured network timeout. */
  private static void platformRead(int millis) {
    long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    boolean interrupted = false;
    while (end - System.nanoTime() > 0) {
      try {
        new CountDownLatch(1).await(end - System.nanoTime(), TimeUnit.NANOSECONDS);
      } catch (InterruptedException observed) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  @ParameterizedTest
  @CsvSource({"08006,true", "57014,false", "55P03,false"})
  void ownedDriverTimeoutBeforeWatchdogUsesUncertainUnavailableOutcome(String state, boolean socket)
      throws Exception {
    DataSource source = mock(DataSource.class);
    Connection raw = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    when(source.getConnection()).thenReturn(raw);
    when(raw.prepareStatement("timeout")).thenReturn(statement);
    SQLException timeout =
        new SQLException(
            "fixture", state, socket ? new SocketTimeoutException("fixture socket timeout") : null);
    when(statement.executeQuery()).thenThrow(timeout);
    try (var budget = fakeBudget(limits(), null);
        var calls = Executors.newVirtualThreadPerTaskExecutor()) {
      var call =
          calls.submit(
              () ->
                  budget.within(
                      Duration.ofSeconds(1),
                      () -> {
                        try (Connection connection = budget.decorate(source).getConnection();
                            PreparedStatement selected = connection.prepareStatement("timeout")) {
                          return selected.executeQuery();
                        } catch (SQLException failure) {
                          throw new AssertionError(failure);
                        }
                      }));
      Throwable failure =
          assertThrows(ExecutionException.class, () -> call.get(1, TimeUnit.SECONDS)).getCause();
      assertEquals(
          ReadFailedException.Reason.QUERY_CANCELLED,
          assertInstanceOf(ReadFailedException.class, failure).reason());
      var fault = Faults.of(failure);
      assertEquals(Code.ARCHIVE_UNAVAILABLE, fault.code());
      assertFalse(fault.detail().contains("Nothing was read"));
      assertFalse(fault.detail().contains("nothing was written"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"42601", "23505"})
  void unrelatedGrammarAndIntegrityFailuresAreNotChangedIntoBudgetFailures(String state)
      throws Exception {
    DataSource source = mock(DataSource.class);
    Connection raw = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    when(source.getConnection()).thenReturn(raw);
    when(raw.prepareStatement("bad")).thenReturn(statement);
    SQLException original = new SQLException("fixture", state);
    when(statement.executeQuery()).thenThrow(original);
    try (var budget = fakeBudget(limits(), null)) {
      var observed =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  budget.within(
                      Duration.ofSeconds(1),
                      () -> {
                        try (Connection connection = budget.decorate(source).getConnection();
                            PreparedStatement selected = connection.prepareStatement("bad")) {
                          return selected.executeQuery();
                        } catch (SQLException failure) {
                          throw new IllegalArgumentException("original", failure);
                        }
                      }));
      assertSame(original, observed.getCause());
      RuntimeException business = new IllegalStateException("business refusal");
      assertSame(
          business,
          assertThrows(
              IllegalStateException.class,
              () ->
                  budget.within(
                      Duration.ofSeconds(1),
                      () -> {
                        throw business;
                      })));
    }
  }

  @Test
  void failedSessionResetDiscardsExactOwnedLeaseBeforePoolReturn() throws Exception {
    DataSource source = mock(DataSource.class);
    Connection raw = mock(Connection.class);
    HikariDataSource pool = mock(HikariDataSource.class);
    when(source.getConnection()).thenReturn(raw);
    SqlSessionDeadlines sessions =
        connection ->
            new SqlSessionDeadlines.Session() {
              @Override
              public void beforeStatement(SqlSessionDeadlines.Remaining remaining) {}

              @Override
              public void restore(SqlSessionDeadlines.Remaining remaining) throws SQLException {
                throw new SQLException("fixture reset failed");
              }
            };
    try (var budget = new ScopedSqlWorkBudget(limits(), null, sessions)) {
      assertThrows(
          ReadFailedException.class,
          () ->
              budget.within(
                  Duration.ofSeconds(1),
                  () -> {
                    try (Connection connection = budget.decorate(source, pool).getConnection()) {
                      assertFalse(connection.isClosed());
                      return "must not publish";
                    } catch (SQLException failure) {
                      throw new AssertionError(failure);
                    }
                  }));
      var order = inOrder(pool, raw);
      order.verify(pool).evictConnection(raw);
      order.verify(raw).abort(any());
      order.verify(raw).close();
      assertSame(raw, budget.decorate(source, pool).getConnection());
      verify(pool).evictConnection(raw);
    }
  }

  @Test
  void expiredSqlDoesNotInterruptMaterializationAndSameThreadCanContinue() throws Exception {
    DataSource source = mock(DataSource.class);
    Connection raw = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    var entered = new CountDownLatch(1);
    var aborted = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(source.getConnection()).thenReturn(raw);
    when(raw.prepareStatement("query")).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(rows);
    when(rows.next())
        .thenAnswer(
            call -> {
              entered.countDown();
              release.await(2, TimeUnit.SECONDS);
              return true;
            });
    doAnswer(
            call -> {
              aborted.countDown();
              return null;
            })
        .when(raw)
        .abort(any());
    try (var budget = fakeBudget(limits(), null);
        var calls = Executors.newSingleThreadExecutor()) {
      var pending =
          calls.submit(
              () -> {
                assertThrows(
                    ReadFailedException.class,
                    () ->
                        budget.within(
                            Duration.ofMillis(80),
                            () -> {
                              try (Connection connection = budget.decorate(source).getConnection();
                                  PreparedStatement selected =
                                      connection.prepareStatement("query");
                                  ResultSet values = selected.executeQuery()) {
                                return values.next();
                              } catch (SQLException failed) {
                                throw new IllegalStateException(failed);
                              }
                            }));
                assertFalse(Thread.currentThread().isInterrupted());
                return budget.within(Duration.ofSeconds(1), () -> "continued");
              });
      try {
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertTrue(aborted.await(1, TimeUnit.SECONDS));
        assertFalse(pending.isDone(), "physical materialization remains owned until it returns");
        release.countDown();
        assertEquals("continued", pending.get(1, TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void genuineCallerInterruptionDuringSqlIsPreserved() throws Exception {
    DataSource source = mock(DataSource.class);
    Connection raw = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    var entered = new CountDownLatch(1);
    var caller = new AtomicReference<Thread>();
    when(source.getConnection()).thenReturn(raw);
    when(raw.prepareStatement("query")).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(rows);
    when(rows.next())
        .thenAnswer(
            call -> {
              caller.set(Thread.currentThread());
              entered.countDown();
              try {
                new CountDownLatch(1).await(5, TimeUnit.SECONDS);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new SQLException("caller stopped", interrupted);
              }
              return true;
            });
    var limits =
        new SqlWorkBudgetProperties(Duration.ofSeconds(2), Duration.ofMillis(100), 4, 2, 8, 2);
    try (var budget = fakeBudget(limits, null);
        var calls = Executors.newSingleThreadExecutor()) {
      var pending =
          calls.submit(
              () -> {
                var failure =
                    assertThrows(
                        ReadFailedException.class,
                        () ->
                            budget.within(
                                Duration.ofSeconds(5),
                                () -> {
                                  try (Connection connection =
                                          budget.decorate(source).getConnection();
                                      PreparedStatement selected =
                                          connection.prepareStatement("query");
                                      ResultSet values = selected.executeQuery()) {
                                    return values.next();
                                  } catch (SQLException failed) {
                                    throw new IllegalStateException(failed);
                                  }
                                }));
                assertEquals(ReadFailedException.Reason.INTERRUPTED, failure.reason());
                return Thread.currentThread().isInterrupted();
              });
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      caller.get().interrupt();
      assertTrue(pending.get(1, TimeUnit.SECONDS));
    }
  }

  @Test
  void failedScopedCloseRetainsPhysicalPermitButDetachesReusedCallerNesting() throws Exception {
    DataSource source = mock(DataSource.class);
    Connection raw = mock(Connection.class);
    when(source.getConnection()).thenReturn(raw, mock(Connection.class));
    doThrow(new SQLException("fixture close is not confirmed")).when(raw).close();
    AdmissionThrottle throttle =
        new AdmissionThrottle(
            4,
            0,
            Duration.ofMillis(1),
            Duration.ofMillis(10),
            System::nanoTime,
            () -> 0,
            delay -> {});
    DataSource throttled = new ThrottledDataSource(source, throttle);
    List<Admission> otherOwners = new ArrayList<>();
    try (var budget = fakeBudget(limits(), null);
        var caller = Executors.newSingleThreadExecutor();
        var owners = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        var first =
            caller.submit(
                () ->
                    budget.within(
                        Duration.ofSeconds(1),
                        () -> {
                          try (Connection connection = budget.decorate(throttled).getConnection()) {
                            assertFalse(connection.isClosed());
                            return "must not publish";
                          } catch (SQLException failure) {
                            throw new AssertionError(failure);
                          }
                        }));
        assertInstanceOf(
            ReadFailedException.class,
            assertThrows(ExecutionException.class, () -> first.get(1, TimeUnit.SECONDS))
                .getCause());
        assertEquals(1, throttle.held());
        for (int index = 0; index < 3; index++)
          otherOwners.add(owners.submit(throttle::acquire).get(1, TimeUnit.SECONDS));
        assertEquals(4, throttle.held());
        var next =
            caller.submit(
                () ->
                    budget.within(
                        Duration.ofMillis(80),
                        () -> {
                          try {
                            return budget.decorate(throttled).getConnection();
                          } catch (SQLException failure) {
                            throw new IllegalArgumentException(failure);
                          }
                        }));
        // Logical SQL expiry does not interrupt a workflow thread parked in the physical
        // throttle. Its seat stays owned until acquisition can return; no nested overdraft.
        assertThrows(TimeoutException.class, () -> next.get(200, TimeUnit.MILLISECONDS));
        verify(source, times(1)).getConnection();
        otherOwners.removeFirst().close();
        assertInstanceOf(
            ReadFailedException.class,
            assertThrows(ExecutionException.class, () -> next.get(1, TimeUnit.SECONDS)).getCause());
        verify(source, times(2)).getConnection();
      } finally {
        otherOwners.forEach(Admission::close);
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"42601,false", "23505,false", "42601,true", "23505,true"})
  void exactOwnedSqlDefectIsPreservedWhenItsResponseRacesExpiry(String state, boolean rollback)
      throws Exception {
    DataSource source = mock(DataSource.class);
    Connection raw = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    when(source.getConnection()).thenReturn(raw);
    when(raw.prepareStatement("late defect")).thenReturn(statement);
    SQLException original = new SQLException("fixture definitive SQL defect", state);
    when(statement.executeQuery())
        .thenAnswer(
            invocation -> {
              platformRead(
                  160); // retain a deterministic original defect after the 100ms query alarm
              throw original;
            });
    try (var budget = fakeBudget(limits(), null);
        var calls = Executors.newVirtualThreadPerTaskExecutor()) {
      var call =
          calls.submit(
              () ->
                  budget.within(
                      Duration.ofSeconds(1),
                      () -> {
                        try (Connection connection = budget.decorate(source).getConnection();
                            PreparedStatement selected =
                                connection.prepareStatement("late defect")) {
                          return selected.executeQuery();
                        } catch (SQLException failure) {
                          if (rollback) {
                            var replaced =
                                new TransactionSystemException(
                                    "rollback failed", new SQLException("closed", "08003"));
                            replaced.initApplicationException(
                                new DataIntegrityViolationException("original", failure));
                            throw replaced;
                          }
                          throw new IllegalArgumentException("original", failure);
                        }
                      }));
      Throwable observed =
          assertThrows(ExecutionException.class, () -> call.get(1, TimeUnit.SECONDS)).getCause();
      if (rollback) {
        var replaced = assertInstanceOf(TransactionSystemException.class, observed);
        assertSame(original, replaced.getApplicationException().getCause());
      } else {
        assertInstanceOf(IllegalArgumentException.class, observed);
        assertSame(original, observed.getCause());
      }
    }
  }

  @Test
  void latePhysicalAcquisitionsRetainCapacityUntilTheyReturn() throws Exception {
    DataSource source = mock(DataSource.class);
    var entered = new CountDownLatch(2);
    when(source.getConnection())
        .thenAnswer(
            call -> {
              entered.countDown();
              platformRead(250);
              return mock(Connection.class);
            });
    var settings =
        new SqlWorkBudgetProperties(Duration.ofMillis(100), Duration.ofMillis(100), 2, 2, 8, 2);
    var registry = new SimpleMeterRegistry();
    try (var budget = fakeBudget(settings, registry);
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = new ArrayList<Future<Connection>>();
      for (int i = 0; i < 2; i++)
        tasks.add(
            callers.submit(
                () ->
                    budget.within(
                        Duration.ofMillis(80),
                        () -> {
                          try {
                            return budget.decorate(source).getConnection();
                          } catch (SQLException failed) {
                            throw new IllegalStateException(failed);
                          }
                        })));
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      long due = System.nanoTime() + Duration.ofSeconds(1).toNanos();
      while ((registry.find("plowshare.sql.work.cancellations").counter() == null
              || registry.get("plowshare.sql.work.cancellations").counter().count() < 2)
          && System.nanoTime() - due < 0) Thread.onSpinWait();
      assertEquals(2, registry.get("plowshare.sql.work.cancellations").counter().count());
      assertThrows(
          ReadUnavailableException.class,
          () -> budget.within(Duration.ofSeconds(1), () -> "not started"));
      verify(source, times(2)).getConnection();
      for (var task : tasks)
        assertInstanceOf(
            ReadFailedException.class,
            assertThrows(ExecutionException.class, () -> task.get(1, TimeUnit.SECONDS)).getCause());
      due = System.nanoTime() + Duration.ofSeconds(1).toNanos();
      while (registry.get("plowshare.sql.work.owned.scopes").gauge().value() != 0
          && System.nanoTime() - due < 0) Thread.onSpinWait();
      assertEquals("recovered", budget.within(Duration.ofSeconds(1), () -> "recovered"));
    } finally {
      registry.close();
    }
  }

  @Test
  void commitAndAutocommitCommitRefreshServerLimitButFailedTransactionRollbackDoesNotSetupSql()
      throws Exception {
    DataSource source = mock(DataSource.class);
    Connection raw = mock(Connection.class);
    when(source.getConnection()).thenReturn(raw);
    when(raw.getAutoCommit()).thenReturn(false);
    AtomicInteger setups = new AtomicInteger();
    SqlSessionDeadlines sessions =
        connection ->
            new SqlSessionDeadlines.Session() {
              @Override
              public void beforeStatement(SqlSessionDeadlines.Remaining remaining)
                  throws SQLException {
                assertTrue(remaining.millis() > 0);
                assertTrue(remaining.millis() <= 100);
                setups.incrementAndGet();
              }

              @Override
              public void restore(SqlSessionDeadlines.Remaining remaining) {}
            };
    try (var budget = new ScopedSqlWorkBudget(limits(), null, sessions)) {
      assertEquals(
          "committed",
          budget.within(
              Duration.ofSeconds(1),
              () -> {
                try (Connection connection = budget.decorate(source).getConnection()) {
                  connection.commit();
                  connection.setAutoCommit(true);
                  assertEquals(2, setups.get());
                  connection
                      .rollback(); // PostgreSQL must allow this even when ordinary setup SQL fails
                  assertEquals(2, setups.get());
                  return "committed";
                } catch (SQLException failed) {
                  throw new AssertionError(failed);
                }
              }));
      verify(raw).commit();
      verify(raw).setAutoCommit(true);
      verify(raw).rollback();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void twoBlockedTimeoutSettersCannotOccupyAlarmsAndRetainOwnershipThroughShutdownAndReuse(
      boolean shutdownWhileBlocked) throws Exception {
    DataSource source = mock(DataSource.class);
    Connection firstRaw = mock(Connection.class);
    Connection secondRaw = mock(Connection.class);
    when(source.getConnection()).thenReturn(firstRaw, secondRaw, firstRaw);
    CountDownLatch setters = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    for (Connection raw : List.of(firstRaw, secondRaw)) {
      doAnswer(
              invocation -> {
                setters.countDown();
                boolean interrupted = false;
                while (release.getCount() != 0) {
                  try {
                    release.await();
                  } catch (InterruptedException observed) {
                    interrupted = true;
                  }
                }
                if (interrupted) Thread.currentThread().interrupt();
                return null;
              })
          .when(raw)
          .setNetworkTimeout(any(), anyInt());
    }
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    SqlWorkBudgetProperties bounded =
        new SqlWorkBudgetProperties(Duration.ofSeconds(1), Duration.ofMillis(40), 4, 2, 8, 2);
    var budget = fakeBudget(bounded, registry);
    try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        var first =
            calls.submit(
                () ->
                    budget.within(
                        Duration.ofMillis(80),
                        () -> {
                          try {
                            return budget.decorate(source).getConnection();
                          } catch (SQLException failed) {
                            throw new IllegalArgumentException(failed);
                          }
                        }));
        var second =
            calls.submit(
                () ->
                    budget.within(
                        Duration.ofMillis(80),
                        () -> {
                          try {
                            return budget.decorate(source).getConnection();
                          } catch (SQLException failed) {
                            throw new IllegalArgumentException(failed);
                          }
                        }));
        assertTrue(setters.await(1, TimeUnit.SECONDS));
        // Observe both logical expirations before admitting the third scope. In the original
        // failure both alarm workers parked on the setter-held monitors immediately afterward.
        long due = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while ((registry.find("plowshare.sql.work.cancellations").counter() == null
                || registry.get("plowshare.sql.work.cancellations").counter().count() < 2)
            && System.nanoTime() - due < 0) Thread.yield();
        assertEquals(2, registry.get("plowshare.sql.work.cancellations").counter().count());
        CountDownLatch thirdEntered = new CountDownLatch(1);
        var third =
            calls.submit(
                () ->
                    budget.within(
                        Duration.ofMillis(80),
                        () -> {
                          thirdEntered.countDown();
                          try {
                            new CountDownLatch(1).await(150, TimeUnit.MILLISECONDS);
                          } catch (InterruptedException observed) {
                            Thread.currentThread().interrupt();
                          }
                          return "late";
                        }));
        assertTrue(thirdEntered.await(1, TimeUnit.SECONDS));
        assertInstanceOf(
            ReadFailedException.class,
            assertThrows(ExecutionException.class, () -> third.get(350, TimeUnit.MILLISECONDS))
                .getCause());
        assertFalse(first.isDone());
        assertFalse(second.isDone());
        assertEquals(2, registry.get("plowshare.sql.work.physical.leases").gauge().value());
        verify(firstRaw, never()).abort(any());
        verify(secondRaw, never()).abort(any());
        verify(firstRaw, never()).close();
        verify(secondRaw, never()).close();
        if (shutdownWhileBlocked) {
          long closing = System.nanoTime();
          budget.close();
          assertTrue(System.nanoTime() - closing < Duration.ofMillis(350).toNanos());
          assertEquals(2, registry.get("plowshare.sql.work.physical.leases").gauge().value());
        }
        release.countDown();
        assertInstanceOf(
            ReadFailedException.class,
            assertThrows(ExecutionException.class, () -> first.get(1, TimeUnit.SECONDS))
                .getCause());
        assertInstanceOf(
            ReadFailedException.class,
            assertThrows(ExecutionException.class, () -> second.get(1, TimeUnit.SECONDS))
                .getCause());
        long settled = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (registry.get("plowshare.sql.work.owned.scopes").gauge().value() != 0
            && System.nanoTime() - settled < 0) Thread.yield();
        assertEquals(0, registry.get("plowshare.sql.work.owned.scopes").gauge().value());
        assertEquals(0, registry.get("plowshare.sql.work.physical.leases").gauge().value());
        // Reuse the same fake pool handle only after old physical ownership has settled.
        // A replacement budget is needed for the branch that already proved bounded shutdown.
        try (var replacement = fakeBudget(bounded, null)) {
          ScopedSqlWorkBudget recovered = shutdownWhileBlocked ? replacement : budget;
          assertEquals(
              "reused",
              recovered.within(
                  Duration.ofSeconds(1),
                  () -> {
                    try (Connection connection = recovered.decorate(source).getConnection()) {
                      assertFalse(connection.isClosed());
                      return "reused";
                    } catch (SQLException failed) {
                      throw new IllegalArgumentException(failed);
                    }
                  }));
        }
        verify(firstRaw, times(1)).abort(any());
        verify(secondRaw, times(1)).abort(any());
        verify(firstRaw, times(2)).close();
        verify(secondRaw, times(1)).close();
      } finally {
        release.countDown();
      }
    } finally {
      budget.close();
    }
  }
}
