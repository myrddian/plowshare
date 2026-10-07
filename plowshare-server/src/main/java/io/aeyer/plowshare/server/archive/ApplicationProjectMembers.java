package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Effective application roles wrap durable membership for every existing access consumer. */
@Primary
@Component
public final class ApplicationProjectMembers implements ProjectMembers {
  private final ProjectMembers stored;
  private final ApplicationPolicy applications;

  public ApplicationProjectMembers(
      @Qualifier("jdbcProjectMembers") ProjectMembers stored, ApplicationPolicy applications) {
    this.stored = stored;
    this.applications = applications;
  }

  @Override
  public boolean isServerAdmin(String handle) {
    return stored.isServerAdmin(handle);
  }

  @Override
  public boolean application(String project) {
    return applications.read(project).kind() == ApplicationPolicy.Kind.APPLICATION;
  }

  @Override
  public boolean isMember(String project, String handle) {
    return stored.isMember(project, handle) && mayUse(project, handle);
  }

  @Override
  public List<String> members(String project) {
    return stored.members(project);
  }

  @Override
  public Optional<String> authorityAccount(String principal) {
    return stored.authorityAccount(principal);
  }

  @Override
  public Optional<ProjectRole> role(String project, String handle) {
    // Tokens retain their own role ceiling and inherit only their service owner's manifest grant.
    String account =
        io.aeyer.plowshare.server.auth.ServiceCredentials.principal(handle)
            ? stored.authorityAccount(handle).orElse(null)
            : handle;
    return applications.read(project).limit(account, stored.role(project, handle));
  }

  @Override
  public boolean mayUse(String project, String handle) {
    return role(project, handle).isPresent();
  }

  @Override
  public boolean mayWork(String project, String handle) {
    return role(project, handle).filter(role -> role.allows(ProjectRole.CONTRIBUTOR)).isPresent();
  }

  @Override
  public boolean mayManage(String project, String handle) {
    return role(project, handle).filter(role -> role.allows(ProjectRole.MANAGER)).isPresent();
  }

  @Override
  public void requireRole(String project, String handle, ProjectRole required) {
    if (role(project, handle).filter(role -> role.allows(required)).isEmpty())
      throw new CallerFault("This account does not have the required project access");
  }

  @Override
  public Access access(String project, String handle) {
    requireRole(project, handle, ProjectRole.VIEWER);
    Access existing = stored.access(project, handle);
    ProjectRole effective =
        role(project, handle).orElseThrow(() -> new CallerFault("Project access changed"));
    return new Access(
        project,
        effective,
        effective == ProjectRole.VIEWER
            ? List.of("read")
            : effective == ProjectRole.CONTRIBUTOR
                ? List.of("read", "work")
                : List.of("read", "work", "manage"),
        existing.members(),
        effective == ProjectRole.MANAGER ? existing.history() : List.of());
  }

  @Override
  public Access managementAccess(String project, String actor) {
    requireManagement(project, actor);
    return stored.isServerAdmin(actor) ? stored.access(project, actor) : access(project, actor);
  }

  /** Administrative grants remain manageable even when the manifest hides an application. */
  private void requireManagement(String project, String actor) {
    if (!stored.isServerAdmin(actor)) requireRole(project, actor, ProjectRole.MANAGER);
  }

  @Override
  public void assign(String project, String handle, ProjectRole role, String actor, boolean add) {
    requireManagement(project, actor);
    stored.assign(project, handle, role, actor, add);
  }

  @Override
  public void remove(String project, String handle, String actor) {
    requireManagement(project, actor);
    stored.remove(project, handle, actor);
  }

  @Override
  public void add(String project, String handle) {
    stored.add(project, handle);
  }

  @Override
  public void remove(String project, String handle) {
    stored.remove(project, handle);
  }
}
