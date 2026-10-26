package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Constant physical SQL/transaction budgets. Actual locking, reachability and scheduling are
 * full-db tests.
 */
class RelayPayloadSweepTest {
  private static final Instant NOW = Instant.parse("2026-10-25T10:42:17Z");

  @ParameterizedTest
  @ValueSource(ints = {1, 100, 1000})
  void oneAtomicBatchUsesThreeJdbcCallsRegardlessOfCorpusOrBatchSize(int count) throws Exception {
    var jdbc = mock(JdbcTemplate.class);
    var transactions = new AtomicInteger();
    var inTransaction = new java.util.concurrent.atomic.AtomicBoolean();
    UnitOfWork work =
        new UnitOfWork() {
          @Override
          public <T> T inTransaction(Supplier<T> action) {
            transactions.incrementAndGet();
            assertTrue(inTransaction.compareAndSet(false, true));
            try {
              return action.get();
            } finally {
              inTransaction.set(false);
            }
          }
        };
    when(jdbc.queryForList(anyString(), eq(String.class), any(), eq(count)))
        .thenAnswer(
            call -> {
              assertTrue(inTransaction.get());
              assertTrue(call.<String>getArgument(0).contains("FOR UPDATE SKIP LOCKED"));
              return IntStream.range(0, count).mapToObj(n -> "%064x".formatted(n)).toList();
            });
    when(jdbc.queryForObject(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<RelayPayloadStore.SweepResult>>any(),
            any(Object[].class)))
        .thenAnswer(
            call -> {
              assertTrue(inTransaction.get());
              var row = mock(ResultSet.class);
              when(row.getInt(1)).thenReturn(count / 2);
              when(row.getInt(2)).thenReturn(count - count / 2);
              return call.<RowMapper<RelayPayloadStore.SweepResult>>getArgument(1).mapRow(row, 0);
            });
    var store =
        new JdbcRelayPayloadStore(
            jdbc, work, Duration.ofHours(1), Duration.ofHours(24), Duration.ofDays(30));
    assertEquals(
        new RelayPayloadStore.SweepResult(count, count / 2, Optional.empty()),
        store.sweepBatch(NOW, count));
    assertEquals(1, transactions.get());
    // Includes transaction-local timeout/JIT settings, the locked selection, and one set-based
    // mutation/result statement. Never a per-row query, transaction, or batchUpdate fallback.
    assertEquals(3, mockingDetails(jdbc).getInvocations().size());
    verify(jdbc).execute(contains("SET LOCAL statement_timeout"));
    verify(jdbc, never()).update(anyString(), any(Object[].class));
  }

  @Test
  void productionCleanupRequiresItsOwnBoundedReadCommittedTransaction() {
    var jdbc = mock(JdbcTemplate.class);
    var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
    when(manager.getTransaction(any()))
        .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
    when(jdbc.queryForList(anyString(), eq(String.class), any(), anyInt())).thenReturn(List.of());
    new RelayConfiguration()
        .relayPayloadStore(jdbc, manager, new RelayProperties())
        .sweepBatch(NOW, 100);
    var definition =
        org.mockito.ArgumentCaptor.forClass(
            org.springframework.transaction.TransactionDefinition.class);
    verify(manager).getTransaction(definition.capture());
    assertEquals(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED,
        definition.getValue().getIsolationLevel());
    assertEquals(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW,
        definition.getValue().getPropagationBehavior());
    assertEquals(5, definition.getValue().getTimeout());
  }

  @Test
  void emptyOrEntirelyLockedBatchStopsWithoutLoadingReferences() {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), eq(String.class), any(), anyInt())).thenReturn(List.of());
    var store =
        new JdbcRelayPayloadStore(
            jdbc, UnitOfWork.NONE, Duration.ofHours(1), Duration.ofHours(24), Duration.ofDays(30));
    assertEquals(
        new RelayPayloadStore.SweepResult(0, 0, Optional.empty()), store.sweepBatch(NOW, 100));
    assertEquals(2, mockingDetails(jdbc).getInvocations().size());
    assertThrows(IllegalArgumentException.class, () -> store.sweepBatch(NOW, 0));
    assertThrows(IllegalArgumentException.class, () -> store.sweepBatch(NOW, 1001));
  }
}
