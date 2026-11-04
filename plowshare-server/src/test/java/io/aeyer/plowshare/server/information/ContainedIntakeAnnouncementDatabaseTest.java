package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.DocumentType;
import io.aeyer.plowshare.server.archive.ArchiveConfig;
import io.aeyer.plowshare.server.archive.JdbcProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.relay.DurableRelay;
import io.aeyer.plowshare.server.relay.JdbcRelayConsumerRepository;
import io.aeyer.plowshare.server.relay.JdbcRelayDeliveryRepository;
import io.aeyer.plowshare.server.relay.JdbcRelayPayloadStore;
import io.aeyer.plowshare.server.relay.JdbcRelayRepository;
import io.aeyer.plowshare.server.relay.JdbcRelaySubscriptionAuthority;
import io.aeyer.plowshare.server.relay.ReadTierRelayConsumption;
import io.aeyer.plowshare.server.relay.Relay;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The intake announcement against a home with no Relay audience, through the <strong>real</strong>
 * event publisher.
 *
 * <h2>The defect this class is the regression test for</h2>
 *
 * <p>V224 admits a contained principal to its own knowledge home, and {@code
 * ContainedExecutionWriteDatabaseTest} proved a child's write works through the real Java path —
 * against {@code RecordedInformationEvents}, a fake that appends announcements to a list. Under
 * production wiring the same write failed. A contained scope has no Relay audience by ADR 0042's
 * own decision, {@code JdbcInformationEventRecords.resolve} raises {@link
 * InformationEventRecords.Unaddressable} for such a home, and {@link InformationEvents} documents
 * that exception as deliberately caught nowhere — so every knowledge write a child made would have
 * thrown and rolled back.
 *
 * <p>That is the second time in this capability that a fake carried a claim the composed system
 * would have refused; the first was a mocked {@code OperationRecords} hiding that a containment hop
 * had never persisted. So this class is composed the way production composes it: real {@code
 * DurableRelay} over {@code JdbcRelayRepository}, real {@code JdbcInformationEventRecords}, real
 * {@code RelayInformationEvents}, real {@code JdbcInformationCatalogueRepository}. Nothing between
 * the write and the broker is stubbed, because the gap being closed lived exactly there.
 *
 * <h2>What makes the fix narrow, and the assertion that holds it narrow</h2>
 *
 * <p>The tolerance is keyed on the home the pipeline has already declined — a contained home, by
 * the one predicate {@code declinedHome} already uses — and not on the exception type. A blanket
 * catch would sit on the path every knowledge write in this server takes and would turn an ordinary
 * home that lost its audience through misconfiguration into silence. {@link
 * #anOrdinaryHomeWithNoRelayAudienceStillSurfacesTheRefusal()} is the assertion that stops this fix
 * becoming a bug-hider, and {@link #theToleranceIsKeyedOnTheDeclinedHomeAndNotOnTheException()} is
 * the one that shows the keying is what decides it.
 *
 * <p>The deep fix — giving a contained scope a real Relay audience by keying Relay topics on {@code
 * authority_scopes.id} — remains ADR 0039's deferred question and is not answered here.
 */
@Tag("full-db")
@Testcontainers
class ContainedIntakeAnnouncementDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  /** The container's own account: every provenance column a knowledge write fills. */
  private static final String HOLDER = "holder";

  private static final Instant T0 = Instant.parse("2026-11-04T09:00:00Z");

  private JdbcTemplate jdbc;
  private UnitOfWork work;
  private JdbcInformationCatalogueRepository records;
  private JdbcInformationEventRecords audiences;

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
    var schema = "contained_intake_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(source).execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
    new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
    var properties = new Properties();
    properties.setProperty("currentSchema", schema + ",public");
    source.setConnectionProperties(properties);
    Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
    jdbc = new JdbcTemplate(source);
    var manager = new DataSourceTransactionManager(source);
    work = new ArchiveConfig().unitOfWork(manager);

    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password) VALUES (?,'fixture',FALSE)",
        HOLDER);
    jdbc.update("INSERT INTO projects(name,workspace) VALUES ('parent','/tmp/parent')");
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

    // Production's own composition, from the broker upwards. The catalogue repository is handed a
    // real RelayInformationEvents, which is the whole point of this class.
    audiences = new JdbcInformationEventRecords(jdbc);
    var broker =
        new JdbcRelayRepository(
            jdbc,
            work,
            new JdbcRelayPayloadStore(
                jdbc, work, Duration.ofHours(1), Duration.ofHours(24), Duration.ofDays(30)));
    Relay relay = new DurableRelay(broker, () -> T0);
    var consumption =
        new ReadTierRelayConsumption(
            new JdbcProjectMembers(jdbc),
            new Workspaces(),
            relay,
            new JdbcRelayConsumerRepository(
                jdbc, work, new JdbcRelayDeliveryRepository(jdbc, work), broker),
            new JdbcRelaySubscriptionAuthority(jdbc),
            Duration.ofHours(12),
            () -> T0);
    records =
        new JdbcInformationCatalogueRepository(
            jdbc,
            Clock.systemUTC(),
            new RelayInformationEvents(audiences, relay, consumption, new Workspaces(), () -> T0));
  }

  /**
   * Real project rows: the personal-owner alias a home scope's audience depends on is not stubbed.
   */
  private final class Workspaces implements ProjectWorkspaces {
    @Override
    public Optional<ProjectRecord> find(String name) {
      throw new UnsupportedOperationException("this fixture needs identity, not workspaces");
    }

    @Override
    public Long id(String name) {
      return jdbc
          .query("SELECT id FROM projects WHERE name=?", (row, index) -> row.getLong(1), name)
          .stream()
          .findFirst()
          .orElse(null);
    }

    @Override
    public Optional<String> personalOwner(String name) {
      return jdbc
          .query(
              "SELECT personal_owner FROM projects WHERE name=?",
              (row, index) -> row.getString(1),
              name)
          .stream()
          .findFirst()
          .map(Optional::ofNullable)
          .orElse(Optional.empty());
    }

    @Override
    public List<java.nio.file.Path> effectiveExclusions(ProjectRecord project) {
      throw new UnsupportedOperationException("this fixture needs identity, not workspaces");
    }
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
            InformationCatalogue.sha256(bytes),
            null,
            null,
            true));
  }

  /** An ordinary caller's own project selection, with the global arm off as V224's arm requires. */
  private InformationContext ordinary() {
    return new InformationContext(
            HOLDER,
            new InformationContext.Selection(InformationContext.Scope.PROJECT, "parent", false))
        .withView(true);
  }

  /** The contained combination: the child's principal acting, its container accountable. */
  private InformationContext contained() {
    return InformationAccess.contained(childPrincipal, HOLDER, childProject).withView(true);
  }

  /**
   * The claim Task 2 made against a fake, made here against the publisher production wires.
   *
   * <p>The assertions are what the rows carry rather than the absence of a throw, because "it did
   * not fail" is not an observation: the resource lands in the child's own home, its provenance is
   * the container's account, and every processing stage is {@code skipped} because the pipeline
   * declines a confined Application's documents.
   */
  @Test
  void aContainedWriteCompletesThroughTheRealEventPublisher() {
    var admitted = work.inTransaction(() -> admit(contained(), "child-notes.md"));

    assertEquals(
        childScope,
        jdbc.queryForObject(
            "SELECT home_scope_id FROM information_resources WHERE id=?",
            UUID.class,
            admitted.resource()),
        "the document landed in the child's own home");
    assertEquals(
        HOLDER,
        jdbc.queryForObject(
            "SELECT owner_handle FROM information_resources WHERE id=?",
            String.class,
            admitted.resource()),
        "provenance is the container's account, as every FK-bearing column requires");
    assertNotNull(
        jdbc.queryForObject(
            "SELECT created_by FROM information_resources WHERE id=?",
            UUID.class,
            admitted.resource()));
    assertEquals(
        List.of("skipped"),
        jdbc.queryForList(
            "SELECT DISTINCT state FROM information_steps WHERE revision_id=?",
            String.class,
            admitted.revision()),
        "every stage is skipped: the pipeline declines a confined Application's documents");
    // And the write is committed and readable back, so the transaction was never poisoned by the
    // refusal the publisher raised inside it.
    assertEquals(
        "child-notes.md",
        jdbc.queryForObject(
            "SELECT title FROM information_revisions WHERE id=?",
            String.class,
            admitted.revision()));

    // The honest cost of the narrow fix, asserted rather than left to be discovered: a contained
    // home has no audience, so nothing is announced for it. The operator-legible record of the
    // decline is the skipped stage rows above, which is what it was before this fix as well.
    assertEquals(List.of(), records.revisionEvents(admitted.revision()));
  }

  /**
   * Same-call positive control: an ordinary home in an addressable project still announces, exactly
   * as it did before the tolerance existed.
   *
   * <p>Observed as an effect — the Relay publication's own history row, written by {@code
   * InformationEventRecords.append} inside the publishing transaction — and not as the absence of a
   * throw.
   */
  @Test
  void anOrdinaryWriteStillAnnouncesItsIntake() {
    var admitted = work.inTransaction(() -> admit(ordinary(), "parent-notes.md"));

    var announced = records.revisionEvents(admitted.revision());
    assertEquals(1, announced.size(), "the intake is announced exactly once");
    assertEquals("intake", announced.getFirst().stage());
    assertEquals("retained", announced.getFirst().action());
    // The audience it went to is the project's own, which is what makes this an addressable home.
    assertEquals(
        new Relay.ProjectScope(parentProject),
        work.inTransaction(() -> audiences.resolve(admitted.revision())).scope());
  }

  /**
   * <strong>The assertion that keeps the fix narrow, link one of two: behavioural.</strong> On a
   * real home with no Relay audience, the refusal surfaces when the flag is false and is tolerated
   * when it is true. Nothing differs between the two calls but the flag, so this isolates the
   * narrowing from the exception type — making the tolerance unconditional makes the first
   * assertion fail, which is the mutation recorded in the task report.
   *
   * <p><strong>Why the home here is the contained one, and what that bounds.</strong> The schema
   * will not let a fixture manufacture an <em>ordinary</em> unaddressable home, and that is
   * deliberate rather than inconvenient: {@code information_knowledge_identity} refuses a home
   * transfer outright ("knowledge home transfer is not supported"), and every project home is
   * addressable by construction because {@code audienceProject} answers a PROJECT scope with its
   * own {@code project_id}. The non-contained homes with no audience are the built-in knowledge
   * scope and an account knowledge home whose owner has no personal project — both real shapes,
   * both pinned in {@code InformationEventAudienceMappingTest}, and neither reachable by a write
   * this fixture can perform without building a Global knowledge grant. So this test shows that the
   * flag decides, and {@link #theFlagIsFalseForEveryHomeButAContainedOne()} shows that an ordinary
   * home always supplies {@code false}. Two links, stated as two rather than claimed as one.
   */
  @Test
  void anUnaddressableHomeSurfacesTheRefusalUnlessTheHomeIsADeclinedOne() {
    var admitted = work.inTransaction(() -> admit(contained(), "child-notes.md"));

    // CONTROL FIRST, so neither assertion below can pass for want of a resolvable revision: the
    // container's own home does resolve an audience through the very same publisher path.
    var ordinary = work.inTransaction(() -> admit(ordinary(), "parent-notes.md"));
    assertDoesNotThrow(
        () ->
            work.inTransaction(
                () -> {
                  records.announceIntake(false, ordinary.revision(), HOLDER, "retained", "");
                  return null;
                }));

    var refused =
        assertThrows(
            InformationEventRecords.Unaddressable.class,
            () ->
                work.inTransaction(
                    () -> {
                      records.announceIntake(false, admitted.revision(), HOLDER, "retained", "");
                      return null;
                    }),
            "a home with no audience must surface its refusal when the flag says nothing declined");
    assertTrue(refused.getMessage().contains("CONTAINED"), refused.getMessage());

    assertDoesNotThrow(
        () ->
            work.inTransaction(
                () -> {
                  records.announceIntake(true, admitted.revision(), HOLDER, "retained", "");
                  return null;
                }),
        "a declined home's missing audience is expected and is tolerated");
  }

  /**
   * <strong>Link two of two: structural.</strong> The flag {@code admit} passes is {@code
   * declinedHome(context)} and nothing else, and that predicate is false for every selection whose
   * project is not a contained one — so an ordinary home can only ever reach the intolerant branch
   * above.
   *
   * <p>It is one predicate, {@code ContainedProjects.name}, and not a fifth way to recognise a
   * contained scope: the same call already decides which processing stages are written {@code
   * skipped}. A selection with no project — account knowledge, or Personal — is never contained,
   * which is why the null check comes first.
   */
  @Test
  void theFlagIsFalseForEveryHomeButAContainedOne() {
    assertFalse(
        JdbcInformationCatalogueRepository.declinedHome(ordinary()),
        "an ordinary project home is not declined");
    // Every scope that can carry a null project: a PROJECT selection is refused one by its own
    // invariant, so the loop asserts over the kinds where "no project" is representable at all.
    for (InformationContext.Scope scope : InformationContext.Scope.values()) {
      if (scope == InformationContext.Scope.PROJECT) continue;
      assertFalse(
          JdbcInformationCatalogueRepository.declinedHome(
              new InformationContext(HOLDER, new InformationContext.Selection(scope, null, false))),
          "a selection with no project is never contained: " + scope);
    }
    // An ordinary project name is not the prefix, whatever else is true of the selection.
    assertFalse(
        JdbcInformationCatalogueRepository.declinedHome(
            new InformationContext(
                HOLDER,
                new InformationContext.Selection(
                    InformationContext.Scope.PROJECT, "parent", true))));
    // And true for exactly the one home the tolerance is for, by the same predicate.
    assertTrue(JdbcInformationCatalogueRepository.declinedHome(contained()));
  }
}
