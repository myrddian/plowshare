package io.aeyer.plowshare.server.access;

import java.util.Objects;

/**
 * The project-creation decision from the current {@link AuthoritySnapshot}, with {@link
 * ProjectCreatorGrants#holds} (the SQL function itself) when no snapshot may be used. Both paths
 * evaluate {@code authority_may_create_projects}; {@code AuthoritySnapshotDifferentialDatabaseTest}
 * keeps them equal.
 */
public final class CachedProjectCreation implements ProjectCreation {
  private final ProjectCreatorGrants stored;
  private final AuthoritySnapshots snapshots;

  public CachedProjectCreation(ProjectCreatorGrants stored, AuthoritySnapshots snapshots) {
    this.stored = Objects.requireNonNull(stored);
    this.snapshots = Objects.requireNonNull(snapshots);
  }

  @Override
  public boolean mayCreate(String handle) {
    if (handle == null || handle.isBlank()) return false;
    return snapshots
        .current()
        .map(snapshot -> snapshot.projectCreator(handle))
        .orElseGet(() -> stored.holds(handle));
  }
}
