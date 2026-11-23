package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.integrations.ha.HomeAssistantProviderJournal.Delivery;
import io.aeyer.plowshare.protocol.IntegrationPayload.TextParameter;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.protocol.SchemaCodec;
import io.aeyer.plowshare.protocol.ToolValue;
import io.aeyer.plowshare.protocol.transport.MessageSegment;
import io.aeyer.plowshare.protocol.transport.PacketBudget;
import io.aeyer.plowshare.protocol.transport.PacketWire;
import io.aeyer.plowshare.protocol.transport.SegmentCredit;
import io.aeyer.plowshare.protocol.transport.SegmentedMessages;
import io.aeyer.plowshare.sdk.RelayTools;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Actual operator composition, default segmented/concurrent SDK and native HA sockets. The local
 * broker deliberately supplies scripted outcomes; it proves wire/recovery contracts, not live
 * server authorization, model execution or device behavior.
 */
@Timeout(20)
class OutboundProviderSdkValidationTest {
  private static final String PRINCIPAL = "fixture-execution";
  @TempDir Path directory;

  @Test
  void bothCataloguesUseOneDefaultSdkConnectionAndTheirDistinctLogicalBindings() throws Exception {
    try (var ha = new HaPeer();
        var broker = new BrokerPeer(Mode.EMPTY)) {
      var configuration = configuration(ha, broker);
      try (var host = HomeAssistantProviderHost.open(configuration, ignored -> "fixture-token")) {
        host.cycle();
        assertEquals(1, broker.connections.get());
        assertEquals(1, ha.connections.get());
        assertEquals(1, broker.count("transport.dispatch"));
        assertEquals(2, broker.catalogues().size());
        assertEquals(
            Set.of("tool.ha-read.catalog", "tool.ha-control.catalog"),
            broker.catalogues().stream()
                .map(value -> value.path("topic").textValue())
                .collect(Collectors.toSet()));
        for (JsonNode publication : broker.catalogues()) {
          assertEquals(configuration.read().project(), publication.path("project").textValue());
          var catalogue = Json.parse(publication.path("text").textValue());
          boolean read = publication.path("topic").textValue().equals("tool.ha-read.catalog");
          Set<String> names = new HashSet<>();
          catalogue.path("tools").forEach(tool -> names.add(tool.path("name").textValue()));
          assertEquals(
              read
                  ? Set.of(
                      "ha_read_search",
                      "ha_read_describe",
                      "ha_read_read",
                      "ha_read_list_actions",
                      "ha_read_health",
                      "ha_read_diagnose")
                  : Set.of("ha_control_describe_action", "ha_control_execute_action"),
              names);
        }
        assertEquals(8, broker.count("relay.consume"));
        assertEquals(0, ha.count("call_service"));
      }
      // Normal shutdown withdraws each catalogue under a fresh logical publication identity.
      assertEquals(4, broker.catalogues().size());
      assertEquals(
          4,
          broker.catalogues().stream()
              .map(value -> value.path("requestId").textValue())
              .distinct()
              .count());
      List<JsonNode> withdrawals = broker.catalogues().subList(2, 4);
      assertEquals(
          Set.of("tool.ha-read.catalog", "tool.ha-control.catalog"),
          withdrawals.stream()
              .map(value -> value.path("topic").textValue())
              .collect(Collectors.toSet()));
      for (JsonNode withdrawal : withdrawals) {
        JsonNode tools = Json.parse(withdrawal.path("text").textValue()).path("tools");
        assertTrue(tools.isArray());
        assertEquals(0, tools.size());
      }
      broker.healthy();
      ha.healthy();
    }
  }

  @Test
  void lostCatalogueAcknowledgementRetainsOriginalIntentWithoutPollActionOrReconnect()
      throws Exception {
    try (var ha = new HaPeer();
        var broker = new BrokerPeer(Mode.LOSE_CATALOGUE)) {
      var configuration = configuration(ha, broker);
      try (var host = HomeAssistantProviderHost.open(configuration, ignored -> "fixture-token")) {
        assertThrows(IOException.class, host::cycle);
        assertThrows(IOException.class, host::cycle);
        assertEquals(1, broker.connections.get());
        assertEquals(1, broker.catalogues().size());
        assertEquals(0, broker.count("relay.consume"));
        assertEquals(0, ha.count("call_service"));
      }
      JsonNode retained =
          Json.parse(Files.readString(configuration.read().journal().resolve("tools.json")));
      String original = broker.catalogues().getFirst().path("requestId").textValue();
      assertEquals(
          original, retained.path("publishing").path("request").path("requestId").textValue());
      assertTrue(retained.path("confirmed").isNull());

      // This second host is an explicit operator restart, not automatic connection recovery.
      // The broker cannot confirm the original event; absence must not authorize a replacement.
      try (var restarted =
          HomeAssistantProviderHost.open(configuration, ignored -> "fixture-token")) {
        assertThrows(IOException.class, restarted::cycle);
      }
      assertEquals(2, broker.connections.get());
      assertEquals(1, broker.count("relay.log"));
      assertEquals(1, broker.catalogues().size());
      assertEquals(0, broker.count("relay.consume"));
      assertEquals(0, ha.count("call_service"));
      broker.healthy();
      ha.healthy();
    }
  }

