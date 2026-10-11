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

  @Test
  void worker_identity_is_optional_explicit_and_strictly_validated() throws Exception {
    Files.createDirectory(root.resolve("server"));
    assertTrue(ApplicationServerSettings.read("fixture", root).worker().isEmpty());
    Path file = root.resolve("server/relay-workers.json");
    Files.writeString(file, "{\"version\":1,\"account\":\"service\"}");
    assertEquals(
        "service",
        ApplicationServerSettings.read("fixture", root).worker().orElseThrow().account());
    for (String text :
        java.util.List.of(
            "{}",
            "null",
            "{\"version\":2,\"account\":\"service\"}",
            "{\"version\":1,\"account\":\"\"}",
            "{\"version\":\"1\",\"account\":\"service\"}",
            "{\"version\":1,\"account\":\"service\",\"role\":\"ADMIN\"}")) {
      Files.writeString(file, text);
      assertThrows(CallerFault.class, () -> ApplicationServerSettings.read("fixture", root));
    }
    Files.delete(file);
    var outside =
        Files.writeString(root.resolve("outside.json"), "{\"version\":1,\"account\":\"service\"}");
    Files.createSymbolicLink(file, outside);
    assertThrows(CallerFault.class, () -> ApplicationServerSettings.read("fixture", root));
  }

  @Test
  void omitted_accounts_inherit_the_manifest_and_refresh_with_it() throws Exception {
    inheritedDeclarations();
    for (String account : java.util.List.of("@service/first", "@service/second")) {
      Files.writeString(
          root.resolve("plowshare.json"), "{\"executionAccount\":\"" + account + "\"}");
      var settings = ApplicationServerSettings.read("fixture", root);
      assertEquals(account, settings.tools().getFirst().account());
      assertEquals(account, settings.providers().getFirst().account());
      assertEquals(account, settings.ports().getFirst().account());
      assertEquals(account, settings.worker().orElseThrow().account());
      assertEquals("fixture", settings.tools().getFirst().project());
      assertEquals("fixture", settings.ports().getFirst().project());
    }
  }

  @Test
  void omission_has_no_deployer_fallback_and_explicit_invalid_values_do_not_inherit()
      throws Exception {
    inheritedDeclarations();
    assertThrows(CallerFault.class, () -> ApplicationServerSettings.read("fixture", root));
    Files.writeString(root.resolve("plowshare.json"), "{\"executionAccount\":\"service\"}");
    for (String account : java.util.List.of("null", "1", "\"\"", "\" invalid \"")) {
      Files.writeString(
          root.resolve("server/relay-workers.json"), "{\"version\":1,\"account\":" + account + "}");
      assertThrows(CallerFault.class, () -> ApplicationServerSettings.read("fixture", root));
    }
    Files.writeString(root.resolve("server/relay-workers.json"), "{\"version\":1}");
    Files.writeString(
        root.resolve("server/tools.json"),
        "{\"version\":1,\"bindings\":[],\"providers\":[{\"provider\":\"scanner\","
            + "\"account\":null,\"prefix\":\"network_\",\"leaseSeconds\":30}]}");
    assertThrows(CallerFault.class, () -> ApplicationServerSettings.read("fixture", root));
  }

  @Test
  void explicit_accounts_remain_valid_even_when_equal_to_or_different_from_the_default()
      throws Exception {
    inheritedDeclarations();
    Files.writeString(root.resolve("plowshare.json"), "{\"executionAccount\":\"service\"}");
    var templates = new java.util.HashMap<String, String>();
    for (String name : java.util.List.of("tools", "ports", "relay-workers"))
      templates.put(name, Files.readString(root.resolve("server/" + name + ".json")));
    for (String account : java.util.List.of("service", "other-service")) {
      for (var template : templates.entrySet()) {
        String explicit =
            template
                .getValue()
                .replace("{\"provider\":", "{\"account\":\"" + account + "\",\"provider\":")
                .replace("{\"topic\":", "{\"account\":\"" + account + "\",\"topic\":")
                .replace("{\"version\":1}", "{\"version\":1,\"account\":\"" + account + "\"}");
        Files.writeString(root.resolve("server/" + template.getKey() + ".json"), explicit);
      }
      var settings = ApplicationServerSettings.read("fixture", root);
      assertEquals(account, settings.tools().getFirst().account());
      assertEquals(account, settings.providers().getFirst().account());
      assertEquals(account, settings.ports().getFirst().account());
      assertEquals(account, settings.worker().orElseThrow().account());
    }
    // Legacy explicit declarations work without any manifest default.
    Files.delete(root.resolve("plowshare.json"));
    assertEquals(
        "other-service",
        ApplicationServerSettings.read("fixture", root).providers().getFirst().account());
  }

  private void inheritedDeclarations() throws Exception {
    Files.createDirectory(root.resolve("server"));
    Files.writeString(
        root.resolve("server/tools.json"),
        """
        {"version":1,"bindings":[{"provider":"scanner","name":"scan",
        "description":"Read configured observations","parameters":[],"timeoutSeconds":30}],
        "providers":[{"provider":"scanner","prefix":"network_","leaseSeconds":30}]}
        """);
    Files.writeString(
        root.resolve("server/ports.json"),
        """
        {"version":1,"bindings":[{"topic":"privacy.scan.requests","direction":"INGRESS","groups":[]}]}
        """);
    Files.writeString(root.resolve("server/relay-workers.json"), "{\"version\":1}");
  }
}
