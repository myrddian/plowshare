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
  void validatesBudgetsBeforeStartup() {
    assertThrows(
        IllegalArgumentException.class, () -> new ReadCapacityProperties(0, 1, 2, 1, 8, 2));
    assertThrows(
        IllegalArgumentException.class, () -> new ReadCapacityProperties(4, 5, 2, 1, 8, 2));
  }
}
