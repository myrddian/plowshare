package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.protocol.transport.ConcurrentDispatch;
import io.aeyer.plowshare.protocol.transport.PacketBudget;
import io.aeyer.plowshare.protocol.transport.SegmentedMessages;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;

class ConcurrentRequestFailureTest {
  @Test
  void deferred_fatal_error_releases_ownership_and_reaches_uncaught_error_owner() throws Exception {
    var registered = new CompletableFuture<Void>();
    var deferred =
        new CompletableFuture<Void>() {
          @Override
          public CompletableFuture<Void> whenComplete(
              BiConsumer<? super Void, ? super Throwable> callback) {
            var result = super.whenComplete(callback);
            registered.complete(null);
            return result;
          }
        };
    var failed = new CompletableFuture<Void>();
    var reported = new CompletableFuture<Throwable>();
    var budget = new PacketBudget(1024 * 1024);
    try (var dispatcher = new ConcurrentRequests(budget, 1, 1)) {
      var connection =
          dispatcher.connect(
              "fatal", (frame, delivered) -> deferred, () -> failed.complete(null), 1);
      connection.offer(
          "fatal",
          new Envelope("fatal", "relay.topics", Envelope.CURRENT_VERSION, Map.of()),
          "alice");
      registered.get(5, TimeUnit.SECONDS);
      var error = new AssertionError("Controlled deferred failure");
      Thread.ofVirtual()
          .uncaughtExceptionHandler((thread, failure) -> reported.complete(failure))
          .start(() -> deferred.completeExceptionally(new CompletionException(error)));
      failed.get(5, TimeUnit.SECONDS);
      assertSame(error, reported.get(5, TimeUnit.SECONDS));
      dispatcher.stopAdmission();
      dispatcher.drained().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(0, budget.heldBytes());
    }
  }

  /** A router whose {@code information.facets} blocks while the payload says {@code slow}. */
  private static FrameRouter blockingRouter(
      CompletableFuture<Void> release, java.util.concurrent.atomic.AtomicInteger entered) {
    return new FrameRouter(
        Map.of(
            "information.facets",
            (payload, asking) -> {
              if (payload.get("slow") == Boolean.TRUE) {
                entered.incrementAndGet();
                release.join();
              }
              return Outcome.ok(Map.of("done", true));
            }));
  }

