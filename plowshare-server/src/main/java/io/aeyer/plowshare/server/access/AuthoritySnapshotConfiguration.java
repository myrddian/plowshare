package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.archive.CachedProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.cache.ProjectionCaches;
import io.aeyer.plowshare.server.cache.ProjectionNotificationProperties;
import io.aeyer.plowshare.server.cache.ProjectionVersions;
import io.aeyer.plowshare.server.information.SystemProcessingAuthority;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
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

  /**
   * The grant repository every service consumes: live grants and lineage from the snapshot, with
   * the JDBC repository for mutations, fallbacks and every other read. Its freshness is the
   * snapshot's notification-trusted bound; see {@link CachedScopedGrantRepository}.
   */
  @Bean
  @Primary
  public ScopedGrantRepository cachedScopedGrantRepository(
      @Qualifier("jdbcScopedGrantRepository") ScopedGrantRepository stored,
      AuthoritySnapshots snapshots) {
    return new CachedScopedGrantRepository(stored, snapshots);
  }

  /**
   * Admissions remembered as verified at once. An entry is a few hundred bytes; concurrent agent
   * steps on deployed installations number in the tens, so this leaves ample room for every live
   * operation while bounding a burst.
   */
  static final int VERIFIED_ADMISSION_CAPACITY = 1024;

  /**
   * Lets {@link PinnedOperationAuthority} answer a repeated recheck of one admission from memory
   * for up to {@code plowshare.projection-cache.notified.staleness} (2 s by default) under the same
   * authority snapshot; see {@link BoundedVerifiedAdmissions}. With notifications disabled the
   * operator chose exact, version-checked authority, so every recheck walks.
   */
  @Bean
  public VerifiedAdmissions verifiedAdmissions(
      AuthoritySnapshots snapshots, ProjectionNotificationProperties notified) {
    if (!notified.enabled()) return VerifiedAdmissions.none();
    return new BoundedVerifiedAdmissions(
        snapshots, notified.staleness(), VERIFIED_ADMISSION_CAPACITY);
  }

  /** The stored members that {@code ApplicationProjectMembers} wraps with manifest limits. */
  @Bean
  public ProjectMembers cachedProjectMembers(
      @Qualifier("jdbcProjectMembers") ProjectMembers stored, AuthoritySnapshots snapshots) {
    return new CachedProjectMembers(stored, snapshots);
  }
}
