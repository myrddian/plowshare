package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import io.aeyer.plowshare.server.cache.ProjectionVersions;
import io.aeyer.plowshare.server.cache.VersionedProjectionCache;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

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
 * <p>When {@code plowshare.authority.confirm-in-transaction} is enabled, a write transaction may
 * use a resident snapshot only after its original complete version token is confirmed by one
 * caller-bound SQL statement. That statement otherwise returns the SQL rows directly, preserving
 * own writes, historical authority time and isolation without a second query. Snapshot refreshes
 * run on a separate bounded worker and never publish this transaction's own writes.
 *
 * <p><b>Fallback to SQL.</b> The stored repository answers when the caller holds a Spring-managed
 * transaction that may contain its own writes and confirmation is disabled or fails, or when no
 * snapshot may be used: the cache is disabled or not ready, the caller is ineligible, an authority
 * table exceeds the snapshot's row limit, or a grant or token expiry falls inside the version
 * token's window. Issuance and revocation always run in the stored repository, whose own
 * issuing-authority recheck stays in SQL under its locks.
 *
 * <p>{@link #projectLineages} is already answered from the snapshot by {@code
 * ApplicationGrantLineage}, its only caller, and {@link #projectBindings} has no production caller;
 * both delegate unchanged.
 */
public final class CachedScopedGrantRepository implements ScopedGrantRepository {
  private final ScopedGrantRepository stored;
  private final AuthoritySnapshots snapshots;
  private final CheckedScopedGrants checked;
  private final AuthoritySnapshotRefresh refresh;
  private final AuthorityCheckTelemetry telemetry;
  private final boolean confirmInTransaction;

  public CachedScopedGrantRepository(ScopedGrantRepository stored, AuthoritySnapshots snapshots) {
    this(
        stored,
        null,
        snapshots,
        AuthoritySnapshotRefresh.none(),
        AuthorityCheckTelemetry.none(),
        false);
  }

  /** The rollout switch affects only checked reads inside transactions that may hold writes. */
  public CachedScopedGrantRepository(
      ScopedGrantRepository stored,
      CheckedScopedGrants checked,
      AuthoritySnapshots snapshots,
      AuthoritySnapshotRefresh refresh,
      AuthorityCheckTelemetry telemetry,
      boolean confirmInTransaction) {
    this.stored = Objects.requireNonNull(stored);
    this.snapshots = Objects.requireNonNull(snapshots);
    this.checked = confirmInTransaction ? Objects.requireNonNull(checked) : checked;
    this.refresh = Objects.requireNonNull(refresh);
    this.telemetry = Objects.requireNonNull(telemetry);
    this.confirmInTransaction = confirmInTransaction;
  }

  @Override
  public List<Binding> live(GrantRecipient recipient) {
    Objects.requireNonNull(recipient);
    if (confirmInTransaction && !VersionedProjectionCache.callerHasNoOwnWrites())
      return checked(
          token -> checked.liveChecked(recipient, token), snapshot -> snapshot.live(recipient));
    return snapshot()
        .map(
            snapshot -> {
              telemetry.grant(AuthorityCheckTelemetry.GrantOutcome.SNAPSHOT);
              return snapshot.live(recipient);
            })
        .orElseGet(
            () -> {
              telemetry.grant(AuthorityCheckTelemetry.GrantOutcome.SQL_INELIGIBLE);
              return stored.live(recipient);
            });
  }

  @Override
  public List<Binding> lineage(UUID id) {
    Objects.requireNonNull(id);
    if (confirmInTransaction && !VersionedProjectionCache.callerHasNoOwnWrites())
      return checked(token -> checked.lineageChecked(id, token), snapshot -> snapshot.lineage(id));
    return snapshot()
        .map(
            snapshot -> {
              telemetry.grant(AuthorityCheckTelemetry.GrantOutcome.SNAPSHOT);
              return snapshot.lineage(id);
            })
        .orElseGet(
            () -> {
              telemetry.grant(AuthorityCheckTelemetry.GrantOutcome.SQL_INELIGIBLE);
              return stored.lineage(id);
            });
  }

  private List<Binding> checked(
      Function<Optional<ProjectionVersions.Token>, CheckedScopedGrants.Checked> read,
      Function<AuthoritySnapshot, List<Binding>> answer) {
    var candidate = snapshots.candidate();
    var result = read.apply(candidate.map(AuthoritySnapshots.Candidate::token));
    if (result.exact()) {
      // Never silently fall back with a second statement: an exact marker without a candidate is
      // a repository contract violation, not a usable grant answer.
      var snapshot =
          candidate.orElseThrow(
              () -> new IllegalStateException("Confirmed grant snapshot is missing"));
      telemetry.grant(AuthorityCheckTelemetry.GrantOutcome.CONFIRMED);
      return answer.apply(snapshot.snapshot());
    }
    telemetry.grant(
        switch (result.reason()) {
          case DIRTY -> AuthorityCheckTelemetry.GrantOutcome.SQL_DIRTY;
          case ISOLATION -> AuthorityCheckTelemetry.GrantOutcome.SQL_ISOLATION;
          case VERSION -> AuthorityCheckTelemetry.GrantOutcome.SQL_VERSION;
          case WINDOW, INELIGIBLE -> AuthorityCheckTelemetry.GrantOutcome.SQL_INELIGIBLE;
          case EXACT -> throw new IllegalStateException("Unexpected exact grant answer");
        });
    // Own authority writes and historical isolation cannot be fixed by a shared refresh. Other
    // misses must recover even when this installation serves only write-transaction reads.
    if (result.reason() != CheckedScopedGrants.Reason.DIRTY
        && result.reason() != CheckedScopedGrants.Reason.ISOLATION) refresh.request();
    return result.rows();
  }

  /**
   * The ordinary notification-trusted snapshot is only for a caller without its own writes. Other
   * callers use the separately confirmed candidate path when enabled, preserving the transaction's
   * own SQL visibility and transaction-time authority window.
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
