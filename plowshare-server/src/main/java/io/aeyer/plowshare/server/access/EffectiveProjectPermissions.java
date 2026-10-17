package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import java.util.Objects;

/** Maps live effective RBAC to project actions without adding authority or caching decisions. */
public final class EffectiveProjectPermissions implements ScopedPermissions {
  private final ProjectMembers members;

  /** Composition must supply the effective, Application-aware ProjectMembers source. */
  public EffectiveProjectPermissions(ProjectMembers members) {
    this.members = Objects.requireNonNull(members, "members");
  }

  @Override
  public boolean allows(Principal principal, ProjectTarget target, Action action) {
    Objects.requireNonNull(principal, "principal");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(action, "action");
    return members
        .role(target.value(), principal.value())
        .map(
            role ->
                switch (action) {
                  case READ ->
                      switch (role) {
                        case VIEWER, CONTRIBUTOR, MANAGER -> true;
                      };
                  case SET_AUDIENCE ->
                      role == io.aeyer.plowshare.server.archive.ProjectRole.MANAGER;
                  case WRITE ->
                      switch (role) {
                        case VIEWER -> false;
                        case CONTRIBUTOR, MANAGER -> true;
                      };
                })
        .orElse(false);
  }
}
