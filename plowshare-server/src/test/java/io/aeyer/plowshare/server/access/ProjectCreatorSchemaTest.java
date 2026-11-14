package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.archive.JdbcProjectMembers;
import io.aeyer.plowshare.server.auth.JdbcServiceAccountRepository;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PostgreSQL proves the V262 schema: the reserved creator role and creation scope, the trigger
 * branch that admits a creator grant only when an active administrator exercises their live
 * ADMIN_CONTROL grant, the one-live-grant index, {@code authority_may_create_projects}, and that
 * the role confers nothing beyond itself.
 */
@Tag("full-db")
@Testcontainers
class ProjectCreatorSchemaTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withUrlParam("sslmode", "disable")
          .withUrlParam("gssEncMode", "disable");

  private static final UUID CREATION = BuiltInIdentities.PROJECT_CREATION.value();

  private JdbcTemplate jdbc;

  /** Each test migrates its own schema so grant fixtures never leak between cases. */
  @BeforeEach
  void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var schema = "creator_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(source).execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
    new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
    var properties = new Properties();
    properties.setProperty("currentSchema", schema + ",public");
    source.setConnectionProperties(properties);
    Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
    jdbc = new JdbcTemplate(source);
    user("admin", true);
    user("other", true);
    user("mia", false);
    user("sam", false);
    jdbc.update("INSERT INTO projects(name) VALUES ('one')");
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

  private UUID projectScope(String project) {
    return jdbc.queryForObject(
        "SELECT s.id FROM authority_scopes s JOIN projects p ON p.id=s.project_id WHERE p.name=?",
        UUID.class,
        project);
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

  /**
   * The admin's ADMIN_CONTROL grant at the global knowledge scope: ADMIN, but not ADMINISTRATION.
   */
  private UUID knowledgeControl(String handle) {
    return jdbc.queryForObject(
        "SELECT id FROM authority_grants WHERE provenance='ADMIN_CONTROL' AND revoked_at IS NULL"
            + " AND scope_id=? AND subject_account=?",
        UUID.class,
        BuiltInIdentities.GLOBAL_KNOWLEDGE.value(),
        account(handle));
  }

  private UUID membership(String project, String target, String actor, String role) {
    return jdbc.queryForObject(
        "SELECT authority_issue_membership(?,?,?,?)", UUID.class, project, target, actor, role);
  }

  /**
   * Inserts a SYSTEM-rooted ROLE grant exercised from {@code exercised}, so the trigger, not a
   * service, is under test.
   */
  private UUID insert(
      String subject, UUID scope, String role, String issuer, UUID exercised, String agent) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO authority_grants(id,subject_account,subject_agent,scope_id,role_id,requested_role,"
            + "issuer_account,issuing_grant,exercised_grant,provenance)"
            + " SELECT ?,?,?,?,r.id,r.name,?,?,?,'ROLE' FROM authority_roles r WHERE r.name=?",
        id,
        account(subject),
        agent,
        scope,
        account(issuer),
        root(),
        exercised,
        role);
    return id;
  }

  private UUID creator(String subject) {
    return insert(subject, CREATION, "PROJECT_CREATOR", "admin", control("admin"), null);
  }

  private boolean mayCreate(String handle) {
    return jdbc.queryForObject("SELECT authority_may_create_projects(?)", Boolean.class, handle);
  }

  private void assertRefused(String message, Runnable insert) {
    var failure = assertThrows(DataAccessException.class, insert::run);
    assertTrue(
        failure.getMessage().contains(message),
        () -> "expected '" + message + "' but was: " + failure.getMessage());
  }

  @Test
  void migration_registers_creator_role_scope_and_single_action() {
    var role =
        jdbc.queryForList(
            "SELECT id FROM authority_roles WHERE name='PROJECT_CREATOR' AND assignable"
                + " AND intrinsic_account IS NULL",
            UUID.class);
    assertEquals(List.of(BuiltInIdentities.PROJECT_CREATOR.value()), role);
    assertEquals(
        List.of("PROJECT_CREATE"),
        jdbc.queryForList(
            "SELECT action FROM authority_role_actions WHERE role_id=?"
                + " AND role_version=1 AND profile='DEFAULT'",
            String.class,
            BuiltInIdentities.PROJECT_CREATOR.value()));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM authority_role_actions WHERE role_id=?",
            Integer.class,
            BuiltInIdentities.PROJECT_CREATOR.value()));
    assertEquals(
        List.of(CREATION),
        jdbc.queryForList(
            "SELECT id FROM authority_scopes WHERE kind='PROJECT_CREATION'", UUID.class));
    assertEquals(
        Map.of(
            "builtin",
            "PROJECT_CREATION",
            "account_id",
            BuiltInIdentities.SYSTEM.value(),
            "no_project",
            true),
        jdbc.queryForMap(
            "SELECT builtin,account_id,project_id IS NULL AS no_project FROM authority_scopes"
                + " WHERE id=?",
            CREATION));
    assertTrue(jdbc.queryForObject("SELECT authority_builtin_registry_valid()", Boolean.class));
    new JdbcIdentityRepository(jdbc).verifyBuiltIns();
    assertInstanceOf(
        AuthorityScope.ProjectCreation.class,
        new JdbcIdentityRepository(jdbc).scope(BuiltInIdentities.PROJECT_CREATION));
    // Nothing but the reserved row may take the kind, and the reserved ids stay reserved.
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO authority_scopes(kind,account_id) VALUES ('PROJECT_CREATION',?)",
                BuiltInIdentities.SYSTEM.value()));
    assertTrue(
        jdbc.queryForObject(
            "SELECT authority_reserved_id(?) AND authority_reserved_id(?)",
            Boolean.class,
            CREATION,
            BuiltInIdentities.PROJECT_CREATOR.value()));
  }

  @Test
  void creator_grant_requires_admin_exercised_grant() {
    UUID manager = membership("one", "mia", "admin", "MANAGER");
    assertRefused(
        "Role exceeds issuing authority",
        () -> insert("sam", CREATION, "PROJECT_CREATOR", "mia", manager, null));
    // ADMIN, but at the global knowledge scope rather than ADMINISTRATION.
    assertRefused(
        "Role exceeds issuing authority",
        () -> insert("sam", CREATION, "PROJECT_CREATOR", "admin", knowledgeControl("admin"), null));
    // The root alone, exercised by nobody, cannot mint the role either.
    assertRefused(
        "Role exceeds issuing authority",
        () ->
            jdbc.update(
                "INSERT INTO authority_grants(subject_account,scope_id,role_id,requested_role,"
                    + "issuer_account,issuing_grant,provenance) VALUES (?,?,?,'PROJECT_CREATOR',?,?,'ROLE')",
                account("sam"),
                CREATION,
                BuiltInIdentities.PROJECT_CREATOR.value(),
                BuiltInIdentities.SYSTEM.value(),
                root()));
    UUID grant = creator("sam");
    assertEquals(
        Map.of("issuing_grant", root(), "exercised_grant", control("admin")),
        jdbc.queryForMap(
            "SELECT issuing_grant,exercised_grant FROM authority_grants WHERE id=?", grant));
    assertTrue(mayCreate("sam"));
  }

  @Test
  void creation_scope_accepts_only_creator_grants() {
    for (String role : List.of("READER", "CONTRIBUTOR", "MANAGER", "ADMIN"))
      assertThrows(
          DataAccessException.class,
          () -> insert("sam", CREATION, role, "admin", control("admin"), null),
          role);
    assertRefused(
        "Role exceeds issuing authority",
        () -> insert("sam", CREATION, "READER", "admin", control("admin"), null));
    for (UUID elsewhere :
        List.of(
            projectScope("one"),
            BuiltInIdentities.ADMINISTRATION.value(),
            BuiltInIdentities.GLOBAL_KNOWLEDGE.value()))
      assertThrows(
          DataAccessException.class,
          () -> insert("sam", elsewhere, "PROJECT_CREATOR", "admin", control("admin"), null));
    assertRefused(
        "Role exceeds issuing authority",
        () ->
            insert("sam", projectScope("one"), "PROJECT_CREATOR", "admin", control("admin"), null));
  }

  @Test
  void creator_grant_recipient_must_be_a_user_without_agent() {
    new JdbcServiceAccountRepository(jdbc).create("service");
    assertThrows(
        DataAccessException.class,
        () -> insert("service", CREATION, "PROJECT_CREATOR", "admin", control("admin"), null));
    assertThrows(
        DataAccessException.class,
        () -> insert("sam", CREATION, "PROJECT_CREATOR", "admin", control("admin"), "worker"));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM authority_grants WHERE scope_id=?", Integer.class, CREATION));
  }

  @Test
  void creator_grant_shape_is_self_role_and_not_self_issued() {
    // A self-grant is admitted only bound to the admin's control (see the bound tests), never
    // exercised: V260's refusal of exercised self-issuance still applies to this role.
    assertRefused(
        "Cannot exercise a grant for yourself",
        () -> insert("admin", CREATION, "PROJECT_CREATOR", "admin", control("admin"), null));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO authority_grants(subject_account,scope_id,role_id,requested_role,coverage,"
                    + "issuer_account,issuing_grant,exercised_grant,provenance)"
                    + " VALUES (?,?,?,'PROJECT_CREATOR','ALL_SCOPES',?,?,?,'ROLE')",
                account("sam"),
                CREATION,
                BuiltInIdentities.PROJECT_CREATOR.value(),
                account("admin"),
                root(),
                control("admin")));
    assertRefused(
        "Role exceeds issuing authority",
        () ->
            jdbc.update(
                "INSERT INTO authority_grants(subject_account,scope_id,role_id,requested_role,"
                    + "issuer_account,issuing_grant,exercised_grant,provenance)"
                    + " VALUES (?,?,?,'PROJECT_CREATOR',?,?,?,'MEMBERSHIP')",
                account("sam"),
                CREATION,
                BuiltInIdentities.PROJECT_CREATOR.value(),
                account("admin"),
                root(),
                control("admin")));
  }

  /**
   * Inserts a bound self-grant: issuer and subject are {@code handle}, the issuing grant is {@code
   * issuing} and nothing is exercised.
   */
  private UUID bound(String handle, UUID scope, String role, UUID issuing) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO authority_grants(id,subject_account,scope_id,role_id,requested_role,"
            + "issuer_account,issuing_grant,provenance)"
            + " SELECT ?,?,?,r.id,r.name,?,?,'ROLE' FROM authority_roles r WHERE r.name=?",
        id,
        account(handle),
        scope,
        account(handle),
        issuing,
        role);
    return id;
  }

  @Test
  void admin_self_grant_is_bound_to_admin_control() {
    UUID grant = bound("admin", CREATION, "PROJECT_CREATOR", control("admin"));
    assertEquals(
        control("admin"),
        jdbc.queryForObject(
            "SELECT issuing_grant FROM authority_grants WHERE id=?", UUID.class, grant));
    assertTrue(mayCreate("admin"));
    jdbc.update("UPDATE admins SET server_admin=FALSE WHERE handle='admin'");
    assertFalse(mayCreate("admin"));
  }

  @Test
  void self_grant_shape_rejected_for_other_roles() {
    // Any non-creator role at the creation scope, bound to the admin's own control.
    for (String role : List.of("READER", "CONTRIBUTOR", "MANAGER", "ADMIN"))
      assertThrows(
          DataAccessException.class, () -> bound("admin", CREATION, role, control("admin")), role);
    assertRefused(
        "Role exceeds issuing authority",
        () -> bound("admin", CREATION, "READER", control("admin")));
    // The creator role, bound, at any other scope.
    assertRefused(
        "Role exceeds issuing authority",
        () -> bound("admin", projectScope("one"), "PROJECT_CREATOR", control("admin")));
    // Bound to anything but ADMIN_CONTROL at ADMINISTRATION: a project manager's membership, or
    // admin control at the global knowledge scope.
    UUID manager = membership("one", "mia", "admin", "MANAGER");
    assertRefused(
        "Role exceeds issuing authority", () -> bound("mia", CREATION, "PROJECT_CREATOR", manager));
    assertRefused(
        "Role exceeds issuing authority",
        () -> bound("admin", CREATION, "PROJECT_CREATOR", knowledgeControl("admin")));
    // Bound to another administrator's control: the delegated path refuses a foreign issuing grant.
    assertRefused(
        "Issuing grant belongs to a different principal",
        () -> bound("admin", CREATION, "PROJECT_CREATOR", control("other")));
    // A bound grant to someone else is not a self-grant.
    assertRefused(
        "Role exceeds issuing authority",
        () ->
            jdbc.update(
                "INSERT INTO authority_grants(subject_account,scope_id,role_id,requested_role,"
                    + "issuer_account,issuing_grant,provenance) VALUES (?,?,?,'PROJECT_CREATOR',?,?,'ROLE')",
                account("sam"),
                CREATION,
                BuiltInIdentities.PROJECT_CREATOR.value(),
                account("admin"),
                control("admin")));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM authority_grants WHERE scope_id=?", Integer.class, CREATION));
  }

  @Test
  void one_live_creator_grant_per_account() {
    UUID first = creator("sam");
    assertThrows(
        DuplicateKeyException.class,
        () -> insert("sam", CREATION, "PROJECT_CREATOR", "other", control("other"), null));
    jdbc.update(
        "UPDATE authority_grants SET revoked_at=now(),version=version+1,revoked_by=?,revoking_grant=?"
            + " WHERE id=?",
        account("admin"),
        control("admin"),
        first);
    insert("sam", CREATION, "PROJECT_CREATOR", "other", control("other"), null);
    assertTrue(mayCreate("sam"));
  }

  @Test
  void may_create_projects_function() {
    assertFalse(mayCreate("sam"));
    UUID grant = creator("sam");
    assertTrue(mayCreate("sam"));
    // The grant belongs to the role, not the issuing admin's continued standing.
    jdbc.update("UPDATE admins SET server_admin=FALSE WHERE handle='admin'");
    assertTrue(mayCreate("sam"));
    jdbc.update(
        "UPDATE authority_grants SET revoked_at=now(),version=version+1,revoked_by=?,revoking_grant=?"
            + " WHERE id=?",
        account("other"),
        control("other"),
        grant);
    assertFalse(mayCreate("sam"));

    jdbc.update(
        "INSERT INTO authority_grants(subject_account,scope_id,role_id,requested_role,issuer_account,"
            + "issuing_grant,exercised_grant,provenance,created_at,expires_at)"
            + " VALUES (?,?,?,'PROJECT_CREATOR',?,?,?,'ROLE',now()-interval '2 days',now()-interval '1 day')",
        account("mia"),
        CREATION,
        BuiltInIdentities.PROJECT_CREATOR.value(),
        account("other"),
        root(),
        control("other"));
    assertFalse(mayCreate("mia"));

    user("dora", false);
    insert("dora", CREATION, "PROJECT_CREATOR", "other", control("other"), null);
    assertTrue(mayCreate("dora"));
    jdbc.update("UPDATE admins SET enabled=FALSE WHERE handle='dora'");
    assertFalse(mayCreate("dora"));

    user("boot", false);
    insert("boot", CREATION, "PROJECT_CREATOR", "other", control("other"), null);
    assertTrue(mayCreate("boot"));
    jdbc.update("UPDATE admins SET bootstrap=TRUE,must_change_password=TRUE WHERE handle='boot'");
    assertFalse(mayCreate("boot"));

    // Administration is not creation: an admin without the grant cannot create through it.
    assertFalse(mayCreate("other"));
    assertFalse(mayCreate("nobody"));
  }

  @Test
  void creator_grant_confers_nothing_else() {
    creator("sam");
    assertTrue(mayCreate("sam"));
    var members = new JdbcProjectMembers(jdbc);
    assertFalse(members.isServerAdmin("sam"));
    assertFalse(members.mayAdminister("one", "sam"));
    assertFalse(members.mayUse("one", "sam"));

    var identities = new JdbcIdentityRepository(jdbc);
    var grants = new JdbcScopedGrantRepository(jdbc);
    ApplicationPolicy applications =
        ignored -> new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.EXTERNAL, Map.of());
    var authority =
        new LiveScopedAuthority(
            grants, applications, new ApplicationGrantLineage(grants, applications), identities);
    var recipient = GrantRecipient.account(new AccountId(account("sam")));
    var live = grants.live(recipient);
    assertEquals(1, live.size());
    assertEquals(StandardRole.PROJECT_CREATOR, live.getFirst().role());
    assertEquals(Set.of(AuthorityAction.PROJECT_CREATE), live.getFirst().actions());
    UUID held = live.getFirst().id();
    for (ScopeId target :
        List.of(BuiltInIdentities.ADMINISTRATION, BuiltInIdentities.PROJECT_CREATION))
      for (AuthorityAction action : AuthorityAction.values()) {
        var scope = identities.scope(target);
        assertFalse(authority.allows(recipient, scope, action), target + " " + action);
        assertFalse(authority.allowsUsing(recipient, scope, action, held), target + " " + action);
      }
    var project = identities.project("one");
    for (AuthorityAction action : AuthorityAction.values())
      assertFalse(authority.allows(recipient, project, action), action::name);
  }
}
