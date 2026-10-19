package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.transport.PacketBudget;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EventRequestsTest {
  @Test
  void closing_discards_queued_work_without_interrupting_an_operation_already_started()
      throws Exception {
    var budget = new PacketBudget(1024 * 1024);
    var entered = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    var done = new CountDownLatch(1);
    var routed = new ArrayList<String>();
    var requests =
        new EventRequests(
            budget,
            frame -> {
              routed.add(frame);
              entered.countDown();
              try {
                assertTrue(finish.await(5, TimeUnit.SECONDS));
              } catch (InterruptedException unexpected) {
                throw new AssertionError("Closing must not cancel an operation", unexpected);
              } finally {
                done.countDown();
              }
            });
    requests.start("close-fixture");
    requests.offer("first");
    assertTrue(entered.await(5, TimeUnit.SECONDS));
    try {
      requests.offer("queued");
      long withQueued = budget.heldBytes();
      requests.close();
      assertTrue(budget.heldBytes() > 0, "The running operation retains its reservation");
      assertTrue(budget.heldBytes() < withQueued, "Queued work releases its reservation");
      requests.offer("after-close");
    } finally {
      finish.countDown();
    }
    assertTrue(done.await(5, TimeUnit.SECONDS));
    // Wait for dispatcher cleanup, without depending on the worker's scheduling after its callback.
    for (int n = 0; n < 100 && budget.heldBytes() != 0; n++) Thread.sleep(5);
    assertEquals(0, budget.heldBytes());
    assertEquals(List.of("first"), routed);
  }

  @Test
  void process_budget_refuses_queue_growth_before_more_work_is_retained() throws Exception {
    var budget = new PacketBudget(200_000);
    var requests = new EventRequests(budget, ignored -> fail("Dispatcher has not started"));
    try {
      requests.offer("x".repeat(60_000));
      long reserved = budget.heldBytes();
      assertThrows(IllegalArgumentException.class, () -> requests.offer("y".repeat(60_000)));
      assertEquals(reserved, budget.heldBytes());
    } finally {
      requests.close();
    }
    assertEquals(0, budget.heldBytes());
  }
}
