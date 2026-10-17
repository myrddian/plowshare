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
}
