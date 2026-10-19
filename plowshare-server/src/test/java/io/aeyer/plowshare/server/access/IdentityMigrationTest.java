package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AuthorityIds.*;
import io.aeyer.plowshare.server.acting.JdbcActingRepository;
import io.aeyer.plowshare.server.auth.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.personal.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** PostgreSQL is required for Flyway rollback, fixed identities, FKs and lifecycle triggers. */
@Tag("full-db")
@Testcontainers
class IdentityMigrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withUrlParam("sslmode", "disable")
          .withUrlParam("gssEncMode", "disable");

  private DriverManagerDataSource source;
  private JdbcTemplate jdbc;

  @BeforeEach
  void legacySchema() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var schema = "identity_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(source).execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
    new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
    var properties = new Properties();
    properties.setProperty("currentSchema", schema + ",public");
    source.setConnectionProperties(properties);
    Flyway.configure()
        .dataSource(source)
        .defaultSchema(schema)
        .schemas(schema)
        .target("154")
        .load()
        .migrate();
    jdbc = new JdbcTemplate(source);
  }

  private void migrate() {
    Flyway.configure().dataSource(source).target("155").load().migrate();
  }

  private void user(String name) {
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password,server_admin) VALUES (?,'fixture',FALSE,FALSE)",
        name);
  }

  private long project(String name, String owner) {
    return jdbc.queryForObject(
        "INSERT INTO projects(name,personal_owner) VALUES (?,?) RETURNING id",
        Long.class,
        name,
        owner);
  }

  @Test
  void mapsExistingAndNewKeysWithoutReclassifyingNullOrAnOrdinaryGlobalProject() {
    user("alice");
    long existing = project("global", null);
    long personal = project(PersonalSpaces.name("alice"), "alice");
    var services = new JdbcServiceAccountRepository(jdbc);
    services.create("integration");
    UUID token = UUID.randomUUID();
    String principal = "@service/" + token;
    services.issue(
        token,
        "integration",
        "worker",
        principal,
        "0".repeat(64),
        OffsetDateTime.now().plusDays(1),
        List.of(
            new ServiceAccounts.Scope(
                "global", io.aeyer.plowshare.server.archive.ProjectRole.VIEWER)));
    project("personal:orphan", null);
    project("client:legacy", null);
    UUID resource = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,kind) VALUES (?,'legacy','untouched','source')",
        resource);
    migrate();
    var identities = new JdbcIdentityRepository(jdbc);
    var account = identities.account("alice");
    assertEquals(
        IdentityRepository.AccountKind.SERVICE_TOKEN, identities.account(principal).kind());
    assertNotEquals(identities.account("integration").id(), identities.account(principal).id());
    assertThrows(CallerFault.class, () -> identities.knowledge(identities.account(principal).id()));
    assertThrows(
        CallerFault.class, () -> identities.personal(identities.account("integration").id()));
    assertEquals(
        token,
        jdbc.queryForObject(
            "SELECT id FROM service_tokens WHERE principal_handle=?", UUID.class, principal));
    var mapped = assertInstanceOf(AuthorityScope.Project.class, identities.project("global"));
    assertEquals(existing, mapped.projectId());
    assertNotEquals(BuiltInIdentities.GLOBAL_KNOWLEDGE, mapped.id());
    var own = identities.personal(account.id());
    assertEquals(personal, own.projectId());
    assertEquals(account.id(), own.account());
    assertNotEquals(own.id(), identities.knowledge(account.id()).id());
    assertThrows(CallerFault.class, () -> identities.project("personal:orphan"));
    assertThrows(CallerFault.class, () -> identities.project("client:legacy"));
    assertThrows(CallerFault.class, () -> identities.legacyHome(Home.global()));
    assertThrows(CallerFault.class, () -> identities.project("unknown"));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM authority_scopes WHERE kind='RESTRICTED'", Integer.class));
    assertEquals(
        resource,
        jdbc.queryForObject(
            "SELECT id FROM information_resources WHERE id=? AND project_id IS NULL AND owner_handle IS NULL",
            UUID.class,
            resource));

    jdbc.update("UPDATE projects SET name='renamed' WHERE id=?", existing);
    assertEquals(mapped, identities.project("renamed"));
    user("bob");
    long added = project("new-project", null);
    assertEquals(
        added,
        assertInstanceOf(AuthorityScope.Project.class, identities.project("new-project"))
            .projectId());
    assertNotEquals(account.id(), identities.account("bob").id());
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO authority_accounts(id,legacy_handle) VALUES (?,?)",
                account.id().value(),
                "bob"));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO authority_scopes(kind,project_id) VALUES ('PROJECT',?)",
                Long.MAX_VALUE));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO authority_accounts(legacy_handle) VALUES ('missing-account')"));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO authority_scopes(kind,project_id,restriction_reason) VALUES ('RESTRICTED',?,'unresolved')",
                Long.MAX_VALUE));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE authority_scopes SET account_id=? WHERE id=?",
                BuiltInIdentities.SYSTEM.value(),
                mapped.id().value()));
  }

  @Test
  void interruptionDuringBuiltinBootstrapRollsBackAndRetriesWithTheSameReservedIds() {
    user("alice");
    long existing = project("preserved", null);
    jdbc.execute(
        """
        CREATE FUNCTION interrupt_builtin_fixture() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN RAISE EXCEPTION 'simulated migration interruption'; END $$
        """);
    jdbc.execute(
        """
        CREATE TRIGGER interrupt_builtin_fixture BEFORE INSERT ON admins FOR EACH ROW
        WHEN (NEW.handle='GLOBAL') EXECUTE FUNCTION interrupt_builtin_fixture()
        """);
    assertThrows(RuntimeException.class, this::migrate);
    assertNull(jdbc.queryForObject("SELECT to_regclass('authority_accounts')", String.class));
    assertEquals(
        "154",
        Flyway.configure().dataSource(source).load().info().current().getVersion().toString());
    assertEquals(
        existing,
        jdbc.queryForObject("SELECT id FROM projects WHERE name='preserved'", Long.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM admins WHERE handle IN ('SYSTEM','GLOBAL')", Integer.class));
    jdbc.execute("DROP TRIGGER interrupt_builtin_fixture ON admins");
    jdbc.execute("DROP FUNCTION interrupt_builtin_fixture()");
    migrate();
    var identities = new JdbcIdentityRepository(jdbc);
    identities.verifyBuiltIns();
    assertEquals(BuiltInIdentities.SYSTEM, identities.account("SYSTEM").id());
    assertEquals(BuiltInIdentities.GLOBAL, identities.account("GLOBAL").id());
  }

  @Test
  void existingProjectMetadataChangesKeepTheMappedUuidAndCurrentPersonalOwner() {
    user("alice");
    String name = PersonalSpaces.name("alice");
    long project = project(name, null);
    migrate();
    UUID scope =
        jdbc.queryForObject(
            "SELECT id FROM authority_scopes WHERE project_id=?", UUID.class, project);
    var identities = new JdbcIdentityRepository(jdbc);
    assertThrows(CallerFault.class, () -> identities.project(name));
    jdbc.update("UPDATE projects SET personal_owner='alice' WHERE id=?", project);
    var personal = identities.personal(identities.account("alice").id());
    assertEquals(scope, personal.id().value());
    assertEquals(project, personal.projectId());
    assertEquals(personal, identities.project(name));
  }

  @Test
  void builtinBootstrapAndRestartAreStableAndMissingRecordsRefuse() {
    migrate();
    var identities = new JdbcIdentityRepository(jdbc);
    var bootstrap = new IdentityBootstrap(identities);
    bootstrap.afterSingletonsInstantiated();
    var before = jdbc.queryForList("SELECT id,kind,account_id FROM authority_scopes ORDER BY id");
    jdbc.execute("SELECT authority_bootstrap_builtins()");
    Flyway.configure().dataSource(source).target("155").load().migrate();
    new IdentityBootstrap(new JdbcIdentityRepository(new JdbcTemplate(source)))
        .afterSingletonsInstantiated();
    assertEquals(
        before, jdbc.queryForList("SELECT id,kind,account_id FROM authority_scopes ORDER BY id"));
    assertEquals(BuiltInIdentities.SYSTEM, identities.account("SYSTEM").id());
    assertEquals(BuiltInIdentities.GLOBAL, identities.account("GLOBAL").id());
    assertEquals(
        BuiltInIdentities.GLOBAL_KNOWLEDGE, identities.knowledge(BuiltInIdentities.GLOBAL).id());
    assertInstanceOf(
        AuthorityScope.Administration.class, identities.scope(BuiltInIdentities.ADMINISTRATION));
    assertFalse(identities.role(BuiltInIdentities.SYSTEM_ROLE).assignable());
    assertEquals(
        Optional.of(BuiltInIdentities.SYSTEM),
        identities.role(BuiltInIdentities.SYSTEM_ROLE).intrinsicAccount());
    assertThrows(CallerFault.class, () -> identities.personal(BuiltInIdentities.SYSTEM));
    assertThrows(
        CallerFault.class, () -> identities.project(BuiltInIdentities.GLOBAL.value().toString()));
    // Simulate out-of-band loss. Normal deletes are guarded; startup must not manufacture IDs.
    jdbc.execute("TRUNCATE authority_roles");
    assertThrows(IllegalStateException.class, bootstrap::afterSingletonsInstantiated);
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM authority_roles", Integer.class));
  }

  @ParameterizedTest
  @ValueSource(strings = {"SYSTEM", "global", "00000000-0000-0000-0000-000000000004"})
  void collisionRollsBackMigrationAndRetryPreservesExistingKeys(String collision) {
    user(collision);
    long project = project("preserved", null);
    assertThrows(RuntimeException.class, this::migrate);
    assertNull(jdbc.queryForObject("SELECT to_regclass('authority_accounts')", String.class));
    assertEquals(
        project, jdbc.queryForObject("SELECT id FROM projects WHERE name='preserved'", Long.class));
    // This fixture has no referring records. Operator resolution is explicit, never bootstrap
    // adoption.
    jdbc.update("UPDATE admins SET handle='resolved-owner' WHERE handle=?", collision);
    migrate();
    var identities = new JdbcIdentityRepository(jdbc);
    assertFalse(identities.account("resolved-owner").locked());
    assertEquals(
        project,
        assertInstanceOf(AuthorityScope.Project.class, identities.project("preserved"))
            .projectId());
  }

  @Test
  void lockedLifecycleAndForeignKeysRefuseCredentialAndRoleEscape() {
    migrate();
    user("operator");
    var accounts = new AdminStore(jdbc);
    var personal = new JdbcPersonalSpaceRepository(jdbc);
    var acting =
        new JdbcActingRepository(
            jdbc,
            org.mockito.Mockito.mock(
                io.aeyer.plowshare.server.applications.ApplicationIdentityRepository.class),
            () -> true);
    var sessions =
        new JdbcDurableSessions(
            jdbc,
            new DataSourceTransactionManager(source),
            Clock.systemUTC(),
            Duration.ofMinutes(15),
            Duration.ofDays(1));
    for (String handle : List.of("SYSTEM", "GLOBAL")) {
      assertFalse(accounts.byHandle(handle).orElseThrow().interactive());
      assertFalse(accounts.isEnabled(handle));
      assertFalse(accounts.isServerAdmin(handle));
      assertFalse(acting.subjectActive(handle));
      assertTrue(personal.serviceAccount(handle));
      assertThrows(IllegalArgumentException.class, () -> personal.reserve(handle));
      assertThrows(CallerFault.class, () -> sessions.issue(handle, false));
      assertThrows(
          DataAccessException.class,
          () -> jdbc.update("UPDATE admins SET enabled=TRUE WHERE handle=?", handle));
      assertThrows(
          DataAccessException.class,
          () -> jdbc.update("UPDATE admins SET password_hash='credential' WHERE handle=?", handle));
      assertThrows(
          DataAccessException.class,
          () -> jdbc.update("UPDATE admins SET handle='renamed-builtin' WHERE handle=?", handle));
      assertThrows(
          DataAccessException.class,
          () -> jdbc.update("DELETE FROM admins WHERE handle=?", handle));
      assertThrows(
          DataAccessException.class,
          () ->
              jdbc.update(
                  "INSERT INTO auth_session_chains(id,handle,restricted,expires_at) VALUES (?,?,FALSE,now()+interval '1 day')",
                  UUID.randomUUID(),
                  handle));
      assertThrows(
          DataAccessException.class,
          () ->
              jdbc.update(
                  "INSERT INTO service_tokens(id,owner_handle,name,principal_handle,digest,expires_at) VALUES (?,?,'token','operator','fixture',now()+interval '1 day')",
                  UUID.randomUUID(),
                  handle));
      assertThrows(DataAccessException.class, () -> project(PersonalSpaces.name(handle), handle));
    }
    assertFalse(personal.accounts().contains("SYSTEM"));
    assertFalse(personal.accounts().contains("GLOBAL"));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("UPDATE authority_roles SET assignable=TRUE WHERE name='SYSTEM'"));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE authority_roles SET intrinsic_account=? WHERE name='SYSTEM'",
                new JdbcIdentityRepository(jdbc).account("operator").id().value()));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("DELETE FROM authority_scopes WHERE builtin IS NOT NULL"));
    assertThrows(DataAccessException.class, () -> user("system"));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO project_members(project_id,handle,role) VALUES (?,'operator','SYSTEM')",
                project("normal", null)));
  }

  @Test
  void builtinAccountsDoNotConsumeFirstHumanSetupOrSeedSelection() {
    migrate();
    var accounts = new AdminStore(jdbc);
    assertTrue(accounts.handleOfAnyOtherAdmin("chosen").isEmpty());
    assertTrue(accounts.bootstrap("temporary"));
    assertTrue(accounts.finishSetup("admin", "temporary", "chosen", "permanent"));
    assertTrue(accounts.isServerAdmin("chosen"));
    assertFalse(accounts.bootstrap("unused"));
    new JdbcIdentityRepository(jdbc).verifyBuiltIns();
  }
}
