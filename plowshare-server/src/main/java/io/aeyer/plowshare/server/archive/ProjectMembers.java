package io.aeyer.plowshare.server.archive;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Durable project membership and live role grants. Callers supply authenticated principals.
 * Mutations enforce explicit management authority and preserve a last manager under a project lock;
 * grant history is recorded in the same transaction. No caller receives JDBC infrastructure.
 */
public interface ProjectMembers {
  /** Whether the registered server source has a valid root application manifest. */
  default boolean application(String project) {
    return false;
  }

  /** Account whose live manifest grant caps this principal, including machine-token owners. */
  default Optional<String> authorityAccount(String principal) {
    return Optional.ofNullable(principal);
  }

  boolean isServerAdmin(String handle);

  /** Explicit administrative control, never a project data role. */
  default boolean mayAdminister(String project, String actor) {
    return false;
  }

  boolean isMember(String project, String handle);

  List<String> members(String project);

  void add(String project, String handle);

  void remove(String project, String handle);

  Optional<ProjectRole> role(String project, String handle);

  /**
   * Effective roles for one operation; implementations may batch facts without retaining grants.
   */
  default java.util.Map<String, ProjectRole> roles(Collection<String> projects, String account) {
    var selection =
        new ReadSelection(account, projects == null ? null : new java.util.ArrayList<>(projects));
    var result = new java.util.LinkedHashMap<String, ProjectRole>();
    for (String project : selection.projects())
      role(project, account).ifPresent(value -> result.put(project, value));
    return java.util.Map.copyOf(result);
  }

  boolean mayWork(String project, String handle);

  boolean mayManage(String project, String handle);

  void requireRole(String project, String handle, ProjectRole required);

  Access access(String project, String handle);

  /** Membership-management result; server administration is distinct from ordinary project use. */
  default Access managementAccess(String project, String actor) {
    return access(project, actor);
  }

  void assign(String project, String handle, ProjectRole role, String actor, boolean add);

  void remove(String project, String handle, String actor);

  boolean mayUse(String project, String handle);

  /**
   * Resolve this candidate set under current read authority in one operation. Implementations may
   * batch database work; the immutable result is not an authorization lease or a shared cache.
   * Application/issuance boundaries remain part of each implementation's effective mayUse policy.
   */
  default Set<String> readableProjects(Collection<String> projects, String account) {
    var selection =
        new ReadSelection(account, projects == null ? null : new java.util.ArrayList<>(projects));
    return selection.projects().stream()
        .filter(project -> mayUse(project, account))
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  /** Exact authenticated principal and deduplicated project identities; no coercion or trimming. */
  record ReadSelection(String account, List<String> projects) {
    public ReadSelection {
      if (account == null
          || account.isBlank()
          || !account.equals(account.strip())
          || account.length() > 256
          || account.codePoints().anyMatch(Character::isISOControl))
        throw new IllegalArgumentException("Authenticated project account required");
      if (projects == null
          || projects.stream()
              .anyMatch(
                  project ->
                      project == null
                          || project.isBlank()
                          || !project.equals(project.strip())
                          || project.length() > 1024
                          || project.codePoints().anyMatch(Character::isISOControl)))
        throw new IllegalArgumentException("Exact bounded project identities required");
      projects = projects.stream().distinct().sorted().toList();
    }
  }

  public record Grant(String handle, ProjectRole role) {}

  public record GrantChange(
      long id,
      java.time.OffsetDateTime occurredAt,
      String actor,
      String target,
      String action,
      ProjectRole role) {}

  public record Access(
      String project,
      ProjectRole role,
      List<String> permissions,
      List<Grant> members,
      List<GrantChange> history) {}
}
