package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * Real PostgreSQL, because the assertions are the behaviour of the write gates a contained
 * principal meets: {@code information_lock_destination} (where a new document may land) and {@code
 * information_lock_knowledge} (whether an existing one may be written or have its audience set).
 *
 * <p>CHARACTERIZATION (run before any widening): the first assertion failed, so the write side did
 * need widening. Two gates refused a contained principal, in this order. First {@code
 * information_lock_destination} (V163, over V157:182) found no PROJECT/PERSONAL scope and required
 * an {@code admins} row, so a child could not even land a new document. Behind it {@code
 * information_home_allows} (V163, over V157:124) required {@code account_active} and a project
 * role, so a child could not write an existing one. Each was widened in turn by one arm in V224's
 * write section, keyed on {@code contained_knowledge_actor} and the principal's own scope. {@code
 * information_set_root_audience} is deliberately not widened: SET_AUDIENCE stays refused, which is
 * what keeps a child from publishing generally readable.
 *
 * <p>Not covered here: memory writes ({@code memories} has no actor-keyed SQL gate; the trigger
 * only maps project to home), which belong to the service layer.
 */
@Tag("full-db")
@Testcontainers
class ContainedActorWriteSchemaTest {
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
    var schema = "contained_write_" + UUID.randomUUID().toString().replace("-", "");
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
    serviceToken();
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

  /**
   * A retained document whose home is {@code scope}. The home is resolved by the shipped mapping
   * from the project and the {@code project:}-prefixed namespace, so nothing here asserts a home
   * the schema would not have chosen; the initial policy row the insert trigger writes is HOME.
   *
   * <p>The owner is deliberately the stranger, so that the stranger's refusals below also establish
   * that ownership is not a route into a contained home.
   */
  private UUID retained(UUID scope) {
    return retainedBy(scope, STRANGER);
  }

  /**
   * As {@link #retained}, but owned by {@code owner}; a contained author has no handle, so null.
   */
  private UUID retainedBy(UUID scope, String owner) {
    var home = homes.get(scope);
    var resource = UUID.randomUUID();
    var revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,owner_handle,project_id,kind,tags)"
            + " VALUES (?,?,?,?,?,'source','[]')",
        resource,
        "project:" + home.name(),
        resource + ".txt",
        owner,
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

  private String home(UUID scope) {
    return homes.get(scope).name();
  }

  private boolean mayLand(String actor, UUID scope) {
    return jdbc.queryForObject(
        "SELECT information_lock_destination(?,'project',?,FALSE)",
        Boolean.class,
        actor,
        home(scope));
  }

  private boolean mayWrite(String actor, UUID scope, UUID revision) {
    return jdbc.queryForObject(
        "SELECT information_lock_knowledge(?,?,'project',?,FALSE,'WRITE')",
        Boolean.class,
        revision,
        actor,
        home(scope));
  }

  @Test
  void aChildWritesADocumentInItsOwnHome() {
    var document = retainedBy(childScope, null);

    assertTrue(mayLand(childPrincipal, childScope), "a new document may land in its own home");
    assertTrue(mayWrite(childPrincipal, childScope, document), "and it may write what is there");
  }

  @Test
  void aChildCannotWriteInItsContainersHome() {
    var document = retained(parentScope);

    // Control: the container's own contributor really can, so the refusals are not an empty
    // fixture.
    assertTrue(mayLand(HOLDER, parentScope));
    assertTrue(mayWrite(HOLDER, parentScope, document));
    assertFalse(mayLand(childPrincipal, parentScope));
    assertFalse(mayWrite(childPrincipal, parentScope, document));
  }

  @Test
  void aChildCannotWriteInASiblingsHome() {
    var document = retainedBy(siblingScope, null);

    assertTrue(
        mayWrite(siblingPrincipal, siblingScope, document), "control: the sibling's own home");
    assertFalse(mayLand(childPrincipal, siblingScope));
    assertFalse(mayWrite(childPrincipal, siblingScope, document));
  }

  @Test
  void aChildCannotPublishGenerallyReadable() {
    var document = retainedBy(childScope, null);
    // Control: a MANAGER of the container publishes a document of its own home through the same
    // function, so the child's refusal is the function's authority check and not a broken call.
    user("manager", false);
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES (?,'manager','MANAGER')",
        parentProject);
    var parentDocument = retainedBy(parentScope, "manager");
    assertNotNull(
        jdbc.queryForObject(
            "SELECT information_set_root_audience(?,'manager','project','parent',FALSE,TRUE)",
            Long.class,
            parentDocument));

    var refusal =
        assertThrows(
            org.springframework.dao.DataAccessException.class,
            () ->
                jdbc.queryForObject(
                    "SELECT information_set_root_audience(?,?,'project',?,FALSE,TRUE)",
                    Long.class,
                    document,
                    childPrincipal,
                    home(childScope)));
    var cause = refusal.getMostSpecificCause().getMessage();
    assertTrue(cause.contains("scoped audience control required"), cause);
    assertEquals(
        "HOME",
        jdbc.queryForObject(
            "SELECT audience FROM information_resource_policies WHERE resource_id=?",
            String.class,
            resourceOf(document)));
  }

  @Test
  void aChildNamingItsOwnProjectCannotWriteAnotherHomesDocument() {
    var sibling = retainedBy(siblingScope, null);
    var container = retained(parentScope);

    assertFalse(mayWrite(childPrincipal, childScope, sibling));
    assertFalse(mayWrite(childPrincipal, childScope, container));
  }

  @Test
  void aGrandchildCannotWriteItsAncestorsHome() {
    var own = retainedBy(grandchildScope, null);
    var ancestor = retainedBy(childScope, null);

    assertTrue(mayWrite(grandchildPrincipal, grandchildScope, own), "control: its own home");
    assertFalse(mayLand(grandchildPrincipal, childScope));
    assertFalse(mayWrite(grandchildPrincipal, childScope, ancestor));
    assertFalse(mayLand(childPrincipal, grandchildScope));
  }

  @Test
  void aRevokedPrincipalCannotWrite() {
    var document = retainedBy(childScope, null);
    assertTrue(mayWrite(childPrincipal, childScope, document));

    jdbc.update("DELETE FROM contained_principals WHERE id=?::uuid", childPrincipal);

    assertFalse(mayLand(childPrincipal, childScope));
    assertFalse(mayWrite(childPrincipal, childScope, document));
  }

  @Test
  void aChildCannotSetAnAudienceEvenOnItsOwnDocument() {
    var document = retainedBy(childScope, null);

    assertFalse(
        jdbc.queryForObject(
            "SELECT information_lock_knowledge(?,?,'project',?,FALSE,'SET_AUDIENCE')",
            Boolean.class,
            document,
            childPrincipal,
            home(childScope)));
  }

  @Test
  void anOrdinaryWriterIsUnaffectedByTheNewArm() {
    assertTrue(mayLand(HOLDER, parentScope));
    assertFalse(mayLand(HOLDER, childScope), "an ordinary member has no role in a contained home");
    assertFalse(mayLand(STRANGER, parentScope));
    assertFalse(mayLand(TOKEN_PRINCIPAL, childScope));
  }

  private UUID resourceOf(UUID revision) {
    return jdbc.queryForObject(
        "SELECT resource_id FROM information_revisions WHERE id=?", UUID.class, revision);
  }
}
