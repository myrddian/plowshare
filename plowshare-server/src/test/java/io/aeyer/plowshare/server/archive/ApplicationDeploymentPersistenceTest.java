package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ApplicationDeployment.*;
import io.aeyer.plowshare.protocol.FileStoreReference;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.applications.SourceApplicationDeployments;
import io.aeyer.plowshare.server.events.ScheduleDefinitionStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.ConfiguredFileStores;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** SQL constraints, placement/receipt atomicity, row locks and restart reads require PostgreSQL. */
@Tag("full-db")
@Testcontainers
class ApplicationDeploymentPersistenceTest {
  @Container
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static DriverManagerDataSource source;
  static JdbcTemplate jdbc;
  @TempDir Path directory;
  ConfiguredFileStores files;
  ProjectStore projects;
  UnitOfWork work;
  ScheduleDefinitionStore schedules;
  ApplicationDeploymentStore store;
  SourceApplicationDeployments deployments;
  String name;

  @BeforeAll
  static void migrate() {
    source = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    jdbc = new JdbcTemplate(source);
    Flyway.configure().dataSource(source).load().migrate();
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin) VALUES ('deployer','fixture',TRUE)");
  }

  @BeforeEach
  void setup() throws Exception {
    name = "app-" + UUID.randomUUID();
    Path root = Files.createDirectory(directory.resolve("sources"));
    Path config = directory.resolve("filestore.js");
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    Files.writeString(
        config,
        "export default "
            + json.writeValueAsString(
                Map.of(
                    "version",
                    1,
                    "defaultStore",
                    "applications",
                    "fileStores",
                    Map.of(
                        "applications",
                        Map.of(
                            "root",
                            root.toString(),
                            "access",
                            Map.of(
                                "accounts",
                                List.of(Map.of("handle", "deployer", "role", "MANAGER")))))))
            + ";");
    files = new ConfiguredFileStores(config.toString());
    projects =
        new ProjectStore(
            jdbc,
            directory.resolve("server.yml"),
            directory.resolve("sampling"),
            null,
            null,
            null,
            files);
    work = new ArchiveConfig().unitOfWork(new DataSourceTransactionManager(source));
    schedules = mock(ScheduleDefinitionStore.class);
    restart();
  }

  @AfterEach
  void close() {
    files.close();
  }

  void restart() {
    store = new JdbcApplicationDeploymentStore(jdbc, work, projects, schedules);
    deployments =
        new SourceApplicationDeployments(
            store, new JdbcProjectMembers(jdbc), projects, files, (project, root) -> {});
  }

  Deploy request(UUID id, UUID expected, String text) {
    return new Deploy(
        name,
        id,
        expected,
        new FileStoreReference("applications", name),
        List.of(),
        List.of(
            new File(
                "plowshare.json",
                "{\"version\":1,\"name\":\""
                    + name
                    + "\",\"access\":{\"accounts\":[{\"handle\":\"deployer\",\"role\":\"MANAGER\"}]}}"),
            new File("README.md", text)));
  }

  @Test
  void deploy_update_rollback_and_restart_keep_identity_and_conversations() {
    var initial = deployments.deploy("deployer", request(UUID.randomUUID(), null, "one"));
    Long id = projects.id(name);
    var conversations = new ConversationStore(jdbc);
    var conversation = conversations.open(Home.of(name), Budget.of(20));
    var updated =
        deployments.deploy(
            "deployer", request(UUID.randomUUID(), initial.release().revision(), "two"));
    assertEquals(id, projects.id(name));
    assertEquals(
        id,
        jdbc.queryForObject(
            "SELECT project_id FROM conversations WHERE id = ?", Long.class, conversation.id()));
    restart();
    assertEquals(
        updated.release().revision(), deployments.status("deployer", name).activeRevision());
    assertEquals(initial, deployments.receipt("deployer", name, initial.requestId()));
    var rolled =
        deployments.activate(
            "deployer",
            new Activate(
                name,
                UUID.randomUUID(),
                updated.release().revision(),
                initial.release().revision()));
    assertEquals(initial.release(), rolled.release());
    assertEquals(
        initial.release().revision(), deployments.status("deployer", name).activeRevision());
    assertTrue(
        projects
            .find(name)
            .orElseThrow()
            .workspace()
            .endsWith(initial.release().revision().toString()));
    verify(schedules, times(1)).register("deployer", id, "server");
  }

  @Test
  void source_updates_do_not_recreate_removed_membership() {
    var first = deployments.deploy("deployer", request(UUID.randomUUID(), null, "one"));
    Long id = projects.id(name);
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM project_members WHERE project_id = ?", Integer.class, id));
    jdbc.update("DELETE FROM project_members WHERE project_id = ?", id);
    var second =
        deployments.deploy(
            "deployer", request(UUID.randomUUID(), first.release().revision(), "two"));
    assertEquals(
        second.release().revision(), deployments.status("deployer", name).activeRevision());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM project_members WHERE project_id = ?", Integer.class, id));
  }

  @Test
  void identical_receipt_survives_source_removal_but_uuid_cannot_change_payload() throws Exception {
    var request = request(UUID.randomUUID(), null, "one");
    var committed = deployments.deploy("deployer", request);
    Files.delete(projects.find(name).orElseThrow().workspace().resolve("plowshare.json"));
    restart();
    assertEquals(committed, deployments.deploy("deployer", request));
    assertThrows(
        CallerFault.class,
        () -> deployments.deploy("deployer", request(request.requestId(), null, "changed")));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM application_releases WHERE project_id = ?",
            Integer.class,
            projects.id(name)));
  }

  @Test
  void concurrent_updates_compare_the_reviewed_head_under_the_project_lock() throws Exception {
    var initial = deployments.deploy("deployer", request(UUID.randomUUID(), null, "one"));
    var left = request(UUID.randomUUID(), initial.release().revision(), "left");
    var right = request(UUID.randomUUID(), initial.release().revision(), "right");
    var barrier = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      List<Future<Boolean>> results = new ArrayList<>();
      for (var request : List.of(left, right))
        results.add(
            pool.submit(
                () -> {
                  barrier.await(10, TimeUnit.SECONDS);
                  try {
                    deployments.deploy("deployer", request);
                    return true;
                  } catch (CallerFault refused) {
                    return false;
                  }
                }));
      int succeeded = 0;
      for (var result : results) if (result.get(20, TimeUnit.SECONDS)) succeeded++;
      assertEquals(1, succeeded);
    }
    assertEquals(2, deployments.status("deployer", name).releases().size());
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM application_deployment_receipts WHERE project_id = ?",
            Integer.class,
            projects.id(name)));
  }

  @Test
  void failed_schedule_enrollment_rolls_back_project_placement_and_receipt() {
    doThrow(new CallerFault("Enrollment failed"))
        .when(schedules)
        .register(eq("deployer"), anyLong(), eq("server"));
    UUID id = UUID.randomUUID();
    assertThrows(CallerFault.class, () -> deployments.deploy("deployer", request(id, null, "one")));
    assertTrue(projects.find(name).isEmpty());
    assertNull(deployments.status("deployer", name).activeRevision());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM application_deployment_receipts WHERE request_id = ?",
            Integer.class,
            id));
  }
}
