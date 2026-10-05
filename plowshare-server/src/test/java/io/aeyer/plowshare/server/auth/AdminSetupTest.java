package io.aeyer.plowshare.server.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.api.ProjectController;
import io.aeyer.plowshare.server.archive.JdbcProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import io.aeyer.plowshare.server.ws.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * Real database and filter: setup consumption, races, restart persistence, and project authority.
 */
@Tag("full-db")
@Testcontainers
class AdminSetupTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static JdbcTemplate jdbc;
  static final ObjectMapper JSON = new ObjectMapper();
  @TempDir Path tmp;
  AdminStore accounts;
  TokenStore tokens;
  PasswordHasher hasher;
  MockMvc mvc;
  String temporary;
  ProjectStore projects;

  @BeforeAll
  static void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
  }

  @BeforeEach
  void reset() {
    jdbc.execute("TRUNCATE admins, projects CASCADE");
    accounts = new AdminStore(jdbc);
    hasher = new PasswordHasher();
    var auth = new AuthProperties();
    auth.setFirstRunSetup(true);
    tokens =
        new TokenStore(
            Clock.systemUTC(),
            auth.getAccessLifetime(),
            auth.getRefreshLifetime(),
            auth.getTicketLifetime());
    var attempts = new LoginAttempts(Clock.systemUTC(), 20, auth.getLoginLockout());
    projects =
        new ProjectStore(jdbc, tmp.resolve("config"), tmp.resolve("sampling"), null, null, null);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new AuthController(tokens, auth, accounts, hasher, attempts),
                new ProjectController(projects, new PresenceRegistry()))
            .addFilters(new AuthFilter(tokens, auth).withAccounts(accounts))
            .build();
    temporary = Tokens.mint();
    assertTrue(accounts.bootstrap(hasher.hash(temporary.toCharArray())));
  }

  MockHttpServletResponse postJson(String path, Object body, String access) throws Exception {
    var request = post(path).contentType("application/json").content(JSON.writeValueAsString(body));
    if (access != null) request.header("Authorization", "Bearer " + access);
    return mvc.perform(request).andReturn().getResponse();
  }

  String login(String handle, String password) throws Exception {
    var response =
        mvc.perform(
                post("/v1/auth/login")
                    .header("X-Plowshare-Token-Delivery", "body")
                    .contentType("application/json")
                    .content(
                        JSON.writeValueAsString(Map.of("handle", handle, "password", password))))
            .andReturn()
            .getResponse();
    assertEquals(200, response.getStatus());
    return JSON.readTree(response.getContentAsString()).get("access").asText();
  }

  Map<String, String> setup(String handle) {
    return Map.of(
        "temporaryPassword", temporary, "handle", handle, "password", "a-long-personal-password");
  }

  @Test
  void setup_only_login_cannot_use_projects_sockets_or_password_promotion() throws Exception {
    String access = login("admin", temporary);
    for (String path : List.of("/v1/projects", "/v1/events", "/v1/files")) {
      assertEquals(
          401,
          mvc.perform(get(path).header("Authorization", "Bearer " + access))
              .andReturn()
              .getResponse()
              .getStatus());
    }
    assertEquals(
        403,
        postJson(
                "/v1/auth/password",
                Map.of("currentPassword", temporary, "newPassword", "a-long-personal-password"),
                access)
            .getStatus());
    assertFalse(accounts.isServerAdmin("admin"));
    assertTrue(accounts.byHandle("admin").orElseThrow().bootstrap());
  }

  @Test
  void setup_consumes_temporary_credentials_and_admin_can_add_a_server_project() throws Exception {
    String access = login("admin", temporary);
    assertEquals(204, postJson("/v1/auth/setup", setup("owner"), access).getStatus());
    assertTrue(accounts.byHandle("admin").isEmpty());
    assertTrue(accounts.isServerAdmin("owner"));
    assertFalse(accounts.byHandle("owner").orElseThrow().mustChangePassword());
    assertEquals(401, postJson("/v1/auth/setup", setup("another"), access).getStatus());
    String permanent = login("owner", "a-long-personal-password");
    assertEquals(
        200,
        postJson("/v1/projects", Map.of("name", "example", "workspace", tmp.toString()), permanent)
            .getStatus());
    assertEquals(List.of("owner"), new JdbcProjectMembers(jdbc).members("example"));
    assertFalse(new AdminStore(jdbc).bootstrap(hasher.hash(Tokens.mint().toCharArray())));
    assertTrue(new AdminStore(jdbc).isServerAdmin("owner"));
  }

  @Test
  void pending_restart_rotates_password_and_invalid_setup_preserves_the_temporary_account()
      throws Exception {
    String access = login("admin", temporary);
    assertEquals(
        400,
        postJson(
                "/v1/auth/setup",
                Map.of(
                    "temporaryPassword", temporary, "handle", "invalid/name", "password", "short"),
                access)
            .getStatus());
    assertTrue(accounts.byHandle("admin").orElseThrow().bootstrap());
    assertTrue(new AdminStore(jdbc).bootstrap(hasher.hash(Tokens.mint().toCharArray())));
    assertEquals(401, postJson("/v1/auth/setup", setup("owner"), access).getStatus());
  }

  @Test
  void concurrent_setup_creates_exactly_one_admin() throws Exception {
    var bootstrap = accounts.byHandle("admin").orElseThrow();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first =
          pool.submit(
              () -> accounts.finishSetup("admin", bootstrap.passwordHash(), "first", "hash-first"));
      var second =
          pool.submit(
              () ->
                  accounts.finishSetup("admin", bootstrap.passwordHash(), "second", "hash-second"));
      assertNotEquals(first.get(), second.get());
      assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM admins", Integer.class));
    }
  }

  @Test
  void regular_accounts_cannot_mutate_projects_over_http_or_websocket() throws Exception {
    accounts.create("member", hasher.hash("a-long-member-password".toCharArray()));
    accounts.changePassword("member", hasher.hash("a-long-member-password".toCharArray()));
    jdbc.update("UPDATE admins SET server_admin = FALSE WHERE handle = 'member'");
    String access = login("member", "a-long-member-password");
    assertEquals(
        403,
        postJson("/v1/projects", Map.of("name", "denied", "workspace", tmp.toString()), access)
            .getStatus());
    assertEquals(
        403,
        postJson("/v1/search/providers", Map.of("baseUrl", "http://provider.invalid"), access)
            .getStatus());
    FrameRouter router =
        new FrameRoutingConfig()
            .frameRouter(
                List.of(
                    new ProjectFrames(
                        projects,
                        new PresenceRegistry(),
                        new JdbcProjectMembers(jdbc),
                        new AuthProperties())));
    router.useAccounts(accounts);
    var outcome =
        router.route(
            "{\"id\":\"one\",\"type\":\"project.define\",\"protocol_version\":\"plowshare-v1\",\"payload\":{\"name\":\"denied\",\"workspace\":\""
                + tmp
                + "\"}}",
            new Asking("session", "member"));
    assertTrue(outcome.said().contains("Only a server administrator"), () -> outcome.toString());
    assertNull(projects.id("denied"));
  }
}