  @Test
  void diagnosticToolsCrossPublicSdkAndReadOnlyNativeBoundary() throws Exception {
    try (var ha = new HaPeer();
        var broker = new BrokerPeer(Mode.DIAGNOSTICS)) {
      var configuration = readOnlyConfiguration(ha, broker);
      try (var host = HomeAssistantProviderHost.open(configuration, ignored -> "fixture-token")) {
        host.cycle();
        assertEquals(3, broker.results().size());
        JsonNode health =
            Json.parse(
                Json.parse(broker.results().get(1).path("text").textValue())
                    .path("text")
                    .textValue());
        assertEquals(
            "AUTHENTICATED_CONNECTION", health.path("homeAssistantConnection").textValue());
        assertEquals("CONFIRMED_LOCAL_LEASE", health.path("providerCatalogue").textValue());
        assertEquals("NOT_CHECKED", health.path("endToEnd").textValue());
        assertTrue(!health.path("lastSuccessfulSdkCommunication").isNull());
        JsonNode diagnosis =
            Json.parse(
                Json.parse(broker.results().get(2).path("text").textValue())
                    .path("text")
                    .textValue());
        assertEquals("OBSERVED", diagnosis.path("status").textValue());
        assertEquals("AVAILABLE", diagnosis.path("availability").textValue());
        assertEquals(
            "NOT_EXPOSED_BY_POLICY",
            diagnosis.path("requestedAttributes").get(0).path("status").textValue());
        assertEquals(0, ha.count("call_service"));
        assertEquals(1, broker.connections.get());
      }
      broker.healthy();
      ha.healthy();
    }
  }

  @Test
  void capabilityToolsResolveDescribeAndReadThroughPublicSdkWithoutLegacyBypass() throws Exception {
    try (var ha = new HaPeer(true);
        var broker = new BrokerPeer(Mode.CAPABILITIES)) {
      var configuration = capabilityConfiguration(ha, broker);
      try (var host = HomeAssistantProviderHost.open(configuration, ignored -> "fixture-token")) {
        host.cycle();
        var catalogue = Json.parse(broker.catalogues().getFirst().path("text").textValue());
        Set<String> names = new HashSet<>();
        catalogue.path("tools").forEach(tool -> names.add(tool.path("name").textValue()));
        assertEquals(
            Set.of(
                "ha_read_health",
                "ha_read_resolve",
                "ha_read_describe_fields",
                "ha_read_read_fields"),
            names);
        assertEquals(3, broker.results().size());
        var resolved = broker.capabilityResult("ha_read_resolve");
        assertEquals("AMBIGUOUS", resolved.path("status").textValue());
        assertTrue(resolved.path("candidates").size() >= 2);
        var described = broker.capabilityResult("ha_read_describe_fields");
        assertTrue(described.path("fields").size() > 0);
        var read = broker.capabilityResult("ha_read_read_fields");
        var row = read.path("readings").get(0);
        assertEquals("climate.office", row.path("entityId").textValue());
        assertEquals("AVAILABLE", row.path("status").textValue());
        assertEquals("CURRENT", row.path("field").path("role").textValue());
        assertEquals("temperature", row.path("field").path("capability").textValue());
        assertEquals(new BigDecimal("19.4"), row.path("value").decimalValue());
        assertFalse(read.toString().contains("private-hidden-token"));
        assertEquals(0, ha.count("call_service"));
        assertEquals(
            0,
            broker.requests.stream()
                .filter(
                    v ->
                        v.path("payload")
                            .path("topic")
                            .asText()
                            .equals("tool.ha-read.ha_read_read.request"))
                .count());
      }
      broker.healthy();
      ha.healthy();
    }
  }

  @Test
  void capabilityStartupFailureReleasesItsNewIdentityOwnerWithoutPublishing() throws Exception {
    try (var ha = new HaPeer(true);
        var broker = new BrokerPeer(Mode.EMPTY)) {
      var configuration = capabilityConfiguration(ha, broker);
      // Retain valid identity metadata first so the later lock collision reaches composition,
      // rather than the separate preflight fence for partially lost identity state.
      try (var staged = HomeAssistantProviderHost.open(configuration, ignored -> "fixture-token")) {
        assertFalse(staged.awaitClosed(Duration.ofMillis(1)));
      }
      Files.createDirectories(configuration.read().journal());
      // Another exact SDK receipt owner prevents composition after the identity store opens.
      // No catalogue may be published merely to discover that collision.
      try (var receiptOwner =
              FileChannel.open(
                  configuration.read().journal().resolve("owner.lock"),
                  StandardOpenOption.CREATE,
                  StandardOpenOption.WRITE);
          var receiptLock = receiptOwner.lock()) {
        var failure =
            assertThrows(
                HomeAssistantProviderHost.StartupException.class,
                () -> HomeAssistantProviderHost.open(configuration, ignored -> "fixture-token"));
        assertTrue(
            failure.getCause() instanceof IOException,
            "Composition must reach the existing receipt owner's lock fence");
        assertEquals("HA tool journal is already owned", failure.getCause().getMessage());
        assertTrue(failure.owner().awaitClosed(Duration.ofSeconds(2)));
        try (var identityOwner =
                FileChannel.open(
                    configuration.discovery().orElseThrow().identityJournal().resolve("owner.lock"),
                    StandardOpenOption.WRITE);
            var released = identityOwner.tryLock()) {
          assertTrue(
              released != null, "Failed composition must release its retained identity owner");
        }
        assertTrue(receiptLock.isValid(), "Cleanup must not release the other receipt owner");
      }
      assertEquals(0, broker.catalogues().size());
      assertEquals(0, ha.count("call_service"));
      broker.healthy();
      ha.healthy();
    }
  }

