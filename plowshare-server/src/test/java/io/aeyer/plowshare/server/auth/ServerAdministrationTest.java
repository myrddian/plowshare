package io.aeyer.plowshare.server.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.ws.*;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Tag("full-db")
@Testcontainers
class ServerAdministrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static DriverManagerDataSource source;
  static JdbcTemplate jdbc;
  AnnotationConfigApplicationContext context;
  AdminStore accounts;
  ServerAdministration service;
  TokenStore tokens;
  PasswordHasher passwords;
  SocketAuthorization sockets;

  @org.springframework.context.annotation.Configuration
  @org.springframework.transaction.annotation.EnableTransactionManagement
  static class Transactions {}

  @BeforeAll
  static void migrate() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
  }

  TokenStore server() {
    return new TokenStore(
            Clock.systemUTC(), Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofSeconds(30))
        .withDurableSessions(
            new JdbcDurableSessions(
                jdbc,
                new DataSourceTransactionManager(source),
                Clock.systemUTC(),
                Duration.ofMinutes(15),
                Duration.ofDays(7)));
  }

  @BeforeEach
  void setup() {
    jdbc.execute("TRUNCATE admins,admin_audit CASCADE");
    context = new AnnotationConfigApplicationContext();
    context.registerBean(JdbcTemplate.class, () -> jdbc);
    context.registerBean(
        org.springframework.transaction.PlatformTransactionManager.class,
        () -> new DataSourceTransactionManager(source));
    context.register(Transactions.class);
    context.registerBean(AdminStore.class);
    context.registerBean(PasswordHasher.class);
    context.registerBean(TokenStore.class, this::server);
    context.register(ServerAdministration.class, SocketAuthorization.class);
    context.register(JdbcAccountAdministrationRepository.class);
    context.registerBean(
        io.aeyer.plowshare.server.archive.UnitOfWork.class,
        () ->
            new io.aeyer.plowshare.server.archive.ArchiveConfig()
                .unitOfWork(new DataSourceTransactionManager(source)));
    context.refresh();
    accounts = context.getBean(AdminStore.class);
    service = context.getBean(ServerAdministration.class);
    tokens = context.getBean(TokenStore.class);
    passwords = context.getBean(PasswordHasher.class);
    sockets = context.getBean(SocketAuthorization.class);
    accounts.create("owner", passwords.hash("owner-test-password".toCharArray()));
    accounts.changePassword("owner", passwords.hash("owner-test-password".toCharArray()));
  }

  @AfterEach
  void close() {
    context.close();
  }

  @Test
  void create_roles_credentials_and_audit_are_persistent_and_nonsecret() {
    var result = service.create("owner", "worker", false);
    assertFalse(result.account().serverAdmin());
    assertTrue(result.account().mustChangePassword());
    assertTrue(
        passwords.matches(
            result.temporaryPassword().toCharArray(),
            accounts.byHandle("worker").orElseThrow().passwordHash()));
    assertFalse(result.toString().contains(result.temporaryPassword()));
    assertEquals(2, service.list("owner").size());
    assertEquals(
        "account.create", service.history("owner", "worker", 0, 50).entries().getFirst().action());
    assertFalse(
        jdbc.queryForList("SELECT * FROM admin_audit")
            .toString()
            .contains(result.temporaryPassword()));
    assertThrows(CallerFault.class, () -> service.create("owner", "worker", true));
    assertThrows(CallerFault.class, () -> service.create("owner", "Personal:worker", true));
    assertEquals(1, service.history("owner", "", 0, 50).entries().size());
  }

  @Test
  void new_administrator_must_complete_password_setup_before_replacing_the_current_one() {
    service.create("owner", "successor", true);
    assertThrows(CallerFault.class, () -> service.update("owner", "owner", false, null));
    accounts.changePassword("successor", passwords.hash("successor-test-password".toCharArray()));
    service.update("owner", "owner", false, null);
    assertFalse(accounts.isEnabled("owner"));
    assertTrue(accounts.isServerAdmin("successor"));
  }

  @Test
  void a_handshake_or_ticket_cannot_adopt_the_new_version_after_revocation() throws Exception {
    service.create("owner", "worker", false);
    var pair = tokens.issuePair("worker", false);
    var request = new MockHttpServletRequest("GET", EventChannelHandler.PATH);
    request.setServletPath(EventChannelHandler.PATH);
    request.addHeader("Authorization", "Bearer " + pair.access());
    var response = new MockHttpServletResponse();
    var filter = new AuthFilter(tokens, new AuthProperties()).withAccounts(accounts);
    var attributes = new java.util.HashMap<String, Object>();
    filter.doFilter(
        request,
        response,
        (req, res) -> {
          assertEquals("worker", req.getAttribute(AuthFilter.HANDLE_ATTRIBUTE));
          attributes.put(EventChannelHandler.HANDLE, "worker");
          attributes.put("plowshare.sessionVersion", req.getAttribute("plowshare.sessionVersion"));
        });
    assertEquals(0L, attributes.get("plowshare.sessionVersion"));
    service.revokeSessions("owner", "worker");
    var socket = mock(WebSocketSession.class);
    when(socket.getAttributes()).thenReturn(attributes);
    assertFalse(sockets.attach(socket));
    verify(socket).close(argThat(status -> status.getCode() == 1008));
    String stale = tokens.mintTicket("worker", (Long) attributes.get("plowshare.sessionVersion"));
    assertTrue(tokens.redeemTicket(stale).isEmpty());
    var controller =
        new AuthController(
            tokens,
            new AuthProperties(),
            accounts,
            passwords,
            new LoginAttempts(Clock.systemUTC(), 10, Duration.ofMinutes(5)));
    String staleControllerTicket = controller.ticket(request).getBody().ticket();
    assertTrue(tokens.redeemTicket(staleControllerTicket).isEmpty());
  }

  @Test
  void nonadministrators_cannot_read_or_mutate_operator_resources() {
    service.create("owner", "worker", false);
    assertThrows(CallerFault.class, () -> service.list("worker"));
    assertThrows(CallerFault.class, () -> service.history("worker", "", 0, 50));
    assertThrows(CallerFault.class, () -> service.sessions("worker", "owner"));
    assertThrows(CallerFault.class, () -> service.update("worker", "worker", null, true));
    assertThrows(CallerFault.class, () -> service.reset("worker", "owner"));
    assertThrows(CallerFault.class, () -> service.revokeSessions("worker", "owner"));
  }

  @Test
  void last_enabled_administrator_survives_concurrent_role_changes() throws Exception {
    assertThrows(CallerFault.class, () -> service.update("owner", "owner", false, null));
    assertThrows(CallerFault.class, () -> service.update("owner", "owner", null, false));
    service.create("owner", "other", true);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var start = new CountDownLatch(1);
      var results =
          java.util.List.of("owner", "other").stream()
              .map(
                  handle ->
                      executor.submit(
                          () -> {
                            start.await();
                            try {
                              service.update(handle, handle, null, false);
                              return true;
                            } catch (CallerFault refused) {
                              return false;
                            }
                          }))
              .toList();
      start.countDown();
      assertEquals(
          1,
          results.stream()
              .filter(
                  f -> {
                    try {
                      return f.get();
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  })
              .count());
    }
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM admins WHERE enabled AND server_admin", Integer.class));
  }

  @Test
  void reset_and_revoke_retire_tokens_tickets_and_both_socket_roles_after_commit()
      throws Exception {
    service.create("owner", "worker", false);
    var pair = tokens.issuePair("worker", false);
    var remote = server();
    String ticket = remote.mintTicket("worker");
    WebSocketSession event = mock(WebSocketSession.class), file = mock(WebSocketSession.class);
    for (var socket : java.util.List.of(event, file)) {
      when(socket.getAttributes())
          .thenReturn(
              new java.util.HashMap<>(
                  Map.of(
                      EventChannelHandler.HANDLE,
                      "worker",
                      "plowshare.sessionVersion",
                      accounts.sessionVersion("worker"))));
      assertTrue(sockets.attach(socket));
    }
    String oldHash = accounts.byHandle("worker").orElseThrow().passwordHash();
    var reset = service.reset("owner", "worker");
    for (var socket : java.util.List.of(event, file))
      verify(socket).close(argThat(status -> status.getCode() == 1008));
    assertFalse(remote.validAccess(pair.access()));
    assertTrue(remote.refresh(pair.refresh()).isEmpty());
    assertTrue(remote.redeemTicket(ticket).isEmpty());
    assertTrue(
        passwords.matches(
            reset.temporaryPassword().toCharArray(),
            accounts.byHandle("worker").orElseThrow().passwordHash()));
    assertThrows(CallerFault.class, () -> tokens.issuePair("worker", false, oldHash));
    assertFalse(accounts.changePassword("worker", "replacement", oldHash));
    assertThrows(CallerFault.class, () -> service.reset("owner", "owner"));
  }

  @Test
  void disabling_preserves_account_but_blocks_login_and_reenable_does_not_revive_credentials()
      throws Exception {
    var created = service.create("owner", "worker", false);
    var pair = tokens.issuePair("worker", false);
    service.update("owner", "worker", false, null);
    assertFalse(accounts.byHandle("worker").orElseThrow().enabled());
    assertFalse(tokens.validAccess(pair.access()));
    assertTrue(tokens.refresh(pair.refresh()).isEmpty());
    assertThrows(CallerFault.class, () -> tokens.issuePair("worker", false));
    var controller =
        new AuthController(
            tokens,
            new AuthProperties(),
            accounts,
            passwords,
            new LoginAttempts(Clock.systemUTC(), 10, Duration.ofMinutes(5)));
    var mvc =
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
            .build();
    assertEquals(
        401,
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/v1/auth/login")
                    .contentType("application/json")
                    .content(
                        new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsString(
                                Map.of(
                                    "handle", "worker", "password", created.temporaryPassword()))))
            .andReturn()
            .getResponse()
            .getStatus());
    service.update("owner", "worker", true, null);
    assertFalse(tokens.validAccess(pair.access()));
    assertTrue(tokens.validAccess(tokens.issuePair("worker", true).access()));
  }

  @Test
  void sessions_and_cursor_audit_survive_restart_and_exclude_credentials() {
    service.create("owner", "worker", false);
    tokens.issuePair("worker", true);
    assertEquals(1, service.sessions("owner", "worker").size());
    service.update("owner", "worker", null, true);
    assertTrue(service.sessions("owner", "worker").isEmpty());
    var page = service.history("owner", "worker", 0, 1);
    assertTrue(page.before() > 0);
    service.update("owner", "worker", null, false);
    var older = service.history("owner", "worker", page.before(), 100);
    assertEquals(1, older.entries().size());
    assertEquals("account.create", older.entries().getFirst().action());
    service.revokeSessions("owner", "worker");
    assertEquals(
        "session.revoke", service.history("owner", "worker", 0, 50).entries().getFirst().action());
    assertThrows(CallerFault.class, () -> service.history("owner", "", 0, 101));
  }

  @Test
  void audit_failure_rolls_back_account_and_revocation() {
    service.create("owner", "worker", false);
    var pair = tokens.issuePair("worker", false);
    jdbc.execute(
        "ALTER TABLE admin_audit ADD CONSTRAINT test_reject_update CHECK(action <> 'account.update')");
    try {
      assertThrows(
          org.springframework.dao.DataIntegrityViolationException.class,
          () -> service.update("owner", "worker", false, null));
      assertTrue(accounts.isEnabled("worker"));
      assertTrue(tokens.validAccess(pair.access()));
      assertEquals(1, service.history("owner", "", 0, 50).entries().size());
    } finally {
      jdbc.execute("ALTER TABLE admin_audit DROP CONSTRAINT test_reject_update");
    }
  }

  @Test
  void frames_require_admin_and_reject_malformed_flags() {
    service.create("owner", "worker", false);
    var frames = new AdminFrames(accounts);
    frames.useAdministration(service);
    assertThrows(
        CallerFault.class,
        () ->
            frames
                .frames()
                .get(FrameTypes.ADMIN_ACCOUNTS)
                .handle(Map.of(), new Asking("session", "worker")));
    assertThrows(
        CallerFault.class,
        () ->
            frames
                .frames()
                .get(FrameTypes.ADMIN_ACCOUNT_UPDATE)
                .handle(
                    Map.of("handle", "worker", "enabled", "false"),
                    new Asking("session", "owner")));
  }
}
