package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.server.agents.ApplicationResources;
import io.aeyer.plowshare.server.applications.ApplicationIdentitySource;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.faults.CallerFault;

/** Reads the current Application identity and effective token ceiling on every control decision. */
public final class ApplicationScheduleControlAuthority implements ScheduleControlAuthority {
  private final ApplicationResources resources;
  private final ProjectMembers members;

  public ApplicationScheduleControlAuthority(
      ApplicationResources resources, ProjectMembers members) {
    this.resources = resources;
    this.members = members;
  }

  @Override
  public void requireApplicationControl(ScheduleDefinitionStore.Source source, String account) {
    if (source.project() == null || !source.source().equals("server")) throw refused();
    var root =
        resources
            .root(source.projectId())
            .orElseThrow(ApplicationScheduleControlAuthority::refused);
    var identity = ApplicationIdentitySource.read(root);
    // The manifest executionAccount is an explicit binding. A handle declaration may authorize
    // its scoped service tokens; a declared token principal remains that exact principal.
    String execution = identity.principal();
    boolean executor =
        execution != null
            && (execution.equals(account)
                || members.authorityAccount(account).filter(execution::equals).isPresent());
    if (members.mayManage(source.project(), account)) return;
    if (!executor || !members.mayWork(source.project(), account)) throw refused();
  }

  private static CallerFault refused() {
    return new CallerFault(
        "Controlling an Application schedule requires its execution account with CONTRIBUTOR access or project MANAGER access");
  }
}
