package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectConfigurationTest {
  @Test
  void settings_are_typed_and_caps_carry_the_manifest_source() {
    var settings =
        ProjectConfiguration.parse(
            """
{"version":1,"name":"house","caps":{"steps":4,"budget":8,"autoIncrease":true},
 "commands":{"local":{"mode":"ask","inherit":[],"env":{"LABEL":"quoted \\\"value\\\""}},"server":{"mode":"off"}},
 "skills":{"research":{"agentVisible":false}},"defaultBot":"interlocutor","homeAssistant":{"test":true}}
""",
            "house",
            "plowshare");
    assertTrue(settings.caps().increasesAutomatically());
    assertEquals(new ProjectCaps.Setting(8, "plowshare"), settings.caps().budget());
    assertEquals("ask", settings.commands().local().mode());
    assertEquals("quoted \"value\"", settings.commands().local().env().get("LABEL"));
    assertEquals(false, settings.skills().get("research"));
    assertEquals("interlocutor", settings.defaultBot().orElseThrow().name());
  }

  @Test
  void invalid_and_duplicate_settings_cannot_approve_an_increase() {
    for (String fields :
        List.of(
            "\"caps\":{\"autoIncrease\":\"true\"}",
            "\"caps\":{\"budget\":0}",
            "\"caps\":{\"autoIncrease\":false,\"autoIncrease\":true}",
            "\"commands\":{\"local\":{\"shells\":1}}",
            "\"skills\":{\"research\":{\"agentVisible\":1}}",
            "\"defaultBot\":\"../elsewhere\""))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ProjectConfiguration.parse(
                  "{\"version\":1,\"name\":\"house\"," + fields + "}", "house", "plowshare"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ProjectConfiguration.parse("{\"version\":1,\"name\":\"other\"}", "house", "plowshare"));
  }

  @Test
  void server_and_client_manifests_keep_precedence_and_separate_command_authority(
      @TempDir Path root) throws Exception {
    Files.writeString(
        root.resolve("plowshare"),
        "{\"version\":1,\"name\":\"house\",\"caps\":{\"budget\":8,\"autoIncrease\":true},\"commands\":{\"server\":{\"mode\":\"ask\"}}}");
    var files =
        new FakeFiles()
            .withFile(
                "plowshare",
                "{\"version\":1,\"name\":\"house\",\"caps\":{\"autoIncrease\":false},\"commands\":{\"local\":{\"mode\":\"ask\"},\"server\":{\"mode\":\"open\"}}}");
    var environments = new Environments(name -> 7L, id -> root.resolve("environment.yml"), files);
    environments.useProjectConfiguration(
        project -> ProjectConfiguration.server(root, List.of(), project));
    var caps = environments.caps("house", "session");
    assertFalse(caps.increasesAutomatically());
    assertEquals(new ProjectCaps.Setting(8, "the server's plowshare"), caps.budget());
    assertEquals("plowshare", caps.autoIncrease().source());
    assertFalse(environments.caps("house", null).increasesAutomatically());
    var commands = environments.resolve("house", "session");
    assertEquals("ask", commands.local().mode());
    assertEquals("ask", commands.server().mode());
    Files.createDirectory(root.resolve(".plowshare"));
    Files.writeString(root.resolve(".plowshare/project"), "house\n");
    assertEquals(ProjectCaps.NONE, ProjectConfiguration.server(root, List.of(), "house").caps());
  }

  @Test
  void links_exclusions_and_unknown_versions_are_refused(@TempDir Path root) throws Exception {
    Path target = root.resolve("other");
    Files.writeString(target, "{\"version\":1,\"name\":\"house\"}");
    Files.createSymbolicLink(root.resolve("plowshare"), target);
    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectConfiguration.server(root, List.of(), "house"));
    Files.delete(root.resolve("plowshare"));
    Files.move(target, root.resolve("plowshare"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectConfiguration.server(root, List.of(root.resolve("plowshare")), "house"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ProjectConfiguration.parse("{\"version\":2,\"name\":\"house\"}", "house", "plowshare"));
  }
}
