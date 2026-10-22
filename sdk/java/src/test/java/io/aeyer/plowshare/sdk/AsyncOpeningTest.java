package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.*;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

class AsyncOpeningTest {
  @Test
  void lazyOpeningIsSharedAndCancellingOneWaiterDoesNotCancelSibling() throws Exception {
    var release = new CountDownLatch(1);
    var received = new CountDownLatch(1);
    try (var server = heldUpgrade(received, release);
        var client = ordered(server)) {
      AsyncServerClient capability = client;
      var cancellation = new RequestCancellation();
      var detached =
          capability.connectionAsync(new RequestOptions(Duration.ofSeconds(3), cancellation));
      var retained = capability.connectionAsync();
      assertTrue(received.await(3, TimeUnit.SECONDS));
      assertFalse(retained.toCompletableFuture().isDone());
      cancellation.cancel();
      assertEquals(Plowshare.Delivery.NOT_SUBMITTED, failure(detached).delivery());
      release.countDown();
      var connected = retained.toCompletableFuture().get(3, TimeUnit.SECONDS);
      assertSame(
          connected, capability.connectionAsync().toCompletableFuture().get(3, TimeUnit.SECONDS));
      assertEquals(1, server.getRequestCount());
    } finally {
      release.countDown();
    }
  }

  @Test
  void openingDeadlineDetachesWithoutAbortingSharedUpgrade() throws Exception {
    var release = new CountDownLatch(1);
    var received = new CountDownLatch(1);
    try (var server = heldUpgrade(received, release);
        var client = ordered(server)) {
      var shortWait = client.connectionAsync(RequestOptions.defaults(Duration.ofMillis(100)));
      var sibling = client.connectionAsync();
      assertTrue(received.await(3, TimeUnit.SECONDS));
      assertEquals(Plowshare.Delivery.NOT_SUBMITTED, failure(shortWait).delivery());
      release.countDown();
      assertTrue(sibling.toCompletableFuture().get(3, TimeUnit.SECONDS).connected());
      assertEquals(1, server.getRequestCount());
    } finally {
      release.countDown();
    }
  }

  @Test
  void openingWaitersAreBoundedAndCloseFencesLateUpgradePublication() throws Exception {
    var release = new CountDownLatch(1);
    var received = new CountDownLatch(1);
    try (var server = heldUpgrade(received, release)) {
      var client = ordered(server);
      try {
        var calls = new ArrayList<CompletionStage<Plowshare>>();
        for (int i = 0; i < 64; i++) calls.add(client.connectionAsync());
        assertTrue(received.await(3, TimeUnit.SECONDS));
        assertEquals(
            Plowshare.Delivery.NOT_SUBMITTED, failure(client.connectionAsync()).delivery());
        client.close();
        release.countDown();
        for (var call : calls)
          assertEquals(Plowshare.Delivery.NOT_SUBMITTED, failure(call).delivery());
        assertEquals(
            Plowshare.Delivery.NOT_SUBMITTED, failure(client.connectionAsync()).delivery());
        assertEquals(1, server.getRequestCount());
      } finally {
        client.close();
      }
    } finally {
      release.countDown();
    }
  }

  @Test
  void concurrentModeRefusalDoesNotSubmitAnyDomainOperationOrDowngrade() throws Exception {
    try (var server = new MockWebServer()) {
      var messages = new java.util.concurrent.atomic.AtomicInteger();
      server.enqueue(
          new MockResponse()
              .withWebSocketUpgrade(
                  new WebSocketListener() {
                    @Override
                    public void onMessage(okhttp3.WebSocket socket, String text) {
                      messages.incrementAndGet();
                      try {
                        var frame = SdkJson.mapper().readTree(text);
                        assertEquals("transport.dispatch", frame.path("type").asText());
                        socket.send(
                            SdkJson.mapper()
                                .writeValueAsString(
                                    java.util.Map.of(
                                        "id",
                                        frame.path("id").asText(),
                                        "type",
                                        "transport.dispatch",
                                        "protocol_version",
                                        "plowshare-v1",
                                        "payload",
                                        java.util.Map.of("code", "NOT_FOUND"))));
                      } catch (IOException invalid) {
                        throw new AssertionError(invalid);
                      }
                    }
                  }));
      var opening =
          Plowshare.connectAsync(
              server.url("/").toString(),
              "fixture",
              "refused",
              Duration.ofSeconds(3),
              null,
              Plowshare.TransportMode.LEGACY,
              Plowshare.DispatchMode.CONCURRENT);
      assertEquals(Plowshare.Delivery.NOT_SUBMITTED, failure(opening).delivery());
      assertEquals(1, messages.get());
      assertEquals(1, server.getRequestCount());
    }
  }

