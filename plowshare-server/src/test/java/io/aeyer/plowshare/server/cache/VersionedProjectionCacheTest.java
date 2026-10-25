package io.aeyer.plowshare.server.cache;

import static io.aeyer.plowshare.server.cache.CacheTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;

class VersionedProjectionCacheTest {
  private static final Set<ProjectionVersions.Domain> DOMAINS =
      Set.of(ProjectionVersions.Domain.SEARCH);
  private static final SnapshotCache.Key KEY = new SnapshotCache.Key("search", "selection");

  @Test
  void coldFillAndWarmHitReuseValueButRevalidateStoredEvidenceEveryTime() {
    try (var fixture = new Fixture()) {
      var loads = new AtomicInteger();
      var value = new Value("loaded");
      for (int attempt = 0; attempt < 2; attempt++) {
        assertSame(
            value,
            fixture.cache.read(
                KEY,
                true,
                () -> {
                  loads.incrementAndGet();
                  return value;
                },
                () -> fail("Fresh value should be shared"),
                ignored -> 100));
        verify(fixture.versions, times(1)).canShare();
        verify(fixture.versions, times(attempt + 2)).current(fixture.token);
      }
      assertEquals(1, loads.get());
      verify(fixture.versions, times(2)).capture(DOMAINS);
      verify(fixture.versions, times(3)).current(fixture.token);
      assertEquals(1, fixture.backing.statistics().hits());
    }
  }

  @Test
  void freshCaptureCannotLaunderAnOldValueAfterAPendingWriterCommitsWithoutCounterMovement() {
    try (var fixture = new Fixture()) {
      var old = token(List.of("10"));
      var fresh = token(List.of());
      assertEquals(old.sourceVersion(), fresh.sourceVersion());
      fixture.captured.set(Optional.of(old));
      assertEquals(
          new Value("old"), fixture.cache.read(KEY, true, () -> new Value("old"), ignored -> 100));
      fixture.captured.set(Optional.of(fresh));
      when(fixture.versions.current(old)).thenReturn(false);

      assertEquals(
          new Value("live"),
          fixture.cache.read(
              KEY,
              true,
              () -> fail("The existing same-version entry must be validated before reuse"),
              () -> new Value("live"),
              ignored -> 100));

      verify(fixture.versions, never()).current(fresh);
      verify(fixture.versions, times(3)).current(old);
      assertEquals(0, fixture.backing.statistics().entries());
      assertEquals(
          new Value("new"), fixture.cache.read(KEY, true, () -> new Value("new"), ignored -> 100));
      verify(fixture.versions, times(2)).current(fresh);
    }
  }

  @Test
  void dirtyBeforeCaptureAndDirtyAfterLoaderNeverAdmitTransactionLocalValues() {
    try (var fixture = new Fixture()) {
      fixture.share.set(false);
      assertEquals(
          new Value("direct"),
          fixture.cache.read(
              KEY,
              true,
              () -> fail("Dirty transaction must not use bounded shared loader"),
              () -> new Value("direct"),
              ignored -> 100));
      assertEquals(0, fixture.backing.statistics().entries());
      fixture.share.set(true);
      assertEquals(
          new Value("direct-after"),
          fixture.cache.read(
              KEY,
              true,
              () -> {
                fixture.share.set(false);
                return new Value("uncommitted");
              },
              () -> new Value("direct-after"),
              ignored -> 100));
      assertEquals(0, fixture.backing.statistics().entries());
      fixture.share.set(true);
      assertEquals(
          new Value("committed"),
          fixture.cache.read(KEY, true, () -> new Value("committed"), ignored -> 100));
    }
  }

