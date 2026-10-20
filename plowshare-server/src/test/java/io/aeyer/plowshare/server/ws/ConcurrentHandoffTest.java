package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.protocol.transport.PacketBudget;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ConcurrentHandoffTest {
  private Envelope request(String id) {
    return new Envelope(id, "relay.topics", Envelope.CURRENT_VERSION, Map.of());
  }

  @Test
  void early_delivery_keeps_one_handoff_slot_and_duplicate_guard_until_execution_settles()
      throws Exception {
    var completions = new ConcurrentHashMap<String, CompletableFuture<Void>>();
    for (int n = 0; n < 65; n++) completions.put("id-" + n, new CompletableFuture<>());
    var deliveries = new ConcurrentHashMap<String, ConcurrentRequests.Delivery>();
    var first = new CompletableFuture<ConcurrentRequests.Delivery>();
    var second = new CompletableFuture<ConcurrentRequests.Delivery>();
    var budget = new PacketBudget(1024 * 1024);
    try (var dispatcher = new ConcurrentRequests(budget, 2, 2)) {
      var connection =
          dispatcher.connect(
              "handoff",
              (id, delivery) -> {
                deliveries.put(id, delivery);
                if (id.equals("id-0")) first.complete(delivery);
                if (id.equals("id-1")) second.complete(delivery);
                return completions.get(id);
              },
              () -> fail("Unexpected execution failure"));
      for (int n = 0; n < 64; n++) connection.offer("id-" + n, request("id-" + n), "alice");
      var handoff = first.get(5, TimeUnit.SECONDS);
      var other = second.get(5, TimeUnit.SECONDS);
      handoff.started();
      handoff.run();
      assertFalse(handoff.executionFinished().toCompletableFuture().isDone());
      connection.offer("id-64", request("id-64"), "alice");
      assertThrows(
          IllegalArgumentException.class, () -> connection.offer("id-0", request("id-0"), "alice"));
      assertThrows(
          IllegalArgumentException.class,
          () -> connection.offer("overflow", request("overflow"), "alice"));
      assertThrows(IllegalStateException.class, other::started);
      completions.get("id-0").complete(null);
      handoff.executionFinished().toCompletableFuture().get(5, TimeUnit.SECONDS);
      connection.close();
      completions.values().forEach(completion -> completion.complete(null));
      dispatcher.stopAdmission();
      dispatcher.drained().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(0, budget.heldBytes());
    }
  }
}
