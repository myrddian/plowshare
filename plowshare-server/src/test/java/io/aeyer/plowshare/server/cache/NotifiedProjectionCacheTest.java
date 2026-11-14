package io.aeyer.plowshare.server.cache;

import static io.aeyer.plowshare.server.cache.ProjectionCacheStatistics.Event.LOAD;
import static io.aeyer.plowshare.server.cache.ProjectionCacheStatistics.Event.LOADED_RETURN;
import static io.aeyer.plowshare.server.cache.ProjectionCacheStatistics.Event.TRUSTED_HIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A notification-trusted cache answers repeat reads with no SQL while delivery is proven fresh, and
 * takes the versioned path for anything it cannot prove: a watched change, late or missing
 * delivery, a caller's own write transaction, or an expiry inside the staleness bound.
 */
class NotifiedProjectionCacheTest {
  private static final Set<ProjectionVersions.Domain> DOMAINS =
      Set.of(ProjectionVersions.Domain.AUTHORITY);
  private static final Duration STALENESS = Duration.ofSeconds(2);
  private static final SnapshotCache.Key KEY = new SnapshotCache.Key("authority", "installation");

  private static ProjectionVersions.Token token(long version, Instant validUntil) {
    return new ProjectionVersions.Token(
        new UUID(0, 1),
        Map.of(ProjectionVersions.Domain.AUTHORITY, version),
        List.of(),
        "999999",
        null,
        validUntil);
  }

  private static final class Fixture implements AutoCloseable {
    final ProjectionVersions versions = mock(ProjectionVersions.class);
    final ProjectionChangeSignals signals = mock(ProjectionChangeSignals.class);
    final AtomicReference<ProjectionChangeSignals.Observer> observer = new AtomicReference<>();
    final AtomicLong delivered = new AtomicLong(System.nanoTime());
    final AtomicInteger loads = new AtomicInteger();
    final VersionedProjectionCache<String> cache;

    Fixture(Duration staleness, ProjectionVersions.Token token) {
      when(versions.capture(DOMAINS)).thenReturn(Optional.of(token));
      when(versions.current(any())).thenReturn(true);
      when(versions.canShare()).thenReturn(true);
      when(signals.subscribe(eq(DOMAINS), any()))
          .thenAnswer(
              call -> {
                ProjectionChangeSignals.Observer registered = call.getArgument(1);
                observer.set(registered);
                registered.stateChanged(new ProjectionChangeSignals.State(1, true));
                return (ProjectionChangeSignals.Subscription) () -> {};
              });
      when(signals.deliveredThrough()).thenAnswer(call -> OptionalLong.of(delivered.get()));
      cache =
          new VersionedProjectionCache<>(
              new BoundedSnapshotCache<>(
                  new SnapshotCache.Namespace(new UUID(0, 1).toString(), "notified"),
                  new SnapshotCache.Limits(
                      8, 4_000_000, 2, 4_000_000, 64, 64, 2, 32, Duration.ofSeconds(10))),
              versions,
              signals,
              DOMAINS,
              1_000_000,
              staleness);
    }

    Fixture() {
      this(STALENESS, token(1, null));
    }

    String read() {
      return cache.read(
          KEY,
          true,
          () -> "snapshot-" + loads.incrementAndGet(),
          () -> fail("A cacheable read must not bypass the cache"),
          value -> 64);
    }

    void fresh() {
      delivered.set(System.nanoTime());
    }

    public void close() {
      cache.close();
    }
  }

  @Test
  void repeatReadsWhileDeliveryIsFreshRunNoVersionSql() {
    try (var f = new Fixture()) {
      assertEquals("snapshot-1", f.read());
      verify(f.versions, times(1)).capture(DOMAINS);
      for (int check = 0; check < 100; check++) {
        f.fresh();
        assertEquals("snapshot-1", f.read());
      }
      verify(f.versions, times(1)).capture(DOMAINS);
      verify(f.versions, times(2)).current(any()); // The first read's load and hit checks only.
      assertEquals(1, f.loads.get());
      assertEquals(100, f.cache.statistics().count(TRUSTED_HIT));
      assertEquals(1, f.cache.statistics().count(LOAD));
      assertEquals(1, f.cache.statistics().count(LOADED_RETURN));
    }
  }

  @Test
  void aWatchedChangeSendsTheNextReadThroughVersionsAndReloads() {
    try (var f = new Fixture()) {
      f.read();
      f.read();
      f.observer.get().changed(DOMAINS);
      f.fresh();
      assertEquals("snapshot-2", f.read());
      verify(f.versions, times(2)).capture(DOMAINS);
      f.fresh();
      assertEquals("snapshot-2", f.read(), "Trusted again once the new value passed its check");
      verify(f.versions, times(2)).capture(DOMAINS);
    }
  }

  @Test
  void lateMissingOrRecoveringDeliveryUsesVersions() {
    try (var f = new Fixture()) {
      f.read();
      f.delivered.set(System.nanoTime() - Duration.ofSeconds(3).toNanos());
      f.read();
      verify(f.versions, times(2)).capture(DOMAINS);

      when(f.signals.deliveredThrough()).thenReturn(OptionalLong.empty());
      f.read();
      verify(f.versions, times(3)).capture(DOMAINS);

      when(f.signals.deliveredThrough()).thenAnswer(call -> OptionalLong.of(System.nanoTime()));
      f.observer.get().stateChanged(new ProjectionChangeSignals.State(2, false));
      f.observer.get().stateChanged(new ProjectionChangeSignals.State(3, true));
      f.read();
      verify(f.versions, times(4)).capture(DOMAINS);
    }
  }

  @Test
  void aCallerThatMayHoldItsOwnWritesAlwaysUsesVersions() {
    try (var f = new Fixture()) {
      f.read();
      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        f.fresh();
        f.read();
        verify(f.versions, times(2)).capture(DOMAINS);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        f.fresh();
        f.read();
        verify(f.versions, times(2)).capture(DOMAINS);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
            Connection.TRANSACTION_REPEATABLE_READ);
        f.fresh();
        f.read();
        verify(f.versions, times(3)).capture(DOMAINS);
      } finally {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
      }
    }
  }

  @Test
  void anExpiryInsideTheStalenessBoundIsDecidedBySql() {
    try (var f = new Fixture(STALENESS, token(1, Instant.now().plusSeconds(1)))) {
      f.read();
      f.fresh();
      f.read();
      verify(f.versions, times(2)).capture(DOMAINS);
    }
  }

  @Test
  void aVersionedCacheNeverTrustsNotifications() {
    try (var f = new Fixture(null, token(1, null))) {
      f.read();
      f.fresh();
      f.read();
      verify(f.versions, times(2)).capture(DOMAINS);
      verify(f.signals, never()).deliveredThrough();
    }
  }
}
