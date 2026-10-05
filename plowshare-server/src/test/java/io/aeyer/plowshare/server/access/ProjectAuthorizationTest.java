package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Tag("full-db")
@Testcontainers
class ProjectAuthorizationTest {
  @Container
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static JdbcTemplate jdbc;
  ProjectMembers members;
  ProjectAuthorization access;

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
        .load()
        .migrate();
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
  }

  @BeforeEach
  void setup() {
    jdbc.execute("TRUNCATE projects,admins CASCADE");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin,must_change_password) VALUES ('admin','hash',true,false),('manager','hash',false,false),('contributor','hash',false,false),('viewer','hash',false,false),('stranger','hash',false,false)");
    project("integration");
    project("other");
    members = new JdbcProjectMembers(jdbc);
    access =
        new ProjectAuthorization(
            new io.aeyer.plowshare.server.access.JdbcResourceScopeRepository(jdbc),
            members,
            new AdminStore(jdbc));
    members.assign("integration", "manager", ProjectRole.MANAGER, "admin", true);
    members.assign("integration", "contributor", ProjectRole.CONTRIBUTOR, "manager", true);
    members.assign("integration", "viewer", ProjectRole.VIEWER, "manager", true);
  }

  Long project(String name) {
    jdbc.update("INSERT INTO projects(name) VALUES (?) ON CONFLICT DO NOTHING", name);
    return jdbc.queryForObject("SELECT id FROM projects WHERE name=?", Long.class, name);
  }

  @Test
  void role_matrix_enforces_read_work_and_manage() {
    for (String op :
        List.of(
            "conversation.list",
            "memory.recall",
            "agent.list",
            "board.topics",
            "information.list",
            "project.access",
            "proposal.list")) {
      for (String account : List.of("admin", "manager", "contributor", "viewer"))
        assertTrue(
            access.allowed(
                op,
                io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                    op, Map.of("project", "integration")),
                account),
            op + account);
      assertFalse(
          access.allowed(
              op,
              io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                  op, Map.of("project", "integration")),
              "stranger"),
          op);
    }
    for (String op :
        List.of(
            "agent.run",
            "conversation.open",
            "memory.write",
            "board.post",
            "information.upload",
            "information.evidence.record",
            "information.tags",
            "union.hidden",
            "memory.digest",
            "memory.navigate")) {
      for (String account : List.of("admin", "manager", "contributor"))
        assertTrue(
            access.allowed(
                op,
                io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                    op, Map.of("project", "integration")),
                account),
            op + account);
      assertFalse(
          access.allowed(
              op,
              io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                  op, Map.of("project", "integration")),
              "viewer"),
          op);
    }
    for (String op :
        List.of(
            "agent.define", "project.member.add", "project.member.remove", "project.member.role")) {
      assertTrue(
          access.allowed(
              op,
              io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                  op, Map.of("project", "integration")),
              "manager"));
      for (String account : List.of("viewer", "contributor", "stranger"))
        assertFalse(
            access.allowed(
                op,
                io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                    op, Map.of("project", "integration")),
                account),
            op + account);
    }
    for (String op :
        List.of(
            "project.create",
            "project.workspace",
            "admin.accounts",
            "provider.deregister",
            "event.fire",
            "schedule.define"))
      assertFalse(
          access.allowed(
              op,
              io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                  op, Map.of("project", "integration")),
              "manager"),
          op);
  }

  @Test
  void every_admin_operation_checks_live_server_authority_before_its_handler_runs()
      throws Exception {
    var handled = new java.util.concurrent.atomic.AtomicInteger();
    Map<String, io.aeyer.plowshare.server.ws.FrameHandler> handlers = new HashMap<>();
    for (var field : io.aeyer.plowshare.server.ws.FrameTypes.class.getFields()) {
      if (field.getType() != String.class) continue;
      String operation = (String) field.get(null);
      if (operation.startsWith("admin.") && !operation.equals("admin.status"))
        handlers.put(
            operation,
            (payload, asking) -> {
              handled.incrementAndGet();
              return io.aeyer.plowshare.protocol.frames.Outcome.ok(Map.of());
            });
    }
    assertTrue(handlers.containsKey("admin.pricing.set"));
    var router = new io.aeyer.plowshare.server.ws.FrameRouter(handlers);
    router.useAuthorization(access);
    router.useAccounts(new AdminStore(jdbc));
    for (String operation : handlers.keySet()) {
      var envelope =
          new io.aeyer.plowshare.protocol.frames.Envelope(
              "test",
              operation,
              io.aeyer.plowshare.protocol.frames.Envelope.CURRENT_VERSION,
              Map.of());
      for (String account :
          List.of(
              "manager",
              "contributor",
              "viewer",
              "stranger",
              "missing",
              "@service/00000000-0000-0000-0000-000000000001")) {
        assertNotEquals(
            io.aeyer.plowshare.protocol.frames.Code.OK,
            router
                .route(envelope, new io.aeyer.plowshare.server.ws.Asking("session", account))
                .code(),
            operation + account);
      }
      assertEquals(0, handled.get());
      assertEquals(
          io.aeyer.plowshare.protocol.frames.Code.OK,
          router
              .route(envelope, new io.aeyer.plowshare.server.ws.Asking("session", "admin"))
              .code(),
          operation);
      handled.set(0);
    }
    jdbc.update("UPDATE admins SET server_admin=false WHERE handle='admin'");
    for (String operation : handlers.keySet()) {
      var envelope =
          new io.aeyer.plowshare.protocol.frames.Envelope(
              "test",
              operation,
              io.aeyer.plowshare.protocol.frames.Envelope.CURRENT_VERSION,
              Map.of());
      assertNotEquals(
          io.aeyer.plowshare.protocol.frames.Code.OK,
          router
              .route(envelope, new io.aeyer.plowshare.server.ws.Asking("session", "admin"))
              .code(),
          operation);
    }
    assertEquals(0, handled.get());
  }

  @Test
  void personal_spaces_are_owner_only_even_for_server_administrators() {
    String personal = PersonalSpaces.name("viewer");
    Long id = project(personal);
    jdbc.update("UPDATE projects SET personal_owner='viewer' WHERE id=?", id);
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES (?,'viewer','MANAGER')", id);
    assertTrue(
        access.allowed(
            "agent.run",
            io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                "agent.run", Map.of("project", personal)),
            "viewer"));
    assertFalse(
        access.allowed(
            "conversation.list",
            io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                "conversation.list", Map.of("project", personal)),
            "admin"));
    assertFalse(
        access.allowed(
            "conversation.list",
            io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                "conversation.list", Map.of("project", personal)),
            "manager"));
    assertThrows(
        ArchiveRefusedException.class,
        () -> members.assign(personal, "contributor", ProjectRole.VIEWER, "viewer", true));
  }

  @Test
  void stored_resource_scope_cannot_be_overridden_by_payload_project() {
    Long id = project("integration");
    new ConversationStore(jdbc, java.time.Instant::now, () -> "cnv_rbac")
        .open(Home.of("integration"), Budget.of(10));
    jdbc.update(
        "INSERT INTO jobs(id,project_id,agent,started_at) VALUES ('job_rbac',?,'bot',now())", id);
    assertFalse(
        access.allowed(
            "conversation.resume",
            io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                "conversation.resume", Map.of("conversation", "cnv_rbac", "project", "other")),
            "viewer"));
    assertFalse(
        access.allowed(
            "conversation.turns",
            io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                "conversation.turns", Map.of("conversation", "cnv_rbac")),
            "stranger"));
    assertTrue(
        access.allowed(
            "conversation.turns",
            io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                "conversation.turns", Map.of("conversation", "cnv_rbac")),
            "viewer"));
    assertFalse(
        access.allowed(
            "job.cancel",
            io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                "job.cancel", Map.of("job", "job_rbac")),
            "viewer"));
    assertTrue(
        access.allowed(
            "job.status",
            io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                "job.status", Map.of("job", "job_rbac")),
            "viewer"));
    members.assign("integration", "contributor", ProjectRole.VIEWER, "manager", false);
    assertFalse(
        access.allowed(
            "agent.run",
            io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                "agent.run", Map.of("conversation", "cnv_rbac")),
            "contributor"));
    members.remove("integration", "viewer", "manager");
    assertFalse(
        access.allowed(
            "job.status",
            io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                "job.status", Map.of("job", "job_rbac")),
            "viewer"));
  }

  @Test
  void managers_manage_grants_and_history_without_becoming_server_administrators() {
    assertEquals(ProjectRole.MANAGER, members.access("integration", "manager").role());
    assertEquals(3, members.access("integration", "manager").history().size());
    assertTrue(members.access("integration", "viewer").history().isEmpty());
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class,
        () -> members.assign("integration", "stranger", ProjectRole.MANAGER, "viewer", true));
    members.assign("integration", "stranger", ProjectRole.VIEWER, "manager", true);
    members.assign("integration", "stranger", ProjectRole.CONTRIBUTOR, "manager", false);
    assertTrue(members.mayWork("integration", "stranger"));
    members.remove("integration", "stranger", "manager");
    assertFalse(members.mayUse("integration", "stranger"));
    assertEquals(6, members.access("integration", "manager").history().size());
  }

  @Test
  void last_manager_and_duplicate_add_are_protected_transactionally() throws Exception {
    int count = members.access("integration", "manager").history().size();
    assertThrows(
        ArchiveRefusedException.class,
        () -> members.assign("integration", "manager", ProjectRole.VIEWER, "manager", false));
    assertThrows(
        ArchiveRefusedException.class, () -> members.remove("integration", "manager", "manager"));
    assertThrows(
        ArchiveRefusedException.class,
        () -> members.assign("integration", "manager", ProjectRole.VIEWER, "admin", true));
    assertEquals(count, members.access("integration", "manager").history().size());
    members.assign("integration", "contributor", ProjectRole.MANAGER, "manager", false);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var latch = new CountDownLatch(1);
      var one =
          pool.submit(
              () -> {
                latch.await();
                try {
                  members.remove("integration", "manager", "manager");
                  return true;
                } catch (ArchiveRefusedException denied) {
                  return false;
                }
              });
      var two =
          pool.submit(
              () -> {
                latch.await();
                try {
                  members.remove("integration", "contributor", "contributor");
                  return true;
                } catch (ArchiveRefusedException denied) {
                  return false;
                }
              });
      latch.countDown();
      assertNotEquals(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS));
    }
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM project_members WHERE role='MANAGER'", Integer.class));
  }

  @Test
  void information_sql_admission_matches_roles_and_rechecks_disabled_accounts() {
    Long integration = project("integration");
    for (String account : List.of("admin", "manager", "contributor", "viewer", "stranger"))
      assertEquals(
          members.mayUse("integration", account),
          jdbc.queryForObject(
              "SELECT information_project_readable(?,?)", Boolean.class, integration, account));
    var information = new io.aeyer.plowshare.server.information.InformationAccess(members);
    var selection =
        io.aeyer.plowshare.server.information.InformationContext.Selection.project("integration");
    var viewer = information.resolve("viewer", selection);
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class, () -> information.requireWork(viewer));
    information.requireWork(information.resolve("contributor", selection));
    jdbc.update("UPDATE admins SET enabled=false WHERE handle='viewer'");
    assertFalse(
        jdbc.queryForObject(
            "SELECT information_project_readable(?,?)", Boolean.class, integration, "viewer"));
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class,
        () -> information.requireSelection(viewer));
  }

  @Test
  void disabled_accounts_have_no_access_even_with_an_explicit_grant() {
    jdbc.update("UPDATE admins SET enabled=false WHERE handle='viewer'");
    assertFalse(members.mayUse("integration", "viewer"));
    assertFalse(members.isMember("integration", "viewer"));
    assertFalse(
        access.allowed(
            "conversation.list",
            io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                "conversation.list", Map.of("project", "integration")),
            "viewer"));
  }

  @Test
  void listings_recheck_current_grants_and_keep_personal_work_private() {
    var own =
        new io.aeyer.plowshare.server.events.TriggerRecord(
            "own", "tick", "integration", null, "bot", "work", null, null, 1, false, "viewer");
    var foreign =
        new io.aeyer.plowshare.server.events.TriggerRecord(
            "foreign", "tick", "integration", null, "bot", "work", null, null, 1, false, "manager");
    var outcome = io.aeyer.plowshare.protocol.frames.Outcome.ok(List.of(own, foreign));
    assertEquals(
        List.of(own),
        new io.aeyer.plowshare.server.ws.ProjectListingFilter(access)
            .filter("trigger.list", outcome, "viewer")
            .payload());
    members.remove("integration", "viewer", "manager");
    assertEquals(
        List.of(),
        new io.aeyer.plowshare.server.ws.ProjectListingFilter(access)
            .filter("trigger.list", outcome, "viewer")
            .payload());

    var visible = org.mockito.Mockito.mock(io.aeyer.plowshare.protocol.Orchestration.RunView.class);
    org.mockito.Mockito.when(visible.project()).thenReturn("integration");
    var privateWork =
        org.mockito.Mockito.mock(io.aeyer.plowshare.protocol.Orchestration.RunView.class);
    org.mockito.Mockito.when(privateWork.project()).thenReturn(PersonalSpaces.name("viewer"));
    var runs =
        io.aeyer.plowshare.protocol.frames.Outcome.ok(
            new io.aeyer.plowshare.protocol.Orchestration.Listed(List.of(visible, privateWork)));
    var filtered =
        (io.aeyer.plowshare.protocol.Orchestration.Listed)
            new io.aeyer.plowshare.server.ws.ProjectListingFilter(access)
                .filter("orchestration.list", runs, "admin")
                .payload();
    assertEquals(List.of(visible), filtered.orchestrations());
  }

  @Test
  void router_refuses_viewer_work_before_invoking_a_handler() {
    var executions = new java.util.concurrent.atomic.AtomicInteger();
    var router =
        new io.aeyer.plowshare.server.ws.FrameRouter(
            Map.of(
                "agent.run",
                (payload, asking) -> {
                  executions.incrementAndGet();
                  return io.aeyer.plowshare.protocol.frames.Outcome.ok();
                }));
    router.useAuthorization(access);
    var request =
        new io.aeyer.plowshare.protocol.frames.Envelope(
            "request",
            "agent.run",
            io.aeyer.plowshare.protocol.frames.Envelope.CURRENT_VERSION,
            Map.of("project", "integration"));
    assertEquals(
        io.aeyer.plowshare.protocol.frames.Code.BAD_REQUEST,
        router.route(request, new io.aeyer.plowshare.server.ws.Asking("session", "viewer")).code());
    assertEquals(0, executions.get());
    assertEquals(
        io.aeyer.plowshare.protocol.frames.Code.OK,
        router
            .route(request, new io.aeyer.plowshare.server.ws.Asking("session", "contributor"))
            .code());
    assertEquals(1, executions.get());
  }

  @Test
  void sql_information_admission_does_not_grant_admins_other_personal_spaces() {
    String personal = PersonalSpaces.name("viewer");
    Long id = project(personal);
    jdbc.update("UPDATE projects SET personal_owner='viewer' WHERE id=?", id);
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) VALUES (?,'viewer','MANAGER')", id);
    assertTrue(
        jdbc.queryForObject(
            "SELECT information_project_readable(?,?)", Boolean.class, id, "viewer"));
    assertFalse(
        jdbc.queryForObject(
            "SELECT information_project_readable(?,?)", Boolean.class, id, "admin"));
  }

  @Test
  void regular_accounts_can_stop_their_legacy_schedules_but_cannot_resume_global_emission() {
    assertTrue(
        access.allowed(
            "schedule.pause",
            io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                "schedule.pause", Map.of("schedule", "old", "paused", true)),
            "manager"));
    assertFalse(
        access.allowed(
            "schedule.pause",
            io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                "schedule.pause", Map.of("schedule", "old", "paused", false)),
            "manager"));
    assertTrue(
        access.allowed(
            "schedule.pause",
            io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(
                "schedule.pause", Map.of("schedule", "old", "paused", false)),
            "admin"));
  }
}
