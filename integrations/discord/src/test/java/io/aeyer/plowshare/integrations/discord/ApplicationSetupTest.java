package io.aeyer.plowshare.integrations.discord;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.agents.WorkspaceApplicationPolicy;
import io.aeyer.plowshare.server.applications.ApplicationServiceManifest;
import io.aeyer.plowshare.server.archive.ProjectRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
    String source = Files.readString(root.resolve("plowshare.json"));
    var service = ApplicationServiceManifest.declaration(source).orElseThrow();
    var boundary = WorkspaceApplicationPolicy.parse(source, route.get("project").asText());
    assertEquals(ApplicationPolicy.Kind.APPLICATION, boundary.kind());
    assertEquals("CONTRIBUTOR", service.role());
    assertEquals(Map.of(service.handle(), ProjectRole.CONTRIBUTOR), boundary.accounts());
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
        {"version":1,"name":"discord",
         "service":{"handle":"discord-service-fixture","role":"CONTRIBUTOR"},
         "access":{"accounts":[
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
    assertEquals(
        Optional.of(ProjectRole.MANAGER),
        boundary.limit("operator-fixture", Optional.of(ProjectRole.MANAGER)));
    assertTrue(
        boundary.limit("discord-person-fixture", Optional.of(ProjectRole.MANAGER)).isEmpty());
  }

  @Test
  void owned_source_resolves_the_execution_principal_without_repeating_its_account_grant()
      throws Exception {
    Path root = Path.of(System.getProperty("integration.directory")).resolve("application");
    String source = Files.readString(root.resolve("plowshare.json"));
    var json = new ObjectMapper();
    var manifest = json.readTree(source);
    assertFalse(manifest.has("executionAccount"));
    assertFalse(manifest.has("acting"));
    assertEquals(0, manifest.get("access").get("accounts").size());
    var service = ApplicationServiceManifest.declaration(source).orElseThrow();
    String principal = "@service/" + UUID.randomUUID();
    String deployed = ApplicationServiceManifest.resolve(source, principal);
    assertEquals(principal, json.readTree(deployed).get("executionAccount").asText());
    assertEquals(service, ApplicationServiceManifest.declaration(deployed).orElseThrow());
    assertEquals(
        Optional.of(ProjectRole.CONTRIBUTOR),
        WorkspaceApplicationPolicy.parse(deployed, "discord")
            .limit(service.handle(), Optional.of(ProjectRole.CONTRIBUTOR)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ApplicationServiceManifest.resolve(deployed, "@service/" + UUID.randomUUID()));
    ArrayNode accounts = manifest.get("access").withArray("accounts");
    accounts.addObject().put("handle", service.handle()).put("role", "CONTRIBUTOR");
    String duplicate = json.writeValueAsString(manifest);
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceApplicationPolicy.parse(duplicate, "discord"));
  }
}
