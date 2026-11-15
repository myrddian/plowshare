package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.cache.ProjectionVersions;
import java.util.Objects;
import java.util.Optional;

/**
 * The current in-memory authority snapshot, when one may be used for this caller.
 *
 * <p>Empty means the caller must evaluate authority in SQL: the cache is disabled, the caller's
 * transaction is dirty or historical, the current version could not be validated, or the
 * installation exceeds the snapshot bounds. A present snapshot was validated against the current
 * AUTHORITY version for this call; callers must not retain it beyond the decision they make with
 * it.
 */
public interface AuthoritySnapshots {
  Optional<AuthoritySnapshot> current();

  /** A resident projection and its own loader token, requiring database confirmation before use. */
  record Candidate(AuthoritySnapshot snapshot, ProjectionVersions.Token token) {
    public Candidate {
      Objects.requireNonNull(snapshot);
      Objects.requireNonNull(token);
    }
  }

  /** No SQL, no load, and no freshness claim; intended only for checked grant reads. */
  default Optional<Candidate> candidate() {
    return Optional.empty();
  }

  /**
   * Retires trusted evidence and reloads through the authoritative cache path. Called only outside
   * the requesting transaction, normally by {@link AuthoritySnapshotRefresh}.
   */
  default void refresh() {
    current();
  }

  /** No snapshot: every caller keeps its SQL authority. */
  static AuthoritySnapshots none() {
    return Optional::empty;
  }
}
