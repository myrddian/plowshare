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
 * Real PostgreSQL, because the assertions are the behaviour of one SQL gate function, {@code
 * information_navigation_reference_visible}, and of nothing else; a mocked repository would assert
 * the mock. That function is the only admission arm at {@code
 * JdbcInformationNavigationRepository.referenceVisible} and at the {@code reference_visible} column
 * of the neighbour page, so what it refuses is simply not offered as a reference stub.
 *
 * <p>CHARACTERIZATION (run before any widening): 5 tests, 3 failed, and every failure was a
 * positive assertion about a contained actor. {@code information_navigation_reference_visible}
 * (V172:85, over V158:172) opens with {@code information_global_read_member($2)} as its ONLY
 * admission arm, and a contained principal is a bare UUID with no {@code admins} row for that
 * membership to be true of (V218), so it was refused the stub of a document in its own home —
 * {@code aContainedActorSeesAReferenceInsideItsOwnSpace} failed on its one assertion, {@code
 * aContainedActorDoesNotSeeASiblingsReference} failed on its sibling-principal control after every
 * refusal in it had already passed, and {@code aRevokedPrincipalLosesTheArm} failed on the live
 * principal it checks before revoking. That is the gap V231 closes, with one added disjunct.
 *
 * <p>Two tests PASSED BEFORE the migration, and that is the point of recording them. {@code
 * anAncestorStillSeesAReferenceIntoItsDescendant} passed, so it is a regression test for the shape
 * of the change and not a new assertion of unknown value: the pre-existing arm already carried an
 * ancestor's visibility into its descendant, and a contained-actor condition added as a CONJUNCT
 * here would have taken it away from every existing caller, because there is no second arm to fall
 * back on. {@code anUnrelatedAccountSeesNeither} passed too, including its control that an enabled
 * ordinary account sees both stubs: that coarse fence is pre-existing and V231 neither narrows nor
 * widens it.
 */
