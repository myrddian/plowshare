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
 *   <li>{@code JdbcInformationNavigationRepository} — RELATIONSHIP WRITES AND READS NOW ADMITTED,
 *       the rest of the repository's writes still refused. The characterization's refusal was
 *       {@code writable}'s first act, {@code lockIdentity(acting, project)}: {@code SELECT handle
 *       FROM admins WHERE handle=? AND enabled AND NOT bootstrap FOR SHARE} is empty for a bare
 *       UUID. What made that refusal worth keeping at the time was the five provenance columns
 *       below it, each {@code REFERENCES admins(handle)}, which would have turned an admitted lock
 *       into a constraint violation. One of the five — {@code information_relationships.actor}
 *       (V147:16) — is pure provenance, keyed by nothing, and now writes from {@code
 *       InformationContext.attribution}, so {@code lockIdentity} takes the context and holds the
 *       container's row for a contained write. The other four stay closed on purpose and not for
 *       want of routing: {@code information_navigation_policies.actor} is policy management, and
 *       the {@code account} of {@code information_navigation_views}, {@code
 *       information_navigation_receipts} and {@code information_navigation_audit} is a key rather
 *       than a label, so attributing them would merge a child's saved views and request identifiers
 *       into its container's namespace. Authority was never what refused here: {@code
 *       information_lock_knowledge(…,'WRITE')} reaches {@code information_home_allows}, whose
 *       contained arm (V229:285) admits the child for its own home and only for WRITE — which is
 *       now the whole of the confinement on this path, pinned by {@link
 *       #aContainedActorStillCannotWriteIntoItsContainersProject}. Liveness is a separate question
 *       that {@code requireActive} answers, which was the whole of what a navigation <em>read</em>
 *       needed; {@link #navigationLivenessAdmitsAContainedPrincipalOnItsOwnArm} holds that half.
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
 * <p>Navigation alone got no guard, and a Javadoc paragraph instead. Its refusal was a
 * caller-visible {@code NotFoundFault} from a predicate, not a foreign key, so it could not
 * evaporate the way the other three could — and when the relationship path was opened, the
 * predicate that replaced the lock's refusal was the one already admitting the child for its own
 * home, so there was still nothing for a guard to add.
 *
 * <p>So the assertions below are asymmetric on purpose. Four name the guard's exact message; the
 * navigation ones name what the repository's own SQL decides, and the confinement assertion is the
 * line that would fail if {@code information_home_allows} ever widened.
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

  /**
   * The navigation write path, now open to a contained run in its own home.
   *
   * <p>Real PostgreSQL is required and not a convenience here: every one of the three facts below
   * is a property of the database. That {@code create} now reaches its insert is a {@code FOR
   * SHARE} lock resolving against a row the acting principal does not own; that the insert lands at
   * all is {@code information_relationships_actor_fkey} being satisfied by the attribution handle;
   * and that a write outside the child's home is still refused is {@code
   * information_lock_knowledge}'s contained arm (V229:285) reading a trigger-resolved {@code
   * home_scope_id}. None of the three is observable through a mock of this repository, because all
   * three are the repository's SQL.
   */
  @Test
  void navigationAdmitsAContainedActorsWriteInItsOwnHomeAccountedToItsContainer() {
    var source = admit(contained(), "child-source.md");
    var target = admit(contained(), "child-target.md");
    var edge = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_relationships(id,source,target,relation,actor) VALUES(?,?,?,'mentions',?)",
        edge,
        source.resource(),
        target.resource(),
        HOLDER);

    // ADMITTED. lockIdentity's contained branch holds the container's admins row and tests the
    // child's liveness, and information_lock_knowledge's contained arm supplies the authority.
    var created = UUID.randomUUID();
    var relationship =
        work.inTransaction(
            () ->
                navigation.create(
                    contained(),
                    created,
                    source.resource(),
                    new io.aeyer.plowshare.protocol.InformationNavigation.Target(
                        target.resource(), null),
                    "mentions",
                    source.revision()));
    assertEquals(created, relationship.id());
    assertEquals(1, relationship.version());

    // The row the child wrote is accounted to its container, which is the only value
    // information_relationships.actor's foreign key into admins(handle) can hold. This is the
    // assertion that would have been a DataIntegrityViolationException had the insert kept using
    // the acting axis.
    assertEquals(
        HOLDER,
        jdbc.queryForObject(
            "SELECT actor FROM information_relationships WHERE id=?", String.class, created),
        "the child's edge is attributed to its container");

    // And removal, which writes no provenance of its own but meets the same lock.
    assertEquals(2, work.inTransaction(() -> navigation.remove(contained(), edge, 1)).version());
    assertTrue(
        jdbc.queryForObject(
            "SELECT removed FROM information_relationships WHERE id=?", Boolean.class, edge));
  }

  /**
   * The confinement the admission above rests on: opening the lock did not open the home.
   *
   * <p>{@code lockContainedIdentity} ignores the resource's project — a {@code contained:} project
   * has no members to serialize against — so the whole of what keeps the child inside its own home
   * on this path is {@code information_lock_knowledge}'s contained arm. If that arm ever widened,
   * this is the test that fails, and the repository would then need {@code ContainedActorGuard}
   * after all.
   */
  @Test
  void aContainedActorStillCannotWriteIntoItsContainersProject() {
    var ours = admit(contained(), "child-own.md");
    var theirs = admit(container(), "parent-own.md");

    var refused =
        assertThrows(
            NotFoundFault.class,
            () ->
                work.inTransaction(
                    () ->
                        navigation.create(
                            contained(),
                            UUID.randomUUID(),
                            theirs.resource(),
                            new io.aeyer.plowshare.protocol.InformationNavigation.Target(
                                ours.resource(), null),
                            "mentions",
                            theirs.revision())));
    assertEquals("information is unavailable in this selection", refused.getMessage());
    assertEquals(
        0,
        (long)
            jdbc.queryForObject(
                "SELECT count(*) FROM information_relationships WHERE source=?",
                Long.class,
                theirs.resource()),
        "and nothing was written out of the container's document");

    // Asked of the predicate directly, so the refusal above is pinned to this arm rather than
    // inferred from a message two gates share. The same call, the same acting principal and the
    // same selection: true for the child's own revision, false for its container's.
    assertTrue(write(ours.revision()), "the child's own home is writable");
    assertFalse(write(theirs.revision()), "its container's project is not");
  }

  /** {@code information_lock_knowledge(…,'WRITE')} as {@code writable} asks it, for the child. */
  private boolean write(UUID revision) {
    return work.inTransaction(
        () ->
            jdbc.queryForObject(
                "SELECT information_lock_knowledge(?,?,'project',?,false,'WRITE')",
                Boolean.class,
                revision,
                childPrincipal,
                childProject));
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

  /**
   * Navigation liveness, on the arm that answers for a contained principal.
   *
   * <p>{@code DocumentNavigation.read} calls {@code requireActive} and nothing else about the
   * actor, so this is the whole of what a contained run needed to reach a navigation read — the
   * scoping below it is V229's and V231's. {@code requireActive} asked only {@code admins}, and a
   * contained principal has no row there by design (V218); for such a principal existence is
   * liveness, because V218 gives {@code contained_principals} no revocation column and the row dies
   * with its scope.
   *
   * <p>The same predicate is now {@code lockContainedIdentity}'s liveness test on the relationship
   * write path, which is why it is asserted here as an existence test rather than a shape test: the
   * two refusals below are what keep a well-formed UUID that is nobody, and a handle that is no
   * account, out of both.
   *
   * <p>What {@code lockAccount(String)} still refuses is a separate path and a separate reason.
   * {@code DocumentNavigation.mutate} and {@code PortableInformation.commit} take a bare handle,
   * have no second axis to consult, and write the three {@code account} columns that are keys
   * rather than labels — saved views, receipts and audit — so there is nothing there to re-point.
   */
  @Test
  void navigationLivenessAdmitsAContainedPrincipalOnItsOwnArm() {
    // The container's own account: unchanged, and the control that says the admins arm still works.
    assertDoesNotThrow(() -> active(HOLDER));

    // The child's own principal: admitted on the contained arm, which is new.
    assertDoesNotThrow(() -> active(childPrincipal));

    // A UUID that is no live contained principal, and a handle that is no account: both refused, so
    // the new arm is an existence test and not a shape test.
    assertThrows(NotFoundFault.class, () -> active(UUID.randomUUID().toString()));
    assertThrows(NotFoundFault.class, () -> active("nobody"));
  }

  /** requireActive in its own transaction, as DocumentNavigation.read calls it. */
  private void active(String account) {
    work.inTransaction(
        () -> {
          navigation.requireActive(account);
          return null;
        });
  }
}
