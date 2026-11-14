package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.access.OperationAuthority.Admission;
import io.aeyer.plowshare.server.cache.VersionedProjectionCache;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * A per-process, bounded memory of admissions that recently passed {@link
 * PinnedOperationAuthority#require}'s full walk.
 *
 * <p><b>Key.</b> The whole {@link Admission} value: id, executor, account, scope, home, grant
 * source, the ordered support list (so every action and supporting grant) and {@code globalRead}.
 * Two admissions are interchangeable for {@code require} only when all of these agree, so a
 * different admission (another id, home, support set or Global READ claim) never reuses another's
 * verification.
 *
 * <p><b>Validity.</b> An entry answers a later {@code require} only while both hold:
 *
 * <ul>
 *   <li>the caller's current {@link AuthoritySnapshot} is the very snapshot instance the entry was
 *       verified under. The snapshot cache serves one instance per AUTHORITY version, so a grant,
 *       membership, account or session change that moves the version invalidates every entry; a
 *       reload of the same version merely costs one extra walk;
 *   <li>the entry is younger than {@code bound}, measured from the moment before its walk began.
 *       Some inputs of the walk do not move the AUTHORITY version: the Application manifest that
 *       {@code members.role} applies, operation records and resource scope lookups. The age bound
 *       is what limits their lag.
 * </ul>
 *
 * <p><b>Staleness.</b> A revocation, membership or role change, manifest change or record change
 * may therefore let an already-verified admission keep running for up to {@code bound} (the
 * notification-trusted snapshot bound, {@code plowshare.projection-cache.notified.staleness}, 2 s
 * by default), on top of the snapshot's own lag. A fresh admission is never admitted from memory.
 *
 * <p><b>Never from memory.</b> No entry is read or written when the caller holds a Spring-managed
 * transaction that may contain its own writes (anything but none or a read-only READ COMMITTED
 * one), or when no snapshot may be used for the caller (cache disabled or not ready, ineligible or
 * oversized, expiry inside the token window). Those callers always walk, exactly as before. A walk
 * that refuses throws unchanged and records nothing, so a denial is never cached.
 *
 * <p><b>Bound and concurrency.</b> At most {@code capacity} entries, evicted least recently used.
 * Entries are tied to the one snapshot instance they were verified under, so the cache retains at
 * most one snapshot reference beyond the snapshot cache's own. One lock guards the LRU map and that
 * reference together; the walk itself runs outside the lock, so concurrent rechecks of the same
 * admission may both walk, which only costs a walk. Nothing is shared across processes.
 */
public final class BoundedVerifiedAdmissions implements VerifiedAdmissions {
  /** A verification is recorded with this stamp, taken before its walk began. */
  private record Verified(long atNanos) {}

  private final AuthoritySnapshots snapshots;
  private final long boundNanos;
  private final int capacity;
  private final LongSupplier nanoTime;

  /** Guards {@link #entries} and {@link #generation}, which change together. */
  private final Object lock = new Object();

  /** Access-ordered for LRU eviction; every entry was verified under {@link #generation}. */
  private final LinkedHashMap<Admission, Verified> entries;

  private AuthoritySnapshot generation;

  public BoundedVerifiedAdmissions(AuthoritySnapshots snapshots, Duration bound, int capacity) {
    this(snapshots, bound, capacity, System::nanoTime);
  }

  /**
   * @param bound the longest an entry may answer; positive and at most one minute, like the
   *     notification staleness it is normally configured from
   * @param capacity the most admissions remembered at once; positive
   * @param nanoTime a monotonic clock in nanoseconds
   */
  public BoundedVerifiedAdmissions(
      AuthoritySnapshots snapshots, Duration bound, int capacity, LongSupplier nanoTime) {
    this.snapshots = Objects.requireNonNull(snapshots);
    Objects.requireNonNull(bound);
    if (bound.isNegative() || bound.isZero() || bound.compareTo(Duration.ofMinutes(1)) > 0)
      throw new IllegalArgumentException("Verified admission bound must be in (0, 1m]");
    if (capacity < 1) throw new IllegalArgumentException("Verified admission capacity required");
    this.boundNanos = bound.toNanos();
    this.capacity = capacity;
    this.nanoTime = Objects.requireNonNull(nanoTime);
    this.entries =
        new LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<Admission, Verified> eldest) {
            return size() > BoundedVerifiedAdmissions.this.capacity;
          }
        };
  }

  @Override
  public void require(Admission admission, Runnable walk) {
    Objects.requireNonNull(admission);
    Objects.requireNonNull(walk);
    // The transaction test precedes the snapshot read, as in CachedScopedGrantRepository: a caller
    // whose own uncommitted writes may differ from committed state must decide what its statements
    // see, and must not leave a verification behind that rests on writes that may roll back.
    if (!VersionedProjectionCache.callerHasNoOwnWrites()) {
      walk.run();
      return;
    }
    var current = snapshots.current();
    if (current.isEmpty()) {
      walk.run();
      return;
    }
    var snapshot = current.get();
    long started = nanoTime.getAsLong();
    if (verified(admission, snapshot, started)) return;
    walk.run();
    record(admission, snapshot, started);
  }

  /** Number of remembered admissions; for tests and diagnostics only. */
  int size() {
    synchronized (lock) {
      return entries.size();
    }
  }

  private boolean verified(Admission admission, AuthoritySnapshot snapshot, long now) {
    synchronized (lock) {
      if (generation != snapshot) {
        // The authority version moved (or the snapshot was reloaded): nothing verified under the
        // previous snapshot may answer, and keeping those entries would only pin that snapshot.
        entries.clear();
        generation = snapshot;
        return false;
      }
      var entry = entries.get(admission);
      if (entry == null) return false;
      if (now - entry.atNanos() < boundNanos) return true;
      entries.remove(admission);
      return false;
    }
  }

  private void record(Admission admission, AuthoritySnapshot snapshot, long started) {
    synchronized (lock) {
      // A walk that began under an older snapshot than the one now remembered is not recorded:
      // its verdict was reached against authority that has since changed.
      if (generation != snapshot) return;
      entries.put(admission, new Verified(started));
    }
  }
}
