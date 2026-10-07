package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.FileStoreReference;
import io.aeyer.plowshare.server.files.ConfiguredFileStores;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("full-db")
@Testcontainers
class ApplicationPlacementTest {
  @Container
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static JdbcTemplate jdbc;
  @TempDir Path temporary;

  @BeforeAll
  static void migrate() {
    var source = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,server_admin) VALUES ('placement-admin','fixture',TRUE)");
  }

  ProjectStore projects(ConfiguredFileStores stores) {
    return new ProjectStore(
        jdbc,
        temporary.resolve("server.yml"),
        temporary.resolve("sampling"),
        null,
        null,
        null,
        stores);
  }

  void configure(Path file, Path source, Path output) throws Exception {
    Files.writeString(
        file,
        "export default "
            + new ObjectMapper()
                .writeValueAsString(
                    Map.of(
                        "version",
                        1,
                        "defaultStore",
                        "applications",
                        "fileStores",
                        Map.of(
                            "applications",
                            Map.of("root", source.toString()),
                            "artifacts",
                            Map.of("root", output.toString()))))
            + ";");
  }

  @Test
  void aliases_survive_restart_remapping_and_revocation_without_absolute_path_fallback()
      throws Exception {
    Path sources = Files.createDirectory(temporary.resolve("sources")),
        outputs = Files.createDirectory(temporary.resolve("outputs"));
    Path root = Files.createDirectory(sources.resolve("app")),
        report = Files.createDirectory(outputs.resolve("app"));
    Path file = temporary.resolve("filestore.js");
    configure(file, sources, outputs);
    var placement =
        new ApplicationPlacement(
            new FileStoreReference("applications", "app"),
            List.of(new FileStoreReference("artifacts", "app")));
    try (var stores = new ConfiguredFileStores(file.toString())) {
      var projects = projects(stores);
      projects.createServer(
          "placed",
          "DISJOINT",
          List.of(),
          "placement-admin",
          () -> new ServerProjects.ServerWorkspace(root, true, placement));
      var restarted = projects(stores).find("placed").orElseThrow();
      assertEquals(placement, restarted.placement());
      assertEquals(List.of(report.toRealPath()), restarted.writeRoots());
      assertTrue(projects.effectiveExclusions(restarted).contains(file.toRealPath()));
      assertThrows(ArchiveRefusedException.class, () -> projects.moveWorkspace("placed", root));
      assertThrows(ArchiveRefusedException.class, () -> projects.lend("placed", List.of(outputs)));
      Path moved = Files.createDirectory(temporary.resolve("moved"));
      Path next = Files.createDirectory(moved.resolve("app"));
      configure(file, moved, outputs);
      assertEquals(next.toRealPath(), projects(stores).find("placed").orElseThrow().workspace());
      Files.writeString(file, "export default {}; ");
      assertThrows(WorkspaceRefusedException.class, () -> projects.find("placed"));
      assertTrue(projects.all().stream().noneMatch(row -> row.name().equals("placed")));
      assertEquals(
          root.toString(),
          jdbc.queryForObject("SELECT workspace FROM projects WHERE name='placed'", String.class));
      assertThrows(
          DataIntegrityViolationException.class,
          () -> jdbc.update("UPDATE projects SET machine='foreign' WHERE name='placed'"));
    }
  }

  @Test
  void explicit_adoption_keeps_source_and_preserves_legacy_rows_and_rolls_back_refused_grants()
      throws Exception {
    Path sources = Files.createDirectory(temporary.resolve("sources")),
        outputs = Files.createDirectory(temporary.resolve("outputs"));
    Path root = Files.createDirectory(sources.resolve("app")),
        other = Files.createDirectory(sources.resolve("other"));
    Path file = temporary.resolve("filestore.js");
    configure(file, sources, outputs);
    try (var stores = new ConfiguredFileStores(file.toString())) {
      var projects = projects(stores);
      projects.createServer(
          "adopt",
          "DISJOINT",
          List.of("."),
          "placement-admin",
          () -> new ServerProjects.ServerWorkspace(root, true));
      projects.define("legacy", other, List.of());
      assertNull(projects.find("legacy").orElseThrow().placement());
      assertThrows(
          ArchiveRefusedException.class,
          () ->
              projects.place(
                  "adopt",
                  new ApplicationPlacement(
                      new FileStoreReference("applications", "other"), List.of()),
                  "placement-admin"));
      assertNull(projects.find("adopt").orElseThrow().placement());
      var adopted =
          projects.place(
              "adopt",
              new ApplicationPlacement(new FileStoreReference("applications", "app"), List.of()),
              "placement-admin");
      assertEquals(root.toRealPath(), adopted.workspace());
      assertTrue(adopted.readOnly());
      assertNull(projects.find("legacy").orElseThrow().placement());
      assertThrows(
          RuntimeException.class,
          () -> projects.place("adopt", adopted.placement(), "missing-account"));
      assertEquals(adopted.placement(), projects.find("adopt").orElseThrow().placement());
      projects.forget("adopt");
      assertTrue(projects.find("adopt").isEmpty());
      assertNull(
          jdbc.queryForObject(
              "SELECT application_storage FROM projects WHERE name='adopt'", String.class));
      assertTrue(
          jdbc.queryForObject(
              "SELECT application_boundary FROM projects WHERE name='adopt'", Boolean.class));
      var registered =
          projects.createServer(
              "adopt",
              "DISJOINT",
              List.of(),
              "placement-admin",
              () -> new ServerProjects.ServerWorkspace(root, true, adopted.placement()));
      assertEquals(adopted.placement(), registered.placement());
    }
  }
}
