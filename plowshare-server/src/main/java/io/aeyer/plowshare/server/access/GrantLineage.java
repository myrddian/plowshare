package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.archive.ProjectRole;
import java.util.Optional;
import java.util.UUID;

/**
 * Effective Application boundaries along live issuance chains, separate from stored role policy.
 */
public interface GrantLineage {
  boolean valid(UUID grant);

  Optional<ProjectRole> projectRole(String project, String account, Optional<ProjectRole> ceiling);

  /** Reuses this decision's current target manifest, retaining live ancestor policy checks. */
  default Optional<ProjectRole> projectRole(
      String project,
      String account,
      Optional<ProjectRole> ceiling,
      io.aeyer.plowshare.server.agents.ApplicationPolicy.Boundary boundary) {
    return projectRole(project, account, ceiling);
  }
}
