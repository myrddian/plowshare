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
      // The non-blocking form: the waiting form would park here rather than fail, which is what
      // a_request_at_the_window_bound_waits_instead_of_failing covers.
      assertEquals(
          Plowshare.Delivery.NOT_SUBMITTED,
          assertInstanceOf(
                  Plowshare.TransportException.class,
                  failure(api.statusAsync(RequestOptions.nonBlocking(Duration.ofSeconds(3)))))
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
      // The non-blocking form still fails such a call immediately, which is the escape a
      // continuation holding a permit has from parking behind itself.
      assertEquals(
          Plowshare.Delivery.NOT_SUBMITTED,
          assertInstanceOf(
                  Plowshare.TransportException.class,
                  failure(api.statusAsync(RequestOptions.nonBlocking(Duration.ofSeconds(3)))))
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
  void queuedCancellationDoesNotWaitForHeldSegmentCreditsAndQueuedTimeIsUncharged()
      throws Exception {
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
        // A request still in the writer queue has not been transmitted, so it has no deadline to
        // expire: its own allowance is unspent until the writer arms it at the write. Time held
        // behind a writer waiting on segment credit is charged against nothing, and a close is
        // what settles such a request.
        var queuedBehindCredit =
            new ScheduleClient(client)
                .listPageAsync(
                    "fixture", null, 50, RequestOptions.defaults(Duration.ofMillis(100)));
        Thread.sleep(400);
        assertFalse(
            queuedBehindCredit.toCompletableFuture().isDone(),
            "queued time was charged against the request deadline");
        assertTrue(packets.isEmpty());
        client.closeAsync().toCompletableFuture().get(3, TimeUnit.SECONDS);
        assertEquals(
            Plowshare.Delivery.NOT_SUBMITTED,
            assertInstanceOf(Plowshare.TransportException.class, failure(queuedBehindCredit))
                .delivery());
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

  @Test
  void a_request_at_the_window_bound_waits_instead_of_failing() throws Exception {
    try (var fixture = new Fixture(1);
        var client = fixture.connect(Plowshare.DispatchMode.CONCURRENT)) {
      var api = new AdministrationClient(client);
      var held = api.statusAsync().toCompletableFuture();
      var first = fixture.take();
      var parked = api.statusAsync().toCompletableFuture();
      assertNull(
          fixture.requests.poll(300, TimeUnit.MILLISECONDS),
          "a request at the window bound must park rather than over-submit");
      assertFalse(parked.isDone(), "a full window is flow control, not a fault");
      fixture.answer(first, Map.of("handle", "held", "serverAdmin", false));
      assertEquals("held", held.get(3, TimeUnit.SECONDS).handle());
      fixture.answer(fixture.take(), Map.of("handle", "parked", "serverAdmin", false));
      assertEquals("parked", parked.get(3, TimeUnit.SECONDS).handle());
    }
  }

  @Test
  void a_refused_request_is_resubmitted_under_the_same_id_and_the_caller_sees_a_reply()
      throws Exception {
    try (var fixture = new Fixture(1);
        var client = fixture.connect(Plowshare.DispatchMode.CONCURRENT)) {
      // Jitter pinned to its fixed half: the pause is 50ms and nothing here waits on chance.
      client.resubmitRandomness(() -> 0.0);
      var api = new AdministrationClient(client);
      var refused = api.statusAsync().toCompletableFuture();
      var first = fixture.take();
      // Parked for admission before the refusal. A refusal releases the dispatch count and
      // keeps the admission slot, so the order must be A, A, B and never A, B, A.
      var behind = api.statusAsync().toCompletableFuture();
      fixture.reply(first, "CAPACITY_EXHAUSTED", null);
      var retry = fixture.take();
      assertEquals(
          first.path("id").asText(),
          retry.path("id").asText(),
          "a refusal is resubmitted under the same request id");
      fixture.answer(retry, Map.of("handle", "retried", "serverAdmin", false));
      assertEquals("retried", refused.get(3, TimeUnit.SECONDS).handle());
      var last = fixture.take();
      assertNotEquals(first.path("id").asText(), last.path("id").asText());
      fixture.answer(last, Map.of("handle", "behind", "serverAdmin", false));
      assertEquals("behind", behind.get(3, TimeUnit.SECONDS).handle());
    }
  }

  @Test
  void repeated_refusals_end_in_the_callers_own_timeout_and_never_re_arm_the_deadline()
      throws Exception {
    try (var fixture = new Fixture(1);
        var client = fixture.connect(Plowshare.DispatchMode.CONCURRENT)) {
      fixture.refuseEverything = true;
      client.resubmitRandomness(() -> 0.0);
      long started = System.nanoTime();
      var call =
          client.requestAsync(
              "admin.status", Map.of(), RequestOptions.defaults(Duration.ofMillis(400)));
      var failure = assertInstanceOf(Plowshare.TransportException.class, failure(call));
      long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
      // The pauses are 50, 100, 200 and 400ms, so the 400ms deadline expires inside one of
      // them, never within 50ms of a transmit. A pause expiry is NOT_SUBMITTED exactly: the
      // refused attempt released its dispatch count and the server did none of the work.
      assertEquals(Plowshare.Delivery.NOT_SUBMITTED, failure.delivery(), failure.getMessage());
      assertTrue(failure.getMessage().contains("deadline"), failure.getMessage());
      assertTrue(
          elapsed < 1500, "the deadline was re-armed or extended by a resubmission: " + elapsed);
      assertTrue(fixture.requests.size() >= 2, "a refusal must be resubmitted at all");
    }
  }

  @Test
  void the_deadline_is_not_consumed_by_waiting_for_capacity() throws Exception {
    try (var fixture = new Fixture(1);
        var client = fixture.connect(Plowshare.DispatchMode.CONCURRENT)) {
      var api = new AdministrationClient(client);
      var held = api.statusAsync().toCompletableFuture();
      var first = fixture.take();
      var waiting =
          api.statusAsync(RequestOptions.defaults(Duration.ofMillis(150))).toCompletableFuture();
      // Four times its own timeout at the window bound. The deadline arms at the write, so
      // none of this is charged against the time the server is given to answer.
      Thread.sleep(600);
      assertFalse(waiting.isDone(), "the wait for capacity consumed the request deadline");
      fixture.answer(first, Map.of("handle", "held", "serverAdmin", false));
      assertEquals("held", held.get(3, TimeUnit.SECONDS).handle());
      fixture.answer(fixture.take(), Map.of("handle", "waiting", "serverAdmin", false));
      assertEquals("waiting", waiting.get(3, TimeUnit.SECONDS).handle());
    }
  }

  @Test
  void the_non_blocking_form_still_fails_immediately_with_NOT_SUBMITTED() throws Exception {
    try (var fixture = new Fixture(1);
        var client = fixture.connect(Plowshare.DispatchMode.CONCURRENT)) {
      var held = client.requestAsync("admin.status", Map.of());
      var first = fixture.take();
      var shed =
          client.requestAsync(
              "admin.status", Map.of(), RequestOptions.nonBlocking(Duration.ofSeconds(3)));
      assertEquals(
          Plowshare.Delivery.NOT_SUBMITTED,
          assertInstanceOf(Plowshare.TransportException.class, failure(shed)).delivery());
      fixture.answer(first, Map.of());
      assertTrue(held.toCompletableFuture().get(3, TimeUnit.SECONDS).successful());
      // The non-blocking form reports a refusal as an outcome rather than absorbing it.
      var surfaced =
          client.requestAsync(
              "admin.status", Map.of(), RequestOptions.nonBlocking(Duration.ofSeconds(3)));
      fixture.reply(fixture.take(), "CAPACITY_EXHAUSTED", null);
      assertEquals(
          "CAPACITY_EXHAUSTED", surfaced.toCompletableFuture().get(3, TimeUnit.SECONDS).code());
      assertNull(
          fixture.requests.poll(300, TimeUnit.MILLISECONDS),
          "the non-blocking form performs no resubmission");
    }
  }

  @Test
  void a_cancelled_wait_for_capacity_reports_NOT_SUBMITTED_and_frees_its_slot() throws Exception {
    try (var fixture = new Fixture(1);
        var client = fixture.connect(Plowshare.DispatchMode.CONCURRENT)) {
      var held = client.requestAsync("admin.status", Map.of());
      var first = fixture.take();
      var cancellation = new RequestCancellation();
      var parked =
          client.requestAsync(
              "admin.status", Map.of(), new RequestOptions(Duration.ofSeconds(3), cancellation));
      cancellation.cancel();
      var failure = assertInstanceOf(Plowshare.TransportException.class, failure(parked));
      assertEquals(Plowshare.Delivery.NOT_SUBMITTED, failure.delivery());
      assertTrue(failure.getMessage().contains("cancelled"), failure.getMessage());
      assertNull(
          fixture.requests.poll(200, TimeUnit.MILLISECONDS), "a cancelled wait writes nothing");
      fixture.answer(first, Map.of());
      assertTrue(held.toCompletableFuture().get(3, TimeUnit.SECONDS).successful());
      // Cancellation is never a way to leak capacity: the window admits again.
      var next = client.requestAsync("admin.status", Map.of());
      fixture.answer(fixture.take(), Map.of());
      assertTrue(next.toCompletableFuture().get(3, TimeUnit.SECONDS).successful());
    }
  }

  @Test
  void the_dispatch_negotiation_is_not_resubmitted_when_refused() throws Exception {
    try (var fixture = new Fixture(64, true)) {
      var opening = fixture.open(Plowshare.DispatchMode.CONCURRENT);
      assertInstanceOf(IOException.class, failure(opening));
      // A second dispatch selection on one connection is a protocol violation, so a refused
      // negotiation fails honestly instead of being paced and retried.
      Thread.sleep(400);
      assertEquals(1, fixture.negotiations, "a refused negotiation must never be resubmitted");
    }
  }

  @Test
  void callers_parked_for_capacity_are_released_when_the_connection_closes() throws Exception {
    try (var fixture = new Fixture(1);
        var client = fixture.connect(Plowshare.DispatchMode.CONCURRENT)) {
      var held = client.requestAsync("admin.status", Map.of());
      fixture.take();
      var parked = client.requestAsync("admin.status", Map.of()).toCompletableFuture();
      assertFalse(parked.isDone());
      // A parked caller has no deadline of its own, so a close is what must release it.
      var drained = client.closeAsync();
      var failure = assertInstanceOf(Plowshare.TransportException.class, failure(parked));
      assertEquals(Plowshare.Delivery.NOT_SUBMITTED, failure.delivery());
      assertInstanceOf(Plowshare.TransportException.class, failure(held));
      drained.toCompletableFuture().get(3, TimeUnit.SECONDS);
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

    /** Answers every application frame CAPACITY_EXHAUSTED as it arrives, after recording it. */
    volatile boolean refuseEverything;

    Fixture() {
      this(64, false);
    }

    Fixture(int maxPending) {
      this(maxPending, false);
    }

    Fixture(int maxPending, boolean refuseNegotiation) {
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
                          if (refuseNegotiation) reply(request, "CAPACITY_EXHAUSTED", null);
                          else
                            answer(
                                request,
                                Map.of(
                                    "mode",
                                    "concurrent-v1",
                                    "maxPending",
                                    maxPending,
                                    "maxIdBytes",
                                    128));
                          return;
                        }
                        requests.add(request);
                        if (refuseEverything) reply(request, "CAPACITY_EXHAUSTED", null);
                      } catch (IOException failure) {
                        throw new AssertionError(failure);
                      }
                    }
                  }));
    }

    CompletionStage<Plowshare> open(Plowshare.DispatchMode mode) {
      return Plowshare.connectAsync(
          server.url("/").toString(),
          "fixture",
          "async-fixture",
          Duration.ofSeconds(5),
          null,
          Plowshare.TransportMode.LEGACY,
          mode);
    }

    Plowshare connect(Plowshare.DispatchMode mode) throws Exception {
      return open(mode).toCompletableFuture().get(3, TimeUnit.SECONDS);
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
