package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.DocumentType;
import io.aeyer.plowshare.server.archive.ArchiveConfig;
import io.aeyer.plowshare.server.archive.JdbcProjectMembers;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.faults.NotFoundFault;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
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
 * Real PostgreSQL, because every assertion here is the behaviour of a SQL gate, constraint or
 * trigger-resolved home that a contained actor's write meets in one of the five sibling
 * repositories {@code JdbcInformationCatalogueRepository}'s guard does not cover. Each test drives
 * its repository directly, with an {@link InformationContext} whose {@code acting()} is the child's
 * bare principal UUID and whose {@code attribution()} is the container's handle — the one
 * combination {@link InformationAccess#contained} builds and nothing in production builds yet.
 *
 * <p>CHARACTERIZATION (run before any widening): all five refusal assertions failed and the
 * uncontained control passed, so four of the five repositories refused for a reason of their own
 * and one admitted outright. Per repository, what actually happened, and in what order:
 *
 * <ul>
 *   <li>{@code JdbcInformationNavigationRepository} — REFUSED, by an authority predicate. {@code
 *       create} and {@code remove} both recheck through {@code writable}, whose first act is {@code
 *       lockIdentity(acting, project)}: {@code SELECT handle FROM admins WHERE handle=? AND enabled
 *       AND NOT bootstrap FOR SHARE} is empty for a bare UUID, so it raised {@code
 *       NotFoundFault("information is unavailable in this selection")} before any statement wrote.
 *       Nothing was reached; {@code information_relationships.actor}'s foreign key into {@code
 *       admins} (V147:16) sits behind it and was never asked.
 *   <li>{@code JdbcInformationProjectionRepository} — REFUSED, by the write-gate reservation.
 *       {@code prepare} ran {@code capture}, which locks and reads only; {@code
 *       content.requireWrite} inside it <em>admitted</em> the child, because V229's write arm opens
 *       {@code information_lock_knowledge} for a contained principal in its own home. The refusal
 *       came one statement later, from {@code information_reuse_preparations_actor_request_id_fkey}
 *       into {@code information_write_gates(account, request_id)}, as a {@code
 *       DataIntegrityViolationException}.
 *   <li>{@code JdbcAcquisitionRepository} — REFUSED, by a provenance column alone. {@code
 *       submitRetained} asks no authority question at all: it took its advisory lock, found no
 *       prior ticket, and was refused by {@code information_acquisitions_account_fkey} into {@code
 *       admins} (V83:273) as a {@code DataIntegrityViolationException}.
 *   <li>{@code JdbcInformationContentRepository} — REFUSED, by a provenance column alone. {@code
 *       authorise}'s own {@code requireWrite} <em>admitted</em> the child, and only {@code
 *       information_stage_authorizations_actor_fkey} into {@code admins} (V144:9) refused the
 *       insert, as a {@code DataIntegrityViolationException}.
 *   <li>{@code JdbcInformationInterchangeRepository} — <strong>ADMITTED.</strong> {@code
 *       lockDestination} threw nothing. It is keyed on the selection's namespace rather than on an
 *       account, and this repository's one durable write, {@code retain}, takes no context, so
 *       nothing on the portable-import path asked about the acting principal at all.
 * </ul>
 *
 * <p>WHAT THAT DECIDED. Four of the five got the guard: the one that admitted outright
 * (interchange), and the three whose only refusal was a foreign key reached from a column written
 * off the <em>acting</em> axis (acquisition, content, projection). The catalogue repository's own
 * reasoning already calls that shape insufficient for {@code link} — a server fault for a caller's
 * act — and it evaporates the moment such a column is written from {@code attribution()}, which is
 * exactly the change the catalogue's provenance columns have already had. Projection's is the
 * clearest case rather than the exception: a contained run's write gate is the natural thing for
 * the container's account to hold, so that move is likely rather than hypothetical, and {@code
 * capture}'s own {@code requireWrite} had already admitted the child.
 *
 * <p>Navigation alone got no guard, and a Javadoc paragraph instead. {@code lockIdentity} is its
 * mutators' first statement, asks about the acting principal, and answers as a caller-visible
 * {@code NotFoundFault} — an authority predicate, which cannot evaporate the way a foreign key can.
 * A second refusal in front of a sound one is a second thing to keep in step.
 *
 * <p>So the assertions below are asymmetric on purpose. Four name the guard's exact message; the
 * navigation one names the refusal that was already there, and failing it means that refusal has
 * moved and the repository now needs the guard too.
 *
 * <p>Not covered here: whether the service layer above these repositories would have refused a
 * contained actor earlier. It is still unverified, and these are the repositories' own contracts.
 */
