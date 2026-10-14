package io.aeyer.plowshare.integrations.discord;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.agents.WorkspaceApplicationPolicy;
import io.aeyer.plowshare.server.archive.ProjectRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Validate receiving source through current loaders without a server, model or database. */
class ApplicationSetupTest {
  @Test
  void receiving_application_matches_the_adapter_and_has_no_tool_authority() throws Exception {
    Path integration = Path.of(System.getProperty("integration.directory"));
    var route =
        new ObjectMapper()
            .readTree(integration.resolve("config.template.json").toFile())
            .get("routes")
            .get(0);
    Path root = integration.resolve("application");
    var boundary =
        WorkspaceApplicationPolicy.parse(
            Files.readString(root.resolve("plowshare.json")), route.get("project").asText());
    assertEquals(ApplicationPolicy.Kind.APPLICATION, boundary.kind());
    assertTrue(boundary.accounts().isEmpty());
    assertTrue(boundary.limit("ungranted", Optional.of(ProjectRole.MANAGER)).isEmpty());
    assertFalse(Files.exists(root.resolve(".plowshare")));
    var bots = AgentRegistry.of(root.resolve("bots"), Set.of(), Set.of());
    assertTrue(bots.disabled().isEmpty(), bots.disabled().toString());
    assertEquals(Set.of(route.get("bot").asText()), bots.names());
    var bot = bots.get(route.get("bot").asText());
    assertTrue(bot.bot());
    assertTrue(bot.exported());
    assertFalse(bot.delegable());
    assertTrue(bot.tools().isEmpty());
    assertTrue(bot.scopes().isEmpty());
    assertTrue(bot.calls().isEmpty());
    assertEquals(4, bot.maxTurns());
    assertEquals(4, bot.maxModelCalls());
  }

  @Test
  void explicit_manifest_grants_cap_membership_and_do_not_create_it() throws Exception {
    String manifest =
        """
        {"version":1,"name":"discord","access":{"accounts":[
          {"handle":"discord-service-fixture","role":"CONTRIBUTOR"},
          {"handle":"operator-fixture","role":"MANAGER"}
        ]}}
        """;
    var boundary = WorkspaceApplicationPolicy.parse(manifest, "discord");
    assertEquals(
        Optional.of(ProjectRole.CONTRIBUTOR),
        boundary.limit("discord-service-fixture", Optional.of(ProjectRole.MANAGER)));
    assertEquals(
        Optional.of(ProjectRole.VIEWER),
        boundary.limit("discord-service-fixture", Optional.of(ProjectRole.VIEWER)));
    assertTrue(boundary.limit("discord-service-fixture", Optional.empty()).isEmpty());
    assertTrue(
        boundary.limit("discord-person-fixture", Optional.of(ProjectRole.MANAGER)).isEmpty());
  }
}
