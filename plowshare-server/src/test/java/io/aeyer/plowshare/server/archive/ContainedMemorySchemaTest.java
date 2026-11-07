package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.access.BuiltInIdentities;
import io.aeyer.plowshare.server.embedding.QuerySources;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * Real PostgreSQL, because the assertions are the behaviour of the SQL gates {@code MemoryStore}
 * puts in front of the archive: the read pin, the write pin, and the write authority behind it.
 *
 * <p><strong>Two kinds of test here, and the difference is deliberate.</strong> The gate
 * characterizations reproduce the store's statements as the constants below and ask them directly,
 * so what is measured is the gate and not a service's handling of it. {@code
 * aMixedCaseActorStringNeverProducesANullAccountPin} and {@code
 * aContainedPrincipalIsStillRefusedAboveTheStore} instead drive a real {@code MemoryStore}, because
 * what they are about is Java-side — the {@code lower(?)} in the store's own statements, the
 * store's null-account refusals, and which principal a production binding presents — and a copy
 * cannot regress any of it. The first of those two carries the full argument.
 *
 * <p>CHARACTERIZATION (run before any widening). Three of the eight tests that existed then failed
 * — {@code aContainedActorReadsItsOwnMemories}, {@code aContainedActorWritesItsOwnMemories} and the
 * own-home control inside {@code aSiblingReadsNothingOfItsSiblings} — so the memory side did need
 * widening, and ADR 0042's claim that a contained principal "reads its own documents and its own
 * memories" was over-claimed for memories. Five passed beforehand and are regression tests now:
 * {@code anAncestorReadsItsDescendantsMemories} (the container's walk already worked), {@code
 * aContainedActorDoesNotReachAGloballyReadableMemory}, {@code
 * anOrdinaryAccountsMemoryAccessIsUnchanged}, {@code
 * theMemoryDoorIsClosedByTheAccountJoinAndNotByGlobalRead} and {@code
 * aContainedActorReadsNoOtherProjectsMemories} — the last of which passed only because everything
 * was refused, so on its own it proved nothing until the three above passed too. {@code
 * aMixedCaseActorStringNeverProducesANullAccountPin} is the ninth and was added after the widening,
 * for a hole the widening itself introduced; {@code MemoryStore}'s class Javadoc states what it
 * holds.
 *
 * <p><strong>WHICH PREDICATE REFUSED, IN ORDER.</strong> On the READ path the first refusal is the
 * statement's own {@code FROM authority_accounts a WHERE a.legacy_handle=$actor} (the shape is
 * V157's; a contained principal has no such row by design, V218): no row, so the store's {@code
 * rows.size() != 1} raised before anything beside the join was decided. {@code
 * information_global_read_member} (V158:16) stands beside it and also answers false, but its
 * refusal is the same missing-rows fact — {@code account_active} plus an {@code admins}-to-{@code
 * authority_accounts} join — and NOT a scope decision: that function never tests that the account
 * holds a grant of the {@code GLOBAL_READ} role and names no scope, so it means "is this an
 * ordinary active account". Third and last, {@code information_project_readable} — the only
 * conjunct that is about scope — already answered TRUE for the child's own contained project before
 * this change, through {@code knowledge_scope_readable}'s contained arm (V223:188). So scope was
 * never the barrier; the account join was.
 *
 * <p>On the WRITE path the first refusal is the write pin's {@code a.id=(SELECT id FROM
 * authority_accounts WHERE legacy_handle=$actor)} — the same absent row, one statement earlier than
 * the predicate usually named. Behind it the write authority's {@code EXISTS} refuses on that same
 * {@code authority_accounts} row. Its {@code admins}/{@code server_admin} requirement is
 * CONDITIONAL, not a general administrator gate: {@code ?::text IS NOT NULL} is the home's project,
 * so only a Global-tier write ever reaches that arm, which is why an ordinary non-administrator
 * contributor files into its project today. V232 adds one arm to each of the three statements and
 * changes none of the above.
 */