@Tag("full-db")
@Testcontainers
class ContainedActorWriteReachDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withUrlParam("sslmode", "disable")
          .withUrlParam("gssEncMode", "disable");

  private static final String HOLDER = "alice";
  private static final String CONFINED = "A confined Application may not ";

  private JdbcTemplate jdbc;
  private UnitOfWork work;
  private JdbcInformationCatalogueRepository records;
  private JdbcInformationNavigationRepository navigation;
  private JdbcInformationProjectionRepository projection;
  private JdbcAcquisitionRepository acquisitions;
  private JdbcInformationContentRepository content;
  private JdbcInformationInterchangeRepository interchange;

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
    var schema = "contained_reach_" + UUID.randomUUID().toString().replace("-", "");
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
    navigation = new JdbcInformationNavigationRepository(jdbc, new JdbcProjectMembers(jdbc));
    content =
        new JdbcInformationContentRepository(jdbc, new JdbcInformationContentTypeRepository(jdbc));
    projection = new JdbcInformationProjectionRepository(jdbc, content);
    acquisitions = new JdbcAcquisitionRepository(jdbc, Clock.systemUTC());
    interchange = new JdbcInformationInterchangeRepository(jdbc);

    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password) VALUES (?,'fixture',FALSE)",
        HOLDER);
    jdbc.update("INSERT INTO projects(name) VALUES ('parent')");
    parentProject = jdbc.queryForObject("SELECT id FROM projects WHERE name='parent'", Long.class);
    parentScope =
        jdbc.queryForObject(
            "SELECT id FROM authority_scopes WHERE project_id=?", UUID.class, parentProject);
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

  /** The acting principal is the child's bare UUID; the accounting account is its container's. */
  private InformationContext contained() {
    return InformationAccess.contained(childPrincipal, HOLDER, childProject).withView(true);
  }

  /** The container's own contributor, which every refusal below is measured against. */
  private InformationContext container() {
    return new InformationContext(HOLDER, InformationAccess.projectSelection(HOLDER, "parent"))
        .withView(true);
  }

  /**
   * A document admitted through the catalogue repository, which is the one write path already
   * guarded and already proven to work for a contained actor in its own home.
   */
  private InformationCatalogue.Admission admit(InformationContext context, String name) {
    byte[] bytes = ("body " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    return work.inTransaction(
        () ->
            records.admit(
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
                    true)));
  }

  private static String digest(byte[] bytes) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** A reserved write gate, which a projection preparation's foreign key requires. */
  private void gate(String account, UUID request) {
    jdbc.update(
        "INSERT INTO information_write_gates(account,request_id,fingerprint,operation,token,lease_until)"
            + " VALUES (?,?,'fixture','projection',?,now()+interval '1 day')",
        account,
        request,
        UUID.randomUUID());
  }

  @Test
  void navigationRefusesAContainedActorsWrite() {
    var source = admit(contained(), "child-source.md");
    var target = admit(contained(), "child-target.md");
    // An edge out of the child's own document, inserted by hand because creating one is the very
    // call under test. Its actor is the container's handle, which is the only kind the column's
    // foreign key admits.
    var edge = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_relationships(id,source,target,relation,actor) VALUES(?,?,?,'mentions',?)",
        edge,
        source.resource(),
        target.resource(),
        HOLDER);

    // No guard here, and this is the assertion that says why: lockIdentity has already refused the
    // acting principal on an admins row it cannot have, as a caller fault, before any write. If
    // this starts admitting, this repository needs ContainedActorGuard.
    var created =
        assertThrows(
            NotFoundFault.class,
            () ->
                work.inTransaction(
                    () ->
                        navigation.create(
                            contained(),
                            UUID.randomUUID(),
                            source.resource(),
                            new io.aeyer.plowshare.protocol.InformationNavigation.Target(
                                target.resource(), null),
                            "mentions",
                            source.revision())));
    assertEquals("information is unavailable in this selection", created.getMessage());

    var removed =
        assertThrows(
            NotFoundFault.class,
            () -> work.inTransaction(() -> navigation.remove(contained(), edge, 1)));
    assertEquals("information is unavailable in this selection", removed.getMessage());
    assertFalse(
        jdbc.queryForObject(
            "SELECT removed FROM information_relationships WHERE id=?", Boolean.class, edge),
        "and the edge the child tried to remove is untouched");
  }

  @Test
  void projectionRefusesAContainedActorsWrite() {
    var document = admit(contained(), "child-projected.md");

    var refused =
        assertThrows(
            CallerFault.class,
            () ->
                work.inTransaction(
                    () ->
                        projection.prepare(
                            contained(), UUID.randomUUID(), document.revision(), null, Map.of())));
    assertEquals(CONFINED + "prepare a metadata revision", refused.getMessage());
    assertEquals(
        0,
        jdbc.queryForObject("SELECT count(*) FROM information_reuse_preparations", Integer.class),
        "and no preparation was retained");
  }

  @Test
  void acquisitionRefusesAContainedActorsWrite() {
    var refused =
        assertThrows(
            CallerFault.class,
            () ->
                work.inTransaction(
                    () ->
                        acquisitions.submitRetained(
                            contained(),
                            UUID.randomUUID(),
                            "a".repeat(64),
                            "https://example.invalid/paper.pdf",
                            "paper.pdf",
                            null,
                            1,
                            "documents",
                            null)));
    assertEquals(CONFINED + "queue a URL for acquisition", refused.getMessage());
    assertEquals(
        0,
        jdbc.queryForObject("SELECT count(*) FROM information_acquisitions", Integer.class),
        "and nothing was queued");
  }

  @Test
  void contentRefusesAContainedActorsWrite() {
    var document = admit(contained(), "child-authorised.md");

    var refused =
        assertThrows(
            CallerFault.class,
            () ->
                work.inTransaction(
                    () -> {
                      content.authorise(
                          contained(), document.revision(), UUID.randomUUID(), List.of("extract"));
                      return null;
                    }));
    assertEquals(CONFINED + "authorise processing stages", refused.getMessage());
    assertEquals(
        0,
        jdbc.queryForObject("SELECT count(*) FROM information_stage_authorizations", Integer.class),
        "and no stage was authorised");
  }

  @Test
  void interchangeRefusesAContainedActorsWrite() {
    var refused =
        assertThrows(
            CallerFault.class,
            () ->
                work.inTransaction(
                    () -> {
                      interchange.lockDestination(contained(), "imported.md");
                      return null;
                    }));
    assertEquals(CONFINED + "import a portable bundle", refused.getMessage());
  }

  /** One write per repository as the container's own contributor: none of this may change. */
  @Test
  void anUncontainedActorsWriteIsUnaffected() {
    var source = admit(container(), "parent-source.md");
    var target = admit(container(), "parent-target.md");

    var relationship =
        work.inTransaction(
            () ->
                navigation.create(
                    container(),
                    UUID.randomUUID(),
                    source.resource(),
                    new io.aeyer.plowshare.protocol.InformationNavigation.Target(
                        target.resource(), null),
                    "mentions",
                    source.revision()));
    assertEquals(
        HOLDER,
        jdbc.queryForObject(
            "SELECT actor FROM information_relationships WHERE id=?",
            String.class,
            relationship.id()));
    assertEquals(
        2,
        work.inTransaction(() -> navigation.remove(container(), relationship.id(), 1)).version());

    var request = UUID.randomUUID();
    gate(HOLDER, request);
    var prepared =
        work.inTransaction(
            () -> projection.prepare(container(), request, source.revision(), null, Map.of()));
    assertEquals(source.revision(), prepared.manifest().revision());

    var ticket =
        work.inTransaction(
            () ->
                acquisitions.submitRetained(
                    container(),
                    UUID.randomUUID(),
                    "b".repeat(64),
                    "https://example.invalid/paper.pdf",
                    "paper.pdf",
                    null,
                    1,
                    "documents",
                    null));
    assertEquals(HOLDER, ticket.account());

    work.inTransaction(
        () -> {
          content.authorise(container(), target.revision(), UUID.randomUUID(), List.of("extract"));
          return null;
        });
    assertEquals(
        HOLDER,
        jdbc.queryForObject(
            "SELECT actor FROM information_stage_authorizations WHERE revision_id=?",
            String.class,
            target.revision()));

    work.inTransaction(
        () -> {
          interchange.lockDestination(container(), "imported.md");
          return null;
        });
  }
}
