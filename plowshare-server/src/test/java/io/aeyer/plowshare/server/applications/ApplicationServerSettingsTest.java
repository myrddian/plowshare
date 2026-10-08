package io.aeyer.plowshare.server.applications;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationServerSettingsTest {
  @TempDir Path root;

  @Test
  void absence_adds_no_authority_and_unknown_server_configuration_is_refused() throws Exception {
    assertEquals(ApplicationServerSettings.EMPTY, ApplicationServerSettings.read("fixture", root));
    Files.createDirectory(root.resolve("server"));
    Files.writeString(root.resolve("server/application.yml"), "spring: unrelated");
    assertThrows(CallerFault.class, () -> ApplicationServerSettings.read("fixture", root));
  }

  @Test
  void malformed_missing_and_coerced_values_are_refused() throws Exception {
    Files.createDirectory(root.resolve("server"));
    for (String text :
        java.util.List.of(
            "{}",
            "{\"version\":\"1\",\"bindings\":[]}",
            "{\"version\":1.0,\"bindings\":[]}",
            "{\"version\":1,\"version\":2,\"bindings\":[]}",
            "{\"version\":1,\"bindings\":[],\"secrets\":{}}")) {
      Files.writeString(root.resolve("server/tools.json"), text);
      assertThrows(CallerFault.class, () -> ApplicationServerSettings.read("fixture", root));
    }
  }

  @Test
  void linked_declarations_and_oversized_input_are_refused() throws Exception {
    Files.createDirectory(root.resolve("server"));
    Path source =
        Files.writeString(root.resolve("outside.json"), "{\"version\":1,\"bindings\":[]}");
    Files.createSymbolicLink(root.resolve("server/tools.json"), source);
    assertThrows(CallerFault.class, () -> ApplicationServerSettings.read("fixture", root));
    Files.delete(root.resolve("server/tools.json"));
    Files.writeString(root.resolve("server/tools.json"), " ".repeat(65537));
    assertThrows(CallerFault.class, () -> ApplicationServerSettings.read("fixture", root));
  }
}
