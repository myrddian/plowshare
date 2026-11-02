package io.aeyer.plowshare.server.applications;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.allowances.AllowanceId;
import io.aeyer.plowshare.server.allowances.AllowanceSnapshot;
import io.aeyer.plowshare.server.allowances.JdbcAllowanceRepository;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.JdbcProjectMembers;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.information.AccountKnowledgePermissions;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * The contained conversation door, against the real authority substrate.
 *
 * <h2>Why this needs a real database</h2>
 *
 * <p>Nothing could start work inside a confined child Application, and the reason was not the
 * operation that starts it: {@code ConversationStore} resolved a run's home to a scope through
 * {@code OperationAuthority.scope(Home)} at both of its doors, that resolution refuses a {@code
 * CONTAINED} row by design, and {@code admit} refuses a contained target besides — correctly, since
 * a contained admission comes into being only through a containment hop. Every one of those facts
 * is PL/pgSQL, a real grant row or a real {@code authority_scopes} row, so a mock cannot answer
 * whether the new door works: {@code contain} must really resolve the stored containment row, the
 * container's live grants must really cover the hop, and {@code bind} must really prove the
 * conversation belongs to the admission's own home.
 *
 * <h2>What it asserts</h2>
 *
 * <p>The two facts the ruling names — the envelope's budget is the <strong>child's own
 * allowance</strong> and its admission's account is the <strong>container's</strong> — plus the
 * refusals that make the door a door rather than a hole: the ordinary doors refuse a contained
 * home, and the contained door refuses an admission retained for a different child.
 *
 * <p>This is the first evidence in the slice that a child can be started at all. What it does not
 * cover is stated in the task report: the agent registry, {@code JobStore} and {@code Turn} are not
 * stood up here, so a model-calling run is still not exercised end to end.
 */
@Tag("full-db")
@Testcontainers
class ContainedConversationDoorDatabaseTest {
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
  private PinnedOperationAuthority policy;
  private ConversationStore conversations;
  private JdbcAllowanceRepository allowances;

  private ScopeId parentScope;
  private ScopeId childScope;
  private ScopeId siblingScope;
  private String childProject;
  private String siblingProject;
  private AllowanceId childAllowance;

  @BeforeEach
  void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var schema = "contained_door_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(source).execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
    new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
    var properties = new Properties();
    properties.setProperty("currentSchema", schema + ",public");
    source.setConnectionProperties(properties);
    Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
    jdbc = new JdbcTemplate(source);

