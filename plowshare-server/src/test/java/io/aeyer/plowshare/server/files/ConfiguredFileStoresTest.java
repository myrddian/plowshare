package io.aeyer.plowshare.server.files;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.FileStoreReference;
import io.aeyer.plowshare.server.archive.ApplicationPlacement;
import io.aeyer.plowshare.server.archive.ProjectRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class ConfiguredFileStoresTest {
  @TempDir Path temporary;

  @Test
  void image_default_is_readable_by_the_real_registry_with_only_the_configured_manager_grant()
      throws Exception {
    Path data = Files.createDirectory(temporary.resolve("data"));
    Path workspace = Files.createDirectory(temporary.resolve("workspace \"quoted\""));
    String manager = "operator";
    var builder =
        new ProcessBuilder("sh", System.getProperty("plowshare.docker.filestores"))
            .redirectErrorStream(true);
    builder.environment().keySet().removeIf(key -> key.startsWith("PLOWSHARE_"));
    builder.environment().put("PLOWSHARE_DATA_DIR", data.toString());
    builder.environment().put("PLOWSHARE_PROJECTS_WORKSPACE_DIRECTORY", workspace.toString());
    builder.environment().put("PLOWSHARE_ADMIN_HANDLE", manager);
    var process = builder.start();
    try {
      assertTrue(process.waitFor(10, TimeUnit.SECONDS));
      assertEquals(0, process.exitValue());
    } finally {
      process.destroyForcibly();
    }
    try (var stores = new ConfiguredFileStores(data.resolve("filestore.js").toString())) {
      var reference = new FileStoreReference("applications", "network-privacy-watch");
      assertEquals(
          workspace.resolve("applications").toRealPath(),
          stores
              .resolve(
                  new ApplicationPlacement(new FileStoreReference("applications", ""), List.of()))
              .root());
      assertTrue(stores.permits(reference, manager, ProjectRole.MANAGER));
      assertFalse(stores.permits(reference, "other", ProjectRole.VIEWER));
    }
  }

  @Test
  void packaged_setting_uses_image_default_but_keeps_explicit_and_private_overlay_precedence()
      throws Exception {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    for (var source :
        new YamlPropertySourceLoader().load("packaged", new ClassPathResource("application.yml"))) {
      environment.getPropertySources().addLast(source);
    }
    assertEquals("", environment.getProperty("plowshare.filestores.config-file"));
    Path image = temporary.resolve("image.js");
    Path explicit = temporary.resolve("explicit.js");
    Path privateOverlay = temporary.resolve("private.js");
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "image", Map.of("PLOWSHARE_DEFAULT_FILESTORES_CONFIG_FILE", image.toString())));
    assertEquals(image.toString(), environment.getProperty("plowshare.filestores.config-file"));
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "explicit", Map.of("PLOWSHARE_FILESTORES_CONFIG_FILE", explicit.toString())));
    assertEquals(explicit.toString(), environment.getProperty("plowshare.filestores.config-file"));
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "private-overlay",
                Map.of("plowshare.filestores.config-file", privateOverlay.toString())));
    assertEquals(
        privateOverlay.toString(), environment.getProperty("plowshare.filestores.config-file"));
  }

  Path write(Map<String, Object> stores) throws Exception {
    Path file = temporary.resolve("filestore.js");
    Files.writeString(
        file,
        "export default "
            + new ObjectMapper()
                .writeValueAsString(
                    Map.of("version", 1, "defaultStore", "applications", "fileStores", stores))
            + ";");
    return file;
  }

  @Test
  void resolves_only_on_this_host_and_rechecks_roots_and_separate_account_grants()
      throws Exception {
    Path root = Files.createDirectory(temporary.resolve("applications"));
    Path outputs = Files.createDirectory(temporary.resolve("outputs"));
    Path source = Files.createDirectory(root.resolve("chatbot"));
    Path reports = Files.createDirectory(outputs.resolve("reports"));
    var input = new FileStoreReference("applications", "chatbot");
    var output = new FileStoreReference("artifacts", "reports");
    var placement = new ApplicationPlacement(input, List.of(output));
    Path config =
        write(
            Map.of(
                "applications",
                Map.of("root", root.toString()),
                "artifacts",
                Map.of(
                    "root",
                    outputs.toString(),
                    "access",
                    Map.of("accounts", List.of(Map.of("handle", "reader", "role", "VIEWER"))))));
    try (var stores = new ConfiguredFileStores(config.toString())) {
      assertEquals(source.toRealPath(), stores.resolve(placement).root());
      assertEquals(List.of(reports.toRealPath()), stores.resolve(placement).writableAreas());
      assertFalse(stores.permits(input, "reader", ProjectRole.VIEWER));
      assertTrue(stores.permits(output, "reader", ProjectRole.VIEWER));
      assertFalse(stores.permits(output, "reader", ProjectRole.CONTRIBUTOR));
      assertFalse(stores.permits(output, "other", ProjectRole.VIEWER));
      write(
          Map.of(
              "applications",
              Map.of("root", root.toString()),
              "artifacts",
              Map.of("root", outputs.toString())));
      assertFalse(stores.permits(output, "reader", ProjectRole.VIEWER));
      Files.writeString(config, "export default {version:1};");
      assertThrows(WorkspaceRefusedException.class, () -> stores.resolve(placement));
      assertThrows(
          WorkspaceRefusedException.class,
          () -> stores.permits(output, "reader", ProjectRole.VIEWER));
      assertEquals("export default {version:1};", Files.readString(config));
    }
  }

  @Test
  void rejects_unknown_aliases_links_missing_roots_and_malformed_whole_definitions()
      throws Exception {
    Path root = Files.createDirectory(temporary.resolve("applications"));
    Path outside = Files.createDirectory(temporary.resolve("outside"));
    Files.createSymbolicLink(root.resolve("escape"), outside);
    Path config = write(Map.of("applications", Map.of("root", root.toString())));
    try (var stores = new ConfiguredFileStores(config.toString())) {
      assertThrows(
          WorkspaceRefusedException.class,
          () ->
              stores.resolve(
                  new ApplicationPlacement(new FileStoreReference("unknown", ""), List.of())));
      assertThrows(
          WorkspaceRefusedException.class,
          () ->
              stores.resolve(
                  new ApplicationPlacement(
                      new FileStoreReference("applications", "escape/file"), List.of())));
      for (String bad :
          List.of(
              "null",
              "{version:2,defaultStore:'applications',fileStores:{applications:{root:'/unused'}}}",
              "{version:1,defaultStore:'applications',fileStores:{applications:{root:3}}}",
              "{version:1,defaultStore:'applications',fileStores:{applications:{root:'/unused',unexpected:true}}}")) {
        Files.writeString(config, "export default " + bad + ";");
        assertThrows(
            WorkspaceRefusedException.class,
            () ->
                stores.resolve(
                    new ApplicationPlacement(
                        new FileStoreReference("applications", ""), List.of())));
      }
      write(Map.of("applications", Map.of("root", temporary.resolve("missing").toString())));
      assertThrows(
          WorkspaceRefusedException.class,
          () ->
              stores.resolve(
                  new ApplicationPlacement(new FileStoreReference("applications", ""), List.of())));
      assertFalse(Files.exists(temporary.resolve("missing")));
    }
    Path linked = temporary.resolve("linked.js");
    Files.createSymbolicLink(linked, config);
    try (var stores = new ConfiguredFileStores(linked.toString())) {
      assertThrows(
          WorkspaceRefusedException.class,
          () ->
              stores.resolve(
                  new ApplicationPlacement(new FileStoreReference("applications", ""), List.of())));
    }
  }

  @Test
  void refuses_host_access_imports_and_blocking_javascript() throws Exception {
    Path file = temporary.resolve("filestore.js");
    try (var stores = new ConfiguredFileStores(file.toString())) {
      for (String source :
          List.of(
              "while(true){}; export default {};",
              "import './other.js'; export default {};",
              "Java.type('java.lang.System'); export default {};")) {
        Files.writeString(file, source);
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () ->
                assertThrows(
                    WorkspaceRefusedException.class,
                    () ->
                        stores.resolve(
                            new ApplicationPlacement(
                                new FileStoreReference("applications", ""), List.of()))));
        assertEquals(source, Files.readString(file));
      }
    }
  }
}
