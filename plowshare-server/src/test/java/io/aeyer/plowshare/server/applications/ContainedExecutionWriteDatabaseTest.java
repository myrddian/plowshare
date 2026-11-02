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
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.information.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
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
 * <h2>What it found, after the account ruling</h2>
 *
 * <p>The ruling is that a contained principal is never an {@code authority_accounts} subject:
 * anything needing an <strong>account</strong> takes the container's, and anything needing an
 * <strong>author</strong> records the principal. Measured against PostgreSQL, the knowledge write
 * path cannot express that split, because {@code InformationContext.account()} is one field serving
 * both: the destination gate is asked about it and {@code owner_handle} / {@code created_by} are
 * written from it. The principal passes the gate (V224's arm, scope-bound only) and fails the row's
 * provenance; the container's account passes the provenance and fails the gate, because a contained
 * project carries no {@code PROJECT} scope and so no role for anybody. Both halves are asserted
 * actor by actor, with the container's contributor writing in its own home as the control.
 *
 * <p>The work-authority question is settled rather than open: the container's account against the
 * nearest enclosing uncontained scope resolves {@code CONTRIBUTOR}, which is the question {@code
 * requireOwn} already asks and the one {@code application.child.run} now asks through {@code
 * Runs.Ask.workProject}. The two questions round one asked both answer nothing and are asserted to
 * keep answering nothing, since a grant arm keyed on a contained principal would be one more
 * confinement surface to prove shut.
 *
 * <p><strong>The memory write works</strong>, which is the other half of ADR 0042's writable home
 * and was untested in slice two, and it is also where the account ruling's one real hazard is
 * tested: with the account shared, {@code formed_by} is what lets a container tell its child's
 * writing from its own. That gets a test rather than an argument.
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
  private RecordedInformationEvents events;
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
    events = new RecordedInformationEvents();
    records = new JdbcInformationCatalogueRepository(jdbc, Clock.systemUTC(), events);
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
   * The ruled design, working: a child's knowledge write through the real Java path, with the two
   * axes separated.
   *
   * <p>{@code InformationAccess.contained} is the one place the combination is built — the child's
   * principal acting, its container's account accountable, and the scope-bound selection V224
   * admits. The assertions are what the row actually holds afterwards, because that is the thing
   * round one could not produce at all.
   */
  @Test
  void aChildWritesKnowledgeAsItselfAccountedToItsContainer() {
    var admitted =
        work.inTransaction(
            () ->
                admit(
                    InformationAccess.contained(childPrincipal, HOLDER, childProject)
                        .withView(true),
                    "child-notes.md"));

    // PROVENANCE: the container's account, which is what satisfies owner_handle's foreign key into
    // admins and created_by's into authority_accounts.
    assertEquals(
        HOLDER,
        jdbc.queryForObject(
            "SELECT owner_handle FROM information_resources WHERE id=?",
            String.class,
            admitted.resource()),
        "owner_handle is the container's account, never the bare principal");
    assertEquals(
        jdbc.queryForObject(
            "SELECT id FROM authority_accounts WHERE legacy_handle=?", UUID.class, HOLDER),
        jdbc.queryForObject(
            "SELECT created_by FROM information_resources WHERE id=?",
            UUID.class,
            admitted.resource()),
        "created_by resolves through authority_accounts and is no longer NULL");
    assertEquals(
        "authenticated_admission",
        jdbc.queryForObject(
            "SELECT creator_evidence FROM information_resources WHERE id=?",
            String.class,
            admitted.resource()),
        "and the CHECK passes honestly rather than by declaring the creator unknown");
    assertEquals(
        jdbc.queryForObject(
            "SELECT id FROM authority_accounts WHERE legacy_handle=?", UUID.class, HOLDER),
        jdbc.queryForObject(
            "SELECT created_by FROM information_revisions WHERE id=?",
            UUID.class,
            admitted.revision()));

    // HOME: the child's own contained scope. This is the write landing where V224's arm admits it
    // and nowhere else, and it is the field a container distinguishes the writing by.
    assertEquals(
        childScope,
        jdbc.queryForObject(
            "SELECT home_scope_id FROM information_resources WHERE id=?",
            UUID.class,
            admitted.resource()),
        "a child's document lands in the child's own home");

    // THE BARE PRINCIPAL REACHES NO FK-BEARING COLUMN. The ruling says it must stop appearing
    // verbatim, and these are every column in this write that could have carried it.
    assertEquals(
        0,
        (int)
            jdbc.queryForObject(
                "SELECT count(*) FROM information_resources WHERE owner_handle=?",
                Integer.class,
                childPrincipal));
    assertEquals(
        0,
        (int)
            jdbc.queryForObject(
                "SELECT count(*) FROM information_requests WHERE account=?",
                Integer.class,
                childPrincipal));
    // The intake event's actor too, read off the recording publisher this test composes: that
    // column is keyed to a credentialed identity like the rest, which is exactly why knowledge has
    // no field that can hold a contained principal as author. See the task report.
    assertEquals(
        List.of(HOLDER),
        events.announced().stream().map(InformationEvents.Occurrence::actor).distinct().toList());
    assertEquals(
        HOLDER,
        jdbc.queryForObject(
            "SELECT account FROM information_requests WHERE revision_id=?",
            String.class,
            admitted.revision()),
        "the idempotency receipt is the container's namespace, as its FK requires");

    // THE GATE WAS STILL THE CHILD'S. The container's account is not admitted to this home, so the
    // write above cannot have been admitted as the container -- which is the whole point of the
    // split and the one thing a single-axis fix would have got wrong.
    assertFalse(
        jdbc.queryForObject(
            "SELECT information_lock_destination(?,'project',?,FALSE)",
            Boolean.class,
            HOLDER,
            childProject),
        "the container holds no write authority inside its child's home, and none was granted");
    assertTrue(
        jdbc.queryForObject(
            "SELECT information_lock_destination(?,'project',?,FALSE)",
            Boolean.class,
            childPrincipal,
            childProject),
        "the principal does, which is the actor the gate was asked about");
    // And the global arm stays shut, which is why the contained selection is scope-bound.
    assertFalse(
        jdbc.queryForObject(
            "SELECT information_lock_destination(?,'project',?,TRUE)",
            Boolean.class,
            childPrincipal,
            childProject));
    assertFalse(
        InformationAccess.contained(childPrincipal, HOLDER, childProject)
            .selection()
            .includeGlobal(),
        "so the contained selection does not ask for it");
  }

  /**
   * The ordinary path is unchanged by the split, which is the other half of ruling one: this is a
   * widening of expressiveness and not a behavioural change.
   *
   * <p>Asserted rather than asserted-by-absence: for a context built any pre-existing way the two
   * axes hold the same value, so every provenance column that now reads {@code attribution()}
   * receives exactly what it received when it read {@code account()}.
   */
  @Test
  void anOrdinaryWriteIsUnchangedBecauseBothAxesHoldOneValue() {
    var ordinary = context(HOLDER, "parent");
    assertEquals(ordinary.account(), ordinary.attribution());
    assertEquals(ordinary.account(), ordinary.acting());
    assertEquals(ordinary.attribution(), ordinary.withView(true).attribution());
    assertEquals(
        ordinary.attribution(), ordinary.withCorpus(InformationContext.Corpus.CODE).attribution());

    var admitted = work.inTransaction(() -> admit(ordinary.withView(true), "ordinary.md"));
    assertEquals(
        HOLDER,
        jdbc.queryForObject(
            "SELECT owner_handle FROM information_resources WHERE id=?",
            String.class,
            admitted.resource()));
    assertEquals(
        parentScope,
        jdbc.queryForObject(
            "SELECT home_scope_id FROM information_resources WHERE id=?",
            UUID.class,
            admitted.resource()));
    // And a contained context differs on exactly one axis, never on the acting one.
    var child = InformationAccess.contained(childPrincipal, HOLDER, childProject);
    assertEquals(childPrincipal, child.acting());
    assertEquals(childPrincipal, child.account());
    assertEquals(HOLDER, child.attribution());
  }

  /**
   * The pipeline declines a contained-home document deliberately, and "blocked" does not mean
   * "broken".
   *
   * <p>A child's write succeeds and enqueues <strong>no</strong> work that could fail, take a lease
   * or retry: every stage is written {@code skipped}, which is this table's existing word for
   * deliberate non-processing. That matters structurally rather than cosmetically — the claim
   * scan's work list is {@code s.state='pending' OR (s.state='running' AND s.lease_until<?)}
   * ({@code JdbcInformationProcessingRepository}), so a revision with no such row is never selected
   * and there is nothing to lease. Declining when work was claimed instead would already have
   * enqueued the failure.
   *
   * <p>The control is the half that makes this a scoped decline rather than a broken pipeline: an
   * ordinary document in an uncontained home still enqueues exactly what it enqueues today.
   */
  @Test
  void aContainedDocumentIsDeclinedByThePipelineAndAnOrdinaryOneIsNot() {
    var child =
        work.inTransaction(
            () ->
                admit(
                    InformationAccess.contained(childPrincipal, HOLDER, childProject)
                        .withView(true),
                    "child-notes.md"));

    // NOTHING CLAIMABLE: no pending or running step exists, which is what the claim scan needs.
    assertEquals(
        0,
        (int)
            jdbc.queryForObject(
                "SELECT count(*) FROM information_steps WHERE revision_id=?"
                    + " AND state IN ('pending','running')",
                Integer.class,
                child.revision()),
        "a child's document enqueues no claimable work, so nothing can lease or retry it");
    assertEquals(
        0,
        (int)
            jdbc.queryForObject(
                "SELECT count(*) FROM information_steps WHERE revision_id=?"
                    + " AND (lease_token IS NOT NULL OR lease_until IS NOT NULL OR attempt<>0)",
                Integer.class,
                child.revision()),
        "and holds no lease and has attempted nothing");
    // DELIBERATE, not lost: every stage says skipped, and nothing says failed or blocked.
    assertEquals(
        List.of("skipped"),
        jdbc.queryForList(
            "SELECT DISTINCT state FROM information_steps WHERE revision_id=?",
            String.class,
            child.revision()));

    // OBSERVABLE: the decline announced itself, named its reason and sits beside the intake it
    // belongs to, so an operator can tell a skip from a loss.
    var announced =
        events.announced().stream()
            .filter(occurrence -> occurrence.revision().equals(child.revision()))
            .toList();
    assertEquals(
        List.of("retained", "skipped"),
        announced.stream().map(InformationEvents.Occurrence::action).toList(),
        "the intake is announced, then the decline");
    var decline = announced.getLast();
    assertEquals("intake", decline.stage());
    assertTrue(decline.detail().contains("confined Application"), decline.detail());
    assertTrue(decline.detail().contains("0042"), decline.detail());
    assertEquals(
        HOLDER, decline.actor(), "announced under the accounting account, as the FK needs");

    // CONTROL: an ordinary document in an uncontained home enqueues its work exactly as before.
    var ordinary = work.inTransaction(() -> admit(context(HOLDER, "parent"), "ordinary.md"));
    assertEquals(
        0,
        (int)
            jdbc.queryForObject(
                "SELECT count(*) FROM information_steps WHERE revision_id=? AND state='skipped'",
                Integer.class,
                ordinary.revision()),
        "nothing is skipped for an uncontained home");
    assertTrue(
        jdbc.queryForObject(
                "SELECT count(*) FROM information_steps WHERE revision_id=? AND state='pending'",
                Integer.class,
                ordinary.revision())
            > 0,
        "and its stages are pending, which is the behaviour this decline must not have changed");
    assertEquals(
        List.of("retained"),
        events.announced().stream()
            .filter(occurrence -> occurrence.revision().equals(ordinary.revision()))
            .map(InformationEvents.Occurrence::action)
            .toList(),
        "and an ordinary intake announces no decline");
  }

  /**
   * Every catalogue management operation refuses a contained acting principal explicitly, before
   * anything writes — and each refusal has an ordinary actor passing the same guard beside it.
   *
   * <p>V224 admits a contained principal into {@code lockResource} and the readability predicates
   * for its own home, so it really can arrive at every one of these.
   *
   * <p>Unguarded, only {@code link} would have failed: its {@code information_links.actor_handle}
   * is a live foreign key to {@code admins(handle)}, which a bare-UUID contained principal has no
   * row in. The other eight would have <strong>succeeded</strong> — {@code information_events} was
   * dropped by {@code V188__information_events_on_relay.sql:96}, and those eight hand the actor to
   * Relay rather than to a keyed column. The guard is therefore an <strong>authority
   * check</strong>, not an integrity patch: the gap it closes was a silent grant of nine management
   * operations to a confined Application, not a crash.
   *
   * <p>Each case asserts two things: the contained principal gets the guard's own {@code
   * CallerFault}, and an ordinary actor does <strong>not</strong> — either succeeding outright, or
   * reaching the operation's own precondition, which is what proves the guard is scoped to
   * contained actors rather than refusing everybody. Where an ordinary actor's success would need a
   * fabricated processing state (five {@code ready} stages, a failed step), the control asserts it
   * got past the guard to a different refusal, and says so.
   */
  @Test
  void everyCatalogueManagementOperationRefusesAContainedActor() {
    var child =
        work.inTransaction(
            () ->
                admit(
                    InformationAccess.contained(childPrincipal, HOLDER, childProject)
                        .withView(true),
                    "child-notes.md"));
    var mine = work.inTransaction(() -> admit(context(HOLDER, "parent"), "ordinary.md"));
    var confined = InformationAccess.contained(childPrincipal, HOLDER, childProject).withView(true);
    var ordinary = context(HOLDER, "parent").withView(true);

    // One list, so a tenth management operation added without a guard shows up as a missing row
    // here rather than as a silent grant in production: eight of the nine would succeed unguarded,
    // so the failure mode this list protects against is capability, not a crash.
    record Operation(String name, java.util.function.Consumer<InformationContext> on) {}
    var operations =
        List.of(
            new Operation(
                "link",
                context ->
                    records.link(context, revisionFor(context, child, mine), "parent", false)),
            new Operation(
                "share",
                context -> records.share(context, revisionFor(context, child, mine), true)),
            new Operation(
                "availability",
                context ->
                    records.availability(context, revisionFor(context, child, mine), "excluded")),
            new Operation(
                "retry", context -> records.retry(context, revisionFor(context, child, mine))),
            new Operation(
                "rebuild",
                context -> records.rebuild(context, revisionFor(context, child, mine), "embed")),
            new Operation(
                "allowance",
                context -> records.allowance(context, revisionFor(context, child, mine), 3)),
            new Operation(
                "cancelProcessing",
                context -> records.cancelProcessing(context, revisionFor(context, child, mine))),
            new Operation(
                "recordEvidence",
                context ->
                    records.recordEvidence(
                        context,
                        revisionFor(context, child, mine),
                        0,
                        4,
                        "body",
                        "p1",
                        UUID.randomUUID())),
            new Operation(
                "finalise",
                context -> records.finalise(context, revisionFor(context, child, mine), Map.of())));

    for (var operation : operations) {
      var refusal =
          assertThrows(
              CallerFault.class,
              () ->
                  work.inTransaction(
                      () -> {
                        operation.on().accept(confined);
                        return null;
                      }),
              operation.name() + " must refuse a contained actor");
      assertTrue(
          refusal.getMessage().startsWith("A confined Application may not "),
          operation.name() + ": " + refusal.getMessage());

      // The control: the same call as an ordinary member of the container's project is not stopped
      // by that guard. It may fail on the operation's own terms -- a report needs completed
      // processing, a retry needs something to retry -- and that is the point: a different
      // refusal, or none, means the guard did not fire.
      try {
        work.inTransaction(
            () -> {
              operation.on().accept(ordinary);
              return null;
            });
      } catch (RuntimeException expected) {
        assertFalse(
            expected.getMessage() != null
                && expected.getMessage().startsWith("A confined Application may not "),
            operation.name() + " refused an ordinary actor with the contained guard");
      }
    }
  }

  /** The caller's own document: the child's for a contained context, the container's otherwise. */
  private UUID revisionFor(
      InformationContext context,
      InformationCatalogue.Admission child,
      InformationCatalogue.Admission mine) {
    return childPrincipal.equals(context.acting()) ? child.revision() : mine.revision();
  }

  /**
   * The ruled replacement for the question I asked in round one. A contained principal holds no
   * project role and must not be given one; the question that decides a child's work authority is
   * the container's account against the <strong>nearest enclosing uncontained scope</strong>, which
   * is the rule {@code requireOwn} already applies. Asserted here against real grants, because the
   * whole point is that this question resolves where the other one could not.
   */
  @Test
  void aChildsWorkAuthorityResolvesOnTheNearestEnclosingUncontainedScope() {
    // The question that decides it, and it resolves.
    assertEquals(
        "CONTRIBUTOR",
        jdbc.queryForObject("SELECT authority_project_role('parent',?)", String.class, HOLDER),
        "the container's account against the enclosing uncontained scope");
    // The two questions round one asked, both of which answer nothing -- and must keep answering
    // nothing, because a grant arm keyed on a contained principal would be one more confinement
    // surface to prove shut.
    assertNull(
        jdbc.queryForObject(
            "SELECT authority_project_role(?,?)", String.class, childProject, childPrincipal),
        "a contained principal holds no project role, by design");
    assertNull(
        jdbc.queryForObject(
            "SELECT authority_project_role(?,?)", String.class, childProject, HOLDER),
        "and neither does its container IN the contained project: there is no PROJECT scope there");
    assertNull(
        jdbc.queryForObject(
            "SELECT service_project_role(?,?)", String.class, childProject, childPrincipal));
    assertEquals(
        0,
        (int)
            jdbc.queryForObject(
                "SELECT count(*) FROM authority_accounts WHERE legacy_handle=?",
                Integer.class,
                childPrincipal),
        "V218 is unchanged: no authority account is created for a contained principal");
    // The enclosing scope really is uncontained, which is what makes the first question askable:
    // a project-keyed role question needs a PROJECT scope, and only this one has it.
    assertEquals(
        "PROJECT",
        jdbc.queryForObject(
            "SELECT kind FROM authority_scopes WHERE id=?", String.class, parentScope));
    assertEquals(
        "CONTAINED",
        jdbc.queryForObject(
            "SELECT kind FROM authority_scopes WHERE id=?", String.class, childScope));
  }

  /**
   * The other carried requirement, and the half that works: a child forms a memory in its own home,
   * through the real {@code MemoryStore}. {@code memories} has no actor-keyed SQL gate, so what is
   * asserted is the home the shipped trigger resolves — the child's contained scope, from the
   * child's own project — and the author the row keeps.
   */
  @Test
  void aChildWritesAMemoryInItsOwnHome() {
    String id = "mem-" + UUID.randomUUID();
    memories.save(memory(id, Home.of(childProject), childPrincipal));

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

    // Control: the same store and the same call, writing into the container's project, lands on
    // the container's scope -- so the assertion above is about the home the trigger chose and not
    // the only home it could have chosen.
    String outside = "mem-" + UUID.randomUUID();
    memories.save(memory(outside, Home.of("parent"), HOLDER));
    assertEquals(
        parentScope,
        jdbc.queryForObject("SELECT home_scope_id FROM memories WHERE id=?", UUID.class, outside));
    assertNotEquals(childScope, parentScope);
  }

  /**
   * The hard requirement that comes with sharing the account: a container must be able to tell its
   * child's writing from its own.
   *
   * <p>This is not a nicety. Child output is untrusted input to its container and a
   * prompt-injection surface by construction, so a container that read its child's words back and
   * saw itself as their author could compose them as its own writing. With the <em>account</em>
   * axis now the container's by ruling, the <em>author</em> axis is the only thing that carries the
   * distinction, and for memory that is {@code formed_by} — the existing mechanism, not a new one.
   *
   * <p>Both rows below are reachable by the same container through the same store. The assertions
   * are the two fields that differ, named explicitly so a later change that collapses either one
   * has to delete this test to do it: {@code formed_by} (the author) and {@code home_scope_id} (the
   * home, which a container's own writing never carries).
   */
  @Test
  void aContainerCanTellItsChildsWritingFromItsOwn() {
    String mine = "mem-" + UUID.randomUUID();
    String childs = "mem-" + UUID.randomUUID();
    memories.save(memory(mine, Home.of("parent"), HOLDER));
    memories.save(memory(childs, Home.of(childProject), childPrincipal));

    // THE AUTHOR FIELD: formed_by. The child's row names the contained principal; the container's
    // names the container's account. They are never the same string.
    assertEquals(
        HOLDER,
        jdbc.queryForObject("SELECT formed_by FROM memories WHERE id=?", String.class, mine));
    assertEquals(
        childPrincipal,
        jdbc.queryForObject("SELECT formed_by FROM memories WHERE id=?", String.class, childs));
    assertNotEquals(
        jdbc.queryForObject("SELECT formed_by FROM memories WHERE id=?", String.class, mine),
        jdbc.queryForObject("SELECT formed_by FROM memories WHERE id=?", String.class, childs),
        "the author distinguishes the two, which is what keeps child output identifiable");
    // And the principal is a value no account can impersonate by holding it: it is not a handle in
    // admins and not a legacy_handle in authority_accounts, so nothing can sign in as it.
    assertEquals(
        0,
        (int)
            jdbc.queryForObject(
                "SELECT count(*) FROM admins WHERE handle=?", Integer.class, childPrincipal));

    // THE HOME FIELD: home_scope_id. A second, structural distinction that the trigger enforces
    // rather than the writer choosing, so it holds even for a writer that lies about its author.
    assertEquals(
        parentScope,
        jdbc.queryForObject("SELECT home_scope_id FROM memories WHERE id=?", UUID.class, mine));
    assertEquals(
        childScope,
        jdbc.queryForObject("SELECT home_scope_id FROM memories WHERE id=?", UUID.class, childs));
    assertEquals(
        "CONTAINED",
        jdbc.queryForObject(
            "SELECT s.kind FROM memories m JOIN authority_scopes s ON s.id=m.home_scope_id"
                + " WHERE m.id=?",
            String.class,
            childs),
        "a container's own writing never lands on a CONTAINED scope");

    // The negative control that makes this a distinction and not a coincidence: a row claiming the
    // child's home while the writer names the container's project is refused the child's scope --
    // the trigger resolves the home from the project and does not take the writer's word for it.
    String confused = "mem-" + UUID.randomUUID();
    memories.save(memory(confused, Home.of("parent"), childPrincipal));
    assertEquals(
        parentScope,
        jdbc.queryForObject("SELECT home_scope_id FROM memories WHERE id=?", UUID.class, confused),
        "the home follows the project, so an author string cannot move a row into a child's home");
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
}
