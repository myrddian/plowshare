package io.aeyer.plowshare.server.cache;

import static io.aeyer.plowshare.server.cache.CacheTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

class SnapshotCacheLoadingTest {
  @Test
  void concurrentMissesCoalesceAndPublishOneTrackedImmutableValue() {
    try (var fixture = new Fixture()) {
      var request = request("one");
      var calls = new AtomicInteger();
      var first =
          fixture.cache.read(
              request,
              () -> {
                calls.incrementAndGet();
                return loaded(request, "shared");
              });
      var second = fixture.cache.read(request, () -> fail("A coalesced loader must not run"));

      assertEquals(1, fixture.executor.size());
      assertEquals(1, fixture.cache.statistics().loads());
      assertEquals(2, fixture.cache.statistics().waiters());
      assertEquals(1, fixture.cache.statistics().coalesced());
      fixture.executor.runNext();

      var answer = first.result().toCompletableFuture().join();
      assertSame(answer, second.result().toCompletableFuture().join());
      assertEquals(new Value("shared"), answer.value());
      assertEquals(request, answer.request());
      assertEquals(1, calls.get());
      assertNoPhysicalReservations(fixture.cache);
      assertEquals(1, fixture.cache.statistics().entries());
      assertTrue(fixture.timer.cancelled(0));
      assertTrue(fixture.timer.cancelled(1));
    }
  }

  @Test
  void cancellingOneWaiterNeverCancelsAnotherAndLateTimersCannotUndoSuccess() {
    try (var fixture = new Fixture()) {
      var request = request("one");
      var first = fixture.cache.read(request, () -> loaded(request, "shared"));
      var second = fixture.cache.read(request, () -> fail("Joined"));

      assertTrue(first.cancel());
      assertFalse(first.cancel());
      assertInstanceOf(CancellationException.class, failure(first));
      awaitStatistics(fixture.cache, statistics -> statistics.waiters() == 1);
      fixture.timer.fire(0); // A timer may already have started when cancel reported success.
      assertFalse(second.result().toCompletableFuture().isDone());
      fixture.executor.runNext();
      fixture.timer.fire(0);
      fixture.timer.fire(1);

      assertEquals(new Value("shared"), second.result().toCompletableFuture().join().value());
      assertNoPhysicalReservations(fixture.cache);
      assertFalse(second.cancel());
    }
  }

  @Test
  void oneWaiterTimeoutLeavesTheOtherReadAndSharedLoaderIntact() {
    try (var fixture = new Fixture()) {
      var request = request("one");
      var first = fixture.cache.read(request, () -> loaded(request, "shared"));
      var second = fixture.cache.read(request, () -> fail("Joined"));

      fixture.timer.fire(0);
      assertInstanceOf(TimeoutException.class, failure(first));
      assertEquals(1, fixture.cache.statistics().loads());
      awaitStatistics(fixture.cache, statistics -> statistics.waiters() == 1);
      fixture.executor.runNext();
      fixture.timer.fire(1);

      assertEquals(new Value("shared"), second.result().toCompletableFuture().join().value());
      assertEquals(1, fixture.cache.statistics().entries());
      assertNoPhysicalReservations(fixture.cache);
    }
  }

  @Test
  void abandonedQueuedLoadRetainsReservationUntilItsTaskActuallyExits() {
    try (var fixture = new Fixture(oneLoad())) {
      var request = request("one");
      var calls = new AtomicInteger();
      var read =
          fixture.cache.read(
              request,
              () -> {
                calls.incrementAndGet();
                return loaded(request, "abandoned");
              });
      long reserved = fixture.cache.statistics().loadingWeight();

      assertTrue(read.cancel());
      assertEquals(1, fixture.cache.statistics().loads());
      assertEquals(reserved, fixture.cache.statistics().loadingWeight());
      assertCacheFailure(
          SnapshotCache.Failure.CAPACITY,
          fixture.cache.read(request("two"), () -> fail("No capacity")));
      fixture.executor.runNext();

      assertEquals(0, calls.get(), "Unstarted abandoned work can skip the loader");
      assertNoPhysicalReservations(fixture.cache);
      assertEquals(0, fixture.cache.statistics().entries());
      var retry = fixture.cache.read(request, () -> loaded(request, "new"));
      fixture.executor.runNext();
      assertEquals(new Value("new"), retry.result().toCompletableFuture().join().value());
    }
  }

