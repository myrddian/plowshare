package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import io.aeyer.plowshare.server.cache.VersionedProjectionCache;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Answers the hot live-grant reads, {@link #live} and {@link #lineage}, from the in-memory {@link
 * AuthoritySnapshot} instead of re-walking {@code authority_live_grants()} in SQL, and delegates
 * everything else to the stored repository.
 *
 * <p><b>Consistency.</b> A snapshot answer equals the stored repository's SQL answer for the
 * authority state the snapshot was loaded from ({@code AuthoritySnapshotDifferentialDatabaseTest}
 * compares both in every fixture state). The snapshot is notification-trusted: a committed
 * revocation, grant issue, account disable or membership change can take up to {@code
 * plowshare.projection-cache.notified.staleness} (2 s by default) to reach these answers, so a
 * revoked grant may still be returned as live, and a new one may still be missing, for that long.
 *
 * <p><b>Fallback to SQL.</b> The stored repository answers when the caller holds a Spring-managed
 * transaction that may contain its own writes (anything but none or a read-only READ COMMITTED
 * one), or when no snapshot may be used: the cache is disabled or not ready, the caller is
 * ineligible, an authority table exceeds the snapshot's row limit, or a grant or token expiry falls
 * inside the version token's window. Issuance and revocation always run in the stored repository,
 * whose own issuing-authority recheck stays in SQL under its locks.
 *
 * <p>{@link #projectLineages} is already answered from the snapshot by {@code
 * ApplicationGrantLineage}, its only caller, and {@link #projectBindings} has no production caller;
 * both delegate unchanged.
 */
public final class CachedScopedGrantRepository implements ScopedGrantRepository {
  private final ScopedGrantRepository stored;
  private final AuthoritySnapshots snapshots;

  public CachedScopedGrantRepository(ScopedGrantRepository stored, AuthoritySnapshots snapshots) {
    this.stored = Objects.requireNonNull(stored);
    this.snapshots = Objects.requireNonNull(snapshots);
  }

  @Override
  public List<Binding> live(GrantRecipient recipient) {
    Objects.requireNonNull(recipient);
    return snapshot()
        .map(snapshot -> snapshot.live(recipient))
        .orElseGet(() -> stored.live(recipient));
  }

  @Override
  public List<Binding> lineage(UUID id) {
    Objects.requireNonNull(id);
    return snapshot().map(snapshot -> snapshot.lineage(id)).orElseGet(() -> stored.lineage(id));
  }

  /**
   * The snapshot for a caller that cannot hold its own uncommitted writes. A write transaction
   * keeps the SQL path even when the versioned cache would admit it, so mutations and admissions
   * that decide inside their transaction see exactly what their statements see.
   */
  private Optional<AuthoritySnapshot> snapshot() {
    return VersionedProjectionCache.callerHasNoOwnWrites() ? snapshots.current() : Optional.empty();
  }

  @Override
  public boolean globalReadEligible(AccountId account) {
    return stored.globalReadEligible(account);
  }

  @Override
  public List<Binding> projectBindings(String project, String account) {
    return stored.projectBindings(project, account);
  }

  @Override
  public List<List<Binding>> projectLineages(String project, String account) {
    return stored.projectLineages(project, account);
  }

  @Override
  public Optional<Binding> find(UUID id) {
    return stored.find(id);
  }

  @Override
  public Binding issue(Issue request) {
    return stored.issue(request);
  }

  @Override
  public Binding revoke(UUID id, long version, GrantRecipient issuer, UUID authority) {
    return stored.revoke(id, version, issuer, authority);
  }
}
