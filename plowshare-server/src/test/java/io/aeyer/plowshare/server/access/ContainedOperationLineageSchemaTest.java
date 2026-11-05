package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.archive.JdbcProjectMembers;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.information.AccountKnowledgePermissions;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * A containment hop's durable lineage, against the real trigger.
 *
 * <h2>Why this class exists at all, which is the finding rather than the feature</h2>
 *
 * <p>{@code contain} has never persisted a hop. {@code ContainmentHopTest} — the suite that proves
 * the hop's authority rules — mocks {@code OperationRecords}, so {@code retainChild} was asserted
 * and never executed, and two shipped slices described a live contained admission that no database
 * had ever accepted. The first time a hop reached real PostgreSQL, {@code operation_parent_valid}
 * (V162:35-36) refused it: that trigger requires a child admission to carry {@code c.scope=p.scope
 * AND c.project_name=p.project_name}, and a containment hop changes both <em>by design</em>.
 *
 * <p>So the regression test is <strong>the un-mocked path</strong>, not a tightened assertion
 * against the mock. Every test below drives a real {@link JdbcOperationRecords}.
 *
 * <h2>What V230 changed, and what it must not</h2>
 *
 * <p>V230 is the SQL counterpart of a replacement ADR 0042 already made in Java: gate two already
 * replaced {@code require}'s demand for an ancestor with an identical scope <em>and</em> home by
 * the containment edge itself — the child's own stored row must name this ancestor's scope as its
 * container. The trigger is the same demand in SQL and had never been given the same treatment,
 * because no hop was ever persisted to fail it.
 *
 * <p>The constraint being changed is the substrate's <strong>no-escalation invariant</strong>, so
 * what matters here is the set of refusals, not the admission. Each has a positive control proving
 * the legitimate hop is admitted, so none of them can pass for want of a fixture:
 *
 * <ul>
 *   <li>a contained child whose scope's {@code container} is not the parent admission's scope — a
 *       grandparent, a sibling subtree, an unrelated scope
 *   <li>a child whose actions are not a subset of the parent's
 *   <li>a <strong>non</strong>-contained child, which keeps the exact scope/project equality,
 *       asserted byte-for-byte
 *   <li>a chain beyond the configured depth
 *   <li>a contained admission with no parent edge at all
 * </ul>
 *
 * <p>Two layers refuse most of these and both are exercised deliberately: {@code contain} refuses
 * in Java before any row is written, and the trigger refuses a row inserted directly. The second is
 * the one that matters for an invariant — a Java check is a check, a trigger is the fence — so the
 * direct inserts below are not redundant with the {@code contain} calls above them.
 */
