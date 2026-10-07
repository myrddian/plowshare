package io.aeyer.plowshare.server.archive;

import java.util.List;
import java.util.Optional;

/**
 * Durable project membership and live role grants. Callers supply authenticated principals.
 * Mutations enforce manager authority and preserve a last manager under a project lock; grant
 * history is recorded in the same transaction. No caller receives JDBC infrastructure.
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

  boolean isMember(String project, String handle);

  List<String> members(String project);

  void add(String project, String handle);

  void remove(String project, String handle);

  Optional<ProjectRole> role(String project, String handle);

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