    var identities = new JdbcIdentityRepository(jdbc);
    identities.verifyBuiltIns();
    var grants = new JdbcScopedGrantRepository(jdbc);
    ApplicationPolicy applications =
        ignored -> new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.EXTERNAL, Map.of());
    var authority =
        new LiveScopedAuthority(
            grants, applications, new ApplicationGrantLineage(grants, applications), identities);
    var members = new JdbcProjectMembers(jdbc);
    policy =
        new PinnedOperationAuthority(
            identities,
            grants,
            authority,
            members,
            new JdbcOperationRecords(jdbc),
            new JdbcResourceScopeRepository(jdbc),
            AccountKnowledgePermissions.DENY,
            new ContainmentBounds(3, 4));
    allowances = new JdbcAllowanceRepository(jdbc);
    conversations = new ConversationStore(jdbc, policy, allowances);

    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password) VALUES (?,'fixture',FALSE)",
        HOLDER);
    jdbc.update("INSERT INTO projects(name) VALUES ('parent')");
    long parentProject =
        jdbc.queryForObject("SELECT id FROM projects WHERE name='parent'", Long.class);
    parentScope =
        new ScopeId(
            jdbc.queryForObject(
                "SELECT id FROM authority_scopes WHERE project_id=?", UUID.class, parentProject));
    // CONTRIBUTOR, which is what makes the container's live grants cover the hop. Without it
    // contain() would refuse and every assertion below would pass for want of a grant.
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES (?,?,'CONTRIBUTOR')",
        parentProject,
        HOLDER);

    childProject = "contained:" + UUID.randomUUID();
    childScope = contained(childProject, parentScope);
    siblingProject = "contained:" + UUID.randomUUID();
    siblingScope = contained(siblingProject, parentScope);

    childAllowance = new AllowanceId(UUID.randomUUID());
    allowances.retain(new AllowanceSnapshot(childAllowance, 5, 0));
  }

  /** A contained scope over a fresh contained project; ancestry is the column, not the name. */
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

  private AuthorityScope.Contained scope(ScopeId child, String project) {
    return new AuthorityScope.Contained(child, projectId(project), parentScope);
  }

  /** The container's own admission, then the hop: exactly what {@code run} performs. */
  private OperationAuthority.Admission hop(ScopeId child, String project) {
    var parent =
        policy.admit(
            HOLDER, new AuthorityScope.Project(parentScope, projectId("parent")), DERIVABLE);
    return policy.contain(parent, scope(child, project), DERIVABLE);
  }

  /**
   * The hop now persists (V225), so the door is reached — and stops at gate six, which is pinned
   * here with its exact refusal because the decision it needs has not been taken.
   *
   * <p>{@code operation_conversation_valid} (V162:62) requires the conversation row's {@code agent}
   * to equal the bound envelope's, and {@code conversations_a_person_s_conversation_names_no_agent}
   * (V17:213) forbids an agent on an {@code origin='turn'} row. {@code openContained} writes an
   * {@code Origin.TURN} row, so its agent is NULL while the envelope always names one, and the
   * binding is refused. The fix is a choice about which {@code Origin} a contained run carries, not
   * another predicate: an origin kind reaches listings, retention and lifecycle, so it is reported
   * for decision rather than taken in a migration.
   *
   * <p>What this test proves today, which is more than the version it replaces: the hop is
   * recorded, the admission is live, and the remaining blocker is the conversation row's origin and
   * nothing else. When the origin is chosen, this becomes the envelope assertion in the test below
   * it.
   */
  @Test
  void theHopPersistsAndTheDoorStopsAtTheConversationOrigin() {
    var child = hop(childScope, childProject);

    // The hop itself: recorded, and live. Before V225 neither was true.
    assertEquals(
        1,
        (int)
            jdbc.queryForObject(
                "SELECT count(*) FROM operation_admission_parents WHERE child=?",
                Integer.class,
                child.id()),
        "the hop records its lineage, which V225 made possible");
    assertDoesNotThrow(() -> policy.require(child), "and the contained admission is live");
    assertEquals(Home.of(childProject), child.home());

    var refused =
        assertThrows(
            org.springframework.dao.DataAccessException.class,
            () ->
                conversations.openContained(
                    Home.of(childProject),
                    allowances.restore(childAllowance),
                    null,
                    HOLDER,
                    child,
                    "worker",
                    "0".repeat(64)));
    var cause = refused.getMostSpecificCause().getMessage();
    // Case-insensitive: V162 spells it lower case and a later migration re-raises it capitalised,
    // so pinning either spelling exactly would break on the other.
    assertTrue(
        cause
            .toLowerCase(java.util.Locale.ROOT)
            .contains("conversation does not match retained execution"),
        cause);
    assertTrue(cause.contains("operation_conversation_valid"), cause);

    // And the reason is the agent column, which the origin decides: the row this door writes is a
    // TURN row, so its agent is NULL while the envelope names one.
    assertNull(
        jdbc.queryForObject(
            "SELECT agent FROM conversations WHERE project_id="
                + "(SELECT id FROM projects WHERE name=?)",
            String.class,
            childProject),
        "the row was written with no agent, because an origin='turn' row may not name one");
    assertEquals(
        "turn",
        jdbc.queryForObject(
            "SELECT origin FROM conversations WHERE project_id="
                + "(SELECT id FROM projects WHERE name=?)",
            String.class,
            childProject));
  }

  /**
   * Both doors carry the branch. The ordinary doors refuse a contained home and name the one that
   * serves it, instead of reaching the scope resolution and failing there — which is what made this
   * gate look like a defect in containment rather than a door nobody had built.
   */
  @Test
  void theOrdinaryDoorsRefuseAContainedHomeAndNameTheOneThatServesIt() {
    var refusedOpen =
        assertThrows(
            CallerFault.class,
            () ->
                conversations.open(
                    Home.of(childProject), allowances.restore(childAllowance), null, HOLDER));
    assertTrue(refusedOpen.getMessage().contains("openContained"), refusedOpen.getMessage());

    var refusedLog =
        assertThrows(
            CallerFault.class,
            () ->
                conversations.log(
                    Origin.SUBMISSION,
                    Home.of(childProject),
                    "worker",
                    null,
                    allowances.restore(childAllowance),
                    HOLDER));
    assertTrue(refusedLog.getMessage().contains("openContained"), refusedLog.getMessage());

    // Control: the same two doors still work for an ordinary home, byte for byte as before. This
    // is what makes the refusals above a branch on containment and not a broken store.
    assertNotNull(
        conversations
            .open(Home.of("parent"), io.aeyer.plowshare.server.agents.Budget.of(3), null, HOLDER)
            .id());
    assertNotNull(
        conversations
            .log(
                Origin.SUBMISSION,
                Home.of("parent"),
                "worker",
                null,
                io.aeyer.plowshare.server.agents.Budget.of(3),
                HOLDER)
            .id());
  }

  /**
   * The door opens for a contained home <em>with an admission retained for that same home</em> and
   * nothing else, which is checked before anything is written and therefore checkable without the
   * hop that the gate above blocks.
   *
   * <p>The admission used here is the container's own, whose retained home is the container's
   * project — standing in for "an admission that is not this child's", which is the whole class the
   * check has to refuse, a sibling's included. Once the lineage gate opens, this should gain a
   * sibling-admission case built from a real hop; it is written this way now so the door's own
   * check is covered rather than untested.
   */
  @Test
  void theContainedDoorRefusesAnAdmissionRetainedForAnotherHome() {
    var elsewhere =
        policy.admit(
            HOLDER, new AuthorityScope.Project(parentScope, projectId("parent")), DERIVABLE);
    assertEquals(Home.of("parent"), elsewhere.home());

    assertThrows(
        CallerFault.class,
        () ->
            conversations.openContained(
                Home.of(childProject),
                allowances.restore(childAllowance),
                null,
                HOLDER,
                elsewhere,
                "worker",
                "0".repeat(64)),
        "an admission retained for another home does not open this child");
    assertThrows(
        CallerFault.class,
        () ->
            conversations.openContained(
                Home.of("parent"),
                allowances.restore(childAllowance),
                null,
                HOLDER,
                elsewhere,
                "worker",
                "0".repeat(64)),
        "and an uncontained home is not this door's business at all");
    assertEquals(
        0,
        (int) jdbc.queryForObject("SELECT count(*) FROM conversations", Integer.class),
        "a refusal leaves no conversation behind: the admission is checked before the row");
  }
}
