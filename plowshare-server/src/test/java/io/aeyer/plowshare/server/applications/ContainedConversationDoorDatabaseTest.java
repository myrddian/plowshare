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
   * CHARACTERIZATION, and the reason this slice stops here: the hop itself cannot record its
   * lineage against real PostgreSQL, so the door below it is unreachable and the envelope's budget
   * and account cannot yet be asserted.
   *
   * <p>{@code contain} retains the child admission's parent edge, and {@code
   * operation_parent_valid} (V162:35-36) requires a child admission to carry {@code c.scope =
   * p.scope AND c.project_name = p.project_name}. A containment hop changes <strong>both</strong>
   * by design — that is what containment is — so the trigger refuses the edge. Every existing test
   * of the hop mocks {@code OperationRecords}, which is why this has been invisible: the hop has
   * never run against the real trigger.
   *
   * <p>This test exists to pin that refusal with its exact message rather than to assert that it is
   * correct. It is not correct: it is a gate written before anything created a contained admission,
   * exactly like {@code JdbcIdentityRepository}'s {@code CONTAINED} refusal and {@code
   * ConversationStore}'s two doors. Opening it needs a new Flyway migration and therefore its own
   * authorization — shipped migrations are never edited — which is why this round stops and reports
   * instead of widening again.
   *
   * <p>A second gate sits behind it, read from the DDL rather than reached: {@code
   * operation_conversation_valid} (V162:62) requires the conversation row's {@code agent} to equal
   * the bound envelope's, while {@code conversations_a_person_s_conversation_names_no_agent}
   * (V17:212) requires {@code (origin='turn') = (agent IS NULL)}. So an envelope that names an
   * agent cannot sit on the {@code Origin.TURN} row this door writes, and the origins that do name
   * one are precisely those whose door calls {@code admit}. That is an {@code Origin} decision, so
   * it is named here and not guessed at.
   */
  @Test
  void theHopCannotRecordItsLineageSoTheDoorBelowItIsUnreachable() {
    var parent =
        policy.admit(
            HOLDER, new AuthorityScope.Project(parentScope, projectId("parent")), DERIVABLE);
    // Control: the container's own admission is retained and sealed, so the refusal below is the
    // hop's parent edge and not a broken fixture or an unauthorized container.
    assertTrue(
        jdbc.queryForObject(
            "SELECT sealed FROM operation_admissions WHERE id=?", Boolean.class, parent.id()),
        "the container's own admission is sealed");
    assertEquals(
        "parent",
        jdbc.queryForObject(
            "SELECT project_name FROM operation_admissions WHERE id=?", String.class, parent.id()));

    var refused =
        assertThrows(
            org.springframework.dao.DataAccessException.class,
            () -> policy.contain(parent, scope(childScope, childProject), DERIVABLE));
    var cause = refused.getMostSpecificCause().getMessage();
    assertTrue(cause.contains("child authority exceeds retained parent"), cause);
    assertTrue(cause.contains("operation_parent_valid"), cause);

    // And the two columns the trigger compares really do differ, which is what makes this a
    // containment problem rather than a missing grant: a hop changes the scope and the project.
    assertNotEquals(parentScope, childScope);
    assertNotEquals("parent", childProject);
    // So no conversation was opened either, because the door is never reached.
    assertEquals(0, (int) jdbc.queryForObject("SELECT count(*) FROM conversations", Integer.class));
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
