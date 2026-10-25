package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * Real PostgreSQL is required here and nowhere else in this change: the assertions are the Flyway
 * migration applying over the shipped schema, the per-kind CHECK constraints themselves rejecting
 * malformed containment rows, and the automatic project-to-scope mapping declining to touch a
 * contained project. Neither a mocked repository nor an in-memory fake can evaluate a SQL CHECK or
 * fire a trigger, and those are the fence, so they are proved where they live.
 */
@Tag("full-db")
@Testcontainers
class ContainedScopeSchemaTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withUrlParam("sslmode", "disable")
          .withUrlParam("gssEncMode", "disable");

  private JdbcTemplate jdbc;
  private long parentProject;
  private long childProject;
  private long ordinaryProject;
  private UUID parentScope;

  @BeforeEach
  void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var schema = "contained_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(source).execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
    new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
    var properties = new Properties();
    properties.setProperty("currentSchema", schema + ",public");
    source.setConnectionProperties(properties);
    Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
    jdbc = new JdbcTemplate(source);
    user("admin", true);
    user("alice", false);
    parentProject = project("parent");
    ordinaryProject = project("ordinary");
    childProject = contained();
    parentScope =
        jdbc.queryForObject(
            "SELECT id FROM authority_scopes WHERE project_id=?", UUID.class, parentProject);
  }

  private void user(String handle, boolean admin) {
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password,server_admin)"
            + " VALUES (?,'fixture',FALSE,?)",
        handle,
        admin);
  }

  private long project(String name) {
    jdbc.update("INSERT INTO projects(name) VALUES (?)", name);
    return jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, name);
  }

  /** A child project's name carries only the fixed classification prefix; ancestry is a column. */
  private long contained() {
    return project("contained:" + UUID.randomUUID());
  }

  private int insert(UUID id, String kind, Long project, UUID container) {
    return jdbc.update(
        "INSERT INTO authority_scopes(id,kind,project_id,container) VALUES (?,?,?,?)",
        id,
        kind,
        project,
        container);
  }

  private long scopes(long project) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM authority_scopes WHERE project_id=?", Long.class, project);
  }

  @Test
  void migrationAcceptsAContainedScopeWithAContainer() {
    var child = UUID.randomUUID();
    assertEquals(1, insert(child, "CONTAINED", childProject, parentScope));
    assertEquals(
        parentScope,
        jdbc.queryForObject(
            "SELECT container FROM authority_scopes WHERE id=?", UUID.class, child));
    // Containment nests: a contained scope is itself a legal container.
    assertEquals(1, insert(UUID.randomUUID(), "CONTAINED", contained(), child));
  }

  @Test
  void containedScopeWithoutAContainerViolatesTheCheck() {
    assertThrows(
        DataIntegrityViolationException.class,
        () -> insert(UUID.randomUUID(), "CONTAINED", childProject, null));
    // A contained scope is a project confinement, never an account identity. With no project key
    // there is no contained project to find, so the shape trigger refuses before the CHECK is
    // reached; either refusal is the fence, and this asserts the one that actually fires.
    var refusal =
        assertThrows(
            DataAccessException.class,
            () -> insert(UUID.randomUUID(), "CONTAINED", null, parentScope));
    assertTrue(refusal.getMessage().contains("requires a contained project"), refusal.getMessage());
  }

  @Test
  void nonContainedKindsRejectAContainer() {
    assertEquals(
        1, jdbc.update("DELETE FROM authority_scopes WHERE project_id=?", ordinaryProject));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> insert(UUID.randomUUID(), "PROJECT", ordinaryProject, parentScope));
  }

  @Test
  void aScopeCannotContainItself() {
    var self = UUID.randomUUID();
    assertThrows(
        DataIntegrityViolationException.class, () -> insert(self, "CONTAINED", childProject, self));
  }

  @Test
  void aContainerCannotBeDroppedWhileItStillContainsAScope() {
    assertEquals(1, insert(UUID.randomUUID(), "CONTAINED", childProject, parentScope));
    // Refusal, not a silent cascade: descendants are destroyed by the capability that owns their
    // lifecycle, so the schema must not quietly collect them when a container disappears.
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("DELETE FROM authority_scopes WHERE id=?", parentScope));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("DELETE FROM projects WHERE id=?", parentProject));
  }

  /**
   * Ordinary project creation maps every project to one scope through an AFTER INSERT OR UPDATE
   * trigger. A contained project must be left alone, or it acquires an addressable project scope
   * that nobody named.
   */
  @Test
  void aContainedProjectIsNotMappedToAScopeAutomatically() {
    assertEquals(0L, scopes(childProject));
    assertEquals(1L, scopes(parentProject));
    assertEquals(1L, scopes(ordinaryProject));
  }

  /**
   * The case that would have been silent: the mapping is an upsert with {@code DO UPDATE SET
   * kind=EXCLUDED.kind} on a UNIQUE {@code project_id}, driven by a trigger that also fires on
   * UPDATE, so an unrelated later edit of the project row could retroactively convert a contained
   * scope back into an ordinary addressable one.
   */
  @Test
  void aContainedScopeSurvivesAnUpdateOfItsProjectRow() {
    var child = UUID.randomUUID();
    assertEquals(1, insert(child, "CONTAINED", childProject, parentScope));
    assertEquals(
        1, jdbc.update("UPDATE projects SET personal_owner=NULL WHERE id=?", childProject));
    assertEquals(
        1,
        jdbc.update(
            "UPDATE projects SET name='contained:'||gen_random_uuid() WHERE id=?", childProject));
    assertEquals(1L, scopes(childProject));
    assertEquals(
        "CONTAINED",
        jdbc.queryForObject("SELECT kind FROM authority_scopes WHERE id=?", String.class, child));
    assertEquals(
        parentScope,
        jdbc.queryForObject(
            "SELECT container FROM authority_scopes WHERE id=?", UUID.class, child));
  }

  @Test
  void aContainedScopeRequiresAContainedProject() {
    assertEquals(
        1, jdbc.update("DELETE FROM authority_scopes WHERE project_id=?", ordinaryProject));
    var refusal =
        assertThrows(
            DataAccessException.class,
            () -> insert(UUID.randomUUID(), "CONTAINED", ordinaryProject, parentScope));
    assertTrue(refusal.getMessage().contains("requires a contained project"), refusal.getMessage());
    // The mirror image: a contained project cannot be given an ordinary project scope either.
    assertThrows(
        DataAccessException.class, () -> insert(UUID.randomUUID(), "PROJECT", childProject, null));
  }

  @Test
  void aContainedScopeRequiresAnExistingContainer() {
    var refusal =
        assertThrows(
            DataAccessException.class,
            () -> insert(UUID.randomUUID(), "CONTAINED", childProject, UUID.randomUUID()));
    assertTrue(
        refusal.getMessage().contains("requires an existing container"), refusal.getMessage());
  }

  private UUID containedScope() {
    var child = UUID.randomUUID();
    assertEquals(1, insert(child, "CONTAINED", childProject, parentScope));
    return child;
  }

  private long grants(UUID scope) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM authority_grants WHERE scope_id=?", Long.class, scope);
  }

  /**
   * The database mints MEMBERSHIP grants itself, from an AFTER INSERT OR UPDATE OR DELETE trigger
   * on {@code project_members}. No application-side exclusion can see that, so the trigger must
   * resolve nothing for a contained scope.
   */
  @Test
  void aProjectMembersRowMintsNoGrantAgainstAContainedScope() {
    var child = containedScope();
    assertEquals(
        1,
        jdbc.update(
            "INSERT INTO project_members(project_id,handle,role) VALUES (?,?,'CONTRIBUTOR')",
            childProject,
            "alice"));
    assertEquals(0L, grants(child));
  }

  @Test
  void anExplicitGrantAgainstAContainedScopeIsRefusedByAdmission() {
    var child = containedScope();
    var control = control();
    var refusal = assertThrows(DataAccessException.class, () -> grant(child, control, "READER"));
    assertTrue(refusal.getMessage().contains("cannot receive that role"), refusal.getMessage());
    assertEquals(0L, grants(child));
  }

  /**
   * Defence in depth, asserted independently of the admission trigger: were a grant to reach the
   * table by any route, every live-grant read must still decline to honour it. The trigger is
   * disabled only to place the row.
   */
  @Test
  void aGrantAgainstAContainedScopeIsNotLive() {
    var child = containedScope();
    var control = control();
    jdbc.execute("ALTER TABLE authority_grants DISABLE TRIGGER authority_grant_insert");
    try {
      grant(child, control, "READER");
    } finally {
      jdbc.execute("ALTER TABLE authority_grants ENABLE TRIGGER authority_grant_insert");
    }
    assertEquals(1L, grants(child));
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT count(*) FROM authority_grants g JOIN authority_live_grants() l ON l.id=g.id "
                + "WHERE g.scope_id=?",
            Long.class,
            child));
  }

  /**
   * The server admin's own live project-control authority; more than one ADMIN_CONTROL row exists,
   * so the coverage and an explicit order pick the same one every run.
   */
  private UUID control() {
    return jdbc.queryForObject(
        "SELECT g.id FROM authority_grants g JOIN authority_live_grants() l ON l.id=g.id "
            + "WHERE g.provenance='ADMIN_CONTROL' AND g.coverage='ORDINARY_PROJECT_CONTROL' "
            + "ORDER BY g.created_at,g.id LIMIT 1",
        UUID.class);
  }

  private void grant(UUID scope, UUID authority, String role) {
    jdbc.update(
        "INSERT INTO authority_grants(subject_account,scope_id,role_id,requested_role,"
            + "issuer_account,issuing_grant,provenance) "
            + "SELECT a.id,?,r.id,?,p.subject_account,p.id,'ROLE' "
            + "FROM authority_accounts a, authority_roles r, authority_grants p "
            + "WHERE a.legacy_handle='alice' AND r.name=? AND p.id=?",
        scope,
        role,
        role,
        authority);
  }

  /**
   * The prefix is load-bearing, so losing it must not be survivable. Renaming a contained project
   * drives the mapping trigger, which tries to upsert the scope back to PROJECT and is stopped by
   * the kind/container shape constraint.
   */
  @Test
  void renamingAContainedProjectOutOfItsPrefixIsRefused() {
    var child = containedScope();
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("UPDATE projects SET name='now-ordinary' WHERE id=?", childProject));
    assertEquals(
        "CONTAINED",
        jdbc.queryForObject("SELECT kind FROM authority_scopes WHERE id=?", String.class, child));
  }

  private UUID container(UUID scope) {
    return jdbc.queryForObject(
        "SELECT container FROM authority_scopes WHERE id=?", UUID.class, scope);
  }

  /**
   * V196. Containment ancestry is fixed at insert. V155's lock already refuses a direct UPDATE of a
   * scope row, so this asserts the refusal a caller actually meets and that it names containment —
   * the nested-trigger route the lock deliberately permits is covered separately below.
   */
  @Test
  void aContainedScopeContainerCannotBeRepointed() {
    var child = containedScope();
    var sibling = UUID.randomUUID();
    assertEquals(1, insert(sibling, "CONTAINED", contained(), parentScope));
    var refusal =
        assertThrows(
            DataAccessException.class,
            () ->
                jdbc.update("UPDATE authority_scopes SET container=? WHERE id=?", sibling, child));
    assertTrue(refusal.getMessage().contains("containment is immutable"), refusal.getMessage());
    // Dropping the container is the same move spelled with a NULL.
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("UPDATE authority_scopes SET container=NULL WHERE id=?", child));
    assertEquals(parentScope, container(child));
  }

  /**
   * The route V196 actually adds. V155's lock permits an UPDATE of a scope row from inside a nested
   * trigger — {@code pg_trigger_depth()>=2}, which today is the project-to-scope mapping upsert —
   * and that route must not be able to carry a container change either. A fixture trigger is the
   * only way to reach it, since the shipped mapping excludes contained projects entirely.
   */
  @Test
  void containmentCannotBeRepointedEvenFromANestedTrigger() {
    var outer = containedScope();
    var sibling = UUID.randomUUID();
    assertEquals(1, insert(sibling, "CONTAINED", contained(), parentScope));
    jdbc.execute(
        "CREATE FUNCTION fixture_repoint() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
            + " UPDATE authority_scopes SET container='"
            + sibling
            + "' WHERE id='"
            + outer
            + "'; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER fixture_repoint AFTER UPDATE ON projects"
            + " FOR EACH ROW EXECUTE FUNCTION fixture_repoint()");
    try {
      var refusal =
          assertThrows(
              DataAccessException.class,
              () -> jdbc.update("UPDATE projects SET name='renamed' WHERE id=?", ordinaryProject));
      assertTrue(refusal.getMessage().contains("containment is immutable"), refusal.getMessage());
    } finally {
      jdbc.execute("DROP TRIGGER fixture_repoint ON projects");
      jdbc.execute("DROP FUNCTION fixture_repoint()");
    }
    assertEquals(parentScope, container(outer));
  }

  /**
   * The consequence that matters. A cycle is not merely wrong data: the authority path walks the
   * container chain to bound containment depth, so a cycle would make that walk loop rather than
   * refuse. An insert can only name a container that already exists, so the only way to close
   * A-contains-B-contains-A is to repoint A; with that refused, no chain can revisit a scope at any
   * depth and the relation is a forest by construction.
   */
  @Test
  void aContainmentCycleCannotBeBuilt() {
    var outer = containedScope();
    var inner = UUID.randomUUID();
    assertEquals(1, insert(inner, "CONTAINED", contained(), outer));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("UPDATE authority_scopes SET container=? WHERE id=?", inner, outer));
    assertEquals(parentScope, container(outer));
    assertEquals(outer, container(inner));
  }

  @Test
  void existingKindsAndRowsAreUnchanged() {
    assertEquals(
        "PROJECT",
        jdbc.queryForObject(
            "SELECT kind FROM authority_scopes WHERE project_id=?", String.class, parentProject));
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT count(*) FROM authority_scopes WHERE container IS NOT NULL", Long.class));
  }
}
