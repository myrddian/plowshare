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
   * THE SLICE'S GOAL, met: a contained conversation opened through the real door on a real hop,
   * with the two facts the ruling names.
   *
   * <p>Everything in this test is the production path — {@code admit}, {@code contain}, {@code
   * openContained}, {@code bind} and the real allowance repository — so what it asserts is what a
   * started child actually gets: an envelope whose <strong>budget is the child's own
   * allowance</strong> under the ceiling its container set, and whose <strong>admission account is
   * the container's</strong>, unchanged across the hop.
   */
  @Test
  void aContainedRunGetsAnEnvelopeOnItsOwnPoolAndItsContainersAccount() {
    var child = hop(childScope, childProject);
    // The hop is recorded and live, which V225 made possible and which nothing before it was.
    assertEquals(
        1,
        (int)
            jdbc.queryForObject(
                "SELECT count(*) FROM operation_admission_parents WHERE child=?",
                Integer.class,
                child.id()));
    assertDoesNotThrow(() -> policy.require(child));

    var opened =
        conversations.openContained(
            Home.of(childProject),
            allowances.restore(childAllowance),
            null,
            HOLDER,
            child,
            "worker",
            "0".repeat(64));

    // THE ROW: the child's own project, its own origin, naming its agent, carrying its own pool.
    assertEquals(
        childProject,
        jdbc.queryForObject(
            "SELECT p.name FROM conversations c JOIN projects p ON p.id=c.project_id WHERE c.id=?",
            String.class,
            opened.id()));
    assertEquals(
        "contained",
        jdbc.queryForObject(
            "SELECT origin FROM conversations WHERE id=?", String.class, opened.id()));
    assertEquals(
        "worker",
        jdbc.queryForObject(
            "SELECT agent FROM conversations WHERE id=?", String.class, opened.id()));
    assertEquals(
        childAllowance.value(),
        jdbc.queryForObject(
            "SELECT budget_id FROM conversations WHERE id=?", UUID.class, opened.id()),
        "the row points at the child's own allowance, not a pool minted for the run");

    // THE ENVELOPE application.child.trajectory reads back. These are the ruling's two facts.
    var envelope = policy.conversation(opened.id()).orElseThrow();
    assertEquals(
        childAllowance,
        envelope.budget(),
        "the envelope's budget is the child's allowance, under the ceiling its container set");
    assertEquals(
        jdbc.queryForObject(
            "SELECT id FROM authority_accounts WHERE legacy_handle=?", UUID.class, HOLDER),
        envelope.admission().account().value(),
        "and its accounting account is the container's, unchanged across the hop");
    assertEquals(HOLDER, envelope.admission().executor(), "as is its executor");
    assertEquals(childScope, envelope.admission().scope(), "while the scope is the child's own");
    assertEquals("worker", envelope.agent());
    // The ceiling really is the container's 5 calls, so "its own pool" is a bounded pool.
    assertEquals(5, allowances.find(childAllowance).orElseThrow().ceiling());
    // And the container's trajectory read finds exactly this envelope, keyed on the child's scope.
    assertEquals(1, policy.conversationsIn(childScope).size());
  }

  /**
   * The one way the {@code Origin.CONTAINED} decision goes wrong: a permissive sharing arm.
   *
   * <p>Sharing an allowance by reference is precisely what disqualified {@code delegation} — a
   * contained conversation carried on it would spend its container's pool, against ADR 0042's
   * "allocated, never shared". So V225 puts {@code contained} on the <em>owned</em> side of {@code
   * conversations_an_allowance_is_owned_or_shared}, and this asserts the fence rather than trusting
   * the service to pass a budget: a budget-less contained row is refused by PostgreSQL.
   */
  @Test
  void aContainedConversationCannotShareItsContainersPool() {
    var refused =
        assertThrows(
            org.springframework.dao.DataAccessException.class,
            () -> insertRow("contained", "worker", null),
            "a contained conversation with no allowance of its own is refused");
    assertTrue(
        refused.getMostSpecificCause().getMessage().contains("owned_or_shared"),
        refused.getMostSpecificCause().getMessage());
    // CONTROL: the same row with its own allowance is accepted, so the refusal is the missing
    // budget and not the origin being unknown.
    assertDoesNotThrow(() -> insertRow("contained", "worker", 5));
    // And the shared-side kinds are unchanged: a delegation still may not carry one, which is the
    // property that made it the wrong kind to reuse.
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> insertRow("delegation", "worker", 5),
        "a delegation still shares by reference and holds no budget of its own");
  }

  /**
   * The agent pairing, fenced in both directions by one equality rather than by two constraints.
   *
   * <p>{@code conversations_a_person_s_conversation_names_no_agent} (V17:212) is {@code
   * (origin='turn') = (agent IS NULL)}, so it already says both things: a turn names no agent, and
   * every other origin — {@code contained} included — must name one. V225 adds no second constraint
   * saying the same thing; this asserts both halves instead, so the pairing is pinned where it
   * currently fails and where it currently holds.
   */
  @Test
  void aContainedConversationMustNameItsAgentAndATurnMustNot() {
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> insertRow("contained", null, 5),
        "a contained conversation with no agent is refused");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> insertRow("turn", "worker", 5),
        "and a turn naming one is refused, which is the same equality from the other side");
    // CONTROLS: each origin with its own correct pairing is accepted.
    assertDoesNotThrow(() -> insertRow("contained", "worker", 5));
    assertDoesNotThrow(() -> insertRow("turn", null, 5));
  }

  /**
   * One conversation row written by hand, so a constraint can be asked a question the service would
   * never pose. The project is the child's, the lifecycle is a root's, and nothing else is set,
   * which keeps a refusal attributable to the column under test.
   */
  private void insertRow(String origin, String agent, Integer budget) {
    jdbc.update(
        "INSERT INTO conversations"
            + " (id,project_id,origin,lifecycle,agent,created_at,budget_total,budget_spent,"
            + " budget_lifted,turn_cap_lifted,budget_id)"
            + " VALUES (?,(SELECT id FROM projects WHERE name=?),?,'active',?,now(),?,?,FALSE,"
            + " FALSE,?)",
        "c-" + UUID.randomUUID(),
        childProject,
        origin,
        agent,
        budget,
        budget == null ? null : 0,
        UUID.randomUUID());
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
   * <p>Two admissions that are not this child's are used, and the <strong>sibling's is the one that
   * matters</strong>: both children are held by the same container, so a container holding one
   * proved contained admission must not be able to open a conversation inside the other. The
   * container's own admission covers the uncontained case in the same shape. The sibling case is
   * built from a real hop, which V225 made possible — before it, no second contained admission
   * could exist to try.
   */
  @Test
  void theContainedDoorRefusesAnAdmissionRetainedForAnotherHome() {
    // A real sibling hop: another child of the same container, with its own live admission.
    var sibling = hop(siblingScope, siblingProject);
    assertEquals(Home.of(siblingProject), sibling.home());
    assertDoesNotThrow(() -> policy.require(sibling), "the sibling's own admission is live");

    assertThrows(
        CallerFault.class,
        () ->
            conversations.openContained(
                Home.of(childProject),
                allowances.restore(childAllowance),
                null,
                HOLDER,
                sibling,
                "worker",
                "0".repeat(64)),
        "a sibling's admission does not open this child, though one container holds both");
    // CONTROL: the sibling's own admission opens the sibling's own conversation, so the refusal is
    // about which child the admission names and not about the hop or the door being broken.
    assertNotNull(
        conversations
            .openContained(
                Home.of(siblingProject),
                allowances.restore(childAllowance),
                null,
                HOLDER,
                sibling,
                "worker",
                "0".repeat(64))
            .id());

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
        1,
        (int) jdbc.queryForObject("SELECT count(*) FROM conversations", Integer.class),
        "only the sibling's own conversation exists: every refusal above wrote none, because the"
            + " admission is checked before the row");
  }
}
