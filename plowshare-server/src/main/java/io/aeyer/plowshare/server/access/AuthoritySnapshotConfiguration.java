package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.archive.CachedProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.cache.ProjectionCaches;
import io.aeyer.plowshare.server.cache.ProjectionVersions;
import io.aeyer.plowshare.server.information.SystemProcessingAuthority;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Composes the in-memory authority snapshot and the project membership reads that use it. */
@Configuration
public class AuthoritySnapshotConfiguration {
  /**
   * Rows per authority table before the snapshot yields to SQL. Deployed installations hold tens of
   * rows; the bound keeps an unexpectedly large one from being copied into memory.
   */
  static final int ROW_LIMIT = 10_000;

  @Bean
  public AuthoritySnapshots authoritySnapshots(
      JdbcTemplate jdbc, ProjectionCaches caches, ReadAdmission admission) {
    return new CachedAuthoritySnapshots(
        // Authority changes are rare and an admin acts on a seconds scale: trust notifications for
        // repeat checks, within plowshare.projection-cache.notified.staleness.
        caches.create(
            "authority-snapshot-v1",
            Set.of(ProjectionVersions.Domain.AUTHORITY),
            ProjectionCaches.Freshness.NOTIFIED),
        new JdbcAuthoritySnapshotRepository(jdbc, ROW_LIMIT),
        admission);
  }

  /**
   * SYSTEM processing decided from the snapshot, so worker polls stop re-deriving it from live
   * grants. No snapshot for this caller leaves the decision in SQL.
   */
  @Bean
  public SystemProcessingAuthority systemProcessingAuthority(AuthoritySnapshots snapshots) {
    return new SystemProcessingAuthority() {
      public Optional<Boolean> processing() {
        return snapshots.current().map(AuthoritySnapshot::systemProcessingAuthorized);
      }

      public Optional<Boolean> scopeValid(String project) {
        return snapshots.current().map(snapshot -> snapshot.systemScopeValid(project));
      }
    };
  }

  /** The stored members that {@code ApplicationProjectMembers} wraps with manifest limits. */
  @Bean
  public ProjectMembers cachedProjectMembers(
      @Qualifier("jdbcProjectMembers") ProjectMembers stored, AuthoritySnapshots snapshots) {
    return new CachedProjectMembers(stored, snapshots);
  }
}
