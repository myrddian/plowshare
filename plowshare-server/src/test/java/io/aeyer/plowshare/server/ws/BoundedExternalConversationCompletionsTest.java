package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BoundedExternalConversationCompletionsTest {
  @Test
  void timeoutSelectsOnceAndRetainsItsSlotUntilPhysicalCompletion() throws Exception {
    try (var owner = new BoundedExternalConversationCompletions(Duration.ofMillis(30), 1)) {
      var received = new LinkedBlockingQueue<Outcome>();
      var request = owner.begin(received::add);
      Outcome timeout = received.poll(2, TimeUnit.SECONDS);
      assertNotNull(timeout);
      assertEquals(Code.ARCHIVE_UNAVAILABLE, timeout.code());
      assertEquals(null, timeout.payload());
      assertTrue(timeout.said().contains("do not replay"));
      assertFalse(timeout.said().contains("Nothing was read"));
      assertFalse(timeout.said().contains("nothing was written"));
      request.answer(Outcome.ok("late"));
      assertThrows(ArchiveUnavailableException.class, () -> owner.begin(received::add));
      assertTrue(received.isEmpty());
      request.close();
      try (var next = owner.begin(received::add)) {
        next.answer(Outcome.ok("next"));
      }
      assertEquals("next", received.poll(2, TimeUnit.SECONDS).payload());
    }
  }

  @Test
  void blockedNotificationCannotBlockAnotherAlarmOrReleaseItsRetainedSlot() throws Exception {
    try (var owner = new BoundedExternalConversationCompletions(Duration.ofMillis(30), 2)) {
      var entered = new CompletableFuture<Thread>();
      var unblock = new CompletableFuture<Void>();
      var first =
          owner.begin(
              outcome -> {
                entered.complete(Thread.currentThread());
                unblock.join();
              });
      Thread notification = entered.get(2, TimeUnit.SECONDS);
      assertTrue(notification.isVirtual());
      assertFalse(notification.getName().startsWith("external-conversation-deadline-"));
      var secondReply = new LinkedBlockingQueue<Outcome>();
      var second = owner.begin(secondReply::add);
      assertEquals(Code.ARCHIVE_UNAVAILABLE, secondReply.poll(2, TimeUnit.SECONDS).code());
      first.close();
      second.close();
      // The blocked notifier still owns one slot even after its original route has finished.
      var third = owner.begin(ignored -> {});
      assertThrows(ArchiveUnavailableException.class, () -> owner.begin(ignored -> {}));
      third.close();
      unblock.complete(null);
      notification.join(2000);
      assertFalse(notification.isAlive());
      try (var freed = owner.begin(ignored -> {})) {
        freed.answer(Outcome.ok("finished"));
      }
    }
  }

  @Test
  void earlyAnswerCancelsTimeoutAndRestoresCallerThreadContext() throws Exception {
    try (var owner = new BoundedExternalConversationCompletions(Duration.ofSeconds(1), 1)) {
      var received = new LinkedBlockingQueue<Outcome>();
      var execution = new AtomicReference<Thread>();
      try (var request = owner.begin(received::add)) {
        request.answer(
            request.within(
                () -> {
                  execution.set(Thread.currentThread());
                  return Outcome.ok("early");
                }));
      }
      assertEquals(Thread.currentThread(), execution.get());
      assertEquals("early", received.poll(2, TimeUnit.SECONDS).payload());
      assertTrue(received.isEmpty());
    }
  }
}
