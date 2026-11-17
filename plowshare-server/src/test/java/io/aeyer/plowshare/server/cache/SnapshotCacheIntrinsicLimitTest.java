package io.aeyer.plowshare.server.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

import io.aeyer.plowshare.server.cache.CacheTestSupport.HeldExecutor;
import io.aeyer.plowshare.server.cache.CacheTestSupport.ManualTimer;
import io.aeyer.plowshare.server.cache.CacheTestSupport.Value;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SnapshotCacheIntrinsicLimitTest {
  private record Fixture(
      HeldExecutor executor, ManualTimer timer, BoundedSnapshotCache<Value> cache)
      implements AutoCloseable {
    @Override
    public void close() {
      cache.close();
      timer.shutdownNow();
    }
  }

  private static Fixture fixture(int dependenciesPerKey, int totalLinks) {
    var executor = new HeldExecutor();
    var timer = new ManualTimer();
    var limits =
        new SnapshotCache.Limits(
            4, 10_000, 1, 10_000, 1, 1, dependenciesPerKey, totalLinks, Duration.ofSeconds(5));
    var cache =
        new BoundedSnapshotCache<Value>(CacheTestSupport.namespace(), limits, executor, timer);
    cache.completeRecovery(cache.beginRecovery());
    return new Fixture(executor, timer, cache);
  }

  private static SnapshotCache.Request request(String key, int dependencies, long weight) {
    Set<SnapshotCache.Dependency> selected =
        dependencies == 1
            ? Set.of(new SnapshotCache.Dependency("one"))
            : Set.of(new SnapshotCache.Dependency("one"), new SnapshotCache.Dependency("two"));
    return new SnapshotCache.Request(
        new SnapshotCache.Key(key, "selection"),
        new SnapshotCache.SourceVersion("v1"),
        selected,
        weight);
  }

  private static void assertIntrinsic(Fixture fixture, SnapshotCache.Request request) {
    var failure =
        assertInstanceOf(
            SnapshotCache.CacheException.class,
            CacheTestSupport.failure(
                fixture.cache().read(request, () -> fail("No oversized load"))));
    assertEquals(SnapshotCache.Failure.LOAD_TOO_LARGE, failure.failure());
    assertEquals(0, fixture.executor().size());
    assertEquals(0, fixture.cache().statistics().loads());
    assertEquals(0, fixture.cache().statistics().waiters());
  }

  @Test
  void dependencyFanoutCannotBecomeAFalseBusyRefusal() {
    try (var fixture = fixture(1, 8)) {
      assertIntrinsic(fixture, request("dependencies", 2, 100));
    }
  }

  @Test
  void aReservationLargerThanAnEmptyCacheDoesNotWaitForCapacity() {
    try (var fixture = fixture(2, 8)) {
      assertIntrinsic(fixture, request("bytes", 1, 10_000));
    }
  }

  @Test
  void anUnaddressableTotalLinkFootprintIsNotTemporaryContention() {
    try (var fixture = fixture(2, 1)) {
      assertIntrinsic(fixture, request("links", 1, 100));
    }
  }

  @Test
  void intrinsicSizeIsClassifiedBeforeOccupancyAndActualCompetingWorkIsStillBackpressure() {
    try (var fixture = fixture(1, 8)) {
      var firstRequest = request("first", 1, 100);
      var held =
          fixture.cache().read(firstRequest, () -> CacheTestSupport.loaded(firstRequest, "held"));
      var impossible =
          assertInstanceOf(
              SnapshotCache.CacheException.class,
              CacheTestSupport.failure(
                  fixture
                      .cache()
                      .read(request("impossible", 2, 100), () -> fail("Never admitted"))));
      assertEquals(SnapshotCache.Failure.LOAD_TOO_LARGE, impossible.failure());
      var competing =
          assertInstanceOf(
              SnapshotCache.CacheException.class,
              CacheTestSupport.failure(
                  fixture
                      .cache()
                      .read(
                          request("competing", 1, 100),
                          () -> fail("The actual occupied seat is backpressure"))));
      assertEquals(SnapshotCache.Failure.CAPACITY, competing.failure());
      held.cancel();
      fixture.executor().runNext();
      assertEquals(
          0,
          CacheTestSupport.awaitStatistics(fixture.cache(), statistics -> statistics.loads() == 0)
              .loads());
    }
  }
}
