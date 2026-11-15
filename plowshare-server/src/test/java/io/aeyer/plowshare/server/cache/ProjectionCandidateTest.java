package io.aeyer.plowshare.server.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Resident candidates return original evidence, without freshness SQL or an in-flight wait. */
class ProjectionCandidateTest {
  private static final Set<ProjectionVersions.Domain> DOMAINS =
      Set.of(ProjectionVersions.Domain.AUTHORITY);
  private static final SnapshotCache.Key KEY = new SnapshotCache.Key("authority", "installation");
  private static final UUID DATABASE = new UUID(0, 1);

  private static ProjectionVersions.Token token(List<String> pending) {
    return new ProjectionVersions.Token(
        DATABASE, Map.of(ProjectionVersions.Domain.AUTHORITY, 1L), pending, "999", null, null);
  }

  private static final class Fixture implements AutoCloseable {
    final ProjectionVersions versions = mock(ProjectionVersions.class);
    final AtomicReference<ProjectionChangeSignals.Observer> observer = new AtomicReference<>();
    final BoundedSnapshotCache<VersionedProjectionCache.Versioned<String>> backing;
    final VersionedProjectionCache<String> cache;
    ProjectionVersions.Token token = token(List.of("19"));

    Fixture() {
      when(versions.capture(DOMAINS)).thenAnswer(call -> Optional.of(token));
      when(versions.current(any())).thenReturn(true);
      when(versions.canShare()).thenReturn(true);
      var signals = mock(ProjectionChangeSignals.class);
      when(signals.subscribe(eq(DOMAINS), any()))
          .thenAnswer(
              call -> {
                observer.set(call.getArgument(1));
                observer.get().stateChanged(new ProjectionChangeSignals.State(1, true));
                return (ProjectionChangeSignals.Subscription) () -> {};
              });
      backing =
          spy(
              new BoundedSnapshotCache<VersionedProjectionCache.Versioned<String>>(
                  new SnapshotCache.Namespace(DATABASE.toString(), "authority"),
                  new SnapshotCache.Limits(
                      2, 4_000_000, 2, 4_000_000, 8, 8, 2, 16, Duration.ofSeconds(5))));
      cache = new VersionedProjectionCache<>(backing, versions, signals, DOMAINS, 100_000);
    }

    String read() {
      return cache.read(KEY, true, () -> "resident", () -> "sql", value -> 128L);
    }

    public void close() {
      cache.close();
    }
  }

  @Test
  void candidateReturnsTheOriginalLoaderTokenWithoutCapturingOrCheckingVersions() {
    try (var f = new Fixture()) {
      assertTrue(f.cache.candidate(KEY).isEmpty());
      assertEquals("resident", f.read());
      reset(f.versions);
      for (int i = 0; i < 100; i++) {
        var candidate = f.cache.candidate(KEY).orElseThrow();
        assertEquals("resident", candidate.value());
        assertSame(f.token, candidate.token());
      }
      verifyNoInteractions(f.versions);
      f.cache.invalidate(KEY);
      assertTrue(f.cache.candidate(KEY).isEmpty());
    }
  }

  @Test
  void notificationArrivingDuringPeekRetiresItsCandidate() {
    try (var f = new Fixture()) {
      f.read();
      doAnswer(
              call -> {
                Object resident = call.callRealMethod();
                f.observer.get().changed(DOMAINS);
                return resident;
              })
          .when(f.backing)
          .peek(any());
      assertTrue(f.cache.candidate(KEY).isEmpty());
      assertTrue(f.cache.candidate(KEY).isEmpty());
    }
  }

  @Test
  void reconnectRecoveryAndExcessPendingMetadataRefuseCandidates() {
    try (var f = new Fixture()) {
      f.token = token(LongStream.rangeClosed(1, 65).mapToObj(Long::toString).toList());
      f.read();
      assertTrue(f.cache.candidate(KEY).isEmpty());
      f.token = token(List.of());
      f.cache.invalidate(KEY);
      f.read();
      assertTrue(f.cache.candidate(KEY).isPresent());
      f.observer.get().stateChanged(new ProjectionChangeSignals.State(2, false));
      assertTrue(f.cache.candidate(KEY).isEmpty());
      f.observer.get().stateChanged(new ProjectionChangeSignals.State(3, true));
      assertTrue(f.cache.candidate(KEY).isEmpty());
    }
  }

  @Test
  void anEvictedCandidateNeverJoinsTheSameVersionsPendingReload() throws Exception {
    try (var f = new Fixture()) {
      f.read();
      assertTrue(f.cache.candidate(KEY).isPresent());
      var request = ArgumentCaptor.forClass(SnapshotCache.Request.class);
      verify(f.backing).peek(request.capture());
      // Evict the value while retaining the lookup index, as normal LRU eviction does.
      f.backing.invalidate(KEY);
      var started = new CountDownLatch(1);
      var finish = new CountDownLatch(1);
      try (var pending =
          f.backing.read(
              request.getValue(),
              () -> {
                started.countDown();
                assertTrue(finish.await(5, TimeUnit.SECONDS));
                return new SnapshotCache.Loaded<>(
                    f.token.sourceVersion(),
                    new VersionedProjectionCache.Versioned<>(f.token, null, "reloaded"),
                    128);
              })) {
        try {
          assertTrue(started.await(5, TimeUnit.SECONDS));
          var before = f.backing.statistics();
          assertTimeoutPreemptively(
              Duration.ofSeconds(1), () -> assertTrue(f.cache.candidate(KEY).isEmpty()));
          var after = f.backing.statistics();
          assertEquals(before.waiters(), after.waiters());
          assertEquals(before.loads(), after.loads());
          assertEquals(before.coalesced(), after.coalesced());
        } finally {
          finish.countDown();
        }
        pending.result().toCompletableFuture().get(5, TimeUnit.SECONDS);
      }
    }
  }
}
