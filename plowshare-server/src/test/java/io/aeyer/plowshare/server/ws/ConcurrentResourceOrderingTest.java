package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.protocol.transport.PacketBudget;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConcurrentResourceOrderingTest {
  private Envelope request(String id, String conversation) {
    return new Envelope(
        id, "conversation.resume", Envelope.CURRENT_VERSION, Map.of("conversation", conversation));
  }

  @Test
  void conflict_waits_across_connections_while_unrelated_equal_hash_key_overtakes()
      throws Exception {
    assertEquals("Aa".hashCode(), "BB".hashCode());
    var budget = new PacketBudget(1024 * 1024);
    var telemetry = new DispatchTelemetry();
    try (var dispatcher = new ConcurrentRequests(budget, 1, 2, telemetry)) {
      var release = new CompletableFuture<Void>();
      var firstEntered = new CompletableFuture<Void>();
      var secondEntered = new CompletableFuture<Void>();
      var unrelatedEntered = new CompletableFuture<Void>();
      var first =
          dispatcher.connect(
              "one",
              (frame, delivered) -> {
                firstEntered.complete(null);
                return release.thenRun(delivered);
              },
              () -> fail("Unexpected failure"));
      var second =
          dispatcher.connect(
              "two",
              (frame, delivered) -> {
                secondEntered.complete(null);
                delivered.run();
                return CompletableFuture.completedFuture(null);
              },
              () -> fail("Unexpected failure"));
      var unrelated =
          dispatcher.connect(
              "three",
              (frame, delivered) -> {
                unrelatedEntered.complete(null);
                delivered.run();
                return CompletableFuture.completedFuture(null);
              },
              () -> fail("Unexpected failure"));
      first.offer("first", request("first", "Aa"), "alice");
      firstEntered.get(5, TimeUnit.SECONDS);
      second.offer("second", request("second", "Aa"), "bob");
      unrelated.offer("unrelated", request("unrelated", "BB"), "alice");
      unrelatedEntered.get(5, TimeUnit.SECONDS);
      assertFalse(secondEntered.isDone());
      release.complete(null);
      secondEntered.get(5, TimeUnit.SECONDS);
      dispatcher.stopAdmission();
      dispatcher.drained().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(0, telemetry.snapshot().resourceKeys());
      assertTrue(telemetry.snapshot().contentions() >= 1);
      assertEquals(0, budget.heldBytes());
    }
  }

  @Test
  void running_and_queued_requests_share_the_advertised_pending_bound() throws Exception {
    var budget = new PacketBudget(1024 * 1024);
    var telemetry = new DispatchTelemetry();
    try (var dispatcher = new ConcurrentRequests(budget, 1, 2, telemetry)) {
      var entered = new CompletableFuture<Void>();
      var release = new CompletableFuture<Void>();
      var workers = new AtomicInteger();
      var connection =
          dispatcher.connect(
              "bounded",
              (frame, delivered) -> {
                workers.incrementAndGet();
                entered.complete(null);
                return release;
              },
              () -> fail("Unexpected failure"));
      connection.offer("x", request("first", "same"), "alice");
      entered.get(5, TimeUnit.SECONDS);
      for (int n = 1; n < 64; n++) connection.offer("x", request("id-" + n, "same"), "alice");
      assertThrows(
          IllegalArgumentException.class,
          () -> connection.offer("x", request("overflow", "same"), "alice"));
      assertEquals(1, workers.get());
      assertEquals(128, budget.heldBytes());
      connection.close();
      assertEquals(2, budget.heldBytes());
      release.complete(null);
      dispatcher.stopAdmission();
      dispatcher.drained().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(0, telemetry.snapshot().queued());
      assertEquals(0, telemetry.snapshot().resourceKeys());
      assertEquals(1, telemetry.snapshot().rejected());
      assertEquals(0, budget.heldBytes());
    }
  }
}
