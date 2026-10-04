package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Accounts allowed to root a project and reach its files. */
@Component
public class ProjectMembers {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public ProjectMembers(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.transactions =
        new TransactionTemplate(
            new DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource(), "dataSource")));
  }

  public boolean isServerAdmin(String handle) {
    return handle != null
        && !jdbc.queryForList(
                "SELECT 1 FROM admins WHERE handle = ? AND enabled AND server_admin AND NOT bootstrap",
                Integer.class,
                handle)
            .isEmpty();
  }

  public boolean isMember(String project, String handle) {
    if (handle == null) {
      return false;
    }
    return ArchiveUnavailableException.translating(
        "read project membership",
        () ->
            !jdbc.queryForList(
                    "SELECT 1 FROM project_members m JOIN projects p"
                        + " ON p.id = m.project_id JOIN admins a ON a.handle=m.handle AND a.enabled WHERE p.name = ? AND m.handle = ?",
                    Integer.class,
                    project,
                    handle)
                .isEmpty());
  }

  public List<String> members(String project) {
    return ArchiveUnavailableException.translating(
        "list project members",
        () ->
            jdbc.queryForList(
                "SELECT m.handle FROM project_members m JOIN projects p"
                    + " ON p.id = m.project_id WHERE p.name = ? ORDER BY m.handle",
                String.class,
                project));
  }

  private void ordinary(String project) {
    if (ClientProjects.privateProject(project))
      throw new ArchiveRefusedException("Client projects cannot be shared with accounts");
    if (project.startsWith("personal:"))
      throw new ArchiveRefusedException("Personal space membership is fixed to its owner");
  }

  public void add(String project, String handle) {
    ordinary(project);
    ArchiveUnavailableException.translating(
        "add a project member",
        () ->
            transactions.execute(
                status -> {
                  if (jdbc.queryForList(
                          "SELECT 1 FROM admins WHERE handle = ?", Integer.class, handle)
                      .isEmpty()) {
                    throw new ArchiveRefusedException("no account named '" + handle + "'");
                  }
                  Long id = ProjectIds.toRead(jdbc, Home.of(project));
                  if (id == ProjectIds.NONE) {
                    throw new ArchiveRefusedException("no project named '" + project + "'");
                  }
                  lock(id);
                  jdbc.update(
                      "INSERT INTO project_members (project_id, handle) VALUES (?, ?)"
                          + " ON CONFLICT DO NOTHING",
                      id,
                      handle);
                  return null;
                }));
  }

  public void remove(String project, String handle) {
    ordinary(project);
    ArchiveUnavailableException.translating(
        "remove a project member",
        () ->
            transactions.execute(
                status -> {
                  Long id = ProjectIds.toRead(jdbc, Home.of(project));
                  if (id == ProjectIds.NONE) {
                    throw new ArchiveRefusedException("no project named '" + project + "'");
                  }
                  lock(id);
                  return jdbc.update(
                      "DELETE FROM project_members WHERE project_id = ? AND handle = ?",
                      id,
                      handle);
                }));
  }

  public java.util.Optional<ProjectRole> role(String project, String handle) {
    if (project == null || handle == null) return java.util.Optional.empty();
    if (io.aeyer.plowshare.server.auth.ServiceCredentials.principal(handle)) {
      var ceiling =
          jdbc.queryForList("SELECT service_project_role(?,?)", String.class, project, handle);
      return ceiling.isEmpty() || ceiling.getFirst() == null
          ? java.util.Optional.empty()
          : java.util.Optional.of(ProjectRole.parse(ceiling.getFirst()));
    }
    if (project.startsWith("personal:")
        && !project.equals(io.aeyer.plowshare.server.personal.PersonalSpaces.name(handle)))
      return java.util.Optional.empty();
    if (!project.startsWith("personal:")
        && !ClientProjects.privateProject(project)
        && isServerAdmin(handle)) return java.util.Optional.of(ProjectRole.MANAGER);
    var roles =
        jdbc.queryForList(
            "SELECT m.role FROM project_members m JOIN projects p ON p.id=m.project_id"
                + " JOIN admins a ON a.handle=m.handle AND a.enabled WHERE p.name=? AND m.handle=?",
            String.class,
            project,
            handle);
    if (roles.isEmpty()) return java.util.Optional.empty();
    return java.util.Optional.of(
        project.equals(io.aeyer.plowshare.server.personal.PersonalSpaces.name(handle))
            ? ProjectRole.MANAGER
            : ProjectRole.parse(roles.getFirst()));
  }

  public boolean mayWork(String project, String handle) {
    return role(project, handle).filter(role -> role.allows(ProjectRole.CONTRIBUTOR)).isPresent();
  }

  public boolean mayManage(String project, String handle) {
    return role(project, handle).filter(role -> role.allows(ProjectRole.MANAGER)).isPresent();
  }

  public void requireRole(String project, String handle, ProjectRole required) {
    if (role(project, handle).filter(role -> role.allows(required)).isEmpty())
      throw new io.aeyer.plowshare.server.faults.CallerFault(
          "This account needs " + required + " access to project '" + project + "'");
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

  public Access access(String project, String handle) {
    if (ProjectIds.toRead(jdbc, Home.of(project)) == ProjectIds.NONE)
      throw new ArchiveRefusedException("No project has that name");
    requireRole(project, handle, ProjectRole.VIEWER);
    ProjectRole effective = role(project, handle).orElseThrow();
    var grants =
        jdbc.query(
            "SELECT m.handle,m.role FROM project_members m JOIN projects p ON p.id=m.project_id WHERE p.name=? ORDER BY m.handle",
            (rs, n) -> new Grant(rs.getString(1), ProjectRole.parse(rs.getString(2))),
            project);
    var changes =
        effective == ProjectRole.MANAGER
            ? jdbc.query(
                "SELECT a.id,a.occurred_at,a.actor,a.target,a.action,a.role FROM project_access_audit a JOIN projects p ON p.id=a.project_id WHERE p.name=? ORDER BY a.id DESC LIMIT 25",
                (rs, n) ->
                    new GrantChange(
                        rs.getLong(1),
                        rs.getObject(2, java.time.OffsetDateTime.class),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6) == null ? null : ProjectRole.parse(rs.getString(6))),
                project)
            : List.<GrantChange>of();
    return new Access(
        project,
        effective,
        effective == ProjectRole.VIEWER
            ? List.of("read")
            : effective == ProjectRole.CONTRIBUTOR
                ? List.of("read", "work")
                : List.of("read", "work", "manage"),
        grants,
        changes);
  }

  public void assign(String project, String handle, ProjectRole role, String actor, boolean add) {
    ordinary(project);
    transactions.execute(
        status -> {
          Long id = ProjectIds.toRead(jdbc, Home.of(project));
          if (id == ProjectIds.NONE) throw new ArchiveRefusedException("No project has that name");
          lock(id);
          requireRole(project, actor, ProjectRole.MANAGER);
          if (jdbc.queryForList(
                  "SELECT 1 FROM admins WHERE handle=? AND NOT bootstrap AND account_kind<>'SERVICE_TOKEN'",
                  Integer.class,
                  handle)
              .isEmpty()) throw new ArchiveRefusedException("No permanent account has that handle");
          var previous =
              jdbc.queryForList(
                  "SELECT role FROM project_members WHERE project_id=? AND handle=?",
                  String.class,
                  id,
                  handle);
          if (!add && previous.isEmpty())
            throw new ArchiveRefusedException("That account is not a project member");
          if (add && !previous.isEmpty())
            throw new ArchiveRefusedException(
                "That account is already a member; use project.member.role to change its role");
          protectManager(id, actor, previous, role);
          if (add)
            jdbc.update(
                "INSERT INTO project_members(project_id,handle,role) VALUES (?,?,?)",
                id,
                handle,
                role.name());
          else
            jdbc.update(
                "UPDATE project_members SET role=? WHERE project_id=? AND handle=?",
                role.name(),
                id,
                handle);
          jdbc.update(
              "INSERT INTO project_access_audit(project_id,actor,target,action,role) VALUES (?,?,?,?,?)",
              id,
              actor,
              handle,
              add ? "member.add" : "member.role",
              role.name());
          return null;
        });
  }

  public void remove(String project, String handle, String actor) {
    ordinary(project);
    transactions.execute(
        status -> {
          Long id = ProjectIds.toRead(jdbc, Home.of(project));
          if (id == ProjectIds.NONE) throw new ArchiveRefusedException("No project has that name");
          lock(id);
          requireRole(project, actor, ProjectRole.MANAGER);
          var previous =
              jdbc.queryForList(
                  "SELECT role FROM project_members WHERE project_id=? AND handle=?",
                  String.class,
                  id,
                  handle);
          protectManager(id, actor, previous, null);
          if (jdbc.update("DELETE FROM project_members WHERE project_id=? AND handle=?", id, handle)
              > 0)
            jdbc.update(
                "INSERT INTO project_access_audit(project_id,actor,target,action) VALUES (?,?,?,?)",
                id,
                actor,
                handle,
                "member.remove");
          return null;
        });
  }

  private void protectManager(Long id, String actor, List<String> previous, ProjectRole next) {
    if (!isServerAdmin(actor)
        && previous.contains("MANAGER")
        && next != ProjectRole.MANAGER
        && jdbc.queryForObject(
                "SELECT count(*) FROM project_members m JOIN admins a ON a.handle=m.handle AND a.enabled WHERE project_id=? AND role='MANAGER'",
                Long.class,
                id)
            <= 1)
      throw new ArchiveRefusedException(
          "Keep an enabled project manager before removing the last manager");
  }

  /** Administrators may use ordinary projects; other accounts need an explicit membership. */
  public boolean mayUse(String project, String handle) {
    return role(project, handle).isPresent();
  }

  private void lock(Long id) {
    jdbc.queryForObject("SELECT id FROM projects WHERE id = ? FOR UPDATE", Long.class, id);
  }
}
