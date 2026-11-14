package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.auth.JdbcServiceAccountRepository;
import io.aeyer.plowshare.server.auth.ServiceAccounts;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL proves the V260 trigger branch and membership issuance: a person exercising a live
 * grant issues a SYSTEM-rooted grant that records the exercised grant, while every authorization
 * check still runs against the exercised grant and delegated (token) issuance keeps its lineage.
 */
@Tag("full-db")
@Testcontainers
class RoleExercisedIssuanceDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withUrlParam("sslmode", "disable")
          .withUrlParam("gssEncMode", "disable");

  private JdbcTemplate jdbc;

  /** Each test migrates its own schema so lineage fixtures never leak between cases. */
  @BeforeEach
  void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var schema = "exercised_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(source).execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
    new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
    var properties = new Properties();
    properties.setProperty("currentSchema", schema + ",public");
    source.setConnectionProperties(properties);
    Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
    jdbc = new JdbcTemplate(source);
    user("admin", true);
    user("mia", false);
    user("sam", false);
    user("tom", false);
    jdbc.update("INSERT INTO projects(name) VALUES ('one'),('two')");
  }

  private void user(String handle, boolean admin) {
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password,server_admin) VALUES (?,'fixture',FALSE,?)",
        handle,
        admin);
  }

  private UUID account(String handle) {
    return jdbc.queryForObject(
        "SELECT id FROM authority_accounts WHERE legacy_handle=?", UUID.class, handle);
  }

  private UUID scope(String project) {
    return jdbc.queryForObject(
        "SELECT s.id FROM authority_scopes s JOIN projects p ON p.id=s.project_id WHERE p.name=?",
        UUID.class,
        project);
  }

  private UUID root() {
    return jdbc.queryForObject(
        "SELECT id FROM authority_grants WHERE provenance='ROOT_POLICY'", UUID.class);
  }

  /** The admin's ORDINARY_PROJECT_CONTROL grant, used to issue fixture grants directly. */
  private UUID control() {
    return jdbc.queryForObject(
        "SELECT id FROM authority_grants WHERE provenance='ADMIN_CONTROL' AND revoked_at IS NULL"
            + " AND coverage='ORDINARY_PROJECT_CONTROL' AND subject_account=?",
        UUID.class,
        account("admin"));
  }

  private UUID issueMembership(String project, String target, String actor, String role) {
    return jdbc.queryForObject(
        "SELECT authority_issue_membership(?,?,?,?)", UUID.class, project, target, actor, role);
  }

  private UUID membership(String project, String handle) {
    return jdbc.queryForObject(
        "SELECT id FROM authority_grants WHERE provenance='MEMBERSHIP' AND revoked_at IS NULL"
            + " AND scope_id=? AND subject_account=?",
        UUID.class,
        scope(project),
        account(handle));
  }

  private UUID column(String column, UUID grant) {
    return switch (column) {
      case "issuing_grant" ->
          jdbc.queryForObject(
              "SELECT issuing_grant FROM authority_grants WHERE id=?", UUID.class, grant);
      case "exercised_grant" ->
          jdbc.queryForObject(
              "SELECT exercised_grant FROM authority_grants WHERE id=?", UUID.class, grant);
      default -> throw new IllegalArgumentException(column);
    };
  }

  private boolean live(UUID grant) {
    return jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM authority_live_grants() WHERE id=?)", Boolean.class, grant);
  }

  /** Revokes a grant under the admin's control grant, as an administrator removal would. */
  private void revoke(UUID grant) {
    jdbc.update(
        "UPDATE authority_grants SET revoked_at=now(),version=version+1,revoked_by=?,revoking_grant=?"
            + " WHERE id=?",
        account("admin"),
        control(),
        grant);
  }

  /** Inserts a SYSTEM-rooted grant directly so the trigger, not the function, is under test. */
  private void insertExercised(
      String subject,
      String project,
      String role,
      String issuer,
      UUID exercised,
      String issuerAgent,
      UUID token) {
    jdbc.update(
        "INSERT INTO authority_grants(subject_account,scope_id,role_id,requested_role,issuer_account,"
            + "issuer_agent,issuing_grant,issuing_token,exercised_grant,provenance)"
            + " SELECT ?,?,r.id,r.name,?,?,?,?,?,'ROLE' FROM authority_roles r WHERE r.name=?",
        account(subject),
        scope(project),
        account(issuer),
        issuerAgent,
        root(),
        token,
        exercised,
        role);
  }

  private void assertRefused(String message, Runnable insert) {
    var failure = assertThrows(DataAccessException.class, insert::run);
    assertTrue(
        failure.getMessage().contains(message),
        () -> "expected '" + message + "' but was: " + failure.getMessage());
  }

  @Test
  void member_survives_issuing_manager_removal() {
    UUID mia = issueMembership("one", "mia", "admin", "MANAGER");
    UUID sam = issueMembership("one", "sam", "mia", "CONTRIBUTOR");
    revoke(mia);
    assertFalse(live(mia));
    assertTrue(live(sam));
    assertEquals(root(), column("issuing_grant", sam));
    assertEquals(mia, column("exercised_grant", sam));
  }

  @Test
  void issued_membership_has_no_issuer_expiry_cap() {
    UUID mia = temporaryManager("mia");
    UUID sam = issueMembership("one", "sam", "mia", "CONTRIBUTOR");
    assertEquals(mia, column("exercised_grant", sam));
    assertNull(
        jdbc.queryForObject(
            "SELECT expires_at FROM authority_grants WHERE id=?", OffsetDateTime.class, sam));
    assertTrue(live(sam));
  }

  @Test
  void exercised_grant_must_be_live_and_held_by_issuer() {
    UUID mia = issueMembership("one", "mia", "admin", "MANAGER");
    UUID tom = issueMembership("one", "tom", "admin", "MANAGER");
    assertRefused(
        "Issuing grant belongs to a different principal",
        () -> insertExercised("sam", "one", "READER", "mia", tom, null, null));
    revoke(mia);
    assertRefused(
        "Issuing authority is not live",
        () -> insertExercised("sam", "one", "READER", "mia", mia, null, null));
  }

  @Test
  void exercised_issuance_keeps_role_ceilings() {
    UUID mia = issueMembership("one", "mia", "admin", "MANAGER");
    UUID sam = issueMembership("one", "sam", "mia", "CONTRIBUTOR");
    assertRefused(
        "Role exceeds issuing authority",
        () -> insertExercised("tom", "one", "ADMIN", "mia", mia, null, null));
    assertRefused(
        "Role exceeds issuing authority",
        () -> insertExercised("tom", "one", "READER", "sam", sam, null, null));
    assertRefused(
        "Grant exceeds issuing scope",
        () -> insertExercised("tom", "two", "READER", "mia", mia, null, null));
    insertExercised("tom", "one", "READER", "mia", mia, null, null);
    assertTrue(live(liveGrantOf("tom")));
  }

  private UUID liveGrantOf(String handle) {
    return jdbc.queryForObject(
        "SELECT id FROM authority_grants WHERE subject_account=? AND revoked_at IS NULL",
        UUID.class,
        account(handle));
  }

  @Test
  void exercised_grant_forbids_agent_and_token_issuers() {
    UUID mia = issueMembership("one", "mia", "admin", "MANAGER");
    assertRefused(
        "authority_grants_exercised_by_person",
        () -> insertExercised("sam", "one", "READER", "mia", mia, "helper", null));
    var services = new JdbcServiceAccountRepository(jdbc);
    services.create("service");
    UUID token = UUID.randomUUID();
    services.issue(
        token,
        "service",
        "manager",
        "@service/" + token,
        "1".repeat(64),
        OffsetDateTime.now().plusDays(1),
        List.of(new ServiceAccounts.Scope("one", ProjectRole.MANAGER)));
    assertRefused(
        "authority_grants_exercised_by_person",
        () -> insertExercised("sam", "one", "READER", "mia", mia, null, token));
  }

  @Test
  void exercised_issuance_requires_system_root_and_person_issuer() {
    UUID mia = issueMembership("one", "mia", "admin", "MANAGER");
    assertRefused(
        "Exercised issuance must be rooted at the system policy",
        () ->
            jdbc.update(
                "INSERT INTO authority_grants(subject_account,scope_id,role_id,requested_role,issuer_account,"
                    + "issuing_grant,exercised_grant,provenance) VALUES (?,?,?,'READER',?,?,?,'ROLE')",
                account("sam"),
                scope("one"),
                BuiltInIdentities.READER.value(),
                account("mia"),
                mia,
                mia));
    new JdbcServiceAccountRepository(jdbc).create("service");
    UUID service = issueMembership("one", "service", "admin", "MANAGER");
    assertRefused(
        "Exercised issuance requires a person issuer",
        () -> insertExercised("sam", "one", "READER", "service", service, null, null));
  }

  @Test
  void exercised_self_issuance_is_refused() {
    UUID mia = issueMembership("one", "mia", "admin", "MANAGER");
    assertRefused(
        "Cannot exercise a grant for yourself",
        () -> insertExercised("mia", "one", "READER", "mia", mia, null, null));
  }

  @Test
  void manager_cannot_reissue_own_membership() {
    UUID temporary = temporaryManager("mia");
    UUID own = issueMembership("one", "mia", "mia", "MANAGER");
    // Self-assignment stays bound to the authorizing grant: same lineage, same expiry cap.
    assertEquals(temporary, column("issuing_grant", own));
    assertNull(column("exercised_grant", own));
    assertEquals(expiry(temporary), expiry(own));
    revoke(temporary);
    assertFalse(live(own));
  }

  @Test
  void admin_self_assignment_stays_bound_to_admin_control() {
    UUID control = control();
    UUID own = issueMembership("one", "admin", "admin", "MANAGER");
    assertEquals(control, column("issuing_grant", own));
    assertNull(column("exercised_grant", own));
    assertNull(expiry(own));
    assertTrue(live(own));
    jdbc.update("UPDATE admins SET server_admin=FALSE WHERE handle='admin'");
    assertFalse(live(own));
  }

  private OffsetDateTime expiry(UUID grant) {
    return jdbc.queryForObject(
        "SELECT expires_at FROM authority_grants WHERE id=?", OffsetDateTime.class, grant);
  }

  /** A ROLE MANAGER grant on project one that expires in a day, issued under admin control. */
  private UUID temporaryManager(String handle) {
    UUID grant = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO authority_grants(id,subject_account,scope_id,role_id,requested_role,issuer_account,"
            + "issuing_grant,expires_at,provenance) VALUES (?,?,?,?,'MANAGER',?,?,now()+interval '1 day','ROLE')",
        grant,
        account(handle),
        scope("one"),
        BuiltInIdentities.MANAGER.value(),
        account("admin"),
        control());
    return grant;
  }

  @Test
  void audit_records_exercised_grant_as_authority() {
    UUID mia = issueMembership("one", "mia", "admin", "MANAGER");
    UUID sam = issueMembership("one", "sam", "mia", "CONTRIBUTOR");
    var audit =
        jdbc.queryForMap(
            "SELECT actor_account,authority FROM authority_grant_audit WHERE grant_id=? AND event='ISSUE'",
            sam);
    assertEquals(account("mia"), audit.get("actor_account"));
    assertEquals(mia, audit.get("authority"));
  }

  @Test
  void token_issued_membership_is_unchanged() {
    var services = new JdbcServiceAccountRepository(jdbc);
    services.create("service");
    UUID owner = issueMembership("one", "service", "admin", "MANAGER");
    UUID token = UUID.randomUUID();
    String principal = "@service/" + token;
    var expiry = OffsetDateTime.now().plusDays(1);
    services.issue(
        token,
        "service",
        "manager",
        principal,
        "1".repeat(64),
        expiry,
        List.of(new ServiceAccounts.Scope("one", ProjectRole.MANAGER)));
    UUID sam = issueMembership("one", "sam", principal, "CONTRIBUTOR");
    assertEquals(owner, column("issuing_grant", sam));
    assertNull(column("exercised_grant", sam));
    var expires =
        jdbc.queryForObject(
            "SELECT expires_at FROM authority_grants WHERE id=?", OffsetDateTime.class, sam);
    assertTrue(expires != null && !expires.isAfter(expiry));
    assertEquals(
        token,
        jdbc.queryForObject(
            "SELECT issuing_token FROM authority_grants WHERE id=?", UUID.class, sam));
  }
}
