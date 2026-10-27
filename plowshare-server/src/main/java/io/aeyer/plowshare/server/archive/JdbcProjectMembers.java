package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Accounts allowed to root a project and reach its files. */
@Repository
public class JdbcProjectMembers implements ProjectMembers {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public JdbcProjectMembers(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.transactions =
        new TransactionTemplate(
            new DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource(), "dataSource")));
  }

  @Override
  public java.util.Optional<String> authorityAccount(String principal) {
    if (!io.aeyer.plowshare.server.auth.ServiceCredentials.principal(principal))
      return java.util.Optional.ofNullable(principal);
    return ArchiveUnavailableException.translating(
        "read service account authority",
        () ->
            jdbc
                .queryForList(
                    "SELECT owner_handle FROM service_tokens WHERE principal_handle = ? AND account_active(principal_handle)",
                    String.class,
                    principal)
                .stream()
                .findFirst());
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
                        + " ON p.id = m.project_id JOIN admins a ON a.handle=m.handle AND a.enabled WHERE p.name = ? AND m.handle = ? AND (p.name LIKE 'client:%' OR authority_project_role(p.name,m.handle) IS NOT NULL)",
                    Integer.class, project, handle)
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
    // ADR 0042, list (c): a contained project has no membership of its own. A project_members row
    // against one mints nothing -- V195 makes authority_membership_changed resolve no scope for a
    // CONTAINED one -- so the row would be authority-free clutter that nonetheless makes a child
    // look like a shared project to every membership listing. Refused rather than tolerated.
    if (io.aeyer.plowshare.protocol.ContainedProjects.name(project))
      throw new ArchiveRefusedException(
          "A contained Application's project is reached only through its container and cannot take"
              + " members");
  }

  public void add(String project, String handle) {
    ordinary(project);
    io.aeyer.plowshare.server.access.BuiltInIdentities.requireOrdinaryAccount(handle);
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
    var roles =
        jdbc.queryForList(
            ClientProjects.privateProject(project)
                ? "SELECT m.role FROM project_members m JOIN projects p ON p.id=m.project_id JOIN admins a ON a.handle=m.handle AND a.enabled WHERE p.name=? AND m.handle=?"
                : "SELECT role FROM (SELECT authority_project_role(?,?) AS role) effective WHERE role IS NOT NULL",
            String.class,
            project,
            handle);
    if (roles.isEmpty()) return java.util.Optional.empty();
    return java.util.Optional.of(
        project.equals(io.aeyer.plowshare.server.personal.PersonalSpaces.name(handle))
            ? ProjectRole.MANAGER
            : ProjectRole.parse(roles.getFirst()));
  }

  @Override
  public java.util.Map<String, ProjectRole> roles(
      java.util.Collection<String> projects, String account) {
    var selection =
        new ReadSelection(account, projects == null ? null : new java.util.ArrayList<>(projects));
    if (selection.projects().isEmpty()) return java.util.Map.of();
    var rows =
        jdbc.query(
            """
        WITH live AS MATERIALIZED (SELECT id FROM authority_live_grants()),
        actor AS MATERIALIZED (SELECT ?::text AS handle,?::text AS personal,?::boolean AS service),
        requested AS MATERIALIZED (SELECT value AS project FROM jsonb_array_elements_text(?::jsonb)),
        ordinary AS MATERIALIZED (
          SELECT p.name,CASE WHEN bool_or(role.name='MANAGER') THEN 'MANAGER'
            WHEN bool_or(role.name='CONTRIBUTOR') THEN 'CONTRIBUTOR'
            WHEN bool_or(role.name='READER') THEN 'VIEWER' END AS role
          FROM actor JOIN authority_accounts a ON a.legacy_handle=CASE WHEN actor.service
            THEN (SELECT owner_handle FROM service_tokens WHERE principal_handle=actor.handle)
            ELSE actor.handle END
          JOIN authority_grants g ON g.subject_account=a.id JOIN live ON live.id=g.id
          JOIN authority_scopes scope ON scope.id=g.scope_id
          JOIN projects p ON p.id=scope.project_id JOIN requested ON requested.project=p.name
          JOIN authority_roles role ON role.id=g.role_id
          WHERE g.subject_agent IS NULL AND g.coverage='SELF'
            AND (scope.kind='PROJECT' OR (scope.kind='PERSONAL' AND scope.account_id=a.id))
          GROUP BY p.name
        ), resolved AS (
          SELECT requested.project,CASE
            WHEN actor.service THEN (
              SELECT CASE WHEN ordinary.role='VIEWER' OR ceiling.role='VIEWER' THEN 'VIEWER'
                WHEN ordinary.role='CONTRIBUTOR' OR ceiling.role='CONTRIBUTOR' THEN 'CONTRIBUTOR'
                ELSE 'MANAGER' END
              FROM service_tokens token JOIN service_token_scopes ceiling ON ceiling.token_id=token.id
              JOIN projects p ON p.id=ceiling.project_id AND p.name=requested.project
              WHERE token.principal_handle=actor.handle AND account_active(actor.handle)
                AND ordinary.role IS NOT NULL AND p.personal_owner IS NULL
                AND p.name NOT LIKE 'client:%' AND p.name NOT LIKE 'personal:%')
            WHEN requested.project LIKE 'personal:%' AND requested.project<>actor.personal THEN NULL
            WHEN requested.project LIKE 'client:%' THEN (
              SELECT m.role FROM project_members m JOIN projects p ON p.id=m.project_id
              JOIN admins a ON a.handle=m.handle AND a.enabled
              WHERE p.name=requested.project AND m.handle=actor.handle)
            WHEN requested.project=actor.personal AND ordinary.role IS NOT NULL THEN 'MANAGER'
            ELSE ordinary.role END AS role
          FROM requested CROSS JOIN actor LEFT JOIN ordinary ON ordinary.name=requested.project
        ) SELECT project,role FROM resolved WHERE role IS NOT NULL
        """,
            (row, index) ->
                java.util.Map.entry(row.getString(1), ProjectRole.parse(row.getString(2))),
            selection.account(),
            io.aeyer.plowshare.server.personal.PersonalSpaces.name(selection.account()),
            io.aeyer.plowshare.server.auth.ServiceCredentials.principal(selection.account()),
            io.aeyer.plowshare.server.information.InformationJson.json(selection.projects()));
    var result = new java.util.LinkedHashMap<String, ProjectRole>();
    rows.forEach(row -> result.put(row.getKey(), row.getValue()));
    return java.util.Map.copyOf(result);
  }

  @Override
  public java.util.Set<String> readableProjects(
      java.util.Collection<String> projects, String account) {
    var selection =
        new ReadSelection(account, projects == null ? null : new java.util.ArrayList<>(projects));
    if (selection.projects().isEmpty()) return java.util.Set.of();
    // Keep role().isPresent semantics exactly: machine principals use their live token ceiling;
    // foreign Personal spaces fail closed; client projects use enabled raw membership; ordinary
    // projects use the existing live RBAC function. This batches round trips, not policy caching.
    return ArchiveUnavailableException.translating(
        "read candidate project membership",
        () ->
            java.util.Set.copyOf(
                jdbc.queryForList(
                    """
            WITH actor AS (SELECT ?::text AS handle, ?::text AS personal, ?::boolean AS service),
                 requested AS (SELECT value AS project FROM jsonb_array_elements_text(?::jsonb))
            SELECT requested.project FROM requested CROSS JOIN actor
            WHERE CASE
              WHEN actor.service THEN service_project_role(requested.project,actor.handle) IS NOT NULL
              WHEN requested.project LIKE 'personal:%' AND requested.project<>actor.personal THEN FALSE
              WHEN requested.project LIKE 'client:%' THEN EXISTS (
                SELECT 1 FROM project_members m JOIN projects p ON p.id=m.project_id
                JOIN admins a ON a.handle=m.handle AND a.enabled
                WHERE p.name=requested.project AND m.handle=actor.handle)
              ELSE authority_project_role(requested.project,actor.handle) IS NOT NULL
            END
            """,
                    String.class,
                    selection.account(),
                    io.aeyer.plowshare.server.personal.PersonalSpaces.name(selection.account()),
                    io.aeyer.plowshare.server.auth.ServiceCredentials.principal(
                        selection.account()),
                    io.aeyer.plowshare.server.information.InformationJson.json(
                        selection.projects()))));
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

  @Override
  public boolean mayAdminister(String project, String actor) {
    if (project == null || actor == null) return false;
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM authority_grants g JOIN authority_roles r ON r.id=g.role_id "
                + "WHERE g.id=authority_project_management_grant(?,?) AND r.name='ADMIN')",
            Boolean.class,
            project,
            actor));
  }

  @Override
  public Access managementAccess(String project, String actor) {
    return mayAdminister(project, actor)
        ? administratorAccess(project)
        : ordinaryAccess(project, actor);
  }

  private Access administratorAccess(String project) {
    Access result = projectAccess(project, ProjectRole.MANAGER);
    return new Access(
        project, ProjectRole.MANAGER, List.of("manage"), result.members(), result.history());
  }

  public Access access(String project, String handle) {
    return mayAdminister(project, handle)
        ? administratorAccess(project)
        : ordinaryAccess(project, handle);
  }

  private Access ordinaryAccess(String project, String handle) {
    if (ProjectIds.toRead(jdbc, Home.of(project)) == ProjectIds.NONE)
      throw new ArchiveRefusedException("No project has that name");
    // This is one operation-local authority resolution. A future command must resolve it again;
    // member/history hydration below neither grants authority nor calls external providers.
    ProjectRole effective =
        role(project, handle)
            .filter(role -> role.allows(ProjectRole.VIEWER))
            .orElseThrow(
                () ->
                    new io.aeyer.plowshare.server.faults.CallerFault(
                        "This account needs "
                            + ProjectRole.VIEWER
                            + " access to project '"
                            + project
                            + "'"));
    return projectAccess(project, effective);
  }

  private Access projectAccess(String project, ProjectRole effective) {
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
          if (!mayAdminister(project, actor)) requireRole(project, actor, ProjectRole.MANAGER);
          if (jdbc.queryForList(
                  "SELECT 1 FROM admins WHERE handle=? AND NOT bootstrap AND account_kind IN ('USER','SERVICE')",
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
          jdbc.queryForObject(
              "SELECT authority_issue_membership(?,?,?,?)",
              java.util.UUID.class,
              project,
              handle,
              actor,
              role.name());
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
          protectResultingManagers(id, project, actor, previous, role);
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
          if (!mayAdminister(project, actor)) requireRole(project, actor, ProjectRole.MANAGER);
          var previous =
              jdbc.queryForList(
                  "SELECT role FROM project_members WHERE project_id=? AND handle=?",
                  String.class,
                  id,
                  handle);
          protectManager(id, actor, previous, null);
          jdbc.update(
              "UPDATE authority_grants SET revoked_at=now(),version=version+1,"
                  + "revoked_by=(SELECT id FROM authority_accounts WHERE legacy_handle=?),"
                  + "revoking_grant=authority_membership_authority(?,?) WHERE scope_id=(SELECT id FROM authority_scopes WHERE project_id=?) "
                  + "AND subject_account=(SELECT id FROM authority_accounts WHERE legacy_handle=?) AND provenance='MEMBERSHIP' AND revoked_at IS NULL",
              actor,
              project,
              actor,
              id,
              handle);
          if (jdbc.update("DELETE FROM project_members WHERE project_id=? AND handle=?", id, handle)
              > 0)
            jdbc.update(
                "INSERT INTO project_access_audit(project_id,actor,target,action) VALUES (?,?,?,?)",
                id,
                actor,
                handle,
                "member.remove");
          protectResultingManagers(id, project, actor, previous, null);
          return null;
        });
  }

  private void protectManager(Long id, String actor, List<String> previous, ProjectRole next) {
    if (!mayAdminister(projectName(id), actor)
        && previous.contains("MANAGER")
        && next != ProjectRole.MANAGER
        && jdbc.queryForObject(
                "SELECT authority_project_manager_count(id) FROM authority_scopes WHERE project_id=?",
                Long.class,
                id)
            <= 1)
      throw new ArchiveRefusedException(
          "Keep an enabled project manager before removing the last manager");
  }

  private String projectName(Long id) {
    return jdbc.queryForObject("SELECT name FROM projects WHERE id=?", String.class, id);
  }

  private void protectResultingManagers(
      Long id, String project, String actor, List<String> previous, ProjectRole next) {
    if (mayAdminister(project, actor)
        || !previous.contains("MANAGER")
        || next == ProjectRole.MANAGER) return;
    if (jdbc.queryForObject(
            "SELECT authority_project_manager_count(id) FROM authority_scopes WHERE project_id=?",
            Long.class,
            id)
        == 0)
      throw new ArchiveRefusedException(
          "Keep an enabled project manager before removing the last manager");
  }

  /** Data use follows explicit live grants; administrative control is checked separately. */
  public boolean mayUse(String project, String handle) {
    return role(project, handle).isPresent();
  }

  private void lock(Long id) {
    jdbc.queryForObject("SELECT id FROM projects WHERE id = ? FOR UPDATE", Long.class, id);
  }
}
