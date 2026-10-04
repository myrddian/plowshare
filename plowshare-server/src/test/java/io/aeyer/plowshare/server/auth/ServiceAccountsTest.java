package io.aeyer.plowshare.server.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.ProjectAuthorization;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.ws.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.web.socket.WebSocketSession;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

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
    jdbc.execute("TRUNCATE projects,admins CASCADE");
    context = new AnnotationConfigApplicationContext();
    context.registerBean(JdbcTemplate.class, () -> jdbc);
    context.register(ServerAdministrationTest.Transactions.class);
    context.registerBean(
        org.springframework.transaction.PlatformTransactionManager.class,
        () -> new DataSourceTransactionManager(source));
    context.register(
        AdminStore.class, ProjectMembers.class, ServiceAccounts.class, SocketAuthorization.class);
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
                new DurableSessions(
                    jdbc,
                    new DataSourceTransactionManager(source),
                    Clock.systemUTC(),
                    Duration.ofMinutes(15),
                    Duration.ofDays(7)));
    tokens.withServiceCredentials(new ServiceCredentials(jdbc, Clock.systemUTC()));
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
  void machine_identity_has_no_interactive_login_or_administrator_role() {
    assertFalse(accounts.byHandle("ha-integration").orElseThrow().interactive());
    assertFalse(accounts.isServerAdmin("ha-integration"));
    var personal =
        new io.aeyer.plowshare.server.personal.PersonalSpaces(
            jdbc, io.aeyer.plowshare.server.data.DataLayout.NONE);
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
    var mvc =
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
            .build();
    try {
      assertEquals(
          401,
          mvc.perform(
                  org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                          "/v1/auth/login")
                      .contentType("application/json")
                      .content("{\"handle\":\"ha-integration\",\"password\":\"irrelevant\"}"))
              .andReturn()
              .getResponse()
              .getStatus());
    } catch (Exception failed) {
      throw new AssertionError(failed);
    }
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
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
    var information = new io.aeyer.plowshare.server.information.InformationAccess(members);
    var context = information.forRun(principal, Home.of("automation"));
    assertFalse(context.selection().includeShared());
    information.requireSelection(context);
    assertThrows(CallerFault.class, () -> information.resolve(principal, null));
    assertThrows(
        CallerFault.class,
        () ->
            information.resolve(
                principal,
                io.aeyer.plowshare.server.information.InformationContext.Selection.project(
                    "automation")));
    var policy = new ProjectAuthorization(jdbc, members, accounts);
    policy.require("agent.list", Map.of("project", "automation"), principal);
    policy.require("incoming.catalog", Map.of("project", "automation"), principal);
    policy.require("incoming.status", Map.of("project", "automation"), principal);
    assertThrows(
        CallerFault.class,
        () -> policy.require("incoming.receive", Map.of("project", "automation"), principal));
    assertThrows(
        CallerFault.class,
        () -> policy.require("incoming.catalog", Map.of("project", "other"), principal));
    assertThrows(
        CallerFault.class,
        () -> policy.require("agent.run", Map.of("project", "automation"), principal));
    assertThrows(
        CallerFault.class,
        () -> policy.require("agent.list", Map.of("project", "other"), principal));
    assertThrows(CallerFault.class, () -> policy.require("conversation.open", Map.of(), principal));
    assertThrows(
        CallerFault.class,
        () -> policy.require("provider.list", Map.of("project", "automation"), principal));
    assertThrows(CallerFault.class, () -> policy.require("admin.accounts", Map.of(), principal));
  }

  @Test
  void live_grants_intersect_token_scopes_and_cannot_be_recovered_by_rotation() {
    var issued = issue(ProjectRole.CONTRIBUTOR);
    String principal = issued.token().principal();
    assertTrue(members.mayWork("automation", principal));
    assertFalse(members.mayWork("other", principal));
    new ProjectAuthorization(jdbc, members, accounts)
        .require("incoming.receive", Map.of("project", "automation"), principal);
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
