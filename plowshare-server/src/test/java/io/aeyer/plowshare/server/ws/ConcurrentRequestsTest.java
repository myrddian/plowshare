package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.protocol.transport.PacketBudget;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ConcurrentRequestsTest {
  private static Envelope request(String id, String type, Map<String, Object> payload) {
    return new Envelope(id, type, Envelope.CURRENT_VERSION, payload);
  }

  @Test
  void independent_completion_overtakes_held_work_and_ids_stay_active_until_delivery()
      throws Exception {
    var budget = new PacketBudget(1024 * 1024);
    var telemetry = new DispatchTelemetry();
    try (var dispatcher = new ConcurrentRequests(budget, 2, 2, telemetry)) {
      var started = new ConcurrentHashMap<String, CompletableFuture<Void>>();
      var receipts = new ConcurrentHashMap<String, Runnable>();
      var firstStarted = new CompletableFuture<Void>();
      var secondStarted = new CompletableFuture<Void>();
      var connection =
          dispatcher.connect(
              "socket",
              (id, delivered) -> {
                var completion = new CompletableFuture<Void>();
                started.put(id, completion);
                receipts.put(id, delivered);
                (id.equals("first") ? firstStarted : secondStarted).complete(null);
                return completion;
              },
              () -> fail("Unexpected transport failure"));
      connection.offer("first", request("first", "information.facets", Map.of()), "alice");
      firstStarted.get(5, TimeUnit.SECONDS);
      connection.offer("second", request("second", "relay.topics", Map.of()), "alice");
      secondStarted.get(5, TimeUnit.SECONDS);
      started.get("second").complete(null);
      assertFalse(started.get("first").isDone());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              connection.offer("duplicate", request("second", "relay.topics", Map.of()), "alice"));
      receipts.get("second").run();
      started.get("first").complete(null);
      receipts.get("first").run();
      dispatcher.stopAdmission();
      dispatcher.drained().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(0, budget.heldBytes());
      var measured = telemetry.snapshot();
      assertEquals(2, measured.admitted());
      assertEquals(1, measured.duplicateIds());
      assertEquals(0, measured.active());
      assertEquals(0, measured.queued());
      assertEquals(0, measured.requestBytes());
      assertEquals(0, measured.resourceKeys());
      assertEquals(2, measured.execution().count());
    }
  }

  @Test
  void conflicts_span_connections_but_observers_do_not_hold_mutation_capacity() throws Exception {
    var budget = new PacketBudget(1024 * 1024);
    try (var dispatcher = new ConcurrentRequests(budget, 1, 1)) {
      var held = new CompletableFuture<Void>();
      var entered = new CompletableFuture<Void>();
      var next = new CompletableFuture<Void>();
      var observing = new CompletableFuture<Void>();
      var observerEntered = new CompletableFuture<Void>();
      var first =
          dispatcher.connect(
              "one",
              (id, delivered) -> {
                entered.complete(null);
                return held.thenRun(delivered);
              },
              () -> fail("Unexpected transport failure"));
      var second =
          dispatcher.connect(
              "two",
              (id, delivered) -> {
                next.complete(null);
                delivered.run();
                return CompletableFuture.completedFuture(null);
              },
              () -> fail("Unexpected transport failure"));
      var observer =
          dispatcher.connect(
              "observer",
              (id, delivered) -> {
                observerEntered.complete(null);
                return observing.thenRun(delivered);
              },
              () -> fail("Unexpected transport failure"));
      observer.offer("wait", request("wait", "information.await", Map.of()), "alice");
      observerEntered.get(5, TimeUnit.SECONDS);
      var payload = Map.<String, Object>of("conversation", "same");
      first.offer("first", request("first", "conversation.resume", payload), "alice");
      entered.get(5, TimeUnit.SECONDS);
      second.offer("second", request("second", "conversation.resume", payload), "bob");
      assertFalse(next.isDone());
      held.complete(null);
      next.get(5, TimeUnit.SECONDS);
      observing.complete(null);
      dispatcher.stopAdmission();
      dispatcher.drained().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(0, budget.heldBytes());
    }
  }

  @Test
  void acting_observer_does_not_hold_context_lane_and_close_orders_after_conflicting_work()
      throws Exception {
    var budget = new PacketBudget(1024 * 1024);
    try (var dispatcher = new ConcurrentRequests(budget, 1, 1)) {
      var context = java.util.UUID.randomUUID();
      var selection = new io.aeyer.plowshare.protocol.ActingContext.Selection(context);
      var observing = new CompletableFuture<Void>();
      var observed = new CompletableFuture<Void>();
      var mutation = new CompletableFuture<Void>();
      var mutated = new CompletableFuture<Void>();
      var closed = new CompletableFuture<Void>();
      var connection =
          dispatcher.connect(
              "socket",
              (id, delivered) ->
                  switch (id) {
                    case "observe" -> {
                      observed.complete(null);
                      yield observing.thenRun(delivered);
                    }
                    case "mutation" -> {
                      mutated.complete(null);
                      yield mutation.thenRun(delivered);
                    }
                    default -> {
                      closed.complete(null);
                      delivered.run();
                      yield CompletableFuture.completedFuture(null);
                    }
                  },
              () -> fail("Unexpected failure"));
      connection.offer(
          "observe",
          new Envelope(
              "observe", "information.await", Envelope.CURRENT_VERSION, Map.of(), selection),
          "actor");
      observed.get(5, TimeUnit.SECONDS);
      connection.offer(
          "mutation",
          new Envelope(
              "mutation", "acting.turn.start", Envelope.CURRENT_VERSION, Map.of(), selection),
          "actor");
      mutated.get(5, TimeUnit.SECONDS);
      connection.offer(
          "close",
          request("close", "acting.context.close", Map.of("context", context.toString())),
          "actor");
      assertFalse(closed.isDone());
      mutation.complete(null);
      closed.get(5, TimeUnit.SECONDS);
      assertFalse(observing.isDone());
      observing.complete(null);
      dispatcher.stopAdmission();
      dispatcher.drained().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(0, budget.heldBytes());
    }
  }

  @Test
  void close_discards_unstarted_work_and_retains_running_ownership_until_settled()
      throws Exception {
    var budget = new PacketBudget(1024 * 1024);
    try (var dispatcher = new ConcurrentRequests(budget, 1, 1)) {
      var held = new CompletableFuture<Void>();
      var entered = new CompletableFuture<Void>();
      var connection =
          dispatcher.connect(
              "socket",
              (id, delivered) -> {
                assertEquals("first", id);
                entered.complete(null);
                return held;
              },
              () -> fail("Unexpected transport failure"));
      connection.offer("first", request("first", "relay.log", Map.of()), "alice");
      entered.get(5, TimeUnit.SECONDS);
      connection.offer("second", request("second", "relay.log", Map.of()), "alice");
      connection.close();
      assertEquals(10, budget.heldBytes());
      dispatcher.stopAdmission();
      assertFalse(dispatcher.drained().toCompletableFuture().isDone());
      held.complete(null);
      dispatcher.drained().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(0, budget.heldBytes());
    }
  }
}
