package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RelayMaintenanceTest {
  @Test
  void each_sweep_uses_one_clock_value_and_configured_bounds() {
    var repository = mock(RelayRepository.class);
    var deliveries = mock(RelayDeliveryRepository.class);
    var operations = mock(RelayOperationRepository.class);
    var properties = new RelayProperties();
    properties.setTopicBatchSize(2);
    properties.setPublicationBatchSize(3);
    Instant now = Instant.parse("2026-10-05T01:00:00Z");
    var a = new Relay.TopicKey(1, "release.observed");
    var b = new Relay.TopicKey(2, "release.observed");
    when(repository.topicsToPrune(now, 2)).thenReturn(List.of(a, b));
    when(repository.prune(a, now, 3)).thenReturn(3);
    when(repository.prune(b, now, 3)).thenReturn(1);
    try (var maintenance =
        new RelayMaintenance(
            repository,
            deliveries,
            mock(RelayExecutions.class),
            properties,
            () -> now,
            operations,
            mock(RelayNativeRepository.class))) {
      assertEquals(4, maintenance.sweep());
    }
    verify(operations).prune(Duration.ofDays(90), properties.getDeliveryBatchSize());
    verifyNoMoreInteractions(operations);
    verify(repository).topicsToPrune(now, 2);
    verify(repository).prune(a, now, 3);
    verify(repository).prune(b, now, 3);
    verifyNoMoreInteractions(repository);
    verify(deliveries).recoverExpired(now, properties.getDeliveryBatchSize());
    verify(deliveries)
        .pruneSettled(now, properties.getSettledRetention(), properties.getDeliveryBatchSize());
    verifyNoMoreInteractions(deliveries);
  }

  @Test
  void sweep_includes_the_payload_pass_in_the_returned_count() {
    var repository = mock(RelayRepository.class);
    var payloads = mock(RelayPayloadStore.class);
    var properties = new RelayProperties();
    properties.setPayloadSweepBatch(25);
    Instant now = Instant.parse("2026-10-05T01:00:00Z");
    when(repository.topicsToPrune(now, properties.getTopicBatchSize()))
        .thenReturn(List.of(new Relay.TopicKey(1, "release.observed")));
    when(repository.prune(any(), eq(now), anyInt())).thenReturn(3);
    when(payloads.sweepBatch(now, 25))
        .thenReturn(new RelayPayloadStore.SweepResult(2, 2, java.util.Optional.empty()));
    try (var maintenance =
        new RelayMaintenance(
            repository,
            mock(RelayDeliveryRepository.class),
            mock(RelayExecutions.class),
            properties,
            () -> now,
            mock(RelayOperationRepository.class),
            mock(RelayNativeRepository.class))) {
      maintenance.usePayloads(payloads);
      assertEquals(5, maintenance.sweep());
    }
    var order = inOrder(repository, payloads);
    order.verify(repository).prune(any(), eq(now), anyInt());
    order.verify(payloads).sweepBatch(now, 25);
  }

  @Test
  void catchUpStopsAtBatchAndTimeBudgetsAndDoesNotSpinOnLockedRows() {
    var payloads = mock(RelayPayloadStore.class);
    var properties = new RelayProperties();
    properties.setPayloadSweepMaxBatches(3);
    Instant now = Instant.parse("2026-10-05T01:00:00Z");
    var backlog = java.util.Optional.of(now.minusSeconds(60));
    when(payloads.sweepBatch(now, 100))
        .thenReturn(new RelayPayloadStore.SweepResult(100, 50, backlog));
    var ticks = new java.util.concurrent.atomic.AtomicLong();
    try (var maintenance =
        new RelayMaintenance(
            mock(RelayRepository.class),
            mock(RelayDeliveryRepository.class),
            mock(RelayExecutions.class),
            properties,
            () -> now,
            mock(RelayOperationRepository.class),
            mock(RelayNativeRepository.class),
            ticks::get)) {
      maintenance.usePayloads(payloads);
      assertEquals(150, maintenance.sweep());
      verify(payloads, times(3)).sweepBatch(now, 100);
      clearInvocations(payloads);
      when(payloads.sweepBatch(now, 100))
          .thenAnswer(
              call -> {
                ticks.addAndGet(properties.getPayloadSweepBudget().toNanos());
                return new RelayPayloadStore.SweepResult(100, 50, backlog);
              });
      assertEquals(50, maintenance.sweep());
      verify(payloads).sweepBatch(now, 100);
      clearInvocations(payloads);
      when(payloads.sweepBatch(now, 100))
          .thenReturn(new RelayPayloadStore.SweepResult(0, 0, backlog));
      assertEquals(0, maintenance.sweep());
      verify(payloads).sweepBatch(now, 100);
    }
  }

  @Test
  void overlappingLocalSweepsBorrowNoExtraConnectionAndFailureReleasesTheLane() {
    var payloads = mock(RelayPayloadStore.class);
    var properties = new RelayProperties();
    Instant now = Instant.parse("2026-10-05T01:00:00Z");
    try (var maintenance =
        new RelayMaintenance(
            mock(RelayRepository.class),
            mock(RelayDeliveryRepository.class),
            mock(RelayExecutions.class),
            properties,
            () -> now,
            mock(RelayOperationRepository.class),
            mock(RelayNativeRepository.class))) {
      maintenance.usePayloads(payloads);
      when(payloads.sweepBatch(now, 100))
          .thenAnswer(
              call -> {
                assertEquals(0, maintenance.sweep());
                throw new IllegalStateException("fixture failure");
              });
      assertThrows(IllegalStateException.class, maintenance::sweep);
      doReturn(new RelayPayloadStore.SweepResult(0, 0, java.util.Optional.empty()))
          .when(payloads)
          .sweepBatch(now, 100);
      assertEquals(0, maintenance.sweep());
      verify(payloads, times(2)).sweepBatch(now, 100);
    }
  }

  @Test
  void disabled_cleanup_does_not_query_on_startup() {
    var repository = mock(RelayRepository.class);
    var deliveries = mock(RelayDeliveryRepository.class);
    var properties = new RelayProperties();
    properties.setCleanupEnabled(false);
    try (var maintenance =
        new RelayMaintenance(
            repository,
            deliveries,
            mock(RelayExecutions.class),
            properties,
            Instant::now,
            mock(RelayOperationRepository.class),
            mock(RelayNativeRepository.class))) {
      maintenance.onApplicationEvent(null);
    }
    verifyNoInteractions(repository, deliveries);
  }

  @Test
  void configuration_rejects_unbounded_or_nonpositive_sweeps() {
    var properties = new RelayProperties();
    assertThrows(IllegalArgumentException.class, () -> properties.setPayloadSweepMaxBatches(0));
    assertThrows(IllegalArgumentException.class, () -> properties.setPayloadSweepMaxBatches(17));
    assertThrows(
        IllegalArgumentException.class, () -> properties.setPayloadSweepBudget(Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> properties.setPayloadSweepBudget(Duration.ofSeconds(6)));
    assertThrows(
        IllegalArgumentException.class,
        () -> properties.setPayloadBacklogWarningAge(Duration.ZERO));
    assertThrows(IllegalArgumentException.class, () -> properties.setTopicBatchSize(101));
    assertThrows(IllegalArgumentException.class, () -> properties.setPublicationBatchSize(0));
    assertThrows(
        IllegalArgumentException.class, () -> properties.setCleanupInterval(Duration.ofMillis(1)));
    assertThrows(
        IllegalArgumentException.class, () -> properties.setCleanupInterval(Duration.ofDays(2)));
    assertThrows(IllegalArgumentException.class, () -> properties.setDeliveryBatchSize(1001));
    assertThrows(
        IllegalArgumentException.class, () -> properties.setSettledRetention(Duration.ZERO));
  }
}
