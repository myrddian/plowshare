package io.aeyer.plowshare.server.applications;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.DocumentType;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.MemoryState;
import io.aeyer.plowshare.protocol.Provenance;
import io.aeyer.plowshare.server.archive.ArchiveConfig;
import io.aeyer.plowshare.server.archive.MemoryStore;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.information.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * What a running child can actually write, through the real Java paths rather than through the SQL
 * gates alone.
 *
 * <h2>Why this needs a real database</h2>
 *
 * <p>V224 opened the knowledge write gates for a contained principal and {@code
 * ContainedActorWriteSchemaTest} proved it at the SQL level — but that test had to insert its rows
 * by hand with {@code owner_handle} NULL, because that column's foreign key targets {@code admins}
 * and {@code information_resources.created_by}'s targets {@code authority_accounts}, and a
 * contained principal has a row in neither by design (V218). So whether the <em>Java</em> write
 * path works as a child was genuinely unknown: the one caller of the widened gate is {@code
 * JdbcInformationCatalogueRepository}, and until {@code application.child.run} nothing constructed
 * an {@link InformationContext} for a contained principal at all. Both assertions here are
 * PostgreSQL's own — a column constraint and a trigger-resolved home — so neither can be made
 * against a mock.
 *
 * <h2>What it found</h2>
 *
 * <p><strong>The knowledge write path does not work as a child, and this test pins why.</strong>
 * {@code admit} writes {@code owner_handle} from {@code context.account()} and {@code created_by}
 * from {@code (SELECT id FROM authority_accounts WHERE legacy_handle=?)} on the same value, with
 * {@code creator_evidence='authenticated_admission'} alongside. For a bare-UUID principal the first
 * violates a foreign key into {@code admins} and the second resolves NULL, which V157's {@code
 * CHECK ((created_by IS NULL)=(creator_evidence='unknown'))} then refuses. The gate V224 widened is
 * reached and admits; what refuses is the provenance the insert writes afterwards. That is an
 * authority decision about how a credential-less principal is attributed, not a read decision, and
 * it is recorded as a gap for the slice to rule on rather than worked around here.
 *
 * <p>There are in fact <strong>two</strong> knowledge refusals and they are different facts. The
 * selection every ordinary caller builds — {@code InformationContext.Selection.project} — sets
 * {@code includeGlobal}, and the widened gate does not admit a contained actor through the global
 * arm, so the write is refused at the destination before a row is attempted. With the global arm
 * off the gate admits, and then the provenance above refuses. A fix has to answer both.
 *
 * <p><strong>A third gap decides whether a started run can begin at all</strong>, and is asserted
 * here for the same reason: {@code Runs.start} requires a project role for its caller, and {@code
 * authority_project_role} resolves none for a bare-UUID principal. It is the same absence of an
 * {@code authority_accounts} row, met by a different gate.
 *
 * <p><strong>The memory write path does work as a child</strong>, which is the other half of ADR
 * 0042's writable home and was untested in slice two. {@code memories.formed_by} is free text with
 * no foreign key, and V157's {@code memory_knowledge_home} trigger resolves the home from the
 * project, so a memory formed by the principal in the child's own project lands on the child's
 * contained scope and on nothing else.
 */
