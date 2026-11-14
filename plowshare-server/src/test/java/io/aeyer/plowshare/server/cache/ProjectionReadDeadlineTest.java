package io.aeyer.plowshare.server.cache;

import static io.aeyer.plowshare.server.cache.ProjectionCacheStatistics.Event.DEADLINE_REJECTED;
import static io.aeyer.plowshare.server.cache.ProjectionCacheStatistics.Event.LOAD;
import static io.aeyer.plowshare.server.cache.ProjectionCacheStatistics.Event.VERSIONED_HIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.cache.CacheTestSupport.Value;
import io.aeyer.plowshare.server.reads.BoundedReadAdmission;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import io.aeyer.plowshare.server.reads.ReadCapacityProperties;
import io.aeyer.plowshare.server.reads.ReadDeadline;
import io.aeyer.plowshare.server.reads.ReadSchedulingProperties;
import io.aeyer.plowshare.server.reads.ReadUnavailableException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * One deadline per cached read: the FETCH slot wait and the wait on another request's reload draw
 * on the same budget, a read past it is refused as busy without starting another load, and the
 * elected loader still stores its value for the next read. The backing cache's own wait bound is
 * set far above the budget, so only the read's deadline can explain each refusal.
 */
class ProjectionReadDeadlineTest {
  private static final Set<ProjectionVersions.Domain> DOMAINS =
      Set.of(ProjectionVersions.Domain.SEARCH);
  private static final SnapshotCache.Key KEY = new SnapshotCache.Key("deny-set", "account");
  private static final Duration BUDGET = Duration.ofMillis(300);

  private static VersionedProjectionCache<Value> cache() {
    var versions = mock(ProjectionVersions.class);
    var token =
        new ProjectionVersions.Token(
            new UUID(0, 1),
            Map.of(ProjectionVersions.Domain.SEARCH, 4L),
            List.of(),
            "999999",
            null,
            null);
    when(versions.capture(DOMAINS)).thenReturn(Optional.of(token));
    when(versions.canShare()).thenReturn(true);
    when(versions.current(any())).thenReturn(true);
    return new VersionedProjectionCache<>(
        new BoundedSnapshotCache<>(
            CacheTestSupport.namespace(),
            new SnapshotCache.Limits(
                8, 4_000_000, 4, 4_000_000, 16, 8, 4, 64, Duration.ofSeconds(60))),
        versions,
        new VersionedProjectionCacheTest.Signals(),
        DOMAINS,
        1_000,
        null,
        BUDGET);
  }

  @Test
  void aWaiterPastTheDeadlineIsBusyWithoutASecondLoadAndTheElectedLoadServesTheNextRead()
      throws Exception {
    try (var cache = cache();
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var loads = new AtomicInteger();
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var elected =
          callers.submit(
              () ->
                  cache.read(
                      KEY,
                      true,
                      () -> {
                        loads.incrementAndGet();
                        entered.countDown();
                        await(release);
                        return new Value("loaded");
                      },
                      () -> fail("The elected caller must not bypass"),
                      ignored -> 100));
      assertTrue(entered.await(5, TimeUnit.SECONDS));

      long started = System.nanoTime();
      assertThrows(
          ReadUnavailableException.class,
          () ->
              cache.read(
                  KEY,
                  true,
                  () -> fail("A joined caller never loads"),
                  () -> fail("A refused waiter must not start an uncached load"),
                  ignored -> 100));
      long waited = System.nanoTime() - started;
      assertTrue(waited >= BUDGET.toNanos() / 2, "The waiter used its budget");
      assertTrue(waited < Duration.ofSeconds(5).toNanos(), "Not the backing cache's 60 s bound");

      // The elected loader is past its own deadline too: it finishes and stores, but is busy.
      release.countDown();
      var failure = assertThrows(Exception.class, () -> elected.get(5, TimeUnit.SECONDS));
      assertTrue(failure.getCause() instanceof ReadUnavailableException);

      assertEquals(
          new Value("loaded"),
          cache.read(
              KEY,
              true,
              () -> fail("The finished load serves the next read"),
              () -> fail("No bypass"),
              ignored -> 100));
      assertEquals(1, loads.get());
      assertEquals(1, cache.statistics().count(LOAD));
      assertEquals(2, cache.statistics().count(DEADLINE_REJECTED));
      assertEquals(1, cache.statistics().count(VERSIONED_HIT));
    }
  }

  @Test
  void theSlotWaitUsesWhatRemainsOfTheReadsBudget() throws Exception {
    // FETCH waits outside a cache read may last 10 s; inside one only the read's budget remains.
    var admission =
        new BoundedReadAdmission(
            new ReadCapacityProperties(4, 2, 2, 1, 8, 2),
            new ReadSchedulingProperties(64, 4, 2000, 10_000));
    var first = admission.admit("deny-sets", ReadAdmission.Work.FETCH);
    var second = admission.admit("deny-sets", ReadAdmission.Work.FETCH);
    try (var cache = cache()) {
      var uncached = new AtomicInteger();
      long started = System.nanoTime();
      assertThrows(
          ReadUnavailableException.class,
          () ->
              cache.read(
                  KEY,
                  true,
                  () ->
                      admission.read(
                          "deny-sets", ReadAdmission.Work.FETCH, () -> new Value("never")),
                  () -> {
                    uncached.incrementAndGet();
                    return new Value("direct");
                  },
                  ignored -> 100));
      long waited = System.nanoTime() - started;
      assertTrue(waited >= BUDGET.toNanos() / 2, "The slot wait used the read's budget");
      assertTrue(waited < Duration.ofSeconds(5).toNanos(), "Not the 10 s standalone fetch wait");
      assertEquals(0, uncached.get(), "A busy fill is not replaced by an uncached read");
      assertEquals(0, admission.waitingFetches());
    } finally {
      first.close();
      second.close();
    }
  }

  @Test
  void aNestedReadKeepsTheEarlierEnclosingDeadline() {
    long outer =
        ReadDeadline.within(
            Duration.ofMillis(100),
            () ->
                ReadDeadline.within(
                    Duration.ofSeconds(30), () -> ReadDeadline.deadlineNanos(Duration.ofDays(1))));
    assertTrue(outer - System.nanoTime() < Duration.ofSeconds(1).toNanos());
    long standalone = ReadDeadline.deadlineNanos(Duration.ofSeconds(30));
    assertTrue(standalone - System.nanoTime() > Duration.ofSeconds(20).toNanos());
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }
}
