package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.ActingContext;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

/** Actual socket correlation and owned completion execution; no database or replay fixture. */
class AsyncExchangeTest {
  private static final ObjectMapper JSON = SdkJson.mapper();

  @Test
  void negotiatesBeforeDomainWorkAndCompletesTypedCallsOutOfOrder() throws Exception {
    try (var fixture = new Fixture();
        var client = fixture.connect(Plowshare.DispatchMode.CONCURRENT)) {
      var api = new AdministrationClient(client);
      var one = api.statusAsync().toCompletableFuture();
      var first = fixture.take();
      var two = api.statusAsync().toCompletableFuture();
      var second = fixture.take();
      fixture.answer(second, Map.of("handle", "second", "serverAdmin", false));
      assertEquals("second", two.get(3, TimeUnit.SECONDS).handle());
      assertFalse(one.isDone());
      fixture.answer(first, Map.of("handle", "first", "serverAdmin", false));
      assertEquals("first", one.get(3, TimeUnit.SECONDS).handle());
      assertEquals("first", one.thenApply(AdministrationClient.Status::handle).get());
      assertTrue(one.isDone());
      assertEquals(1, fixture.negotiations);
    }
  }

  @Test
  void publicContinuationCanWaitForAnotherRequestWithoutBlockingReader() throws Exception {
    try (var fixture = new Fixture();
        var client = fixture.connect(Plowshare.DispatchMode.ORDERED)) {
      var api = new AdministrationClient(client);
      var first = api.statusAsync();
      var request = fixture.take();
      var nested =
          first
              .thenApply(
                  ignored -> {
                    try {
                      return Plowshare.await(api.statusAsync());
                    } catch (IOException failure) {
                      throw new CompletionException(failure);
                    }
                  })
              .toCompletableFuture();
      fixture.answer(request, Map.of("handle", "first", "serverAdmin", false));
      fixture.answer(fixture.take(), Map.of("handle", "nested", "serverAdmin", false));
      assertEquals("nested", nested.get(3, TimeUnit.SECONDS).handle());
    }
  }

  @Test
  void voidSuccessAndRefusalAreTerminalForMultipleObservers() throws Exception {
    try (var fixture = new Fixture();
        var client = fixture.connect(Plowshare.DispatchMode.ORDERED)) {
      var call =
          new ActingClient(client).closeAsync(new ActingContext.Selection(UUID.randomUUID()));
      fixture.answer(fixture.take(), Map.of());
      assertNull(call.toCompletableFuture().get(3, TimeUnit.SECONDS));
      assertNull(call.thenApply(value -> value).toCompletableFuture().get());
      var refused = new AdministrationClient(client).statusAsync();
      fixture.reply(fixture.take(), "BAD_REQUEST", null);
      assertInstanceOf(IOException.class, failure(refused));
      assertInstanceOf(IOException.class, failure(refused));
    }
  }

  @Test
  void admissionBoundsEveryQueuedOrSentRequestAndCloseSettlesAll() throws Exception {
    try (var fixture = new Fixture();
        var client = fixture.connect(Plowshare.DispatchMode.ORDERED)) {
      var api = new AdministrationClient(client);
      List<CompletionStage<AdministrationClient.Status>> calls = new ArrayList<>();
      for (int i = 0; i < 64; i++) calls.add(api.statusAsync());
      assertEquals(
          Plowshare.Delivery.NOT_SUBMITTED,
          assertInstanceOf(Plowshare.TransportException.class, failure(api.statusAsync()))
              .delivery());
      fixture.take();
      client.closeAsync().toCompletableFuture().get(3, TimeUnit.SECONDS);
      for (var call : calls) assertInstanceOf(Plowshare.TransportException.class, failure(call));
      client.closeAsync().toCompletableFuture().get(3, TimeUnit.SECONDS);
    }
  }

