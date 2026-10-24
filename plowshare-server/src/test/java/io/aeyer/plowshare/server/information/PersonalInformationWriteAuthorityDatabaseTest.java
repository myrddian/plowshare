package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.frames.*;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.LogStages;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.documents.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.fetch.PageFetcher;
import io.aeyer.plowshare.server.harness.Harness;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.personal.JdbcPersonalSpaceRepository;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import io.aeyer.plowshare.server.testing.AuthorityFixtures;
import io.aeyer.plowshare.server.ws.*;
import java.time.Clock;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * The personal information write path's authority, decided by PostgreSQL rather than by a mock.
 *
 * <h2>Why this needs a real database</h2>
 *
 * <p>{@code 6e0d2e50} changed which {@link Home} an information write presents to admission: a
 * PERSONAL selection now resolves {@code Home.of(PersonalSpaces.name(account))} through {@link
 * InformationAccess#executionHome} instead of falling to {@code Home.global()}. Every test of that
 * change so far is mocked, and a mock cannot answer the half of the decision that lives in SQL:
 *
 * <ul>
 *   <li>{@code PinnedOperationAuthority.scope(Home)} resolves that project name through {@code
 *       authority_scopes}, and only the real row says whether a personal space's name resolves to
 *       an {@link AuthorityScope.Personal} rather than a {@link AuthorityScope.Project} or nothing
 *       at all. The classification is computed by {@code authority_map_project} (V155) from {@code
 *       projects.personal_owner} and the owner's {@code admins.account_kind}.
 *   <li>{@code admit} then needs a live supporting grant, and {@code requireOwn} needs a role from
 *       {@code authority_project_role} (V156). Both answers come from {@code authority_grants} rows
 *       that only the {@code authority_membership_changed} trigger on {@code project_members}
 *       creates. Whether such a row exists for a personal space is the load-bearing fact the fix
 *       rests on, and it had only been read out of Java source.
 *   <li>The write itself is admitted by {@code information_lock_destination} and refused or allowed
 *       by {@code information_account_action} (V157, superseded by V163, rewrapped in V172/V178) —
 *       PL/pgSQL that takes real row locks and reads real grants.
 * </ul>
 *
 * <p>The two personal tests were confirmed to see the defect: with {@code executionHome}'s PERSONAL
 * branch put back to {@code Home.global()}, the upload below returns BAD_REQUEST carrying the
 * original production message, "Operation is outside its retained scoped authority", from real
 * PostgreSQL rather than from a stub. With the fix in place it is ACCEPTED.
 *
 * <p>So this class drives {@code information.upload} through the real {@link FrameRouter}, the real
 * catalogue, the real {@link InformationWriteGates} and a {@link ConversationStore} that actually
 * holds an {@link OperationAuthority}, against migrated PostgreSQL. Per the repository's database
 * test policy it is tagged {@code full-db} and runs only under {@code -PfullDb}.
 */
@Tag("full-db")
@Testcontainers
class PersonalInformationWriteAuthorityDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final String OWNER = "owner";
  private static final String OTHER = "other";

  static DriverManagerDataSource source;
  JdbcTemplate jdbc;
  UnitOfWork work;
  InformationAccess access;
  InformationCatalogue catalogue;
  PinnedOperationAuthority policy;
  JdbcProjectMembers members;
  JdbcIdentityRepository identities;
  FrameRouter router;

  @BeforeAll
  static void migrate() {
    source = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
  }

  @BeforeEach
  void setup() {
    jdbc = new JdbcTemplate(source);
    AuthorityFixtures.truncate(
        jdbc,
        "TRUNCATE documents,information_resources,conversations,projects,admins,operation_admissions CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password,server_admin)"
            + " VALUES(?,'fixture',false,false),(?,'fixture',false,false),('manager','fixture',false,false),('contributor','fixture',false,false),('viewer','fixture',false,false)",
        OWNER,
        OTHER);
    jdbc.update("INSERT INTO projects(name) VALUES('atlas')");
    for (var member :
        List.of(
            Map.entry("manager", "MANAGER"),
            Map.entry("contributor", "CONTRIBUTOR"),
            Map.entry("viewer", "VIEWER"),
            Map.entry(OTHER, "CONTRIBUTOR")))
      jdbc.update(
          "INSERT INTO project_members(project_id,handle,role) SELECT id,?,?::TEXT FROM projects WHERE name='atlas'",
          member.getKey(),
          member.getValue());

    work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
    identities = new JdbcIdentityRepository(jdbc);
    identities.verifyBuiltIns();
    var grants = new JdbcScopedGrantRepository(jdbc);
    ApplicationPolicy applications =
        ignored -> new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.EXTERNAL, Map.of());
    var authority =
        new LiveScopedAuthority(
            grants, applications, new ApplicationGrantLineage(grants, applications), identities);
    members = new JdbcProjectMembers(jdbc);
    policy =
        new PinnedOperationAuthority(
            identities,
            grants,
            authority,
            members,
            new JdbcOperationRecords(jdbc),
            new JdbcResourceScopeRepository(jdbc));
    access =
        new InformationAccess(
            new EffectiveProjectPermissions(members),
            io.aeyer.plowshare.server.requests.RequestProjects.EXPLICIT_ONLY,
            new LiveAccountKnowledgePermissions(identities, grants, authority));
    var records =
        new JdbcInformationCatalogueRepository(
            jdbc, Clock.systemUTC(), new RecordedInformationEvents());
    var readable = new java.util.concurrent.atomic.AtomicReference<InformationReadAccess>();
    var logs =
        new InformationJobs(
            new JdbcJobInformationRepository(jdbc),
            access,
            (context, revision) -> readable.get().requireReadable(context, revision));
    var gates =
        new InformationWriteGates(
            new JdbcInformationGateRepository(jdbc, Clock.systemUTC()),
            work,
            access,
            logs,
            new ConversationStore(jdbc, policy),
            LogStages.NONE,
            Hooks.NONE,
            Harness.NONE);
    var registry = new JdbcInformationContentTypeRepository(jdbc);
    var settings = new InformationContentSettings(true);
    var types =
        new InformationConfig()
            .informationContentTypes(registry, access, members, records, work, gates, settings);
    var content = new JdbcInformationContentRepository(jdbc, registry);
    catalogue =
        new InformationCatalogue(
                records,
                work,
                access,
                logs,
                new InformationContentAdmission(content, types, access, settings),
                content,
                new JdbcInformationProjectionRepository(jdbc, content),
                gates)
            .withAllowance(() -> 30);
    readable.set(catalogue);
    var frames =
        new InformationFrames(
            catalogue,
            access,
            new DocumentStore(jdbc, work),
            mock(RetrievalService.class),
            mock(Deliberation.class),
            mock(JobStore.class),
            new DocumentsProperties(),
            mock(PageFetcher.class),
            mock(InformationContentTypes.class),
            mock(InformationContentSettings.class),
            mock(InformationNavigationService.class),
            mock(InformationInterchangeService.class));
    router = new FrameRouter(frames.frames());
    router.useAuthorization(
        new ProjectAuthorization(
            new JdbcResourceScopeRepository(jdbc), members, mock(AdminStore.class)));
  }

  /**
   * Exactly the two statements {@code JdbcPersonalSpaceRepository} issues, in its order: {@code
   * reserve} creates the project row, {@code initialized} creates the {@code project_members} row.
   * They are kept separate here because the grant the write path needs only appears at the second.
   */
  private long reserve(String handle) {
    return new JdbcPersonalSpaceRepository(jdbc).reserve(handle).id();
  }

  /** The owner's grant, written when the identity is provisioned rather than when storage is. */
  private void own(String handle, long project) {
    new JdbcPersonalSpaceRepository(jdbc).owns(project, handle);
  }

  private void initialize(String handle, long project) {
    new JdbcPersonalSpaceRepository(jdbc).initialized(project, handle);
  }

  private long personalSpace(String handle) {
    long project = reserve(handle);
    own(handle, project);
    initialize(handle, project);
    return project;
  }

  private Outcome upload(String actor, Map<String, Object> scope, String name) {
    return router.route(
        new Envelope(
            UUID.randomUUID().toString(),
            "information.upload",
            Envelope.CURRENT_VERSION,
            Map.of(
                "scope",
                scope,
                "requestId",
                UUID.randomUUID().toString(),
                "name",
                name,
                "text",
                "retained body")),
        new Asking("verification", actor));
  }

  private static Map<String, Object> personalScope() {
    return Map.of("kind", "personal");
  }

  /**
   * The end-to-end assertion no mock can make: a fresh ordinary USER account uploads a personal
   * document and the server keeps it, with the admission retained against that account's own
   * PERSONAL scope rather than refused for naming the global tier.
   */
  @Test
  void an_ordinary_account_uploads_a_personal_document_against_its_own_personal_scope() {
    personalSpace(OWNER);

    var accepted = upload(OWNER, personalScope(), "notes.md");
    assertEquals(Code.ACCEPTED, accepted.code(), accepted.said());

    // The row is written in the account's own personal project, not an account namespace: a
    // personal selection is an alias for that project, so it is addressed the way any project is.
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_resources WHERE namespace=? AND source_name='notes.md'",
            Integer.class,
            "project:" + PersonalSpaces.name(OWNER)));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM information_resources WHERE namespace=?",
            Integer.class,
            "account:" + OWNER),
        "the account namespace is no longer a destination for a personal write");

    // One admission, retained against the PERSONAL scope of this account and its own home.
    var personal = assertInstanceOf(AuthorityScope.Personal.class, scopeOf(OWNER));
    assertEquals(identities.account(OWNER).id(), personal.account());
    assertEquals(
        List.of(personal.id().value()),
        jdbc.queryForList(
            "SELECT scope FROM operation_admissions WHERE executor_handle=?", UUID.class, OWNER));
    assertEquals(
        List.of(PersonalSpaces.name(OWNER)),
        jdbc.queryForList(
            "SELECT project_name FROM operation_admissions WHERE executor_handle=?",
            String.class,
            OWNER));
    // And the pipeline log the gate opened is in that personal home, not the global tier.
    assertEquals(
        List.of(PersonalSpaces.name(OWNER)),
        jdbc.queryForList(
            "SELECT p.name FROM conversations c JOIN projects p ON p.id=c.project_id"
                + " WHERE c.owner_handle=? AND c.agent='document_pipeline'",
            String.class,
            OWNER));
  }

  /** The PERSONAL scope row a personal space's name resolves to through real schema. */
  private AuthorityScope scopeOf(String handle) {
    return policy.scope(Home.of(PersonalSpaces.name(handle)));
  }

  /**
   * The grant/membership question the fix rests on, asked of real rows: a reserved personal project
   * alone carries no role, and the MANAGER grant that makes the write possible appears only when
   * {@code initialized} inserts the {@code project_members} row and the {@code
   * authority_membership_changed} trigger fires.
   */
  @Test
  void the_personal_write_grant_comes_from_identity_provisioning_not_storage() {
    long project = reserve(OWNER);
    String name = PersonalSpaces.name(OWNER);

    // The scope exists as soon as the project does, and it is PERSONAL, owned by this account.
    var personal = assertInstanceOf(AuthorityScope.Personal.class, scopeOf(OWNER));
    assertEquals(identities.account(OWNER).id(), personal.account());
    assertEquals(project, personal.projectId());

    // But no role and no grant yet: nothing has created a membership row.
    assertNull(
        jdbc.queryForObject("SELECT authority_project_role(?,?)", String.class, name, OWNER));
    assertTrue(members.role(name, OWNER).isEmpty());
    assertEquals(0, liveGrants(personal));
    assertThrows(
        CallerFault.class,
        () -> policy.admit(OWNER, personal, Set.of(AuthorityAction.CONVERSATION_CREATE)));
    assertEquals(Code.BAD_REQUEST, upload(OWNER, personalScope(), "early.md").code());

    // The grant arrives with the identity, not with storage: PersonalSpaces.ensureIdentity writes
    // it before any union exists, so an account can hold documents even where storage could not be
    // allocated. Storage initialization is called afterwards here only to show it is not what
    // grants.
    own(OWNER, project);

    assertEquals(
        "MANAGER",
        jdbc.queryForObject("SELECT authority_project_role(?,?)", String.class, name, OWNER));
    assertEquals(Optional.of(ProjectRole.MANAGER), members.role(name, OWNER));
    assertEquals(1, liveGrants(personal));
    assertEquals(
        "MEMBERSHIP",
        jdbc.queryForObject(
            "SELECT g.provenance FROM authority_grants g WHERE g.scope_id=? AND g.revoked_at IS NULL",
            String.class,
            personal.id().value()));
    assertDoesNotThrow(
        () -> policy.admit(OWNER, personal, Set.of(AuthorityAction.CONVERSATION_CREATE)));
  }

  private int liveGrants(AuthorityScope scope) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM authority_grants g JOIN authority_live_grants() l ON l.id=g.id"
            + " WHERE g.scope_id=?",
        Integer.class,
        scope.id().value());
  }

  /** A project upload still works and still needs CONTRIBUTOR or better. */
  @Test
  void a_project_upload_still_works_and_still_requires_contributor_or_manager() {
    var scope = Map.<String, Object>of("kind", "project", "project", "atlas");
    assertEquals(Code.ACCEPTED, upload("contributor", scope, "contributed.md").code());
    assertEquals(Code.ACCEPTED, upload("manager", scope, "managed.md").code());
    assertNotEquals(Code.ACCEPTED, upload("viewer", scope, "refused.md").code());
    assertEquals(
        Set.of("contributed.md", "managed.md"),
        Set.copyOf(
            jdbc.queryForList(
                "SELECT source_name FROM information_resources WHERE namespace='project:atlas'",
                String.class)));
    var atlas = assertInstanceOf(AuthorityScope.Project.class, policy.scope(Home.of("atlas")));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM operation_admissions WHERE scope=?",
            Integer.class,
            atlas.id().value()));
  }

  /** Global admission remains refused, including the resolution that precedes it. */
  @Test
  void a_global_destination_is_still_refused() {
    personalSpace(OWNER);
    assertThrows(CallerFault.class, () -> policy.scope(Home.global()));
    assertEquals(Code.BAD_REQUEST, upload(OWNER, Map.of("kind", "global"), "global.md").code());
    assertEquals(Code.BAD_REQUEST, upload(OWNER, Map.of("kind", "global_read"), "read.md").code());
    assertEquals(
        0, jdbc.queryForObject("SELECT count(*) FROM information_resources", Integer.class));
  }

  /**
   * The ownership fence against real grant rows. {@code other} holds a live CONTRIBUTOR grant of
   * its own, so this is not a caller with no authority at all; it still cannot be admitted to
   * another account's personal home, and PostgreSQL will not even create the grant that would make
   * it look plausible.
   */
  @Test
  void another_accounts_personal_home_is_refused_even_where_grants_exist() {
    long owned = personalSpace(OWNER);
    personalSpace(OTHER);
    var ownersSpace = assertInstanceOf(AuthorityScope.Personal.class, scopeOf(OWNER));

    // `other` genuinely holds live authority -- on atlas, and on its own personal space.
    assertEquals(Optional.of(ProjectRole.CONTRIBUTOR), members.role("atlas", OTHER));
    assertDoesNotThrow(
        () ->
            policy.admit(
                OTHER,
                policy.scope(Home.of("atlas")),
                Set.of(AuthorityAction.CONVERSATION_CREATE)));
    assertDoesNotThrow(
        () -> policy.admit(OTHER, scopeOf(OTHER), Set.of(AuthorityAction.CONVERSATION_CREATE)));

    // It is still refused on the owner's personal scope.
    assertThrows(
        CallerFault.class,
        () -> policy.admit(OTHER, ownersSpace, Set.of(AuthorityAction.CONVERSATION_CREATE)));
    assertNull(
        jdbc.queryForObject(
            "SELECT authority_project_role(?,?)", String.class, PersonalSpaces.name(OWNER), OTHER));

    // And the grant that would make it plausible cannot be written: the destination check on
    // authority_grants refuses a PERSONAL scope whose account is not the subject.
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO project_members(project_id,handle,role) VALUES(?,?,'MANAGER')",
                owned,
                OTHER));

    // A PERSONAL upload by `other` reaches its own space, never the owner's.
    assertEquals(Code.ACCEPTED, upload(OTHER, personalScope(), "mine.md").code());
    assertEquals(
        List.of("project:" + PersonalSpaces.name(OTHER)),
        jdbc.queryForList("SELECT namespace FROM information_resources", String.class),
        "the write lands in this account's own personal project and nowhere else");
    // Every admission this account ever obtained, including the two taken explicitly above: its
    // own personal space and atlas, and never the owner's personal space.
    assertEquals(
        Set.of("atlas", PersonalSpaces.name(OTHER)),
        Set.copyOf(
            jdbc.queryForList(
                "SELECT project_name FROM operation_admissions WHERE executor_handle=?",
                String.class,
                OTHER)));
  }
}