@Tag("full-db")
@Testcontainers
class ContainedNavigationReferenceSchemaTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withUrlParam("sslmode", "disable")
          .withUrlParam("gssEncMode", "disable");

  private static final String HOLDER = "alice";
  private static final String STRANGER = "stranger";
  private static final String DISABLED = "disabled";
  private static final String ADMINISTRATOR = "admin";

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
    var schema = "contained_reference_" + UUID.randomUUID().toString().replace("-", "");
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
    disabledUser(DISABLED);
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

  /**
   * A disabled account. {@code information_global_read_member} opens with {@code account_active},
   * so this is the one ordinary account the pre-existing arm refuses; see {@link
   * #anUnrelatedAccountSeesNeither} for why that matters to this test's "unrelated".
   */
  private void disabledUser(String handle) {
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password,enabled)"
            + " VALUES (?,'fixture',FALSE,FALSE)",
        handle);
  }

  private long project(String name) {
    jdbc.update("INSERT INTO projects(name) VALUES (?)", name);
    return jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, name);
  }

  /**
   * A fresh ordinary project outside the containment subtree, registered as a home so {@link
   * #reference} can place a document in it. Its PROJECT scope is the one the project trigger mints.
   */
  private UUID unrelatedProject(String name) {
    var project = project(name);
    var scope =
        jdbc.queryForObject(
            "SELECT id FROM authority_scopes WHERE project_id=?", UUID.class, project);
    homes.put(scope, new Home(project, name));
    return scope;
  }

  /** The contained project behind a contained scope, kept so a selection can name it. */
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
   * A retained document whose home is {@code scope} and whose reference stub is not hidden, so that
   * the gate's own {@code referenceHidden} clause is not what decides these assertions. The home is
   * resolved by the shipped mapping from the project and the {@code project:}-prefixed namespace,
   * and the HOME policy row is the one the insert trigger writes.
   *
   * <p>The owner is deliberately the stranger, so the refusals below also establish that ownership
   * is not a route into a contained home. Returns the RESOURCE, which is what the gate takes.
   */
  private UUID reference(UUID scope) {
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
    unhide(resource);
    return resource;
  }

  /**
   * The platform default is {@code referenceHidden: true}, which hides every stub from everyone. A
   * document-level override turns it off for this one resource; {@code allowOverrides} is true at
   * both the platform and project level by default, so the document row is the selected one.
   */
  private void unhide(UUID resource) {
    jdbc.update(
        "INSERT INTO information_navigation_policies(policy_key,version,settings,actor)"
            + " VALUES (?,1,?::jsonb,?)",
        "document:" + resource,
        "{\"defaults\":{\"ownerOnly\":false,\"referenceHidden\":false,\"propagate\":true},"
            + "\"mandatory\":{\"ownerOnly\":false,\"referenceHidden\":false,\"propagate\":false},"
            + "\"allowOverrides\":true,\"referenceLabel\":\"Restricted document\"}",
        ADMINISTRATOR);
    assertFalse(
        jdbc.queryForObject(
            "SELECT (information_navigation_policy(?)->>'referenceHidden')::boolean",
            Boolean.class,
            resource),
        "fixture: the stub is not hidden, so the gate's own hidden clause decides nothing here");
  }

  private String home(UUID scope) {
    return homes.get(scope).name();
  }

  /** The gate itself, asked as both call sites ask it: a resource, an actor, a selected project. */
  private boolean visible(String actor, UUID resource, String project) {
    return jdbc.queryForObject(
        "SELECT coalesce(information_navigation_reference_visible(?,?,?),FALSE)",
        Boolean.class,
        resource,
        actor,
        project);
  }

  @Test
  void aContainedActorSeesAReferenceInsideItsOwnSpace() {
    var own = reference(childScope);

    assertTrue(visible(childPrincipal, own, home(childScope)));
  }

  @Test
  void aContainedActorDoesNotSeeASiblingsReference() {
    var sibling = reference(siblingScope);
    var container = reference(parentScope);
    var descendant = reference(grandchildScope);
    var outside = reference(unrelatedProject("outside"));

    assertFalse(visible(childPrincipal, sibling, home(childScope)), "a sibling's home");
    assertFalse(visible(childPrincipal, container, home(childScope)), "its container's home");
    assertFalse(visible(childPrincipal, descendant, home(childScope)), "its own descendant's home");
    assertFalse(visible(childPrincipal, outside, home(childScope)), "an uncontained home");
    // Naming another home in the selection is not a second route into it either.
    assertFalse(visible(childPrincipal, sibling, home(siblingScope)));
    assertFalse(visible(childPrincipal, container, "parent"));
    // Control: the sibling's own principal does see the sibling's stub, so the refusals above are
    // the actor axis and not a fixture that hides the stub from everybody.
    assertTrue(visible(siblingPrincipal, sibling, home(siblingScope)));
    assertTrue(visible(grandchildPrincipal, descendant, home(grandchildScope)));
  }

  /**
   * Review Focus 3. An ancestor's reference visibility into its descendant already worked, through
   * the pre-existing {@code information_global_read_member} arm; the characterization recorded it
   * passing BEFORE the migration, which is what makes this a regression test for the disjunct
   * rather than a new assertion. A conjunct keyed on the contained actor would break exactly this.
   */
  @Test
  void anAncestorStillSeesAReferenceIntoItsDescendant() {
    var child = reference(childScope);
    var grandchild = reference(grandchildScope);

    assertTrue(visible(HOLDER, child, "parent"), "the container's member sees into its child");
    assertTrue(visible(HOLDER, grandchild, "parent"), "and into the generation below it");
  }

  /**
   * "Unrelated" here has to mean an actor the gate has no arm for at all: a disabled account, or a
   * bare UUID that is no live principal. An ENABLED ordinary account is not unrelated to this gate
   * — {@code information_global_read_member} is true of every active non-bootstrap account, so the
   * pre-existing arm already admits one to any non-RESTRICTED stub, with no scope test. That is the
   * unextended fence this gate carries, it is asserted below as a control, and this migration
   * neither narrows nor widens it.
   */
  @Test
  void anUnrelatedAccountSeesNeither() {
    var child = reference(childScope);
    var container = reference(parentScope);
    var foreign = UUID.randomUUID().toString();

    for (var actor : List.of(DISABLED, foreign)) {
      assertFalse(visible(actor, child, home(childScope)), actor);
      assertFalse(visible(actor, container, "parent"), actor);
    }
    assertTrue(visible(STRANGER, child, "parent"), "control: the pre-existing account arm");
    assertTrue(visible(STRANGER, container, "parent"), "control: the pre-existing account arm");
  }

  @Test
  void aRevokedPrincipalLosesTheArm() {
    var own = reference(childScope);
    assertTrue(visible(childPrincipal, own, home(childScope)));

    assertEquals(
        1, jdbc.update("DELETE FROM contained_principals WHERE id=?::uuid", childPrincipal));

    assertFalse(visible(childPrincipal, own, home(childScope)));
  }
}
