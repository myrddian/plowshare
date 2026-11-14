package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import io.aeyer.plowshare.server.archive.CachedProjectMembers;
import io.aeyer.plowshare.server.archive.JdbcProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.auth.CachedSessionAuthority;
import io.aeyer.plowshare.server.auth.JdbcAccountAdministrationRepository;
import io.aeyer.plowshare.server.auth.SessionAuthority;
import io.aeyer.plowshare.server.auth.SessionAuthority.Session;
import io.aeyer.plowshare.server.auth.StoredSessionAuthority;
import io.aeyer.plowshare.server.cache.BoundedSnapshotCache;
import io.aeyer.plowshare.server.cache.JdbcProjectionVersions;
import io.aeyer.plowshare.server.cache.ProjectionChangeSignals;
import io.aeyer.plowshare.server.cache.ProjectionVersions;
import io.aeyer.plowshare.server.cache.SnapshotCache;
import io.aeyer.plowshare.server.cache.VersionedProjectionCache;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import io.aeyer.plowshare.server.testing.AuthorityFixtures;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The in-memory authority evaluation must give exactly the verdicts of the SQL authority functions.
 *
 * <p>After every fixture mutation, each {@link AuthoritySnapshot} method is compared with its
 * function for every account and project, and {@link CachedProjectMembers} with {@link
 * JdbcProjectMembers} for every read it answers from memory. Mutations cover account enablement,
 * server administration, bootstrap accounts, service-token issuance, revocation and expiry, owner
 * disablement, token scope ceilings, membership removal, delegated grant expiry and revocation,
 * client membership and personal spaces. A change to one of those SQL functions that is not
 * mirrored in Java fails here.
 *
 * <p>Every state also compares the snapshot's live grants for every (account, agent) recipient, the
 * lineage of every grant and an unknown id, and the socket session facts of every account at
 * several versions with {@link JdbcScopedGrantRepository} and {@link AdminStore}, including order.
 * The fixture includes delegated agent grants (one issued by an agent, one expiring) so these
 * comparisons see agent recipients, multi-link chains and their revocation.
 */
