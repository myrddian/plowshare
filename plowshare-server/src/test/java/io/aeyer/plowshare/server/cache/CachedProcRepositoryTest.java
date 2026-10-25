package io.aeyer.plowshare.server.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.proc.*;
import io.aeyer.plowshare.server.reads.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CachedProcRepositoryTest {
  @Test
  void duplicateFeedsSharePhysicalWorkButDifferentPrincipalsAndRevocationCannotReuseAuthority()
      throws Exception {
    var domains =
        Set.of(ProjectionVersions.Domain.OBSERVATIONS, ProjectionVersions.Domain.AUTHORITY);
    var token =
        new AtomicReference<>(
            new ProjectionVersions.Token(
                UUID.randomUUID(),
                Map.of(
                    ProjectionVersions.Domain.OBSERVATIONS,
                    1L,
                    ProjectionVersions.Domain.AUTHORITY,
                    1L),
                List.of(),
                null,
                null));
    var versions = mock(ProjectionVersions.class);
    when(versions.capture(domains)).thenAnswer(ignored -> Optional.of(token.get()));
    when(versions.current(any())).thenAnswer(call -> call.getArgument(0).equals(token.get()));
    when(versions.canShare()).thenReturn(true);
    var signals = mock(ProjectionChangeSignals.class);
    when(signals.subscribe(eq(domains), any()))
        .thenAnswer(
            call -> {
              call.getArgument(1, ProjectionChangeSignals.Observer.class)
                  .stateChanged(new ProjectionChangeSignals.State(1, true));
              return (ProjectionChangeSignals.Subscription) () -> {};
            });
    var source = mock(ProcRepository.class);
    var query = new ProcQuery("mine", null, null, null, null, null, null, 50);
    var snapshot =
        new ProcViews.Queues(
            Instant.now(), query, List.of(), List.of(), List.of(), null, List.of());
    var entered = new CompletableFuture<Void>();
    var release = new CompletableFuture<Void>();
    when(source.queues("alice", query))
        .thenAnswer(
            ignored -> {
              entered.complete(null);
              release.get(10, TimeUnit.SECONDS);
              return snapshot;
            });
    when(source.queues("bob", query)).thenThrow(new CallerFault("No project access"));
    var backing =
        new BoundedSnapshotCache<VersionedProjectionCache.Versioned<ProcViews.Queues>>(
            new SnapshotCache.Namespace("fixture", "queues"),
            new SnapshotCache.Limits(
                8, 2_000_000, 2, 4_000_000, 16, 8, 4, 64, Duration.ofSeconds(10)));
    try (var cache = new VersionedProjectionCache<>(backing, versions, signals, domains, 16_000);
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var admission = new BoundedReadAdmission(new ReadCapacityProperties(4, 2, 2, 1, 8, 2));
      var repository = new CachedProcRepository(source, cache, admission, new ObjectMapper());
      var first = callers.submit(() -> repository.queues("alice", query));
      entered.get(5, TimeUnit.SECONDS);
      var futures = new ArrayList<Future<Boolean>>();
      for (int feed = 0; feed < 1000; feed++)
        futures.add(
            callers.submit(
                () -> {
                  try {
                    assertSame(snapshot, repository.queues("alice", query));
                    return true;
                  } catch (ReadUnavailableException busy) {
                    return false;
                  }
                }));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (backing.statistics().waiters() < 2 && System.nanoTime() < deadline) Thread.yield();
      assertTrue(
          backing.statistics().waiters() >= 2, "Equivalent requests must join before release");
      assertEquals(
          1, admission.active(ReadAdmission.Work.REPORT), "Followers hold no physical-work slot");
      release.complete(null);
      assertSame(snapshot, first.get(5, TimeUnit.SECONDS));
      int accepted = 0;
      for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) accepted++;
      assertTrue(accepted > 0);
      verify(source).queues("alice", query);
      assertThrows(CallerFault.class, () -> repository.queues("bob", query));
      var old = token.get();
      token.set(
          new ProjectionVersions.Token(
              old.database(),
              Map.of(
                  ProjectionVersions.Domain.OBSERVATIONS,
                  1L,
                  ProjectionVersions.Domain.AUTHORITY,
                  2L),
              List.of(),
              null,
              null));
      when(source.queues("alice", query)).thenThrow(new CallerFault("Membership revoked"));
      assertThrows(CallerFault.class, () -> repository.queues("alice", query));
    } finally {
      release.complete(null);
    }
  }
}