  private static void awaitEntered(java.util.concurrent.atomic.AtomicInteger entered, int count)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (entered.get() < count && System.nanoTime() < deadline) Thread.sleep(5);
    assertTrue(entered.get() >= count, "Expected " + count + " handlers to start");
  }

  private static void select(ConcurrentEventChannelTest.Fixture fixture) throws Exception {
    fixture.send("select", ConcurrentDispatch.TYPE, Map.of("mode", ConcurrentDispatch.MODE));
    assertEquals("select", FrameJson.answering().readTree(fixture.reply()).path("id").asText());
  }

  @Test
  void a_full_connection_queue_is_answered_not_disconnected() throws Exception {
    var release = new CompletableFuture<Void>();
    var entered = new java.util.concurrent.atomic.AtomicInteger();
    try (var fixture =
        new ConcurrentEventChannelTest.Fixture(
            false, blockingRouter(release, entered), handler -> handler.useRequestDepth(2))) {
      select(fixture);
      fixture.send("one", "information.facets", Map.of("slow", true));
      fixture.send("two", "information.facets", Map.of("slow", true));
      awaitEntered(entered, 2);
      fixture.send("three", "information.facets", Map.of());
      var refusal = FrameJson.answering().readTree(fixture.reply());
      assertEquals("three", refusal.path("id").asText());
      assertEquals(
          "CAPACITY_EXHAUSTED", refusal.path("payload").path("code").asText(), refusal.toString());
      assertTrue(fixture.open.get(), "A refusal must leave the connection open");
      verify(fixture.socket, never()).close(any(CloseStatus.class));

      // The refused request owns nothing, so the same id is accepted once there is room.
      release.complete(null);
      var finished = new java.util.HashSet<String>();
      for (int n = 0; n < 2; n++)
        finished.add(FrameJson.answering().readTree(fixture.reply()).path("id").asText());
      assertEquals(java.util.Set.of("one", "two"), finished);
      fixture.send("three", "information.facets", Map.of());
      var retried = FrameJson.answering().readTree(fixture.reply());
      assertEquals("three", retried.path("id").asText());
      assertNotEquals("CAPACITY_EXHAUSTED", retried.path("payload").path("code").asText());
    } finally {
      release.complete(null);
    }
  }

  @Test
  void a_refusal_while_a_reply_is_being_written_is_answered_and_leaves_the_connection_open()
      throws Exception {
    // Scope: this proves a refusal arriving while the writer is blocked mid-send is still answered
    // and never disconnects. It does NOT prove the handoff slot is untouched: with three requests
    // active the depth of 2 would refuse regardless of the handoff exemption. That the refusal
    // takes no slot holds because refuseCongested writes through Delivery.answer and never calls
    // ConcurrentRequests.handoff(); a refused request has no Request object to hand off, so the
    // buggy variant cannot be written against this API. Verified by inspection, not by this test.
    var release = new CompletableFuture<Void>();
    var writerHeld = new CompletableFuture<Void>();
    var writerGate = new CompletableFuture<Void>();
    var entered = new java.util.concurrent.atomic.AtomicInteger();
    try (var fixture =
        new ConcurrentEventChannelTest.Fixture(
            false, blockingRouter(release, entered), handler -> handler.useRequestDepth(2))) {
      select(fixture);
      // Hold the writer inside the first reply's send: that reply's handoff slot stays taken.
      fixture.beforeWrite =
          () -> {
            writerHeld.complete(null);
            writerGate.join();
          };
      fixture.send("quick", "information.facets", Map.of());
      writerHeld.get(5, TimeUnit.SECONDS);
      fixture.send("one", "information.facets", Map.of("slow", true));
      awaitEntered(entered, 1);
      // quick is handing off (exempt from the depth), one is running, two fills the window.
      fixture.send("two", "information.facets", Map.of("slow", true));
      awaitEntered(entered, 2);
      fixture.send("refused", "information.facets", Map.of());
      assertTrue(fixture.open.get(), "A refusal during a handoff must not disconnect");
      writerGate.complete(null);

      var ids = new java.util.HashMap<String, String>();
      for (int n = 0; n < 2; n++) {
        var reply = FrameJson.answering().readTree(fixture.reply());
        ids.put(reply.path("id").asText(), reply.path("payload").path("code").asText());
      }
      assertEquals("CAPACITY_EXHAUSTED", ids.get("refused"));
      assertTrue(ids.containsKey("quick"));
      assertTrue(fixture.open.get());
    } finally {
      writerGate.complete(null);
      release.complete(null);
    }
  }

  @Test
  void a_malformed_frame_still_disconnects() throws Exception {
    try (var fixture = new ConcurrentEventChannelTest.Fixture(false, new FrameRouter(Map.of()))) {
      select(fixture);
      fixture.handler.handleTextMessage(fixture.socket, new TextMessage("{not json"));
      verify(fixture.socket, timeout(5000)).close(any(CloseStatus.class));
    }
  }

  @Test
  void a_duplicate_active_id_still_disconnects() throws Exception {
    var release = new CompletableFuture<Void>();
    var entered = new java.util.concurrent.atomic.AtomicInteger();
    try (var fixture =
        new ConcurrentEventChannelTest.Fixture(false, blockingRouter(release, entered))) {
      select(fixture);
      fixture.send("same", "information.facets", Map.of("slow", true));
      awaitEntered(entered, 1);
      fixture.send("same", "information.facets", Map.of("slow", true));
      verify(fixture.socket, timeout(5000)).close(any(CloseStatus.class));
    } finally {
      release.complete(null);
    }
  }

  @Test
  void a_refused_offer_owns_nothing_and_its_id_can_be_offered_again() throws Exception {
    var completions = new java.util.concurrent.ConcurrentHashMap<String, CompletableFuture<Void>>();
    var budget = new PacketBudget(1024 * 1024);
    try (var dispatcher = new ConcurrentRequests(budget, 2, 2)) {
      var connection =
          dispatcher.connect(
              "depth",
              (frame, delivered) -> {
                // Delivery is what releases a request's admission slot.
                delivered.run();
                return completions.computeIfAbsent(frame, key -> new CompletableFuture<>());
              },
              () -> fail("Unexpected execution failure"),
              1);
      Envelope first = new Envelope("a", "relay.topics", Envelope.CURRENT_VERSION, Map.of());
      Envelope second = new Envelope("b", "relay.topics", Envelope.CURRENT_VERSION, Map.of());
      connection.offer("a", first, "alice");
      long held = budget.heldBytes();
      assertThrows(
          ConcurrentRequests.CapacityExceededException.class,
          () -> connection.offer("b", second, "alice"));
      assertEquals(held, budget.heldBytes(), "A refusal must not retain a reservation");
      completions.computeIfAbsent("a", key -> new CompletableFuture<>()).complete(null);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (true) {
        try {
          connection.offer("b", second, "alice");
          break;
        } catch (ConcurrentRequests.CapacityExceededException stillFull) {
          assertTrue(System.nanoTime() < deadline, "Capacity never freed");
          Thread.sleep(5);
        }
      }
      completions.computeIfAbsent("b", key -> new CompletableFuture<>()).complete(null);
      connection.close();
      dispatcher.stopAdmission();
      dispatcher.drained().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(0, budget.heldBytes());
    }
  }

  @Test
  void shared_packet_memory_exhaustion_is_answered_not_disconnected() throws Exception {
    var release = new CompletableFuture<Void>();
    var entered = new java.util.concurrent.atomic.AtomicInteger();
    // Leave room for small replies but not for a request carrying a large body.
    var shared = new PacketBudget(3L * SegmentedMessages.CHUNK_BYTES);
    shared.acquire(3L * SegmentedMessages.CHUNK_BYTES - 3_000);
    try (var fixture =
        new ConcurrentEventChannelTest.Fixture(
            false, blockingRouter(release, entered), handler -> handler.usePacketBudget(shared))) {
      select(fixture);
      fixture.send("big", "information.facets", Map.of("pad", "x".repeat(2_000)));
      var refusal = FrameJson.answering().readTree(fixture.reply());
      assertEquals("big", refusal.path("id").asText());
      assertEquals("CAPACITY_EXHAUSTED", refusal.path("payload").path("code").asText());
      assertTrue(fixture.open.get(), "Memory pressure is congestion and must not disconnect");
      verify(fixture.socket, never()).close(any(CloseStatus.class));
      assertEquals(0, entered.get());
    } finally {
      release.complete(null);
    }
  }

  @Test
  void a_budget_refusal_owns_nothing_and_its_id_can_be_offered_again() throws Exception {
    long limit = 3L * SegmentedMessages.CHUNK_BYTES;
    var budget = new PacketBudget(limit);
    try (var dispatcher = new ConcurrentRequests(budget, 2, 2)) {
      var connection =
          dispatcher.connect(
              "memory",
              (frame, delivered) -> {
                delivered.run();
                return CompletableFuture.completedFuture(null);
              },
              () -> fail("Unexpected execution failure"),
              2);
      budget.acquire(limit);
      Envelope request = new Envelope("a", "relay.topics", Envelope.CURRENT_VERSION, Map.of());
      assertThrows(
          ConcurrentRequests.CapacityExceededException.class,
          () -> connection.offer("a", request, "alice"));
      assertEquals(limit, budget.heldBytes());
      budget.release(limit);
      connection.offer("a", request, "alice");
      connection.close();
      dispatcher.stopAdmission();
      dispatcher.drained().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(0, budget.heldBytes());
    }
  }
}