  @Test
  void retainedCapabilityReceiptsRejectMissingIdentityStateBeforeAnyNewConnection()
      throws Exception {
    try (var ha = new HaPeer(true);
        var broker = new BrokerPeer(Mode.CAPABILITIES)) {
      var configuration = capabilityConfiguration(ha, broker);
      try (var host = HomeAssistantProviderHost.open(configuration, ignored -> "fixture-token")) {
        host.cycle();
      }
      Path retainedJournal = configuration.read().journal().resolve("tools.json");
      byte[] retained = Files.readAllBytes(retainedJournal);
      int nativeConnections = ha.connections.get();
      int sdkConnections = broker.connections.get();
      int publications = broker.publications.size();
      Path identities =
          configuration.discovery().orElseThrow().identityJournal().resolve("identities.json");
      Files.delete(identities); // Isolated fault: original SDK receipts and markers survive.
      assertThrows(
          IOException.class,
          () -> HomeAssistantProviderHost.open(configuration, ignored -> "fixture-token"));
      assertEquals(nativeConnections, ha.connections.get());
      assertEquals(sdkConnections, broker.connections.get());
      assertEquals(publications, broker.publications.size());
      assertArrayEquals(retained, Files.readAllBytes(retainedJournal));
      assertFalse(
          Files.exists(identities), "Refusal must not bootstrap replacement identity state");
      assertEquals(0, ha.count("call_service"));
      broker.healthy();
      ha.healthy();
    }
  }

  @Test
  void readHandlerCrossesBothRealProtocolsAndLostResultAckNeverRepeatsCollection()
      throws Exception {
    try (var ha = new HaPeer();
        var broker = new BrokerPeer(Mode.LOSE_READ_RESULT)) {
      var configuration = configuration(ha, broker);
      try (var host = HomeAssistantProviderHost.open(configuration, ignored -> "fixture-token")) {
        assertThrows(IOException.class, host::cycle);
        assertEquals(1, broker.results().size());
        JsonNode publication = broker.results().getFirst();
        assertEquals(broker.invocation, publication.path("correlationId").textValue());
        assertEquals(broker.invocation, publication.path("parentEventId").textValue());
        JsonNode result = Json.parse(publication.path("text").textValue());
        assertEquals("COMPLETED", result.path("state").textValue());
        assertTrue(result.path("text").textValue().contains("lamp"));
        assertEquals(1, ha.count("config/entity_registry/list"));
        assertEquals(0, ha.count("call_service"));
        assertThrows(IOException.class, host::cycle);
      }
      long collected = ha.count("config/entity_registry/list");
      String original = broker.results().getFirst().path("requestId").textValue();
      try (var restarted =
          HomeAssistantProviderHost.open(configuration, ignored -> "fixture-token")) {
        assertThrows(IOException.class, restarted::cycle);
      }
      assertEquals(2, broker.connections.get());
      assertTrue(broker.count("relay.log") > 0);
      assertEquals(1, broker.results().size());
      assertEquals(original, broker.results().getFirst().path("requestId").textValue());
      assertEquals(collected, ha.count("config/entity_registry/list"));
      assertEquals(0, ha.count("call_service"));
      JsonNode retained =
          Json.parse(Files.readString(configuration.read().journal().resolve("tools.json")));
      assertEquals("PUBLISHING", retained.path("receipts").get(0).path("phase").textValue());
      broker.healthy();
      ha.healthy();
    }
  }

  @Test
  void freshSdkOwnerConfirmsLostCatalogueBeforeRenewingWithoutReplayingItsPublication()
      throws Exception {
    try (var ha = new HaPeer();
        var broker = new BrokerPeer(Mode.RETAIN_LOST_CATALOGUE)) {
      var configuration = readOnlyConfiguration(ha, broker);
      try (var host = HomeAssistantProviderHost.open(configuration, ignored -> "fixture-token")) {
        assertThrows(IOException.class, host::cycle);
      }
      String original = broker.catalogues().getFirst().path("requestId").textValue();
      try (var recovered =
          HomeAssistantProviderHost.open(configuration, ignored -> "fixture-token")) {
        recovered.cycle();
        assertEquals(2, broker.connections.get());
        assertEquals(1, broker.count("relay.log"));
        assertEquals(2, broker.catalogues().size());
        assertEquals(
            1,
            broker.catalogues().stream()
                .filter(value -> original.equals(value.path("requestId").textValue()))
                .count());
        assertEquals(6, broker.count("relay.consume"));
        assertEquals(0, ha.count("config/entity_registry/list"));
      }
      assertEquals(0, ha.count("call_service"));
      broker.healthy();
      ha.healthy();
    }
  }

