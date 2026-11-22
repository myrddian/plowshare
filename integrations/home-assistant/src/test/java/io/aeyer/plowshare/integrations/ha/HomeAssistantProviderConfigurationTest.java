package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.integrations.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HomeAssistantProviderConfigurationTest {
  @TempDir Path directory;

  static ObjectNode fixture() throws IOException {
    return (ObjectNode)
        Json.parse(
            """
        {"version":1,"plowshare":"https://plowshare.example.invalid","binding":"house",
         "policyRevision":"fixture-v1","homeAssistant":{
           "endpoint":"https://home-assistant.example.invalid","tokenEnv":"HA_TOKEN",
           "entities":{"lamp":{"entity":"switch.example_lamp","attributes":[]}},
           "actions":{"lamp_on":{"service":"switch.turn_on","targets":["switch.example_lamp"],
             "parameters":{},"required":[]}},"subscriptions":[]},
         "read":{"project":"fixture-project","provider":"ha-read","account":"fixture-execution",
           "prefix":"ha_read_","tokenEnv":"PLOWSHARE_TOKEN","journal":"private/read","leaseSeconds":120},
         "control":{"project":"fixture-project","provider":"ha-control","account":"fixture-execution",
           "prefix":"ha_control_","tokenEnv":"PLOWSHARE_TOKEN","journal":"private/control",
           "leaseSeconds":120,"allowedActions":["lamp_on"]},
         "timing":{"connectTimeoutMillis":20000,"requestTimeoutMillis":2000,
           "cleanupTimeoutMillis":2000,"pollIntervalMillis":500,"renewEveryMillis":10000,
           "metadataMaxAgeMillis":30000}}
        """);
  }

  static Path write(Path directory, ObjectNode value) throws IOException {
    Path path = directory.resolve("provider.json");
    Files.writeString(path, value.toString());
    return path;
  }

  @Test
  void
      strictTypedConfigurationPreservesSharedLoginAndSeparateLogicalOwnersWithoutFilesystemEffects()
          throws Exception {
    var value = HomeAssistantProviderConfiguration.read(write(directory, fixture()));
    assertEquals("fixture-project", value.read().project());
    var control = value.control().orElseThrow();
    assertEquals(value.read().account(), control.provider().account());
    assertEquals(value.read().tokenEnv(), control.provider().tokenEnv());
    assertEquals(directory.resolve("private/read"), value.read().journal());
    assertEquals(List.of("lamp_on"), control.allowedActions());
    assertEquals(1, value.selectedActions().size());
    assertFalse(Files.exists(directory.resolve("private")));
    var readonly = fixture();
    readonly.remove("control");
    assertTrue(
        HomeAssistantProviderConfiguration.read(write(directory, readonly)).control().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"routes", "script", "feedback", "systemPrompt", "externalContext"})
  void rejectsWorkflowAndPromptFieldsBeforeAnyResourceOwnership(String key) throws Exception {
    var value = fixture();
    value.put(key, "not-supported");
    assertThrows(
        IOException.class, () -> HomeAssistantProviderConfiguration.read(write(directory, value)));
    assertFalse(Files.exists(directory.resolve("private")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"account", "tokenEnv", "project", "provider", "prefix", "journal"})
  void refusesContradictoryPrincipalsAndOverlappingLogicalOwners(String field) throws Exception {
    var value = fixture();
    var read = (ObjectNode) value.get("read");
    var control = (ObjectNode) value.get("control");
    control.put(
        field,
        switch (field) {
          case "account" -> "another-principal";
          case "tokenEnv" -> "OTHER_TOKEN";
          case "project" -> "another-project";
          case "journal" -> "private/read/child";
          default -> read.get(field).textValue();
        });
    assertThrows(
        IOException.class, () -> HomeAssistantProviderConfiguration.read(write(directory, value)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"conversation.process", "ai_task.generate_data", "ai_task.generate_image"})
  void cannotExposeInferenceEndpointsThroughNamedControl(String service) throws Exception {
    var value = fixture();
    ((ObjectNode) value.path("homeAssistant").path("actions").path("lamp_on"))
        .put("service", service);
    assertThrows(
        IOException.class, () -> HomeAssistantProviderConfiguration.read(write(directory, value)));
  }

  @Test
  void requiresSelectedFixedDisclosedActionsAndNoSubscriptions() throws Exception {
    var value = fixture();
    ((ObjectNode) value.get("homeAssistant")).putArray("subscriptions").add("lamp");
    Path subscribed = write(directory, value);
    assertThrows(IOException.class, () -> HomeAssistantProviderConfiguration.read(subscribed));
    value = fixture();
    ((ObjectNode) value.get("control")).putArray("allowedActions").add("missing");
    Path missing = write(directory, value);
    assertThrows(IOException.class, () -> HomeAssistantProviderConfiguration.read(missing));
    value = fixture();
    ((ObjectNode) value.path("homeAssistant").path("actions").path("lamp_on"))
        .putArray("targets")
        .add("switch.hidden");
    Path hidden = write(directory, value);
    assertThrows(IOException.class, () -> HomeAssistantProviderConfiguration.read(hidden));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "connectTimeoutMillis",
        "requestTimeoutMillis",
        "cleanupTimeoutMillis",
        "pollIntervalMillis",
        "renewEveryMillis",
        "metadataMaxAgeMillis"
      })
  void refusesUnboundedOrMissingTiming(String field) throws Exception {
    var value = fixture();
    ((ObjectNode) value.get("timing")).put(field, 0);
    assertThrows(
        IOException.class, () -> HomeAssistantProviderConfiguration.read(write(directory, value)));
  }

  @Test
  void rejectsNullUnknownDuplicateTrailingMalformedAndOversizedInput() throws Exception {
    Path path = directory.resolve("provider.json");
    String valid = fixture().toString();
    for (String malformed :
        List.of(
            valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
            valid + " {}",
            valid.replace("\"read\":{", "\"read\":{\"unknown\":1,"),
            valid.replace("\"version\":1", "\"version\":null"))) {
      Files.writeString(path, malformed);
      assertThrows(IOException.class, () -> HomeAssistantProviderConfiguration.read(path));
    }
    Files.write(path, new byte[] {(byte) 0xc3, (byte) 0x28});
    assertThrows(IOException.class, () -> HomeAssistantProviderConfiguration.read(path));
    Files.write(path, new byte[Json.MAX_MESSAGE + 1]);
    assertThrows(IOException.class, () -> HomeAssistantProviderConfiguration.read(path));
  }

  @Test
  void actualStartupRefusesSymbolicLinkAncestorsBeforeJournalCreation() throws Exception {
    Path privateDirectory = directory.toRealPath();
    Path real = privateDirectory.resolve("real");
    Files.createDirectory(real);
    Files.createSymbolicLink(privateDirectory.resolve("alias"), real);
    var value = fixture();
    ((ObjectNode) value.get("read")).put("journal", "alias/read");
    var configuration = HomeAssistantProviderConfiguration.read(write(privateDirectory, value));
    assertThrows(
        IOException.class, () -> HomeAssistantProviderHost.requirePrivatePaths(configuration));
    assertFalse(Files.exists(real.resolve("read")));
  }
}
