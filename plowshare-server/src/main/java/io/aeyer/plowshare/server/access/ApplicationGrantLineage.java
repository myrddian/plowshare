package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.archive.ClientProjects;
import io.aeyer.plowshare.server.archive.ProjectRole;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** An issuing MANAGER must still be a manager under the current deployed manifest. */
@Component
public final class ApplicationGrantLineage implements GrantLineage {
  private final ScopedGrantRepository grants;
  private final ApplicationPolicy applications;

  public ApplicationGrantLineage(ScopedGrantRepository grants, ApplicationPolicy applications) {
    this.grants = grants;
    this.applications = applications;
  }

  @Override
  public boolean valid(UUID grant) {
    var chain = grants.lineage(grant);
    if (chain.isEmpty()) return false;
    // Persistence checks scope, versions, account/token activity and acyclic bounded lineage.
    // Manifest boundaries are live filesystem policy, so they must be intersected here too.
    for (int i = 1; i < chain.size(); i++) {
      var issuer = chain.get(i);
      if (issuer.role() == StandardRole.MANAGER
          && issuer.project().isPresent()
          && applications
              .read(issuer.project().orElseThrow())
              .limit(issuer.recipientHandle(), Optional.of(ProjectRole.MANAGER))
              .filter(role -> role == ProjectRole.MANAGER)
              .isEmpty()) return false;
    }
    return true;
  }

  @Override
  public Optional<ProjectRole> projectRole(
      String project, String account, Optional<ProjectRole> ceiling) {
    if (ceiling.isEmpty() || ClientProjects.privateProject(project)) return ceiling;
    Optional<ProjectRole> result = Optional.empty();
    for (var grant : grants.projectBindings(project, account)) {
      Optional<ProjectRole> role =
          switch (grant.role()) {
            case READER -> Optional.of(ProjectRole.VIEWER);
            case CONTRIBUTOR -> Optional.of(ProjectRole.CONTRIBUTOR);
            case MANAGER -> Optional.of(ProjectRole.MANAGER);
            case ADMIN, SYSTEM, GLOBAL_READ -> Optional.empty();
          };
      if (role.isEmpty() || !valid(grant.id())) continue;
      role = applications.read(project).limit(account, role);
      if (role.isPresent() && (result.isEmpty() || role.get().allows(result.get()))) result = role;
    }
    return result.map(role -> role.allows(ceiling.orElseThrow()) ? ceiling.orElseThrow() : role);
  }
}