  @Test
  void freshSdkOwnerReconcilesAcceptedStateResultWithoutAnotherNativeRead() throws Exception {
    try (var ha = new HaPeer();
        var broker = new BrokerPeer(Mode.RETAIN_LOST_STATE_RESULT)) {
      var configuration = readOnlyConfiguration(ha, broker);
      try (var host = HomeAssistantProviderHost.open(configuration, ignored -> "fixture-token")) {
        assertThrows(IOException.class, host::cycle);
      }
      broker.healthy();
      ha.healthy();
      JsonNode original = broker.stateResults().getFirst();
      JsonNode result = Json.parse(original.path("text").textValue());
      assertEquals("COMPLETED", result.path("state").textValue());
      assertEquals(
          "off",
          Json.parse(result.path("text").textValue())
              .path("states")
              .get(0)
              .path("value")
              .textValue());
      assertEquals(2, ha.count("get_states"), "One startup snapshot and one actual native read");
      assertEquals(1, ha.count("config/entity_registry/list"));

      try (var recovered =
          HomeAssistantProviderHost.open(configuration, ignored -> "fixture-token")) {
        recovered.cycle();
        recovered.cycle();
        assertEquals(2, broker.connections.get());
        assertEquals(1, broker.stateResults().size());
        assertEquals(original, broker.stateResults().getFirst());
        assertEquals(
            3,
            ha.count("get_states"),
            "The new HA connection bootstraps once; the original invocation is not collected again");
        assertEquals(1, ha.count("config/entity_registry/list"));
      }
      JsonNode retained =
          Json.parse(Files.readString(configuration.read().journal().resolve("tools.json")));
      assertEquals(0, retained.path("receipts").size());
      assertTrue(retained.path("publishing").isNull());
      assertEquals(0, ha.count("call_service"));
      broker.healthy();
      ha.healthy();
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = RelayTools.Phase.class,
      names = {"EXECUTING", "READY"})
  void freshSdkOwnersNeverExecuteRetainedIntentsOrRecollectRetainedReadyResults(
      RelayTools.Phase phase) throws Exception {
    try (var ha = new HaPeer();
        var broker = new BrokerPeer(Mode.EMPTY)) {
      var configuration = readOnlyConfiguration(ha, broker);
      seedReceipt(configuration, broker.invocation, broker.generation, phase);
      for (int owner = 0; owner < 2; owner++) {
        try (var recovered =
            HomeAssistantProviderHost.open(configuration, ignored -> "fixture-token")) {
          recovered.cycle();
        }
      }
      assertEquals(2, broker.connections.get());
      assertEquals(1, broker.results().size());
      JsonNode publication = broker.results().getFirst();
      assertEquals(
          RelayTools.resultRequestId(broker.invocation), publication.path("requestId").textValue());
      assertEquals(broker.invocation, publication.path("correlationId").textValue());
      JsonNode result = Json.parse(publication.path("text").textValue());
      assertEquals(
          phase == RelayTools.Phase.EXECUTING ? "UNKNOWN" : "COMPLETED",
          result.path("state").textValue());
      if (phase == RelayTools.Phase.READY)
        assertEquals("retained original observation", result.path("text").textValue());
      assertEquals(0, ha.count("config/entity_registry/list"));
      assertEquals(2, ha.count("get_states"), "Only the two fresh HA connection snapshots");
      assertEquals(0, ha.count("call_service"));
      broker.healthy();
      ha.healthy();
    }
  }

  private static void seedReceipt(
      HomeAssistantProviderConfiguration configuration,
      String invocation,
      String generation,
      RelayTools.Phase phase)
      throws Exception {
    var runtime = configuration.read().runtime(configuration.timing());
    var resources = new HomeAssistantReadToolsTest.Resources();
    var tools =
        new HomeAssistantReadTools(
            HomeAssistantReadToolsTest.SOURCE, runtime.prefix(), resources, resources);
    String owner =
        HomeAssistantToolProvider.ownerFingerprint(
            configuration.plowshare(),
            configuration.homeAssistant().endpoint(),
            "fixture-token",
            configuration.policyRevision(),
            runtime.binding());
    try (var journal =
        new HomeAssistantToolJournal(
            configuration.read().journal(),
            HomeAssistantToolProvider.journalIdentity(runtime, tools),
            HomeAssistantToolProvider.journalOwner(runtime, owner))) {
      var call =
          new RelayTools.Call(
              "plowshare-tool/2",
              invocation,
              runtime.binding().project(),
              runtime.binding().provider(),
              "ha_read_search",
              PRINCIPAL,
              "fixture-job",
              "fixture-call",
              Instant.now().plusSeconds(30).toString(),
              Map.of(),
              SchemaCodec.fingerprint(tools.declarations().getFirst().schema()));
      String request = Json.MAPPER.writeValueAsString(call);
      journal.save(
          new RelayTools.Receipt(
              invocation, request, RelayTools.Phase.EXECUTING, Optional.empty(), Optional.empty()));
      // Represent process loss after the original broker acknowledgement, either before a
      // confirmed handler result or after that result was durably retained but not published.
      var delivery =
          new Delivery(invocation, "tool.ha-read.ha_read_search.request", generation, "1");
      journal.captureDelivery(delivery);
      journal.acknowledged(delivery);
      if (phase == RelayTools.Phase.READY)
        journal.save(
            new RelayTools.Receipt(
                invocation,
                request,
                phase,
                Optional.of(
                    new RelayTools.Result(
                        RelayTools.State.COMPLETED, "retained original observation")),
                Optional.empty()));
    }
  }

  private HomeAssistantProviderConfiguration readOnlyConfiguration(HaPeer ha, BrokerPeer broker)
      throws IOException {
    configuration(ha, broker);
    ObjectNode value =
        (ObjectNode) Json.parse(Files.readString(directory.toRealPath().resolve("provider.json")));
    value.remove("control");
    ((ObjectNode) value.path("homeAssistant")).set("actions", Json.object());
    ((ObjectNode) value.path("homeAssistant").path("entities").path("lamp"))
        .set("attributes", Json.MAPPER.createArrayNode());
    Path file = directory.toRealPath().resolve("readonly.json");
    Files.writeString(file, value.toString());
    return HomeAssistantProviderConfiguration.read(file);
  }

  private HomeAssistantProviderConfiguration capabilityConfiguration(HaPeer ha, BrokerPeer broker)
      throws IOException {
    readOnlyConfiguration(ha, broker);
    ObjectNode value =
        (ObjectNode) Json.parse(Files.readString(directory.toRealPath().resolve("readonly.json")));
    ((ObjectNode) value.path("homeAssistant"))
        .set(
            "entities",
            Json.parse(
                """
        {"office":{"entity":"climate.office","attributes":[]},
         "office-duplicate":{"entity":"climate.office_2","attributes":[]},
         "office-sensor":{"entity":"sensor.office_temperature","attributes":[]}}
        """));
    value.set(
        "discovery",
        Json.parse(
            """
        {"mode":"fixed","profile":"standard_measurements","excludedEntities":[],
         "excludedFields":[],"identityJournal":"private/capability-identities"}
        """));
    Path file = directory.toRealPath().resolve("capabilities.json");
    Files.writeString(file, value.toString());
    return HomeAssistantProviderConfiguration.read(file);
  }

  private HomeAssistantProviderConfiguration configuration(HaPeer ha, BrokerPeer broker)
      throws IOException {
    Path examples = Path.of(System.getProperty("integration.examples"));
    ObjectNode value =
        (ObjectNode)
            Json.parse(Files.readString(examples.resolve("outbound/control/provider.json")));
    value.put("plowshare", broker.server.url("/").toString());
    ((ObjectNode) value.path("homeAssistant")).put("endpoint", ha.server.url("/").toString());
    ((ObjectNode) value.path("read")).put("account", PRINCIPAL);
    ((ObjectNode) value.path("control")).put("account", PRINCIPAL);
    ((ObjectNode) value.path("timing")).put("connectTimeoutMillis", 2000);
    Path config = directory.toRealPath().resolve("provider.json");
    Files.writeString(config, value.toString());
    return HomeAssistantProviderConfiguration.read(config);
  }

  private enum Mode {
    EMPTY,
    DIAGNOSTICS,
    CAPABILITIES,
    LOSE_CATALOGUE,
    LOSE_READ_RESULT,
    RETAIN_LOST_CATALOGUE,
    RETAIN_LOST_STATE_RESULT
  }

  /** Native fixture accepts only the explicit startup/resource protocol used by this test. */
  private static final class HaPeer extends WebSocketListener implements AutoCloseable {
    final MockWebServer server = new MockWebServer();
    final AtomicInteger connections = new AtomicInteger();
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final boolean capabilities;

    HaPeer() throws IOException {
      this(false);
    }

    HaPeer(boolean capabilities) throws IOException {
      this.capabilities = capabilities;
      server.setDispatcher(
          new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
              assertEquals("/api/websocket", request.getPath());
              return new MockResponse().withWebSocketUpgrade(HaPeer.this);
            }
          });
      server.start();
    }

