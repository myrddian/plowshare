package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import java.util.Objects;

/** Live project visibility shared by Relay inspection, readers and Goal source selection. */
@FunctionalInterface
interface RelayProjectReadAccess {
  boolean mayRead(String account, String project);

  static RelayProjectReadAccess existing(ProjectMembers members, ProjectWorkspaces projects) {
    Objects.requireNonNull(members);
    Objects.requireNonNull(projects);
    return (account, project) -> {
      var owner = projects.personalOwner(project);
      return (owner.isEmpty() || owner.get().equals(account)) && members.mayUse(project, account);
    };
  }
}
