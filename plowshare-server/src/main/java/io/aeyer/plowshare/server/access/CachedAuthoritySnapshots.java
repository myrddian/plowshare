package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.cache.ProjectionCache;
import io.aeyer.plowshare.server.cache.SnapshotCache;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import java.util.Objects;
import java.util.Optional;

/**
 * One installation-wide authority snapshot per AUTHORITY version.
 *
 * <p>The versioned cache captures a token before loading and validates it before publishing, and
 * validates the stored token again on every hit. Any write to an authority table moves the counter,
 * and grant or token expiry is excluded by the token's time window, so a returned snapshot is as
 * current as the SQL functions would be for this statement. A dirty, historical or expiry-spanning
 * caller transaction is ineligible and receives no snapshot. Nothing is retried: an unavailable
 * snapshot simply leaves the caller on its SQL path.
 */
public final class CachedAuthoritySnapshots implements AuthoritySnapshots {
  private static final SnapshotCache.Key KEY =
      new SnapshotCache.Key("authority-snapshot", "installation");

  /** Marker for "use SQL"; never published for an eligible caller with a bounded snapshot. */
  private static final Optional<AuthoritySnapshot> UNAVAILABLE = Optional.empty();

  private final ProjectionCache<Optional<AuthoritySnapshot>> cache;
  private final AuthoritySnapshotRepository repository;
  private final ReadAdmission admission;

  public CachedAuthoritySnapshots(
      ProjectionCache<Optional<AuthoritySnapshot>> cache,
      AuthoritySnapshotRepository repository,
      ReadAdmission admission) {
    this.cache = Objects.requireNonNull(cache);
    this.repository = Objects.requireNonNull(repository);
    this.admission = Objects.requireNonNull(admission);
  }

  @Override
  public Optional<AuthoritySnapshot> current() {
    if (!cache.enabled()) return UNAVAILABLE;
    return cache.read(
        KEY,
        true,
        () ->
            admission.read(
                "authority-snapshot",
                ReadAdmission.Work.FETCH,
                () -> repository.load().map(AuthoritySnapshot::new)),
        () -> UNAVAILABLE,
        value -> value.map(snapshot -> snapshot.facts().weight()).orElse(1024L));
  }
}