    @Override
    public void onOpen(WebSocket socket, Response response) {
      connections.incrementAndGet();
      socket.send("{\"type\":\"auth_required\"}");
    }

    @Override
    public void onMessage(WebSocket socket, String text) {
      try {
        JsonNode request = Json.parse(text);
        requests.add(request);
        String type = request.path("type").textValue();
        if (type.equals("auth")) {
          assertEquals("fixture-token", request.path("access_token").textValue());
          socket.send("{\"type\":\"auth_ok\"}");
          return;
        }
        JsonNode payload =
            switch (type) {
              case "subscribe_events" -> Json.object();
              case "get_states" ->
                  capabilities
                      ? Json.parse(
                          """
                    [{"entity_id":"climate.office","state":"off","attributes":{"friendly_name":"Office","current_temperature":19.4,"temperature":20,"temperature_unit":"°C"},"last_changed":"2026-11-24T00:00:00Z","last_updated":"2026-11-24T00:00:00Z"},
                     {"entity_id":"climate.office_2","state":"unavailable","attributes":{"friendly_name":"Office"},"last_changed":"2026-11-24T00:00:00Z","last_updated":"2026-11-24T00:00:00Z"},
                     {"entity_id":"sensor.office_temperature","state":"unavailable","attributes":{"friendly_name":"Office temperature","device_class":"temperature","unit_of_measurement":"°C"},"last_changed":"2026-11-24T00:00:00Z","last_updated":"2026-11-24T00:00:00Z"}]
                    """)
                      : Json.parse(
                          """
            [{"entity_id":"light.example_lamp","state":"off","attributes":{"brightness":0},
              "last_changed":"2026-11-22T00:00:00Z","last_updated":"2026-11-22T00:00:00Z"}]
            """);
              case "config/entity_registry/list" ->
                  capabilities
                      ? capabilityRegistry()
                      : Json.parse(
                          """
            [{"entity_id":"light.example_lamp","name":"Fixture lamp","device_id":null,"area_id":null}]
            """);
              case "config/device_registry/list" ->
                  capabilities
                      ? Json.parse(
                          """
                  [{"id":"office-device","name":"Office AC","area_id":"office"},
                   {"id":"office-device-two","name":"Other Office AC","area_id":"office"},
                   {"id":"office-motion","name":"Office motion sensor","area_id":"office"}]
                  """)
                      : Json.MAPPER.createArrayNode();
              case "config/area_registry/list" ->
                  capabilities
                      ? Json.parse(
                          """
                  [{"area_id":"office","name":"Office","aliases":[]}]
                  """)
                      : Json.MAPPER.createArrayNode();
              case "get_config" -> Json.parse("{\"unit_system\":{\"temperature\":\"°C\"}}");
              case "config/entity_registry/get_entries" -> capabilityEntries(request);
              case "get_services" -> Json.parse("{\"light\":{\"turn_on\":{\"fields\":{}}}}");
              default -> throw new AssertionError("Unexpected HA fixture operation: " + type);
            };
        ObjectNode reply = Json.object().put("type", "result").put("success", true);
        reply.set("id", request.get("id"));
        reply.set("result", payload);
        socket.send(reply.toString());
      } catch (Throwable invalid) {
        failure.compareAndSet(null, invalid);
        socket.close(1011, "fixture failed");
      }
    }

    long count(String type) {
      return requests.stream().filter(v -> type.equals(v.path("type").textValue())).count();
    }

    private JsonNode capabilityEntries(JsonNode request) throws IOException {
      assertTrue(capabilities, "Legacy mode must not request extended capability metadata");
      var result = Json.object();
      for (JsonNode selected : request.path("entity_ids")) {
        JsonNode found = null;
        for (JsonNode entity : capabilityRegistry())
          if (entity.path("entity_id").asText().equals(selected.asText())) found = entity;
        if (found == null) result.putNull(selected.asText());
        else result.set(selected.asText(), found);
      }
      return result;
    }

    private static JsonNode capabilityRegistry() throws IOException {
      return Json.parse(
          """
          [{"id":"fixture-office","entity_id":"climate.office","name":"Office","original_name":"Office","device_id":"office-device","area_id":"office","aliases":["Office AC"],"platform":"fixture","unique_id":"office-one"},
           {"id":"fixture-office-two","entity_id":"climate.office_2","name":"Office","original_name":"Office","device_id":"office-device-two","area_id":"office","aliases":[],"platform":"fixture","unique_id":"office-two"},
           {"id":"fixture-office-temperature","entity_id":"sensor.office_temperature","name":"Office temperature","device_id":"office-motion","area_id":"office","aliases":[],"platform":"fixture","unique_id":"office-motion"}]
          """);
    }

