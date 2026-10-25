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
  private final io.aeyer.plowshare.server.access.GrantLineage lineage;

  public ApplicationProjectMembers(
      @Qualifier("jdbcProjectMembers") ProjectMembers stored,
      ApplicationPolicy applications,
      io.aeyer.plowshare.server.access.GrantLineage lineage) {
    this.stored = stored;
    this.applications = applications;
    this.lineage = lineage;
  }

  @Override
  public boolean isServerAdmin(String handle) {
    return stored.isServerAdmin(handle);
  }

  @Override
  public boolean mayAdminister(String project, String actor) {
    return stored.mayAdminister(project, actor);
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
    return applications
        .read(project)
        .limit(account, lineage.projectRole(project, account, stored.role(project, handle)));
  }

  @Override
  public java.util.Map<String, ProjectRole> roles(
      java.util.Collection<String> projects, String handle) {
    var selection =
        new ReadSelection(handle, projects == null ? null : new java.util.ArrayList<>(projects));
    var storedRoles = stored.roles(selection.projects(), handle);
    String account =
        io.aeyer.plowshare.server.auth.ServiceCredentials.principal(handle)
            ? stored.authorityAccount(handle).orElse(null)
            : handle;
    if (account == null) return java.util.Map.of();
    var result = new java.util.LinkedHashMap<String, ProjectRole>();
    storedRoles.forEach(
        (project, role) ->
            applications
                .read(project)
                .limit(account, lineage.projectRole(project, account, Optional.of(role)))
                .ifPresent(value -> result.put(project, value)));
    return java.util.Map.copyOf(result);
  }

  @Override
  public boolean mayUse(String project, String handle) {
    return role(project, handle).isPresent();
  }

  @Override
  public java.util.Set<String> readableProjects(
      java.util.Collection<String> projects, String handle) {
    var selection =
        new ReadSelection(handle, projects == null ? null : new java.util.ArrayList<>(projects));
    var admitted = stored.readableProjects(selection.projects(), selection.account());
    if (admitted.isEmpty()) return java.util.Set.of();
    String account =
        io.aeyer.plowshare.server.auth.ServiceCredentials.principal(handle)
            ? stored.authorityAccount(handle).orElse(null)
            : handle;
    if (account == null) return java.util.Set.of();
    // Only role presence is requested. All project data roles include VIEWER, so intersecting
    // the live manifest/issuance chain with a VIEWER ceiling preserves readability without
    // reloading each stored role. The underlying JDBC admission is batched, while these live
    // filesystem and lineage decisions deliberately remain per project; no authorization is cached.
    return selection.projects().stream()
        .filter(admitted::contains)
        .filter(
            project ->
                applications
                    .read(project)
                    .limit(
                        account,
                        lineage.projectRole(project, account, Optional.of(ProjectRole.VIEWER)))
                    .isPresent())
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
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
    if (stored.mayAdminister(project, handle)) return stored.managementAccess(project, handle);
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
    return stored.mayAdminister(project, actor)
        ? stored.managementAccess(project, actor)
        : access(project, actor);
  }

  /** Administrative grants remain manageable even when the manifest hides an application. */
  private void requireManagement(String project, String actor) {
    if (!stored.mayAdminister(project, actor)) requireRole(project, actor, ProjectRole.MANAGER);
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
