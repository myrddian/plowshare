package io.aeyer.plowshare.server.access;

import java.util.Optional;

/**
 * Loads the authority tables as typed facts for in-memory evaluation.
 *
 * <p>Empty means the installation exceeds the snapshot bounds; callers must then use the SQL
 * authority functions. A result is never truncated. The load reads several tables in separate
 * statements: it is safe to share only under a {@code ProjectionVersions} AUTHORITY token captured
 * before it and validated after it, because every table read here moves that counter on write.
 */
public interface AuthoritySnapshotRepository {
  Optional<AuthoritySnapshot.Facts> load();
}
