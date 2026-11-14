package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL proves V261 against data written under V259's lineage rules: live person-issued grants
 * are reissued from the SYSTEM root through the V260 trigger, lineage-dead ones are revoked rather
 * than revived, and delegated, self-issued and system-minted grants are left byte-identical. Each
 * test migrates its own schema to V259, seeds it, then migrates to V261.
 */
@Tag("full-db")
@Testcontainers
class RerootPersonIssuedGrantsMigrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withUrlParam("sslmode", "disable")
          .withUrlParam("gssEncMode", "disable");

  private DriverManagerDataSource source;
  private String schema;
  private JdbcTemplate jdbc;

  @BeforeEach
  void migrateToV259() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    schema = "reroot_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(source).execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
    new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
    var properties = new Properties();
    properties.setProperty("currentSchema", schema + ",public");
    source.setConnectionProperties(properties);
    migrateTo("259");
    jdbc = new JdbcTemplate(source);
    user("admin", true);
    user("mia", false);
    user("sam", false);
    user("tom", false);
    jdbc.update("INSERT INTO projects(name) VALUES ('one'),('two')");
  }

  private void migrateTo(String target) {
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .target(target)
        .load()
        .migrate();
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

  private UUID systemAccount() {
    return jdbc.queryForObject(
        "SELECT subject_account FROM authority_grants WHERE provenance='ROOT_POLICY'", UUID.class);
  }

  /** The admin's ORDINARY_PROJECT_CONTROL grant (ROOT_POLICY-parented). */
  private UUID control() {
    return jdbc.queryForObject(
        "SELECT id FROM authority_grants WHERE provenance='ADMIN_CONTROL' AND revoked_at IS NULL"
            + " AND coverage='ORDINARY_PROJECT_CONTROL' AND subject_account=?",
        UUID.class,
        account("admin"));
  }

  /** V259's authority_issue_membership: every issue hangs off the authorizing grant. */
  private UUID issueMembership(String project, String target, String actor, String role) {
    return jdbc.queryForObject(
        "SELECT authority_issue_membership(?,?,?,?)", UUID.class, project, target, actor, role);
  }

  /** A SYSTEM-rooted membership written through the project_members bridge. */
  private void member(String project, String handle, String role) {
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,?,? FROM projects WHERE name=?",
        handle,
        role,
        project);
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

  /** Revokes a grant under the admin's control grant, as an administrator removal would. */
  private void revoke(UUID grant) {
    jdbc.update(
        "UPDATE authority_grants SET revoked_at=now(),version=version+1,revoked_by=?,revoking_grant=?"
            + " WHERE id=?",
        account("admin"),
        control(),
        grant);
  }

  private boolean live(UUID grant) {
    return jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM authority_live_grants() WHERE id=?)", Boolean.class, grant);
  }

  /**
   * The whole row as text, so "untouched" means every column, including version and revocation.
   * V260 adds exercised_grant after the seed was written, so the comparison drops that one key.
   */
  private String row(UUID grant) {
    return jdbc.queryForObject(
        "SELECT (to_jsonb(g)-'exercised_grant')::text FROM authority_grants g WHERE id=?",
        String.class,
        grant);
  }

  private UUID uuidColumn(String column, UUID grant) {
    return switch (column) {
      case "issuing_grant" ->
          jdbc.queryForObject(
              "SELECT issuing_grant FROM authority_grants WHERE id=?", UUID.class, grant);
      case "exercised_grant" ->
          jdbc.queryForObject(
              "SELECT exercised_grant FROM authority_grants WHERE id=?", UUID.class, grant);
      case "issuing_token" ->
          jdbc.queryForObject(
              "SELECT issuing_token FROM authority_grants WHERE id=?", UUID.class, grant);
      case "revoked_by" ->
          jdbc.queryForObject(
              "SELECT revoked_by FROM authority_grants WHERE id=?", UUID.class, grant);
      case "revoking_grant" ->
          jdbc.queryForObject(
              "SELECT revoking_grant FROM authority_grants WHERE id=?", UUID.class, grant);
      default -> throw new IllegalArgumentException(column);
    };
  }

  private OffsetDateTime timestamp(String column, UUID grant) {
    return switch (column) {
      case "expires_at" ->
          jdbc.queryForObject(
              "SELECT expires_at FROM authority_grants WHERE id=?", OffsetDateTime.class, grant);
      case "revoked_at" ->
          jdbc.queryForObject(
              "SELECT revoked_at FROM authority_grants WHERE id=?", OffsetDateTime.class, grant);
      default -> throw new IllegalArgumentException(column);
    };
  }

  /** The single unrevoked grant of a provenance held by a person (not an agent) on a project. */
  private UUID current(String provenance, String project, String handle) {
    return jdbc.queryForObject(
        "SELECT id FROM authority_grants WHERE provenance=? AND revoked_at IS NULL AND scope_id=?"
            + " AND subject_account=? AND subject_agent IS NULL",
        UUID.class,
        provenance,
        scope(project),
        account(handle));
  }

  private int liveGrantsOn(String project, String handle) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM authority_grants g JOIN authority_live_grants() l ON l.id=g.id"
            + " WHERE g.scope_id=? AND g.subject_account=?",
        Integer.class,
        scope(project),
        account(handle));
  }

  private void assertRevokedBySystem(UUID grant) {
    assertNotNull(timestamp("revoked_at", grant));
    assertEquals(systemAccount(), uuidColumn("revoked_by", grant));
    assertEquals(root(), uuidColumn("revoking_grant", grant));
  }

  @Test
  void live_person_issued_grants_are_reissued_from_root() {
    UUID mia = temporaryManager("mia");
    UUID sam = issueMembership("one", "sam", "mia", "CONTRIBUTOR");
    var expiry = timestamp("expires_at", sam);
    assertNotNull(expiry);
    assertEquals(mia, uuidColumn("issuing_grant", sam));

    migrateTo("261");

    // sam's grant was exercised from mia's original grant, which was still live when sam was
    // reissued because children are re-rooted before their parents.
    UUID reissued = current("MEMBERSHIP", "one", "sam");
    assertEquals(1, liveGrantsOn("one", "sam"));
    assertTrue(live(reissued));
    assertEquals(root(), uuidColumn("issuing_grant", reissued));
    assertEquals(mia, uuidColumn("exercised_grant", reissued));
    assertEquals(expiry.toInstant(), timestamp("expires_at", reissued).toInstant());
    assertRevokedBySystem(sam);

    // mia's own grant was person-issued by the admin under control, so it is re-rooted too.
    UUID miaReissued = current("ROLE", "one", "mia");
    assertEquals(root(), uuidColumn("issuing_grant", miaReissued));
    assertEquals(control(), uuidColumn("exercised_grant", miaReissued));
    assertRevokedBySystem(mia);

    // Removing mia no longer removes sam.
    revoke(miaReissued);
    assertTrue(live(reissued));

    var audit =
        jdbc.queryForMap(
            "SELECT actor_account,authority FROM authority_grant_audit WHERE grant_id=? AND event='ISSUE'",
            reissued);
    assertEquals(account("mia"), audit.get("actor_account"));
    assertEquals(mia, audit.get("authority"));
  }

  @Test
  void lineage_dead_grants_are_revoked_not_revived() {
    UUID mia = issueMembership("one", "mia", "admin", "MANAGER");
    UUID sam = issueMembership("one", "sam", "mia", "CONTRIBUTOR");
    revoke(mia);
    assertFalse(live(sam));
    assertNull(timestamp("revoked_at", sam));

    migrateTo("261");

    assertRevokedBySystem(sam);
    assertEquals(0, liveGrantsOn("one", "sam"));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM authority_grants WHERE subject_account=? AND revoked_at IS NULL",
            Integer.class,
            account("sam")));
  }

  private void enabled(String handle, boolean enabled) {
    jdbc.update("UPDATE admins SET enabled=? WHERE handle=?", enabled, handle);
  }

  /**
   * A dormant grant (its subject disabled at deploy time) is neither revoked nor re-rooted, and nor
   * is its live issuing grant, so re-enabling the account restores its access.
   */
  @Test
  void disabled_member_under_live_manager_survives_reenable() {
    UUID mia = issueMembership("one", "mia", "admin", "MANAGER");
    UUID sam = issueMembership("one", "sam", "mia", "CONTRIBUTOR");
    enabled("sam", false);
    assertTrue(live(mia));
    assertFalse(live(sam));
    String miaBefore = row(mia);
    String samBefore = row(sam);

    migrateTo("261");

    assertEquals(miaBefore, row(mia));
    assertEquals(samBefore, row(sam));
    assertTrue(live(mia));
    enabled("sam", true);
    assertTrue(live(sam));
    assertEquals(1, liveGrantsOn("one", "sam"));
  }

  /** A disabled manager's grant and every grant issued under it come back on re-enable. */
  @Test
  void disabled_manager_with_issued_members_survives_reenable() {
    UUID mia = issueMembership("one", "mia", "admin", "MANAGER");
    UUID sam = issueMembership("one", "sam", "mia", "MANAGER");
    UUID tom = roleGrant("tom", null, "one", "READER", "sam", null, sam, false);
    var before = liveAccess();
    enabled("mia", false);
    assertFalse(live(mia));
    assertFalse(live(sam));
    assertFalse(live(tom));
    List<String> rows = List.of(row(mia), row(sam), row(tom));

    migrateTo("261");

    assertEquals(rows, List.of(row(mia), row(sam), row(tom)));
    enabled("mia", true);
    assertTrue(live(mia));
    assertTrue(live(sam));
    assertTrue(live(tom));
    assertEquals(before, liveAccess());
  }

  /**
   * A token-issued membership under a person-issued grant to a service: with the owning service
   * disabled, both are dormant, so neither is moved and both revive with the service.
   */
  @Test
  void disabled_service_owner_under_rerooted_grant_survives_reenable() {
    new JdbcServiceAccountRepository(jdbc).create("service");
    UUID owner = issueMembership("one", "service", "admin", "MANAGER");
    UUID sam = issueMembership("one", "sam", "@service/" + serviceToken("one"), "CONTRIBUTOR");
    // A live sibling under the same issuer is still re-rooted.
    UUID mia = issueMembership("one", "mia", "admin", "MANAGER");
    var before = liveAccess();
    enabled("service", false);
    assertFalse(live(owner));
    assertFalse(live(sam));
    String ownerBefore = row(owner);
    String samBefore = row(sam);

    migrateTo("261");

    assertEquals(ownerBefore, row(owner));
    assertEquals(samBefore, row(sam));
    assertRevokedBySystem(mia);
    assertEquals(root(), uuidColumn("issuing_grant", current("MEMBERSHIP", "one", "mia")));
    enabled("service", true);
    assertTrue(live(owner));
    assertTrue(live(sam));
    assertEquals(before, liveAccess());
  }

  /**
   * A token revocation is permanent, unlike disabling an account: the grant it issued is dead, not
   * dormant, so it does not stop its live issuing grant from being re-rooted.
   */
  @Test
  void grant_under_revoked_token_does_not_keep_its_issuer() {
    new JdbcServiceAccountRepository(jdbc).create("service");
    issueMembership("one", "service", "admin", "MANAGER");
    UUID token = serviceToken("one");
    UUID sam = issueMembership("one", "sam", "@service/" + token, "CONTRIBUTOR");
    jdbc.update("UPDATE service_tokens SET revoked_at=now() WHERE id=?", token);
    assertFalse(live(sam));

    migrateTo("261");

    assertFalse(live(sam));
    assertTrue(live(current("MEMBERSHIP", "one", "service")));
    assertEquals(root(), uuidColumn("issuing_grant", current("MEMBERSHIP", "one", "service")));
  }

  @Test
  void agent_and_token_issued_grants_are_untouched() {
    // tom's root-issued membership authorizes a forced MANAGER grant for his agent, which is a
    // self-issue; the agent then issues sam a grant on project two.
    member("one", "tom", "MANAGER");
    member("two", "tom", "MANAGER");
    UUID agentGrant = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO authority_grants(id,subject_account,subject_agent,scope_id,role_id,requested_role,"
            + "issuer_account,issuing_grant,manager_forced,provenance)"
            + " VALUES (?,?,'helper',?,?,'MANAGER',?,?,TRUE,'ROLE')",
        agentGrant,
        account("tom"),
        scope("two"),
        BuiltInIdentities.MANAGER.value(),
        account("tom"),
        current("MEMBERSHIP", "two", "tom"));
    UUID agentIssued = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO authority_grants(id,subject_account,scope_id,role_id,requested_role,issuer_account,"
            + "issuer_agent,issuing_grant,provenance) VALUES (?,?,?,?,'READER',?,'helper',?,'ROLE')",
        agentIssued,
        account("sam"),
        scope("two"),
        BuiltInIdentities.READER.value(),
        account("tom"),
        agentGrant);

    var services = new JdbcServiceAccountRepository(jdbc);
    services.create("service");
    member("one", "service", "MANAGER");
    UUID token = UUID.randomUUID();
    String principal = "@service/" + token;
    services.issue(
        token,
        "service",
        "manager",
        principal,
        "1".repeat(64),
        OffsetDateTime.now().plusDays(1),
        List.of(new ServiceAccounts.Scope("one", ProjectRole.MANAGER)));
    UUID tokenIssued = issueMembership("one", "sam", principal, "CONTRIBUTOR");
    assertEquals(token, uuidColumn("issuing_token", tokenIssued));

    String agentBefore = row(agentGrant);
    String agentIssuedBefore = row(agentIssued);
    String tokenIssuedBefore = row(tokenIssued);
    migrateTo("261");

    assertEquals(agentBefore, row(agentGrant));
    assertEquals(agentIssuedBefore, row(agentIssued));
    assertEquals(tokenIssuedBefore, row(tokenIssued));
    assertTrue(live(agentIssued));
    assertTrue(live(tokenIssued));
  }

  @Test
  void service_issued_grants_are_untouched() {
    new JdbcServiceAccountRepository(jdbc).create("service");
    member("one", "service", "MANAGER");
    UUID sam = issueMembership("one", "sam", "service", "CONTRIBUTOR");
    assertEquals(current("MEMBERSHIP", "one", "service"), uuidColumn("issuing_grant", sam));
    String before = row(sam);

    migrateTo("261");

    assertEquals(before, row(sam));
    assertTrue(live(sam));
  }

  @Test
  void self_issued_grants_are_untouched() {
    // A live self-assignment under admin control, and a lineage-dead one under a revoked
    // temporary grant: neither may become a permanent SYSTEM-rooted grant.
    UUID adminOwn = issueMembership("one", "admin", "admin", "MANAGER");
    UUID temporary = temporaryManager("mia");
    UUID miaOwn = issueMembership("one", "mia", "mia", "MANAGER");
    revoke(temporary);
    assertTrue(live(adminOwn));
    assertFalse(live(miaOwn));
    String adminBefore = row(adminOwn);
    String miaBefore = row(miaOwn);

    migrateTo("261");

    assertEquals(adminBefore, row(adminOwn));
    assertEquals(miaBefore, row(miaOwn));
    assertTrue(live(adminOwn));
    assertFalse(live(miaOwn));
  }

  @Test
  void system_minted_grants_are_untouched() {
    member("one", "mia", "MANAGER");
    member("two", "sam", "VIEWER");
    List<String> before =
        jdbc.queryForList(
            "SELECT (to_jsonb(g)-'exercised_grant')::text FROM authority_grants g"
                + " WHERE g.issuing_grant=? OR g.provenance='ADMIN_CONTROL' ORDER BY g.id",
            String.class,
            root());
    assertTrue(before.size() >= 3);

    migrateTo("261");

    assertEquals(
        before,
        jdbc.queryForList(
            "SELECT (to_jsonb(g)-'exercised_grant')::text FROM authority_grants g"
                + " WHERE (g.issuing_grant=? AND g.exercised_grant IS NULL)"
                + " OR g.provenance='ADMIN_CONTROL' ORDER BY g.id",
            String.class,
            root()));
  }

  /** Inserts a ROLE grant directly so V259's delegated lineage rules admit it. */
  private UUID roleGrant(
      String subject,
      String subjectAgent,
      String project,
      String role,
      String issuer,
      String issuerAgent,
      UUID issuingGrant,
      boolean managerForced) {
    UUID grant = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO authority_grants(id,subject_account,subject_agent,scope_id,role_id,requested_role,"
            + "issuer_account,issuer_agent,issuing_grant,manager_forced,provenance)"
            + " SELECT ?,?,?,?,r.id,r.name,?,?,?,?,'ROLE' FROM authority_roles r WHERE r.name=?",
        grant,
        account(subject),
        subjectAgent,
        scope(project),
        account(issuer),
        issuerAgent,
        issuingGrant,
        managerForced,
        role);
    return grant;
  }

  /** Every column that a re-parenting must preserve: all but identity, lineage and revocation. */
  private String attributes(UUID grant) {
    return jdbc.queryForObject(
        "SELECT (to_jsonb(g)-'id'-'created_at'-'issuing_grant'-'exercised_grant'-'revoked_at'"
            + "-'revoked_by'-'revoked_by_agent'-'revoking_grant'-'version')::text"
            + " FROM authority_grants g WHERE id=?",
        String.class,
        grant);
  }

  /** The unrevoked successor of a grant: the same attributes, written by V261. */
  private UUID successor(UUID grant) {
    return jdbc.queryForObject(
        "SELECT n.id FROM authority_grants o JOIN authority_grants n ON n.revoked_at IS NULL"
            + " AND n.id<>o.id AND (to_jsonb(n)-'id'-'created_at'-'issuing_grant'-'exercised_grant'"
            + "-'revoked_at'-'revoked_by'-'revoked_by_agent'-'revoking_grant'-'version')"
            + "=(to_jsonb(o)-'id'-'created_at'-'issuing_grant'-'exercised_grant'-'revoked_at'"
            + "-'revoked_by'-'revoked_by_agent'-'revoking_grant'-'version') WHERE o.id=?",
        UUID.class,
        grant);
  }

  /** Live access as (subject, agent, scope, role) tuples, independent of grant identity. */
  private List<String> liveAccess() {
    return jdbc.queryForList(
        "SELECT concat_ws('/',g.subject_account,coalesce(g.subject_agent,'-'),g.scope_id,g.role_id)"
            + " FROM authority_grants g JOIN authority_live_grants() l ON l.id=g.id ORDER BY 1",
        String.class);
  }

  private UUID serviceToken(String project) {
    var services = new JdbcServiceAccountRepository(jdbc);
    UUID token = UUID.randomUUID();
    services.issue(
        token,
        "service",
        "manager",
        "@service/" + token,
        "1".repeat(64),
        OffsetDateTime.now().plusDays(1),
        List.of(new ServiceAccounts.Scope(project, ProjectRole.MANAGER)));
    return token;
  }

  @Test
  void delegated_descendants_of_rerooted_grants_stay_live() {
    member("one", "mia", "MANAGER");
    UUID sam =
        roleGrant(
            "sam", null, "one", "MANAGER", "mia", null, current("MEMBERSHIP", "one", "mia"), false);
    UUID agent = roleGrant("sam", "agent-1", "one", "READER", "sam", null, sam, false);
    String attributes = attributes(agent);
    assertTrue(live(agent));

    migrateTo("261");

    UUID samReissued = successor(sam);
    assertEquals(root(), uuidColumn("issuing_grant", samReissued));
    UUID agentReissued = successor(agent);
    assertTrue(live(agentReissued));
    assertEquals(samReissued, uuidColumn("issuing_grant", agentReissued));
    assertNull(uuidColumn("exercised_grant", agentReissued));
    assertEquals(attributes, attributes(agentReissued));
    assertRevokedBySystem(agent);
  }

  @Test
  void token_issued_membership_under_rerooted_grant_stays_live() {
    new JdbcServiceAccountRepository(jdbc).create("service");
    UUID owner = issueMembership("one", "service", "admin", "MANAGER");
    UUID token = serviceToken("one");
    UUID sam = issueMembership("one", "sam", "@service/" + token, "CONTRIBUTOR");
    assertEquals(owner, uuidColumn("issuing_grant", sam));
    String attributes = attributes(sam);

    migrateTo("261");

    UUID ownerReissued = current("MEMBERSHIP", "one", "service");
    assertEquals(root(), uuidColumn("issuing_grant", ownerReissued));
    UUID samReissued = current("MEMBERSHIP", "one", "sam");
    assertTrue(live(samReissued));
    assertEquals(ownerReissued, uuidColumn("issuing_grant", samReissued));
    assertEquals(token, uuidColumn("issuing_token", samReissued));
    assertEquals(attributes, attributes(samReissued));
    assertRevokedBySystem(sam);
  }

  @Test
  void no_grant_live_before_migration_is_dead_after() {
    // Person chain: admin control -> mia -> sam, plus a ROLE grant sam holds from mia.
    UUID mia = issueMembership("one", "mia", "admin", "MANAGER");
    issueMembership("one", "sam", "mia", "CONTRIBUTOR");
    UUID samRole = roleGrant("sam", null, "one", "MANAGER", "mia", null, mia, false);
    // Self-issued agent grant under a re-rooted grant.
    roleGrant("sam", "agent-1", "one", "READER", "sam", null, samRole, false);
    // Agent-issued grant: mia's forced MANAGER agent issues tom a READER role.
    UUID helper = roleGrant("mia", "helper", "one", "MANAGER", "mia", null, mia, true);
    roleGrant("tom", null, "one", "READER", "mia", "helper", helper, false);
    // Token-issued and SERVICE-issued grants under the service's person-issued membership.
    new JdbcServiceAccountRepository(jdbc).create("service");
    UUID service = issueMembership("one", "service", "admin", "MANAGER");
    issueMembership("one", "tom", "@service/" + serviceToken("one"), "CONTRIBUTOR");
    roleGrant("tom", null, "one", "CONTRIBUTOR", "service", null, service, false);
    // A live admin self-assignment and a person chain that is already dead by lineage.
    issueMembership("two", "admin", "admin", "MANAGER");
    UUID miaTwo = issueMembership("two", "mia", "admin", "MANAGER");
    UUID samTwo = issueMembership("two", "sam", "mia", "READER");
    revoke(miaTwo);
    assertFalse(live(samTwo));
    var before = liveAccess();
    assertTrue(before.size() >= 10, () -> "fixture too small: " + before);

    migrateTo("261");

    assertEquals(before, liveAccess());
    assertRevokedBySystem(samTwo);
  }

  @Test
  void migration_refuses_a_missing_root() {
    // The root is immutable and referenced, so it cannot be deleted. Instead this test schema is
    // tampered with directly: drop the root/lineage CHECK, suspend the grant triggers, and turn the
    // root into an ordinary row so no ROOT_POLICY grant remains.
    UUID root = root();
    jdbc.execute(
        "DO $$ DECLARE c TEXT; BEGIN SELECT conname INTO STRICT c FROM pg_constraint"
            + " WHERE conrelid='authority_grants'::regclass AND contype='c'"
            + " AND pg_get_constraintdef(oid) LIKE '%ROOT_POLICY%issuing_grant IS NULL%';"
            + " EXECUTE format('ALTER TABLE authority_grants DROP CONSTRAINT %I',c); END $$");
    jdbc.execute("ALTER TABLE authority_grants DISABLE TRIGGER USER");
    jdbc.update("UPDATE authority_grants SET provenance='ROLE' WHERE id=?", root);
    jdbc.execute("ALTER TABLE authority_grants ENABLE TRIGGER USER");
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM authority_grants WHERE provenance='ROOT_POLICY'", Integer.class));

    var failure = assertThrows(FlywayException.class, () -> migrateTo("261"));
    assertTrue(
        failure.getMessage().contains("query returned no rows"),
        () -> "expected a STRICT root failure but was: " + failure.getMessage());
  }

  @Test
  void person_issued_grant_under_delegated_grant_is_rerooted() {
    // admin control -> mia (person) -> mia/helper (self, forced MANAGER) -> tom (agent-issued)
    // -> sam (person-issued by tom). Pass 1 re-roots mia and sam; pass 2 re-parents helper and tom.
    UUID mia = issueMembership("one", "mia", "admin", "MANAGER");
    UUID helper = roleGrant("mia", "helper", "one", "MANAGER", "mia", null, mia, true);
    UUID tom = roleGrant("tom", null, "one", "MANAGER", "mia", "helper", helper, false);
    UUID sam = roleGrant("sam", null, "one", "READER", "tom", null, tom, false);
    assertTrue(live(sam));
    var before = liveAccess();

    migrateTo("261");

    assertEquals(before, liveAccess());
    UUID samReissued = successor(sam);
    assertTrue(live(samReissued));
    assertEquals(root(), uuidColumn("issuing_grant", samReissued));
    // sam was reissued while tom's original grant was still live; that grant is recorded as the
    // authority even though pass 2 later re-parents tom.
    assertEquals(tom, uuidColumn("exercised_grant", samReissued));
    assertRevokedBySystem(sam);
    UUID helperReissued = successor(helper);
    UUID tomReissued = successor(tom);
    assertEquals(current("MEMBERSHIP", "one", "mia"), uuidColumn("issuing_grant", helperReissued));
    assertEquals(helperReissued, uuidColumn("issuing_grant", tomReissued));
    assertTrue(live(tomReissued));
    revoke(tomReissued);
    assertTrue(live(samReissued));
  }

  @Test
  void three_level_person_chain_reroots() {
    UUID mia = issueMembership("one", "mia", "admin", "MANAGER");
    UUID tom = roleGrant("tom", null, "one", "MANAGER", "mia", null, mia, false);
    UUID sam = roleGrant("sam", null, "one", "READER", "tom", null, tom, false);

    migrateTo("261");

    UUID miaReissued = current("MEMBERSHIP", "one", "mia");
    UUID tomReissued = successor(tom);
    UUID samReissued = successor(sam);
    assertEquals(control(), uuidColumn("exercised_grant", miaReissued));
    assertEquals(mia, uuidColumn("exercised_grant", tomReissued));
    assertEquals(tom, uuidColumn("exercised_grant", samReissued));
    for (UUID reissued : List.of(miaReissued, tomReissued, samReissued)) {
      assertEquals(root(), uuidColumn("issuing_grant", reissued));
      assertTrue(live(reissued));
    }
    for (UUID old : List.of(mia, tom, sam)) {
      assertRevokedBySystem(old);
    }
    revoke(miaReissued);
    revoke(tomReissued);
    assertTrue(live(samReissued));
  }
}
