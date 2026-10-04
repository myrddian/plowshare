package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.EnvironmentFile;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Window;
import io.aeyer.plowshare.server.agents.AgentsConfig;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.files.*;
import io.aeyer.plowshare.server.images.ImageStore;
import io.aeyer.plowshare.server.session.*;
import io.aeyer.plowshare.server.union.UnionRouting;
import io.aeyer.plowshare.server.union.UnionStore;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class ServerProjectFramesTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static JdbcTemplate jdbc;
  @TempDir Path tmp;
  ProjectStore projects;
  FrameHandler create;

  @BeforeAll
  static void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
  }

  @BeforeEach
  void setup() {
    jdbc.execute("TRUNCATE projects, admins CASCADE");
    new AdminStore(jdbc).create("owner", "fixture-hash");
    new AdminStore(jdbc).create("member", "fixture-hash");
    jdbc.update("UPDATE admins SET server_admin = FALSE WHERE handle = 'member'");
    projects =
        new ProjectStore(jdbc, tmp.resolve("config"), tmp.resolve("sampling"), null, null, null);
    create =
        new ServerProjectFrames(projects, tmp.resolve("workspaces").toString())
            .frames()
            .get(FrameTypes.PROJECT_CREATE);
  }

  void create(Map<String, Object> body) {
    create.handle(body, new Asking("s", "owner"));
  }

  LocalProvider files(String name, Mode mode) {
    return new LocalProvider(projects, Home.of(name), List.of(new Grant(Scope.WORKSPACE, mode)));
  }

  @Test
  void managedCreationProvisionsMarkerAndPersistsIdentityWithoutOverwritingAnExistingProject()
      throws Exception {
    create(Map.of("name", "home-assistant"));
    var row = projects.find("home-assistant").orElseThrow();
    assertEquals("MANAGED", row.type());
    assertEquals(List.of("."), row.writePaths());
    assertEquals(
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree("{\"version\":1,\"name\":\"home-assistant\"}"),
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(Files.readString(row.workspace().resolve(".plowshare/project"))));
    assertTrue(new ProjectMembers(jdbc).isMember("home-assistant", "owner"));
    assertThrows(RuntimeException.class, () -> create(Map.of("name", "home-assistant")));
    assertEquals(row.workspace(), projects.find("home-assistant").orElseThrow().workspace());
    try (var entries = Files.list(tmp.resolve("workspaces"))) {
      assertEquals(1, entries.count());
    }
    assertThrows(
        RuntimeException.class,
        () -> create.handle(Map.of("name", "denied"), new Asking("s", "member")));
    assertNull(projects.id("denied"));
  }

  @Test
  void disjointNeverWritesPipelineMetadataOrBecomesAUnionAndCheckoutDoesNotRelocateServerFiles()
      throws Exception {
    Path root = Files.createDirectory(tmp.resolve("pipeline"));
    create(Map.of("name", "integration", "type", "DISJOINT", "workspace", root.toString()));
    assertFalse(Files.exists(root.resolve(".plowshare")));
    var row = projects.find("integration").orElseThrow();
    assertTrue(row.readOnly());
    assertTrue(new UnionStore(jdbc).find("integration").isEmpty());
    new UnionStore(jdbc).enable("integration", java.time.Instant.now());
    assertNull(
        jdbc.queryForObject(
            "SELECT union_since FROM projects WHERE name = 'integration'", Object.class));
    projects.rootOn("integration", "laptop", "/checkout", "owner");
    assertEquals(root, projects.find("integration").orElseThrow().workspace());
    assertTrue(projects.rootedElsewhere("integration").isEmpty());
    assertThrows(
        WorkspaceRefusedException.class,
        () -> files("integration", Mode.WRITE).write(root.resolve("new"), "denied"));
  }

  @Test
  void writableAreasIntersectAgentGrantsAndRefuseMovesEditsCommandsTraversalAndSymlinkEscapes()
      throws Exception {
    Path root = Files.createDirectory(tmp.resolve("pipeline"));
    Path source = root.resolve("source.yml");
    Files.writeString(source, "pipeline owns this");
    create(
        Map.of(
            "name",
            "integration",
            "type",
            "DISJOINT",
            "workspace",
            root.toString(),
            "writePaths",
            List.of("generated", "reports")));
    LocalProvider writer = files("integration", Mode.WRITE);
    assertEquals("pipeline owns this", writer.read(source, Window.of(0, 10)).lines().get(0));
    Path output = root.resolve("generated/result.txt");
    writer.write(output, "created");
    writer.edit(output, "created", "edited");
    assertEquals("edited", Files.readString(output));
    writer.move(output, root.resolve("reports/result.txt"));
    assertThrows(WorkspaceRefusedException.class, () -> writer.write(source, "no"));
    assertThrows(WorkspaceRefusedException.class, () -> writer.edit(source, "pipeline", "no"));
    assertThrows(WorkspaceRefusedException.class, () -> writer.delete(source));
    assertThrows(
        WorkspaceRefusedException.class,
        () -> writer.move(root.resolve("reports/result.txt"), root.resolve("source-2.yml")));
    assertThrows(
        WorkspaceRefusedException.class,
        () ->
            files("integration", Mode.READ).write(root.resolve("generated/read-grant.txt"), "no"));
    assertThrows(
        WorkspaceRefusedException.class,
        () ->
            writer.run(
                root.resolve("generated"),
                List.of("true"),
                EnvironmentFile.Side.DEFAULT,
                Duration.ofSeconds(1),
                () -> false));
    Path outside = Files.createDirectory(tmp.resolve("outside"));
    Files.createSymbolicLink(root.resolve("generated/escape"), outside);
    assertThrows(
        WorkspaceRefusedException.class,
        () -> writer.write(root.resolve("generated/escape/no"), "no"));
    writer.delete(root.resolve("reports/result.txt"));
    jdbc.update("UPDATE projects SET write_paths = '{}' WHERE name = 'integration'");
    assertThrows(
        WorkspaceRefusedException.class,
        () -> writer.write(root.resolve("generated/revoked"), "no"));
    assertEquals("pipeline owns this", Files.readString(source));
    assertThrows(
        RuntimeException.class,
        () ->
            create(
                Map.of(
                    "name",
                    "bad",
                    "type",
                    "DISJOINT",
                    "workspace",
                    root.toString(),
                    "writePaths",
                    List.of("../outside"))));
    assertNull(projects.id("bad"));
  }

  @Test
  void attachedCheckoutIsUsedOnlyByItsOwnSessionAndBackgroundTasksKeepTheServerWorkspace()
      throws Exception {
    Path root = Files.createDirectory(tmp.resolve("pipeline"));
    create(Map.of("name", "integration", "type", "DISJOINT", "workspace", root.toString()));
    var sessions = mock(SessionRegistry.class);
    var presences = new PresenceRegistry();
    presences.declare(new Presence("checkout", "laptop", "/checkout", "integration"));
    var session = mock(Session.class);
    when(session.has(Role.FILE_PROVIDER)).thenReturn(true);
    when(session.id()).thenReturn("checkout");
    when(sessions.find("checkout")).thenReturn(Optional.of(session));
    var unions = mock(UnionRouting.class);
    when(unions.providers(anyString(), anyList(), any(), any())).thenReturn(Optional.empty());
    var routing =
        new AgentsConfig()
            .runProviders(
                projects,
                mock(SessionChannel.class),
                sessions,
                presences,
                ImageStore.NONE,
                unions,
                new ProjectMembers(jdbc));
    assertInstanceOf(
        LocalProvider.class,
        routing
            .forRun(
                Home.of("integration"),
                List.of(new Grant(Scope.WORKSPACE, Mode.READ)),
                null,
                "owner")
            .get(0));
    var client =
        routing.forRun(
            Home.of("integration"),
            List.of(new Grant(Scope.WORKSPACE, Mode.WRITE)),
            "checkout",
            "owner");
    assertEquals(1, client.size());
    assertInstanceOf(RemoteProvider.class, client.get(0));
  }

  @Test
  void clientDisjointStaysPrivateToOneSessionEvenForTheSameAccountAndAdmin() throws Exception {
    Path checkout = tmp.resolve("pipeline-client");
    Files.createDirectory(checkout);
    String manifest = "{\"version\":1,\"name\":\"Integration\"}";
    Files.writeString(checkout.resolve("plowshare"), manifest);
    var attach = new ClientProjectFrames(projects).frames().get(FrameTypes.PROJECT_ATTACH);
    var body =
        Map.<String, Object>of(
            "name", "Integration", "workspace", checkout.toString(), "machine", "client-host");
    var first =
        (io.aeyer.plowshare.server.api.ProjectView)
            attach.handle(body, new Asking("first-client", "member")).payload();
    var second =
        (io.aeyer.plowshare.server.api.ProjectView)
            attach.handle(body, new Asking("second-client", "member")).payload();
    assertNotEquals(first.name(), second.name());
    assertEquals("Integration", first.displayName());
    assertEquals("DISJOINT", first.type());
    assertEquals(
        List.of(first.name()),
        projects.allForSession("member", "first-client").stream()
            .map(ProjectRecord::name)
            .toList());
    assertEquals(
        List.of(second.name()),
        projects.allForSession("member", "second-client").stream()
            .map(ProjectRecord::name)
            .toList());
    assertTrue(projects.allFor("member").isEmpty());
    assertTrue(projects.allFor("owner").isEmpty());
    assertTrue(projects.allForSession("member", "third-client").isEmpty());
    assertTrue(new UnionStore(jdbc).find(first.name()).isEmpty());
    new UnionStore(jdbc).enable(first.name(), java.time.Instant.now());
    assertTrue(new UnionStore(jdbc).find(first.name()).isEmpty());
    assertThrows(RuntimeException.class, () -> new ProjectMembers(jdbc).add(first.name(), "owner"));
    assertThrows(
        RuntimeException.class,
        () -> ClientProjects.requireOwn(first.name(), "second-client", "member"));
    var router =
        new FrameRouter(
            Map.of(
                "test.probe",
                (payload, asking) -> io.aeyer.plowshare.protocol.frames.Outcome.ok("accepted")));
    assertEquals(
        io.aeyer.plowshare.protocol.frames.Code.OK,
        router
            .route(
                FrameParity.frame("test.probe", "{\"project\":\"" + first.name() + "\"}"),
                new Asking("first-client", "member"))
            .code());
    assertEquals(
        io.aeyer.plowshare.protocol.frames.Code.CONFLICT,
        router
            .route(
                FrameParity.frame("test.probe", "{\"project\":\"" + first.name() + "\"}"),
                new Asking("second-client", "member"))
            .code());

    var conversations = new ConversationStore(jdbc);
    var conversation =
        conversations.open(Home.of(first.name()), io.aeyer.plowshare.server.agents.Budget.of(10));
    router.usePersonalAccess(new io.aeyer.plowshare.server.personal.PersonalAccess(conversations));
    String conversationBody = "{\"conversation\":\"" + conversation.id() + "\"}";
    assertEquals(
        io.aeyer.plowshare.protocol.frames.Code.OK,
        router
            .route(
                FrameParity.frame("test.probe", conversationBody),
                new Asking("first-client", "member"))
            .code());
    assertEquals(
        io.aeyer.plowshare.protocol.frames.Code.CONFLICT,
        router
            .route(
                FrameParity.frame("test.probe", conversationBody),
                new Asking("second-client", "member"))
            .code());
    assertThrows(
        RuntimeException.class,
        () ->
            ClientProjects.requirePayload(
                Map.of("scope", Map.of("project", first.name())), "second-client", "member"));
    assertEquals(manifest, Files.readString(checkout.resolve("plowshare")));
    assertFalse(Files.exists(checkout.resolve(".plowshare")));
  }
}