@Tag("full-db")
@Testcontainers
class ContainedOperationLineageSchemaTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withUrlParam("sslmode", "disable")
          .withUrlParam("gssEncMode", "disable");

  private static final String HOLDER = "alice";

  /** The four actions a hop may carry, as {@code SourceContainedApplications} narrows to them. */
  private static final Set<AuthorityAction> DERIVABLE =
      Set.of(
          AuthorityAction.READ,
          AuthorityAction.WRITE,
          AuthorityAction.CONVERSATION_CREATE,
          AuthorityAction.RUN_MODEL);

  private JdbcTemplate jdbc;
  private JdbcIdentityRepository identities;
  private JdbcScopedGrantRepository grants;
  private LiveScopedAuthority authority;
  private JdbcProjectMembers members;
  private JdbcOperationRecords records;

  private ScopeId parentScope;
  private long parentProject;
  private ScopeId childScope;
  private String childProject;
  private ScopeId siblingScope;
  private String siblingProject;
  private ScopeId grandchildScope;
  private String grandchildProject;
  private ScopeId unrelatedScope;

  @BeforeEach
  void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var schema = "contained_lineage_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(source).execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
    new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
    var properties = new Properties();
    properties.setProperty("currentSchema", schema + ",public");
    source.setConnectionProperties(properties);
    Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
    jdbc = new JdbcTemplate(source);

    identities = new JdbcIdentityRepository(jdbc);
    identities.verifyBuiltIns();
    grants = new JdbcScopedGrantRepository(jdbc);
    ApplicationPolicy applications =
        ignored -> new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.EXTERNAL, Map.of());
    authority =
        new LiveScopedAuthority(
            grants, applications, new ApplicationGrantLineage(grants, applications), identities);
    members = new JdbcProjectMembers(jdbc);
    // The real thing, which is the whole point of this class.
    records = new JdbcOperationRecords(jdbc);

    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password) VALUES (?,'fixture',FALSE)",
        HOLDER);
    jdbc.update("INSERT INTO projects(name) VALUES ('parent')");
    parentProject = jdbc.queryForObject("SELECT id FROM projects WHERE name='parent'", Long.class);
    parentScope =
        new ScopeId(
            jdbc.queryForObject(
                "SELECT id FROM authority_scopes WHERE project_id=?", UUID.class, parentProject));
    // CONTRIBUTOR, so the container's live grants genuinely cover the hop: without it contain
    // would refuse on authority and every assertion here would prove nothing about the trigger.
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES (?,?,'CONTRIBUTOR')",
        parentProject,
        HOLDER);

    childProject = "contained:" + UUID.randomUUID();
    childScope = contained(childProject, parentScope);
    siblingProject = "contained:" + UUID.randomUUID();
    siblingScope = contained(siblingProject, parentScope);
    grandchildProject = "contained:" + UUID.randomUUID();
    grandchildScope = contained(grandchildProject, childScope);
    jdbc.update("INSERT INTO projects(name) VALUES ('unrelated')");
    unrelatedScope =
        new ScopeId(
            jdbc.queryForObject(
                "SELECT id FROM authority_scopes WHERE project_id="
                    + "(SELECT id FROM projects WHERE name='unrelated')",
                UUID.class));
  }

  private ScopeId contained(String project, ScopeId container) {
    jdbc.update("INSERT INTO projects(name) VALUES (?)", project);
    long id = jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, project);
    var scope = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO authority_scopes(id,kind,project_id,container) VALUES (?,'CONTAINED',?,?)",
        scope,
        id,
        container.value());
    return new ScopeId(scope);
  }

  private long projectId(String name) {
    return jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, name);
  }

  private PinnedOperationAuthority policy(ContainmentBounds bounds) {
    return new PinnedOperationAuthority(
        identities,
        grants,
        authority,
        members,
        records,
        new JdbcResourceScopeRepository(jdbc),
        AccountKnowledgePermissions.DENY,
        bounds);
  }

  private PinnedOperationAuthority policy() {
    return policy(new ContainmentBounds(3, 4));
  }

  private AuthorityScope.Contained scope(ScopeId child, String project, ScopeId container) {
    return new AuthorityScope.Contained(child, projectId(project), container);
  }

  /** The container's own admission: a real, sealed, retained row. */
  private OperationAuthority.Admission container() {
    return policy()
        .admit(HOLDER, new AuthorityScope.Project(parentScope, parentProject), DERIVABLE);
  }

  private UUID edge(UUID child) {
    return jdbc
        .query(
            "SELECT parent FROM operation_admission_parents WHERE child=?",
            (row, n) -> row.getObject(1, UUID.class),
            child)
        .stream()
        .findFirst()
        .orElse(null);
  }

  /**
   * The central test: a real hop, persisted. Before V230 this failed with {@code child authority
   * exceeds retained parent} from {@code operation_parent_valid}.
   */
  @Test
  void aContainmentHopRecordsItsLineage() {
    var parent = container();
    var child = policy().contain(parent, scope(childScope, childProject, parentScope), DERIVABLE);

    assertEquals(
        parent.id(), edge(child.id()), "the hop's parent edge is retained, not merely asserted");
    // The two columns the old trigger compared, now genuinely different, which is what a hop is.
    assertEquals(
        childScope.value(),
        jdbc.queryForObject(
            "SELECT scope FROM operation_admissions WHERE id=?", UUID.class, child.id()));
    assertEquals(
        childProject,
        jdbc.queryForObject(
            "SELECT project_name FROM operation_admissions WHERE id=?", String.class, child.id()));
    assertEquals(
        parentScope.value(),
        jdbc.queryForObject(
            "SELECT scope FROM operation_admissions WHERE id=?", UUID.class, parent.id()));
    // Everything the hop keeps unchanged is still compared by the trigger and still equal: the
    // executor, its handle and the grant source. A hop changes scope and home and nothing else.
    assertEquals(
        jdbc.queryForObject(
            "SELECT executor FROM operation_admissions WHERE id=?", UUID.class, parent.id()),
        jdbc.queryForObject(
            "SELECT executor FROM operation_admissions WHERE id=?", UUID.class, child.id()),
        "the accounting account is the container's, unchanged across the hop");
    assertEquals(
        jdbc.queryForObject(
            "SELECT source_account FROM operation_admissions WHERE id=?", UUID.class, parent.id()),
        jdbc.queryForObject(
            "SELECT source_account FROM operation_admissions WHERE id=?", UUID.class, child.id()));
    // And the admission is live afterwards, which is the claim ADR 0042 made while nothing could
    // persist a hop to check it.
    assertDoesNotThrow(() -> policy().require(child));
    // A grandchild hop from the child's own admission persists too: the relation is a chain.
    var grandchild =
        policy().contain(child, scope(grandchildScope, grandchildProject, childScope), DERIVABLE);
    assertEquals(child.id(), edge(grandchild.id()));
  }

  /**
   * The no-escalation invariant, at the fence rather than at the Java check. Each insert names a
   * child scope whose {@code container} is not the parent admission's scope.
   *
   * <p>These go in directly, because that is the only way to ask the trigger the question: {@code
   * contain} refuses all three in Java first, which is asserted separately below. A Java check is a
   * check; the trigger is the fence, and an invariant has to hold at the fence.
   */
  @Test
  void theTriggerRefusesAChildWhoseContainerIsNotTheParentAdmissionsScope() {
    var parent = container();
    var child = policy().contain(parent, scope(childScope, childProject, parentScope), DERIVABLE);

    // CONTROL: the legitimate edge is admitted, by the test above and by this row existing.
    assertEquals(parent.id(), edge(child.id()));

    // A GRANDPARENT: the grandchild's container is the child's scope, not the parent admission's.
    assertThrows(
        DataAccessException.class,
        () -> forge(parent, grandchildScope, grandchildProject),
        "a grandparent cannot be the retained parent of a grandchild's admission");
    // A SIBLING SUBTREE: the sibling's container is the parent scope, so this one IS legitimate
    // against the parent -- and must be refused against the CHILD's admission, which is the
    // sibling-subtree case.
    assertThrows(
        DataAccessException.class,
        () -> forge(child, siblingScope, siblingProject),
        "a sibling's scope is not contained by its sibling");
    // AN UNRELATED SCOPE: not contained at all, and its project is an ordinary one.
    assertThrows(DataAccessException.class, () -> forge(parent, unrelatedScope, "unrelated"));
    // CONTROL: the sibling against its real container is admitted, so the refusals above are
    // about the containment edge and not about forged rows being rejected wholesale.
    assertDoesNotThrow(() -> forge(parent, siblingScope, siblingProject));
  }

  /**
   * Writes a child admission row directly and tries to retain {@code parent} as its parent, which
   * is the one way to put the trigger's question to it without {@code contain}'s Java checks in
   * front. Everything the trigger compares other than scope and project is copied from the parent,
   * so a refusal can only be about the containment edge.
   */
  private void forge(OperationAuthority.Admission parent, ScopeId scope, String project) {
    retainCopy(parent, scope, project, parent);
  }

  /**
   * Writes a sealed child admission row by hand: the parent's executor, handle and grant source
   * copied, the scope and project replaced, and the support rows copied from {@code support}.
   *
   * <p>Support before the seal, because {@code operation_admission_immutable} refuses a sealed
   * admission that gains support — the same order {@code JdbcOperationRecords.retain} uses. Doing
   * it the other way round is a fixture error that looks exactly like a trigger refusal.
   */
  private UUID retainCopy(
      OperationAuthority.Admission parent,
      ScopeId scope,
      String project,
      OperationAuthority.Admission support) {
    var child = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO operation_admissions"
            + " (id,executor,executor_handle,scope,project_name,source_account)"
            + " SELECT ?,executor,executor_handle,?,?,source_account"
            + " FROM operation_admissions WHERE id=?",
        child,
        scope.value(),
        project,
        parent.id());
    jdbc.update(
        "INSERT INTO operation_admission_support(operation,action,grant_id)"
            + " SELECT ?,action,grant_id FROM operation_admission_support WHERE operation=?",
        child,
        support.id());
    jdbc.update("UPDATE operation_admissions SET sealed=true WHERE id=? AND NOT sealed", child);
    jdbc.update(
        "INSERT INTO operation_admission_parents(child,parent)"
            + " SELECT ?,id FROM operation_admissions WHERE id=? AND sealed",
        child,
        parent.id());
    return child;
  }

  /** {@code contain} refuses the same three in Java, before any row is written. */
  @Test
  void containRefusesTheSameThreeBeforeAnythingIsWritten() {
    var parent = container();

    assertThrows(
        CallerFault.class,
        () ->
            policy()
                .contain(parent, scope(grandchildScope, grandchildProject, parentScope), DERIVABLE),
        "a grandchild does not name this container");
    assertThrows(
        CallerFault.class,
        () -> policy().contain(parent, scope(childScope, siblingProject, parentScope), DERIVABLE),
        "nor does a child described with another child's project");
    assertEquals(
        0,
        (int)
            jdbc.queryForObject("SELECT count(*) FROM operation_admission_parents", Integer.class),
        "and nothing was retained by either refusal");
    // CONTROL: the legitimate hop is admitted by the same policy object.
    assertNotNull(
        policy().contain(parent, scope(childScope, childProject, parentScope), DERIVABLE).id());
  }

  /**
   * The non-contained path keeps the exact equality it has today, asserted byte-for-byte: a derived
   * child admission holds its parent's scope and project name, and a row that does not is refused
   * exactly as it was before V230.
   */
  @Test
  void aNonContainedChildKeepsTheScopeAndProjectEquality() {
    var parent = container();
    var derived = policy().derive(parent, Set.of(AuthorityAction.READ));

    assertEquals(parent.id(), edge(derived.id()), "an ordinary derivation still retains its edge");
    assertEquals(
        parentScope.value(),
        jdbc.queryForObject(
            "SELECT scope FROM operation_admissions WHERE id=?", UUID.class, derived.id()),
        "and holds its parent's scope, unchanged");
    assertEquals(
        "parent",
        jdbc.queryForObject(
            "SELECT project_name FROM operation_admissions WHERE id=?",
            String.class,
            derived.id()));

    // A non-contained child whose scope differs is still refused: V230's arm is keyed on a
    // CONTAINED row, so an ordinary scope gets the old equality and nothing else.
    jdbc.update("INSERT INTO projects(name) VALUES ('other')");
    var other =
        new ScopeId(
            jdbc.queryForObject(
                "SELECT id FROM authority_scopes WHERE project_id="
                    + "(SELECT id FROM projects WHERE name='other')",
                UUID.class));
    assertThrows(
        DataAccessException.class,
        () -> forge(parent, other, "other"),
        "an ordinary PROJECT scope is not admitted by the containment arm");
  }

  /** The support subset check is untouched: a child cannot hold an action its parent does not. */
  @Test
  void aChildWhoseActionsExceedItsParentIsRefused() {
    var narrow =
        policy()
            .admit(
                HOLDER,
                new AuthorityScope.Project(parentScope, parentProject),
                Set.of(AuthorityAction.READ));
    // CONTROL: a hop carrying exactly READ is admitted under that narrow parent.
    assertNotNull(
        policy()
            .contain(
                narrow, scope(childScope, childProject, parentScope), Set.of(AuthorityAction.READ))
            .id());
    // And asking for more than the parent holds is refused in Java before any row.
    assertThrows(
        CallerFault.class,
        () ->
            policy()
                .contain(
                    narrow,
                    scope(siblingScope, siblingProject, parentScope),
                    Set.of(AuthorityAction.READ, AuthorityAction.WRITE)),
        "a hop cannot carry an action its parent does not hold");
    // At the fence: a forged contained child of the narrow parent, carrying the WIDE parent's
    // support rows, is refused -- so the containment arm did not become a way past the subset
    // check. This is the invariant V230 must not have touched.
    var wide = container();
    assertThrows(
        DataAccessException.class,
        () -> retainCopy(narrow, siblingScope, siblingProject, wide),
        "the support subset check still refuses an escalating contained child");
    // CONTROL: the same forged row carrying the NARROW parent's own support is admitted, so the
    // refusal above is the extra support and not the forgery.
    assertNotNull(retainCopy(narrow, siblingScope, siblingProject, narrow));
  }

  /**
   * The configured depth still bounds the chain, and it is Java's bound rather than the trigger's.
   */
  @Test
  void aChainBeyondTheConfiguredDepthIsRefused() {
    var parent = container();
    var child = policy().contain(parent, scope(childScope, childProject, parentScope), DERIVABLE);

    assertThrows(
        CallerFault.class,
        () ->
            policy(new ContainmentBounds(1, 4))
                .contain(child, scope(grandchildScope, grandchildProject, childScope), DERIVABLE),
        "a second hop exceeds a configured depth of one");
    // CONTROL: the same hop under a depth of two is admitted and persists.
    var grandchild =
        policy(new ContainmentBounds(2, 4))
            .contain(child, scope(grandchildScope, grandchildProject, childScope), DERIVABLE);
    assertEquals(child.id(), edge(grandchild.id()));
  }

  /**
   * A contained admission with no retained parent edge is refused: a hop is how one comes to be.
   */
  @Test
  void aContainedAdmissionWithNoParentEdgeIsRefused() {
    var parent = container();
    var child = policy().contain(parent, scope(childScope, childProject, parentScope), DERIVABLE);
    // CONTROL: with its edge it is live.
    assertDoesNotThrow(() -> policy().require(child));

    // The same admission with its edge gone is refused. The row is immutable by trigger, so this
    // disables the trigger for one statement -- the point is the Java gate's behaviour when no
    // edge exists, which is also the state a freely constructed record would present.
    jdbc.execute(
        "ALTER TABLE operation_admission_parents DISABLE TRIGGER operation_parent_immutable");
    jdbc.update("DELETE FROM operation_admission_parents WHERE child=?", child.id());
    jdbc.execute(
        "ALTER TABLE operation_admission_parents ENABLE TRIGGER operation_parent_immutable");

    assertThrows(
        CallerFault.class,
        () -> policy().require(child),
        "a contained admission with no parent edge is not accepted for want of a parent");
  }
}
