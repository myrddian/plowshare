package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.reads.ReadDeadline;
import io.aeyer.plowshare.server.reads.ReadFailedException;
import io.aeyer.plowshare.server.reads.ReadUnavailableException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BoundedExternalConversationCompletionsTest {
  @Test
  void acceptedWorkRetainsItsSlotAndSuccessRegardlessOfAssemblyAge() throws Exception {
    try (var owner = new BoundedExternalConversationCompletions(1)) {
      var received = new LinkedBlockingQueue<Outcome>();
      var request =
          owner.begin(System.nanoTime() - Duration.ofSeconds(10).toNanos(), received::add);
      request.answer(
          request.within(
              () -> {
                assertTrue(ReadDeadline.currentDeadlineNanos().isEmpty());
                return Outcome.ok("completed");
              }));
      assertEquals("completed", received.poll(2, TimeUnit.SECONDS).payload());
      assertThrows(ReadUnavailableException.class, () -> owner.begin(received::add));
      request.answer(Outcome.ok("duplicate"));
      assertTrue(received.isEmpty());
      request.close();
      try (var next = owner.begin(received::add)) {
        next.answer(Outcome.ok("next"));
      }
      assertEquals("next", received.poll(2, TimeUnit.SECONDS).payload());
    }
  }

  @Test
  void blockedNotificationRetainsItsPhysicalSlotWithoutBlockingAnotherRequest() throws Exception {
    try (var owner = new BoundedExternalConversationCompletions(2)) {
      var entered = new CompletableFuture<Void>();
      var unblock = new CompletableFuture<Void>();
      var first =
          owner.begin(
              outcome -> {
                entered.complete(null);
                unblock.join();
              });
      Thread notification = Thread.ofVirtual().start(() -> first.answer(Outcome.ok("first")));
      try {
        entered.get(2, TimeUnit.SECONDS);
        first.close();
        var received = new LinkedBlockingQueue<Outcome>();
        var second = owner.begin(received::add);
        assertThrows(ReadUnavailableException.class, () -> owner.begin(ignored -> {}));
        second.answer(Outcome.ok("second"));
        second.close();
        assertEquals("second", received.poll(2, TimeUnit.SECONDS).payload());
      } finally {
        unblock.complete(null);
        notification.join(2000);
      }
      assertFalse(notification.isAlive());
      try (var freed = owner.begin(ignored -> {})) {
        freed.answer(Outcome.ok());
      }
    }
  }

  @Test
  void shutdownStopsIntakeWithoutReplacingAnAcceptedOutcome() {
    var owner = new BoundedExternalConversationCompletions(1);
    var received = new LinkedBlockingQueue<Outcome>();
    var request = owner.begin(received::add);
    owner.close();
    assertThrows(ReadFailedException.class, () -> owner.begin(received::add));
    assertTrue(received.isEmpty());
    request.answer(Outcome.ok("retained"));
    request.close();
    assertEquals("retained", received.remove().payload());
  }
}
