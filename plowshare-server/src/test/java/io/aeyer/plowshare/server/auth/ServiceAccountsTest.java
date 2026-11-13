package io.aeyer.plowshare.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AccessRequest;
import io.aeyer.plowshare.server.access.AccessRequestDecoder;
import io.aeyer.plowshare.server.access.EffectiveProjectPermissions;
import io.aeyer.plowshare.server.access.JdbcResourceScopeRepository;
import io.aeyer.plowshare.server.access.ProjectAuthorization;
import io.aeyer.plowshare.server.archive.ArchiveConfig;
import io.aeyer.plowshare.server.archive.JdbcProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.information.InformationAccess;
import io.aeyer.plowshare.server.information.InformationContext;
import io.aeyer.plowshare.server.personal.JdbcPersonalSpaceRepository;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import io.aeyer.plowshare.server.reads.ReadCapacityConfiguration;
import io.aeyer.plowshare.server.testing.AuthorityFixtures;
import io.aeyer.plowshare.server.ws.EventChannelHandler;
import io.aeyer.plowshare.server.ws.SocketAuthorization;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.socket.WebSocketSession;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("full-db")
@Testcontainers
class ServiceAccountsTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static DriverManagerDataSource source;
  static JdbcTemplate jdbc;
  AnnotationConfigApplicationContext context;
  AdminStore accounts;
  ServiceAccounts services;
  ProjectMembers members;
  TokenStore tokens;
  SocketAuthorization sockets;

  @BeforeAll
  static void migrate() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
  }

  @BeforeEach
  void setup() {
    AuthorityFixtures.truncate(jdbc, "TRUNCATE projects,admins CASCADE");
    context = new AnnotationConfigApplicationContext();
    context.registerBean(JdbcTemplate.class, () -> jdbc);
    context.register(ServerAdministrationTest.Transactions.class);
    context.registerBean(
        PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
    context.register(
        AdminStore.class,
        JdbcProjectMembers.class,
        ServiceAccounts.class,
        SocketAuthorization.class,
        StoredSessionAuthority.class,
        ReadCapacityConfiguration.class);
    context.register(JdbcServiceAccountRepository.class);
    context.registerBean(
        UnitOfWork.class,
        () -> new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source)));
    context.refresh();
    accounts = context.getBean(AdminStore.class);
    members = context.getBean(ProjectMembers.class);
    services = context.getBean(ServiceAccounts.class);
    sockets = context.getBean(SocketAuthorization.class);
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password,server_admin) VALUES ('owner','hash',FALSE,TRUE),('regular','hash',FALSE,FALSE)");
    jdbc.update("INSERT INTO projects(name) VALUES ('automation'),('other')");
    services.create("owner", "ha-integration");
    members.assign("automation", "ha-integration", ProjectRole.MANAGER, "owner", true);
    members.assign("other", "ha-integration", ProjectRole.CONTRIBUTOR, "owner", true);
    tokens =
        new TokenStore(
                Clock.systemUTC(),
                Duration.ofMinutes(15),
                Duration.ofDays(7),
                Duration.ofSeconds(30))
            .withDurableSessions(
                new JdbcDurableSessions(
                    jdbc,
                    new DataSourceTransactionManager(source),
                    Clock.systemUTC(),
                    Duration.ofMinutes(15),
                    Duration.ofDays(7)));
    tokens.withServiceCredentials(new JdbcServiceCredentialsRepository(jdbc, Clock.systemUTC()));
  }

  @AfterEach
  void close() {
    context.close();
  }

  ServiceAccounts.Credential issue(ProjectRole role) {
    return services.issue(
        "owner",
        "ha-integration",
        "production",
        List.of(new ServiceAccounts.Scope("automation", role)),
        30);
  }

  @Test
  void ownerTokenListKeepsEmptyScopeTokensAndDoesNotMixOwners() {
    var empty = issue(ProjectRole.VIEWER);
    jdbc.update("DELETE FROM service_token_scopes WHERE token_id=?", empty.token().id());
    var scoped =
        services.issue(
            "owner",
            "ha-integration",
            "second",
            List.of(
                new ServiceAccounts.Scope("automation", ProjectRole.VIEWER),
                new ServiceAccounts.Scope("other", ProjectRole.VIEWER)),
            30);
    var repository = new JdbcServiceAccountRepository(jdbc);
    var listed = repository.tokens("ha-integration");
    assertEquals(2, listed.size());
    assertTrue(
        listed.stream()
            .filter(token -> token.id().equals(empty.token().id()))
            .findFirst()
            .orElseThrow()
            .scopes()
            .isEmpty());
    assertEquals(
        List.of("automation", "other"),
        listed.stream()
            .filter(token -> token.id().equals(scoped.token().id()))
            .findFirst()
            .orElseThrow()
            .scopes()
            .stream()
            .map(ServiceAccounts.Scope::project)
            .toList());
    assertTrue(repository.tokens("unrelated-owner").isEmpty());
  }

  @Test
  void machine_identity_has_no_interactive_login_or_administrator_role() {
    assertFalse(accounts.byHandle("ha-integration").orElseThrow().interactive());
    assertFalse(accounts.isServerAdmin("ha-integration"));
    var personal =
        new PersonalSpaces(
            new JdbcPersonalSpaceRepository(jdbc),
            new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(jdbc.getDataSource())),
            DataLayout.NONE);
    assertThrows(CallerFault.class, () -> personal.ensure("ha-integration"));
    assertTrue(personal.id("ha-integration").isEmpty());
    assertThrows(CallerFault.class, () -> tokens.issuePair("ha-integration", false));
    var controller =
        new AuthController(
            tokens,
            new AuthProperties(),
            accounts,
            new PasswordHasher(),
            new LoginAttempts(Clock.systemUTC(), 10, Duration.ofMinutes(5)));
    var mvc = MockMvcBuilders.standaloneSetup(controller).build();
    try {
      assertEquals(
          401,
          mvc.perform(
                  MockMvcRequestBuilders.post("/v1/auth/login")
                      .contentType("application/json")
                      .content("{\"handle\":\"ha-integration\",\"password\":\"irrelevant\"}"))
              .andReturn()
              .getResponse()
              .getStatus());
    } catch (Exception failed) {
      throw new AssertionError(failed);
    }
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE admins SET server_admin=TRUE WHERE handle='ha-integration'"));
    assertThrows(CallerFault.class, () -> services.create("regular", "another"));
    assertThrows(CallerFault.class, () -> services.list("regular"));
    assertThrows(CallerFault.class, () -> services.tokens("regular", "ha-integration"));
    assertThrows(CallerFault.class, () -> services.update("regular", "ha-integration", false));
  }

  @Test
  void scope_ceiling_applies_to_incoming_calls_and_durable_execution_identity() {
    var issued = issue(ProjectRole.VIEWER);
    String principal = issued.token().principal();
    assertTrue(tokens.validAccess(issued.credential()));
    assertEquals(principal, tokens.handleFor(issued.credential()).orElseThrow());
    assertEquals(ProjectRole.VIEWER, members.role("automation", principal).orElseThrow());
    assertFalse(members.mayWork("automation", principal));
    assertFalse(members.mayUse("other", principal));
    assertFalse(members.mayUse("personal:726567756c6172", principal));
    assertTrue(
        jdbc.queryForObject(
            "SELECT information_project_readable((SELECT id FROM projects WHERE name='automation'),?)",
            Boolean.class,
            principal));
    assertFalse(
        jdbc.queryForObject(
            "SELECT information_project_readable((SELECT id FROM projects WHERE name='other'),?)",
            Boolean.class,
            principal));
    var information = new InformationAccess(new EffectiveProjectPermissions(members));
    var context = information.forRun(principal, Home.of("automation"));
    assertFalse(context.selection().includeGlobal());
    information.requireSelection(context);
    // Ordinary discovery now permits public Global READ alongside the token's one scoped
    // project. Retained execution above keeps its home-only ceiling; Personal and foreign
    // projects remain unavailable. These are different policy boundaries, not ambient elevation.
    assertThrows(CallerFault.class, () -> information.resolve(principal, null));
    assertEquals(
        "automation",
        information
            .resolve(principal, InformationContext.Selection.project("automation"))
            .selection()
            .project());
    assertThrows(
        CallerFault.class,
        () -> information.resolve(principal, InformationContext.Selection.personal()));
    assertThrows(
        CallerFault.class,
        () -> information.resolve(principal, InformationContext.Selection.project("other")));
    var policy = new ProjectAuthorization(new JdbcResourceScopeRepository(jdbc), members, accounts);
    policy.require(
        "agent.list",
        AccessRequestDecoder.decode("agent.list", Map.of("project", "automation")),
        principal);
    policy.require(
        "relay.log",
        AccessRequestDecoder.decode("relay.log", Map.of("project", "automation")),
        principal);
    policy.require(
        "message.instances",
        AccessRequestDecoder.decode("message.instances", Map.of("project", "automation")),
        principal);
    assertThrows(
        CallerFault.class,
        () ->
            policy.require(
                "relay.publish",
                AccessRequestDecoder.decode("relay.publish", Map.of("project", "automation")),
                principal));
    assertThrows(
        CallerFault.class,
        () ->
            policy.require(
                "relay.log",
                AccessRequestDecoder.decode("relay.log", Map.of("project", "other")),
                principal));
    assertThrows(
        CallerFault.class,
        () ->
            policy.require(
                "agent.run",
                AccessRequestDecoder.decode("agent.run", Map.of("project", "automation")),
                principal));
    assertThrows(
        CallerFault.class,
        () ->
            policy.require(
                "agent.list",
                AccessRequestDecoder.decode("agent.list", Map.of("project", "other")),
                principal));
    assertThrows(
        CallerFault.class,
        () ->
            policy.require(
                "conversation.open",
                AccessRequestDecoder.decode("conversation.open", Map.of()),
                principal));
    assertThrows(
        CallerFault.class,
        () ->
            policy.require(
                "provider.list",
                AccessRequestDecoder.decode("provider.list", Map.of("project", "automation")),
                principal));
    assertThrows(
        CallerFault.class,
        () ->
            policy.require(
                "admin.accounts",
                AccessRequestDecoder.decode("admin.accounts", Map.of()),
                principal));
  }

  @Test
  void live_grants_intersect_token_scopes_and_cannot_be_recovered_by_rotation() {
    var issued = issue(ProjectRole.CONTRIBUTOR);
    String principal = issued.token().principal();
    assertTrue(members.mayWork("automation", principal));
    assertFalse(members.mayWork("other", principal));
    new ProjectAuthorization(new JdbcResourceScopeRepository(jdbc), members, accounts)
        .require("relay.publish", AccessRequest.project("automation"), principal);
    members.assign("automation", "ha-integration", ProjectRole.VIEWER, "owner", false);
    assertEquals(ProjectRole.VIEWER, members.role("automation", principal).orElseThrow());
    assertFalse(members.mayWork("automation", principal));
    assertThrows(
        CallerFault.class,
        () -> services.rotate("owner", "ha-integration", issued.token().id(), 30));
    members.remove("automation", "ha-integration", "owner");
    assertFalse(members.mayUse("automation", principal));
    assertFalse(
        jdbc.queryForObject(
            "SELECT information_project_readable((SELECT id FROM projects WHERE name='automation'),?)",
            Boolean.class,
            principal));
  }

  WebSocketSession attach(String principal) throws Exception {
    var socket = mock(WebSocketSession.class);
    when(socket.getAttributes())
        .thenReturn(
            new HashMap<>(
                Map.of(
                    EventChannelHandler.HANDLE,
                    principal,
                    "plowshare.sessionVersion",
                    accounts.sessionVersion(principal))));
    assertTrue(sockets.attach(socket));
    return socket;
  }

  @Test
  void rotation_preserves_identity_but_retires_old_credentials_tickets_and_live_sockets()
      throws Exception {
    var issued = issue(ProjectRole.CONTRIBUTOR);
    String principal = issued.token().principal();
    String ticket = tokens.mintTicket(principal);
    var socket = attach(principal);
    var rotated = services.rotate("owner", "ha-integration", issued.token().id(), 60);
    assertEquals(issued.token().id(), rotated.token().id());
    assertEquals(principal, rotated.token().principal());
    assertFalse(tokens.validAccess(issued.credential()));
    assertTrue(tokens.validAccess(rotated.credential()));
    assertTrue(tokens.redeemTicket(ticket).isEmpty());
    verify(socket).close(argThat(s -> s.getCode() == 1008));
    String newTicket = tokens.mintTicket(principal);
    assertEquals(principal, tokens.redeemTicket(newTicket).orElseThrow().handle());
    services.revoke("owner", "ha-integration", rotated.token().id());
    assertFalse(tokens.validAccess(rotated.credential()));
    assertFalse(members.mayUse("automation", principal));
  }

  @Test
  void expiry_and_parent_disable_retire_execution_access_and_reenable_does_not_revive_keys()
      throws Exception {
    var issued = issue(ProjectRole.CONTRIBUTOR);
    String principal = issued.token().principal();
    var socket = attach(principal);
    String ticket = tokens.mintTicket(principal);
    jdbc.update(
        "UPDATE service_tokens SET expires_at=now()-interval '1 second' WHERE id=?",
        issued.token().id());
    assertFalse(tokens.validAccess(issued.credential()));
    assertFalse(members.mayWork("automation", principal));
    assertTrue(tokens.redeemTicket(ticket).isEmpty());
    assertFalse(sockets.current(socket));
    var renewed = services.rotate("owner", "ha-integration", issued.token().id(), 30);
    assertTrue(tokens.validAccess(renewed.credential()));
    services.update("owner", "ha-integration", false);
    assertFalse(tokens.validAccess(renewed.credential()));
    services.update("owner", "ha-integration", true);
    assertFalse(tokens.validAccess(renewed.credential()));
    assertTrue(
        tokens.validAccess(
            services.rotate("owner", "ha-integration", issued.token().id(), 30).credential()));
  }

  @Test
  void credentials_are_one_time_responses_and_persist_only_as_digests() {
    var issued = issue(ProjectRole.CONTRIBUTOR);
    assertTrue(issued.credential().startsWith("pss_"));
    assertFalse(issued.toString().contains(issued.credential()));
    assertEquals(
        Tokens.hash(issued.credential()),
        jdbc.queryForObject(
            "SELECT digest FROM service_tokens WHERE id=?", String.class, issued.token().id()));
    assertFalse(
        services.tokens("owner", "ha-integration").toString().contains(issued.credential()));
    assertFalse(
        jdbc.queryForList("SELECT * FROM admin_audit").toString().contains(issued.credential()));
    assertThrows(
        CallerFault.class,
        () ->
            services.issue(
                "owner",
                "ha-integration",
                "second",
                List.of(new ServiceAccounts.Scope("other", ProjectRole.MANAGER)),
                30));
    assertThrows(
        CallerFault.class,
        () -> services.issue("owner", "ha-integration", "second", List.of(), 30));
    assertThrows(
        CallerFault.class,
        () ->
            services.issue(
                "owner",
                "ha-integration",
                "second",
                List.of(new ServiceAccounts.Scope("Personal:owner", ProjectRole.VIEWER)),
                30));
    assertThrows(
        CallerFault.class,
        () ->
            services.issue(
                "owner",
                "ha-integration",
                "second",
                List.of(new ServiceAccounts.Scope("automation", ProjectRole.VIEWER)),
                366));
    assertEquals(1, services.tokens("owner", "ha-integration").size());
  }
}
