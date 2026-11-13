package io.aeyer.plowshare.server.access;

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

  /** No snapshot: every caller keeps its SQL authority. */
  static AuthoritySnapshots none() {
    return Optional::empty;
  }
}
