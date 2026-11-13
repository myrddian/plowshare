package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.CachedProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.cache.*;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The snapshot is loaded once per AUTHORITY version and re-validated on every use. A version
 * change, a dirty caller or an oversized installation sends the caller back to SQL.
 */
class CachedAuthoritySnapshotsTest {
  private static final Set<ProjectionVersions.Domain> DOMAINS =
      Set.of(ProjectionVersions.Domain.AUTHORITY);

  private static ProjectionVersions.Token token(long version) {
    return new ProjectionVersions.Token(
        new UUID(0, 1),
        Map.of(ProjectionVersions.Domain.AUTHORITY, version),
        List.of(),
        "999999",
        null,
        null);
  }

  private static final class Fixture implements AutoCloseable {
    final ProjectionVersions versions = mock(ProjectionVersions.class);
    final ProjectionVersions.Token first = token(1);
    final AtomicInteger loads = new AtomicInteger();
    Optional<AuthoritySnapshot.Facts> facts = Optional.of(facts());
    final VersionedProjectionCache<Optional<AuthoritySnapshot>> cache;
    final CachedAuthoritySnapshots snapshots;

    Fixture() {
      when(versions.capture(DOMAINS)).thenReturn(Optional.of(first));
      when(versions.current(any())).thenReturn(true);
      when(versions.canShare()).thenReturn(true);
      var signals = mock(ProjectionChangeSignals.class);
      when(signals.subscribe(eq(DOMAINS), any()))
          .thenAnswer(
              call -> {
                ProjectionChangeSignals.Observer observer = call.getArgument(1);
                observer.stateChanged(new ProjectionChangeSignals.State(1, true));
                return (ProjectionChangeSignals.Subscription) () -> {};
              });
      cache =
          new VersionedProjectionCache<>(
              new BoundedSnapshotCache<>(
                  new SnapshotCache.Namespace(first.database().toString(), "authority"),
                  new SnapshotCache.Limits(
                      8, 4_000_000, 2, 4_000_000, 64, 64, 2, 32, Duration.ofSeconds(10))),
              versions,
              signals,
              DOMAINS,
              1_000_000);
      snapshots =
          new CachedAuthoritySnapshots(
              cache,
              () -> {
                loads.incrementAndGet();
                return facts;
              },
              ReadAdmission.direct());
    }

    public void close() {
      cache.close();
    }
  }

  /** An enabled reader with no grants: enough to tell loads apart without SQL. */
  private static AuthoritySnapshot.Facts facts() {
    return new AuthoritySnapshot.Facts(
        Instant.parse("2026-11-13T00:00:00Z"),
        List.of(new AuthoritySnapshot.Account("reader", "USER", true, false, true)),
        Map.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  @Test
  void oneLoadAnswersEveryCheckWhileTheVersionHolds() {
    try (var f = new Fixture()) {
      var stored = mock(ProjectMembers.class);
      var members = new CachedProjectMembers(stored, f.snapshots);
      for (int check = 0; check < 100; check++) {
        assertTrue(members.isServerAdmin("reader"));
        assertEquals(Optional.empty(), members.role("one", "reader"));
      }
      assertEquals(1, f.loads.get());
      verifyNoInteractions(stored);
    }
  }

  @Test
  void anAuthorityWriteInvalidatesTheSnapshotBeforeItAnswersAgain() {
    try (var f = new Fixture()) {
      assertTrue(f.snapshots.current().isPresent());
      // A revocation moves the AUTHORITY counter: the stored token no longer validates.
      when(f.versions.current(f.first)).thenReturn(false);
      var second = token(2);
      when(f.versions.capture(DOMAINS)).thenReturn(Optional.of(second));
      when(f.versions.current(second)).thenReturn(true);
      assertTrue(f.snapshots.current().isPresent());
      assertEquals(2, f.loads.get());
    }
  }

  @Test
  void dirtyCallersOversizedInstallationsAndDisabledCachesUseSql() {
    try (var f = new Fixture()) {
      var stored = mock(ProjectMembers.class);
      when(stored.role("one", "reader")).thenReturn(Optional.of(ProjectRole.VIEWER));
      var members = new CachedProjectMembers(stored, f.snapshots);

      when(f.versions.capture(DOMAINS)).thenReturn(Optional.empty());
      assertEquals(Optional.of(ProjectRole.VIEWER), members.role("one", "reader"));
      assertEquals(0, f.loads.get(), "A dirty transaction keeps its own statement authority");

      when(f.versions.capture(DOMAINS)).thenReturn(Optional.of(f.first));
      f.facts = Optional.empty();
      assertEquals(Optional.of(ProjectRole.VIEWER), members.role("one", "reader"));
      assertEquals(Optional.of(ProjectRole.VIEWER), members.role("one", "reader"));
      assertEquals(1, f.loads.get(), "The oversized marker is shared, not reloaded per check");
      verify(stored, times(3)).role("one", "reader");
    }
    var disabled =
        new CachedAuthoritySnapshots(
            ProjectionCache.direct(),
            () -> fail("A disabled cache must not load a snapshot"),
            ReadAdmission.direct());
    assertTrue(disabled.current().isEmpty());
  }
}