    void healthy() {
      assertTrue(failure.get() == null, () -> String.valueOf(failure.get()));
    }

    @Override
    public void close() throws IOException {
      server.close();
    }
  }

  /** Actual segmented protocol, with scripted Relay outcomes and bounded fixture send ownership. */
  private static final class BrokerPeer implements AutoCloseable {
    final MockWebServer server = new MockWebServer();
    final AtomicInteger connections = new AtomicInteger();
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    final List<JsonNode> publications = new CopyOnWriteArrayList<>();
    final List<SegmentedMessages> channels = new CopyOnWriteArrayList<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final AtomicBoolean closing = new AtomicBoolean();
    final AtomicBoolean offered = new AtomicBoolean();
    final AtomicBoolean stateOffered = new AtomicBoolean();
    final AtomicBoolean healthOffered = new AtomicBoolean();
    final Set<String> capabilityOffered = ConcurrentHashMap.newKeySet();
    final String healthInvocation = UUID.randomUUID().toString();
    final AtomicBoolean lostReply = new AtomicBoolean();
    final String invocation = UUID.randomUUID().toString();
    final String stateInvocation = UUID.randomUUID().toString();
    final String generation = UUID.randomUUID().toString();
    final Map<String, Instant> publicationTimes = new ConcurrentHashMap<>();
    final ThreadPoolExecutor replies =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16),
            Thread.ofPlatform().daemon().name("outbound-sdk-fixture-", 0).factory());
    final Mode mode;

    BrokerPeer(Mode mode) throws IOException {
      this.mode = mode;
      server.setDispatcher(
          new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
              assertTrue(request.getPath().startsWith("/v1/events?session="));
              assertEquals("Bearer fixture-token", request.getHeader("Authorization"));
              assertEquals(
                  SegmentedMessages.SUBPROTOCOL, request.getHeader("Sec-WebSocket-Protocol"));
              return new MockResponse()
                  .setHeader("Sec-WebSocket-Protocol", SegmentedMessages.SUBPROTOCOL)
                  .withWebSocketUpgrade(new Channel());
            }
          });
      server.start();
    }

    private final class Channel extends WebSocketListener {
      private SegmentedMessages packets;

      @Override
      public void onOpen(WebSocket socket, Response response) {
        connections.incrementAndGet();
        packets =
            new SegmentedMessages(
                new PacketWire() {
                  @Override
                  public void segment(MessageSegment packet) throws IOException {
                    send(socket, packet);
                  }

                  @Override
                  public void credit(SegmentCredit packet) throws IOException {
                    send(socket, packet);
                  }
                },
                new PacketBudget(1024 * 1024),
                socket::cancel);
        channels.add(packets);
      }

      @Override
      public void onMessage(WebSocket socket, String text) {
        try {
          JsonNode packet = Json.parse(text);
          switch (packet.path("kind").textValue()) {
            case "transport.credit" ->
                packets.credit(Json.MAPPER.treeToValue(packet, SegmentCredit.class));
            case "transport.segment" -> {
              var complete = packets.receive(Json.MAPPER.treeToValue(packet, MessageSegment.class));
              if (complete.isPresent()) {
                JsonNode request = Json.parse(complete.orElseThrow());
                replies.execute(() -> respond(request));
              }
            }
            default -> throw new AssertionError("Expected negotiated transport packet");
          }
        } catch (Throwable invalid) {
          fail(socket, invalid);
        }
      }

      private void respond(JsonNode request) {
        try {
          requests.add(request);
          String type = request.path("type").textValue();
          JsonNode value = request.path("payload");
          Object payload =
              switch (type) {
                case "transport.dispatch" -> {
                  assertEquals("concurrent-v1", value.path("mode").textValue());
                  yield Json.object()
                      .put("mode", "concurrent-v1")
                      .put("maxPending", 64)
                      .put("maxIdBytes", 128);
                }
                case "relay.publish" -> publication(value);
                case "relay.consume" -> batch(value);
                case "relay.ack" ->
                    new RelayPort.Acknowledged(
                        value.path("project").textValue(),
                        value.path("topic").textValue(),
                        value.path("group").textValue(),
                        value.path("batchId").textValue(),
                        "1",
                        false);
                case "relay.log" -> log(value);
                default -> throw new AssertionError("Unexpected SDK operation: " + type);
              };
          if (payload == null)
            return; // Deliberately lost application reply; transport credit already succeeded.
          ObjectNode reply =
              Json.object().put("type", type).put("protocol_version", "plowshare-v1");
          reply.set("id", request.get("id"));
          reply.set(
              "payload",
              Json.object().put("code", "OK").set("payload", Json.MAPPER.valueToTree(payload)));
          packets.send(reply.toString());
        } catch (Throwable invalid) {
          if (!closing.get()) failure.compareAndSet(null, invalid);
        }
      }
    }

    private Object publication(JsonNode value) throws IOException {
      publications.add(value.deepCopy());
      Instant publishedAt = Instant.now();
      publicationTimes.put(value.path("requestId").textValue(), publishedAt);
      boolean catalogue = value.path("topic").textValue().endsWith(".catalog");
      if (mode == Mode.LOSE_CATALOGUE && catalogue || mode == Mode.LOSE_READ_RESULT && !catalogue)
        return null;
      if ((mode == Mode.RETAIN_LOST_CATALOGUE && catalogue
              || mode == Mode.RETAIN_LOST_STATE_RESULT
                  && !catalogue
                  && "ha_read_read"
                      .equals(Json.parse(value.path("text").textValue()).path("tool").textValue()))
          && lostReply.compareAndSet(false, true)) return null;
      return new RelayPort.Published(
          value.path("requestId").textValue(),
          value.path("project").textValue(),
          value.path("topic").textValue(),
          Long.toString(
              publications.stream()
                  .filter(v -> v.path("topic").equals(value.path("topic")))
                  .count()),
          publishedAt);
    }

    private RelayLog.Page log(JsonNode query) {
      String topic = query.path("topic").textValue();
      String after = query.path("after").textValue();
      List<JsonNode> retained =
          mode == Mode.RETAIN_LOST_CATALOGUE || mode == Mode.RETAIN_LOST_STATE_RESULT
              ? publications.stream()
                  .filter(value -> topic.equals(value.path("topic").textValue()))
                  .toList()
              : List.of();
      List<RelayLog.Event> events = new ArrayList<>();
      for (int index = 0; index < retained.size(); index++) {
        String position = Integer.toString(index + 1);
        if (Long.parseLong(position) <= Long.parseLong(after)) continue;
        JsonNode value = retained.get(index);
        events.add(
            new RelayLog.Event(
                position,
                value.path("requestId").textValue(),
                RelayPort.publisher(PRINCIPAL),
                Instant.parse(value.path("occurredAt").textValue()),
                publicationTimes.get(value.path("requestId").textValue()),
                value.path("correlationId").textValue(),
                value.path("parentEventId").textValue(),
                new RelayLog.Payload("TEXT", value.path("text").textValue(), null, null, null)));
      }
      String through = Integer.toString(retained.size());
      return new RelayLog.Page(
          new RelayLog.Scope(query.path("project").textValue(), false),
          new RelayLog.Topic(topic, "TEXT", "300", null, through, "0"),
          after,
          events.isEmpty() ? after : events.getLast().position(),
          null,
          events,
          List.of(),
          List.of());
    }

    private RelayPort.Batch batch(JsonNode value) throws IOException {
      if (mode == Mode.CAPABILITIES) return capabilityBatch(value);
      String project = value.path("project").textValue(), topic = value.path("topic").textValue();
      String group = value.path("group").textValue(),
          consumer = value.path("consumerId").textValue();
      boolean state =
          mode == Mode.RETAIN_LOST_STATE_RESULT
              && topic.equals("tool.ha-read.ha_read_read.request")
              && !results().isEmpty()
              && stateOffered.compareAndSet(false, true);
      boolean diagnose =
          mode == Mode.DIAGNOSTICS
              && topic.equals("tool.ha-read.ha_read_diagnose.request")
              && !results().isEmpty()
              && stateOffered.compareAndSet(false, true);
      boolean health =
          mode == Mode.DIAGNOSTICS
              && topic.equals("tool.ha-read.ha_read_health.request")
              && healthOffered.compareAndSet(false, true);
      boolean search =
          (mode == Mode.LOSE_READ_RESULT
                  || mode == Mode.RETAIN_LOST_STATE_RESULT
                  || mode == Mode.DIAGNOSTICS)
              && topic.equals("tool.ha-read.ha_read_search.request")
              && offered.compareAndSet(false, true);
      if (!state && !search && !diagnose && !health)
        return new RelayPort.Batch(
            project,
            topic,
            group,
            consumer,
            RelayPort.Status.EMPTY,
            null,
            null,
            "0",
            null,
            null,
            List.of());
      String tool =
          state
              ? "ha_read_read"
              : diagnose ? "ha_read_diagnose" : health ? "ha_read_health" : "ha_read_search";
      JsonNode declaration =
          Json.parse(catalogues().getFirst().path("text").textValue())
              .path("tools")
              .get(state ? 2 : diagnose ? 5 : health ? 4 : 0);
      assertEquals(tool, declaration.path("name").textValue());
      var schema =
          SchemaCodec.decode(
              Json.MAPPER.convertValue(
                  declaration.path("inputSchema"), new TypeReference<Map<String, Object>>() {}));
      var call =
          new RelayTools.Call(
              "plowshare-tool/2",
              state || diagnose ? stateInvocation : health ? healthInvocation : invocation,
              project,
              "ha-read",
              tool,
              PRINCIPAL,
              "fixture-job",
              "fixture-call",
              Instant.now().plusSeconds(30).toString(),
              state
                  ? stateArguments()
                  : diagnose
                      ? Map.of(
                          "alias",
                          new TextParameter("lamp"),
                          "revision",
                          stateArguments().get("revision"),
                          "attributes",
                          new ToolValue.Array(
                              List.of(
                                  new TextParameter("unit_of_measurement"),
                                  new TextParameter("current_temperature"))))
                      : Map.of(),
              SchemaCodec.fingerprint(schema));
      var event =
          new RelayLog.Event(
              "1",
              call.invocationId(),
              "tool-runtime",
              Instant.now(),
              Instant.now(),
              call.invocationId(),
              "fixture-source",
              new RelayLog.Payload("TEXT", Json.MAPPER.writeValueAsString(call), null, null, null));
      return new RelayPort.Batch(
          project,
          topic,
          group,
          consumer,
          RelayPort.Status.DATA,
          UUID.randomUUID().toString(),
          "1",
          "1",
          Instant.now().plusSeconds(30),
          null,
          List.of(event),
          generation);
    }

    private RelayPort.Batch capabilityBatch(JsonNode value) throws IOException {
      String project = value.path("project").textValue();
      String topic = value.path("topic").textValue();
      String group = value.path("group").textValue();
      String consumer = value.path("consumerId").textValue();
      String tool = topic.substring("tool.ha-read.".length(), topic.length() - ".request".length());
      boolean ready =
          switch (tool) {
            case "ha_read_resolve" -> true;
            case "ha_read_describe_fields" -> hasCapabilityResult("ha_read_resolve");
            case "ha_read_read_fields" -> hasCapabilityResult("ha_read_describe_fields");
            default -> false;
          };
      if (!ready || !capabilityOffered.add(tool))
        return new RelayPort.Batch(
            project,
            topic,
            group,
            consumer,
            RelayPort.Status.EMPTY,
            null,
            null,
            "0",
            null,
            null,
            List.of());
      Map<String, ToolValue> arguments;
      if (tool.equals("ha_read_resolve")) {
        arguments =
            Map.of(
                "text",
                new TextParameter("office temperature"),
                "capability",
                new TextParameter("temperature"),
                "role",
                new TextParameter("CURRENT"));
      } else {
        JsonNode resolved = capabilityResult("ha_read_resolve");
        JsonNode selected = null;
        for (JsonNode candidate : resolved.path("candidates"))
          if (candidate.path("entityId").asText().equals("climate.office")) selected = candidate;
        assertTrue(selected != null, "Explicitly selected climate resource must be a candidate");
        if (tool.equals("ha_read_describe_fields"))
          arguments =
              Map.of(
                  "resourceId",
                  new TextParameter(selected.path("resourceId").textValue()),
                  "revision",
                  new TextParameter(resolved.path("revision").textValue()));
        else {
          JsonNode described = capabilityResult("ha_read_describe_fields");
          String field = null;
          for (JsonNode candidate : described.path("fields"))
            if (candidate.path("role").asText().equals("CURRENT")
                && candidate.path("capability").asText().equals("temperature"))
              field = candidate.path("id").textValue();
          assertTrue(field != null, "Current temperature field must be explicitly described");
          arguments =
              Map.of(
                  "revision",
                  new TextParameter(described.path("revision").textValue()),
                  "selections",
                  new ToolValue.Array(
                      List.of(
                          new ToolValue.ObjectValue(
                              Map.of(
                                  "resourceId",
                                      new TextParameter(selected.path("resourceId").textValue()),
                                  "fields",
                                      new ToolValue.Array(List.of(new TextParameter(field))))))));
        }
      }
      JsonNode declaration = null;
      for (JsonNode candidate :
          Json.parse(catalogues().getFirst().path("text").textValue()).path("tools"))
        if (candidate.path("name").asText().equals(tool)) declaration = candidate;
      assertTrue(declaration != null, "Only offered capability names can be called");
      var schema =
          SchemaCodec.decode(
              Json.MAPPER.convertValue(
                  declaration.path("inputSchema"), new TypeReference<Map<String, Object>>() {}));
      var call =
          new RelayTools.Call(
              "plowshare-tool/2",
              UUID.randomUUID().toString(),
              project,
              "ha-read",
              tool,
              PRINCIPAL,
              "fixture-job",
              "fixture-call",
              Instant.now().plusSeconds(30).toString(),
              arguments,
              SchemaCodec.fingerprint(schema));
      var event =
          new RelayLog.Event(
              "1",
              call.invocationId(),
              "tool-runtime",
              Instant.now(),
              Instant.now(),
              call.invocationId(),
              "fixture-source",
              new RelayLog.Payload("TEXT", Json.MAPPER.writeValueAsString(call), null, null, null));
      return new RelayPort.Batch(
          project,
          topic,
          group,
          consumer,
          RelayPort.Status.DATA,
          UUID.randomUUID().toString(),
          "1",
          "1",
          Instant.now().plusSeconds(30),
          null,
          List.of(event),
          generation);
    }

    private boolean hasCapabilityResult(String tool) throws IOException {
      for (JsonNode publication : results())
        if (Json.parse(publication.path("text").textValue()).path("tool").asText().equals(tool))
          return true;
      return false;
    }

    JsonNode capabilityResult(String tool) throws IOException {
      for (JsonNode publication : results()) {
        JsonNode result = Json.parse(publication.path("text").textValue());
        if (result.path("tool").asText().equals(tool)) {
          assertEquals("COMPLETED", result.path("state").asText(), result.path("text").asText());
          return Json.parse(result.path("text").textValue());
        }
      }
      throw new AssertionError("Expected retained capability result: " + tool);
    }

    private Map<String, ToolValue> stateArguments() throws IOException {
      JsonNode result = Json.parse(results().getFirst().path("text").textValue());
      JsonNode revision = Json.parse(result.path("text").textValue()).path("revision");
      return Map.of(
          "aliases",
          new ToolValue.Array(List.of(new TextParameter("lamp"))),
          "revision",
          Json.MAPPER.treeToValue(revision, ToolValue.class));
    }

    private static void send(WebSocket socket, Object packet) throws IOException {
      if (!socket.send(Json.MAPPER.writeValueAsString(packet)))
        throw new IOException("Fixture socket refused packet");
    }

    private void fail(WebSocket socket, Throwable invalid) {
      if (!closing.get()) failure.compareAndSet(null, invalid);
      socket.close(1011, "fixture failed");
    }

    long count(String type) {
      return requests.stream().filter(v -> type.equals(v.path("type").textValue())).count();
    }

    List<JsonNode> catalogues() {
      return publications.stream()
          .filter(v -> v.path("topic").textValue().endsWith(".catalog"))
          .toList();
    }

    List<JsonNode> results() {
      return publications.stream()
          .filter(v -> v.path("topic").textValue().endsWith(".result"))
          .toList();
    }

    List<JsonNode> stateResults() {
      return results().stream()
          .filter(value -> stateInvocation.equals(value.path("correlationId").textValue()))
          .toList();
    }

    void healthy() {
      assertTrue(failure.get() == null, () -> String.valueOf(failure.get()));
    }

    @Override
    public void close() throws IOException {
      closing.set(true);
      channels.forEach(SegmentedMessages::close);
      replies.shutdownNow();
      try {
        assertTrue(
            replies.awaitTermination(2, TimeUnit.SECONDS), "Fixture response owner did not settle");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException("Fixture cleanup interrupted", interrupted);
      }
      server.close();
    }
  }
}
