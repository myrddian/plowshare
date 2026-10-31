package io.aeyer.plowshare.server.applications;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.aeyer.plowshare.server.access.AccessRequest;
import io.aeyer.plowshare.server.access.ProjectAuthorization;
import io.aeyer.plowshare.server.access.ResourceScopeRepository;
import io.aeyer.plowshare.server.archive.JdbcProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.events.JdbcScheduleStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.union.UnionStore;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The half of ADR 0042's addressability inventory whose fence is a SQL predicate.
 *
 * <p><strong>Real PostgreSQL is required for exactly these assertions and for nothing else in this
 * change.</strong> Each one is a statement the database evaluates and that no mock or in-memory
 * fake can stand in for:
 *
 * <ul>
 *   <li>the two project listings' {@code name NOT LIKE 'contained:%'} predicates, which are what
 *       keep a child out of {@code project.list} and out of the catalogue that Relay worker
 *       enrollment iterates;
 *   <li>{@code authority_project_role} and {@code authority_project_management_grant}, which
 *       exclude a contained scope by <em>kind</em> rather than by name — the reason most Java call
 *       sites need no check of their own, and therefore the claim most worth proving against a real
 *       row;
 *   <li>{@code information_project_readable}, which <strong>no longer</strong> excludes a contained
 *       scope by kind. V215 widened {@code knowledge_scope_readable}, which this predicate
 *       composes, so a contained project is readable to an actor holding a project role on a scope
 *       that transitively contains it, and to nobody else. That is slice two's capability rather
 *       than a hole, and it is why the assertion here is directional: an ancestor is admitted, a
 *       global-read member that is not an ancestor is refused. The opened behaviour is covered in
 *       its own right — the self arm, the chain walk, siblings, the walk sentinel and a broken
 *       chain — by {@code io.aeyer.plowshare.server.access.ContainedKnowledgeReadSchemaTest}; what
 *       belongs here is the confinement half, because this class is the addressability inventory's
 *       evidence;
 *   <li>{@code authority_membership_changed}, a row trigger that mints a grant inside the database
 *       where no Java predicate can see it;
 *   <li>the union's {@code project_type = 'STANDARD'} filter, which is why a child acquires no
 *       working tree and no Git hub;
 *   <li>{@code JdbcScheduleStore}'s emitter-authorization predicate, which is the scheduler
 *       deciding what to fire.
 * </ul>
 *
 * <p>Each test states its fixture's parent case as well as its child case, so a predicate that
 * refused everything would fail here rather than pass as a fence.
 */
