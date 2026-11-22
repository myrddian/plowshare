package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.nio.file.Files;
import java.nio.file.Path;
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
                      "ha_read_search", "ha_read_describe", "ha_read_read", "ha_read_list_actions")
                  : Set.of("ha_control_describe_action", "ha_control_execute_action"),
              names);
        }
        assertEquals(6, broker.count("relay.consume"));
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
        assertEquals(4, broker.count("relay.consume"));
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

    HaPeer() throws IOException {
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
                  Json.parse(
                      """
            [{"entity_id":"light.example_lamp","state":"off","attributes":{"brightness":0},
              "last_changed":"2026-11-22T00:00:00Z","last_updated":"2026-11-22T00:00:00Z"}]
            """);
              case "config/entity_registry/list" ->
                  Json.parse(
                      """
            [{"entity_id":"light.example_lamp","name":"Fixture lamp","device_id":null,"area_id":null}]
            """);
              case "config/device_registry/list", "config/area_registry/list" ->
                  Json.MAPPER.createArrayNode();
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
      String project = value.path("project").textValue(), topic = value.path("topic").textValue();
      String group = value.path("group").textValue(),
          consumer = value.path("consumerId").textValue();
      boolean state =
          mode == Mode.RETAIN_LOST_STATE_RESULT
              && topic.equals("tool.ha-read.ha_read_read.request")
              && !results().isEmpty()
              && stateOffered.compareAndSet(false, true);
      boolean search =
          (mode == Mode.LOSE_READ_RESULT || mode == Mode.RETAIN_LOST_STATE_RESULT)
              && topic.equals("tool.ha-read.ha_read_search.request")
              && offered.compareAndSet(false, true);
      if (!state && !search)
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
      String tool = state ? "ha_read_read" : "ha_read_search";
      JsonNode declaration =
          Json.parse(catalogues().getFirst().path("text").textValue())
              .path("tools")
              .get(state ? 2 : 0);
      assertEquals(tool, declaration.path("name").textValue());
      var schema =
          SchemaCodec.decode(
              Json.MAPPER.convertValue(
                  declaration.path("inputSchema"), new TypeReference<Map<String, Object>>() {}));
      var call =
          new RelayTools.Call(
              "plowshare-tool/2",
              state ? stateInvocation : invocation,
              project,
              "ha-read",
              tool,
              PRINCIPAL,
              "fixture-job",
              "fixture-call",
              Instant.now().plusSeconds(30).toString(),
              state ? stateArguments() : Map.of(),
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
