package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldReadRequest;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldRole;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldSelection;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.Query;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.ResolutionStatus;
import io.aeyer.plowshare.integrations.ha.HomeAssistantProviderConfiguration.Discovery;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.protocol.IntegrationPayload.NumberParameter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HomeAssistantCapabilityNativeTest {
  @TempDir Path directory;

  @BeforeEach
  void privatePath() throws IOException {
    directory = directory.toRealPath();
  }

  private static final class Native extends WebSocketListener {
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    final List<Integer> registryReplyBytes = new CopyOnWriteArrayList<>();
    final List<Integer> registryReplyCharacters = new CopyOnWriteArrayList<>();
    volatile int registryRows = 1;
    volatile String registryPadding = "";
    int selections;
    int stateReads;
    boolean firstSelectionEvent;
    boolean mutationDuringRead;
    boolean replaceAfterRead;
    boolean denyMetadataEvents;
    boolean changeUnitsDuringRead;
    String selectionFailure;
    String registryId = "registry-fixture";

    @Override
    public void onOpen(WebSocket socket, Response response) {
      socket.send("{\"type\":\"auth_required\"}");
    }

    private ObjectNode registry() {
      var row = HomeAssistantCapabilityCatalogTest.registry("climate.office", registryId, "Office");
      row.putArray("aliases").add("The study").addNull();
      return row;
    }

    private static void event(WebSocket socket) {
      var value = Json.object().put("type", "event");
      var event = value.putObject("event").put("event_type", "entity_registry_updated");
      event.putObject("data").put("entity_id", "climate.not_in_legacy_aliases");
      socket.send(value.toString());
    }

    @Override
    public void onMessage(WebSocket socket, String text) {
      try {
        JsonNode request = Json.parse(text);
        requests.add(request);
        String type = request.path("type").asText();
        if (type.equals("auth")) {
          socket.send("{\"type\":\"auth_ok\"}");
          return;
        }
        JsonNode result = Json.object();
        boolean success = true;
        if (type.equals("get_states")) {
          stateReads++;
          var state = HomeAssistantCapabilityCatalogTest.state("climate.office", "Office", "off");
          ((ObjectNode) state.path("attributes"))
              .put("current_temperature", 19.4)
              .put("temperature", 20);
          result = Json.MAPPER.createArrayNode().add(state);
          if (stateReads > 1 && mutationDuringRead) event(socket);
          if (stateReads > 1 && replaceAfterRead) registryId = "replacement-fixture";
        } else if (type.equals("config/entity_registry/list")) {
          var partial = registry();
          partial.remove("aliases");
          var rows = Json.MAPPER.createArrayNode().add(partial);
          for (int index = 1; index < registryRows; index++) {
            var unselected =
                Json.object()
                    .put("entity_id", "sensor.unselected_" + index)
                    .put("id", "unselected-registry-" + index);
            unselected.putObject("options").put("fixture_padding", registryPadding);
            rows.add(unselected);
          }
          result = rows;
        } else if (type.equals("config/entity_registry/get_entries")) {
          var object = Json.object();
          for (JsonNode entity : request.path("entity_ids"))
            object.set(entity.textValue(), registry());
          result = object;
        } else if (type.equals("config/device_registry/list"))
          result =
              Json.MAPPER
                  .createArrayNode()
                  .add(Json.object().put("id", "device-fixture").put("name", "Thermostat"));
        else if (type.equals("config/area_registry/list"))
          result =
              Json.MAPPER
                  .createArrayNode()
                  .add(Json.object().put("area_id", "office-fixture").put("name", "Office"));
        else if (type.equals("get_config")) {
          var config = Json.object();
          config
              .putObject("unit_system")
              .put("temperature", changeUnitsDuringRead && stateReads > 1 ? "°F" : "°C");
          result = config;
        } else if (type.equals("plowshare/discovery/selection")) {
          selections++;
          if (firstSelectionEvent && selections == 1) event(socket);
          if (selectionFailure != null
              && !selectionFailure.equals("changed")
              && !selectionFailure.equals("version")) success = false;
          var selected =
              Json.object()
                  .put("version", "version".equals(selectionFailure) ? 2 : 1)
                  .put("assistant", "conversation");
          var exposed = selected.putArray("entities");
          if (!("changed".equals(selectionFailure) && selections > 1))
            exposed.add("climate.office");
          result = selected;
        } else if (denyMetadataEvents
            && type.equals("subscribe_events")
            && !request.path("event_type").asText().equals("state_changed")) success = false;
        var reply = Json.object().put("type", "result").put("success", success);
        reply.set("id", request.path("id"));
        if (success) reply.set("result", result);
        else
          reply
              .putObject("error")
              .put("code", selectionFailure == null ? "not_allowed" : selectionFailure)
              .put("message", "private-server-message");
        String encoded = reply.toString();
        if (type.equals("config/entity_registry/list")) {
          registryReplyBytes.add(encoded.getBytes(StandardCharsets.UTF_8).length);
          registryReplyCharacters.add(encoded.length());
        }
        socket.send(encoded);
      } catch (Exception failure) {
        throw new AssertionError(failure);
      }
    }
  }

  @Test
  void nativeRegistryAboveOneMiBStillResolvesAndReadsOnlyAdmittedFields() throws Exception {
    var nativeHa = new Native();
    nativeHa.registryRows = 1746;
    nativeHa.registryPadding = "registry-metadata-".repeat(40);
    try (var server = new MockWebServer()) {
      server.enqueue(new MockResponse().withWebSocketUpgrade(nativeHa));
      server.start();
      var settings = settings(server.url("/").toString());
      try (var adapter =
          new HomeAssistantAdapter(settings, "fixture-token", Duration.ofSeconds(2))) {
        adapter.start(ignored -> {});
        try (var catalog = open(server, nativeHa, adapter, settings)) {
          var resolved =
              catalog.resolve(query("Office"), OperationBudget.start(Duration.ofSeconds(2)));
          assertEquals(ResolutionStatus.RESOLVED, resolved.status());
          var resource = resolved.candidates().getFirst();
          assertEquals("climate.office", resource.entityId());
          var read =
              catalog.read(
                  new FieldReadRequest(
                      resolved.revision(),
                      List.of(
                          new FieldSelection(
                              resource.resourceId(), List.of("attribute.current_temperature")))),
                  OperationBudget.start(Duration.ofSeconds(2)));
          assertEquals(
              new NumberParameter(new BigDecimal("19.4")),
              read.readings().getFirst().value().orElseThrow());
          assertFalse(nativeHa.registryReplyBytes.isEmpty());
          assertTrue(
              nativeHa.registryReplyBytes.stream()
                  .allMatch(size -> size > 1024 * 1024 && size <= 4 * 1024 * 1024));
          assertFalse(Json.MAPPER.writeValueAsString(resolved).contains("unselected"));
          assertFalse(
              nativeHa.requests.stream()
                  .anyMatch(request -> request.path("type").asText().equals("call_service")));
        }
      }
    }
  }

  @Test
  void oversizedUtf8RegistryFencesConnectionWithoutChangingAnchorsOrReplayingReads()
      throws Exception {
    var nativeHa = new Native();
    try (var server = new MockWebServer()) {
      server.enqueue(new MockResponse().withWebSocketUpgrade(nativeHa));
      server.start();
      var settings = settings(server.url("/").toString());
      try (var adapter =
          new HomeAssistantAdapter(settings, "fixture-token", Duration.ofSeconds(2))) {
        adapter.start(ignored -> {});
        try (var catalog = open(server, nativeHa, adapter, settings)) {
          catalog.resolve(query("Office"), OperationBudget.start(Duration.ofSeconds(2)));
          byte[] retained = Files.readAllBytes(directory.resolve("identity/identities.json"));
          nativeHa.registryRows = 1746;
          nativeHa.registryPadding = "é".repeat(1300);
          assertThrows(
              IOException.class,
              () -> catalog.resolve(query("Office"), OperationBudget.start(Duration.ofSeconds(2))));
          int last = nativeHa.registryReplyBytes.size() - 1;
          assertTrue(nativeHa.registryReplyBytes.get(last) > 4 * 1024 * 1024);
          assertTrue(nativeHa.registryReplyCharacters.get(last) < 4 * 1024 * 1024);
          assertFalse(adapter.resourceSource("fixture-check").connection().connected());
          assertArrayEquals(
              retained, Files.readAllBytes(directory.resolve("identity/identities.json")));
          int requests = nativeHa.requests.size();
          assertThrows(
              IOException.class,
              () -> catalog.resolve(query("Office"), OperationBudget.start(Duration.ofSeconds(2))));
          assertEquals(requests, nativeHa.requests.size());
          assertFalse(
              nativeHa.requests.stream()
                  .anyMatch(request -> request.path("type").asText().equals("call_service")));
        }
      }
    }
  }

  private static Query query(String text) {
    return new Query(
        text,
        Optional.empty(),
        Optional.empty(),
        Optional.of("temperature"),
        Optional.of(FieldRole.CURRENT),
        0,
        20,
        Optional.empty());
  }

  private HomeAssistantSettings settings(String origin) {
    return new HomeAssistantSettings(
        origin,
        "HA_TOKEN",
        Map.of("office", new HomeAssistantSettings.Entity("climate.office", List.of())),
        Map.of(),
        List.of());
  }

  private HomeAssistantResourceCapabilities open(
      MockWebServer server,
      Native nativeHa,
      HomeAssistantAdapter adapter,
      HomeAssistantSettings settings)
      throws IOException {
    return HomeAssistantCapabilityCatalog.open(
        adapter,
        "house",
        new Discovery(
            "assist_exposed",
            "standard_measurements",
            Set.of(),
            Set.of(),
            directory.resolve("identity")),
        settings,
        Json.hash("owner"));
  }

  @Test
  void canonicalExposureMaterializationEstablishesFirstObservationAndAliasesUseOneSelectedBatch()
      throws Exception {
    var nativeHa = new Native();
    nativeHa.firstSelectionEvent = true;
    try (var server = new MockWebServer()) {
      server.enqueue(new MockResponse().withWebSocketUpgrade(nativeHa));
      server.start();
      var settings = settings(server.url("/").toString());
      try (var adapter =
          new HomeAssistantAdapter(settings, "fixture-token", Duration.ofSeconds(2))) {
        adapter.start(ignored -> {});
        try (var catalog = open(server, nativeHa, adapter, settings)) {
          var result =
              catalog.resolve(query("study"), OperationBudget.start(Duration.ofSeconds(2)));
          assertEquals(ResolutionStatus.RESOLVED, result.status());
          assertEquals("Office", result.candidates().getFirst().area().orElseThrow());
          assertEquals(2, nativeHa.selections);
          assertEquals(
              1,
              nativeHa.requests.stream()
                  .filter(
                      value ->
                          value.path("type").asText().equals("config/entity_registry/get_entries"))
                  .count());
          assertFalse(
              nativeHa.requests.stream()
                  .anyMatch(value -> value.path("type").asText().equals("call_service")));
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"unknown_command", "not_allowed", "version", "changed"})
  void unavailableMalformedOrChangedExposureNeverFallsBackToOtherEntities(String failure)
      throws Exception {
    var nativeHa = new Native();
    nativeHa.selectionFailure = failure;
    try (var server = new MockWebServer()) {
      server.enqueue(new MockResponse().withWebSocketUpgrade(nativeHa));
      server.start();
      var settings = settings(server.url("/").toString());
      try (var adapter =
          new HomeAssistantAdapter(settings, "fixture-token", Duration.ofSeconds(2))) {
        adapter.start(ignored -> {});
        try (var catalog = open(server, nativeHa, adapter, settings)) {
          var error =
              assertThrows(
                  IOException.class,
                  () ->
                      catalog.resolve(
                          query("Office"), OperationBudget.start(Duration.ofSeconds(2))));
          assertFalse(error.getMessage().contains("private-server-message"));
          assertFalse(
              nativeHa.requests.stream()
                  .anyMatch(
                      value ->
                          value.path("type").asText().equals("homeassistant/expose_entity/list")));
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void registryMutationAfterMetadataCollectionDiscardsReadingsEvenWhenEventsAreDenied(boolean event)
      throws Exception {
    var nativeHa = new Native();
    nativeHa.mutationDuringRead = event;
    nativeHa.replaceAfterRead = !event;
    nativeHa.denyMetadataEvents = !event;
    try (var server = new MockWebServer()) {
      server.enqueue(new MockResponse().withWebSocketUpgrade(nativeHa));
      server.start();
      var settings = settings(server.url("/").toString());
      try (var adapter =
          new HomeAssistantAdapter(settings, "fixture-token", Duration.ofSeconds(2))) {
        adapter.start(ignored -> {});
        try (var catalog = open(server, nativeHa, adapter, settings)) {
          assertThrows(
              IOException.class,
              () -> catalog.resolve(query("Office"), OperationBudget.start(Duration.ofSeconds(2))));
          assertTrue(
              Json.parse(Files.readString(directory.resolve("identity/identities.json")))
                  .path("anchors")
                  .isEmpty());
        }
      }
    }
  }

  @Test
  void installationUnitChangeDuringCollectionCannotMislabelTemperature() throws Exception {
    var nativeHa = new Native();
    nativeHa.changeUnitsDuringRead = true;
    try (var server = new MockWebServer()) {
      server.enqueue(new MockResponse().withWebSocketUpgrade(nativeHa));
      server.start();
      var settings = settings(server.url("/").toString());
      try (var adapter =
          new HomeAssistantAdapter(settings, "fixture-token", Duration.ofSeconds(2))) {
        adapter.start(ignored -> {});
        try (var catalog = open(server, nativeHa, adapter, settings)) {
          var failure =
              assertThrows(
                  IOException.class,
                  () ->
                      catalog.resolve(
                          query("Office"), OperationBudget.start(Duration.ofSeconds(2))));
          assertTrue(failure.getMessage().contains("units changed"));
        }
      }
    }
  }

  @Test
  void mismatchedSourceConfigurationFailsBeforeIdentityStoreCreation() throws Exception {
    var original = settings("https://first.example.invalid");
    try (var adapter = new HomeAssistantAdapter(original, "fixture-token", Duration.ofSeconds(2))) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              HomeAssistantCapabilityCatalog.open(
                  adapter,
                  "house",
                  new Discovery(
                      "fixed",
                      "standard_measurements",
                      Set.of(),
                      Set.of(),
                      directory.resolve("identity")),
                  settings("https://other.example.invalid"),
                  Json.hash("owner")));
      assertFalse(Files.exists(directory.resolve("identity")));
    }
  }
}
