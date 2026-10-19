package io.aeyer.plowshare.server.cache;

import static io.aeyer.plowshare.server.cache.CacheTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Real overlapping callers with controlled loader boundaries; no timers need to fire. */
class SnapshotCacheConcurrencyTest {
  @Test
  void parallelSameKeyReadsElectExactlyOneLoaderAndShareItsTrackedValue() throws Exception {
    var executor = new HeldExecutor();
    var bounds =
        new SnapshotCache.Limits(4, 100_000, 4, 100_000, 16, 16, 4, 32, Duration.ofSeconds(30));
    try (var timer = new ManualTimer();
        var callers = Executors.newVirtualThreadPerTaskExecutor();
        var cache = new BoundedSnapshotCache<Value>(namespace(), bounds, executor, timer)) {
      assertTrue(cache.completeRecovery(cache.beginRecovery()));
      var request = request("shared");
      var go = new CountDownLatch(1);
      var started = new CountDownLatch(12);
      var loads = new AtomicInteger();
      var pending = new ArrayList<Future<SnapshotCache.Read<Value>>>();
      for (int n = 0; n < 12; n++) {
        pending.add(
            callers.submit(
                () -> {
                  started.countDown();
                  await(go);
                  return cache.read(
                      request,
                      () -> {
                        loads.incrementAndGet();
                        return loaded(request, "shared");
                      });
                }));
      }
      assertTrue(started.await(5, TimeUnit.SECONDS));
      go.countDown();
      var reads = new ArrayList<SnapshotCache.Read<Value>>();
      for (var pendingRead : pending) reads.add(pendingRead.get(5, TimeUnit.SECONDS));
      assertEquals(1, executor.size());
      assertEquals(12, cache.statistics().waiters());
      executor.runNext();

      var answer = reads.getFirst().result().toCompletableFuture().get(5, TimeUnit.SECONDS);
      for (var read : reads)
        assertSame(answer, read.result().toCompletableFuture().get(5, TimeUnit.SECONDS));
      assertEquals(1, loads.get());
      assertEquals(11, cache.statistics().coalesced());
      quiescent(cache);
    }
  }

  @Test
  void sameKeyRecursiveLoaderFailsImmediatelyWithoutWaitingForADeadline() {
    var executor = new HeldExecutor();
    try (var timer = new ManualTimer();
        var cache = new BoundedSnapshotCache<Value>(namespace(), limits(), executor, timer)) {
      assertTrue(cache.completeRecovery(cache.beginRecovery()));
      var request = request("one");
      var nested = new AtomicReference<SnapshotCache.Read<Value>>();
      var outer =
          cache.read(
              request,
              () -> {
                nested.set(cache.read(request, () -> fail("Recursive loader must not dispatch")));
                assertFailure(SnapshotCache.Failure.REENTRANT_LOAD, nested.get());
                return loaded(request, "outer");
              });

      assertTimeoutPreemptively(Duration.ofSeconds(5), executor::runNext);

      assertEquals(new Value("outer"), outer.result().toCompletableFuture().join().value());
      assertFailure(SnapshotCache.Failure.REENTRANT_LOAD, nested.get());
      assertEquals(1, timer.size(), "The rejected recursion does not install another timeout");
      assertEquals(0, executor.size());
      quiescent(cache);
    }
  }

  @Test
  void inlineLoadsPreserveCallerThreadAndAllowAcyclicNestedKeys() {
    var executor = new HeldExecutor();
    try (var timer = new ManualTimer();
        var cache = new BoundedSnapshotCache<Value>(namespace(), limits(), executor, timer)) {
      assertTrue(cache.completeRecovery(cache.beginRecovery()));
      var outer = request("outer");
      var inner = request("inner");
      var read =
          assertTimeoutPreemptively(
              Duration.ofSeconds(5),
              () -> {
                var owner = Thread.currentThread();
                return cache.readInline(
                    outer,
                    () -> {
                      assertSame(owner, Thread.currentThread());
                      var nested =
                          cache.readInline(
                              inner,
                              () -> {
                                assertSame(owner, Thread.currentThread());
                                assertFailure(
                                    SnapshotCache.Failure.REENTRANT_LOAD,
                                    cache.readInline(outer, () -> fail("Inline dependency cycle")));
                                return loaded(inner, "inner");
                              });
                      assertEquals(
                          new Value("inner"), nested.result().toCompletableFuture().join().value());
                      return loaded(outer, "outer");
                    });
              });

      assertEquals(new Value("outer"), read.result().toCompletableFuture().join().value());
      assertEquals(
          0, executor.size(), "Inline mode does not switch the loader's transaction thread");
      assertEquals(2, cache.statistics().entries());
      quiescent(cache);
    }
  }

