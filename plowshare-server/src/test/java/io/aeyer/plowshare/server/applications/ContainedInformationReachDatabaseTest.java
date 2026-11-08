package io.aeyer.plowshare.server.applications;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.DocumentType;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.MemoryState;
import io.aeyer.plowshare.protocol.Provenance;
import io.aeyer.plowshare.server.access.ScopedPermissions;
import io.aeyer.plowshare.server.archive.ArchiveConfig;
import io.aeyer.plowshare.server.archive.MemoryStore;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.information.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * What a contained run reaches once its information actor is resolved from its own log.
 *
 * <h2>Why this needs a real database</h2>
 *
 * <p>Every gate this task turns on is a SQL predicate. V223's contained arm, V229's read baseline
 * and write arms, V231's reference arm and V232's memory arms each admit a bare-UUID principal to
 * its own contained scope and to nothing else, by walking {@code contained_scope_ancestors} upward
 * from the descendant; {@code information_job_inputs.owner_handle} is a live foreign key into
 * {@code admins}; {@code home_scope_id} is resolved by a shipped trigger. None of those is a fact a
 * mock can state, and the authority claim this task makes — a child reaches its own corpus and no
 * other — is exactly the claim a mocked gate would assert about itself. The Java half of the change
 * is proved without a database in {@code ContainedInformationActorTest}.
 *
 * <p>Reads and writes go through the real repositories and the real {@link MemoryStore} with a
 * context produced by {@code InformationAccess.forRun}, which is the call the run binding makes.
 * The one fixture that is not production code is {@link #permissions}, and it reproduces {@code
 * EffectiveProjectPermissions} over {@code JdbcProjectMembers}' own effective-role query for an
 * ordinary project rather than standing up that class's whole collaborator graph; the query it
 * issues is {@code authority_project_role}, which is the statement that decides the answer.
 *
 * <h2>Characterization, measured before the wiring</h2>
 *
 * <p>The production change was inverted to its pre-task behaviour ({@code InformationAccess.forRun}
 * raising {@code IllegalStateException} for a contained actor) and this class was run as written:
 * <strong>7 tests, 6 failed, 1 passed, in 1m 47s.</strong> Every one of the six failures was that
 * same {@code IllegalStateException} out of {@code forRun} — including the four tests whose subject
 * is a <em>refusal</em>, because each has to resolve the child's context before it can assert what
 * the child cannot reach. That uniformity is the finding: before this task there was no contained
 * context a production call could hold, so none of these questions could be asked at all, and "a
 * contained run resolves as its container" was observable only through the mocked test's actor, one
 * layer up.
 *
 * <p>The one that <strong>passed beforehand</strong> is {@link
 * #aContainedChildReadsAndWritesItsOwnMemories}, and that is worth more than a line in a table: it
 * never calls {@code forRun}, because the memory path takes a {@code Home} and an author string
 * rather than an {@link InformationContext}. V232 had already opened {@link MemoryStore}'s three
 * identity statements, so the child's memory write and the predicate behind its read worked before
 * this task and work after it. <strong>That task added nothing to the memory half</strong>, and
 * that test is kept as the regression that says so rather than as evidence of this change. A later
 * fix wave did change it: all THREE of {@code JobRuntime}'s memory bindings — {@code
 * MemoryTools.Recall}, {@code MemoryTools.Write} and the automatic reminder's {@code
 * Reminding#forQueries} — now pass {@code InformationActor.acting()} rather than the container's
 * identity, so the principal V232's arms admit is the one a contained run presents. V232's WRITE
 * arm is now evaluated by a production caller, because {@code BoundMemoryWrites.write} pins before
 * it checks the recipient; its READ arm is not, because {@code BoundMemoryQueries.requireScope}
 * precedes the pin. The store-driven half is {@code
 * ContainedMemorySchemaTest.aContainedPrincipalIsStillRefusedAboveTheStore}; the ceiling below is
 * unchanged.
 *
 * <p>One correction to this class's own first draft, recorded because it changes what two
 * assertions mean. {@link #aContainedChildCannotSetAnAudience} and {@link
 * #aContainedChildReachesNoGenerallyReadableDocument} first read {@code
 * information_resources.audience}, a column that does not exist: V158 moved the authoritative root
 * audience to {@code information_resource_policies.audience} with the values {@code HOME} and
 * {@code GLOBAL_READ}, and left the per-revision {@code visibility} flags behind as historical
 * evidence no writer may change. Both tests failed with a grammar error on the first green run and
 * now read {@link #audienceOf}. The pre-task characterization above is unaffected, because both had
 * already failed on {@code forRun} several statements earlier.
 *
 * <h2>What this proves, and what it does not</h2>
 *
 * <p><strong>It proves</strong> that a contained run's context is the child's principal acting and
 * its container accountable; that a document written under it lands on the child's own contained
 * scope with the container's account in every provenance column; that the child reads that document
 * back through {@code JdbcInformationCatalogueRepository}'s real gate; that it reads no document of
 * its container, no generally readable document and nothing of a sibling; that its log inputs are
 * stored under the container's handle and that storing them under the principal is impossible
 * rather than merely refused; and that {@code SET_AUDIENCE} is shut both at the permission question
 * and at the repository guard.
 *
 * <p><strong>The service gates admit a child to its own home now; this paragraph used to say they
 * refused it.</strong> {@code InformationAccess.requireSelection} and {@code requireWork} ask
 * {@link ScopedPermissions} for a live project role, which a contained project grants to nobody —
 * so they refused every contained context, and {@code InformationCatalogue.admit} calls {@code
 * requireWork} as its first statement, which refused the catalogue write path before any SQL arm
 * was evaluated. Both now consult {@link io.aeyer.plowshare.server.access.ContainedHomes} first,
 * and {@link #theServiceGatesAdmitAChildToItsOwnHomeOnly} drives that with the production source
 * over these real rows. {@code requireAudience} deliberately still refuses: V229 admits WRITE for a
 * contained actor and never SET_AUDIENCE.
 *
 * <p><strong>Nor does the navigation path reach V231's disjunct.</strong> An earlier draft of this
 * paragraph said a contained run reaches "the navigation read path, which calls no such gate". It
 * calls a different one. {@code InformationTool} hands {@code actor.acting()} to {@code
 * DocumentNavigation.execute}; its read path calls {@code repository.requireActive}, which is
 * {@code EXISTS(SELECT 1 FROM admins WHERE handle=? AND account_active(handle) AND NOT bootstrap)}
 * in {@code JdbcInformationNavigationRepository}, and its mutation path calls {@code
 * repository.lockAccount} → {@code lockIdentity}'s {@code SELECT handle FROM admins ... FOR SHARE}.
 * A contained principal has no {@code admins} row (V218), so every navigation operation a contained
 * run issues is refused there, before V231's new disjunct is evaluated. That disjunct is reachable
 * only from a direct repository call, which is what the tests here make. Both halves of that
 * paragraph have since changed. {@code requireActive} was taught the contained principal, so a
 * navigation <em>read</em> is no longer refused there; and of the five provenance columns that kept
 * the mutation path closed, the one that is pure provenance — {@code
 * information_relationships.actor} — now writes from {@code attribution()}, so {@code create} and
 * {@code remove} are open to a contained run in its own home, with {@code
 * information_lock_knowledge}'s contained arm as the whole of the confinement. The four columns
 * that are <em>keys</em> rather than labels stay closed, and {@code
 * ContainedActorWriteReachDatabaseTest} records both. So: the information service gates, navigation
 * reads, navigation relationship writes and the memory gates are open; the bare-handle navigation
 * mutate path and the three guarded repositories are not.
 *
 * <p><strong>The memory half is proved at the store, and the layer above it has since been
 * opened.</strong> {@link #aContainedChildReadsAndWritesItsOwnMemories} drives {@link MemoryStore}
 * and V232's predicate directly. One layer up, {@code BoundMemoryQueries.requireScope} and {@code
 * BoundMemoryWrites.requireRecipient} asked only the {@link ScopedPermissions} question and so
 * refused a contained principal; they now ask {@code ContainedHomes} first. That test's assertion
 * about the role answer is unchanged and still true — it is the SQL fact, and a contained project
 * grants nobody a role — but it is now one of two arms rather than the whole gate.
 */
@Tag("full-db")
@Testcontainers
class ContainedInformationReachDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withUrlParam("sslmode", "disable")
          .withUrlParam("gssEncMode", "disable");

  private static final String HOLDER = "alice";

  private JdbcTemplate jdbc;
  private UnitOfWork work;
  private JdbcInformationCatalogueRepository records;
  private RecordedInformationEvents events;
  private MemoryStore memories;
  private InformationAccess access;
  private ScopedPermissions permissions;

  private long parentProject;
  private UUID parentScope;

  /** Two siblings under one container, so "no sibling reach" is a measurement and not a shape. */
  private Child first;

  private Child second;

  /** One contained Application's durable identity, as this fixture inserts it. */
  private record Child(String project, UUID scope, String principal) {}

  @BeforeEach
  void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var schema = "contained_reach_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(source).execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
    new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
    var properties = new Properties();
    properties.setProperty("currentSchema", schema + ",public");
    source.setConnectionProperties(properties);
    Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
    jdbc = new JdbcTemplate(source);
    work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
    events = new RecordedInformationEvents();
    records = new JdbcInformationCatalogueRepository(jdbc, Clock.systemUTC(), events);
    memories = new MemoryStore(jdbc);
    permissions = permissions();
    // The production containment source over these real rows, not a stand-in: the point of the
    // service-gate test below is that the gate and V229's arms agree about ownership.
    access =
        new InformationAccess(
            permissions,
            io.aeyer.plowshare.server.requests.RequestProjects.EXPLICIT_ONLY,
            io.aeyer.plowshare.server.information.AccountKnowledgePermissions.DENY,
            new io.aeyer.plowshare.server.access.JdbcContainedHomes(jdbc));

    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password) VALUES (?,'fixture',FALSE)",
        HOLDER);
    jdbc.update("INSERT INTO projects(name) VALUES ('parent')");
    parentProject = jdbc.queryForObject("SELECT id FROM projects WHERE name='parent'", Long.class);
    parentScope =
        jdbc.queryForObject(
            "SELECT id FROM authority_scopes WHERE project_id=?", UUID.class, parentProject);
    // MANAGER rather than CONTRIBUTOR, so the audience test has a control that succeeds: the
    // container really can set an audience in its own home, and the child's refusal is therefore
    // about the actor and not about the operation being shut for everybody.
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES (?,?,'MANAGER')",
        parentProject,
        HOLDER);

    first = child();
    second = child();
  }

  /** One contained child of {@code parent}: its project, its CONTAINED scope, its principal. */
  private Child child() {
    String project = "contained:" + UUID.randomUUID();
    jdbc.update("INSERT INTO projects(name) VALUES (?)", project);
    long id = jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, project);
    UUID scope = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO authority_scopes(id,kind,project_id,container) VALUES (?,'CONTAINED',?,?)",
        scope,
        id,
        parentScope);
    jdbc.update("INSERT INTO contained_principals(scope_id) VALUES (?)", scope);
    return new Child(
        project,
        scope,
        jdbc.queryForObject(
                "SELECT id FROM contained_principals WHERE scope_id=?", UUID.class, scope)
            .toString());
  }

  /**
   * {@code EffectiveProjectPermissions} over the one query that decides an ordinary project's role.
   *
   * <p>{@code JdbcProjectMembers.role} issues exactly this statement for a project that is neither
   * a client's nor a personal space, and {@code EffectiveProjectPermissions} maps the role to the
   * three actions exactly as reproduced here. The shortcut is the collaborator graph, not the
   * decision: the answer for every actor in this fixture is {@code authority_project_role}'s.
   */
  private ScopedPermissions permissions() {
    return (principal, target, action) -> {
      String role =
          jdbc.queryForObject(
              "SELECT authority_project_role(?,?)",
              String.class,
              target.value(),
              principal.value());
      if (role == null) return false;
      return switch (action) {
        case READ -> true;
        case WRITE -> !role.equals("VIEWER");
        case SET_AUDIENCE -> role.equals("MANAGER");
      };
    };
  }

  /** The call the run binding makes, for a contained run: the child acting, its container owed. */
  private InformationContext childContext(Child child) {
    return access.forRun(
        InformationActor.contained(child.principal(), HOLDER), Home.of(child.project()));
  }

  /** The same call for an ordinary run, which is the control every refusal is measured against. */
  private InformationContext containerContext() {
    return access.forRun(InformationActor.of(HOLDER), Home.of("parent"));
  }

  private InformationCatalogue.Admission admit(InformationContext context, String name) {
    byte[] bytes = ("body of " + name + " " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    return records.admit(
        context,
        UUID.randomUUID(),
        new InformationCatalogueRepository.AdmissionWrite(
            name,
            bytes,
            "text/markdown",
            null,
            "source",
            List.of(),
            null,
            10,
            0,
            false,
            new DocumentType("document", "markdown"),
            digest(bytes),
            null,
            null,
            true));
  }

  /** The idempotency fingerprint; {@code InformationCatalogue.sha256} is package-private. */
  private static String digest(byte[] bytes) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /**
   * The resolved context, and the child's own corpus read back through it.
   *
   * <p>The write is {@code JdbcInformationCatalogueRepository.admit} and the read is the same
   * class's {@code readable} and {@code authorizedText}, both of which build their SQL from the
   * context's acting axis. So this is one actor writing and then reading, through the gate, rather
   * than two assertions about the same row.
   *
   * <p>The last block is the boundary, asserted rather than implied: the service-layer gates on
   * {@code InformationAccess} refuse this very context. A reader who takes "a child reads and
   * writes its own documents" to mean "a child drives {@code InformationCatalogue}" is wrong, and
   * this is where they find out.
   */
  @Test
  void aContainedChildReadsAndWritesItsOwnDocuments() {
    var context = childContext(first);

    assertEquals(first.principal(), context.acting(), "the gates are asked about the child");
    assertEquals(HOLDER, context.attribution(), "the rows are accounted to its container");
    assertEquals(InformationContext.Scope.PROJECT, context.selection().scope());
    assertEquals(first.project(), context.selection().project());
    assertFalse(context.selection().includeGlobal(), "Global is excluded, deliberately");

    var written = work.inTransaction(() -> admit(context, "child-notes.md"));

    assertEquals(
        first.scope(),
        jdbc.queryForObject(
            "SELECT home_scope_id FROM information_resources WHERE id=?",
            UUID.class,
            written.resource()),
        "a child's document lands on the child's own contained scope");
    assertTrue(
        records.readable(context, written.revision()),
        "and the child reads its own revision back through the repository's own gate");
    var text = records.authorizedText(context, written.revision());
    assertTrue(
        text.isPresent(), "including its bytes, which is a second statement and a second gate");

    // THE BOUNDARY, MOVED. These two gates asked for a live project role, which a contained project
    // grants to nobody, so both refused here. They now consult ContainedHomes first and admit a
    // child to the home it owns; theServiceGatesAdmitAChildToItsOwnHomeOnly is where that is
    // asserted, with a sibling's home and the container's project as the controls. Kept as an
    // admission rather than deleted, so this test still says which layer decides.
    assertDoesNotThrow(() -> access.requireSelection(context));
    assertDoesNotThrow(() -> access.requireWork(context));
    // Audience is unchanged and still refused: ownership of a home is not audience authority.
    assertThrows(CallerFault.class, () -> access.requireAudience(context));
    assertNull(
        jdbc.queryForObject(
            "SELECT authority_project_role(?,?)", String.class, first.project(), first.principal()),
        "which is this SQL fact, unchanged by this task and asserted so it stays unchanged");
    assertNull(
        jdbc.queryForObject(
            "SELECT authority_project_role(?,?)", String.class, first.project(), HOLDER),
        "and it is no role for the container either: a contained project has no PROJECT scope");
  }

  /**
   * The memory half, at the store, and the honest statement of where it stops.
   *
   * <p>V232 gave {@link MemoryStore}'s three identity statements a contained arm, and this drives
   * the store and that predicate. The last block is the layer above: {@code
   * BoundMemoryQueries.requireScope} and {@code BoundMemoryWrites} ask {@link ScopedPermissions}
   * about {@code request.acting()}, which for a contained run is the principal, and the answer is
   * read from {@code authority_project_role} — asserted null here, and still null. That answer was
   * the whole gate when this was written; it is now one of two arms, because those entry points
   * consult {@code ContainedHomes} first. The SQL fact was indeed "the thing a later change would
   * have to move", and the change went around it rather than through it: the role answer is
   * untouched and the admission comes from containment. {@code ContainedMemoryGateTest} drives the
   * dispatch with a zero limit, which is what avoids the embedding pool this assertion could not
   * stand up.
   */
  @Test
  void aContainedChildReadsAndWritesItsOwnMemories() {
    String id = "mem-" + UUID.randomUUID();
    memories.save(memory(id, Home.of(first.project()), first.principal()));

    assertEquals(
        first.scope(),
        jdbc.queryForObject("SELECT home_scope_id FROM memories WHERE id=?", UUID.class, id),
        "a child's memory lands on the child's own contained scope");
    assertEquals(
        first.principal(),
        jdbc.queryForObject("SELECT formed_by FROM memories WHERE id=?", String.class, id),
        "formed by the contained principal, which needs no credential row");

    // V232's own predicate, read and write, for the child's own home and for nobody else's.
    assertTrue(
        jdbc.queryForObject(
            "SELECT contained_memory_actor_allows(?,?,FALSE)",
            Boolean.class,
            first.principal(),
            first.project()));
    assertTrue(
        jdbc.queryForObject(
            "SELECT contained_memory_actor_allows(?,?,TRUE)",
            Boolean.class,
            first.principal(),
            first.project()));
    assertFalse(
        jdbc.queryForObject(
            "SELECT contained_memory_actor_allows(?,?,FALSE)",
            Boolean.class,
            first.principal(),
            second.project()),
        "and not a sibling's, which is what makes the arm a confinement and not an opening");

    // THE SQL FACT THE OLD REFUSAL RESTED ON. Still false, and still worth asserting — but no
    // longer the whole gate: BoundMemoryQueries.requireScope and BoundMemoryWrites.
    // requireRecipient now consult ContainedHomes as a disjunct before asking this, so a contained
    // principal reaches its own home while this answer stays exactly as it was.
    assertFalse(
        permissions.allows(
            new ScopedPermissions.Principal(first.principal()),
            new ScopedPermissions.ProjectTarget(first.project()),
            ScopedPermissions.Action.READ),
        "and no role appears for a contained principal, which is what the dispatch routes around"
            + " rather than changes");
    assertFalse(
        permissions.allows(
            new ScopedPermissions.Principal(first.principal()),
            new ScopedPermissions.ProjectTarget(first.project()),
            ScopedPermissions.Action.WRITE),
        "and so does BoundMemoryWrites");
  }

  /** A container's own document is outside the child, and the container still reads it. */
  @Test
  void aContainedChildReachesNoDocumentOfItsContainer() {
    var parent = containerContext();
    var mine = work.inTransaction(() -> admit(parent, "parent-notes.md"));

    assertTrue(records.readable(parent, mine.revision()), "the control: its owner reads it");
    assertFalse(
        records.readable(childContext(first), mine.revision()),
        "and the child it contains does not, although its container wrote it");
    assertTrue(
        records.authorizedText(childContext(first), mine.revision()).isEmpty(),
        "not by hiding the bytes behind a readable row, but by the same gate");
    assertEquals(
        parentScope,
        jdbc.queryForObject(
            "SELECT home_scope_id FROM information_resources WHERE id=?",
            UUID.class,
            mine.resource()),
        "the home the refusal is about is the container's, which the child's walk never reaches");
  }

  /**
   * Shared is not a route in, and the reason is the selection rather than the audience.
   *
   * <p>A contained context's {@code includeGlobal} is false, so no gate's global arm is asked for;
   * and the arm would refuse anyway, because it wants {@code information_global_read_member}, which
   * is a credentialed membership a bare principal has no row for. Both halves are asserted, because
   * either one alone would be a fence a later change could remove without noticing the other.
   */
  @Test
  void aContainedChildReachesNoGenerallyReadableDocument() {
    var parent = containerContext();
    var shared = work.inTransaction(() -> admit(parent, "handbook.md"));
    work.inTransaction(
        () -> {
          records.share(parent, shared.revision(), true);
          return null;
        });

    assertEquals(
        "GLOBAL_READ",
        audienceOf(shared.resource()),
        "the control: the document's root policy really does say generally readable now");
    assertFalse(
        records.readable(childContext(first), shared.revision()),
        "and the child still does not reach it");
    assertFalse(
        childContext(first).selection().includeGlobal(), "it never asks for the global arm");
    assertFalse(
        jdbc.queryForObject(
            "SELECT information_global_read_member(?)", Boolean.class, first.principal()),
        "and would be refused if it did: a contained principal is no general-read member");
  }

  /** Confinement is per scope, not per container: siblings share a parent and reach nothing. */
  @Test
  void twoSiblingChildrenReachNothingOfEachOther() {
    var one = childContext(first);
    var other = childContext(second);
    var mine = work.inTransaction(() -> admit(one, "first.md"));
    var theirs = work.inTransaction(() -> admit(other, "second.md"));

    assertTrue(records.readable(one, mine.revision()));
    assertTrue(records.readable(other, theirs.revision()));
    assertFalse(records.readable(one, theirs.revision()), "no sibling reach, either direction");
    assertFalse(records.readable(other, mine.revision()));
    assertNotEquals(first.scope(), second.scope());
    assertNotEquals(first.principal(), second.principal());
    // The container's account reaches neither, which is the other thing a shared-account design
    // could have got wrong: attribution is not admission.
    assertFalse(records.readable(containerContext(), mine.revision()));
    assertFalse(records.readable(containerContext(), theirs.revision()));
  }

  /**
   * {@code SET_AUDIENCE} stays refused, at both of the two places it could be allowed.
   *
   * <p>The permission question is shut because a contained project grants no role; the repository's
   * {@code ContainedActorGuard} refuses the operation by name before anything is written. Two
   * independent refusals rather than one, with the container's own successful share beside them so
   * that neither reads as the operation being shut for everybody.
   */
  @Test
  void aContainedChildCannotSetAnAudience() {
    var context = childContext(first);
    var mine = work.inTransaction(() -> admit(context, "child-notes.md"));

    assertThrows(CallerFault.class, () -> access.requireAudience(context));
    assertFalse(
        permissions.allows(
            new ScopedPermissions.Principal(first.principal()),
            new ScopedPermissions.ProjectTarget(first.project()),
            ScopedPermissions.Action.SET_AUDIENCE));

    var refused =
        assertThrows(
            CallerFault.class,
            () ->
                work.inTransaction(
                    () -> {
                      records.share(context, mine.revision(), true);
                      return null;
                    }));
    assertTrue(
        refused.getMessage().startsWith("A confined Application may not "), refused.getMessage());
    assertEquals(
        "HOME",
        audienceOf(mine.resource()),
        "and nothing was published: a contained home is not addressable outward");

    // CONTROL: the same operation by the container in its own home succeeds, so the refusals above
    // are about the actor.
    var parent = containerContext();
    var theirs = work.inTransaction(() -> admit(parent, "handbook.md"));
    access.requireAudience(parent);
    work.inTransaction(
        () -> {
          records.share(parent, theirs.revision(), true);
          return null;
        });
    assertEquals("GLOBAL_READ", audienceOf(theirs.resource()));
  }

  /**
   * The authoritative root audience, which lives on {@code information_resource_policies} and not
   * on the resource row: V158 moved it there and {@code information_set_root_audience} writes
   * {@code HOME} or {@code GLOBAL_READ}. The per-revision {@code visibility} flags left on the old
   * tables are retained historical evidence that no writer may change, so reading them instead
   * would have asserted the wrong column.
   */
  private String audienceOf(UUID resource) {
    return jdbc.queryForObject(
        "SELECT audience FROM information_resource_policies WHERE resource_id=?",
        String.class,
        resource);
  }

  /**
   * The two axes, observed in stored rows — and the axis decision this task had to make.
   *
   * <p>The provenance columns hold the container's account, because their foreign keys target
   * {@code admins} and {@code authority_accounts} and a contained principal has a row in neither.
   * That half was already true of a hand-built contained context; what is new is the <strong>log
   * input</strong> row, and it is where the two axes actually disagreed.
   *
   * <p>{@code InformationTool} checked log ownership against the attribution axis while {@code
   * InformationJobs.reads} re-checked the same log against the acting axis, and {@code bind} stored
   * the acting axis in {@code information_job_inputs.owner_handle}. For one handle that was inert.
   * For a contained run it is not merely a disagreement: the acting axis <strong>cannot</strong>
   * occupy that column, because it is {@code NOT NULL REFERENCES admins(handle)} (V83). The last
   * block proves that by trying it, so "we chose attribution" is a measurement and not a
   * preference. No log guard is widened — {@code information_log_readable} is asked about the
   * account that owns the log, which is what it has always compared.
   */
  @Test
  void aWriteByAContainedChildIsAttributedToItsContainer() {
    var context = childContext(first);
    var written = work.inTransaction(() -> admit(context, "child-notes.md"));

    assertEquals(
        HOLDER,
        jdbc.queryForObject(
            "SELECT owner_handle FROM information_resources WHERE id=?",
            String.class,
            written.resource()),
        "owner_handle is the container's account, never the bare principal");
    assertEquals(
        jdbc.queryForObject(
            "SELECT id FROM authority_accounts WHERE legacy_handle=?", UUID.class, HOLDER),
        jdbc.queryForObject(
            "SELECT created_by FROM information_resources WHERE id=?",
            UUID.class,
            written.resource()));
    assertEquals(
        0,
        (int)
            jdbc.queryForObject(
                "SELECT count(*) FROM information_resources WHERE owner_handle=?",
                Integer.class,
                first.principal()),
        "the bare principal reaches no foreign-keyed provenance column");
    assertEquals(
        List.of(HOLDER),
        events.announced().stream().map(InformationEvents.Occurrence::actor).distinct().toList(),
        "and the announcement is under the accounting account, as its own column requires");

    // THE LOG INPUT ROW: the axis decision, stored.
    String log = "cnv_" + UUID.randomUUID();
    new JdbcJobInformationRepository(jdbc).bind(log, context, List.of(written.revision()));
    assertEquals(
        HOLDER,
        jdbc.queryForObject(
            "SELECT owner_handle FROM information_job_inputs WHERE job_id=?", String.class, log),
        "a contained run's log input is owned by the account that owns the log");
    assertTrue(
        jdbc.queryForObject("SELECT information_log_readable(?,?)", Boolean.class, log, HOLDER),
        "so the log stays readable to its owner, which is the check the tool makes");
    assertFalse(
        jdbc.queryForObject(
            "SELECT information_log_readable(?,?)", Boolean.class, log, first.principal()),
        "and is not readable to the acting principal, which is the disagreement this task settled");

    // AND THE ACTING AXIS COULD NOT HAVE BEEN STORED THERE AT ALL.
    var violated =
        assertThrows(
            org.springframework.dao.DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "INSERT INTO information_job_inputs"
                        + "(job_id,owner_handle,revision_id,scope,project_name,include_shared)"
                        + " VALUES (?,?,?,'project',?,FALSE)",
                    "cnv_" + UUID.randomUUID(),
                    first.principal(),
                    written.revision(),
                    first.project()));
    assertTrue(violated.getMessage().contains("information_job_inputs"), violated.getMessage());
  }

  private Memory memory(String id, Home home, String formedBy) {
    return new Memory(
        id,
        "what the child learned",
        "deployment",
        new Provenance(Instant.now(), formedBy, "contained run"),
        MemoryState.ACTIVE,
        false,
        0,
        null,
        "body of " + id,
        null,
        null,
        null,
        home);
  }

  /**
   * The gate this task opened, driven over real rows by the production source.
   *
   * <p>A child selects and works in its own home; a sibling's home and its container's project are
   * both refused, which is what makes the admission containment's rather than a blanket opening.
   * The audience refusal is asserted beside them because ownership of a home must not become
   * audience authority — V229 admits WRITE for a contained actor and deliberately not SET_AUDIENCE.
   *
   * <p>The control matters: {@code permissions} here is the real {@code authority_project_role}
   * question, and it answers nothing for any of these projects. So every admission below comes from
   * the containment path and none of it from a role that the fixture accidentally granted.
   */
  @Test
  void theServiceGatesAdmitAChildToItsOwnHomeOnly() {
    var actor =
        io.aeyer.plowshare.server.information.InformationActor.contained(first.principal(), HOLDER);
    var own = access.forRun(actor, io.aeyer.plowshare.protocol.Home.of(first.project()));

    assertDoesNotThrow(() -> access.requireSelection(own));
    assertDoesNotThrow(() -> access.requireWork(own));
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class, () -> access.requireAudience(own));

    // A sibling's home: the actor is a live contained principal, so this is the ownership match
    // failing rather than the contained gate failing.
    var sibling = access.forRun(actor, io.aeyer.plowshare.protocol.Home.of(second.project()));
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class, () -> access.requireSelection(sibling));

    // And the container's own project is not a contained home at all.
    assertFalse(
        new io.aeyer.plowshare.server.access.JdbcContainedHomes(jdbc)
            .ownsHome(first.principal(), "parent"));
  }
}