@Tag("full-db")
@Testcontainers
class ContainedMemorySchemaTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withUrlParam("sslmode", "disable")
          .withUrlParam("gssEncMode", "disable");

  /**
   * The read admission, reproduced from {@code MemoryStore.pin(MemoryQueries.Request)}, with {@link
   * #readAdmits} applying the store's own row and scope conditions to the result.
   *
   * <p>Reproducing the statement rather than driving the store is a deliberate cost with a named
   * risk: the store can be edited without this constant following, and then a gate is untested
   * while this still passes. It is paid because the store's {@code pin} needs an embedding origin,
   * a proposal and a protected-information flag to be reached, none of which these assertions are
   * about, and because the three statements are the gate — there is no SQL function to call
   * instead, which is how {@code ContainedActorWriteSchemaTest} avoids the same duplication.
   *
   * <p>That named risk is why the two tests whose subject IS the store's Java — {@code
   * aMixedCaseActorStringNeverProducesANullAccountPin} and {@code
   * aContainedPrincipalIsStillRefusedAboveTheStore} — do not use this constant for their load-
   * bearing assertions. They call {@link #store()}. Nothing a copy asserts can regress Java that
   * the copy also contains.
   */
  private static final String READ_PIN =
      """
      SELECT a.id, CASE WHEN ?::text IS NULL THEN ?::uuid
        ELSE (SELECT memory_knowledge_scope(p.id) FROM projects p WHERE p.name=?) END
      FROM authority_accounts a
      WHERE a.legacy_handle=? AND information_global_read_member(?)
        AND (?::text IS NULL OR information_project_readable(
          (SELECT id FROM projects WHERE name=?),?))
      UNION ALL
      SELECT (SELECT c.id FROM contained_principals c WHERE c.id::text=lower(?)),
        (SELECT memory_knowledge_scope(p.id) FROM projects p WHERE p.name=?)
      WHERE contained_memory_actor_allows(?,?,false)
      """;

  /**
   * The write path's identity pin, reproduced from {@code MemoryStore.pin(MemoryWrites.Request)}.
   */
  private static final String WRITE_PIN =
      """
      SELECT a.id, CASE WHEN ?::text IS NULL THEN ?::uuid ELSE
        (SELECT memory_knowledge_scope(p.id) FROM projects p WHERE p.name=?) END
      FROM authority_accounts a WHERE a.id=CASE WHEN ? THEN ?::uuid ELSE
        (SELECT id FROM authority_accounts WHERE legacy_handle=?) END
      UNION ALL
      SELECT (SELECT c.id FROM contained_principals c WHERE c.id::text=lower(?)),
        (SELECT memory_knowledge_scope(p.id) FROM projects p WHERE p.name=?)
      WHERE contained_memory_actor_allows(?,?,true)
      """;

  /** The write authority behind it, reproduced from {@code MemoryStore.require(MemoryWrites…)}. */
  private static final String WRITE_AUTHORITY =
      "SELECT EXISTS(SELECT 1 FROM authority_accounts a WHERE a.id=? AND a.legacy_handle=?"
          + " AND information_global_read_member(?) AND (?::text IS NOT NULL OR EXISTS(SELECT 1"
          + " FROM admins u WHERE u.handle=? AND u.enabled AND u.server_admin AND NOT u.bootstrap"
          + " AND u.account_kind='USER'))) OR contained_memory_actor_allows(?,?,true)";

  private static final RowMapper<QuerySources.Pin> PIN =
      (row, n) -> new QuerySources.Pin(row.getObject(1, UUID.class), row.getObject(2, UUID.class));

  private static final String HOLDER = "alice";
  private static final String STRANGER = "stranger";
  private static final String ADMINISTRATOR = "admin";

  private JdbcTemplate jdbc;
  private long parentProject;
  private UUID parentScope;
  private UUID childScope;
  private UUID grandchildScope;
  private UUID siblingScope;
  private String childPrincipal;
  private String grandchildPrincipal;
  private String siblingPrincipal;

  @BeforeEach
  void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var schema = "contained_memory_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(source).execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
    new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
    var properties = new Properties();
    properties.setProperty("currentSchema", schema + ",public");
    source.setConnectionProperties(properties);
    Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
    jdbc = new JdbcTemplate(source);

    user(ADMINISTRATOR, true);
    user(HOLDER, false);
    user(STRANGER, false);
    parentProject = project("parent");
    parentScope =
        jdbc.queryForObject(
            "SELECT id FROM authority_scopes WHERE project_id=?", UUID.class, parentProject);
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES (?,?,'CONTRIBUTOR')",
        parentProject,
        HOLDER);
    homes.put(parentScope, new Home(parentProject, "parent"));

    childScope = contained(parentScope);
    grandchildScope = contained(childScope);
    siblingScope = contained(parentScope);
    childPrincipal = principal(childScope);
    grandchildPrincipal = principal(grandchildScope);
    siblingPrincipal = principal(siblingScope);
  }

  private void user(String handle, boolean admin) {
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password,server_admin)"
            + " VALUES (?,'fixture',FALSE,?)",
        handle,
        admin);
  }

  private long project(String name) {
    jdbc.update("INSERT INTO projects(name) VALUES (?)", name);
    return jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, name);
  }

  /** The contained project behind a contained scope, kept so a home can be named by project. */
  private record Home(long project, String name) {}

  private final java.util.Map<UUID, Home> homes = new java.util.HashMap<>();

  /** A contained scope over a fresh contained project; ancestry is the column, not the name. */
  private UUID contained(UUID container) {
    var name = "contained:" + UUID.randomUUID();
    var project = project(name);
    var scope = UUID.randomUUID();
    assertEquals(
        1,
        jdbc.update(
            "INSERT INTO authority_scopes(id,kind,project_id,container) VALUES (?,'CONTAINED',?,?)",
            scope,
            project,
            container));
    homes.put(scope, new Home(project, name));
    return scope;
  }

  /** A contained principal's actor identifier is its bare UUID: it has no handle and no account. */
  private String principal(UUID scope) {
    jdbc.update("INSERT INTO contained_principals(scope_id) VALUES (?)", scope);
    return jdbc.queryForObject(
            "SELECT id FROM contained_principals WHERE scope_id=?", UUID.class, scope)
        .toString();
  }

  private String home(UUID scope) {
    return homes.get(scope).name();
  }

  /**
   * A memory whose home the shipped trigger resolves from its project, so nothing here asserts a
   * home the schema would not have chosen. The author is free text on this table — there is no
   * foreign key into {@code admins} — which is why a contained principal can be one.
   */
  private String memory(UUID scope, String author) {
    var id = UUID.randomUUID().toString();
    var home = homes.get(scope);
    jdbc.update(
        "INSERT INTO memories(id,project_id,summary,scope,body,state,formed_at,formed_by,"
            + "formed_where) VALUES (?,?,'fixture','project','body','active',now(),?,'fixture')",
        id,
        home == null ? null : home.project(),
        author);
    assertEquals(
        scope,
        jdbc.queryForObject("SELECT home_scope_id FROM memories WHERE id=?", UUID.class, id),
        "the shipped trigger maps the memory to the home under test");
    return id;
  }

  /** The Global knowledge tier: a memory with no project at all. */
  private String globalMemory() {
    var id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO memories(id,project_id,summary,scope,body,state,formed_at,formed_by,"
            + "formed_where) VALUES (?,NULL,'fixture','global','body','active',now(),?,'fixture')",
        id,
        ADMINISTRATOR);
    return id;
  }

  /**
   * Exactly what the store concludes from {@link #READ_PIN}: one row, and a resolved home. A null
   * {@code project} is the Global knowledge tier, which is how {@code MemoryQueries.Request} names
   * it.
   */
  private boolean readAdmits(String actor, String project) {
    return readPin(actor, project)
        .filter(pin -> pin.scope() != null && pin.account() != null)
        .isPresent();
  }

  /** The admitted pin itself, for the one assertion that is about what the arm projects. */
  private java.util.Optional<QuerySources.Pin> readPin(String actor, String project) {
    var rows =
        jdbc.query(
            READ_PIN,
            PIN,
            project,
            BuiltInIdentities.GLOBAL_KNOWLEDGE.value(),
            project,
            actor,
            actor,
            project,
            project,
            actor,
            actor,
            project,
            actor,
            project);
    return rows.size() == 1 ? java.util.Optional.of(rows.getFirst()) : java.util.Optional.empty();
  }

  /**
   * Both write gates in the order the store asks them: the identity pin first, then the write
   * authority behind it. A refusal by either is a refused write, which is why this helper composes
   * them rather than asking only the second.
   */
  private boolean writeAdmits(String actor, String project) {
    var rows =
        jdbc.query(
            WRITE_PIN,
            PIN,
            project,
            BuiltInIdentities.GLOBAL_KNOWLEDGE.value(),
            project,
            false,
            BuiltInIdentities.SYSTEM.value(),
            actor,
            actor,
            project,
            actor,
            project);
    if (rows.size() != 1 || rows.getFirst().scope() == null || rows.getFirst().account() == null)
      return false;
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            WRITE_AUTHORITY,
            Boolean.class,
            rows.getFirst().account(),
            actor,
            actor,
            project,
            actor,
            actor,
            project));
  }

  @Test
  void aContainedActorReadsItsOwnMemories() {
    memory(childScope, childPrincipal);

    assertTrue(readAdmits(childPrincipal, home(childScope)), "a child reads its own home");
    assertTrue(
        readAdmits(grandchildPrincipal, home(grandchildScope)),
        "and so does one nested a second hop down");

    // What the arm projects: the principal's OWN id in the account column -- deliberately not an
    // authority_accounts id, which it has none of -- and its own scope as the home.
    var pin = readPin(childPrincipal, home(childScope)).orElseThrow();
    assertEquals(UUID.fromString(childPrincipal), pin.account());
    assertEquals(childScope, pin.scope());
  }

  @Test
  void aContainedActorWritesItsOwnMemories() {
    assertTrue(writeAdmits(childPrincipal, home(childScope)), "a child files into its own home");
  }

  @Test
  void aContainedActorReadsNoOtherProjectsMemories() {
    memory(parentScope, HOLDER);
    memory(siblingScope, siblingPrincipal);
    memory(grandchildScope, grandchildPrincipal);

    assertFalse(readAdmits(childPrincipal, "parent"), "not its container's home");
    assertFalse(readAdmits(childPrincipal, home(siblingScope)), "not a sibling's");
    assertFalse(readAdmits(childPrincipal, home(grandchildScope)), "not its own descendant's");
    assertFalse(
        readAdmits(childPrincipal, home(unrelated("outside"))), "not an unrelated project's");
    assertFalse(writeAdmits(childPrincipal, "parent"), "and it files into none of them");
    assertFalse(writeAdmits(childPrincipal, home(siblingScope)));
  }

  /** An ordinary project outside the containment subtree; its PROJECT scope is trigger-minted. */
  private UUID unrelated(String name) {
    var project = project(name);
    var scope =
        jdbc.queryForObject(
            "SELECT id FROM authority_scopes WHERE project_id=?", UUID.class, project);
    homes.put(scope, new Home(project, name));
    return scope;
  }

  @Test
  void aContainedActorDoesNotReachAGloballyReadableMemory() {
    globalMemory();

    // Control: the Global tier really is reachable, so the child's refusal is authority and not
    // an empty fixture. Any ordinary active account passes information_global_read_member.
    assertTrue(readAdmits(HOLDER, null), "control: an ordinary account reads the Global tier");
    assertFalse(readAdmits(childPrincipal, null), "a child does not");
    assertFalse(writeAdmits(childPrincipal, null), "nor file into it");
    assertFalse(
        jdbc.queryForObject(
            "SELECT information_global_read_member(?)", Boolean.class, childPrincipal),
        "and it is not a general-read member, which is what this slice does not change");
  }

  @Test
  void anAncestorReadsItsDescendantsMemories() {
    memory(childScope, childPrincipal);
    memory(grandchildScope, grandchildPrincipal);

    assertTrue(readAdmits(HOLDER, home(childScope)), "the container's contributor reads the child");
    assertTrue(readAdmits(HOLDER, home(grandchildScope)), "and the hop below it");
    assertFalse(
        readAdmits(STRANGER, home(childScope)), "an account outside the container does not");
  }

  @Test
  void aSiblingReadsNothingOfItsSiblings() {
    memory(childScope, childPrincipal);
    memory(siblingScope, siblingPrincipal);

    assertTrue(readAdmits(siblingPrincipal, home(siblingScope)), "control: its own home");
    assertFalse(readAdmits(siblingPrincipal, home(childScope)));
    assertFalse(readAdmits(childPrincipal, home(siblingScope)));
    assertFalse(writeAdmits(siblingPrincipal, home(childScope)));
  }

  /**
   * Which predicate actually closes the memory door, measured rather than assumed, because the
   * answer is what the amendment to ADR 0042 rests on. None of the four facts below is changed by
   * V232, so this test reads the same before and after the widening.
   */
  @Test
  void theMemoryDoorIsClosedByTheAccountJoinAndNotByGlobalRead() {
    assertFalse(
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM authority_accounts WHERE legacy_handle=?)",
                Boolean.class,
                childPrincipal)),
        "the join both statements open on: a contained principal has no authority_accounts row");
    assertFalse(
        jdbc.queryForObject(
            "SELECT information_global_read_member(?)", Boolean.class, childPrincipal),
        "the conjunct beside it also refuses, but only because account_active needs those rows");
    assertTrue(
        jdbc.queryForObject(
            "SELECT information_project_readable((SELECT id FROM projects WHERE name=?),?)",
            Boolean.class,
            home(childScope),
            childPrincipal),
        "and the read path's third conjunct already admitted the child's own home: V223's self arm"
            + " reaches it through knowledge_scope_readable, so scope was never the barrier");
    assertFalse(
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM admins WHERE handle=?)",
                Boolean.class,
                childPrincipal)),
        "the write path's administrator arm has no row to find either -- but it is not reached for"
            + " a project-homed write at all, which anOrdinaryAccountsMemoryAccessIsUnchanged pins");
  }

  /**
   * The admission and the projection must agree about case — asserted against {@code MemoryStore}
   * itself, not against the copies above.
   *
   * <p>{@code contained_knowledge_actor} (V229:30) tests the actor with {@code ~*} and casts it
   * with {@code ::uuid}, both case-insensitive, and {@code contained_own_home} compares UUIDs
   * rather than text — so a mixed-case actor string for a live principal selects the arm. {@code
   * c.id::text} renders lowercase, so a case-sensitive projection would have paired that admission
   * with a NULL account: not a leak, since it is the same principal and its own home, but a {@code
   * Pin} escaping the store with a null account, whose next reader is {@code
   * pin.account().toString()} or a NOT NULL column.
   *
   * <p><strong>Why this one test drives the store and the other eight do not.</strong> The defect
   * it regresses is Java-side: the {@code lower(?)} in {@code MemoryStore}'s own two pin
   * statements. {@link #READ_PIN} and {@link #WRITE_PIN} are copies of those statements and carry
   * their own {@code lower(?)}, so an assertion made through {@link #readPin} keeps passing when
   * the store's is reverted — it would be measuring this file. Measured on 2026-11-08 by doing
   * exactly that: with {@code c.id::text=?} in {@code MemoryStore} and {@code lower(?)} still here,
   * the store-driven assertions below fail and {@link #readAdmits} still passes.
   *
   * <p>The store's other half of the guarantee — the {@code rows.getFirst().account() == null}
   * clause in each of its two refusals — is NOT measured here, and cannot be: while the projection
   * is correct an admitted row's account is never null, so deleting both clauses changes nothing
   * this test can see (also measured, same date). It is defence in depth, not a tested behaviour.
   *
   * <p>The other eight tests characterize SQL gates, where the duplication buys a statement that
   * can be asked in isolation and the store adds nothing to what is under test; this one has to ask
   * the store.
   *
   * <p>Driving {@code MemoryStore.pin} is also the only production-shaped caller that presents a
   * contained principal to V232's arm: every entry point above it — {@code
   * BoundMemoryQueries.requireScope} and {@code BoundMemoryWrites.requireRecipient} — asks {@code
   * ScopedPermissions} for a project role that a {@code contained:} project grants nobody. That
   * refusal is deliberate and is asserted, as itself, by {@link
   * #aContainedPrincipalIsStillRefusedAboveTheStore}.
   */
  @Test
  void aMixedCaseActorStringNeverProducesANullAccountPin() {
    // An explicit id rather than the table's gen_random_uuid default, so the actor string is
    // guaranteed to contain hex letters and the uppercase form really differs from it.
    var scope = contained(parentScope);
    var lettered = UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
    jdbc.update("INSERT INTO contained_principals(id,scope_id) VALUES (?,?)", lettered, scope);
    memory(scope, lettered.toString());
    var mixed = lettered.toString().toUpperCase(java.util.Locale.ROOT);
    assertNotEquals(lettered.toString(), mixed);

    assertTrue(
        jdbc.queryForObject(
            "SELECT contained_memory_actor_allows(?,?,false)", Boolean.class, mixed, home(scope)),
        "the arm admits case-insensitively, which is what makes the projection's case matter");

    var store = store();
    // THE STORE'S OWN read pin. Revert the lower(?) in MemoryStore and this raises; revert the
    // null-account clause and the account below is null.
    var selection = store.pin(read(mixed, home(scope)));
    assertNotNull(
        selection.pin().account(),
        "admitted, so the account is the principal's own and never null");
    assertEquals(lettered, selection.pin().account());
    assertEquals(scope, selection.pin().scope());

    // And the store's own write pin, which also runs the write authority behind it in require(...).
    var writePin = store.pin(write(mixed, home(scope)));
    assertEquals(lettered, writePin.account());
    assertEquals(scope, writePin.scope());

    // The refusals are unmoved by case: the store refuses a mixed-case actor every other home.
    assertEquals(
        "Memory query is outside live read authority",
        assertThrows(CallerFault.class, () -> store.pin(read(mixed, "parent"))).getMessage());
    assertEquals(
        "Memory query is outside live read authority",
        assertThrows(CallerFault.class, () -> store.pin(read(mixed, home(siblingScope))))
            .getMessage());
    assertEquals(
        "Memory write is outside its live authority or source window",
        assertThrows(CallerFault.class, () -> store.pin(write(mixed, "parent"))).getMessage());

    // The reproduced statements agree with the store on all of it, which is what keeps the other
    // eight tests' copies honest for this one principal.
    assertTrue(readAdmits(mixed, home(scope)));
    assertTrue(writeAdmits(mixed, home(scope)));
    assertFalse(readAdmits(mixed, "parent"));
    assertFalse(readAdmits(mixed, home(siblingScope)));
  }

  /**
   * The canonical-case child, through the store, and the refusal one layer above it.
   *
   * <p>The first half is V232's arm reached as production reaches it: {@code JobRuntime}'s memory
   * bindings now pass {@code InformationActor.acting()}, so a contained run's child principal is
   * what {@code MemoryStore.pin} is asked about. Before the acting/attribution split those bindings
   * passed {@code attribution()} — the container's handle — and no production caller could put a
   * contained principal into either statement.
   *
   * <p>The second half is the ceiling, asserted rather than assumed. {@code
   * ScopedPermissions.allows} is what {@code BoundMemoryQueries.requireScope} and {@code
   * BoundMemoryWrites.requireRecipient} ask, and it resolves a role from {@code project_members}; a
   * {@code contained:} project has no member row and no {@code PROJECT} scope, so it answers false
   * for the child AND for its container. That is why the store's own admission is not yet reachable
   * through {@code Archive}, and widening it is an authority decision ADR 0042 does not grant — so
   * this asserts the absence as a fact about today rather than as a defect.
   */
  @Test
  void aContainedPrincipalIsStillRefusedAboveTheStore() {
    memory(childScope, childPrincipal);
    var store = store();

    var selection = store.pin(read(childPrincipal, home(childScope)));
    assertEquals(UUID.fromString(childPrincipal), selection.pin().account());
    assertEquals(childScope, selection.pin().scope());
    assertEquals(childScope, store.pin(write(childPrincipal, home(childScope))).scope());

    // One layer up: the role question both Bound* entry points ask, asked here directly against
    // the same rows. No role for the child, and none for its container either.
    for (String actor : java.util.List.of(childPrincipal, HOLDER)) {
      assertTrue(
          jdbc.queryForList(
                  "SELECT m.role FROM project_members m JOIN projects p ON p.id=m.project_id"
                      + " WHERE p.name=? AND m.handle=?",
                  home(childScope),
                  actor)
              .isEmpty(),
          "a contained project grants no project role, which is what ScopedPermissions resolves");
    }
    assertTrue(
        jdbc.queryForList(
                "SELECT id FROM authority_scopes WHERE project_id=(SELECT id FROM projects"
                    + " WHERE name=?) AND kind='PROJECT'",
                home(childScope))
            .isEmpty(),
        "and it carries no PROJECT scope for a role to be granted on");
  }

  /** The store under test, with the privacy flag its own pins require. */
  private MemoryStore store() {
    var store = new MemoryStore(jdbc);
    store.protectInformation();
    return store;
  }

  /** A run-unbound project read: the smallest request shape {@code pin} will accept. */
  private MemoryQueries.Request read(String actor, String project) {
    return new MemoryQueries.Request(
        actor,
        io.aeyer.plowshare.protocol.Home.of(project),
        MemoryQueries.Kind.RECALL,
        "what is known",
        5,
        false,
        new io.aeyer.plowshare.server.embedding.QueryEmbeddings.Origin(null, () -> {}));
  }

  /** A non-learning write: the arm under test is the one keyed on the acting principal. */
  private MemoryWrites.Request write(String actor, String project) {
    return new MemoryWrites.Request(
        actor,
        io.aeyer.plowshare.protocol.Home.of(project),
        new io.aeyer.plowshare.protocol.MemoryProposal(
            "fixture", "project", "body", "fixture", "fixture"),
        MemoryWrites.Origin.request(() -> {}));
  }

  @Test
  void anOrdinaryAccountsMemoryAccessIsUnchanged() {
    memory(parentScope, HOLDER);
    globalMemory();

    assertTrue(readAdmits(HOLDER, "parent"), "a contributor reads its project's memories");
    assertTrue(writeAdmits(HOLDER, "parent"), "and files into them");
    assertFalse(readAdmits(STRANGER, "parent"), "a non-member does not");
    assertFalse(
        writeAdmits(HOLDER, null), "the Global tier's write still requires a server administrator");
    assertTrue(writeAdmits(ADMINISTRATOR, null), "which the administrator has");
  }
}
