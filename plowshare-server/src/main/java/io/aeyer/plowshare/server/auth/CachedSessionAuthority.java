package io.aeyer.plowshare.server.auth;

import io.aeyer.plowshare.server.access.AuthoritySnapshot;
import io.aeyer.plowshare.server.access.AuthoritySnapshots;
import io.aeyer.plowshare.server.cache.VersionedProjectionCache;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Socket session checks answered from the in-memory {@link AuthoritySnapshot}: a session is current
 * when its account is active ({@code account_active}) and its version equals {@code
 * admins.session_version}, exactly as {@link AdminStore#sessionCurrent} and {@link
 * AdminStore#currentSessions} decide in SQL. Every {@code admins} update, including a bare {@code
 * session_version} bump, moves the AUTHORITY version (V175), which invalidates the snapshot.
 *
 * <p><b>Freshness.</b> The snapshot is notification-trusted: a committed session revocation,
 * password change or account disable may still be honoured for up to {@code
 * plowshare.projection-cache.notified.staleness} (2 s by default) before the socket sweep closes
 * the socket. Only acceptance is served from memory. A session the snapshot does not accept is
 * rechecked by the stored authority, so a socket presenting a version issued after the snapshot was
 * loaded (a fresh login or a new account) is never refused because of the snapshot's age.
 *
 * <p><b>Fallback to SQL.</b> The stored authority answers everything when no snapshot may be used
 * (cache disabled or not ready, ineligible caller, oversized installation, expiry inside the token
 * window) or the caller holds a transaction that may contain its own writes.
 */
public final class CachedSessionAuthority implements SessionAuthority {
  private final SessionAuthority stored;
  private final AuthoritySnapshots snapshots;

  public CachedSessionAuthority(SessionAuthority stored, AuthoritySnapshots snapshots) {
    this.stored = Objects.requireNonNull(stored);
    this.snapshots = Objects.requireNonNull(snapshots);
  }

  @Override
  public boolean sessionCurrent(String handle, long version) {
    var snapshot = snapshot();
    if (snapshot.isPresent() && accepts(snapshot.get(), handle, version)) return true;
    return stored.sessionCurrent(handle, version);
  }

  @Override
  public Set<Session> currentSessions(Collection<Session> sessions) {
    var requested = Set.copyOf(sessions);
    var snapshot = snapshot();
    if (snapshot.isEmpty()) return stored.currentSessions(requested);
    var current = new HashSet<Session>();
    var unconfirmed = new HashSet<Session>();
    for (var session : requested) {
      if (accepts(snapshot.get(), session.handle(), session.version())) current.add(session);
      else unconfirmed.add(session);
    }
    // Refusals are rare (a revoked socket is closed and leaves the sweep), so confirming them
    // keeps every refusal exactly as fresh as SQL at the cost of a statement only when one occurs.
    if (!unconfirmed.isEmpty()) current.addAll(stored.currentSessions(unconfirmed));
    return Set.copyOf(current);
  }

  private static boolean accepts(AuthoritySnapshot snapshot, String handle, long version) {
    return snapshot.activeSessionVersion(handle).filter(fence -> fence == version).isPresent();
  }

  /** No snapshot for a caller that may hold its own uncommitted writes; see the class contract. */
  private Optional<AuthoritySnapshot> snapshot() {
    return VersionedProjectionCache.callerHasNoOwnWrites() ? snapshots.current() : Optional.empty();
  }
}
