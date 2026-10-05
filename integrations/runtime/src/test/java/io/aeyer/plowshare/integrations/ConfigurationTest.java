package io.aeyer.plowshare.integrations;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationTest {
  @TempDir Path directory;

  private String config() {
    return """
{"version":1,"plowshare":"http://localhost:8091","tokenEnv":"PLOWSHARE_TOKEN","journal":"private-journal",
 "bindings":{"house":{"adapter":"fake","project":"home","peer":"ha-house","configuration":{},
 "routes":{"heat":{"entity":"temperature","above":28,"unit":"°C","definition":"investigate","agent":"coordinator","request":"Explain the observations"}}}}}
""";
  }

  @Test
  void named_routes_parse_and_script_source_is_pinned() throws Exception {
    Path file = directory.resolve("config.json");
    Files.writeString(directory.resolve("mapping.mjs"), "export default {};");
    Files.writeString(
        file,
        config()
            .replace("\"configuration\":{}", "\"script\":\"mapping.mjs\",\"configuration\":{}"));
    var parsed = Configuration.read(file, IntegrationFixtures.factories());
    assertEquals(28, parsed.bindings().get("house").routes().get("heat").above());
    String original = parsed.bindings().get("house").fingerprint();
    Files.writeString(directory.resolve("mapping.mjs"), "export default {onEvent(){return []}};");
    assertNotEquals(
        original,
        Configuration.read(file, IntegrationFixtures.factories())
            .bindings()
            .get("house")
            .fingerprint());
  }

  @Test
  void retention_and_coalescing_are_explicit_and_strictly_bounded() throws Exception {
    Path file = directory.resolve("config.json");
    Files.writeString(file, config());
    assertFalse(Configuration.read(file, IntegrationFixtures.factories()).retention().enabled());
    assertEquals(
        "none",
        Configuration.read(file, IntegrationFixtures.factories())
            .bindings()
            .get("house")
            .queue()
            .coalesce());
    String queued =
        config()
            .replace(
                "\"configuration\":{}",
                "\"queue\":{\"coalesce\":\"same-state\",\"maxPendingEvents\":4},\"configuration\":{}");
    Files.writeString(file, queued);
    assertThrows(
        IllegalArgumentException.class,
        () -> Configuration.read(file, IntegrationFixtures.factories()));
    String retained =
        queued.replace(
            "\"version\":1",
            "\"retention\":{\"settledSeconds\":10,\"dedupeSeconds\":60},\"version\":1");
    Files.writeString(file, retained);
    var parsed = Configuration.read(file, IntegrationFixtures.factories());
    assertEquals(60, parsed.retention().dedupeSeconds());
    assertEquals(4, parsed.bindings().get("house").queue().maxPendingEvents());
    for (String invalid :
        new String[] {
          retained.replace("\"dedupeSeconds\":60", "\"dedupeSeconds\":0"),
          retained.replace("\"settledSeconds\":10", "\"settledSeconds\":-1"),
          retained.replace("\"settledSeconds\":10", "\"settledSeconds\":1.5"),
          retained.replace("\"maxPendingEvents\":4", "\"maxPendingEvents\":1025"),
          retained.replace("\"maxPendingEvents\":4", "\"maxPendingEvents\":4294967297"),
          retained.replace("\"coalesce\":\"same-state\"", "\"coalesce\":\"latest\""),
          retained.replace("\"dedupeSeconds\":60", "\"dedupeSeconds\":60,\"unknown\":true")
        }) {
      Files.writeString(file, invalid);
      assertThrows(
          IllegalArgumentException.class,
          () -> Configuration.read(file, IntegrationFixtures.factories()),
          invalid);
    }
  }

  @Test
  void held_duration_is_optional_integral_bounded_and_requires_a_threshold() throws Exception {
    Path file = directory.resolve("config.json");
    Files.writeString(file, config());
    assertEquals(
        0,
        Configuration.read(file, IntegrationFixtures.factories())
            .bindings()
            .get("house")
            .routes()
            .get("heat")
            .holdSeconds());
    String held = config().replace("\"above\":28", "\"above\":28,\"holdSeconds\":10");
    Files.writeString(file, held);
    assertEquals(
        10,
        Configuration.read(file, IntegrationFixtures.factories())
            .bindings()
            .get("house")
            .routes()
            .get("heat")
            .holdSeconds());
    for (String invalid :
        new String[] {
          held.replace("\"holdSeconds\":10", "\"holdSeconds\":-1"),
          held.replace("\"holdSeconds\":10", "\"holdSeconds\":86401"),
          held.replace("\"holdSeconds\":10", "\"holdSeconds\":1.5"),
          held.replace("\"holdSeconds\":10", "\"holdSeconds\":\"10\""),
          held.replace("\"holdSeconds\":10", "\"holdSeconds\":18446744073709551616"),
          held.replace("\"entity\":\"temperature\",\"above\":28,", "")
              .replace("\"configuration\":{}", "\"script\":\"mapping.mjs\",\"configuration\":{}")
        }) {
      Files.writeString(file, invalid);
      assertThrows(
          IllegalArgumentException.class,
          () -> Configuration.read(file, IntegrationFixtures.factories()),
          invalid);
    }
  }

  @Test
  void feedback_suppression_is_opt_in_and_has_a_strict_bounded_window() throws Exception {
    Path file = directory.resolve("config.json");
    Files.writeString(file, config());
    assertFalse(
        Configuration.read(file, IntegrationFixtures.factories())
            .bindings()
            .get("house")
            .feedback()
            .suppressPipelineStarts());
    String enabled =
        config()
            .replace(
                "\"configuration\":{}",
                "\"feedback\":{\"suppressPipelineStarts\":true,\"windowSeconds\":30},\"configuration\":{}");
    Files.writeString(file, enabled);
    var policy =
        Configuration.read(file, IntegrationFixtures.factories())
            .bindings()
            .get("house")
            .feedback();
    assertTrue(policy.suppressPipelineStarts());
    assertEquals(30, policy.windowSeconds());
    assertEquals(1, policy.maxDepth());
    Files.writeString(
        file, enabled.replace("\"windowSeconds\":30", "\"windowSeconds\":30,\"maxDepth\":16"));
    assertEquals(
        16,
        Configuration.read(file, IntegrationFixtures.factories())
            .bindings()
            .get("house")
            .feedback()
            .maxDepth());
    for (String invalid :
        new String[] {
          enabled.replace("\"suppressPipelineStarts\":true", "\"suppressPipelineStarts\":1"),
          enabled.replace("\"suppressPipelineStarts\":true,", ""),
          enabled.replace("\"windowSeconds\":30", "\"windowSeconds\":0"),
          enabled.replace("\"windowSeconds\":30", "\"windowSeconds\":86401"),
          enabled.replace("\"windowSeconds\":30", "\"windowSeconds\":1.5"),
          enabled.replace("\"windowSeconds\":30", "\"windowSeconds\":18446744073709551616"),
          enabled.replace("\"windowSeconds\":30", "\"maxDepth\":0"),
          enabled.replace("\"windowSeconds\":30", "\"maxDepth\":17"),
          enabled.replace("\"windowSeconds\":30", "\"maxDepth\":1.5"),
          enabled.replace("\"windowSeconds\":30", "\"maxDepth\":18446744073709551616"),
          enabled.replace("\"windowSeconds\":30", "\"windowSeconds\":30,\"unknown\":true")
        }) {
      Files.writeString(file, invalid);
      assertThrows(
          IllegalArgumentException.class,
          () -> Configuration.read(file, IntegrationFixtures.factories()),
          invalid);
    }
  }

  @Test
  void unknown_fields_and_unreachable_binding_refuse() throws Exception {
    Path file = directory.resolve("config.json");
    Files.writeString(
        file, config().replace("\"configuration\":{}", "\"other\":true,\"configuration\":{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> Configuration.read(file, IntegrationFixtures.factories()));
    Files.writeString(
        file,
        config()
            .replace("\"configuration\":{}", "\"allowBindings\":[\"other\"],\"configuration\":{}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> Configuration.read(file, IntegrationFixtures.factories()));
  }
}