  @Test
  void blockedCallbacksUseReservedCapacityAndDoNotPreventTransportDrain() throws Exception {
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(64);
    try (var fixture = new Fixture();
        var client = fixture.connect(Plowshare.DispatchMode.ORDERED)) {
      var api = new AdministrationClient(client);
      for (int i = 0; i < 64; i++) {
        api.statusAsync()
            .thenAccept(
                ignored -> {
                  entered.countDown();
                  try {
                    release.await();
                  } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                  }
                });
      }
      for (int i = 0; i < 64; i++)
        fixture.answer(fixture.take(), Map.of("handle", "blocked", "serverAdmin", false));
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      assertEquals(
          Plowshare.Delivery.NOT_SUBMITTED,
          assertInstanceOf(Plowshare.TransportException.class, failure(api.statusAsync()))
              .delivery());
      client.closeAsync().toCompletableFuture().get(3, TimeUnit.SECONDS);
    } finally {
      release.countDown();
    }
  }

  @Test
  void cancellationAfterSubmissionIsUnknownAndLateReplyCannotCompleteANewCall() throws Exception {
    try (var fixture = new Fixture();
        var client = fixture.connect(Plowshare.DispatchMode.ORDERED)) {
      var api = new AdministrationClient(client);
      var cancellation = new RequestCancellation();
      var abandoned = api.statusAsync(new RequestOptions(Duration.ofSeconds(3), cancellation));
      var old = fixture.take();
      cancellation.cancel();
      assertEquals(
          Plowshare.Delivery.UNKNOWN,
          assertInstanceOf(Plowshare.TransportException.class, failure(abandoned)).delivery());
      var current = api.statusAsync();
      var next = fixture.take();
      fixture.answer(old, Map.of("handle", "late", "serverAdmin", false));
      fixture.answer(next, Map.of("handle", "current", "serverAdmin", false));
      assertEquals("current", current.toCompletableFuture().get(3, TimeUnit.SECONDS).handle());
      assertNotEquals(old.path("id").asText(), next.path("id").asText());
    }
  }

  @Test
  void queuedCancellationAndDeadlineDoNotWaitForHeldSegmentCredits() throws Exception {
    try (var server = new MockWebServer()) {
      var packets = new ArrayBlockingQueue<JsonNode>(8);
      server.enqueue(
          new MockResponse()
              .setHeader("Sec-WebSocket-Protocol", "plowshare-segments-v1")
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(WebSocket socket, String text) {
                      try {
                        packets.add(JSON.readTree(text));
                      } catch (IOException failure) {
                        throw new AssertionError(failure);
                      }
                    }
                  }));
      try (var client =
          Plowshare.connect(
              server.url("/").toString(),
              null,
              "held-credit",
              Duration.ofSeconds(5),
              null,
              Plowshare.TransportMode.SEGMENTED,
              Plowshare.DispatchMode.ORDERED)) {
        var first = client.requestAsync("admin.status", Map.of());
        assertNotNull(packets.poll(3, TimeUnit.SECONDS));
        var cancellation = new RequestCancellation();
        var queued =
            new ScheduleClient(client)
                .filesPageAsync(
                    "fixture", null, 50, new RequestOptions(Duration.ofSeconds(3), cancellation));
        cancellation.cancel();
        assertEquals(
            Plowshare.Delivery.NOT_SUBMITTED,
            assertInstanceOf(Plowshare.TransportException.class, failure(queued)).delivery());
        var expired =
            new ScheduleClient(client)
                .listPageAsync(
                    "fixture", null, 50, RequestOptions.defaults(Duration.ofMillis(100)));
        assertEquals(
            Plowshare.Delivery.NOT_SUBMITTED,
            assertInstanceOf(Plowshare.TransportException.class, failure(expired)).delivery());
        assertTrue(packets.isEmpty());
        client.closeAsync().toCompletableFuture().get(3, TimeUnit.SECONDS);
        assertEquals(
            Plowshare.Delivery.UNKNOWN,
            assertInstanceOf(Plowshare.TransportException.class, failure(first)).delivery());
      }
    }
  }

  @Test
  void preCancelledCallWritesNothing() throws Exception {
    try (var fixture = new Fixture();
        var client = fixture.connect(Plowshare.DispatchMode.ORDERED)) {
      var cancellation = new RequestCancellation();
      cancellation.cancel();
      var call =
          new AdministrationClient(client)
              .statusAsync(new RequestOptions(Duration.ofSeconds(3), cancellation));
      assertEquals(
          Plowshare.Delivery.NOT_SUBMITTED,
          assertInstanceOf(Plowshare.TransportException.class, failure(call)).delivery());
      assertTrue(fixture.requests.isEmpty());
    }
  }

  @Test
  void interruptedSynchronousFacadeDetachesItsExchangeAndLeavesFullCapacityReusable()
      throws Exception {
    try (var fixture = new Fixture();
        var client = fixture.connect(Plowshare.DispatchMode.ORDERED)) {
      var api = new AdministrationClient(client);
      var stopped = new CompletableFuture<Throwable>();
      var interruptRetained = new CompletableFuture<Boolean>();
      Thread caller =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      api.status();
                      stopped.completeExceptionally(
                          new AssertionError("Held request unexpectedly completed"));
                    } catch (IOException failure) {
                      interruptRetained.complete(Thread.currentThread().isInterrupted());
                      stopped.complete(failure);
                    }
                  });
      fixture.take();
      caller.interrupt();
      assertEquals(
          Plowshare.Delivery.UNKNOWN,
          assertInstanceOf(Plowshare.TransportException.class, stopped.get(3, TimeUnit.SECONDS))
              .delivery());
      assertTrue(interruptRetained.get(3, TimeUnit.SECONDS));
      // A complete 64-call batch must be accepted again. The interrupted call must not remain
      // registered until its original deadline, nor close the shared socket as cancellation.
      List<CompletionStage<AdministrationClient.Status>> replacement = new ArrayList<>();
      replacement.add(api.statusAsync());
      fixture.take();
      for (int i = 1; i < 64; i++) replacement.add(api.statusAsync());
      for (int i = 1; i < 64; i++) fixture.take();
      client.closeAsync().toCompletableFuture().get(3, TimeUnit.SECONDS);
      for (var call : replacement)
        assertEquals(
            Plowshare.Delivery.UNKNOWN,
            assertInstanceOf(Plowshare.TransportException.class, failure(call)).delivery());
    }
  }

  private static Throwable failure(CompletionStage<?> stage) throws Exception {
    var thrown =
        assertThrows(
            ExecutionException.class, () -> stage.toCompletableFuture().get(3, TimeUnit.SECONDS));
    Throwable cause = thrown.getCause();
    while (cause instanceof CompletionException && cause.getCause() != null)
      cause = cause.getCause();
    return cause;
  }

  private static final class Fixture implements AutoCloseable {
    final MockWebServer server = new MockWebServer();
    final BlockingQueue<JsonNode> requests = new ArrayBlockingQueue<>(128);
    volatile WebSocket socket;
    volatile int negotiations;

    Fixture() {
      server.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket opened, Response response) {
                      socket = opened;
                    }

                    @Override
                    public void onMessage(WebSocket opened, String text) {
                      try {
                        JsonNode request = JSON.readTree(text);
                        if (request.path("type").asText().equals("transport.dispatch")) {
                          negotiations++;
                          assertEquals(
                              "concurrent-v1", request.path("payload").path("mode").asText());
                          answer(
                              request,
                              Map.of("mode", "concurrent-v1", "maxPending", 64, "maxIdBytes", 128));
                        } else requests.add(request);
                      } catch (IOException failure) {
                        throw new AssertionError(failure);
                      }
                    }
                  }));
    }

    Plowshare connect(Plowshare.DispatchMode mode) throws Exception {
      return Plowshare.connectAsync(
              server.url("/").toString(),
              "fixture",
              "async-fixture",
              Duration.ofSeconds(5),
              null,
              Plowshare.TransportMode.LEGACY,
              mode)
          .toCompletableFuture()
          .get(3, TimeUnit.SECONDS);
    }

    JsonNode take() throws InterruptedException {
      JsonNode result = requests.poll(3, TimeUnit.SECONDS);
      assertNotNull(result, "Expected a submitted request");
      return result;
    }

    void answer(JsonNode request, Object payload) throws IOException {
      reply(request, "OK", payload);
    }

    void reply(JsonNode request, String code, Object payload) throws IOException {
      var outcome = new java.util.LinkedHashMap<String, Object>();
      outcome.put("code", code);
      if (payload != null) outcome.put("payload", payload);
      assertTrue(
          socket.send(
              JSON.writeValueAsString(
                  Map.of(
                      "id",
                      request.path("id").asText(),
                      "type",
                      request.path("type").asText(),
                      "protocol_version",
                      "plowshare-v1",
                      "payload",
                      outcome))));
    }

    @Override
    public void close() throws IOException {
      server.close();
    }
  }
}
