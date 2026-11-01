package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * Real PostgreSQL, because the assertions are the behaviour of {@code knowledge_revision_readable}
 * and {@code knowledge_read_authority}. A contained principal is a bare UUID with no account, so
 * the baseline those predicates open with ({@code information_global_read_member}) refuses it; V224
 * admits it to its own subtree and, in the same statement, keeps it out of the general arm.
 *
 * <p>The successes read downward only. The refusals are what prove the direction, and the
 * generally-readable refusal proves the arm is not a grant of general-read membership.
 */
@Tag("full-db")
@Testcontainers
class ContainedActorReadSchemaTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withUrlParam("sslmode", "disable")
          .withUrlParam("gssEncMode", "disable");

  private static final String HOLDER = "alice";
  private static final String STRANGER = "stranger";
  private static final String ADMINISTRATOR = "admin";
  private static final String TOKEN_PRINCIPAL = "@service/probe";

  private JdbcTemplate jdbc;
  private long parentProject;
  private UUID parentScope;
  private UUID childScope;
  private UUID grandchildScope;
  private UUID siblingScope;
  private String childPrincipal;
  private String grandchildPrincipal;
  private String siblingPrincipal;

  @BeforeEach
  void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var schema = "contained_actor_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(source).execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
    new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
    var properties = new Properties();
    properties.setProperty("currentSchema", schema + ",public");
    source.setConnectionProperties(properties);
    Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
    jdbc = new JdbcTemplate(source);

    user(ADMINISTRATOR, true);
    user(HOLDER, false);
    user(STRANGER, false);
    serviceAccounts();
    parentProject = project("parent");
    parentScope =
        jdbc.queryForObject(
            "SELECT id FROM authority_scopes WHERE project_id=?", UUID.class, parentProject);
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES (?,?,'CONTRIBUTOR')",
        parentProject,
        HOLDER);
    homes.put(parentScope, new Home(parentProject, "parent"));

    childScope = contained(parentScope);
    grandchildScope = contained(childScope);
    siblingScope = contained(parentScope);
    childPrincipal = principal(childScope);
    grandchildPrincipal = principal(grandchildScope);
    siblingPrincipal = principal(siblingScope);
  }

  private void user(String handle, boolean admin) {
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password,server_admin)"
            + " VALUES (?,'fixture',FALSE,?)",
        handle,
        admin);
  }

  /** A SERVICE owner plus its SERVICE_TOKEN principal: the pair {@code account_active} requires. */
  private void serviceAccounts() {
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,account_kind,must_change_password)"
            + " VALUES ('owner','fixture','SERVICE',FALSE),(?,'fixture','SERVICE_TOKEN',FALSE)",
        TOKEN_PRINCIPAL);
  }

  /**
   * The token's owner holds the container's MANAGER role, so {@code service_project_role} resolves
   * on {@code parent} and the token principal really does read the container's own scope. Without
   * that, the service-account refusal below would pass for want of a fixture rather than because
   * the new arm excludes service roles.
   */
  private void serviceToken() {
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES (?,'owner','MANAGER')",
        parentProject);
    var token = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO service_tokens(id,owner_handle,name,principal_handle,digest,expires_at)"
            + " VALUES (?,'owner','probe',?,?,now()+interval '1 day')",
        token,
        TOKEN_PRINCIPAL,
        token.toString());
    jdbc.update(
        "INSERT INTO service_token_scopes(token_id,project_id,role) VALUES (?,?,'MANAGER')",
        token,
        parentProject);
  }

  private long project(String name) {
    jdbc.update("INSERT INTO projects(name) VALUES (?)", name);
    return jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, name);
  }

  /**
   * A fresh ordinary project outside the containment subtree, registered as a home so {@link
   * #retained} can place a document in it. Its PROJECT scope is the one the project trigger mints.
   */
  private UUID unrelatedProject(String name) {
    var project = project(name);
    var scope =
        jdbc.queryForObject(
            "SELECT id FROM authority_scopes WHERE project_id=?", UUID.class, project);
    homes.put(scope, new Home(project, name));
    return scope;
  }

  /** The contained project behind a contained scope, kept so a boundary home can name it. */
  private record Home(long project, String name) {}

  private final java.util.Map<UUID, Home> homes = new java.util.HashMap<>();

  /** A contained scope over a fresh contained project; ancestry is the column, not the name. */
  private UUID contained(UUID container) {
    var name = "contained:" + UUID.randomUUID();
    var project = project(name);
    var scope = UUID.randomUUID();
    assertEquals(
        1,
        jdbc.update(
            "INSERT INTO authority_scopes(id,kind,project_id,container) VALUES (?,'CONTAINED',?,?)",
            scope,
            project,
            container));
    homes.put(scope, new Home(project, name));
    return scope;
  }

  /** A contained principal's actor identifier is its bare UUID: it has no handle and no account. */
  private String principal(UUID scope) {
    jdbc.update("INSERT INTO contained_principals(scope_id) VALUES (?)", scope);
    return jdbc.queryForObject(
            "SELECT id FROM contained_principals WHERE scope_id=?", UUID.class, scope)
        .toString();
  }

  private boolean readable(String actor, UUID scope) {
    return jdbc.queryForObject("SELECT knowledge_scope_readable(?,?)", Boolean.class, actor, scope);
  }

  /**
   * A retained document whose home is {@code scope}. The home is resolved by the shipped mapping
   * from the project and the {@code project:}-prefixed namespace, so nothing here asserts a home
   * the schema would not have chosen; the initial policy row the insert trigger writes is HOME.
   *
   * <p>The owner is deliberately the stranger, so that the stranger's refusals below also establish
   * that ownership is not a route into a contained home.
   */
  private UUID retained(UUID scope) {
    var home = homes.get(scope);
    var resource = UUID.randomUUID();
    var revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind,tags)"
            + " VALUES (?,?,?,?,?,'source','[]')",
        resource,
        "project:" + home.name(),
        resource + ".txt",
        STRANGER,
        home.project());
    assertEquals(
        scope,
        jdbc.queryForObject(
            "SELECT home_scope_id FROM information_resources WHERE id=?", UUID.class, resource));
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,"
            + "byte_size,extracted_text) VALUES (?,?,1,'Child output','text/plain',?,1,'output')",
        revision,
        resource,
        revision.toString());
    return revision;
  }

  private boolean revisionReadable(String actor, UUID revision) {
    return jdbc.queryForObject(
        "SELECT coalesce(knowledge_revision_readable(?,?),FALSE)", Boolean.class, revision, actor);
  }

  /** The retained read ceiling, asked about one boundary home by name. */
  private boolean inBoundary(String actor, UUID revision, String home) {
    return jdbc.queryForObject(
        "SELECT coalesce(knowledge_document_in_boundary(?,?,?,FALSE),FALSE)",
        Boolean.class,
        revision,
        actor,
        home);
  }

  /**
   * The same ceiling asked with the general-read flag on. Only the global arm differs, so this is
   * the positive control for the refusals that turn on that flag being off.
   */
  private boolean inBoundaryWithGeneralRead(String actor, UUID revision, String home) {
    return jdbc.queryForObject(
        "SELECT coalesce(knowledge_document_in_boundary(?,?,?,TRUE),FALSE)",
        Boolean.class,
        revision,
        actor,
        home);
  }

  /** Whether a revision is generally readable at all, independently of any ceiling. */
  private boolean generallyReadable(UUID revision) {
    return jdbc.queryForObject(
        "SELECT coalesce(knowledge_general_readable(?),FALSE)", Boolean.class, revision);
  }

  /**
   * Publish a retained document to the general audience, so the global arm has something to find.
   */
  private void publish(UUID revision) {
    assertEquals(
        1,
        jdbc.update(
            "UPDATE information_resource_policies SET version=version+1,audience='GLOBAL_READ',"
                + "private_only=FALSE,reason='AUDIENCE',changed_at=now(),"
                + "actor=(SELECT id FROM authority_accounts WHERE legacy_handle=?)"
                + " WHERE resource_id=(SELECT resource_id FROM information_revisions WHERE id=?)",
            ADMINISTRATOR,
            revision));
  }

  /** A consumed input, so a closure can be made to leave the subtree it was written in. */
  private void input(UUID derived, UUID from) {
    jdbc.update(
        "INSERT INTO information_inputs(derived_revision,input_revision) VALUES (?,?)",
        derived,
        from);
  }

  private boolean dependenciesReadable(String actor, UUID revision) {
    return jdbc.queryForObject(
        "SELECT coalesce(knowledge_dependencies_readable(?,?),FALSE)",
        Boolean.class,
        revision,
        actor);
  }

  @Test
  void aChildReadsItsOwnDocument() {
    assertTrue(revisionReadable(childPrincipal, retained(childScope)));
  }

  @Test
  void aChildIsRefusedAGenerallyReadableRowItNeverNamed() {
    var ordinary = unrelatedProject("ordinary");
    var row = retained(ordinary);
    publish(row);

    assertTrue(generallyReadable(row), "the row really is generally readable");
    assertTrue(revisionReadable(HOLDER, row), "a general-read member reads it");
    assertFalse(revisionReadable(childPrincipal, row));
  }

  @Test
  void aChildIsRefusedItsContainersDocument() {
    assertFalse(revisionReadable(childPrincipal, retained(parentScope)));
  }

  @Test
  void aChildIsRefusedASiblingsDocument() {
    assertFalse(revisionReadable(childPrincipal, retained(siblingScope)));
    assertFalse(revisionReadable(siblingPrincipal, retained(childScope)));
  }

  @Test
  void aGrandchildIsRefusedItsAncestorsDocument() {
    assertFalse(revisionReadable(grandchildPrincipal, retained(childScope)));
    assertFalse(revisionReadable(grandchildPrincipal, retained(parentScope)));
  }

  @Test
  void aContainerStillReadsItsDescendantsDocument() {
    assertTrue(revisionReadable(HOLDER, retained(childScope)));
    assertTrue(revisionReadable(HOLDER, retained(grandchildScope)));
  }

  @Test
  void anOrdinaryHolderIsUnaffectedByTheNewArm() {
    var document = retained(childScope);

    assertTrue(revisionReadable(HOLDER, document));
    assertFalse(revisionReadable(STRANGER, document));
    assertFalse(revisionReadable(ADMINISTRATOR, document));
    assertFalse(revisionReadable(TOKEN_PRINCIPAL, document));
  }

  @Test
  void aRevokedPrincipalLosesTheArm() {
    var document = retained(childScope);
    assertTrue(revisionReadable(childPrincipal, document));

    assertEquals(
        1, jdbc.update("DELETE FROM contained_principals WHERE id=?::uuid", childPrincipal));

    assertFalse(revisionReadable(childPrincipal, document));
  }

  @Test
  void aNonUuidActorDoesNotCrashThePredicate() {
    for (var actor : List.of("holder", "", "not-a-uuid", "@service/probe")) {
      assertFalse(
          jdbc.queryForObject("SELECT contained_knowledge_actor(?)", Boolean.class, actor), actor);
    }
    assertTrue(
        jdbc.queryForObject("SELECT contained_knowledge_actor(?)", Boolean.class, childPrincipal));
    assertFalse(
        jdbc.queryForObject(
            "SELECT contained_knowledge_actor(?)", Boolean.class, UUID.randomUUID().toString()));
  }

  @Test
  void aChildsDependencyClosureLeavingItsSubtreeRefuses() {
    var containerDocument = retained(parentScope);
    var childDocument = retained(childScope);
    input(childDocument, containerDocument);

    assertTrue(revisionReadable(childPrincipal, childDocument));
    assertFalse(revisionReadable(childPrincipal, containerDocument));
    assertFalse(dependenciesReadable(childPrincipal, childDocument));
  }

  @Test
  void theDiscoveryPathAdmitsTheChildsOwnScopeAndNotGeneralMembership() {
    var rows =
        jdbc.queryForList(
            "SELECT scope_id, general_member FROM knowledge_read_authority(?)", childPrincipal);

    assertFalse(rows.isEmpty());
    assertTrue(
        rows.stream().anyMatch(row -> childScope.equals(row.get("scope_id"))),
        "the child's own scope is readable");
    assertTrue(
        rows.stream().allMatch(row -> Boolean.FALSE.equals(row.get("general_member"))),
        "general_member is false on every row");
    assertFalse(
        rows.stream().anyMatch(row -> parentScope.equals(row.get("scope_id"))),
        "never the container's scope");
  }
}
