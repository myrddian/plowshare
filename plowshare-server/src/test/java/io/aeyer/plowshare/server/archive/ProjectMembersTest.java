package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.JdbcProjectCreatorGrants;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import io.aeyer.plowshare.server.session.SessionRegistry;
import io.aeyer.plowshare.server.ws.EventChannelHandler;
import io.aeyer.plowshare.server.ws.FileChannelHandler;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("full-db")
@Testcontainers
class ProjectMembersTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private ProjectMembers members;
  @TempDir Path tmp;

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .load()
        .migrate();
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
  }

  @BeforeEach
  void reset() {
    io.aeyer.plowshare.server.testing.AuthorityFixtures.truncate(
        jdbc, "TRUNCATE projects, admins CASCADE");
    jdbc.update(
        "INSERT INTO admins (handle, password_hash, server_admin) VALUES ('alice', 'hash', TRUE), ('bob', 'hash', TRUE)");
    ProjectIds.toWrite(jdbc, Home.of("ledger"));
    members = new JdbcProjectMembers(jdbc);
  }

  @Test
  void regular_accounts_cannot_claim_projects_and_admin_access_is_explicit() {
    jdbc.update("UPDATE admins SET server_admin = FALSE WHERE account_kind='USER'");
    assertFalse(members.mayUse("ledger", null));
    assertFalse(members.mayUse("ledger", "alice"));
    assertFalse(members.mayUse("missing", "alice"));
    assertTrue(members.members("ledger").isEmpty());
    members.add("ledger", "alice");
    assertTrue(members.mayUse("ledger", "alice"));
    assertFalse(members.mayUse("ledger", "bob"));
    members.remove("ledger", "alice");
    assertFalse(members.mayUse("ledger", "alice"));
    jdbc.update("UPDATE admins SET server_admin = TRUE WHERE handle = 'alice'");
    assertFalse(members.mayUse("ledger", "alice"));
    assertTrue(members.mayAdminister("ledger", "alice"));
    assertThrows(ArchiveRefusedException.class, () -> members.add("ledger", "unknown"));
  }

  @Test
  void concurrent_regular_accounts_cannot_claim_an_unowned_project() throws Exception {
    jdbc.update("UPDATE admins SET server_admin = FALSE WHERE account_kind='USER'");
    try (var pool = Executors.newFixedThreadPool(2)) {
      CountDownLatch start = new CountDownLatch(1);
      var alice =
          pool.submit(
              () -> {
                start.await();
                return members.mayUse("ledger", "alice");
              });
      var bob =
          pool.submit(
              () -> {
                start.await();
                return members.mayUse("ledger", "bob");
              });
      start.countDown();
      assertFalse(alice.get(10, TimeUnit.SECONDS));
      assertFalse(bob.get(10, TimeUnit.SECONDS));
      assertTrue(members.members("ledger").isEmpty());
    }
  }

  private ProjectStore store() {
    return new ProjectStore(jdbc, tmp.resolve("config"), tmp.resolve("sampling"), null, null, null);
  }

  /** A regular account holding the creator role, granted by the server admin alice. */
  private void creator(String handle) {
    jdbc.update(
        "INSERT INTO admins (handle, password_hash, must_change_password, server_admin)"
            + " VALUES (?, 'hash', FALSE, FALSE) ON CONFLICT DO NOTHING",
        handle);
    assertTrue(new JdbcProjectCreatorGrants(jdbc).grant("alice", handle));
  }

  private long rows(String name) {
    return jdbc.queryForObject("SELECT count(*) FROM projects WHERE name = ?", Long.class, name);
  }

  @Test
  void a_file_channel_creates_an_unregistered_project_for_a_creator() throws Exception {
    creator("carol");
    var presences = new PresenceRegistry();
    ProjectStore projects = store();
    var channel =
        new FileChannelHandler(
            new SessionRegistry(),
            presences,
            projects::rootOn,
            members,
            new JdbcProjectCreatorGrants(jdbc)::mayCreate,
            projects);
    var socket = mock(WebSocketSession.class);
    when(socket.getUri())
        .thenReturn(
            URI.create(
                "ws://localhost/v1/files?session=desk&project=sundial&machine=laptop&root=/repo"));
    var attributes = new HashMap<String, Object>();
    attributes.put(EventChannelHandler.HANDLE, "carol");
    when(socket.getAttributes()).thenReturn(attributes);
    channel.afterConnectionEstablished(socket);
    verify(socket, never()).close(any());
    assertEquals(List.of("carol"), members.members("sundial"));
    assertEquals("desk", presences.serving("sundial").orElseThrow().session());
    assertEquals("laptop", projects.rootedElsewhere("sundial").orElseThrow());
  }

  @Test
  void creator_becomes_sole_manager() {
    creator("carol");
    store().rootOn("sundial", "laptop", "/repo", "carol");
    assertEquals(List.of("carol"), members.members("sundial"));
    assertEquals(ProjectRole.MANAGER, members.role("sundial", "carol").orElseThrow());
    assertEquals("laptop", store().rootedElsewhere("sundial").orElseThrow());
  }

  @ParameterizedTest
  @ValueSource(strings = {" sundial", "sundial ", "\u2003sundial", "sundial\u2003"})
  void file_channel_refuses_edge_whitespace_without_creating_a_normalized_row(String name)
      throws Exception {
    creator("carol");
    var presences = new PresenceRegistry();
    ProjectStore projects = store();
    var channel =
        new FileChannelHandler(
            new SessionRegistry(),
            presences,
            projects::rootOn,
            members,
            new JdbcProjectCreatorGrants(jdbc)::mayCreate,
            projects);
    var socket = mock(WebSocketSession.class);
    when(socket.getUri())
        .thenReturn(
            URI.create(
                "ws://localhost/v1/files?session=desk&project="
                    + URLEncoder.encode(name, StandardCharsets.UTF_8)
                    + "&machine=laptop&root=/repo&ready=1"));
    var attributes = new HashMap<String, Object>();
    attributes.put(EventChannelHandler.HANDLE, "carol");
    when(socket.getAttributes()).thenReturn(attributes);

    channel.afterConnectionEstablished(socket);

    var status = ArgumentCaptor.forClass(CloseStatus.class);
    verify(socket).close(status.capture());
    assertEquals(1003, status.getValue().getCode());
    assertEquals(0, rows(name));
    assertEquals(0, rows(name.strip()));
    assertTrue(members.members(name.strip()).isEmpty());
    assertTrue(presences.serving(name.strip()).isEmpty());
    assertFalse(channel.isConnected("desk"));
  }

  @Test
  void self_replacement_returns_a_domain_refusal_and_preserves_membership_and_audit() {
    creator("carol");
    store().rootOn("sundial", "laptop", "/repo", "carol");
    var grants =
        jdbc.queryForList(
            "SELECT to_jsonb(g)::text FROM authority_grants g ORDER BY id", String.class);
    var audit =
        jdbc.queryForList(
            "SELECT to_jsonb(a)::text FROM authority_grant_audit a ORDER BY id", String.class);

    var refused =
        assertThrows(
            ArchiveRefusedException.class,
            () -> members.assign("sundial", "carol", ProjectRole.MANAGER, "carol", false));

    assertEquals(
        "Replacing your issuing membership requires another manager", refused.getMessage());
    assertEquals(List.of("carol"), members.members("sundial"));
    assertEquals(ProjectRole.MANAGER, members.role("sundial", "carol").orElseThrow());
    assertEquals(
        grants,
        jdbc.queryForList(
            "SELECT to_jsonb(g)::text FROM authority_grants g ORDER BY id", String.class));
    assertEquals(
        audit,
        jdbc.queryForList(
            "SELECT to_jsonb(a)::text FROM authority_grant_audit a ORDER BY id", String.class));
  }

  @Test
  void second_concurrent_creator_is_refused() throws Exception {
    creator("carol");
    creator("dave");
    ProjectStore projects = store();
    try (var pool = Executors.newFixedThreadPool(2)) {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Void>> attempts =
          List.of("carol", "dave").stream()
              .map(
                  handle ->
                      pool.submit(
                          (Callable<Void>)
                              () -> {
                                start.await();
                                projects.rootOn("sundial", "laptop", "/repo", handle);
                                return null;
                              }))
              .toList();
      start.countDown();
      int succeeded = 0;
      int refused = 0;
      for (Future<Void> attempt : attempts) {
        try {
          attempt.get(10, TimeUnit.SECONDS);
          succeeded++;
        } catch (ExecutionException failed) {
          assertInstanceOf(ArchiveRefusedException.class, failed.getCause());
          refused++;
        }
      }
      assertEquals(1, succeeded);
      assertEquals(1, refused);
    }
    assertEquals(1, members.members("sundial").size());
    assertEquals(1, rows("sundial"));
  }

  @Test
  void member_reroots_after_creator_role_revoked() {
    creator("carol");
    ProjectStore projects = store();
    projects.rootOn("sundial", "laptop", "/repo", "carol");
    assertTrue(new JdbcProjectCreatorGrants(jdbc).revoke("alice", "carol"));
    projects.rootOn("sundial", "laptop", "/elsewhere", "carol");
    assertEquals(List.of("carol"), members.members("sundial"));
    assertEquals("laptop", projects.rootedElsewhere("sundial").orElseThrow());
  }

  @Test
  void non_member_creator_cannot_attach_a_client_rooted_project() {
    creator("carol");
    creator("dave");
    ProjectStore projects = store();
    projects.rootOn("sundial", "laptop", "/repo", "carol");
    assertThrows(
        ArchiveRefusedException.class, () -> projects.rootOn("sundial", "laptop", "/repo", "dave"));
    assertEquals(List.of("carol"), members.members("sundial"));
  }

  @Test
  void creator_does_not_adopt_a_racing_orphan_row() {
    creator("carol");
    ProjectStore projects = store();
    // rootOn read "no row" for 'sundial' and chose creation; before creation runs, another writer
    // (a memory write, say) registers the name. Entering the creation branch directly with that
    // row in place is the race, made deterministic.
    Long racing = ProjectIds.toWrite(jdbc, Home.of("sundial"));
    ArchiveRefusedException refused =
        assertThrows(
            ArchiveRefusedException.class,
            () -> projects.createByRooting("sundial", "laptop", "/repo", "carol"));
    assertEquals("A project with this name was just created; try again", refused.getMessage());
    assertTrue(members.members("sundial").isEmpty());
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT count(*) FROM project_members WHERE project_id = ?", Long.class, racing));
    // The racing writer's row is untouched: same id, still claimed by no machine.
    assertEquals(racing, projects.id("sundial"));
    assertEquals(1, rows("sundial"));
    assertTrue(projects.rootedElsewhere("sundial").isEmpty());
    assertNull(
        jdbc.queryForObject("SELECT machine FROM projects WHERE id = ?", String.class, racing));
  }

  @Test
  void orphan_row_stays_admin_only() {
    creator("carol");
    ProjectStore projects = store();
    // "ledger" is registered by reset() through ProjectIds.toWrite and has no members.
    assertThrows(
        ArchiveRefusedException.class, () -> projects.rootOn("ledger", "laptop", "/repo", "carol"));
    assertTrue(members.members("ledger").isEmpty());
    projects.rootOn("ledger", "laptop", "/repo", "alice");
    assertEquals(List.of("alice"), members.members("ledger"));
  }

  @Test
  void creator_cannot_create_reserved_or_unnormalised_names() {
    creator("carol");
    ProjectStore projects = store();
    assertThrows(
        ValidationException.class, () -> projects.rootOn(" sundial", "laptop", "/repo", "carol"));
    assertThrows(
        ArchiveRefusedException.class,
        () -> projects.rootOn("personal:x", "laptop", "/repo", "carol"));
    assertThrows(
        ArchiveRefusedException.class,
        () -> projects.rootOn("client:x", "laptop", "/repo", "carol"));
    assertThrows(
        ArchiveRefusedException.class,
        () -> projects.rootOn("PERSONAL:x", "laptop", "/repo", "carol"));
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT count(*) FROM projects WHERE name IN (' sundial', 'personal:x', 'client:x', 'PERSONAL:x')",
            Long.class));
  }

  @Test
  void creators_are_members_and_failed_writes_roll_back_membership_and_project() {
    ProjectStore projects = store();
    projects.define("created", tmp, List.of(), "alice");
    creator("bob");
    projects.rootOn("remote", "laptop", "/repo", "bob");
    assertEquals(List.of("alice"), members.members("created"));
    assertEquals(List.of("bob"), members.members("remote"));
    assertThrows(
        ArchiveRefusedException.class, () -> projects.rootOn("bad", "laptop", "/repo", "unknown"));
    assertNull(projects.id("bad"));
    assertThrows(
        ValidationException.class,
        () -> projects.define("invalid", tmp.resolve("missing"), List.of(), "alice"));
    assertNull(projects.id("invalid"));
  }

  @Test
  void a_file_channel_roots_a_project_after_an_explicit_manager_grant() throws Exception {
    members.assign("ledger", "alice", ProjectRole.MANAGER, "alice", true);
    var sessions = new io.aeyer.plowshare.server.session.SessionRegistry();
    var presences = new io.aeyer.plowshare.server.session.PresenceRegistry();
    ProjectStore projects =
        new ProjectStore(jdbc, tmp.resolve("config"), tmp.resolve("sampling"), null, null, null);
    var channel =
        new io.aeyer.plowshare.server.ws.FileChannelHandler(
            sessions,
            presences,
            projects::rootOn,
            members,
            new JdbcProjectCreatorGrants(jdbc)::mayCreate,
            projects);
    var socket = org.mockito.Mockito.mock(org.springframework.web.socket.WebSocketSession.class);
    org.mockito.Mockito.when(socket.getUri())
        .thenReturn(
            java.net.URI.create(
                "ws://localhost/v1/files?session=desk&project=ledger&machine=laptop&root=/repo"));
    var attributes = new java.util.HashMap<String, Object>();
    attributes.put(io.aeyer.plowshare.server.ws.EventChannelHandler.HANDLE, "alice");
    org.mockito.Mockito.when(socket.getAttributes()).thenReturn(attributes);
    channel.afterConnectionEstablished(socket);
    assertEquals(List.of("alice"), members.members("ledger"));
    assertEquals("desk", presences.serving("ledger").orElseThrow().session());
    assertEquals("laptop", projects.rootedElsewhere("ledger").orElseThrow());
    jdbc.update("UPDATE admins SET server_admin = FALSE WHERE handle = 'bob'");
    assertFalse(members.mayUse("ledger", "bob"));
  }

  @Test
  void migration_backfills_every_existing_project() {
    jdbc.execute("CREATE SCHEMA backfill");
    var before =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas("backfill")
            .defaultSchema("backfill")
            .target("76")
            .load();
    before.migrate();
    jdbc.update("INSERT INTO backfill.admins (handle, password_hash) VALUES ('alice', 'hash')");
    jdbc.update("INSERT INTO backfill.projects (name) VALUES ('one'), ('two')");
    jdbc.update(
        "INSERT INTO backfill.conversations(id,origin,created_at,budget_total,budget_spent,owner_handle)"
            + " VALUES ('cnv_owned','turn',now(),2,0,'alice'),('cnv_ownerless','turn',now(),2,0,NULL)");
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas("backfill")
        .defaultSchema("backfill")
        .load()
        .migrate();
    assertEquals(
        3,
        jdbc.queryForObject(
            "SELECT count(*) FROM backfill.project_members WHERE handle = 'alice'", Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM backfill.projects WHERE personal_owner = 'alice'",
            Integer.class));
    assertEquals(
        jdbc.queryForObject(
            "SELECT id FROM backfill.projects WHERE personal_owner='alice'", Long.class),
        jdbc.queryForObject(
            "SELECT project_id FROM backfill.conversations WHERE id='cnv_owned'", Long.class));
    assertNull(
        jdbc.queryForObject(
            "SELECT project_id FROM backfill.conversations WHERE id='cnv_ownerless'", Long.class));
  }
}
