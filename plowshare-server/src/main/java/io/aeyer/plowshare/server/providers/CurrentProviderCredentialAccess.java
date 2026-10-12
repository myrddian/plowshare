package io.aeyer.plowshare.server.providers;

import io.aeyer.plowshare.protocol.ProviderCredentials;
import io.aeyer.plowshare.protocol.ProviderCredentials.Target;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * Current roles and active service-token ceilings apply on every management/callback transition.
 */
public final class CurrentProviderCredentialAccess implements ProviderCredentialAccess {
  private final AdminStore accounts;
  private final ProjectMembers members;

  public CurrentProviderCredentialAccess(AdminStore accounts, ProjectMembers members) {
    this.accounts = accounts;
    this.members = members;
  }

  private void active(String actor) {
    if (!accounts.isEnabled(actor))
      throw new CallerFault("Provider credentials require an active authenticated account");
  }

  @Override
  public void read(String actor, String project) {
    ProviderCredentials.project(project);
    active(actor);
    members.requireRole(project, actor, ProjectRole.VIEWER);
  }

  @Override
  public void configure(String actor, String project) {
    ProviderCredentials.project(project);
    active(actor);
    accounts.requireServerAdmin(actor);
    members.requireRole(project, actor, ProjectRole.MANAGER);
  }

  @Override
  public String subject(String actor, Target target) {
    read(actor, target.project());
    String subject = target.subject() == null ? actor : target.subject();
    if (target.subject() != null && !subject.equals(actor)) accounts.requireServerAdmin(actor);
    ProviderCredentials.subject(subject);
    active(subject);
    members.requireRole(target.project(), subject, ProjectRole.CONTRIBUTOR);
    return subject;
  }
}