  @Test
  void concurrentInlineCallerJoinsTheElectedCallerWithoutRunningItsOwnLoader() throws Exception {
    var executor = new HeldExecutor();
    try (var timer = new ManualTimer();
        var owner = Executors.newVirtualThreadPerTaskExecutor();
        var cache = new BoundedSnapshotCache<Value>(namespace(), limits(), executor, timer)) {
      assertTrue(cache.completeRecovery(cache.beginRecovery()));
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var request = request("one");
      var elected =
          owner.submit(
              () -> {
                var thread = Thread.currentThread();
                return cache.readInline(
                    request,
                    () -> {
                      assertSame(thread, Thread.currentThread());
                      entered.countDown();
                      await(release);
                      return loaded(request, "shared");
                    });
              });
      SnapshotCache.Read<Value> joined;
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        joined = cache.readInline(request, () -> fail("Joiner must preserve the elected read"));
        assertFalse(joined.result().toCompletableFuture().isDone());
        assertEquals(1, cache.statistics().loads());
        assertEquals(2, cache.statistics().waiters());
        assertEquals(0, executor.size());
      } finally {
        release.countDown();
      }
      assertSame(
          elected.get(5, TimeUnit.SECONDS).result().toCompletableFuture().get(5, TimeUnit.SECONDS),
          joined.result().toCompletableFuture().get(5, TimeUnit.SECONDS));
      quiescent(cache);
    }
  }

  @Test
  void independentConcurrentLoadersRejectAJoinCycleBeforeEitherDeadline() throws Exception {
    try (var timer = new ManualTimer();
        var loaders = Executors.newVirtualThreadPerTaskExecutor();
        var cache = new BoundedSnapshotCache<Value>(namespace(), limits(), loaders, timer)) {
      assertTrue(cache.completeRecovery(cache.beginRecovery()));
      var a = request("a");
      var b = request("b");
      var entered = new CountDownLatch(2);
      var allowA = new CountDownLatch(1);
      var aJoinedB = new CountDownLatch(1);
      var allowB = new CountDownLatch(1);
      var rejected = new AtomicReference<SnapshotCache.Read<Value>>();
      var first =
          cache.read(
              a,
              () -> {
                entered.countDown();
                await(allowA);
                var nested = cache.readInline(b, () -> fail("B already has a loader"));
                aJoinedB.countDown();
                assertEquals(
                    new Value("b"),
                    nested.result().toCompletableFuture().get(5, TimeUnit.SECONDS).value());
                return loaded(a, "a");
              });
      var second =
          cache.read(
              b,
              () -> {
                entered.countDown();
                await(allowB);
                rejected.set(cache.readInline(a, () -> fail("A already has a loader")));
                assertFailure(SnapshotCache.Failure.REENTRANT_LOAD, rejected.get());
                return loaded(b, "b");
              });
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        allowA.countDown();
        assertTrue(aJoinedB.await(5, TimeUnit.SECONDS));
        allowB.countDown();
        assertEquals(
            new Value("a"), first.result().toCompletableFuture().get(5, TimeUnit.SECONDS).value());
        assertEquals(
            new Value("b"), second.result().toCompletableFuture().get(5, TimeUnit.SECONDS).value());
        assertFailure(SnapshotCache.Failure.REENTRANT_LOAD, rejected.get());
        quiescent(cache);
      } finally {
        allowA.countDown();
        allowB.countDown();
      }
    }
  }

  @Test
  void completionCallbackCanInvalidateAndInlineReloadTheSameKeyAfterLoaderExit() {
    var executor = new HeldExecutor();
    try (var timer = new ManualTimer();
        var cache =
            new BoundedSnapshotCache<Value>(
                namespace(), limits(), executor, timer, Runnable::run)) {
      assertTrue(cache.completeRecovery(cache.beginRecovery()));
      var request = request("one");
      var first = cache.read(request, () -> loaded(request, "first"));
      var callback =
          first
              .result()
              .thenApply(
                  answer -> {
                    cache.invalidate(request.key());
                    var next = cache.readInline(request, () -> loaded(request, "second"));
                    return next.result().toCompletableFuture().join();
                  });

      assertTimeoutPreemptively(Duration.ofSeconds(5), executor::runNext);

      var reloaded = callback.toCompletableFuture().join();
      assertEquals(new Value("second"), reloaded.value());
      assertEquals(new Value("first"), first.result().toCompletableFuture().join().value());
      assertSame(
          reloaded,
          cache
              .read(request, () -> fail("Callback's new fill is current"))
              .result()
              .toCompletableFuture()
              .join());
      quiescent(cache);
    }
  }

  @Test
  void failedLoaderRestoresContextBeforeInlineFailureCallbackRetriesTheSameKey() {
    var executor = new HeldExecutor();
    try (var timer = new ManualTimer();
        var cache =
            new BoundedSnapshotCache<Value>(
                namespace(), limits(), executor, timer, Runnable::run)) {
      assertTrue(cache.completeRecovery(cache.beginRecovery()));
      var request = request("one");
      var failed =
          cache.read(
              request,
              () -> {
                throw new IllegalStateException("source fixture failed");
              });
      var callback =
          failed
              .result()
              .handle(
                  (answer, error) -> {
                    assertNotNull(error);
                    return cache
                        .readInline(request, () -> loaded(request, "retry"))
                        .result()
                        .toCompletableFuture()
                        .join();
                  });

      assertTimeoutPreemptively(Duration.ofSeconds(5), executor::runNext);

      assertEquals(new Value("retry"), callback.toCompletableFuture().join().value());
      assertInstanceOf(IllegalStateException.class, failure(failed));
      quiescent(cache);
    }
  }

  @Test
  void closeDuringInlineLoadRejectsFillAndRetainsPhysicalCapacityUntilActualExit()
      throws Exception {
    var executor = new HeldExecutor();
    try (var timer = new ManualTimer();
        var caller = Executors.newVirtualThreadPerTaskExecutor()) {
      var cache = new BoundedSnapshotCache<Value>(namespace(), limits(), executor, timer);
      try {
        assertTrue(cache.completeRecovery(cache.beginRecovery()));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var request = request("one");
        var read =
            caller.submit(
                () ->
                    cache.readInline(
                        request,
                        () -> {
                          entered.countDown();
                          await(release);
                          return loaded(request, "closed");
                        }));
        try {
          assertTrue(entered.await(5, TimeUnit.SECONDS));
          long reservation = cache.statistics().loadingWeight();
          cache.close();
          assertEquals(1, cache.statistics().loads());
          assertEquals(reservation, cache.statistics().loadingWeight());
          assertFailure(
              SnapshotCache.Failure.CLOSED,
              cache.readInline(request("later"), () -> fail("Cache closed")));
        } finally {
          release.countDown();
        }
        assertFailure(SnapshotCache.Failure.CLOSED, read.get(5, TimeUnit.SECONDS));
        assertEquals(0, cache.statistics().entries());
        quiescent(cache);
      } finally {
        cache.close();
      }
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Controlled cache operation interrupted", failure);
    }
  }

  private static void assertFailure(SnapshotCache.Failure expected, SnapshotCache.Read<?> read) {
    assertEquals(
        expected, assertInstanceOf(SnapshotCache.CacheException.class, failure(read)).failure());
  }

  private static void quiescent(SnapshotCache<?> cache) {
    awaitStatistics(
        cache, value -> value.loads() == 0 && value.waiters() == 0 && value.loadingWeight() == 0);
  }
}
