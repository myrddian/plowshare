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
