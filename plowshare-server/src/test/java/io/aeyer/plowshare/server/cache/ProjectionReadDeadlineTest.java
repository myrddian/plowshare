package io.aeyer.plowshare.server.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.aeyer.plowshare.server.reads.ReadFailedException;
import io.aeyer.plowshare.server.reads.ReadSchedulingProperties;
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

/** Cache workflows keep accepted work; only an already enclosing SQL deadline is a hard cutoff. */
class ProjectionReadDeadlineTest {
  private static final Set<ProjectionVersions.Domain> DOMAINS =
      Set.of(ProjectionVersions.Domain.SEARCH);
  private static final SnapshotCache.Key KEY = new SnapshotCache.Key("deny-set", "account");

  private record Fixture(
      VersionedProjectionCache<Value> cache,
      BoundedSnapshotCache<VersionedProjectionCache.Versioned<Value>> backing)
      implements AutoCloseable {
    public void close() {
      cache.close();
    }
  }

  private static ProjectionVersions versions() {
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
    return versions;
  }

  private static Fixture fixture(ProjectionVersions versions) {
    var backing =
        new BoundedSnapshotCache<VersionedProjectionCache.Versioned<Value>>(
            CacheTestSupport.namespace(),
            new SnapshotCache.Limits(
                8, 4_000_000, 4, 4_000_000, 16, 8, 4, 64, Duration.ofMillis(50)));
    return new Fixture(
        new VersionedProjectionCache<>(
            backing,
            versions,
            new VersionedProjectionCacheTest.Signals(),
            DOMAINS,
            1000,
            null,
            Duration.ofMillis(50)),
        backing);
  }

  @Test
  void acceptedInlineReadersAndTheirLoaderContinuePastTheOldCacheTimeout() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var loads = new AtomicInteger();
    try (var fixture = fixture(versions());
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var elected =
          callers.submit(
              () ->
                  fixture
                      .cache()
                      .read(
                          KEY,
                          true,
                          () -> {
                            loads.incrementAndGet();
                            entered.countDown();
                            await(release);
                            return new Value("loaded");
                          },
                          ignored -> 100));
      await(entered);
      var joined =
          callers.submit(
              () ->
                  fixture
                      .cache()
                      .read(
                          KEY,
                          true,
                          () -> fail("A joined reader must not start another SQL load"),
                          ignored -> 100));
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (fixture.backing().statistics().waiters() < 2 && System.nanoTime() < until)
        Thread.onSpinWait();
      assertEquals(2, fixture.backing().statistics().waiters());
      Thread.sleep(150);
      assertFalse(elected.isDone());
      assertFalse(joined.isDone(), "Accepted inline work is observed, not killed by a cache timer");
      release.countDown();
      assertEquals(new Value("loaded"), elected.get(3, TimeUnit.SECONDS));
      assertEquals(new Value("loaded"), joined.get(3, TimeUnit.SECONDS));
      assertEquals(1, loads.get());
    } finally {
      release.countDown();
    }
  }

  @Test
  void anExplicitSqlDeadlineEndsOnlyThatWaiterAndRetainsThePhysicalLoad() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var fixture = fixture(versions());
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var elected =
          callers.submit(
              () ->
                  fixture
                      .cache()
                      .read(
                          KEY,
                          true,
                          () -> {
                            entered.countDown();
                            await(release);
                            return new Value("loaded");
                          },
                          ignored -> 100));
      await(entered);
      var failed =
          assertThrows(
              ReadFailedException.class,
              () ->
                  ReadDeadline.within(
                      Duration.ofMillis(100),
                      () ->
                          fixture
                              .cache()
                              .read(KEY, true, () -> fail("No duplicate load"), ignored -> 100)));
      assertEquals(ReadFailedException.Reason.DEADLINE_EXCEEDED, failed.reason());
      assertFalse(elected.isDone());
      release.countDown();
      assertEquals(new Value("loaded"), elected.get(3, TimeUnit.SECONDS));
      assertEquals(
          new Value("loaded"),
          fixture
              .cache()
              .read(KEY, true, () -> fail("The finished load remains cached"), ignored -> 100));
    } finally {
      release.countDown();
    }
  }

  @Test
  void theFetchSeatWaitHonorsAnAlreadyEnclosingSqlDeadline() {
    var admission =
        new BoundedReadAdmission(
            new ReadCapacityProperties(4, 2, 2, 1, 8, 2),
            new ReadSchedulingProperties(64, 4, 2000, 10_000));
    var first = admission.admit("deny-sets", ReadAdmission.Work.FETCH);
    var second = admission.admit("deny-sets", ReadAdmission.Work.FETCH);
    try (var fixture = fixture(versions())) {
      var failure =
          assertThrows(
              ReadFailedException.class,
              () ->
                  ReadDeadline.within(
                      Duration.ofMillis(100),
                      () ->
                          fixture
                              .cache()
                              .read(
                                  KEY,
                                  true,
                                  () ->
                                      admission.read(
                                          "deny-sets",
                                          ReadAdmission.Work.FETCH,
                                          () -> new Value("never")),
                                  () -> fail("An expired SQL unit must not start another load"),
                                  ignored -> 100)));
      assertEquals(ReadFailedException.Reason.DEADLINE_EXCEEDED, failure.reason());
      assertEquals(0, admission.waitingFetches());
    } finally {
      first.close();
      second.close();
    }
  }

  @Test
  void expiredVersionCaptureCannotStartFallbackOutsideItsActualSqlBudget() {
    var versions = mock(ProjectionVersions.class);
    when(versions.capture(DOMAINS))
        .thenAnswer(
            call -> {
              expireCurrentSqlDeadline();
              return Optional.empty();
            });
    try (var fixture = fixture(versions)) {
      assertThrows(
          ReadFailedException.class,
          () ->
              ReadDeadline.within(
                  Duration.ofMillis(20),
                  () ->
                      fixture
                          .cache()
                          .read(
                              KEY,
                              true,
                              () -> fail("No cache load"),
                              () -> fail("No expired SQL fallback"),
                              ignored -> 100)));
    }
  }

  @Test
  void aLateOrdinaryResultIsRejectedInsideAnActualSqlUnit() {
    try (var fixture = fixture(versions())) {
      var failure =
          assertThrows(
              ReadFailedException.class,
              () ->
                  ReadDeadline.within(
                      Duration.ofMillis(20),
                      () ->
                          fixture
                              .cache()
                              .read(
                                  KEY,
                                  false,
                                  () -> fail("Uncacheable reads use the ordinary loader"),
                                  () -> {
                                    expireCurrentSqlDeadline();
                                    return new Value("late");
                                  },
                                  ignored -> 100)));
      assertEquals(ReadFailedException.Reason.DEADLINE_EXCEEDED, failure.reason());
    }
  }

  @Test
  void anExpiredEnclosingSqlUnitDoesNotStartCacheWork() {
    try (var fixture = fixture(versions())) {
      ReadDeadline.within(
          Duration.ofMillis(20),
          () -> {
            expireCurrentSqlDeadline();
            assertThrows(
                ReadFailedException.class,
                () ->
                    fixture
                        .cache()
                        .read(
                            KEY,
                            true,
                            () -> fail("No late cache load"),
                            () -> fail("No late ordinary load"),
                            ignored -> 100));
            return null;
          });
    }
  }

  private static void expireCurrentSqlDeadline() {
    long deadline = ReadDeadline.currentDeadlineNanos().orElseThrow();
    while (System.nanoTime() - deadline < 0) Thread.onSpinWait();
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(3, TimeUnit.SECONDS));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }
}
