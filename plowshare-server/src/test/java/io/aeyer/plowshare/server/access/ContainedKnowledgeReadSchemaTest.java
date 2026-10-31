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

  /** A contained scope over a fresh contained project; ancestry is the column, not the name. */
  private UUID contained(UUID container) {
    var project = project("contained:" + UUID.randomUUID());
    var scope = UUID.randomUUID();
    assertEquals(
        1,
        jdbc.update(
            "INSERT INTO authority_scopes(id,kind,project_id,container) VALUES (?,'CONTAINED',?,?)",
            scope,
            project,
            container));
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
