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
 * Real PostgreSQL, because every assertion here <em>is</em> a predicate body's behaviour: {@code
 * knowledge_scope_readable}, the readable-scope set {@code knowledge_read_authority} materializes,
 * and the recursive containment walk both consult. A mocked repository cannot evaluate a SQL
 * function, and the function is the fence.
 *
 * <p>Containment admission is one-way by construction. Every success below reads <em>downward</em>
 * — a scope's own principal reading its own home, or an ancestor role holder reading a descendant's
 * — and the refusals are what prove the direction: a descendant never reads an ancestor, a sibling
 * never reads a sibling, and no route arrives from outside the subtree.
 */
@Tag("full-db")
@Testcontainers
class ContainedKnowledgeReadSchemaTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withUrlParam("sslmode", "disable")
          .withUrlParam("gssEncMode", "disable");

  /**
   * The container's role holder: a human account with a live CONTRIBUTOR role on {@code parent}.
   */
  private static final String HOLDER = "alice";

  /** A global-read member with no role anywhere in the containment subtree. */
  private static final String STRANGER = "stranger";

  private static final String ADMINISTRATOR = "admin";
  private static final String TOKEN_PRINCIPAL = "@service/probe";

  private JdbcTemplate jdbc;
  private long parentProject;

  /** The container's own ordinary PROJECT scope: the root of the containment chain. */
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
    var schema = "contained_reads_" + UUID.randomUUID().toString().replace("-", "");
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
    // A membership row is what mints the container role holder's live SELF grant; the trigger that
    // mints it is the same one V208 taught to resolve nothing for a contained scope.
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES (?,?,'CONTRIBUTOR')",
        parentProject,
        HOLDER);
    serviceToken();

    // The container's own project is a home too: the ceiling tests below place documents in it and
    // ask what a descendant's ceiling admits of them.
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

  /**
   * The ceiling as the live discovery path decides it. {@code knowledge_readable_candidates} owns
   * an independent {@code in_boundary} body, so a widening applied only to the exact-target
   * predicate above would leave discovery refusing what an exact read admits.
   */
  private boolean selectedUnderCeiling(
      String actor, UUID revision, String selectedProject, String home) {
    return jdbc.queryForList(
            "SELECT revision FROM knowledge_readable_selection("
                + "?,'project',?,FALSE,?,FALSE,NULL,NULL,FALSE,NULL,NULL,NULL)",
            UUID.class,
            actor,
            selectedProject,
            home)
        .contains(revision);
  }

  /** The boundary home a request names when it selects a contained scope's own project. */
  private String home(UUID scope) {
    return homes.get(scope).name();
  }

  private boolean projectReadable(String actor, UUID scope) {
    return jdbc.queryForObject(
        "SELECT coalesce(information_project_readable(?,?),FALSE)",
        Boolean.class,
        homes.get(scope).project(),
        actor);
  }

  /** Whether {@code scope} appears in the readable-scope set the batched relation materializes. */
  private boolean inReadAuthority(String actor, UUID scope) {
    return jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM knowledge_read_authority(?) WHERE scope_id=?)",
        Boolean.class,
        actor,
        scope);
  }

  @Test
  void aChildReadsItsOwnHome() {
    assertTrue(readable(childPrincipal, childScope));
    assertTrue(readable(grandchildPrincipal, grandchildScope));
  }

  @Test
  void aContainerReadsItsChildsHome() {
    assertTrue(readable(HOLDER, childScope));
    assertTrue(readable(HOLDER, siblingScope));
  }

  @Test
  void aGrandparentReadsThroughTheChain() {
    assertTrue(readable(HOLDER, grandchildScope));
  }

  /**
   * The directional refusal. A contained scope confers no role, so the upward case is covered twice
   * over: a child does not read the ordinary project scope that contains it, and a grandchild does
   * not read the contained scope that contains it either.
   */
  @Test
  void aChildCannotReadItsContainersHome() {
    assertFalse(readable(childPrincipal, parentScope));
    assertFalse(readable(grandchildPrincipal, childScope));
    assertFalse(readable(grandchildPrincipal, parentScope));
  }

  @Test
  void aSiblingCannotReadASiblingsHome() {
    assertFalse(readable(siblingPrincipal, childScope));
    assertFalse(readable(childPrincipal, siblingScope));
    // A nephew is not a sibling but is reached the same way: neither is an ancestor of the other.
    assertFalse(readable(siblingPrincipal, grandchildScope));
  }

  @Test
  void anUnrelatedAccountReadsNoContainedHome() {
    // The stranger is a live global-read member, so what refuses the read is the absence of a role
    // on an ancestor and nothing weaker.
    assertTrue(jdbc.queryForObject("SELECT account_active(?)", Boolean.class, STRANGER));
    assertFalse(readable(STRANGER, childScope));
    assertFalse(readable(STRANGER, grandchildScope));

    // The self arm matches a bare UUID and sits outside every account-based check, and
    // admins.handle is unconstrained TEXT, so an account named after a contained principal would
    // otherwise inherit that principal's read with no account_active or enabled test applied. The
    // account is created here rather than in the fixture precisely because it suppresses the
    // principal's own read, which is the refusal being asserted.
    user(childPrincipal, false);
    assertFalse(readable(childPrincipal, childScope));
    assertFalse(inReadAuthority(childPrincipal, childScope));
  }

  @Test
  void aServerAdministratorReadsNoContainedHome() {
    assertTrue(
        jdbc.queryForObject(
            "SELECT server_admin FROM admins WHERE handle=?", Boolean.class, ADMINISTRATOR));
    assertFalse(readable(ADMINISTRATOR, childScope));
    assertFalse(readable(ADMINISTRATOR, grandchildScope));
  }

  /**
   * A service role is not a containment route. The token principal genuinely reads the container's
   * own project scope through {@code service_project_role}, which is exactly the coalesce the new
   * arm declines to repeat, so the refusal below is the arm's and not a dead fixture's.
   */
  @Test
  void aServiceAccountReadsNoContainedHome() {
    assertEquals(
        "MANAGER",
        jdbc.queryForObject(
            "SELECT service_project_role('parent',?)", String.class, TOKEN_PRINCIPAL));
    assertTrue(readable(TOKEN_PRINCIPAL, parentScope));
    assertFalse(readable(TOKEN_PRINCIPAL, childScope));
    assertFalse(readable(TOKEN_PRINCIPAL, grandchildScope));
  }

  /**
   * The walk must fail closed on a chain it cannot complete. A dangling container is unreachable
   * through application SQL — V208's foreign key and its shape trigger each refuse it — so both are
   * taken out of the way to place the row. The question is what the predicate answers about data
   * that should be impossible, not whether it is constructible; this schema is this test's own, so
   * neither fence is restored.
   */
  @Test
  void aBrokenContainerChainRefusesRatherThanAdmits() {
    jdbc.execute("ALTER TABLE authority_scopes DROP CONSTRAINT authority_scope_container_fkey");
    jdbc.execute("ALTER TABLE authority_scopes DISABLE TRIGGER authority_scope_shape");
    var missing = UUID.randomUUID();
    var orphanProject = project("contained:" + UUID.randomUUID());
    var orphan = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO authority_scopes(id,kind,project_id,container) VALUES (?,'CONTAINED',?,?)",
        orphan,
        orphanProject,
        missing);
    var below = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO authority_scopes(id,kind,project_id,container) VALUES (?,'CONTAINED',?,?)",
        below,
        project("contained:" + UUID.randomUUID()),
        orphan);
    // Nothing above the break is an ancestor of anything below it, and the holder's authority
    // lives above the break.
    assertFalse(readable(HOLDER, orphan));
    assertFalse(readable(HOLDER, below));
    assertFalse(inReadAuthority(HOLDER, orphan));
    // The scope's own principal is unaffected: its admission never walks the chain at all.
    assertTrue(readable(principal(orphan), orphan));
  }

  /**
   * The 64-level sentinel is a structural net over data that should be impossible, not the
   * configured depth bound — that is {@code ContainmentBounds.maxDepth()}, policy enforced in Java
   * on create and deliberately absent from SQL. Asserting both sides of the boundary is what keeps
   * the sentinel from drifting unnoticed.
   */
  @Test
  void aChainBeyondTheWalkSentinelRefuses() {
    var chain = new java.util.ArrayList<UUID>();
    var next = parentScope;
    for (int depth = 1; depth <= 65; depth++) {
      next = contained(next);
      chain.add(next);
    }
    assertTrue(readable(HOLDER, chain.get(63)), "distance 64 is within the walk");
    assertFalse(readable(HOLDER, chain.get(64)), "distance 65 is beyond it and fails closed");
    // The deepest scope still reads its own home: the sentinel bounds the walk, not the self arm.
    assertTrue(readable(principal(chain.get(64)), chain.get(64)));
  }

  /**
   * The blast radius, at the level a caller actually reads documents. Widening scope admission is
   * not confined to the two predicates it edits: {@code knowledge_revision_readable}, {@code
   * information_project_readable} and the retained ceiling in {@code
   * knowledge_document_in_boundary} all resolve through {@code knowledge_scope_readable}, so a
   * descendant's document becomes readable to an ancestor <em>within the descendant's own
   * boundary</em> here rather than in a later task. The refusals are the assertions that matter:
   * one success and three different principals who still get nothing is what makes the widening
   * directional rather than general.
   *
   * <p>The cross-boundary case — an ancestor reading this document while addressing its
   * <em>own</em> project as the boundary home — is asserted last. The ceiling's term was once an
   * equality on the selected project's name and refused it; it now tests containment beside that
   * equality, so the same pair is admitted. {@link #aContainersCeilingAdmitsAChildsResource()} owns
   * that capability and its direction.
   */
  @Test
  void anAncestorReadsADescendantsDocumentWithinThatDescendantsBoundary() {
    var document = retained(childScope);
    var home = homes.get(childScope).name();

    assertTrue(revisionReadable(HOLDER, document));
    assertTrue(inBoundary(HOLDER, document, home));
    assertTrue(projectReadable(HOLDER, childScope));
    // The other half of the limitation is only half lifted, and this records exactly where it
    // stops. The child's own scope is admitted -- aChildReadsItsOwnHome proves that -- but
    // knowledge_revision_readable opens with information_global_read_member, which is false for a
    // bare-UUID principal that has no admins row to be a member through. Scope admission cannot
    // reach that gate, and lifting a baseline gate on every knowledge read is not this task's to
    // make. The ceiling is demonstrably not what refuses: it admits the same document for the same
    // principal, which is what a ceiling that grants nothing should do.
    assertFalse(revisionReadable(childPrincipal, document));
    assertTrue(inBoundary(childPrincipal, document, home));

    // An unrelated account, which also happens to own the resource.
    assertFalse(revisionReadable(STRANGER, document));
    assertFalse(inBoundary(STRANGER, document, home));
    assertFalse(projectReadable(STRANGER, childScope));
    // A sibling subtree.
    assertFalse(revisionReadable(siblingPrincipal, document));
    assertFalse(inBoundary(siblingPrincipal, document, home));
    // A descendant reading upward.
    assertFalse(revisionReadable(grandchildPrincipal, document));
    assertFalse(inBoundary(grandchildPrincipal, document, home));
    // A server administrator and a service credential on the container.
    assertFalse(revisionReadable(ADMINISTRATOR, document));
    assertFalse(revisionReadable(TOKEN_PRINCIPAL, document));

    // Cross-boundary, which this migration now admits as well: the ancestor addresses its OWN
    // project as the boundary home and still reaches the descendant's document, because the ceiling
    // term tests containment beside the home equality. This assertion was a refusal when scope
    // admission was all that had been widened; aContainersCeilingAdmitsAChildsResource is where
    // that capability is covered in its own right, and the refusals around it -- the sibling, the
    // descendant reading upward, the stranger -- are unchanged by it.
    assertTrue(revisionReadable(HOLDER, document));
    assertTrue(inBoundary(HOLDER, document, "parent"));
  }

  /**
   * The capability this task adds: a container executing under its <em>own</em> project as the
   * retained ceiling reaches a resource whose home is a descendant. Equality on the home project's
   * name refused that before, however readable the descendant's scope already was.
   *
   * <p>Both chokepoints are asserted, because the exact-target predicate and {@code
   * knowledge_readable_candidates} decide the ceiling in independent bodies.
   */
  @Test
  void aContainersCeilingAdmitsAChildsResource() {
    var childDocument = retained(childScope);
    var grandchildDocument = retained(grandchildScope);

    assertTrue(inBoundary(HOLDER, childDocument, "parent"), "a child's resource, one link down");
    assertTrue(
        inBoundary(HOLDER, grandchildDocument, "parent"),
        "a grandchild's resource, reached through the chain");
    // An intermediate container is an ancestor like any other: the chain is walked, not the depth.
    assertTrue(inBoundary(HOLDER, grandchildDocument, home(childScope)));
    assertTrue(
        selectedUnderCeiling(HOLDER, childDocument, home(childScope), "parent"),
        "discovery decides in_boundary in its own body and must agree");
  }

  /**
   * The same pair inverted, which is the assertion that makes the widening directional. A
   * descendant cannot reach its container's resource, and the role holder cannot either while
   * executing under the descendant's home — so what refuses is the ceiling and not scope admission,
   * which the holder demonstrably has on both ends.
   */
  @Test
  void aChildsCeilingDoesNotAdmitItsContainersResource() {
    var containerDocument = retained(parentScope);

    assertTrue(readable(HOLDER, parentScope), "the holder is admitted to the container's scope");
    assertFalse(inBoundary(HOLDER, containerDocument, home(childScope)));
    assertFalse(inBoundary(childPrincipal, containerDocument, home(childScope)));
    // One more link up, through a contained intermediate rather than the PROJECT root.
    assertFalse(inBoundary(HOLDER, retained(childScope), home(grandchildScope)));
    assertTrue(
        selectedUnderCeiling(HOLDER, containerDocument, "parent", "parent"),
        "the same row is selected under the container's own ceiling");
    assertFalse(
        selectedUnderCeiling(HOLDER, containerDocument, "parent", home(childScope)),
        "discovery refuses the upward direction too");
  }

  @Test
  void aChildsCeilingAdmitsItsOwnResource() {
    var own = retained(childScope);
    assertTrue(inBoundary(childPrincipal, own, home(childScope)));
    assertTrue(inBoundary(HOLDER, own, home(childScope)));
  }

  /**
   * Sideways is not downward. Neither subtree is on the other's container chain, so a ceiling
   * naming one admits nothing of the other — asserted in both directions, and once with the
   * container's role holder as the actor so that the refusal cannot be a missing admission.
   */
  @Test
  void aCeilingDoesNotAdmitASiblingsResource() {
    var siblingDocument = retained(siblingScope);
    var childDocument = retained(childScope);

    assertFalse(inBoundary(childPrincipal, siblingDocument, home(childScope)));
    assertFalse(inBoundary(siblingPrincipal, childDocument, home(siblingScope)));
    assertTrue(readable(HOLDER, childScope), "the holder is admitted to both subtrees");
    assertTrue(readable(HOLDER, siblingScope));
    assertFalse(inBoundary(HOLDER, childDocument, home(siblingScope)));
    assertFalse(inBoundary(HOLDER, siblingDocument, home(grandchildScope)));
  }

  /**
   * The ceiling is {@code bool_and} over the whole consumed closure, so one input outside it
   * refuses the derived revision entirely. Widening the ceiling downward moves which inputs are
   * inside it; it does not weaken the conjunction, which is what the third pair asserts: under the
   * container's own ceiling the child input is now inside and the closure is admitted, while a
   * closure reaching a project outside the subtree altogether is still refused.
   */
  @Test
  void aClosureLeavingTheSubtreeRefusesTheWholeRead() {
    var inside = retained(childScope);
    var container = retained(parentScope);
    var outside = retained(unrelatedProject("elsewhere"));
    var fromInside = retained(childScope);
    var fromContainer = retained(childScope);
    var fromOutside = retained(childScope);
    input(fromInside, inside);
    input(fromContainer, container);
    input(fromOutside, outside);

    assertTrue(inBoundary(HOLDER, fromInside, home(childScope)));
    assertFalse(
        inBoundary(HOLDER, fromContainer, home(childScope)),
        "an input above the child's home leaves the child's ceiling");
    assertTrue(
        inBoundary(HOLDER, fromContainer, "parent"),
        "the same closure is wholly inside the container's ceiling");
    assertFalse(
        inBoundary(HOLDER, fromOutside, "parent"),
        "a closure reaching outside the subtree refuses the whole read");
    assertFalse(inBoundary(HOLDER, fromOutside, home(childScope)));
  }

  /**
   * The global arm stays shut for a ceiling that does not carry general read, which is the shape
   * {@code contain} always produces for a child. The published row is outside the subtree and the
   * child never named it; the only route to it is the general arm, and the flag closes that.
   *
   * <p>The last assertion is the positive control: with the flag on, the same row under the same
   * ceiling is admitted for an account that is a general-read member, so the refusals above are the
   * flag's work and not a row that was never generally readable to begin with.
   */
  @Test
  void aChildReceivesNoGenerallyReadableRowItNeverNamed() {
    var published = retained(unrelatedProject("published"));
    publish(published);

    assertTrue(generallyReadable(published), "the row really is generally readable");
    assertFalse(inBoundary(childPrincipal, published, home(childScope)));
    assertFalse(inBoundary(HOLDER, published, home(childScope)));
    assertFalse(inBoundary(HOLDER, published, "parent"));
    assertTrue(inBoundaryWithGeneralRead(HOLDER, published, home(childScope)));
  }

  /**
   * The exact-target predicate and the batched readable-scope relation are independent bodies that
   * must answer the same question: widening only one leaves either discovery blind or exact reads
   * refused. Every pair the tests above assert is replayed through both.
   */
  @Test
  void theReadAuthorityRelationAgreesWithTheExactPredicate() {
    record Pair(String actor, UUID scope) {}
    var pairs =
        List.of(
            new Pair(childPrincipal, childScope),
            new Pair(grandchildPrincipal, grandchildScope),
            new Pair(HOLDER, childScope),
            new Pair(HOLDER, grandchildScope),
            new Pair(HOLDER, siblingScope),
            new Pair(HOLDER, parentScope),
            new Pair(childPrincipal, parentScope),
            new Pair(grandchildPrincipal, childScope),
            new Pair(siblingPrincipal, childScope),
            new Pair(childPrincipal, siblingScope),
            new Pair(STRANGER, childScope),
            new Pair(ADMINISTRATOR, childScope),
            new Pair(TOKEN_PRINCIPAL, parentScope),
            new Pair(TOKEN_PRINCIPAL, childScope));
    for (var pair : pairs) {
      assertEquals(
          readable(pair.actor(), pair.scope()),
          inReadAuthority(pair.actor(), pair.scope()),
          pair.actor() + " / " + pair.scope());
    }
  }
}
