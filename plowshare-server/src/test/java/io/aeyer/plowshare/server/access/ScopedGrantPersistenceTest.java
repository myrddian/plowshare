package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.access.AuthorityIds.*;
import io.aeyer.plowshare.server.access.ScopedGrantRepository.*;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** PostgreSQL proves migration preservation, locking, trigger invariants and atomic grant/audit. */
@Tag("full-db")
@Testcontainers
class ScopedGrantPersistenceTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withUrlParam("sslmode", "disable")
          .withUrlParam("gssEncMode", "disable");

  private DriverManagerDataSource source;
  private JdbcTemplate jdbc;
  private IdentityRepository identities;
  private ScopedGrantRepository grants;
  private JdbcProjectMembers members;
  private ApplicationPolicy applications;
  private ScopedAuthority authority;

  @BeforeEach
  void legacy() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var schema = "grants_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(source).execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
    new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
    var properties = new Properties();
    properties.setProperty("currentSchema", schema + ",public");
    source.setConnectionProperties(properties);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .target("155")
        .load()
        .migrate();
    jdbc = new JdbcTemplate(source);
    user("admin", true);
    user("alice", false);
    user("bob", false);
    user("carol", false);
    project("one");
    project("two");
  }

  private void migrate() {
    Flyway.configure().dataSource(source).load().migrate();
    identities = new JdbcIdentityRepository(jdbc);
    grants = new JdbcScopedGrantRepository(jdbc);
    members = new JdbcProjectMembers(jdbc);
    applications = mock(ApplicationPolicy.class);
    when(applications.read(anyString()))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.EXTERNAL, Map.of()));
    authority =
        new LiveScopedAuthority(
            grants, applications, new ApplicationGrantLineage(grants, applications), identities);
  }

  private void user(String name, boolean admin) {
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password,server_admin) VALUES (?,'fixture',FALSE,?)",
        name,
        admin);
  }

  private void project(String name) {
    jdbc.update("INSERT INTO projects(name) VALUES (?)", name);
  }

  private GrantRecipient who(String handle) {
    return GrantRecipient.account(identities.account(handle).id());
  }

  private ScopeId scope(String project) {
    return identities.project(project).id();
  }

  private UUID control() {
    return grants.live(who("admin")).stream()
        .filter(
            g ->
                g.provenance().equals("ADMIN_CONTROL")
                    && g.coverage() == Coverage.ORDINARY_PROJECT_CONTROL)
        .findFirst()
        .orElseThrow()
        .id();
  }

  private Binding issue(String subject, StandardRole role, String project) {
    return grants.issue(
        new Issue(
            UUID.randomUUID(),
            who(subject),
            scope(project),
            role,
            who("admin"),
            control(),
            Optional.empty(),
            false));
  }

  private long count(String table) {
    return switch (table) {
      case "grants" -> jdbc.queryForObject("SELECT count(*) FROM authority_grants", Long.class);
      case "audit" -> jdbc.queryForObject("SELECT count(*) FROM authority_grant_audit", Long.class);
      default -> throw new IllegalArgumentException();
    };
  }

  @Test
  void batchProjectRolesMatchScalarPolicyForHumansTokensAndLiveWithdrawal() {
    migrate();
    members.assign("one", "alice", ProjectRole.MANAGER, "admin", true);
    members.assign("two", "bob", ProjectRole.VIEWER, "admin", true);
    var serviceAccounts = new io.aeyer.plowshare.server.auth.JdbcServiceAccountRepository(jdbc);
    serviceAccounts.create("service");
    members.assign("one", "service", ProjectRole.MANAGER, "admin", true);
    UUID token = UUID.randomUUID();
    String principal = "@service/" + token;
    serviceAccounts.issue(
        token,
        "service",
        "reader",
        principal,
        "2".repeat(64),
        OffsetDateTime.now().plusDays(1),
        List.of(
            new io.aeyer.plowshare.server.auth.ServiceAccounts.Scope("one", ProjectRole.VIEWER)));
    var names = List.of("one", "two", "missing", "personal:someone-else");
    for (String actor : List.of("alice", "bob", "admin", principal)) assertRoleParity(names, actor);
    jdbc.update("UPDATE service_tokens SET revoked_at=now() WHERE id=?", token);
    assertRoleParity(names, principal);
    assertTrue(members.roles(names, principal).isEmpty());
    jdbc.update("UPDATE admins SET enabled=false WHERE handle='alice'");
    assertRoleParity(names, "alice");
    assertTrue(members.roles(names, "alice").isEmpty());
  }

  private void assertRoleParity(List<String> names, String actor) {
    var expected = new LinkedHashMap<String, ProjectRole>();
    for (String name : names) members.role(name, actor).ifPresent(role -> expected.put(name, role));
    assertEquals(expected, members.roles(names, actor));
  }

  @Test
  void migration_snapshots_legacy_admin_data_but_new_admins_and_projects_get_control_only() {
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'alice','VIEWER' FROM projects WHERE name='one'");
    migrate();
    assertEquals(ProjectRole.VIEWER, members.role("one", "alice").orElseThrow());
    assertEquals(ProjectRole.MANAGER, members.role("one", "admin").orElseThrow());
    var snapshot =
        grants.live(who("admin")).stream()
            .filter(
                g -> g.provenance().equals("LEGACY_ADMIN_DATA") && g.scope().equals(scope("one")))
            .findFirst()
            .orElseThrow();
    assertEquals(StandardRole.MANAGER, snapshot.role());
    var restarted = new JdbcScopedGrantRepository(jdbc);
    assertEquals(snapshot, restarted.find(snapshot.id()).orElseThrow());
    project("later");
    user("newadmin", true);
    assertTrue(members.mayAdminister("later", "admin"));
    assertTrue(members.mayAdminister("one", "newadmin"));
    assertTrue(
        authority.allows(
            who("newadmin"),
            identities.scope(BuiltInIdentities.ADMINISTRATION),
            AuthorityAction.ADMINISTER));
    assertTrue(members.role("later", "admin").isEmpty());
    assertTrue(members.role("one", "newadmin").isEmpty());
    assertFalse(members.mayWork("one", "newadmin"));
    assertFalse(members.mayManage("one", "newadmin"));
    assertThrows(
        CallerFault.class, () -> members.requireRole("one", "newadmin", ProjectRole.MANAGER));
    assertEquals(List.of("manage"), members.managementAccess("one", "newadmin").permissions());
    assertEquals(List.of("manage"), members.access("one", "newadmin").permissions());
    var routes =
        new ProjectAuthorization(
            new JdbcResourceScopeRepository(jdbc),
            members,
            new io.aeyer.plowshare.server.auth.AdminStore(jdbc));
    assertTrue(
        routes.allowed(
            "project.access",
            AccessRequestDecoder.decode("project.access", Map.of("project", "one")),
            "newadmin"));
    assertFalse(
        routes.allowed(
            "conversation.list",
            AccessRequestDecoder.decode("conversation.list", Map.of("project", "one")),
            "newadmin"));
    assertFalse(
        jdbc.queryForObject(
            "SELECT information_project_readable(id,'newadmin') FROM projects WHERE name='one'",
            Boolean.class));
    assertTrue(
        authority.allows(
            who("newadmin"),
            identities.scope(BuiltInIdentities.GLOBAL_KNOWLEDGE),
            AuthorityAction.WRITE));
    assertFalse(
        authority.allows(
            who("newadmin"),
            identities.scope(BuiltInIdentities.GLOBAL_KNOWLEDGE),
            AuthorityAction.RUN_MODEL));
    jdbc.update("UPDATE admins SET server_admin=FALSE WHERE handle='admin'");
    assertTrue(members.role("one", "admin").isEmpty());
    assertFalse(members.mayAdminister("one", "admin"));
    assertTrue(grants.find(snapshot.id()).orElseThrow().revokedAt().isPresent());
    jdbc.update("UPDATE admins SET server_admin=TRUE WHERE handle='admin'");
    assertTrue(members.role("one", "admin").isEmpty());
    assertTrue(members.mayAdminister("one", "admin"));
    identities.verifyBuiltIns();
  }

  @Test
  void helper_matrix_is_scoped_and_admin_is_control_without_data_or_inference() {
    migrate();
    for (var role :
        List.of(
            StandardRole.READER,
            StandardRole.CONTRIBUTOR,
            StandardRole.MANAGER,
            StandardRole.ADMIN)) {
      var binding = issue("alice", role, "one");
      for (var action : AuthorityAction.values()) {
        boolean expected =
            switch (role) {
              case READER -> action == AuthorityAction.READ;
              case CONTRIBUTOR ->
                  Set.of(
                          AuthorityAction.READ,
                          AuthorityAction.WRITE,
                          AuthorityAction.CONVERSATION_CREATE,
                          AuthorityAction.RUN_MODEL)
                      .contains(action);
              case MANAGER -> action != AuthorityAction.ADMINISTER;
              case ADMIN ->
                  Set.of(
                          AuthorityAction.ADMINISTER,
                          AuthorityAction.MANAGE_ACCESS,
                          AuthorityAction.MANAGE_SCOPE,
                          AuthorityAction.SET_AUDIENCE)
                      .contains(action);
              default -> throw new AssertionError();
            };
        assertEquals(
            expected,
            authority.allowsUsing(who("alice"), identities.project("one"), action, binding.id()),
            role + " " + action);
        assertFalse(
            authority.allowsUsing(who("alice"), identities.project("two"), action, binding.id()));
      }
      grants.revoke(binding.id(), 1, who("admin"), control());
    }
    var delegatedAdmin =
        new AuthorizedRoleGrants(grants, authority, identities)
            .issue(
                new Issue(
                    UUID.randomUUID(),
                    who("bob"),
                    BuiltInIdentities.ADMINISTRATION,
                    StandardRole.ADMIN,
                    who("admin"),
                    control(),
                    Optional.empty(),
                    false));
    assertTrue(
        authority.allowsUsing(
            who("bob"),
            identities.scope(BuiltInIdentities.ADMINISTRATION),
            AuthorityAction.ADMINISTER,
            delegatedAdmin.id()));
    assertFalse(authority.allows(who("bob"), identities.project("one"), AuthorityAction.READ));
    assertFalse(
        authority.allows(who("bob"), identities.project("one"), AuthorityAction.ADMINISTER));
    assertTrue(identities.role(BuiltInIdentities.ADMIN).assignable());
    assertFalse(identities.role(BuiltInIdentities.SYSTEM_ROLE).assignable());
    assertTrue(authority.allows(who("SYSTEM"), identities.project("one"), AuthorityAction.READ));
    assertFalse(authority.allows(who("GLOBAL"), identities.project("one"), AuthorityAction.READ));
  }

  @Test
  void agent_descoping_force_and_rejection_are_durable_without_propagating_exception() {
    migrate();
    var worker = GrantRecipient.agent(who("alice").account(), "worker");
    var narrowed =
        grants.issue(
            new Issue(
                UUID.randomUUID(),
                worker,
                scope("one"),
                StandardRole.MANAGER,
                who("admin"),
                control(),
                Optional.empty(),
                false));
    assertEquals(StandardRole.CONTRIBUTOR, narrowed.role());
    assertEquals(StandardRole.MANAGER, narrowed.requested());
    assertFalse(narrowed.managerForced());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM authority_grant_audit WHERE grant_id=?",
            Integer.class,
            narrowed.id()));
    var forced =
        grants.issue(
            new Issue(
                UUID.randomUUID(),
                worker,
                scope("one"),
                StandardRole.MANAGER,
                who("admin"),
                control(),
                Optional.empty(),
                true));
    assertTrue(forced.managerForced());
    assertEquals(who("admin"), forced.issuer());
    assertTrue(authority.allows(worker, identities.project("one"), AuthorityAction.MANAGE_ACCESS));
    assertFalse(
        authority.allows(who("alice"), identities.project("one"), AuthorityAction.MANAGE_ACCESS));
    var child = GrantRecipient.agent(who("alice").account(), "child");
    var childGrant =
        grants.issue(
            new Issue(
                UUID.randomUUID(),
                child,
                scope("one"),
                StandardRole.MANAGER,
                worker,
                forced.id(),
                Optional.empty(),
                false));
    assertEquals(StandardRole.CONTRIBUTOR, childGrant.role());
    long grantsBefore = count("grants"), auditBefore = count("audit");
    for (var role : List.of(StandardRole.ADMIN, StandardRole.SYSTEM, StandardRole.GLOBAL_READ)) {
      assertThrows(
          CallerFault.class,
          () ->
              grants.issue(
                  new Issue(
                      UUID.randomUUID(),
                      worker,
                      scope("one"),
                      role,
                      who("admin"),
                      control(),
                      Optional.empty(),
                      true)));
    }
    assertThrows(
        CallerFault.class,
        () ->
            grants.issue(
                new Issue(
                    UUID.randomUUID(),
                    child,
                    scope("one"),
                    StandardRole.MANAGER,
                    worker,
                    forced.id(),
                    Optional.empty(),
                    true)));
    assertEquals(grantsBefore, count("grants"));
    assertEquals(auditBefore, count("audit"));
    grants.revoke(forced.id(), 1, who("admin"), control());
    assertFalse(authority.allows(child, identities.project("one"), AuthorityAction.READ));
  }

  @Test
  void service_force_and_raw_admin_agent_bypass_are_refused_without_partial_records() {
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password,server_admin,account_kind) VALUES ('service','fixture',FALSE,FALSE,'SERVICE')");
    migrate();
    var serviceManager = issue("service", StandardRole.MANAGER, "one");
    var agent = GrantRecipient.agent(who("alice").account(), "worker");
    long before = count("grants");
    assertThrows(
        CallerFault.class,
        () ->
            grants.issue(
                new Issue(
                    UUID.randomUUID(),
                    agent,
                    scope("one"),
                    StandardRole.MANAGER,
                    who("service"),
                    serviceManager.id(),
                    Optional.empty(),
                    true)));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO authority_grants(subject_account,subject_agent,scope_id,role_id,requested_role,issuer_account,issuing_grant,provenance) "
                    + "VALUES (?,'worker',?,?,'ADMIN',?,?,'ROLE')",
                who("alice").account().value(),
                scope("one").value(),
                BuiltInIdentities.ADMIN.value(),
                who("admin").account().value(),
                control()));
    assertEquals(before, count("grants"));
  }

  @Test
  void lineage_scope_expiry_version_and_recipient_checks_fail_closed() {
    migrate();
    var manager = issue("alice", StandardRole.MANAGER, "one");
    var child =
        grants.issue(
            new Issue(
                UUID.randomUUID(),
                who("bob"),
                scope("one"),
                StandardRole.READER,
                who("alice"),
                manager.id(),
                Optional.empty(),
                false));
    assertTrue(authority.allows(who("bob"), identities.project("one"), AuthorityAction.READ));
    assertThrows(
        DataAccessException.class,
        () ->
            grants.issue(
                new Issue(
                    UUID.randomUUID(),
                    who("carol"),
                    scope("two"),
                    StandardRole.READER,
                    who("alice"),
                    manager.id(),
                    Optional.empty(),
                    false)));
    assertThrows(
        CallerFault.class,
        () ->
            grants.issue(
                new Issue(
                    UUID.randomUUID(),
                    who("carol"),
                    scope("one"),
                    StandardRole.READER,
                    who("bob"),
                    manager.id(),
                    Optional.empty(),
                    false)));
    var expiring =
        grants.issue(
            new Issue(
                UUID.randomUUID(),
                who("carol"),
                scope("two"),
                StandardRole.MANAGER,
                who("admin"),
                control(),
                Optional.of(OffsetDateTime.now().plusHours(1)),
                false));
    assertThrows(
        DataAccessException.class,
        () ->
            grants.issue(
                new Issue(
                    UUID.randomUUID(),
                    who("bob"),
                    scope("two"),
                    StandardRole.READER,
                    who("carol"),
                    expiring.id(),
                    Optional.empty(),
                    false)));
    assertThrows(CallerFault.class, () -> grants.revoke(manager.id(), 2, who("admin"), control()));
    assertTrue(grants.find(manager.id()).orElseThrow().revokedAt().isEmpty());
    grants.revoke(manager.id(), 1, who("admin"), control());
    assertFalse(authority.allows(who("bob"), identities.project("one"), AuthorityAction.READ));
    assertTrue(grants.find(child.id()).orElseThrow().revokedAt().isEmpty());
    assertEquals(2, grants.find(manager.id()).orElseThrow().version());
    assertThrows(CallerFault.class, () -> grants.revoke(manager.id(), 1, who("admin"), control()));
    assertEquals(
        who("admin").account().value(),
        jdbc.queryForObject(
            "SELECT actor_account FROM authority_grant_audit WHERE grant_id=? AND event='REVOKE'",
            UUID.class,
            manager.id()));
  }

  @Test
  void membership_mutations_preserve_issuer_audit_and_rollback_when_audit_fails() {
    migrate();
    members.assign("one", "alice", ProjectRole.MANAGER, "admin", true);
    members.assign("one", "bob", ProjectRole.VIEWER, "alice", true);
    var binding = grants.live(who("bob")).getFirst();
    assertEquals(who("alice"), binding.issuer());
    assertEquals(StandardRole.READER, binding.role());
    long before = count("grants");
    jdbc.execute(
        "CREATE FUNCTION fail_grant_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture interruption'; END $$");
    jdbc.execute(
        "CREATE TRIGGER fail_grant_audit BEFORE INSERT ON authority_grant_audit FOR EACH ROW EXECUTE FUNCTION fail_grant_audit()");
    assertThrows(
        DataAccessException.class,
        () -> members.assign("one", "carol", ProjectRole.CONTRIBUTOR, "alice", true));
    assertFalse(members.members("one").contains("carol"));
    assertEquals(before, count("grants"));
    jdbc.execute("DROP TRIGGER fail_grant_audit ON authority_grant_audit");
    members.remove("one", "bob", "alice");
    assertTrue(members.role("one", "bob").isEmpty());
    assertEquals(2, grants.find(binding.id()).orElseThrow().version());
    assertEquals(
        who("alice").account().value(),
        jdbc.queryForObject(
            "SELECT actor_account FROM authority_grant_audit WHERE grant_id=? AND event='REVOKE'",
            UUID.class,
            binding.id()));
  }

  @Test
  void concurrent_manager_removals_leave_a_live_manager() throws Exception {
    migrate();
    for (var handle : List.of("alice", "bob"))
      jdbc.update(
          "INSERT INTO project_members(project_id,handle,role) SELECT id,?,'MANAGER' FROM projects WHERE name='one'",
          handle);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> removeAfter(start, "alice", "bob"));
      var second = executor.submit(() -> removeAfter(start, "bob", "alice"));
      start.countDown();
      assertEquals(
          1,
          (first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0));
    }
    assertEquals(1, members.members("one").size());
    assertEquals(
        ProjectRole.MANAGER, members.role("one", members.members("one").getFirst()).orElseThrow());
  }

  @Test
  void delegated_token_membership_keeps_credential_expiry_and_live_revocation() {
    migrate();
    var serviceAccounts = new io.aeyer.plowshare.server.auth.JdbcServiceAccountRepository(jdbc);
    serviceAccounts.create("service");
    members.assign("one", "service", ProjectRole.MANAGER, "admin", true);
    UUID token = UUID.randomUUID();
    String principal = "@service/" + token;
    var expiry = OffsetDateTime.now().plusDays(1);
    serviceAccounts.issue(
        token,
        "service",
        "manager",
        principal,
        "1".repeat(64),
        expiry,
        List.of(
            new io.aeyer.plowshare.server.auth.ServiceAccounts.Scope("one", ProjectRole.MANAGER)));
    members.assign("one", "bob", ProjectRole.CONTRIBUTOR, principal, true);
    var binding = grants.live(who("bob")).getFirst();
    assertEquals(Optional.of(token), binding.issuingToken());
    assertEquals(who(principal), binding.issuer());
    assertTrue(binding.expiresAt().orElseThrow().compareTo(expiry) <= 0);
    assertTrue(authority.allows(who("bob"), identities.project("one"), AuthorityAction.WRITE));
    jdbc.update("UPDATE service_tokens SET revoked_at=now() WHERE id=?", token);
    assertFalse(authority.allows(who("bob"), identities.project("one"), AuthorityAction.READ));
    assertTrue(members.role("one", "bob").isEmpty());
    assertFalse(members.isMember("one", "bob"));
    assertTrue(grants.find(binding.id()).isPresent());
  }

  @Test
  void issuer_manifest_narrowing_reaches_children_in_every_effective_project_decision() {
    migrate();
    members.assign("one", "alice", ProjectRole.MANAGER, "admin", true);
    members.assign("one", "bob", ProjectRole.CONTRIBUTOR, "alice", true);
    var effective =
        new ApplicationProjectMembers(
            members, applications, new ApplicationGrantLineage(grants, applications));
    when(applications.read("one"))
        .thenReturn(
            new ApplicationPolicy.Boundary(
                ApplicationPolicy.Kind.APPLICATION,
                Map.of("alice", ProjectRole.VIEWER, "bob", ProjectRole.MANAGER)));
    assertTrue(effective.role("one", "bob").isEmpty());
    assertTrue(effective.roles(List.of("one"), "bob").isEmpty());
    assertFalse(authority.allows(who("bob"), identities.project("one"), AuthorityAction.READ));
    when(applications.read("one"))
        .thenReturn(
            new ApplicationPolicy.Boundary(
                ApplicationPolicy.Kind.APPLICATION,
                Map.of("alice", ProjectRole.MANAGER, "bob", ProjectRole.MANAGER)));
    assertEquals(ProjectRole.CONTRIBUTOR, effective.role("one", "bob").orElseThrow());
    assertEquals(Map.of("one", ProjectRole.CONTRIBUTOR), effective.roles(List.of("one"), "bob"));
    assertTrue(authority.allows(who("bob"), identities.project("one"), AuthorityAction.WRITE));
  }

  @Test
  void concurrent_versioned_revoke_commits_one_audit_and_grants_survive_source_project_deletion()
      throws Exception {
    migrate();
    var binding = issue("bob", StandardRole.READER, "one");
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Callable<Boolean> revoke =
          () -> {
            start.await();
            try {
              grants.revoke(binding.id(), 1, who("admin"), control());
              return true;
            } catch (CallerFault refused) {
              return false;
            }
          };
      var first = executor.submit(revoke);
      var second = executor.submit(revoke);
      start.countDown();
      assertEquals(
          1,
          (first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0));
    }
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM authority_grant_audit WHERE grant_id=?",
            Integer.class,
            binding.id()));
    jdbc.update("DELETE FROM projects WHERE name='one'");
    assertTrue(grants.find(binding.id()).isPresent());
    assertTrue(grants.live(who("bob")).isEmpty());
    project("one");
    assertTrue(members.role("one", "bob").isEmpty());
  }

  @Test
  void expiry_disabled_ancestors_and_bounded_chain_are_live_and_history_is_immutable()
      throws Exception {
    migrate();
    UUID expired = UUID.randomUUID();
    // Materialize retained history with a past expiry; no wall-clock sleeps or timing window.
    jdbc.update(
        "INSERT INTO authority_grants(id,subject_account,scope_id,role_id,requested_role,issuer_account,issuing_grant,"
            + "created_at,expires_at,provenance) VALUES (?,?,?,?,'MANAGER',?,?,now()-interval '2 hours',now()-interval '1 hour','ROLE')",
        expired,
        who("alice").account().value(),
        scope("one").value(),
        BuiltInIdentities.MANAGER.value(),
        who("admin").account().value(),
        control());
    assertTrue(grants.live(who("alice")).isEmpty());
    assertTrue(grants.find(expired).isPresent());
    var parent = issue("alice", StandardRole.MANAGER, "two");
    var child =
        grants.issue(
            new Issue(
                UUID.randomUUID(),
                who("bob"),
                scope("two"),
                StandardRole.READER,
                who("alice"),
                parent.id(),
                Optional.empty(),
                false));
    jdbc.update("UPDATE admins SET enabled=FALSE WHERE handle='alice'");
    assertTrue(grants.lineage(child.id()).isEmpty());
    jdbc.update("UPDATE admins SET enabled=TRUE WHERE handle='alice'");
    Binding current = parent;
    for (int i = 0; i < 30; i++) {
      var recipient = current.recipient().equals(who("alice")) ? who("bob") : who("alice");
      current =
          grants.issue(
              new Issue(
                  UUID.randomUUID(),
                  recipient,
                  scope("two"),
                  StandardRole.MANAGER,
                  current.recipient(),
                  current.id(),
                  Optional.empty(),
                  false));
    }
    var deepest = current;
    assertThrows(
        DataAccessException.class,
        () ->
            grants.issue(
                new Issue(
                    UUID.randomUUID(),
                    who("carol"),
                    scope("two"),
                    StandardRole.READER,
                    deepest.recipient(),
                    deepest.id(),
                    Optional.empty(),
                    false)));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("UPDATE authority_grants SET issuing_grant=id WHERE id=?", parent.id()));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("DELETE FROM authority_grant_audit WHERE grant_id=?", parent.id()));
  }

  @Test
  void revoking_an_issuing_role_cannot_remove_all_remaining_managers_through_descendants() {
    migrate();
    project("new");
    var parent = issue("alice", StandardRole.MANAGER, "new");
    var child =
        grants.issue(
            new Issue(
                UUID.randomUUID(),
                who("bob"),
                scope("new"),
                StandardRole.MANAGER,
                who("alice"),
                parent.id(),
                Optional.empty(),
                false));
    assertThrows(CallerFault.class, () -> grants.revoke(parent.id(), 1, who("bob"), child.id()));
    assertTrue(grants.find(parent.id()).orElseThrow().revokedAt().isEmpty());
    assertFalse(grants.lineage(child.id()).isEmpty());
  }

  @Test
  void role_identity_collision_refuses_migration_without_partial_schema_or_credential_changes() {
    user(BuiltInIdentities.ADMIN.value().toString(), false);
    assertThrows(org.flywaydb.core.api.FlywayException.class, this::migrate);
    assertNull(jdbc.queryForObject("SELECT to_regclass('authority_grants')", String.class));
    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM authority_roles", Integer.class));
    assertEquals(
        "155",
        Flyway.configure().dataSource(source).load().info().current().getVersion().toString());
  }

  @Test
  void known_grant_identity_reconciles_uncertain_issuance_without_replay() {
    migrate();
    var request =
        new Issue(
            UUID.randomUUID(),
            who("alice"),
            scope("one"),
            StandardRole.READER,
            who("admin"),
            control(),
            Optional.empty(),
            false);
    var issued = grants.issue(request);
    assertEquals(request.id(), issued.id());
    var restarted = new JdbcScopedGrantRepository(jdbc);
    assertEquals(issued, restarted.find(request.id()).orElseThrow());
    long before = count("audit");
    assertThrows(DataAccessException.class, () -> restarted.issue(request));
    assertEquals(before, count("audit"));
    assertEquals(issued, restarted.find(request.id()).orElseThrow());
  }

  @Test
  void interrupted_role_bootstrap_rolls_back_then_retry_keeps_fixed_role_ids() {
    jdbc.execute(
        "CREATE FUNCTION interrupt_role_bootstrap() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.name='READER' THEN RAISE EXCEPTION 'fixture interruption'; END IF; RETURN NEW; END $$");
    jdbc.execute(
        "CREATE TRIGGER interrupt_role_bootstrap BEFORE INSERT ON authority_roles FOR EACH ROW EXECUTE FUNCTION interrupt_role_bootstrap()");
    assertThrows(org.flywaydb.core.api.FlywayException.class, this::migrate);
    assertNull(jdbc.queryForObject("SELECT to_regclass('authority_grants')", String.class));
    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM authority_roles", Integer.class));
    jdbc.execute("DROP TRIGGER interrupt_role_bootstrap ON authority_roles");
    migrate();
    assertEquals("READER", identities.role(BuiltInIdentities.READER).name());
    identities.verifyBuiltIns();
  }

  private boolean removeAfter(CountDownLatch start, String actor, String target) throws Exception {
    start.await();
    try {
      members.remove("one", target, actor);
      return true;
    } catch (CallerFault | ArchiveRefusedException refused) {
      return false;
    }
  }

  @Test
  void application_limits_remain_live_on_new_grants_and_issuance_cannot_borrow_a_second_binding() {
    migrate();
    var manager = issue("alice", StandardRole.MANAGER, "one");
    var reader = issue("alice", StandardRole.READER, "one");
    var service = new AuthorizedRoleGrants(grants, authority, identities);
    when(applications.read("one"))
        .thenReturn(
            new ApplicationPolicy.Boundary(
                ApplicationPolicy.Kind.APPLICATION, Map.of("alice", ProjectRole.VIEWER)));
    var request =
        new Issue(
            UUID.randomUUID(),
            who("bob"),
            scope("one"),
            StandardRole.CONTRIBUTOR,
            who("alice"),
            manager.id(),
            Optional.empty(),
            false);
    assertThrows(CallerFault.class, () -> service.issue(request));
    assertTrue(authority.allows(who("alice"), identities.project("one"), AuthorityAction.READ));
    when(applications.read("one"))
        .thenReturn(
            new ApplicationPolicy.Boundary(
                ApplicationPolicy.Kind.APPLICATION, Map.of("alice", ProjectRole.MANAGER)));
    assertThrows(
        CallerFault.class,
        () ->
            service.issue(
                new Issue(
                    UUID.randomUUID(),
                    who("bob"),
                    scope("one"),
                    StandardRole.CONTRIBUTOR,
                    who("alice"),
                    reader.id(),
                    Optional.empty(),
                    false)));
    assertEquals(StandardRole.CONTRIBUTOR, service.issue(request).role());
    when(applications.read("one"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.INVALID, Map.of()));
    assertFalse(authority.allows(who("alice"), identities.project("one"), AuthorityAction.READ));
  }
}