  @Test
  void staleBeforeOrDuringLoaderUsesOrdinaryProjectionWithoutCachingCandidate() {
    try (var fixture = new Fixture()) {
      var boundedCalls = new AtomicInteger();
      when(fixture.versions.current(any())).thenReturn(false);
      assertEquals(
          new Value("direct"),
          fixture.cache.read(
              KEY,
              true,
              () -> {
                boundedCalls.incrementAndGet();
                return new Value("stale-bounded");
              },
              () -> new Value("direct"),
              ignored -> 100));
      when(fixture.versions.current(any())).thenReturn(true);
      assertEquals(
          new Value("new-direct"),
          fixture.cache.read(
              KEY,
              true,
              () -> {
                boundedCalls.incrementAndGet();
                when(fixture.versions.current(any())).thenReturn(false);
                return new Value("raced");
              },
              () -> new Value("new-direct"),
              ignored -> 100));
      assertEquals(0, fixture.backing.statistics().entries());
      assertEquals(2, boundedCalls.get(), "Each pure projection is checked before publication");
      verify(fixture.versions, times(2)).canShare();
      verify(fixture.versions, times(2)).current(fixture.token);
    }
  }

  @Test
  void concurrentCapturesWithDifferentPendingXidsCoalesceAndValidateTheLoaderToken()
      throws Exception {
    try (var fixture = new Fixture();
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var loadedToken = token(List.of("11"));
      var joiningToken = token(List.of("22"));
      when(fixture.versions.capture(DOMAINS))
          .thenReturn(Optional.of(loadedToken))
          .thenReturn(Optional.of(joiningToken));
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var loads = new AtomicInteger();
      var first =
          callers.submit(
              () ->
                  fixture.cache.read(
                      KEY,
                      true,
                      () -> {
                        loads.incrementAndGet();
                        entered.countDown();
                        await(release);
                        return new Value("shared");
                      },
                      () -> fail("Unexpected fallback"),
                      ignored -> 100));
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var second =
            callers.submit(
                () ->
                    fixture.cache.read(
                        KEY,
                        true,
                        () -> fail("Joined caller must not execute its projection"),
                        () -> fail("Unexpected fallback"),
                        ignored -> 100));
        awaitStatistics(fixture.backing, statistics -> statistics.waiters() == 2);
        release.countDown();
        assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        assertEquals(1, loads.get());
        verify(fixture.versions, never()).current(joiningToken);
        verify(fixture.versions, times(3)).current(loadedToken);
        verify(fixture.versions).canShare();
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void onlyExplicitBypassRepeatsPureProjectionAndSqlFailuresAreUnwrappedWithoutReplay() {
    try (var fixture = new Fixture()) {
      var fallbacks = new AtomicInteger();
      assertEquals(
          new Value("direct"),
          fixture.cache.read(
              KEY,
              true,
              () -> {
                throw new ProjectionCache.Bypass();
              },
              () -> {
                fallbacks.incrementAndGet();
                return new Value("direct");
              },
              ignored -> 100));
      var expected = new DataRetrievalFailureException("SQL fixture failed");
      assertSame(
          expected,
          assertThrows(
              DataRetrievalFailureException.class,
              () ->
                  fixture.cache.read(
                      KEY,
                      true,
                      () -> {
                        throw expected;
                      },
                      () -> fail("SQL errors must not replay a projection"),
                      ignored -> 100)));
      assertEquals(1, fallbacks.get());
      assertEquals(0, fixture.backing.statistics().entries());
    }
  }

  @Test
  void outageDuringFillDiscardsCandidateAndUsesOneOrdinaryProjection() {
    try (var fixture = new Fixture()) {
      var direct = new AtomicInteger();
      assertEquals(
          new Value("direct"),
          fixture.cache.read(
              KEY,
              true,
              () -> {
                fixture.signals.state(2, false);
                return new Value("interrupted-fill");
              },
              () -> {
                direct.incrementAndGet();
                return new Value("direct");
              },
              ignored -> 100));
      assertEquals(1, direct.get());
      assertEquals(0, fixture.backing.statistics().entries());
      fixture.signals.state(3, true);
      assertEquals(
          new Value("after-recovery"),
          fixture.cache.read(KEY, true, () -> new Value("after-recovery"), ignored -> 100));
    }
  }

  @Test
  void cacheCapacityRefusesWithoutStartingAnotherSqlLoadAndRecoveryCanStillReadDirectly() {
    try (var fixture = new Fixture()) {
      doReturn(failed(SnapshotCache.Failure.CAPACITY))
          .when(fixture.backing)
          .readInline(any(), any());
      var refused =
          assertThrows(
              io.aeyer.plowshare.server.reads.ReadUnavailableException.class,
              () ->
                  fixture.cache.read(
                      KEY,
                      true,
                      () -> fail("Backing rejected before loader"),
                      () -> fail("Capacity exhaustion must not multiply uncached SQL loads"),
                      ignored -> 100));
      assertEquals(
          io.aeyer.plowshare.protocol.frames.Code.ARCHIVE_UNAVAILABLE,
          io.aeyer.plowshare.server.faults.Faults.of(refused).code(),
          "Never promise that an enclosing mutation has not started or invite automatic replay");
      fixture.signals.state(2, false);
      assertEquals(
          new Value("recovering"),
          fixture.cache.read(
              KEY,
              true,
              () -> fail("Listener outage blocks sharing"),
              () -> new Value("recovering"),
              ignored -> 100));
      fixture.signals.state(3, true);
      assertEquals(
          new Value("disabled"),
          fixture.cache.read(
              KEY,
              false,
              () -> fail("Selection is not cacheable"),
              () -> new Value("disabled"),
              ignored -> 100));
    }
  }

  @Test
  void recursiveAndInconsistentRequestFailuresAreNotHiddenByUncachedReplay() {
    try (var fixture = new Fixture()) {
      for (var failure :
          List.of(
              SnapshotCache.Failure.REENTRANT_LOAD, SnapshotCache.Failure.INCONSISTENT_REQUEST)) {
        doReturn(failed(failure)).when(fixture.backing).readInline(any(), any());
        assertEquals(
            failure,
            assertThrows(
                    SnapshotCache.CacheException.class,
                    () ->
                        fixture.cache.read(
                            KEY,
                            true,
                            () -> fail("Rejected"),
                            () -> fail("Contract errors must not replay"),
                            ignored -> 100))
                .failure());
      }
    }
  }

  @Test
  void staleListenerGenerationsCannotClearReopenOrDisableTheCurrentReadyGeneration() {
    try (var fixture = new Fixture()) {
      fixture.signals.state(3, true);
      fixture.signals.state(2, false);
      fixture.signals.state(3, false);
      verify(fixture.backing, times(2)).beginRecovery();
      assertEquals(
          new Value("ready"),
          fixture.cache.read(
              KEY,
              true,
              () -> new Value("ready"),
              () -> fail("Older listener state must have no effect"),
              ignored -> 100));
    }
  }

  @Test
  void reentrantOutageDuringRecoveryPreventsAnOlderTicketFromReopeningSharing() {
    try (var fixture = new Fixture()) {
      var injected = new AtomicBoolean();
      doAnswer(
              invocation -> {
                var ticket = invocation.callRealMethod();
                if (injected.compareAndSet(false, true)) fixture.signals.state(3, false);
                return ticket;
              })
          .when(fixture.backing)
          .beginRecovery();
      fixture.signals.state(2, true);
      assertEquals(
          new Value("outage"),
          fixture.cache.read(
              KEY,
              true,
              () -> fail("Newer outage must dominate older successful recovery"),
              () -> new Value("outage"),
              ignored -> 100));
      fixture.signals.state(4, true);
      assertEquals(
          new Value("recovered"),
          fixture.cache.read(
              KEY,
              true,
              () -> new Value("recovered"),
              () -> fail("Latest recovery should reopen"),
              ignored -> 100));
    }
  }

  @Test
  void concurrentOutageBlocksReadsWhileOlderRecoveryCallbackIsStillRunning() throws Exception {
    try (var fixture = new Fixture();
        var events = Executors.newVirtualThreadPerTaskExecutor()) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      doAnswer(
              invocation -> {
                entered.countDown();
                await(release);
                return invocation.callRealMethod();
              })
          .when(fixture.backing)
          .completeRecovery(any());
      var recovery = events.submit(() -> fixture.signals.state(2, true));
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        fixture.signals.state(3, false);
        assertEquals(
            new Value("outage"),
            fixture.cache.read(
                KEY,
                true,
                () -> fail("New state must block sharing before drainer resumes"),
                () -> new Value("outage"),
                ignored -> 100));
      } finally {
        release.countDown();
      }
      recovery.get(5, TimeUnit.SECONDS);
      assertEquals(
          new Value("still-outage"),
          fixture.cache.read(
              KEY,
              true,
              () -> fail("Old completion cannot reopen"),
              () -> new Value("still-outage"),
              ignored -> 100));
    }
  }

