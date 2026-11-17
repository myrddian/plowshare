package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.integrations.IntegrationAdapter.Result;
import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.integrations.ha.HomeAssistantDiscoveryPolicy.Metadata;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Availability;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Binding;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ReadRequest;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Reference;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.RegistryAvailability;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Search;
import io.aeyer.plowshare.protocol.IntegrationPayload.Arguments;
import io.aeyer.plowshare.protocol.IntegrationPayload.TextParameter;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class HomeAssistantNativeResourcesTest {
  private static final class Fixture extends WebSocketListener {
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    final AtomicInteger stateRequests = new AtomicInteger();
    final AtomicInteger actionRequests = new AtomicInteger();
    final AtomicInteger metadataSubscriptions = new AtomicInteger();
    final CountDownLatch actionReceived = new CountDownLatch(1);
    volatile WebSocket socket;
    boolean denyMetadata;
    boolean denyEntities;
    boolean hangActions;
    boolean hangReads;
    long subscriptionDelayMillis;
    String firstName = "Same name";
    int failMetadataSubscription;

    @Override
    public void onOpen(WebSocket socket, Response response) {
      this.socket = socket;
      socket.send("{\"type\":\"auth_required\"}");
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
        String failure = null;
        switch (type) {
          case "subscribe_events" -> {
            if (!request.path("event_type").asText().equals("state_changed")) {
              if (subscriptionDelayMillis > 0) Thread.sleep(subscriptionDelayMillis);
              int subscription = metadataSubscriptions.incrementAndGet();
              if (denyMetadata) failure = "unauthorized";
              if (subscription == failMetadataSubscription) failure = "internal_error";
            }
          }
          case "get_states" -> {
            stateRequests.incrementAndGet();
            if (hangReads) return;
            result =
                Json.parse(
                    """
                [{"entity_id":"sensor.first","state":"22","last_updated":"2026-11-17T00:00:00Z",
                  "last_changed":"2026-11-17T00:00:00Z","attributes":{"unit_of_measurement":"C",
                  "friendly_name":"State name","nested":{"private":"never expose"}}},
                 {"entity_id":"sensor.second","state":"unavailable","last_updated":"2026-11-17T00:00:00Z",
                  "last_changed":"2026-11-17T00:00:00Z","attributes":{}},
                 {"entity_id":"sensor.private","state":"private-value","attributes":{}}]
                """);
          }
          case "config/entity_registry/list" -> {
            result =
                Json.parse(
                    """
              [{"entity_id":"sensor.first","name":"Same name","device_id":"device-a","area_id":null},
               {"entity_id":"sensor.second","name":"Same name","device_id":null,"area_id":null},
               {"entity_id":"sensor.private","name":"private-name","device_id":"private-device","area_id":"private-area"}]
              """);
            ((ObjectNode) result.get(0)).put("name", firstName);
            if (denyEntities) failure = "unauthorized";
          }
          case "config/device_registry/list" -> {
            if (denyMetadata) failure = "unauthorized";
            else result = Json.parse("[{\"id\":\"device-a\",\"area_id\":\"area-a\"}]");
          }
          case "config/area_registry/list" -> {
            if (denyMetadata) failure = "unknown_command";
            else result = Json.parse("[{\"area_id\":\"area-a\",\"name\":\"Room\"}]");
          }
          case "get_services" -> result = Json.parse("{\"notify\":{\"fixture_phone\":{}}}");
          case "call_service" -> {
            actionRequests.incrementAndGet();
            actionReceived.countDown();
            if (hangActions) return;
            result = Json.parse("{\"context\":{\"id\":\"action-context\"}}");
          }
          default -> throw new AssertionError("unexpected fixture command: " + type);
        }
        ObjectNode reply = Json.object().put("type", "result").put("success", failure == null);
        reply.set("id", request.path("id"));
        if (failure == null) reply.set("result", result);
        else
          reply.set("error", Json.object().put("code", failure).put("message", "private failure"));
        socket.send(reply.toString());
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } catch (IOException failure) {
        throw new AssertionError(failure);
      }
    }

    void stateEvent(String entityId) {
      ObjectNode state =
          Json.object()
              .put("entity_id", entityId)
              .put("state", "25")
              .put("last_changed", "2026-11-17T00:01:00Z")
              .put("last_updated", "2026-11-17T00:01:00Z");
      state.set("attributes", Json.object());
      ObjectNode data = Json.object().put("entity_id", entityId);
      data.set("new_state", state);
      socket.send(
          Json.object()
              .put("type", "event")
              .put("id", 1)
              .set("event", Json.object().put("event_type", "state_changed").set("data", data))
              .toString());
    }

    void metadataEvent(String entityId) {
      socket.send(
          Json.object()
              .put("type", "event")
              .put("id", 2)
              .set(
                  "event",
                  Json.object()
                      .put("event_type", "entity_registry_updated")
                      .set("data", Json.object().put("entity_id", entityId)))
              .toString());
    }
  }

  private static HomeAssistantSettings settings(MockWebServer server) {
    return new HomeAssistantSettings(
        server.url("/").toString(),
        "HA_TOKEN",
        Map.of(
            "first",
            new HomeAssistantSettings.Entity("sensor.first", List.of()),
            "second",
            new HomeAssistantSettings.Entity("sensor.second", List.of()),
            "missing",
            new HomeAssistantSettings.Entity("sensor.missing", List.of())),
        Map.of(
            "notify",
            new HomeAssistantSettings.Action(
                "notify.fixture_phone",
                List.of(),
                Map.of(
                    "message",
                    new HomeAssistantSettings.ParameterPolicy("string", null, null, 100, null)),
                List.of("message"),
                null)),
        List.of());
  }

  private static HomeAssistantDiscoveryPolicy explicitPolicy(Binding binding) {
    return new HomeAssistantDiscoveryPolicy(
        binding,
        "policy-a",
        Map.of(
            "first",
            new HomeAssistantDiscoveryPolicy.Selection(
                "sensor.first",
                Set.of("unit_of_measurement", "nested"),
                new Metadata(true, true, true)),
            "second",
            new HomeAssistantDiscoveryPolicy.Selection(
                "sensor.second", Set.of(), new Metadata(true, false, false)),
            "missing",
            new HomeAssistantDiscoveryPolicy.Selection(
                "sensor.missing", Set.of(), new Metadata(false, false, false))),
        Map.of());
  }

  private static Search search() {
    return new Search("", Optional.empty(), Optional.empty(), 100, Optional.empty());
  }

  private static OperationBudget budget() {
    return OperationBudget.start(Duration.ofSeconds(2));
  }

  @Test
  void realNativeMetadataAndOneBatchReadStayPolicySelected() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      Fixture fixture = new Fixture();
      server.enqueue(new MockResponse().withWebSocketUpgrade(fixture));
      HomeAssistantSettings settings = settings(server);
      try (var adapter =
          new HomeAssistantAdapter(settings, "fixture-token", Duration.ofSeconds(2))) {
        adapter.start(ignored -> {});
        HomeAssistantResourceSource source = adapter.resourceSource("house");
        var catalog =
            new HomeAssistantResourceCatalog(
                source, explicitPolicy(source.binding()), Duration.ofSeconds(2));
        var page = catalog.search(search(), budget());
        assertEquals(3, page.resources().size());
        var first =
            catalog
                .describe(new Reference(source.binding(), "first"), page.revision(), budget())
                .orElseThrow();
        assertEquals("Same name", first.name());
        assertEquals(Optional.of("device-a"), first.device());
        assertEquals(Optional.of("area-a"), first.area());
        assertFalse(page.toString().contains("private"));
        List<Reference> references =
            List.of(
                new Reference(source.binding(), "missing"),
                new Reference(source.binding(), "first"),
                new Reference(source.binding(), "second"));
        int beforeRead = fixture.stateRequests.get();
        var read = catalog.read(new ReadRequest(page.revision(), references), budget());
        assertEquals(beforeRead + 1, fixture.stateRequests.get());
        assertEquals(Availability.MISSING, read.states().get(0).availability());
        assertEquals(Set.of("nested"), read.states().get(1).unsupportedAttributes());
        assertFalse(read.toString().contains("never expose"));
        assertEquals(Availability.UNAVAILABLE, read.states().get(2).availability());
        // Additional attributes require an explicit trusted resource policy. Legacy defaults do
        // not broaden, and legacy selected nested attributes still refuse their old DTO shape.
        var defaults =
            new HomeAssistantResourceCatalog(
                source,
                HomeAssistantDiscoveryPolicy.fromSettings(source.binding(), "defaults", settings),
                Duration.ofSeconds(2));
        var defaultPage = defaults.search(search(), budget());
        var defaultRead =
            defaults.read(
                new ReadRequest(defaultPage.revision(), List.of(references.get(1))), budget());
        assertTrue(defaultRead.states().getFirst().unsupportedAttributes().isEmpty());
        assertFalse(defaultPage.resources().getFirst().readableAttributes().contains("nested"));
        assertEquals(0, fixture.actionRequests.get());
        assertTrue(
            fixture.requests.stream()
                .filter(value -> value.path("type").asText().equals("subscribe_events"))
                .allMatch(value -> value.has("event_type")));
      }
    }
  }

  @Test
  void legacyManualNestedSelectionStillRefusesWhileResourcePolicyIsExplicit() throws Exception {
    JsonNode state =
        Json.parse(
            """
        {"entity_id":"sensor.first","state":"22","attributes":{"nested":{"private":"value"}},
         "last_updated":"2026-11-17T00:00:00Z","last_changed":"2026-11-17T00:00:00Z"}
        """);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HomeAssistantWire.reading(
                "first",
                new HomeAssistantSettings.Entity("sensor.first", List.of("nested")),
                state,
                "epoch",
                Instant.EPOCH));
  }

  @Test
  void deniedResourceEventsDoNotPublishCountersOrChangeVisibleMetadataRevision() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      Fixture fixture = new Fixture();
      server.enqueue(new MockResponse().withWebSocketUpgrade(fixture));
      try (var adapter =
          new HomeAssistantAdapter(settings(server), "fixture-token", Duration.ofSeconds(2))) {
        adapter.start(ignored -> {});
        HomeAssistantResourceSource source = adapter.resourceSource("restricted");
        var policy =
            new HomeAssistantDiscoveryPolicy(
                source.binding(),
                "restricted-policy",
                Map.of(
                    "first",
                    new HomeAssistantDiscoveryPolicy.Selection(
                        "sensor.first", Set.of(), new Metadata(true, false, false))),
                Map.of());
        var catalog = new HomeAssistantResourceCatalog(source, policy, Duration.ofSeconds(2));
        var first = catalog.search(search(), budget());
        long oldDirty = source.connection().metadataVersion();
        fixture.metadataEvent("sensor.second");
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (source.connection().metadataVersion() == oldDirty && System.nanoTime() < deadline)
          Thread.sleep(5);
        assertTrue(source.connection().metadataVersion() != oldDirty);
        assertTrue(
            catalog
                .describe(first.resources().getFirst().reference(), first.revision(), budget())
                .isPresent());
        assertEquals(first.revision(), catalog.search(search(), budget()).revision());
        fixture.stateEvent("sensor.second");
        long stateDeadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (!"25".equals(adapter.snapshot().get("second").state())
            && System.nanoTime() < stateDeadline) Thread.sleep(5);
        assertEquals("25", adapter.snapshot().get("second").state());
        assertEquals(first.revision(), catalog.search(search(), budget()).revision());
      }
    }
  }

  @Test
  void deniedAndUnsupportedRegistriesAreExplicitAndEventsInvalidateOldPages() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      Fixture fixture = new Fixture();
      fixture.denyMetadata = true;
      server.enqueue(new MockResponse().withWebSocketUpgrade(fixture));
      try (var adapter =
          new HomeAssistantAdapter(settings(server), "fixture-token", Duration.ofSeconds(2))) {
        adapter.start(ignored -> {});
        HomeAssistantResourceSource source = adapter.resourceSource("house");
        var catalog =
            new HomeAssistantResourceCatalog(
                source, explicitPolicy(source.binding()), Duration.ofSeconds(2));
        var page = catalog.search(search(), budget());
        var first = page.resources().getFirst();
        assertEquals(RegistryAvailability.DENIED, first.registries().devices());
        assertEquals(RegistryAvailability.UNSUPPORTED, first.registries().areas());
        assertTrue(first.device().isEmpty());
        assertTrue(first.area().isEmpty());
        long old = source.connection().metadataVersion();
        fixture.firstName = "Renamed selected resource";
        fixture.metadataEvent("sensor.first");
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (source.connection().metadataVersion() == old && System.nanoTime() < deadline)
          Thread.sleep(5);
        assertTrue(source.connection().metadataVersion() != old);
        assertThrows(
            IOException.class,
            () -> catalog.describe(first.reference(), page.revision(), budget()));
        assertEquals(
            "Renamed selected resource",
            catalog.search(search(), budget()).resources().getFirst().name());
        assertEquals(1, fixture.stateRequests.get());
      }
    }
  }

  @Test
  void absentEntityRelationshipsCannotBeReportedAsAnEmptyAreaSearch() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      Fixture fixture = new Fixture();
      fixture.denyEntities = true;
      server.enqueue(new MockResponse().withWebSocketUpgrade(fixture));
      try (var adapter =
          new HomeAssistantAdapter(settings(server), "fixture-token", Duration.ofSeconds(2))) {
        adapter.start(ignored -> {});
        HomeAssistantResourceSource source = adapter.resourceSource("house");
        var catalog =
            new HomeAssistantResourceCatalog(
                source, explicitPolicy(source.binding()), Duration.ofSeconds(2));
        var page = catalog.search(search(), budget());
        assertEquals(
            RegistryAvailability.DENIED, page.resources().getFirst().registries().entities());
        assertEquals(
            RegistryAvailability.AVAILABLE, page.resources().getFirst().registries().areas());
        assertThrows(
            IOException.class,
            () ->
                catalog.search(
                    new Search("", Optional.empty(), Optional.of("area-a"), 100, Optional.empty()),
                    budget()));
      }
    }
  }

  @Test
  void partialSubscriptionSetupCannotAccumulateDuplicateRegistrations() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      Fixture fixture = new Fixture();
      fixture.failMetadataSubscription = 2;
      server.enqueue(new MockResponse().withWebSocketUpgrade(fixture));
      try (var adapter =
          new HomeAssistantAdapter(settings(server), "fixture-token", Duration.ofSeconds(2))) {
        adapter.start(ignored -> {});
        HomeAssistantResourceSource source = adapter.resourceSource("house");
        var catalog =
            new HomeAssistantResourceCatalog(
                source, explicitPolicy(source.binding()), Duration.ofSeconds(2));
        assertThrows(IOException.class, () -> catalog.search(search(), budget()));
        assertThrows(IOException.class, () -> catalog.search(search(), budget()));
        assertEquals(2, fixture.metadataSubscriptions.get());
        assertFalse(source.connection().connected());
      }
    }
  }

  @Test
  void metadataCommandsShareOneDeadlineAndAbortCapturedConnection() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      Fixture fixture = new Fixture();
      fixture.subscriptionDelayMillis = 60;
      server.enqueue(new MockResponse().withWebSocketUpgrade(fixture));
      try (var adapter =
          new HomeAssistantAdapter(settings(server), "fixture-token", Duration.ofSeconds(2))) {
        adapter.start(ignored -> {});
        HomeAssistantResourceSource source = adapter.resourceSource("house");
        var catalog =
            new HomeAssistantResourceCatalog(
                source, explicitPolicy(source.binding()), Duration.ofSeconds(2));
        long started = System.nanoTime();
        assertThrows(
            IOException.class,
            () -> catalog.search(search(), OperationBudget.start(Duration.ofMillis(100))));
        assertTrue(System.nanoTime() - started < Duration.ofSeconds(1).toNanos());
        assertFalse(source.connection().connected());
        assertEquals(1, fixture.stateRequests.get());
      }
    }
  }

  @Test
  void readTimeoutFencesConcurrentSubmittedActionWithoutReplay() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      Fixture fixture = new Fixture();
      server.enqueue(new MockResponse().withWebSocketUpgrade(fixture));
      try (var adapter =
              new HomeAssistantAdapter(settings(server), "fixture-token", Duration.ofSeconds(2));
          var worker = Executors.newSingleThreadExecutor()) {
        adapter.start(ignored -> {});
        HomeAssistantResourceSource source = adapter.resourceSource("house");
        var catalog =
            new HomeAssistantResourceCatalog(
                source, explicitPolicy(source.binding()), Duration.ofSeconds(2));
        var page = catalog.search(search(), budget());
        fixture.hangActions = true;
        fixture.hangReads = true;
        var action =
            worker.submit(
                () ->
                    adapter.execute(
                        "actions.execute",
                        new Arguments(
                            null, "notify", Map.of("message", new TextParameter("report")))));
        assertTrue(fixture.actionReceived.await(1, TimeUnit.SECONDS));
        assertThrows(
            IOException.class,
            () ->
                catalog.read(
                    new ReadRequest(
                        page.revision(), List.of(new Reference(source.binding(), "first"))),
                    OperationBudget.start(Duration.ofMillis(50))));
        Result result = action.get(1, TimeUnit.SECONDS);
        assertEquals("UNKNOWN", result.state());
        assertEquals(1, fixture.actionRequests.get());
        assertFalse(source.connection().connected());
        adapter.maintain();
        assertEquals(1, fixture.actionRequests.get());
      }
    }
  }

  @Test
  void staleAttemptCancellationNeverSelectsTheCurrentSocket() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      Fixture fixture = new Fixture();
      server.enqueue(new MockResponse().withWebSocketUpgrade(fixture));
      try (var adapter =
          new HomeAssistantAdapter(settings(server), "fixture-token", Duration.ofSeconds(2))) {
        adapter.start(ignored -> {});
        HomeAssistantResourceSource source = adapter.resourceSource("house");
        String currentEpoch = source.connection().epoch();
        WebSocket oldAttempt = mock(WebSocket.class);
        adapter.abortConnection("old-epoch", oldAttempt);
        verify(oldAttempt).cancel();
        assertTrue(source.connection().connected());
        assertEquals(currentEpoch, source.connection().epoch());
        assertEquals(
            3,
            new HomeAssistantResourceCatalog(
                    source, explicitPolicy(source.binding()), Duration.ofSeconds(2))
                .search(search(), budget())
                .resources()
                .size());
      }
    }
  }
}
