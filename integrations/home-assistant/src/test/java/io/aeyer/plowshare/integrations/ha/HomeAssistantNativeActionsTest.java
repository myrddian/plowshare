package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.integrations.ha.ActionExecutor.Delivery;
import io.aeyer.plowshare.integrations.ha.ActionExecutor.Effect;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ConfiguredAction;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Revision;
import io.aeyer.plowshare.protocol.IntegrationPayload.NumberParameter;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

class HomeAssistantNativeActionsTest {
  enum Reply {
    ACK,
    LOST,
    REFUSED,
    INVALID
  }

  static final class Fixture extends WebSocketListener {
    final AtomicInteger actions = new AtomicInteger();
    final CountDownLatch actionReceived = new CountDownLatch(1);
    final Reply reply;
    volatile JsonNode action;

    Fixture(Reply reply) {
      this.reply = reply;
    }

    @Override
    public void onOpen(WebSocket socket, Response response) {
      socket.send("{\"type\":\"auth_required\"}");
    }

    @Override
    public void onMessage(WebSocket socket, String text) {
      try {
        JsonNode request = Json.parse(text);
        if (request.path("type").asText().equals("auth")) {
          socket.send("{\"type\":\"auth_ok\"}");
          return;
        }
        boolean service = request.path("type").asText().equals("call_service");
        if (service) {
          action = request;
          actions.incrementAndGet();
          actionReceived.countDown();
          if (reply == Reply.LOST) return;
        }
        var response =
            Json.object()
                .put("id", request.path("id").asInt())
                .put("type", "result")
                .put("success", !service || reply != Reply.REFUSED);
        if (service && reply == Reply.REFUSED)
          response.set("error", Json.object().put("code", "service_validation_error"));
        else if (service && reply == Reply.INVALID)
          response.set("result", Json.MAPPER.createArrayNode());
        else if (service)
          response.set(
              "result",
              Json.object()
                  .set(
                      "context",
                      Json.object()
                          .put("id", "native-context")
                          .put("parent_id", "native-parent")
                          .put("user_id", "private-user")));
        else
          response.set(
              "result",
              request.path("type").asText().equals("get_states")
                  ? Json.MAPPER.createArrayNode()
                  : Json.object());
        socket.send(response.toString());
      } catch (IOException invalid) {
        throw new AssertionError(invalid);
      }
    }
  }

  static HomeAssistantSettings settings(MockWebServer server) {
    return new HomeAssistantSettings(
        server.url("/").toString(),
        "HA_TOKEN",
        Map.of(
            "first",
            new HomeAssistantSettings.Entity("light.first", List.of()),
            "second",
            new HomeAssistantSettings.Entity("light.second", List.of())),
        Map.of("lighting", HomeAssistantActionToolsTest.PROFILE),
        List.of());
  }

  static ActionExecutor.Request request(HomeAssistantAdapter adapter) {
    var source = adapter.resourceSource("fixture-house");
    var current = source.connection();
    return new ActionExecutor.Request(
        UUID.randomUUID().toString(),
        new ConfiguredAction(
            "lighting",
            new Revision(source.binding(), "policy-a", current.epoch(), 0, 0),
            HomeAssistantActionToolsTest.PROFILE),
        Map.of("brightness", new NumberParameter(BigDecimal.TEN)));
  }

  @Test
  void nativeAcknowledgementRetainsExactIdentityContextAndBundle() throws Exception {
    try (var server = new MockWebServer()) {
      var fixture = new Fixture(Reply.ACK);
      server.enqueue(new MockResponse().withWebSocketUpgrade(fixture));
      server.start();
      try (var adapter =
          new HomeAssistantAdapter(settings(server), "fixture-token", Duration.ofSeconds(2))) {
        adapter.start(observation -> {});
        var request = request(adapter);
        var receipt =
            adapter
                .actionExecutor("fixture-house")
                .execute(request, () -> {}, HomeAssistantActionToolsTest.budget());
        assertEquals(Delivery.ACKNOWLEDGED, receipt.delivery());
        assertEquals(Effect.NOT_OBSERVED, receipt.effect());
        assertEquals(fixture.action.path("id").asInt(), receipt.nativeRequest().orElseThrow().id());
        assertEquals(
            request.action().revision().epoch(), receipt.nativeRequest().orElseThrow().epoch());
        assertEquals("native-context", receipt.context().orElseThrow().id());
        assertEquals("native-parent", receipt.context().orElseThrow().parent_id());
        assertEquals(2, fixture.action.path("target").path("entity_id").size());
        assertEquals("light", fixture.action.path("domain").asText());
        assertEquals("turn_on", fixture.action.path("service").asText());
        assertEquals(1, fixture.actions.get());
        assertFalse(Json.MAPPER.writeValueAsString(receipt).contains("private-user"));
      }
    }
  }

