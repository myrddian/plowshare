package io.aeyer.plowshare.server.reads;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BoundedReadAdmissionTest {
  @Test
  void socketsCannotMultiplyPrincipalCapacityAndReportsCannotConsumeInteractiveCapacity() {
    var admission = new BoundedReadAdmission(new ReadCapacityProperties(4, 2, 2, 1, 8, 2));
    var first = admission.admit("alice", ReadAdmission.Work.REPORT);
    try (first) {
      for (int socket = 0; socket < 1000; socket++)
        assertThrows(
            ReadUnavailableException.class,
            () -> admission.admit("alice", ReadAdmission.Work.REPORT));
      var other = admission.admit("bob", ReadAdmission.Work.REPORT);
      var ordinary = admission.admit("alice", ReadAdmission.Work.INTERACTIVE);
      try (other;
          ordinary) {
        assertEquals(2, admission.active(ReadAdmission.Work.REPORT));
        assertEquals(1, admission.active(ReadAdmission.Work.INTERACTIVE));
      }
    }
    assertEquals(0, admission.active(ReadAdmission.Work.REPORT));
    assertEquals(1000, admission.rejected());
  }

  @Test
  void aThousandDistinctPrincipalsRemainGloballyBoundedAndLeasesSettleAcrossThreads()
      throws Exception {
    var admission = new BoundedReadAdmission(new ReadCapacityProperties(4, 2, 2, 1, 8, 2));
    var leases = new ArrayList<ReadAdmission.Lease>();
    try {
      for (int i = 0; i < 4; i++)
        leases.add(admission.admit("principal-" + i, ReadAdmission.Work.INTERACTIVE));
      try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
        var futures = new ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < 1000; i++) {
          String account = "other-" + i;
          futures.add(
              callers.submit(
                  () ->
                      assertThrows(
                          ReadUnavailableException.class,
                          () -> admission.admit(account, ReadAdmission.Work.INTERACTIVE))));
        }
        for (var future : futures) future.get(10, TimeUnit.SECONDS);
        for (var lease : leases)
          callers
              .submit(
                  () -> {
                    lease.close();
                    lease.close();
                  })
              .get(10, TimeUnit.SECONDS);
      }
      assertEquals(0, admission.active(ReadAdmission.Work.INTERACTIVE));
      var next = admission.admit("new", ReadAdmission.Work.INTERACTIVE);
      try (next) {
        assertEquals(1, admission.active(ReadAdmission.Work.INTERACTIVE));
      }
    } finally {
      leases.forEach(ReadAdmission.Lease::close);
    }
  }

  @Test
  void refreshesShareReportTotalsReserveForegroundCapacityAndYieldToWaitingReaders()
      throws Exception {
    var admission = new BoundedReadAdmission(new ReadCapacityProperties(4, 2, 2, 1, 8, 2));
    var refresh = admission.admit("alice", ReadAdmission.Work.REPORT_REFRESH);
    assertThrows(
        ReadUnavailableException.class,
        () -> admission.admit("bob", ReadAdmission.Work.REPORT_REFRESH));
    var foreground = admission.admit("bob", ReadAdmission.Work.REPORT);
    try (foreground) {
      assertThrows(
          ReadUnavailableException.class,
          () -> admission.admit("carol", ReadAdmission.Work.REPORT));
    }
    try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var entered = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      var reader =
          callers.submit(
              () ->
                  admission.read(
                      "alice",
                      ReadAdmission.Work.REPORT,
                      () -> {
                        entered.countDown();
                        try {
                          assertTrue(release.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                          throw new AssertionError(interrupted);
                        }
                        return "authorized snapshot";
                      }));
      awaitWaiting(admission, 1);
      assertThrows(
          ReadUnavailableException.class,
          () -> admission.admit("bob", ReadAdmission.Work.REPORT_REFRESH));
      refresh.close(); // Cross-thread release wakes the foreground queue before another feed tick.
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertEquals(1, admission.active(ReadAdmission.Work.REPORT));
      release.countDown();
      assertEquals("authorized snapshot", reader.get(5, TimeUnit.SECONDS));
    } finally {
      refresh.close();
    }
    assertEquals(0, admission.waitingReports());
  }

  @Test
  void queuedReportsAreBoundedInterruptibleAndCannotBlockAnEligibleOtherPrincipal()
      throws Exception {
    var admission =
        new BoundedReadAdmission(
            new ReadCapacityProperties(4, 2, 2, 1, 8, 2), new ReadSchedulingProperties(2, 1, 2000));
    var held = admission.admit("alice", ReadAdmission.Work.REPORT);
    try (held;
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var waiting =
          callers.submit(
              () ->
                  admission.read(
                      "alice",
                      ReadAdmission.Work.REPORT,
                      () -> {
                        fail("Cancelled waiter must never enter SQL");
                        return "unreachable";
                      }));
      awaitWaiting(admission, 1);
      assertThrows(
          ReadUnavailableException.class,
          () -> admission.read("alice", ReadAdmission.Work.REPORT, () -> "must not run"));
      assertEquals(
          "bob",
          admission.read("bob", ReadAdmission.Work.REPORT, () -> "bob"),
          "An occupied principal must not cause head-of-line blocking");
      waiting.cancel(true);
      awaitWaiting(admission, 0);
    }
    assertEquals(0, admission.active(ReadAdmission.Work.REPORT));
  }

  @Test
  void aThousandQueuedReportRequestsCannotGrowMetadataOrEnterSqlPastTheQueueBound()
      throws Exception {
    var admission =
        new BoundedReadAdmission(
            new ReadCapacityProperties(4, 2, 1, 1, 8, 2), new ReadSchedulingProperties(2, 1, 2000));
    var held = admission.admit("held", ReadAdmission.Work.REPORT);
    try (held;
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          callers.submit(() -> admission.read("first", ReadAdmission.Work.REPORT, () -> "first"));
      var second =
          callers.submit(() -> admission.read("second", ReadAdmission.Work.REPORT, () -> "second"));
      awaitWaiting(admission, 2);
      for (int i = 0; i < 1000; i++) {
        String principal = "excess-" + i;
        assertThrows(
            ReadUnavailableException.class,
            () ->
                admission.read(
                    principal,
                    ReadAdmission.Work.REPORT,
                    () -> {
                      fail("Excess request must not enter SQL");
                      return "unreachable";
                    }));
      }
      assertEquals(2, admission.waitingReports());
      held.close();
      assertEquals("first", first.get(5, TimeUnit.SECONDS));
      assertEquals("second", second.get(5, TimeUnit.SECONDS));
    }
    assertEquals(0, admission.waitingReports());
  }

  @Test
  void reportQueueTimeoutReleasesItsBoundBeforeAnySqlRuns() {
    var admission =
        new BoundedReadAdmission(
            new ReadCapacityProperties(4, 2, 1, 1, 8, 2), new ReadSchedulingProperties(2, 1, 1));
    var held = admission.admit("held", ReadAdmission.Work.REPORT);
    try (held) {
      assertThrows(
          ReadUnavailableException.class,
          () ->
              admission.read(
                  "waiting",
                  ReadAdmission.Work.REPORT,
                  () -> {
                    fail("Expired waiter must not enter SQL");
                    return "unreachable";
                  }));
      assertEquals(0, admission.waitingReports());
    }
  }

  @Test
  void aCallerAlreadyHoldingATransactionOrSynchronizationNeverParksForReadCapacity() {
    var admission = new BoundedReadAdmission(new ReadCapacityProperties(4, 2, 1, 1, 8, 2));
    var held = admission.admit("held", ReadAdmission.Work.REPORT);
    try (held) {
      for (boolean actual : new boolean[] {false, true}) {
        if (!actual)
          org.springframework.transaction.support.TransactionSynchronizationManager
              .initSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager
            .setActualTransactionActive(actual);
        try {
          assertThrows(
              ReadUnavailableException.class,
              () ->
                  admission.read(
                      "caller",
                      ReadAdmission.Work.REPORT,
                      () -> {
                        fail("Caller with retained connections must not enter the waiting queue");
                        return "unreachable";
                      }));
          assertEquals(0, admission.waitingReports());
        } finally {
          org.springframework.transaction.support.TransactionSynchronizationManager.clear();
        }
      }
    }
  }

  private static void awaitWaiting(BoundedReadAdmission admission, int count) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (admission.waitingReports() != count && System.nanoTime() < deadline) Thread.yield();
    assertEquals(count, admission.waitingReports());
  }

  @Test
  void validatesBudgetsBeforeStartup() {
    assertThrows(
        IllegalArgumentException.class, () -> new ReadCapacityProperties(0, 1, 2, 1, 8, 2));
    assertThrows(
        IllegalArgumentException.class, () -> new ReadCapacityProperties(4, 5, 2, 1, 8, 2));
  }
}