@Tag("full-db")
@Testcontainers
class ContainedAddressabilityDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withUrlParam("sslmode", "disable")
          .withUrlParam("gssEncMode", "disable");

  @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;

  private JdbcTemplate jdbc;
  private io.aeyer.plowshare.server.archive.ProjectStore projects;
  private JdbcProjectMembers members;
  private long parentId;
  private long childId;
  private String child;

  @BeforeEach
  void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var schema = "addressable_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(source).execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
    new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
    var properties = new Properties();
    properties.setProperty("currentSchema", schema + ",public");
    source.setConnectionProperties(properties);
    Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
    jdbc = new JdbcTemplate(source);
    projects =
        new io.aeyer.plowshare.server.archive.ProjectStore(
            jdbc,
            directory.resolve("config.json"),
            directory.resolve("sampling"),
            directory.resolve("token"),
            directory.resolve("export"),
            directory);
    members = new JdbcProjectMembers(jdbc);
    user("admin", true);
    user("alice", false);
    parentId = project("parent", "/tmp/parent", "STANDARD");
    child = "contained:" + UUID.randomUUID();
    // A child's project row as creation writes it: a container-owned staging workspace, MANAGED,
    // and an application boundary. The workspace is what makes the listing predicates load-bearing
    // -- every listing below filters on workspace IS NOT NULL, which a child satisfies.
    childId = project(child, "/tmp/contained", "MANAGED");
    UUID parentScope =
        jdbc.queryForObject(
            "SELECT id FROM authority_scopes WHERE project_id=?", UUID.class, parentId);
    jdbc.update(
        "INSERT INTO authority_scopes(id,kind,project_id,container) VALUES(?,'CONTAINED',?,?)",
        UUID.randomUUID(),
        childId,
        parentScope);
  }

  private void user(String handle, boolean admin) {
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password,server_admin)"
            + " VALUES (?,'fixture',FALSE,?)",
        handle,
        admin);
  }

  private long project(String name, String workspace, String type) {
    jdbc.update(
        "INSERT INTO projects(name,workspace,project_type) VALUES (?,?,?)", name, workspace, type);
    return jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, name);
  }

  private List<String> names(List<ProjectRecord> rows) {
    return rows.stream().map(ProjectRecord::name).toList();
  }

  @Test
  void projectListOmitsContainedProjectsFromEveryCaller() {
    // A server administrator is the widest caller this listing has, and the one that would have
    // seen every child on the installation.
    assertEquals(List.of("parent"), names(projects.allFor("admin")));
    assertEquals(List.of(), names(projects.allFor("alice")));
    // And the catalogue, which nobody calls with a project name at all: it is read by background
    // reconciliation and by ApplicationRelayWorkerBindings.current(), which offers every row it
    // returns for Relay worker enrollment.
    assertEquals(List.of("parent"), names(projects.all()));
    // The row is still there; it is the addressability that is withheld, not the record.
    assertEquals(
        1L,
        jdbc.queryForObject("SELECT count(*) FROM projects WHERE name=?", Long.class, child)
            .longValue());
  }

  /**
   * A principal with real authority over the container. {@code server_admin} alone mints no grant
   * on a database this fixture creates after Flyway, so the control case has to be a live grant: a
   * {@code project_members} row, which {@code authority_membership_changed} turns into one.
   */
  private void holdsParent(String handle) {
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES(?,?,'MANAGER')",
        parentId,
        handle);
  }

  /**
   * The role and management predicates, which V215 did not touch and which still refuse a contained
   * scope by kind. {@code information_project_readable} is deliberately <em>not</em> asserted here
   * any more: it no longer refuses, so an assertion about it under this name would make the name
   * false. {@link #informationReadabilityAdmitsAnAncestorAndRefusesEveryoneElse()} owns it.
   */
  @Test
  void everyRoleAndManagementPredicateRefusesAContainedProject() {
    holdsParent("alice");
    // No live grant can name a contained scope, so the role function answers nothing even for the
    // container's own MANAGER -- which is why most Java call sites need no prefix check of their
    // own, and is the claim most worth proving against a real row.
    assertNull(
        jdbc.queryForObject("SELECT authority_project_role(?,?)", String.class, child, "alice"));
    assertNull(
        jdbc.queryForObject("SELECT authority_project_role(?,?)", String.class, child, "admin"));
    assertNull(
        jdbc.queryForObject(
            "SELECT authority_project_management_grant(?,?)", UUID.class, child, "alice"));
    // The control: the same predicates do answer for the container itself, so a predicate that
    // refused everything would fail here rather than pass as a fence.
    assertEquals(
        "MANAGER",
        jdbc.queryForObject("SELECT authority_project_role(?,?)", String.class, "parent", "alice"));
    assertNotNull(
        jdbc.queryForObject(
            "SELECT authority_project_management_grant(?,?)", UUID.class, "parent", "alice"));

    // And the repository reads built on them, which is what every call site actually asks.
    assertTrue(members.role(child, "alice").isEmpty());
    assertFalse(members.mayUse(child, "alice"));
    assertFalse(members.mayWork(child, "alice"));
    assertFalse(members.mayManage(child, "alice"));
    assertFalse(members.mayAdminister(child, "alice"));
    assertFalse(members.isMember(child, "alice"));
    assertEquals(Set.of(), members.readableProjects(List.of(child), "alice"));
    assertEquals(Set.of("parent"), members.readableProjects(List.of(child, "parent"), "alice"));
  }

  /**
   * {@code information_project_readable} after V215, in both directions in one test, so it fails
   * whichever way the containment arm breaks.
   *
   * <p>The refusal half needs a principal that clears the <em>baseline</em> gate and fails only on
   * ancestry, or it would prove nothing about containment. {@code information_global_read_member}
   * is asserted directly for both principals first, because it requires an {@code admins} row and
   * the seeded {@code GLOBAL_READ} role definition and no grant at all — so every ordinary account
   * here satisfies it, and a reader who assumes otherwise will mis-explain why this test passes.
   * The non-ancestor is the server administrator, the widest principal this fixture has, and its
   * lack of a project role on the container is asserted rather than assumed.
   */
  @Test
  void informationReadabilityAdmitsAnAncestorAndRefusesEveryoneElse() {
    holdsParent("alice");
    // Both principals clear the baseline gate, so what follows turns on ancestry alone.
    assertTrue(
        jdbc.queryForObject("SELECT information_global_read_member(?)", Boolean.class, "alice"));
    assertTrue(
        jdbc.queryForObject("SELECT information_global_read_member(?)", Boolean.class, "admin"));

    // Admitted: alice holds MANAGER on the project of the scope that contains the child, which is
    // exactly the ancestor arm V215 added. This was a refusal before V215 and is the capability.
    assertEquals(
        "MANAGER",
        jdbc.queryForObject("SELECT authority_project_role(?,?)", String.class, "parent", "alice"));
    assertTrue(
        jdbc.queryForObject(
            "SELECT information_project_readable(?,?)", Boolean.class, childId, "alice"));
    assertTrue(
        jdbc.queryForObject(
            "SELECT information_project_readable(?,?)", Boolean.class, parentId, "alice"));

    // Refused: a server administrator is a global-read member and holds no role on the container,
    // so it is on no chain to the child. server_admin alone mints no grant here, which is the
    // reason holdsParent exists, and is asserted rather than relied upon.
    assertNull(
        jdbc.queryForObject("SELECT authority_project_role(?,?)", String.class, "parent", "admin"));
    assertFalse(
        jdbc.queryForObject(
            "SELECT information_project_readable(?,?)", Boolean.class, childId, "admin"));
    assertFalse(
        jdbc.queryForObject(
            "SELECT information_project_readable(?,?)", Boolean.class, parentId, "admin"));
  }

  @Test
  void aMembershipRowAgainstAChildMintsNoGrant() {
    // The repository refuses to write one; this writes it underneath that refusal, because the
    // trigger is the fence being asserted and it fires with no application code involved.
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES(?,?,'MANAGER')",
        childId,
        "alice");
    assertEquals(
        0L,
        jdbc.queryForObject(
                """
                SELECT count(*) FROM authority_grants g JOIN authority_scopes s ON s.id=g.scope_id
                WHERE s.project_id=? AND g.provenance='MEMBERSHIP' AND g.revoked_at IS NULL
                """,
                Long.class,
                childId)
            .longValue());
    assertTrue(members.role(child, "alice").isEmpty());
    // The control: the same insert against the parent does mint one.
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES(?,?,'MANAGER')",
        parentId,
        "alice");
    assertEquals(
        1L,
        jdbc.queryForObject(
                """
                SELECT count(*) FROM authority_grants g JOIN authority_scopes s ON s.id=g.scope_id
                WHERE s.project_id=? AND g.provenance='MEMBERSHIP' AND g.revoked_at IS NULL
                """,
                Long.class,
                parentId)
            .longValue());
  }

  @Test
  void creatingAChildProvisionsNoWorkingTreeOrGitHub() {
    var unions = new UnionStore(jdbc);
    // A union is the server-side working tree and the bare Git hub together, and both are keyed on
    // the project row. The filter is project_type='STANDARD', which a child's MANAGED row fails, so
    // a child is not a union candidate at all -- it is not that one was created and then disabled.
    assertTrue(unions.find(child).isEmpty());
    assertTrue(unions.enabled().stream().noneMatch(row -> row.projectId() == childId));
    assertEquals(
        "MANAGED",
        jdbc.queryForObject("SELECT project_type FROM projects WHERE id=?", String.class, childId));
    // The parent is the control: an ordinary rooted project is a union candidate.
    jdbc.update("UPDATE projects SET machine='somewhere' WHERE id=?", parentId);
    assertTrue(unions.find("parent").isPresent());
  }

  @Test
  void startupScheduleReconciliationSkipsContainedProjects() {
    jdbc.update(
        "INSERT INTO schedule_sources(account,project_id,source) VALUES('admin',?,'workspace')",
        parentId);
    jdbc.update(
        "INSERT INTO schedule_sources(account,project_id,source) VALUES('admin',?,'workspace')",
        childId);
    enrol("parent-work", parentId);
    enrol("child-work", childId);
    var store = new JdbcScheduleStore(jdbc);
    var due =
        store.due(Instant.now().plusSeconds(60)).stream().map(record -> record.name()).toList();
    assertTrue(due.contains("parent-work"), due.toString());
    // The scheduler would otherwise have fired work for a project nobody named: the emitter rule
    // admits a server administrator's file-managed schedule on any project that is not client: or
    // personal:, and a child's project satisfied that.
    assertFalse(due.contains("child-work"), due.toString());
  }

  private void enrol(String name, long project) {
    Long source =
        jdbc.queryForObject(
            "SELECT id FROM schedule_sources WHERE project_id=?", Long.class, project);
    jdbc.update(
        "INSERT INTO schedules(name,cron,zone,emits,next_fire_at,defined_by,file_managed)"
            + " VALUES(?,'* * * * *','UTC','tick',now(),'admin',TRUE)",
        name);
    jdbc.update(
        "INSERT INTO schedule_files(source_id,name,internal_name,definition,status)"
            + " VALUES(?,?,?,'{}'::jsonb,'active')",
        source,
        name + ".json",
        name);
  }

  @Test
  void anExternalPrincipalAddressingAChildDirectlyIsRefused() {
    var accounts = mock(AdminStore.class);
    var authorization =
        new ProjectAuthorization(mock(ResourceScopeRepository.class), members, accounts);
    var request = new AccessRequest(Set.of(child), List.of(), true, null);
    // A server administrator is the strongest external principal there is, and the operation is
    // the weakest read there is. Both ends are refused, at the one chokepoint every named-project
    // operation goes through.
    assertThrows(
        CallerFault.class, () -> authorization.require("conversation.search", request, "admin"));
    assertThrows(
        CallerFault.class, () -> authorization.require("conversation.open", request, "admin"));
    assertFalse(authorization.allowed("conversation.search", request, "admin"));
    assertFalse(authorization.readable(child, "admin"));
  }

  @Test
  void theParentMediatesOnlyWhatItHolds() {
    holdsParent("alice");
    var accounts = mock(AdminStore.class);
    var authorization =
        new ProjectAuthorization(mock(ResourceScopeRepository.class), members, accounts);
    var parent = new AccessRequest(Set.of("parent"), List.of(), true, null);
    // The container's own project is readable to the principal that holds MANAGER there.
    assertDoesNotThrow(() -> authorization.require("conversation.search", parent, "alice"));
    assertTrue(authorization.readable("parent", "alice"));
    assertTrue(members.role("parent", "alice").orElseThrow().allows(ProjectRole.MANAGER));
    // Addressing the container therefore succeeds exactly where the container holds the
    // permission, and carries nothing into the child: the identical request naming the child is
    // refused for the same principal, which is what makes this a mediation rather than a reach.
    var contained = new AccessRequest(Set.of(child), List.of(), true, null);
    assertThrows(
        CallerFault.class, () -> authorization.require("conversation.search", contained, "alice"));
    assertTrue(members.role(child, "alice").isEmpty());
  }
}
