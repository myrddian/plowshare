package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.testing.AuthorityFixtures;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL proves {@link JdbcProjectCreatorGrants} against the V262 trigger, the
 * one-unrevoked-creator-grant index and the grant audit trigger: what the service writes is what
 * the database admits, and the grant outlives its issuer.
 */
@Tag("full-db")
@Testcontainers
class ProjectCreatorGrantsDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final String NOT_ADMIN = "Only a server administrator may manage server resources";
  private static final UUID CREATION = BuiltInIdentities.PROJECT_CREATION.value();

  private static DriverManagerDataSource source;
  private JdbcTemplate jdbc;
  private JdbcProjectCreatorGrants grants;

  @BeforeAll
  static void migrate() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
  }

  @BeforeEach
  void setup() {
    jdbc = new JdbcTemplate(source);
    AuthorityFixtures.truncate(jdbc, "TRUNCATE admins,projects CASCADE");
    grants = new JdbcProjectCreatorGrants(jdbc);
    user("admin", true);
    user("other", true);
    user("sam", false);
    user("mia", false);
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,account_kind,must_change_password) VALUES ('robot','fixture','SERVICE',FALSE)");
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

  private UUID root() {
    return jdbc.queryForObject(
        "SELECT id FROM authority_grants WHERE provenance='ROOT_POLICY'", UUID.class);
  }

  /** The admin's live ADMIN_CONTROL grant at the ADMINISTRATION scope. */
  private UUID control(String handle) {
    return jdbc.queryForObject(
        "SELECT id FROM authority_grants WHERE provenance='ADMIN_CONTROL' AND revoked_at IS NULL"
            + " AND scope_id=? AND subject_account=?",
        UUID.class,
        BuiltInIdentities.ADMINISTRATION.value(),
        account(handle));
  }

  private boolean mayCreate(String handle) {
    return jdbc.queryForObject("SELECT authority_may_create_projects(?)", Boolean.class, handle);
  }

  /** Every creator grant row of the subject, oldest first. */
  private List<Map<String, Object>> creatorRows(String handle) {
    return jdbc.queryForList(
        "SELECT * FROM authority_grants WHERE scope_id=? AND subject_account=? ORDER BY created_at,id",
        CREATION,
        account(handle));
  }

  private long revokedGrants() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM authority_grants WHERE revoked_at IS NOT NULL", Long.class);
  }

  @Test
  void admin_grants_and_audit_names_admin() {
    assertFalse(grants.holds("sam"));
    grants.grant("admin", "sam");
    assertTrue(grants.holds("sam"));
    assertTrue(mayCreate("sam"));
    var row = creatorRows("sam").getFirst();
    assertEquals("ROLE", row.get("provenance"));
    assertEquals("SELF", row.get("coverage"));
    assertEquals("PROJECT_CREATOR", row.get("requested_role"));
    assertEquals(account("admin"), row.get("issuer_account"));
    assertEquals(root(), row.get("issuing_grant"));
    assertEquals(control("admin"), row.get("exercised_grant"));
    var audit =
        jdbc.queryForMap(
            "SELECT event,actor_account,authority FROM authority_grant_audit WHERE grant_id=?",
            row.get("id"));
    assertEquals("ISSUE", audit.get("event"));
    assertEquals(account("admin"), audit.get("actor_account"));
    assertEquals(control("admin"), audit.get("authority"));
  }

  @Test
  void creator_grant_survives_issuer_demotion_and_disable() {
    grants.grant("admin", "sam");
    jdbc.update("UPDATE admins SET server_admin=FALSE WHERE handle='admin'");
    assertTrue(mayCreate("sam"));
    assertTrue(grants.holds("sam"));
    jdbc.update("UPDATE admins SET enabled=FALSE WHERE handle='admin'");
    assertTrue(mayCreate("sam"));
    assertTrue(grants.holds("sam"));
    // The demoted, disabled issuer can no longer manage the role.
    var refused = assertThrows(CallerFault.class, () -> grants.revoke("admin", "sam"));
    assertEquals(NOT_ADMIN, refused.getMessage());
  }

  /** Unlike a grant to another account, an administrator's self-grant ends with their control. */
  @Test
  void admin_self_grant_is_bound_to_admin_control() {
    UUID authority = control("admin");
    grants.grant("admin", "admin");
    assertTrue(grants.holds("admin"));
    var row = creatorRows("admin").getFirst();
    assertEquals(account("admin"), row.get("subject_account"));
    assertEquals(account("admin"), row.get("issuer_account"));
    assertEquals(authority, row.get("issuing_grant"));
    assertEquals(null, row.get("exercised_grant"));
    grants.grant("admin", "admin");
    assertEquals(1, creatorRows("admin").size());

    jdbc.update("UPDATE admins SET server_admin=FALSE WHERE handle='admin'");
    assertFalse(mayCreate("admin"));
    assertFalse(grants.holds("admin"));
    assertEquals(Set.of(), grants.holdersAmong(List.of("admin")));

    // Promoted again, the administrator holds a new ADMIN_CONTROL grant; the dead self-grant
    // still occupies the slot, so a new self-grant revokes it first and binds to the new control.
    jdbc.update("UPDATE admins SET server_admin=TRUE WHERE handle='admin'");
    assertFalse(grants.holds("admin"));
    grants.grant("admin", "admin");
    assertTrue(grants.holds("admin"));
    var rows = creatorRows("admin");
    assertEquals(2, rows.size());
    assertEquals(account("admin"), rows.getFirst().get("revoked_by"));
    assertEquals(control("admin"), rows.getLast().get("issuing_grant"));
  }

  /** A durable grant from another administrator replaces, not hides behind, a bound self-grant. */
  @Test
  void other_admin_grant_replaces_bound_self_grant() {
    assertTrue(grants.grant("admin", "admin"));
    UUID bound = (UUID) creatorRows("admin").getFirst().get("id");
    // The holder granting again, bound or not, changes nothing.
    assertFalse(grants.grant("admin", "admin"));
    assertTrue(grants.grant("other", "admin"));
    var rows = creatorRows("admin");
    assertEquals(2, rows.size());
    var replaced = rows.stream().filter(row -> row.get("id").equals(bound)).findFirst().get();
    assertEquals(account("other"), replaced.get("revoked_by"));
    assertEquals(control("other"), replaced.get("revoking_grant"));
    var durable = rows.stream().filter(row -> !row.get("id").equals(bound)).findFirst().get();
    assertEquals(null, durable.get("revoked_at"));
    assertEquals(root(), durable.get("issuing_grant"));
    assertEquals(control("other"), durable.get("exercised_grant"));
    // A durable grant is kept, whoever grants again, including the holder themselves.
    assertFalse(grants.grant("other", "admin"));
    assertFalse(grants.grant("admin", "admin"));
    assertEquals(2, creatorRows("admin").size());

    jdbc.update("UPDATE admins SET server_admin=FALSE WHERE handle='admin'");
    assertTrue(mayCreate("admin"));
  }

  @Test
  void any_admin_may_revoke() {
    grants.grant("admin", "sam");
    long before = revokedGrants();
    UUID authority = control("other");
    grants.revoke("other", "sam");
    assertFalse(grants.holds("sam"));
    assertFalse(mayCreate("sam"));
    // Only the creator grant is revoked.
    assertEquals(before + 1, revokedGrants());
    var row = creatorRows("sam").getFirst();
    assertEquals(2L, row.get("version"));
    assertEquals(account("other"), row.get("revoked_by"));
    assertEquals(authority, row.get("revoking_grant"));
    var audit =
        jdbc.queryForMap(
            "SELECT actor_account,authority FROM authority_grant_audit WHERE grant_id=? AND event='REVOKE'",
            row.get("id"));
    assertEquals(account("other"), audit.get("actor_account"));
    assertEquals(authority, audit.get("authority"));
    // Revocation leaves the revoking admin's own control intact.
    assertEquals(authority, control("other"));
  }

  @Test
  void grant_is_idempotent() {
    assertTrue(grants.grant("admin", "sam"));
    assertFalse(grants.grant("admin", "sam"));
    assertFalse(grants.grant("other", "sam"));
    assertEquals(1, creatorRows("sam").size());
    assertTrue(grants.holds("sam"));
  }

  @Test
  void revoke_is_idempotent() {
    long before = revokedGrants();
    assertFalse(grants.revoke("admin", "sam"));
    assertEquals(before, revokedGrants());
    assertTrue(grants.grant("admin", "sam"));
    assertTrue(grants.revoke("admin", "sam"));
    assertFalse(grants.revoke("other", "sam"));
    assertEquals(before + 1, revokedGrants());
    assertFalse(grants.holds("sam"));
    // A revoked holder may be granted again.
    grants.grant("other", "sam");
    assertTrue(grants.holds("sam"));
    assertEquals(2, creatorRows("sam").size());
  }

  @Test
  void grant_after_expiry_reissues() throws InterruptedException {
    jdbc.update(
        "INSERT INTO authority_grants(subject_account,scope_id,role_id,requested_role,issuer_account,"
            + "issuing_grant,exercised_grant,provenance,expires_at)"
            + " VALUES (?,?,?,'PROJECT_CREATOR',?,?,?,'ROLE',?)",
        account("sam"),
        CREATION,
        BuiltInIdentities.PROJECT_CREATOR.value(),
        account("admin"),
        root(),
        control("admin"),
        Timestamp.from(Instant.now().plusMillis(300)));
    assertTrue(grants.holds("sam"));
    Instant expires =
        jdbc.queryForObject(
                "SELECT expires_at FROM authority_grants WHERE scope_id=? AND subject_account=?",
                Timestamp.class,
                CREATION,
                account("sam"))
            .toInstant();
    while (Instant.now().isBefore(expires.plusMillis(50))) Thread.sleep(10);
    assertFalse(grants.holds("sam"));

    grants.grant("other", "sam");
    assertTrue(grants.holds("sam"));
    var rows = creatorRows("sam");
    assertEquals(2, rows.size());
    var stale = rows.getFirst();
    assertTrue(stale.get("revoked_at") != null);
    assertEquals(account("other"), stale.get("revoked_by"));
    assertEquals(control("other"), stale.get("revoking_grant"));
    assertEquals(null, rows.getLast().get("revoked_at"));
  }

  @Test
  void non_admin_actors_are_refused() {
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password,server_admin,enabled) VALUES ('off','fixture',FALSE,TRUE,FALSE)");
    grants.grant("admin", "mia");
    for (String actor : List.of("sam", "off", "robot", "missing")) {
      var granting = assertThrows(CallerFault.class, () -> grants.grant(actor, "sam"));
      assertEquals(NOT_ADMIN, granting.getMessage());
      var revoking = assertThrows(CallerFault.class, () -> grants.revoke(actor, "mia"));
      assertEquals(NOT_ADMIN, revoking.getMessage());
    }
    assertFalse(grants.holds("sam"));
    assertTrue(grants.holds("mia"));
  }

  @Test
  void recipient_must_be_an_active_user() {
    jdbc.update("UPDATE admins SET enabled=FALSE WHERE handle='mia'");
    for (String recipient : List.of("robot", "mia", "missing")) {
      var refused = assertThrows(CallerFault.class, () -> grants.grant("admin", recipient));
      assertEquals(
          "The project creator role can only be granted to an active user account",
          refused.getMessage());
    }
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT count(*) FROM authority_grants WHERE scope_id=?", Long.class, CREATION));
  }

  @Test
  void holders_among_answers_for_the_whole_listing() {
    grants.grant("admin", "sam");
    grants.grant("admin", "other");
    grants.grant("other", "mia");
    grants.revoke("admin", "mia");
    assertEquals(
        Set.of("sam", "other"),
        grants.holdersAmong(List.of("admin", "other", "sam", "mia", "robot", "missing")));
    assertEquals(Set.of("sam"), grants.holdersAmong(List.of("sam")));
    assertEquals(Set.of(), grants.holdersAmong(List.of()));
  }
}