  private static SnapshotCache.Read<VersionedProjectionCache.Versioned<Value>> failed(
      SnapshotCache.Failure failure) {
    return new SnapshotCache.Read<>() {
      public java.util.concurrent.CompletionStage<
              SnapshotCache.Tracked<VersionedProjectionCache.Versioned<Value>>>
          result() {
        return java.util.concurrent.CompletableFuture.failedFuture(
            new SnapshotCache.CacheException(failure));
      }

      public boolean cancel() {
        return false;
      }
    };
  }

  private static ProjectionVersions.Token token(List<String> pending) {
    return new ProjectionVersions.Token(
        new UUID(0, 1), Map.of(ProjectionVersions.Domain.SEARCH, 4L), pending, null, null);
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failure);
    }
  }

  static final class Signals implements ProjectionChangeSignals {
    final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();
    final AtomicInteger disposed = new AtomicInteger();

    public Subscription subscribe(Set<ProjectionVersions.Domain> domains, Observer observer) {
      observers.add(observer);
      observer.stateChanged(new State(1, true));
      var open = new AtomicBoolean(true);
      return () -> {
        if (open.compareAndSet(true, false)) {
          observers.remove(observer);
          disposed.incrementAndGet();
        }
      };
    }

    void state(long generation, boolean ready) {
      observers.forEach(observer -> observer.stateChanged(new State(generation, ready)));
    }

    public void start() {}

    public boolean healthy() {
      return true;
    }

    public void close() {
      observers.clear();
    }
  }

  private static final class Fixture implements AutoCloseable {
    final CacheTestSupport.ManualTimer timer = new CacheTestSupport.ManualTimer();
    final ProjectionVersions versions = mock(ProjectionVersions.class);
    final AtomicBoolean share = new AtomicBoolean(true);
    final ProjectionVersions.Token token = token(List.of());
    final AtomicReference<Optional<ProjectionVersions.Token>> captured =
        new AtomicReference<>(Optional.of(token));
    final Signals signals = new Signals();
    final BoundedSnapshotCache<VersionedProjectionCache.Versioned<Value>> backing =
        spy(
            new BoundedSnapshotCache<>(
                namespace(),
                new SnapshotCache.Limits(
                    8, 2_000_000, 4, 4_000_000, 16, 8, 4, 64, Duration.ofSeconds(30)),
                Runnable::run,
                timer,
                Runnable::run));
    final VersionedProjectionCache<Value> cache;

    Fixture() {
      when(versions.capture(DOMAINS))
          .thenAnswer(invocation -> share.get() ? captured.get() : Optional.empty());
      when(versions.canShare()).thenAnswer(invocation -> share.get());
      when(versions.current(any())).thenReturn(true);
      cache = new VersionedProjectionCache<>(backing, versions, signals, DOMAINS, 1_000);
    }

    public void close() {
      cache.close();
      timer.shutdownNow();
    }
  }
}