@Tag("full-db")
@Testcontainers
class AuthoritySnapshotDifferentialDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static SingleConnectionDataSource source;
  private JdbcTemplate jdbc;
  private JdbcProjectMembers stored;
  private JdbcScopedGrantRepository grants;
  private JdbcAuthoritySnapshotRepository repository;
  private UUID token;
  private final List<String> handles =
      List.of("alice", "bob", "carol", "admin", "owner", "@service/probe", "missing");
  private final List<String> projects = new ArrayList<>();
  private AdminStore accounts;

  /** Agent keys compared in every state; {@code ghost} never holds a grant. */
  private static final List<String> AGENTS = List.of("helper", "scout", "ghost");

  /** Comparisons per kind across the class, reported once after all tests. */
  private static final Map<String, Long> COMPARISONS = new TreeMap<>();

  private static final AtomicInteger STATES = new AtomicInteger();

  @AfterAll
  static void report() {
    System.out.println(
        "AuthoritySnapshotDifferentialDatabaseTest states="
            + STATES
            + " comparisons="
            + COMPARISONS);
  }

  private static void same(String kind, Object expected, Object actual, String message) {
    assertEquals(expected, actual, message);
    COMPARISONS.merge(kind, 1L, Long::sum);
  }

  @BeforeAll
  static void migrate() {
    source =
        new SingleConnectionDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    source.setAutoCommit(true);
    Flyway.configure().dataSource(source).load().migrate();
  }

  @BeforeEach
  void setup() {
    jdbc = new JdbcTemplate(source);
    AuthorityFixtures.truncate(jdbc, "TRUNCATE admins,projects CASCADE");
    stored = new JdbcProjectMembers(jdbc);
    grants = new JdbcScopedGrantRepository(jdbc);
    accounts = new AdminStore(jdbc);
    repository = new JdbcAuthoritySnapshotRepository(jdbc, 10_000);
    jdbc.update(
        "INSERT INTO admins(handle,password_hash) VALUES('alice','fixture'),('bob','fixture'),('carol','fixture')");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin) VALUES('admin','fixture',true)");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,account_kind,must_change_password) VALUES('owner','fixture','SERVICE',false),('@service/probe','fixture','SERVICE_TOKEN',false)");
    for (String handle : List.of("alice", "bob", "carol", "admin"))
      AuthorityFixtures.personalIdentity(jdbc, handle);
    jdbc.update("INSERT INTO projects(name) VALUES('one'),('two'),('client:desk')");
    member("one", "alice", "MANAGER");
    member("one", "bob", "VIEWER");
    member("two", "bob", "CONTRIBUTOR");
    member("one", "owner", "MANAGER");
    member("two", "owner", "CONTRIBUTOR");
    member("client:desk", "carol", "CONTRIBUTOR");
    token = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO service_tokens(id,owner_handle,name,principal_handle,digest,expires_at) VALUES(?,'owner','fixture','@service/probe',?,now()+interval '1 hour')",
        token,
        token.toString());
    jdbc.update(
        "INSERT INTO service_token_scopes(token_id,project_id,role) SELECT ?,id,'CONTRIBUTOR' FROM projects WHERE name='one'",
        token);
    jdbc.update(
        "INSERT INTO service_token_scopes(token_id,project_id,role) SELECT ?,id,'VIEWER' FROM projects WHERE name='two'",
        token);
    // Delegated agent grants on 'one': alice forces MANAGER onto her agent 'helper', which issues
    // READER to bob's agent 'scout' (a two-link chain issued by an agent); alice also issues an
    // expiring CONTRIBUTOR to her own 'scout'.
    delegate("alice", null, "one", "alice", "helper", "MANAGER", true, null);
    delegate("alice", "helper", "one", "bob", "scout", "READER", false, null);
    delegate(
        "alice",
        null,
        "one",
        "alice",
        "scout",
        "CONTRIBUTOR",
        false,
        Timestamp.from(Instant.now().plusSeconds(3600)));
    projects.clear();
    projects.addAll(jdbc.queryForList("SELECT name FROM projects ORDER BY name", String.class));
    projects.addAll(List.of("absent", "personal:" + "00", PersonalSpaces.name("missing")));
  }

  private void member(String project, String handle, String role) {
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,?,? FROM projects WHERE name=?",
        handle,
        role,
        project);
  }

  /**
   * Issues one ROLE grant to {@code recipient}'s {@code agent}, from the issuer's (or its agent's)
   * live SELF grant on {@code project}; the V216 insert trigger checks it as in service.
   */
  private void delegate(
      String issuer,
      String issuerAgent,
      String project,
      String recipient,
      String agent,
      String role,
      boolean forced,
      Timestamp expires) {
    int issued =
        jdbc.update(
            "INSERT INTO authority_grants(subject_account,subject_agent,scope_id,role_id,requested_role,"
                + "issuer_account,issuer_agent,issuing_grant,provenance,manager_forced,expires_at)"
                + " SELECT target.id,?,parent.scope_id,role.id,?,parent.subject_account,"
                + "parent.subject_agent,parent.id,'ROLE',?,?"
                + " FROM authority_grants parent JOIN authority_accounts source"
                + " ON source.id=parent.subject_account AND source.legacy_handle=?"
                + " JOIN authority_scopes scope ON scope.id=parent.scope_id"
                + " JOIN projects p ON p.id=scope.project_id"
                + " CROSS JOIN authority_accounts target CROSS JOIN authority_roles role"
                + " WHERE parent.revoked_at IS NULL AND parent.coverage='SELF' AND p.name=?"
                + " AND parent.subject_agent IS NOT DISTINCT FROM CAST(? AS text)"
                + " AND target.legacy_handle=? AND role.name=? LIMIT 1",
            agent,
            role,
            forced,
            expires,
            issuer,
            project,
            issuerAgent,
            recipient,
            role);
    assertEquals(1, issued, "delegated " + role + " to " + recipient + "/" + agent);
  }

  private AuthoritySnapshot snapshot() {
    return new AuthoritySnapshot(repository.load().orElseThrow());
  }

  private Optional<String> sql(String function, String project, String account) {
    return Optional.ofNullable(
        jdbc.queryForObject("SELECT " + function + "(?,?)", String.class, project, account));
  }

  /** {@code EXISTS(SELECT 1 FROM intrinsic)} exactly as V236 and V241 write it. */
  private static final String SYSTEM_PROCESSING =
      """
      WITH live AS MATERIALIZED (SELECT id FROM authority_live_grants()),
      intrinsic AS MATERIALIZED (
       SELECT g.id FROM authority_grants g JOIN live ON live.id=g.id
       JOIN authority_roles role ON role.id=g.role_id
       JOIN authority_role_actions a ON a.role_id=role.id AND a.role_version=g.role_version
        AND a.profile='DEFAULT'
       WHERE NOT role.assignable AND role.intrinsic_account=g.subject_account
        AND g.coverage='ALL_SCOPES' AND g.subject_agent IS NULL
       GROUP BY g.id
       HAVING count(*) FILTER(WHERE a.action IN ('READ','WRITE','CONVERSATION_CREATE','RUN_MODEL'))=4)
      SELECT EXISTS(SELECT 1 FROM intrinsic)
      """;

  /** Every snapshot method and every cached membership read against its SQL source. */
  private void parity(String state) {
    STATES.incrementAndGet();
    var snapshot = snapshot();
    grantParity(state, snapshot);
    sessionParity(state, snapshot);
    var members = new CachedProjectMembers(stored, () -> Optional.of(snapshot));
    var live = new HashMap<UUID, Integer>();
    jdbc.query(
        "SELECT id,depth FROM authority_live_grants()",
        (RowCallbackHandler) row -> live.put(row.getObject(1, UUID.class), row.getInt(2)));
    assertEquals(live, snapshot.liveGrants(), state + ": authority_live_grants");
    assertEquals(
        jdbc.queryForObject(SYSTEM_PROCESSING, Boolean.class),
        snapshot.systemProcessingAuthorized(),
        state + ": system processing grant");
    var targets = new ArrayList<String>(projects);
    targets.add(null);
    targets.add("no-such-project");
    for (String project : targets)
      assertEquals(
          jdbc.queryForObject("SELECT information_system_scope_valid(?)", Boolean.class, project),
          snapshot.systemScopeValid(project),
          state + ": information_system_scope_valid " + project);
    for (String handle : handles) {
      assertEquals(
          jdbc.queryForObject("SELECT account_active(?)", Boolean.class, handle),
          snapshot.accountActive(handle),
          state + ": account_active " + handle);
      assertEquals(
          stored.isServerAdmin(handle),
          members.isServerAdmin(handle),
          state + ": isServerAdmin " + handle);
      assertEquals(
          stored.authorityAccount(handle),
          members.authorityAccount(handle),
          state + ": authorityAccount " + handle);
      assertEquals(
          stored.roles(projects, handle),
          members.roles(projects, handle),
          state + ": roles " + handle);
      assertEquals(
          stored.readableProjects(projects, handle),
          members.readableProjects(projects, handle),
          state + ": readableProjects " + handle);
      for (String project : projects) {
        String pair = state + ": " + project + "/" + handle;
        assertEquals(
            sql("authority_project_role", project, handle),
            snapshot.authorityProjectRole(project, handle),
            pair + " authority_project_role");
        assertEquals(
            sql("service_project_role", project, handle),
            snapshot.serviceProjectRole(project, handle),
            pair + " service_project_role");
        assertEquals(stored.role(project, handle), members.role(project, handle), pair + " role");
        assertEquals(
            stored.isMember(project, handle),
            members.isMember(project, handle),
            pair + " isMember");
        if (!project.startsWith("personal:") || project.length() > 11)
          assertEquals(
              grants.projectLineages(project, handle),
              snapshot.projectLineages(project, handle),
              pair + " projectLineages");
      }
    }
  }

  /**
   * {@code live} for every (account, agent) recipient and {@code lineage} for every grant id and an
   * unknown one, element for element and in order.
   */
  private void grantParity(String state, AuthoritySnapshot snapshot) {
    var agents = new ArrayList<Optional<String>>();
    agents.add(Optional.empty());
    AGENTS.forEach(agent -> agents.add(Optional.of(agent)));
    for (UUID account :
        jdbc.queryForList("SELECT id FROM authority_accounts ORDER BY id", UUID.class))
      for (var agent : agents) {
        var recipient = new GrantRecipient(new AccountId(account), agent);
        same(
            "live",
            grants.live(recipient),
            snapshot.live(recipient),
            state + ": live " + recipient);
      }
    var ids = new ArrayList<>(jdbc.queryForList("SELECT id FROM authority_grants", UUID.class));
    ids.add(UUID.randomUUID());
    for (UUID id : ids)
      same("lineage", grants.lineage(id), snapshot.lineage(id), state + ": lineage " + id);
  }

  /**
   * {@code sessionCurrent} for every account (fixture handles, every {@code admins} row and an
   * unknown handle) at versions 0..2 and its stored version and the next, and one {@code
   * currentSessions} batch of all of them, against the snapshot's session fence alone and through
   * {@link CachedSessionAuthority}.
   */
  private void sessionParity(String state, AuthoritySnapshot snapshot) {
    var everyone = new HashSet<>(handles);
    everyone.addAll(jdbc.queryForList("SELECT handle FROM admins", String.class));
    var sessions = new HashSet<Session>();
    for (String handle : everyone) {
      var versions = new HashSet<>(List.of(0L, 1L, 2L));
      jdbc.queryForList("SELECT session_version FROM admins WHERE handle=?", Long.class, handle)
          .forEach(
              version -> {
                versions.add(version);
                versions.add(version + 1);
              });
      for (long version : versions) {
        sessions.add(new Session(handle, version));
        same(
            "sessionCurrent",
            accounts.sessionCurrent(handle, version),
            snapshot.activeSessionVersion(handle).filter(fence -> fence == version).isPresent(),
            state + ": sessionCurrent " + handle + "@" + version);
      }
    }
    var expected = accounts.currentSessions(sessions);
    var inMemory = new HashSet<Session>();
    for (var session : sessions)
      if (snapshot
          .activeSessionVersion(session.handle())
          .filter(fence -> fence == session.version())
          .isPresent()) inMemory.add(session);
    same("currentSessions", expected, Set.copyOf(inMemory), state + ": currentSessions");
    same(
        "currentSessions",
        expected,
        new CachedSessionAuthority(
                new StoredSessionAuthority(accounts), () -> Optional.of(snapshot))
            .currentSessions(sessions),
        state + ": cached currentSessions");
  }

  @Test
  void everyAuthorityChangeGivesTheSameVerdictInMemoryAsInSql() {
    parity("baseline");
    var mutations = new LinkedHashMap<String, String>();
    mutations.put("bob disabled", "UPDATE admins SET enabled=false WHERE handle='bob'");
    mutations.put("bob enabled", "UPDATE admins SET enabled=true WHERE handle='bob'");
    mutations.put("alice server admin", "UPDATE admins SET server_admin=true WHERE handle='alice'");
    // V104 admits bootstrap only for a non-administrator that must change its password.
    mutations.put(
        "bob bootstrap",
        "UPDATE admins SET bootstrap=true,must_change_password=true WHERE handle='bob'");
    mutations.put(
        "bob ordinary",
        "UPDATE admins SET bootstrap=false,must_change_password=false WHERE handle='bob'");
    mutations.put("credential withdrawn", "UPDATE service_tokens SET credential_issued=false");
    mutations.put(
        "token revoked", "UPDATE service_tokens SET credential_issued=true,revoked_at=now()");
    mutations.put(
        "token expired",
        "UPDATE service_tokens SET revoked_at=NULL,expires_at=now()-interval '1 second'");
    mutations.put("token renewed", "UPDATE service_tokens SET expires_at=now()+interval '1 hour'");
    mutations.put("owner disabled", "UPDATE admins SET enabled=false WHERE handle='owner'");
    mutations.put("owner enabled", "UPDATE admins SET enabled=true WHERE handle='owner'");
    mutations.put(
        "token ceiling raised",
        "UPDATE service_token_scopes SET role='MANAGER' WHERE role='VIEWER'");
    mutations.put(
        "carol left the client project", "DELETE FROM project_members WHERE handle='carol'");
    mutations.put(
        "owner left project one",
        "DELETE FROM project_members WHERE handle='owner' AND project_id=(SELECT id FROM projects WHERE name='one')");
    mutations.put(
        "alice sessions revoked",
        "UPDATE admins SET session_version=session_version+1 WHERE handle='alice'");
    mutations.put(
        "service principal sessions revoked",
        "UPDATE admins SET session_version=session_version+1 WHERE handle='@service/probe'");
    mutations.put(
        "helper delegation revoked",
        "UPDATE authority_grants SET revoked_at=now(),version=version+1 WHERE subject_agent='helper'");
    mutations.put(
        "alice left project one",
        "DELETE FROM project_members WHERE handle='alice' AND project_id=(SELECT id FROM projects WHERE name='one')");
    for (var mutation : mutations.entrySet()) {
      jdbc.update(mutation.getValue());
      parity(mutation.getKey());
    }
  }

  @Test
  void delegatedGrantsMatchThroughRevocationExpiryAndIssuerRemoval() throws InterruptedException {
    String issue =
        "INSERT INTO authority_grants(subject_account,scope_id,role_id,requested_role,issuer_account,issuing_grant,provenance,expires_at)"
            + " SELECT recipient.id,parent.scope_id,role.id,'READER',parent.subject_account,parent.id,'ROLE',?"
            + " FROM authority_grants parent JOIN authority_accounts issuer ON issuer.id=parent.subject_account AND issuer.legacy_handle='alice'"
            + " JOIN authority_scopes scope ON scope.id=parent.scope_id JOIN projects p ON p.id=scope.project_id"
            + " CROSS JOIN authority_accounts recipient CROSS JOIN authority_roles role"
            + " WHERE parent.provenance='MEMBERSHIP' AND parent.revoked_at IS NULL AND p.name='two'"
            + " AND recipient.legacy_handle='carol' AND role.name='READER'";
    // alice manages 'two' so she can delegate there.
    member("two", "alice", "MANAGER");
    jdbc.update(issue, Timestamp.from(Instant.now().plusSeconds(3600)));
    parity("delegated with expiry");
    assertTrue(snapshot().authorityProjectRole("two", "carol").isPresent());
    // Grants change only by one versioned revocation.
    jdbc.update(
        "UPDATE authority_grants SET revoked_at=now(),version=version+1 WHERE provenance='ROLE'");
    parity("delegation revoked");
    assertTrue(snapshot().authorityProjectRole("two", "carol").isEmpty());
    // Grants are immutable, so expiry is observed by waiting it out. Comparing only after the
    // instant has passed keeps the snapshot and the SQL calls on the same side of it; in service,
    // the version token's expiry window gives the same guarantee.
    jdbc.update(issue, Timestamp.from(Instant.now().plusMillis(300)));
    Instant expires =
        jdbc.queryForObject(
                "SELECT max(expires_at) FROM authority_grants WHERE provenance='ROLE' AND revoked_at IS NULL",
                Timestamp.class)
            .toInstant();
    while (Instant.now().isBefore(expires.plusMillis(50))) Thread.sleep(10);
    parity("delegation expired");
    assertTrue(snapshot().authorityProjectRole("two", "carol").isEmpty());
    jdbc.update(issue, (Object) null);
    parity("open delegation");
    assertEquals(1, snapshot().projectLineages("two", "carol").size());
    assertTrue(snapshot().projectLineages("two", "carol").getFirst().size() >= 2);
    jdbc.update(
        "DELETE FROM project_members WHERE handle='alice' AND project_id=(SELECT id FROM projects WHERE name='two')");
    parity("issuer removed");
    assertTrue(snapshot().authorityProjectRole("two", "carol").isEmpty());
  }

  /**
   * The root policy, built-in roles and BUILTIN account are immutable, so SYSTEM processing stays
   * authorized on a valid installation; parity covers it in every fixture state. The decided V241
   * overload agrees with the SQL-decided one and, given a decision, never walks live grants.
   */
  @Test
  void theDecidedOverloadMatchesSqlAndSkipsTheGrantWalk() {
    assertTrue(snapshot().systemProcessingAuthorized(), "Migrations install SYSTEM processing");
    parity("system processing");
    String revisions = "SELECT count(*) FROM information_system_processing_revisions(%s)";
    assertEquals(
        jdbc.queryForObject(revisions.formatted(""), Long.class),
        jdbc.queryForObject(revisions.formatted("NULL::boolean"), Long.class));
    assertEquals(0L, jdbc.queryForObject(revisions.formatted("FALSE"), Long.class));
    // EXPLAIN shows only a Function Scan for a SQL function, so count the reads of admins that
    // account_active performs inside authority_live_grants, within one transaction.
    assertTrue(adminReads("NULL::boolean") > 0, "The SQL-decided call walks live grants");
    assertEquals(0L, adminReads("TRUE"), "A decided call must not evaluate live grants");
  }

  /** Scans of admins caused by one call, from this transaction's own statistics. */
  private long adminReads(String argument) {
    String reads =
        "SELECT coalesce(seq_scan,0)+coalesce(idx_scan,0) FROM pg_stat_xact_user_tables"
            + " WHERE relname='admins'";
    jdbc.execute("BEGIN");
    try {
      long before = jdbc.queryForObject(reads, Long.class);
      jdbc.queryForObject(
          "SELECT count(*) FROM information_system_processing_revisions(" + argument + ")",
          Long.class);
      return jdbc.queryForObject(reads, Long.class) - before;
    } finally {
      jdbc.execute("ROLLBACK");
    }
  }

  @Test
  void anOversizedTableYieldsToSqlInsteadOfAPartialSnapshot() {
    assertTrue(new JdbcAuthoritySnapshotRepository(jdbc, 1).load().isEmpty());
    var members =
        new CachedProjectMembers(
            stored,
            () -> new JdbcAuthoritySnapshotRepository(jdbc, 1).load().map(AuthoritySnapshot::new));
    assertEquals(stored.role("one", "alice"), members.role("one", "alice"));
  }

  @Test
  void projectMembersBeanTypeIsTheStoredRepository() {
    ProjectMembers members = new CachedProjectMembers(stored, AuthoritySnapshots.none());
    assertEquals(stored.role("one", "alice"), members.role("one", "alice"));
    assertEquals(stored.members("one"), members.members("one"));
  }

  /**
   * The production composition (versioned cache, {@link CachedAuthoritySnapshots}, the grant and
   * session decorators) against real AUTHORITY counters: a {@code session_version} bump through the
   * administration path and a grant revocation through the repository each move the version, so the
   * next answer is loaded again and reflects the change. Notification trust is not enabled here;
   * {@code NotifiedProjectionDatabaseTest} bounds that path.
   */
  @Test
  void aSessionRevocationAndAGrantRevocationInvalidateTheSnapshot() {
    var authoritative =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var versions = new JdbcProjectionVersions(jdbc, authoritative, 128);
    var domains = Set.of(ProjectionVersions.Domain.AUTHORITY);
    var signals = mock(ProjectionChangeSignals.class);
    when(signals.subscribe(eq(domains), any()))
        .thenAnswer(
            call -> {
              ProjectionChangeSignals.Observer observer = call.getArgument(1);
              observer.stateChanged(new ProjectionChangeSignals.State(1, true));
              return (ProjectionChangeSignals.Subscription) () -> {};
            });
    var loads = new AtomicInteger();
    try (var cache =
        new VersionedProjectionCache<Optional<AuthoritySnapshot>>(
            new BoundedSnapshotCache<>(
                new SnapshotCache.Namespace(UUID.randomUUID().toString(), "authority"),
                new SnapshotCache.Limits(
                    8, 4_000_000, 2, 4_000_000, 64, 64, 2, 32, Duration.ofSeconds(5))),
            versions,
            signals,
            domains,
            1_000_000)) {
      var snapshots =
          new CachedAuthoritySnapshots(
              cache,
              () -> {
                loads.incrementAndGet();
                return repository.load();
              },
              ReadAdmission.direct());
      var cachedGrants = new CachedScopedGrantRepository(grants, snapshots);
      SessionAuthority sessions =
          new CachedSessionAuthority(
              new StoredSessionAuthority(accounts) {
                @Override
                public boolean sessionCurrent(String handle, long version) {
                  throw new AssertionError("An accepted session must not reach SQL");
                }
              },
              snapshots);
      long version = accounts.sessionVersion("alice");
      assertTrue(sessions.sessionCurrent("alice", version));
      assertTrue(sessions.sessionCurrent("alice", version));
      assertEquals(1, loads.get(), "One load answers repeated checks while the version holds");

      var token = versions.capture(domains).orElseThrow();
      new JdbcAccountAdministrationRepository(jdbc).revokeSessions("alice");
      assertFalse(versions.current(token), "A session_version bump moves AUTHORITY");
      assertEquals(
          Optional.of(version + 1),
          snapshots.current().orElseThrow().activeSessionVersion("alice"));
      assertEquals(2, loads.get());
      assertEquals(
          accounts.currentSessions(Set.of(new Session("alice", version))),
          snapshots
              .current()
              .orElseThrow()
              .activeSessionVersion("alice")
              .filter(fence -> fence == version)
              .map(fence -> Set.of(new Session("alice", fence)))
              .orElse(Set.of()));
      assertTrue(sessions.sessionCurrent("alice", version + 1));

      UUID alice =
          jdbc.queryForObject(
              "SELECT id FROM authority_accounts WHERE legacy_handle='alice'", UUID.class);
      var helper = GrantRecipient.agent(new AccountId(alice), "helper");
      var bobScout =
          GrantRecipient.agent(
              new AccountId(
                  jdbc.queryForObject(
                      "SELECT id FROM authority_accounts WHERE legacy_handle='bob'", UUID.class)),
              "scout");
      var delegated = cachedGrants.live(helper);
      assertEquals(1, delegated.size());
      assertTrue(
          cachedGrants.lineage(cachedGrants.live(bobScout).getFirst().id()).size() >= 3,
          "scout, helper, alice's membership and its ancestry");
      int before = loads.get();
      var membership =
          grants.live(GrantRecipient.account(new AccountId(alice))).stream()
              .filter(binding -> binding.project().equals(Optional.of("one")))
              .findFirst()
              .orElseThrow();
      token = versions.capture(domains).orElseThrow();
      grants.revoke(
          delegated.getFirst().id(),
          delegated.getFirst().version(),
          GrantRecipient.account(new AccountId(alice)),
          membership.id());
      assertFalse(versions.current(token), "A grant revocation moves AUTHORITY");
      assertEquals(List.of(), cachedGrants.live(helper));
      assertEquals(List.of(), cachedGrants.lineage(delegated.getFirst().id()));
      assertEquals(grants.live(bobScout), cachedGrants.live(bobScout));
      assertTrue(cachedGrants.live(bobScout).isEmpty(), "The agent-issued child lost its parent");
      assertEquals(before + 1, loads.get(), "The revocation forced exactly one reload");
    }
  }
}