  @Test
  void uncooperativeRunningLoaderStillOccupiesCapacityAfterAllWaitersLeave() throws Exception {
    try (var fixture = new Fixture(oneLoad())) {
      var request = request("one");
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var read =
          fixture.cache.read(
              request,
              () -> {
                entered.countDown();
                boolean waiting = true;
                while (waiting) {
                  try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    waiting = false;
                  } catch (InterruptedException ignored) {
                    // Model a JDBC/provider adapter that ignores interruption and remains
                    // physically live.
                  }
                }
                return loaded(request, "abandoned");
              });
      var worker = Thread.ofVirtual().start(fixture.executor.takeNext());
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        long reserved = fixture.cache.statistics().loadingWeight();
        worker.interrupt();
        fixture.timer.fire(0);
        assertInstanceOf(TimeoutException.class, failure(read));
        assertEquals(1, fixture.cache.statistics().loads());
        assertEquals(reserved, fixture.cache.statistics().loadingWeight());
        assertCacheFailure(
            SnapshotCache.Failure.CAPACITY,
            fixture.cache.read(request("two"), () -> fail("Still physically full")));
      } finally {
        release.countDown();
        worker.join(Duration.ofSeconds(5));
      }
      assertFalse(worker.isAlive());
      assertNoPhysicalReservations(fixture.cache);
      assertEquals(0, fixture.cache.statistics().entries());
    }
  }

  @Test
  void invalidatedOldLoadCannotOverwriteNewFillForRecreatedKey() {
    try (var fixture = new Fixture()) {
      var request = request("one");
      var replacement = new AtomicReference<SnapshotCache.Read<Value>>();
      var old =
          fixture.cache.read(
              request,
              () -> {
                fixture.cache.invalidate(request.key());
                replacement.set(fixture.cache.read(request, () -> loaded(request, "new")));
                // Finish the replacement first while the old physical loader is still running.
                assertEquals(2, fixture.cache.statistics().loads());
                fixture.executor.runLast();
                assertEquals(
                    new Value("new"),
                    replacement.get().result().toCompletableFuture().join().value());
                return loaded(request, "old");
              });
      fixture.executor.runNext();

      assertCacheFailure(SnapshotCache.Failure.INVALIDATED, old);
      var hit = fixture.cache.read(request, () -> fail("Old completion must not remove new entry"));
      assertSame(
          replacement.get().result().toCompletableFuture().join(),
          hit.result().toCompletableFuture().join());
      assertEquals(1, fixture.cache.statistics().entries());
      assertNoPhysicalReservations(fixture.cache);
      assertEquals(2, fixture.cache.statistics().dependencyLinks());
    }
  }

  @Test
  void dependencyInvalidationFencesEveryCoalescedWaiterAndAllowsExplicitRetry() {
    try (var fixture = new Fixture()) {
      var request = request("one");
      var first = fixture.cache.read(request, () -> loaded(request, "old"));
      var second = fixture.cache.read(request, () -> fail("Joined"));
      fixture.cache.invalidate(request.dependencies().iterator().next());

      assertCacheFailure(SnapshotCache.Failure.INVALIDATED, first);
      assertCacheFailure(SnapshotCache.Failure.INVALIDATED, second);
      assertEquals(1, fixture.cache.statistics().loads());
      var fresh = fixture.cache.read(request, () -> loaded(request, "new"));
      fixture.executor.runLast();
      fixture.executor.runNext();

      assertEquals(new Value("new"), fresh.result().toCompletableFuture().join().value());
      assertNoPhysicalReservations(fixture.cache);
      assertEquals(1, fixture.cache.statistics().entries());
    }
  }

  @Test
  void failedLoaderIsNotCachedAndNextRequestStartsASeparateLoad() {
    try (var fixture = new Fixture()) {
      var request = request("one");
      var expected = new IllegalStateException("Loader fixture failure");
      var first =
          fixture.cache.read(
              request,
              () -> {
                throw expected;
              });
      var second = fixture.cache.read(request, () -> fail("Joined"));
      fixture.executor.runNext();

      assertSame(expected, failure(first));
      assertSame(expected, failure(second));
      assertNoPhysicalReservations(fixture.cache);
      assertEquals(0, fixture.cache.statistics().entries());
      assertEquals(1, fixture.cache.statistics().loadFailures());
      var retry = fixture.cache.read(request, () -> loaded(request, "new"));
      fixture.executor.runNext();
      assertEquals(new Value("new"), retry.result().toCompletableFuture().join().value());
    }
  }

  @Test
  void executorRejectionFailsTheReadAndReleasesItsTimerAndReservations() {
    try (var fixture = new Fixture()) {
      fixture.executor.reject = true;
      var request = request("one");
      var read = fixture.cache.read(request, () -> fail("Rejected before execution"));

      assertInstanceOf(RejectedExecutionException.class, failure(read));
      assertNoPhysicalReservations(fixture.cache);
      assertEquals(0, fixture.cache.statistics().entries());
      assertTrue(fixture.timer.cancelled(0));
      fixture.executor.reject = false;
      var retry = fixture.cache.read(request, () -> loaded(request, "new"));
      fixture.executor.runNext();
      assertEquals(new Value("new"), retry.result().toCompletableFuture().join().value());
    }
  }

  @Test
  void rejectedTimerFailsOnlyItsWaiterAndDoesNotCancelExistingSharedRead() {
    try (var fixture = new Fixture()) {
      var request = request("one");
      var first = fixture.cache.read(request, () -> loaded(request, "shared"));
      fixture.timer.reject = true;
      var second = fixture.cache.read(request, () -> fail("Joined"));

      assertInstanceOf(RejectedExecutionException.class, failure(second));
      awaitStatistics(fixture.cache, statistics -> statistics.waiters() == 1);
      assertEquals(1, fixture.cache.statistics().loads());
      fixture.executor.runNext();

      assertEquals(new Value("shared"), first.result().toCompletableFuture().join().value());
      assertNoPhysicalReservations(fixture.cache);
    }
  }

  @Test
  void rejectedInitialTimerNeverInvokesLoaderAndEventuallyReleasesQueuedReservation() {
    try (var fixture = new Fixture()) {
      fixture.timer.reject = true;
      var read = fixture.cache.read(request("one"), () -> fail("No active waiter"));

      assertInstanceOf(RejectedExecutionException.class, failure(read));
      fixture.executor.runNext();
      assertNoPhysicalReservations(fixture.cache);
      assertEquals(0, fixture.cache.statistics().entries());
    }
  }

  @Test
  void waiterAndLoadingWeightBoundsRejectWithoutRetainingExtraWork() {
    var limits =
        new SnapshotCache.Limits(4, 100_000, 4, 100_000, 2, 1, 4, 32, Duration.ofSeconds(30));
    try (var fixture = new Fixture(limits)) {
      var first = fixture.cache.read(request("one"), () -> loaded(request("one"), "one"));
      assertCacheFailure(
          SnapshotCache.Failure.CAPACITY,
          fixture.cache.read(request("one"), () -> fail("Per-load waiter limit")));
      var second = fixture.cache.read(request("two"), () -> loaded(request("two"), "two"));
      long reserved = fixture.cache.statistics().loadingWeight();
      assertCacheFailure(
          SnapshotCache.Failure.CAPACITY,
          fixture.cache.read(request("three"), () -> fail("Global waiter limit")));
      assertEquals(2, fixture.executor.size());
      assertEquals(reserved, fixture.cache.statistics().loadingWeight());
      fixture.executor.runNext();
      fixture.executor.runNext();
      assertEquals(new Value("one"), first.result().toCompletableFuture().join().value());
      assertEquals(new Value("two"), second.result().toCompletableFuture().join().value());
    }
    var weighted =
        new SnapshotCache.Limits(4, 100_000, 4, 3_000, 8, 4, 4, 32, Duration.ofSeconds(30));
    try (var fixture = new Fixture(weighted)) {
      var first = fixture.cache.read(request("one"), () -> loaded(request("one"), "one"));
      long reserved = fixture.cache.statistics().loadingWeight();
      assertCacheFailure(
          SnapshotCache.Failure.CAPACITY,
          fixture.cache.read(
              request("two"), () -> fail("Metadata and DTO reservation exceed weight")));
      assertEquals(1, fixture.executor.size());
      assertEquals(reserved, fixture.cache.statistics().loadingWeight());
      fixture.executor.runNext();
      assertEquals(new Value("one"), first.result().toCompletableFuture().join().value());
    }
  }

  @Test
  void perLoadWaiterLimitIncludesCancelledWaiterUntilItsCompletionCallbackExits() throws Exception {
    var limits =
        new SnapshotCache.Limits(4, 100_000, 4, 100_000, 8, 2, 4, 32, Duration.ofSeconds(30));
    try (var fixture = new Fixture(limits)) {
      var request = request("one");
      var first = fixture.cache.read(request, () -> loaded(request, "shared"));
      var cancelled = fixture.cache.read(request, () -> fail("Joined"));
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var callback =
          cancelled
              .result()
              .handle(
                  (answer, error) -> {
                    assertNotNull(error);
                    entered.countDown();
                    awaitRelease(release);
                    return true;
                  });
      try {
        assertTrue(cancelled.cancel());
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(
            2,
            fixture.cache.statistics().waiters(),
            "One active waiter and its cancelled sibling's delivery remain retained");
        assertCacheFailure(
            SnapshotCache.Failure.CAPACITY,
            fixture.cache.read(
                request, () -> fail("Per-load capacity includes delivering callbacks")));
        assertEquals(1, fixture.executor.size());
        assertFalse(first.result().toCompletableFuture().isDone());
      } finally {
        release.countDown();
      }
      assertTrue(callback.toCompletableFuture().get(5, TimeUnit.SECONDS));
      awaitStatistics(fixture.cache, statistics -> statistics.waiters() == 1);
      var third = fixture.cache.read(request, () -> fail("The original load is still joinable"));
      assertEquals(1, fixture.executor.size());
      fixture.executor.runNext();

      assertInstanceOf(CancellationException.class, failure(cancelled));
      assertEquals(new Value("shared"), first.result().toCompletableFuture().join().value());
      assertSame(
          first.result().toCompletableFuture().join(), third.result().toCompletableFuture().join());
      assertNoPhysicalReservations(fixture.cache);
    }
  }

  @Test
  void loaderSchedulerCancellationAndCompletionCallbacksRunOutsideCacheLock() {
    try (var fixture = new Fixture()) {
      fixture.timer.scheduling = () -> assertUnlocked(fixture.cache);
      fixture.timer.cancelling = () -> assertUnlocked(fixture.cache);
      var request = request("one");
      var read =
          fixture.cache.read(
              request,
              () -> {
                assertUnlocked(fixture.cache);
                return loaded(request, "one");
              });
      var callback = read.result().thenRun(() -> assertUnlocked(fixture.cache));
      fixture.executor.runNext();
      callback.toCompletableFuture().join();
      assertEquals(new Value("one"), read.result().toCompletableFuture().join().value());
    }
  }

  @Test
  void inlineExecutorAndReentrantLoaderCanReadAnotherKeyWithoutDuplicatingLoads() {
    try (var timer = new ManualTimer();
        var cache = new BoundedSnapshotCache<Value>(namespace(), limits(), Runnable::run, timer)) {
      assertTrue(cache.completeRecovery(cache.beginRecovery()));
      var outer = request("outer");
      var inner = request("inner");
      var read =
          cache.read(
              outer,
              () -> {
                assertUnlocked(cache);
                var nested = cache.read(inner, () -> loaded(inner, "inner"));
                assertEquals(
                    new Value("inner"), nested.result().toCompletableFuture().join().value());
                return loaded(outer, "outer");
              });

      assertEquals(new Value("outer"), read.result().toCompletableFuture().join().value());
      assertEquals(2, cache.statistics().entries());
      assertNoPhysicalReservations(cache);
    }
  }

  @Test
  void blockingFirstCompletionCallbackDoesNotBlockAnotherCoalescedWaiter() throws Exception {
    try (var fixture = new Fixture()) {
      var request = request("one");
      var first = fixture.cache.read(request, () -> loaded(request, "shared"));
      var second = fixture.cache.read(request, () -> fail("Joined"));
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var blocked =
          first
              .result()
              .thenRun(
                  () -> {
                    entered.countDown();
                    awaitRelease(release);
                  });
      var worker = Thread.ofVirtual().start(fixture.executor.takeNext());
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(
            new Value("shared"),
            second.result().toCompletableFuture().get(5, TimeUnit.SECONDS).value());
        assertFalse(blocked.toCompletableFuture().isDone());
        assertTrue(
            fixture.cache.statistics().waiters() >= 1,
            "Blocked delivery must retain a bounded waiter reservation");
        assertEquals(
            1,
            fixture.cache.statistics().loads(),
            "Physical-flight reservation includes its unfinished delivery tasks");
      } finally {
        release.countDown();
        worker.join(Duration.ofSeconds(5));
      }
      assertFalse(worker.isAlive());
      blocked.toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertNoPhysicalReservations(fixture.cache);
    }
  }

  @Test
  void blockingFirstTimeoutCallbackDoesNotBlockAnotherWaiterTimeout() throws Exception {
    try (var fixture = new Fixture()) {
      var request = request("one");
      var first = fixture.cache.read(request, () -> fail("No waiter survives the deadlines"));
      var second = fixture.cache.read(request, () -> fail("Joined"));
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var blocked =
          first
              .result()
              .handle(
                  (answer, error) -> {
                    assertNotNull(error);
                    entered.countDown();
                    awaitRelease(release);
                    return true;
                  });
      var timerWorker = Thread.ofVirtual().start(() -> fixture.timer.fire(0));
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        fixture.timer.fire(1);
        var failed =
            second
                .result()
                .handle((answer, error) -> error)
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertNotNull(failed);
        assertInstanceOf(TimeoutException.class, failure(second));
        assertFalse(blocked.toCompletableFuture().isDone());
        assertTrue(fixture.cache.statistics().waiters() >= 1);
        // The queued physical task must also stay charged until it can actually exit.
        assertEquals(1, fixture.cache.statistics().loads());
      } finally {
        release.countDown();
        timerWorker.join(Duration.ofSeconds(5));
      }
      assertFalse(timerWorker.isAlive());
      assertTrue(blocked.toCompletableFuture().get(5, TimeUnit.SECONDS));
      fixture.executor.runNext();
      assertNoPhysicalReservations(fixture.cache);
      assertEquals(0, fixture.cache.statistics().entries());
    }
  }

  @Test
  void rejectedResultDispatchAttemptsEverySiblingAndFailsWithoutStrandingReservations() {
    var executor = new HeldExecutor();
    var dispatches = new AtomicInteger();
    Executor results =
        command -> {
          dispatches.incrementAndGet();
          throw new RejectedExecutionException("Result fixture rejected completion");
        };
    try (var timer = new ManualTimer();
        var cache =
            new BoundedSnapshotCache<Value>(namespace(), limits(), executor, timer, results)) {
      assertTrue(cache.completeRecovery(cache.beginRecovery()));
      var request = request("one");
      var first = cache.read(request, () -> loaded(request, "shared"));
      var second = cache.read(request, () -> fail("Joined"));

      executor.runNext();

      assertEquals(2, dispatches.get(), "Dispatch failure must not skip later siblings");
      assertInstanceOf(RejectedExecutionException.class, failure(first));
      assertInstanceOf(RejectedExecutionException.class, failure(second));
      assertFalse(first.cancel());
      assertFalse(second.cancel());
      assertTrue(timer.cancelled(0));
      assertTrue(timer.cancelled(1));
      assertNoPhysicalReservations(cache);
    }
  }

  @Test
  void acceptedSiblingDispatchPrecedesBlockingRejectedResultFallbackAndRetainsItsCharge()
      throws Exception {
    var executor = new HeldExecutor();
    var accepted = new HeldExecutor();
    var dispatches = new AtomicInteger();
    Executor results =
        command -> {
          if (dispatches.incrementAndGet() == 1)
            throw new RejectedExecutionException("First result fixture rejected completion");
          accepted.execute(command);
        };
    try (var timer = new ManualTimer();
        var cache =
            new BoundedSnapshotCache<Value>(namespace(), limits(), executor, timer, results)) {
      assertTrue(cache.completeRecovery(cache.beginRecovery()));
      var request = request("one");
      var first = cache.read(request, () -> loaded(request, "shared"));
      var second = cache.read(request, () -> fail("Joined"));
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      BiFunction<SnapshotCache.Tracked<Value>, Throwable, Boolean> completion =
          (answer, error) -> {
            if (error == null) return false;
            assertEquals(
                2, dispatches.get(), "Normal sibling dispatch must precede fallback callbacks");
            assertUnlocked(cache);
            entered.countDown();
            awaitRelease(release);
            return true;
          };
      var firstCallback = first.result().handle(completion);
      var secondCallback = second.result().handle(completion);
      var worker = Thread.ofVirtual().start(executor.takeNext());
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(1, accepted.size());
        // Waiters have no dispatch-order contract; whichever task was rejected owns the fallback.
        boolean rejectedFirst = first.result().toCompletableFuture().isCompletedExceptionally();
        var successful = rejectedFirst ? second : first;
        var fallback = rejectedFirst ? firstCallback : secondCallback;
        accepted.runNext();
        assertEquals(
            new Value("shared"),
            successful.result().toCompletableFuture().get(5, TimeUnit.SECONDS).value());
        assertFalse(fallback.toCompletableFuture().isDone());
        assertEquals(1, cache.statistics().waiters(), "Blocked fallback retains its waiter charge");
        assertEquals(
            1, cache.statistics().loads(), "Its loader cannot exit before fallback returns");
        assertTrue(cache.statistics().loadingWeight() > 0);
      } finally {
        release.countDown();
        worker.join(Duration.ofSeconds(5));
      }
      assertFalse(worker.isAlive());
      boolean rejectedFirst = firstCallback.toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertNotEquals(rejectedFirst, secondCallback.toCompletableFuture().get(5, TimeUnit.SECONDS));
      assertInstanceOf(RejectedExecutionException.class, failure(rejectedFirst ? first : second));
      assertNoPhysicalReservations(cache);
    }
  }

  private static void awaitRelease(CountDownLatch release) {
    try {
      assertTrue(release.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Fixture delivery was interrupted", failure);
    }
  }

  private static void assertUnlocked(SnapshotCache<?> cache) {
    var result = new CompletableFuture<Void>();
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                cache.statistics();
                result.complete(null);
              } catch (Throwable failure) {
                result.completeExceptionally(failure);
              }
            });
    try {
      result.get(5, TimeUnit.SECONDS);
    } catch (Exception failure) {
      throw new AssertionError("A callback held the cache lock", failure);
    }
  }

  private static void assertCacheFailure(
      SnapshotCache.Failure expected, SnapshotCache.Read<?> read) {
    assertEquals(
        expected, assertInstanceOf(SnapshotCache.CacheException.class, failure(read)).failure());
  }

  private static void assertNoPhysicalReservations(SnapshotCache<?> cache) {
    var statistics =
        awaitStatistics(
            cache,
            value -> value.loads() == 0 && value.loadingWeight() == 0 && value.waiters() == 0);
    assertEquals(0, statistics.loads());
    assertEquals(0, statistics.loadingWeight());
    assertEquals(0, statistics.waiters());
  }

  private static SnapshotCache.Limits oneLoad() {
    return new SnapshotCache.Limits(4, 100_000, 1, 100_000, 8, 4, 4, 32, Duration.ofSeconds(30));
  }

  private static final class Fixture implements AutoCloseable {
    final HeldExecutor executor = new HeldExecutor();
    final ManualTimer timer = new ManualTimer();
    final BoundedSnapshotCache<Value> cache;

    Fixture() {
      this(limits());
    }

    Fixture(SnapshotCache.Limits limits) {
      cache = new BoundedSnapshotCache<>(namespace(), limits, executor, timer);
      assertTrue(cache.completeRecovery(cache.beginRecovery()));
    }

    @Override
    public void close() {
      cache.close();
      timer.shutdownNow();
    }
  }
}
