package io.aeyer.plowshare.server.reads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class InteractiveReadAdmissionTest {
  private static BoundedReadAdmission admission(
      int global, int principal, int queue, int each, int millis) {
    return new BoundedReadAdmission(
        new ReadCapacityProperties(global, principal, 2, 1, 8, 2),
        new ReadSchedulingProperties(64, 4, 2000, 2000, queue, each, millis));
  }

  @Test
  void normalBurstsWaitInEligibleOrderWithoutBlockingAnotherAccount() throws Exception {
    var admission = admission(2, 1, 8, 4, 2000);
    var held = admission.admit("alice", ReadAdmission.Work.INTERACTIVE);
    var order = new CopyOnWriteArrayList<String>();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (held;
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          callers.submit(
              () -> {
                var lease = admission.awaitInteractive("alice");
                try (lease) {
                  order.add("first");
                  entered.countDown();
                  assertTrue(release.await(5, TimeUnit.SECONDS));
                }
                return true;
              });
      awaitWaiting(admission, 1);
      var bob = admission.awaitInteractive("bob");
      try (bob) {
        assertEquals(2, admission.active(ReadAdmission.Work.INTERACTIVE));
      }
      var second =
          callers.submit(
              () -> {
                var lease = admission.awaitInteractive("alice");
                try (lease) {
                  order.add("second");
                }
                return true;
              });
      awaitWaiting(admission, 2);
      held.close();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertEquals(List.of("first"), order);
      assertThrows(
          ReadUnavailableException.class,
          () -> admission.admit("alice", ReadAdmission.Work.INTERACTIVE));
      release.countDown();
      assertTrue(first.get(5, TimeUnit.SECONDS));
      assertTrue(second.get(5, TimeUnit.SECONDS));
      assertEquals(List.of("first", "second"), order);
    } finally {
      release.countDown();
    }
    assertEquals(0, admission.waitingInteractive());
    assertEquals(0, admission.active(ReadAdmission.Work.INTERACTIVE));
  }

  @Test
  void queueBoundsAndInterruptionsRefuseBeforeHandlerWorkAndReleaseTickets() throws Exception {
    var admission = admission(1, 1, 2, 1, 2000);
    var held = admission.admit("held", ReadAdmission.Work.INTERACTIVE);
    var interrupted = new AtomicBoolean();
    var waiting =
        Thread.ofVirtual()
            .start(
                () -> {
                  assertThrows(
                      ReadFailedException.class, () -> admission.awaitInteractive("alice"));
                  interrupted.set(Thread.currentThread().isInterrupted());
                });
    try (held;
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      awaitWaiting(admission, 1);
      assertThrows(ReadUnavailableException.class, () -> admission.awaitInteractive("alice"));
      var bob =
          callers.submit(
              () -> {
                var lease = admission.awaitInteractive("bob");
                try (lease) {
                  return "bob";
                }
              });
      awaitWaiting(admission, 2);
      assertThrows(ReadUnavailableException.class, () -> admission.awaitInteractive("carol"));
      waiting.interrupt();
      waiting.join(5000);
      assertFalse(waiting.isAlive());
      assertTrue(interrupted.get());
      awaitWaiting(admission, 1);
      held.close();
      assertEquals("bob", bob.get(5, TimeUnit.SECONDS));
    } finally {
      waiting.interrupt();
    }
    assertEquals(0, admission.waitingInteractive());
  }

  @Test
  void expiredAdmissionNeverStartsAHandlerAndUsesTheEarlierBudget() {
    var admission = admission(1, 1, 8, 4, 2000);
    var calls = new AtomicInteger();
    var held = admission.admit("held", ReadAdmission.Work.INTERACTIVE);
    try (held) {
      ReadDeadline.within(
          Duration.ofMillis(50),
          () -> {
            assertThrows(
                ReadFailedException.class,
                () ->
                    admission.readAsync(
                        "alice",
                        () -> {
                          calls.incrementAndGet();
                          return CompletableFuture.completedFuture("unreachable");
                        }));
            return null;
          });
      assertEquals(0, calls.get());
      assertEquals(0, admission.waitingInteractive());
    }
    ReadDeadline.within(
        Duration.ofNanos(1),
        () -> {
          assertThrows(
              ReadFailedException.class,
              () ->
                  admission.readAsync(
                      "alice",
                      () -> {
                        calls.incrementAndGet();
                        return CompletableFuture.completedFuture("unreachable");
                      }));
          return null;
        });
    assertEquals(0, calls.get());
    assertEquals(0, admission.active(ReadAdmission.Work.INTERACTIVE));
  }

  @Test
  void admissionPreservesAnExistingSqlDeadlineWithoutCreatingAHandlerDeadline() throws Exception {
    var admission = admission(1, 1, 8, 4, 2000);
    var held = admission.admit("held", ReadAdmission.Work.INTERACTIVE);
    try (held;
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var waiting =
          callers.submit(
              () ->
                  ReadDeadline.within(
                      Duration.ofSeconds(1),
                      () -> {
                        long expected = ReadDeadline.deadlineNanos(Duration.ofDays(1));
                        var result =
                            admission.readAsync(
                                "alice",
                                () -> {
                                  assertEquals(
                                      expected, ReadDeadline.deadlineNanos(Duration.ofDays(1)));
                                  return CompletableFuture.completedFuture("page");
                                });
                        assertEquals(expected, ReadDeadline.deadlineNanos(Duration.ofDays(1)));
                        return result.toCompletableFuture().join();
                      }));
      awaitWaiting(admission, 1);
      held.close();
      assertEquals("page", waiting.get(5, TimeUnit.SECONDS));
    }
    assertTrue(
        ReadDeadline.deadlineNanos(Duration.ofDays(1)) - System.nanoTime()
            > Duration.ofHours(1).toNanos());
  }

  @Test
  void queueWaitBudgetDoesNotExpireAnAdmittedHandlerAndCancellationRetainsItsSeat()
      throws Exception {
    var admission = admission(1, 1, 8, 4, 20);
    var source = new CompletableFuture<String>();
    var reply =
        admission
            .readAsync(
                "alice",
                () -> {
                  assertTrue(
                      ReadDeadline.deadlineNanos(Duration.ofDays(1)) - System.nanoTime()
                          > Duration.ofHours(1).toNanos());
                  return source;
                })
            .toCompletableFuture();
    Thread.sleep(40);
    assertFalse(reply.isDone(), "Admission waiting is not the provider/workflow lifetime");
    assertFalse(source.isDone());
    assertEquals(1, admission.active(ReadAdmission.Work.INTERACTIVE));
    source.complete("page");
    assertEquals(0, admission.active(ReadAdmission.Work.INTERACTIVE));
    assertEquals("page", reply.get(5, TimeUnit.SECONDS));

    var cancelledSource = new CompletableFuture<String>();
    var cancelledReply = admission.readAsync("alice", () -> cancelledSource).toCompletableFuture();
    assertTrue(cancelledReply.cancel(true));
    assertFalse(cancelledSource.isDone());
    assertEquals(1, admission.active(ReadAdmission.Work.INTERACTIVE));
    cancelledSource.complete("settled");
    assertEquals(0, admission.active(ReadAdmission.Work.INTERACTIVE));
  }

  @Test
  void actualErrorsPropagateOnceAndReleaseThePhysicalSeat() throws Exception {
    var admission = admission(1, 1, 8, 4, 2000);
    var failure = new IllegalStateException("reader failed");
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () ->
                admission.readAsync(
                    "alice",
                    () -> {
                      throw failure;
                    })));
    assertEquals(0, admission.active(ReadAdmission.Work.INTERACTIVE));
    var source = new CompletableFuture<String>();
    var reply = admission.readAsync("alice", () -> source).toCompletableFuture();
    source.completeExceptionally(failure);
    assertSame(
        failure,
        assertThrows(ExecutionException.class, () -> reply.get(5, TimeUnit.SECONDS)).getCause());
    assertEquals(0, admission.active(ReadAdmission.Work.INTERACTIVE));
  }

  @Test
  void retainedTransactionsAndAlreadyInterruptedCallersCannotEnterTheWaitingQueue() {
    var admission = admission(1, 1, 8, 4, 2000);
    var held = admission.admit("held", ReadAdmission.Work.INTERACTIVE);
    try (held) {
      TransactionSynchronizationManager.initSynchronization();
      try {
        assertThrows(ReadUnavailableException.class, () -> admission.awaitInteractive("alice"));
        assertEquals(0, admission.waitingInteractive());
      } finally {
        TransactionSynchronizationManager.clear();
      }
    }
    Thread.currentThread().interrupt();
    try {
      assertThrows(ReadFailedException.class, () -> admission.awaitInteractive("alice"));
      assertEquals(0, admission.active(ReadAdmission.Work.INTERACTIVE));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void pureAuthorityWaitingNeverParksACallerHoldingATransactionOrSynchronization() {
    var admission = admission(1, 1, 8, 4, 2000);
    var held = admission.admit("alice", ReadAdmission.Work.AUTHORITY);
    try (held) {
      for (boolean actual : List.of(false, true)) {
        if (actual) TransactionSynchronizationManager.setActualTransactionActive(true);
        else TransactionSynchronizationManager.initSynchronization();
        try {
          assertThrows(
              ReadUnavailableException.class,
              () -> admission.readAuthoritative("alice", () -> "must not start"));
          assertEquals(0, admission.waitingAuthoritative());
        } finally {
          TransactionSynchronizationManager.clear();
        }
      }
    }
  }

  @Test
  void reportAndFetchWaitsAndFastGrantsRespectExpiryAndInterruption() {
    var admission = admission(1, 1, 8, 4, 2000);
    for (var work : List.of(ReadAdmission.Work.REPORT, ReadAdmission.Work.FETCH)) {
      var held = new ArrayList<ReadAdmission.Lease>();
      for (int i = 0; i < (work == ReadAdmission.Work.FETCH ? 2 : 1); i++)
        held.add(admission.admit("owner", work));
      var calls = new AtomicInteger();
      try {
        ReadDeadline.within(
            Duration.ofMillis(50),
            () -> {
              assertThrows(
                  ReadFailedException.class,
                  () -> admission.read("owner", work, calls::incrementAndGet));
              return null;
            });
      } finally {
        held.forEach(ReadAdmission.Lease::close);
      }
      ReadDeadline.within(
          Duration.ofNanos(1),
          () -> {
            assertThrows(
                ReadFailedException.class,
                () -> admission.read("owner", work, calls::incrementAndGet));
            return null;
          });
      Thread.currentThread().interrupt();
      try {
        assertThrows(
            ReadFailedException.class, () -> admission.read("owner", work, calls::incrementAndGet));
      } finally {
        Thread.interrupted();
      }
      assertEquals(0, calls.get());
      assertEquals(0, admission.waitingReports());
      assertEquals(0, admission.waitingFetches());
      assertEquals(0, admission.active(work));
    }
  }

  @Test
  void cleanupFailureCannotPublishASuccessfulReply() throws Exception {
    var failure = new IllegalStateException("cleanup failed");
    ReadAdmission admission =
        (principal, work) ->
            () -> {
              throw failure;
            };
    var reply =
        admission
            .readAsync("alice", () -> CompletableFuture.completedFuture("page"))
            .toCompletableFuture();
    assertSame(
        failure,
        assertThrows(ExecutionException.class, () -> reply.get(5, TimeUnit.SECONDS)).getCause());
    var original = new IllegalArgumentException("dispatch failed");
    assertSame(
        original,
        assertThrows(
            IllegalArgumentException.class,
            () ->
                admission.readAsync(
                    "alice",
                    () -> {
                      throw original;
                    })));
    assertEquals(List.of(failure), List.of(original.getSuppressed()));
  }

  private static void awaitWaiting(BoundedReadAdmission admission, int count)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (admission.waitingInteractive() != count && System.nanoTime() - deadline < 0)
      Thread.sleep(1);
    assertEquals(count, admission.waitingInteractive());
  }
}