@Tag("full-db")
@Testcontainers
class ContainedExecutionWriteDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withUrlParam("sslmode", "disable")
          .withUrlParam("gssEncMode", "disable");

  private static final String HOLDER = "alice";

  private JdbcTemplate jdbc;
  private UnitOfWork work;
  private JdbcInformationCatalogueRepository records;
  private MemoryStore memories;

  private long parentProject;
  private UUID parentScope;
  private UUID childScope;
  private String childProject;
  private String childPrincipal;

  @BeforeEach
  void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var schema = "contained_exec_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(source).execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
    new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
    var properties = new Properties();
    properties.setProperty("currentSchema", schema + ",public");
    source.setConnectionProperties(properties);
    Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
    jdbc = new JdbcTemplate(source);
    work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
    records =
        new JdbcInformationCatalogueRepository(
            jdbc, Clock.systemUTC(), new RecordedInformationEvents());
    memories = new MemoryStore(jdbc);

    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password) VALUES (?,'fixture',FALSE)",
        HOLDER);
    jdbc.update("INSERT INTO projects(name) VALUES ('parent')");
    parentProject = jdbc.queryForObject("SELECT id FROM projects WHERE name='parent'", Long.class);
    parentScope =
        jdbc.queryForObject(
            "SELECT id FROM authority_scopes WHERE project_id=?", UUID.class, parentProject);
    // The container's own contributor: the control every refusal below is measured against.
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES (?,?,'CONTRIBUTOR')",
        parentProject,
        HOLDER);

    childProject = "contained:" + UUID.randomUUID();
    jdbc.update("INSERT INTO projects(name) VALUES (?)", childProject);
    long childProjectId =
        jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, childProject);
    childScope = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO authority_scopes(id,kind,project_id,container) VALUES (?,'CONTAINED',?,?)",
        childScope,
        childProjectId,
        parentScope);
    jdbc.update("INSERT INTO contained_principals(scope_id) VALUES (?)", childScope);
    childPrincipal =
        jdbc.queryForObject(
                "SELECT id FROM contained_principals WHERE scope_id=?", UUID.class, childScope)
            .toString();
  }

  /** Exactly what an ordinary caller presents: {@code Selection.project} sets includeGlobal. */
  private InformationContext context(String actor, String project) {
    return new InformationContext(actor, InformationAccess.projectSelection(actor, project))
        .withView(true);
  }

  /** The same selection with the global arm off, which is the only one V224's write arm admits. */
  private InformationContext scopeBound(String actor, String project) {
    return new InformationContext(
            actor,
            new InformationContext.Selection(InformationContext.Scope.PROJECT, project, false))
        .withView(true);
  }

  private InformationCatalogue.Admission admit(InformationContext context, String name) {
    byte[] bytes = ("body " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
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
   * The carried requirement: a knowledge write through the real Java path, as the child.
   *
   * <p>The control comes first, so neither refusal below can be read as a broken fixture: the
   * container's own contributor admits a document through the identical call and the identical
   * repository, and its {@code owner_handle} and {@code created_by} are asserted so that what the
   * path <em>does</em> write is on the record beside what it cannot.
   *
   * <p>Two distinct refusals follow, and they are different facts. The first is the selection every
   * ordinary caller presents — {@code InformationContext.Selection.project} sets {@code
   * includeGlobal} — which the widened gate does not admit for a contained actor, so the write is
   * refused before any row is attempted. The second is with the global arm off, which the gate does
   * admit: there the insert's own provenance columns refuse, because a contained principal has no
   * {@code admins} row for {@code owner_handle} and no {@code authority_accounts} row for {@code
   * created_by}.
   */
  @Test
  void theJavaKnowledgeWritePathCannotYetWriteAsAChild() {
    var admitted = work.inTransaction(() -> admit(context(HOLDER, "parent"), "notes.md"));
    assertEquals(
        HOLDER,
        jdbc.queryForObject(
            "SELECT owner_handle FROM information_resources WHERE id=?",
            String.class,
            admitted.resource()),
        "the control writes the caller's own handle as owner_handle");
    assertEquals(
        jdbc.queryForObject(
            "SELECT id FROM authority_accounts WHERE legacy_handle=?", UUID.class, HOLDER),
        jdbc.queryForObject(
            "SELECT created_by FROM information_resources WHERE id=?",
            UUID.class,
            admitted.resource()),
        "and created_by resolved from the same value through authority_accounts");

    // Finding one: the ordinary selection carries the global arm, and the widened write gate does
    // not admit a contained actor through it. Asserted on the gate itself first, so the Java
    // refusal below is located rather than merely observed.
    assertFalse(
        jdbc.queryForObject(
            "SELECT information_lock_destination(?,'project',?,TRUE)",
            Boolean.class,
            childPrincipal,
            childProject),
        "the global arm stays closed to a contained actor, as V224 intends");
    assertThrows(
        io.aeyer.plowshare.server.faults.NotFoundFault.class,
        () ->
            work.inTransaction(() -> admit(context(childPrincipal, childProject), "global-arm.md")),
        "so the selection an ordinary caller builds is refused at the destination gate");

    // Finding two: with the global arm off the gate admits, and the insert's provenance refuses.
    assertTrue(
        jdbc.queryForObject(
            "SELECT information_lock_destination(?,'project',?,FALSE)",
            Boolean.class,
            childPrincipal,
            childProject),
        "the scope-bound destination is admitted, which is what V224 opened");
    var refused =
        assertThrows(
            DataAccessException.class,
            () ->
                work.inTransaction(
                    () -> admit(scopeBound(childPrincipal, childProject), "child-notes.md")));
    var cause = refused.getMostSpecificCause().getMessage();
    // V157's CHECK ((created_by IS NULL)=(creator_evidence='unknown')) on information_resources.
    // It fires first because PostgreSQL evaluates a CHECK before the foreign key, and the row it
    // names shows exactly why: created_by is NULL -- the subselect on authority_accounts found no
    // row for the bare UUID -- while the insert wrote 'authenticated_admission' beside it. The
    // owner_handle foreign key into admins is the second refusal behind it and is not reached.
    assertTrue(cause.contains("information_resources_check"), cause);
    assertTrue(cause.contains("authenticated_admission"), cause);
    assertTrue(
        cause.contains(childPrincipal), "and owner_handle carried the bare principal: " + cause);
    assertEquals(
        0,
        (int)
            jdbc.queryForObject(
                "SELECT count(*) FROM information_resources WHERE source_name IN"
                    + " ('child-notes.md','global-arm.md')",
                Integer.class),
        "and nothing half-written survives either refusal");
  }

  /**
   * The other carried requirement, and the half that works: a child forms a memory in its own home.
   * {@code memories} has no actor-keyed SQL gate, so what is asserted is the home the shipped
   * trigger resolves — the child's contained scope, from the child's own project, with the
   * principal recorded as the former.
   */
  @Test
  void aChildWritesAMemoryInItsOwnHome() {
    String id = "mem-" + UUID.randomUUID();
    memories.save(memory(id, Home.of(childProject)));

    assertEquals(
        childScope,
        jdbc.queryForObject("SELECT home_scope_id FROM memories WHERE id=?", UUID.class, id),
        "a child's memory lands on the child's own contained scope");
    assertEquals(
        childPrincipal,
        jdbc.queryForObject("SELECT formed_by FROM memories WHERE id=?", String.class, id),
        "formed by the contained principal, which needs no credential row");
    assertEquals(
        childProject,
        jdbc.queryForObject(
            "SELECT p.name FROM memories m JOIN projects p ON p.id=m.project_id WHERE m.id=?",
            String.class,
            id));

    // Control: the same store, the same former, a memory in the container's project lands on the
    // container's scope — so the assertion above is about the home the trigger chose and not about
    // the only home it could have chosen.
    String outside = "mem-" + UUID.randomUUID();
    memories.save(memory(outside, Home.of("parent")));
    assertEquals(
        parentScope,
        jdbc.queryForObject("SELECT home_scope_id FROM memories WHERE id=?", UUID.class, outside));
    assertNotEquals(childScope, parentScope);
  }

  /**
   * The third gap this slice has to rule on, and the one that decides whether a started run can
   * actually begin: {@code Runs.start} calls {@code CallerAccess.requireWork(project, handle)},
   * which resolves a project role for the caller. For a contained principal there is none, because
   * {@code authority_project_role} (V156) joins {@code authority_accounts} on {@code legacy_handle}
   * and a bare UUID has no row there — the same absence that refuses the knowledge write's {@code
   * created_by} above.
   *
   * <p>Asserted here rather than described, with the container's own contributor as the control, so
   * that the gap is evidence and not a reading of Java source. {@code application.child.run}
   * decides everything up to the start; what the live run service then does with this caller is
   * this fact.
   */
  @Test
  void aContainedPrincipalResolvesNoProjectRoleForItsOwnHome() {
    assertEquals(
        "CONTRIBUTOR",
        jdbc.queryForObject("SELECT authority_project_role('parent',?)", String.class, HOLDER),
        "control: an ordinary member does resolve a role, so the null below is not a dead fixture");
    assertNull(
        jdbc.queryForObject(
            "SELECT authority_project_role(?,?)", String.class, childProject, childPrincipal),
        "a contained principal holds no project role in its own home");
    assertNull(
        jdbc.queryForObject(
            "SELECT service_project_role(?,?)", String.class, childProject, childPrincipal),
        "and none as a service token either: its identifier is not an @service/ handle");
    assertEquals(
        0,
        (int)
            jdbc.queryForObject(
                "SELECT count(*) FROM authority_accounts WHERE legacy_handle=?",
                Integer.class,
                childPrincipal),
        "because it has no authority account at all, which is V218's design");
  }

  private Memory memory(String id, Home home) {
    return new Memory(
        id,
        "what the child learned",
        "deployment",
        new Provenance(Instant.now(), childPrincipal, "contained run"),
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
}