  @Test
  void concurrentModeRejectsStringOrOverflowedControlLimits() throws Exception {
    for (Object limit : java.util.List.of("64", 4294967360L)) {
      try (var server = new MockWebServer()) {
        server.enqueue(
            new MockResponse()
                .withWebSocketUpgrade(
                    new WebSocketListener() {
                      @Override
                      public void onMessage(okhttp3.WebSocket socket, String text) {
                        try {
                          var frame = SdkJson.mapper().readTree(text);
                          assertEquals("transport.dispatch", frame.path("type").asText());
                          socket.send(
                              SdkJson.mapper()
                                  .writeValueAsString(
                                      java.util.Map.of(
                                          "id",
                                          frame.path("id").asText(),
                                          "type",
                                          "transport.dispatch",
                                          "protocol_version",
                                          "plowshare-v1",
                                          "payload",
                                          java.util.Map.of(
                                              "code",
                                              "OK",
                                              "payload",
                                              java.util.Map.of(
                                                  "mode",
                                                  "concurrent-v1",
                                                  "maxPending",
                                                  limit,
                                                  "maxIdBytes",
                                                  128)))));
                        } catch (IOException invalid) {
                          throw new AssertionError(invalid);
                        }
                      }
                    }));
        var opening =
            Plowshare.connectAsync(
                server.url("/").toString(),
                "fixture",
                "bad-limits",
                Duration.ofSeconds(3),
                null,
                Plowshare.TransportMode.LEGACY,
                Plowshare.DispatchMode.CONCURRENT);
        assertEquals(Plowshare.Delivery.NOT_SUBMITTED, failure(opening).delivery());
        assertEquals(1, server.getRequestCount());
      }
    }
  }

  @Test
  void a_lower_advertised_depth_is_accepted_and_bounds_admission() throws Exception {
    try (var server = acknowledging(4)) {
      var client = connectConcurrent(server).toCompletableFuture().get(3, TimeUnit.SECONDS);
      try {
        // The server never answers, so every admitted request stays outstanding.
        var outstanding = new ArrayList<CompletionStage<Plowshare.Reply>>();
        for (int i = 0; i < 4; i++)
          outstanding.add(client.requestAsync("admin.status", java.util.Map.of()));
        for (var request : outstanding) assertFalse(request.toCompletableFuture().isDone());
        // The negotiated depth, not the protocol ceiling of 64, is what bounds admission.
        var fifth = client.requestAsync("admin.status", java.util.Map.of());
        assertEquals(Plowshare.Delivery.NOT_SUBMITTED, failure(fifth).delivery());
      } finally {
        client.closeAsync();
      }
    }
  }

  @Test
  void advertised_depth_boundaries_are_one_to_the_protocol_ceiling() throws Exception {
    for (int accepted : new int[] {1, 64}) {
      try (var server = acknowledging(accepted)) {
        var client = connectConcurrent(server).toCompletableFuture().get(3, TimeUnit.SECONDS);
        client.closeAsync();
      }
    }
    for (int rejected : new int[] {0, 65}) {
      try (var server = acknowledging(rejected)) {
        assertEquals(
            Plowshare.Delivery.NOT_SUBMITTED, failure(connectConcurrent(server)).delivery());
      }
    }
  }

  private static CompletionStage<Plowshare> connectConcurrent(MockWebServer server) {
    return Plowshare.connectAsync(
        server.url("/").toString(),
        "fixture",
        "depth",
        Duration.ofSeconds(3),
        null,
        Plowshare.TransportMode.LEGACY,
        Plowshare.DispatchMode.CONCURRENT);
  }

  /** Acknowledges the dispatch handshake with {@code maxPending} and ignores every later frame. */
  private static MockWebServer acknowledging(int maxPending) throws IOException {
    var server = new MockWebServer();
    server.enqueue(
        new MockResponse()
            .withWebSocketUpgrade(
                new WebSocketListener() {
                  @Override
                  public void onMessage(okhttp3.WebSocket socket, String text) {
                    try {
                      var frame = SdkJson.mapper().readTree(text);
                      if (!"transport.dispatch".equals(frame.path("type").asText())) return;
                      socket.send(
                          SdkJson.mapper()
                              .writeValueAsString(
                                  java.util.Map.of(
                                      "id",
                                      frame.path("id").asText(),
                                      "type",
                                      "transport.dispatch",
                                      "protocol_version",
                                      "plowshare-v1",
                                      "payload",
                                      java.util.Map.of(
                                          "code",
                                          "OK",
                                          "payload",
                                          java.util.Map.of(
                                              "mode",
                                              "concurrent-v1",
                                              "maxPending",
                                              maxPending,
                                              "maxIdBytes",
                                              128)))));
                    } catch (IOException invalid) {
                      throw new AssertionError(invalid);
                    }
                  }
                }));
    return server;
  }

  private static MockWebServer heldUpgrade(CountDownLatch received, CountDownLatch release) {
    var server = new MockWebServer();
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
            received.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {});
          }
        });
    return server;
  }

  private static WsServerClient ordered(MockWebServer server) {
    return new WsServerClient(
        server.url("/").toString(),
        "fixture",
        Plowshare.TransportMode.LEGACY,
        Plowshare.DispatchMode.ORDERED);
  }

  private static Plowshare.TransportException failure(CompletionStage<?> stage) throws Exception {
    var failed =
        assertThrows(
            ExecutionException.class, () -> stage.toCompletableFuture().get(3, TimeUnit.SECONDS));
    Throwable cause = failed.getCause();
    while (cause instanceof CompletionException && cause.getCause() != null)
      cause = cause.getCause();
    return assertInstanceOf(Plowshare.TransportException.class, cause);
  }
}