  @Test
  void lostInvalidAndRefusedAcknowledgementsRetainUnknownEffectWithoutResend() throws Exception {
    for (Reply reply : List.of(Reply.LOST, Reply.INVALID, Reply.REFUSED)) {
      try (var server = new MockWebServer()) {
        var fixture = new Fixture(reply);
        server.enqueue(new MockResponse().withWebSocketUpgrade(fixture));
        server.start();
        try (var adapter =
            new HomeAssistantAdapter(settings(server), "fixture-token", Duration.ofSeconds(2))) {
          adapter.start(observation -> {});
          var receipt =
              adapter
                  .actionExecutor("fixture-house")
                  .execute(
                      request(adapter), () -> {}, OperationBudget.start(Duration.ofMillis(150)));
          assertEquals(
              reply == Reply.REFUSED ? Delivery.REFUSED : Delivery.UNKNOWN, receipt.delivery());
          assertEquals(Effect.UNKNOWN, receipt.effect());
          assertTrue(receipt.nativeRequest().isPresent());
          assertEquals(1, fixture.actions.get());
        }
      }
    }
  }

  @Test
  void expiredRevokedAndInterruptedBeforeAdmissionNeverSubmit() throws Exception {
    try (var server = new MockWebServer()) {
      var fixture = new Fixture(Reply.ACK);
      server.enqueue(new MockResponse().withWebSocketUpgrade(fixture));
      server.start();
      try (var adapter =
          new HomeAssistantAdapter(settings(server), "fixture-token", Duration.ofSeconds(2))) {
        adapter.start(observation -> {});
        var executor = adapter.actionExecutor("fixture-house");
        var request = request(adapter);
        var refused =
            executor.execute(
                request,
                () -> {
                  throw new IOException("revoked");
                },
                HomeAssistantActionToolsTest.budget());
        assertEquals(Delivery.NOT_SUBMITTED, refused.delivery());
        assertEquals(Effect.NOT_STARTED, refused.effect());
        Thread.currentThread().interrupt();
        try {
          assertEquals(
              Delivery.NOT_SUBMITTED,
              executor
                  .execute(request, () -> {}, HomeAssistantActionToolsTest.budget())
                  .delivery());
          assertTrue(Thread.currentThread().isInterrupted());
        } finally {
          Thread.interrupted();
        }
        try {
          assertEquals(
              Delivery.NOT_SUBMITTED,
              executor
                  .execute(
                      request,
                      () -> Thread.currentThread().interrupt(),
                      HomeAssistantActionToolsTest.budget())
                  .delivery());
          assertTrue(Thread.currentThread().isInterrupted());
        } finally {
          Thread.interrupted();
        }
        var expired = OperationBudget.start(Duration.ofNanos(1));
        assertEquals(
            Delivery.NOT_SUBMITTED, executor.execute(request, () -> {}, expired).delivery());
        assertEquals(0, fixture.actions.get());
      }
    }
  }

  @Test
  void interruptAfterPossibleSendIsUnknownAndNeverResent() throws Exception {
    try (var server = new MockWebServer()) {
      var fixture = new Fixture(Reply.LOST);
      server.enqueue(new MockResponse().withWebSocketUpgrade(fixture));
      server.start();
      try (var adapter =
          new HomeAssistantAdapter(settings(server), "fixture-token", Duration.ofSeconds(2))) {
        adapter.start(observation -> {});
        var request = request(adapter);
        var outcome = new AtomicReference<ActionExecutor.Receipt>();
        var failure = new AtomicReference<Throwable>();
        var worker =
            new Thread(
                () -> {
                  try {
                    outcome.set(
                        adapter
                            .actionExecutor("fixture-house")
                            .execute(request, () -> {}, HomeAssistantActionToolsTest.budget()));
                  } catch (Throwable thrown) {
                    failure.set(thrown);
                  }
                });
        worker.start();
        assertTrue(fixture.actionReceived.await(1, TimeUnit.SECONDS));
        worker.interrupt();
        worker.join(1000);
        assertFalse(worker.isAlive());
        assertEquals(null, failure.get());
        assertEquals(Delivery.UNKNOWN, outcome.get().delivery());
        assertEquals(1, fixture.actions.get());
      }
    }
  }

  @Test
  void foreignCredentialAndChangedConfiguredProfileCannotAct() throws Exception {
    try (var server = new MockWebServer()) {
      var fixture = new Fixture(Reply.ACK);
      server.enqueue(new MockResponse().withWebSocketUpgrade(fixture));
      server.start();
      try (var adapter =
          new HomeAssistantAdapter(settings(server), "fixture-token", Duration.ofSeconds(2))) {
        adapter.start(observation -> {});
        var executor = adapter.actionExecutor("fixture-house");
        var foreign =
            new ActionExecutor.Request(
                UUID.randomUUID().toString(),
                HomeAssistantActionToolsTest.ACTION,
                Map.of("brightness", new NumberParameter(BigDecimal.ONE)));
        assertThrows(
            IllegalArgumentException.class,
            () -> executor.execute(foreign, () -> {}, HomeAssistantActionToolsTest.budget()));
        assertEquals(0, fixture.actions.get());
      }
    }
  }
}
