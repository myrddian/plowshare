package io.aeyer.plowshare.server.proc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProcAuditTest {
  @Test
  void validatesSelectionBeforeInspectionAndDoesNotStartWork() {
    var repository = mock(ProcRepository.class);
    var audit = new ProcAudit(repository, () -> new ProcRepository.Live(Instant.now(), List.of()));
    assertThrows(CallerFault.class, () -> audit.inspect("alice", query(null)));
    verifyNoInteractions(repository);
    for (int limit : new int[] {-1, 0, 101, Integer.MAX_VALUE})
      assertThrows(
          CallerFault.class, () -> new ProcQuery(null, null, null, null, null, null, null, limit));
    assertThrows(
        CallerFault.class, () -> new ProcQuery("admin", null, null, null, null, null, null, null));
    assertThrows(
        CallerFault.class, () -> new ProcQuery(null, null, "x\0y", null, null, null, null, null));
  }

  @Test
  void localPoolQueuesAreDisclosedOnlyAfterAuthorizedFleetRead() {
    var repository = mock(ProcRepository.class);
    var runtime = mock(ProcRuntime.class);
    var mine = query(null);
    var fleet = new ProcQuery("server", null, null, null, null, null, null, null);
    when(repository.queues("alice", mine))
        .thenReturn(
            new ProcViews.Queues(
                Instant.now(), mine, List.of(), List.of(), List.of(), null, List.of()));
    when(repository.queues("alice", fleet))
        .thenReturn(
            new ProcViews.Queues(
                Instant.now(), fleet, List.of(), List.of(), List.of(), null, List.of()));
    when(runtime.pools()).thenReturn(List.of(new ProcViews.Pool("fixture", "CHAT", 12, 1, 1)));
    var audit = new ProcAudit(repository, runtime);
    assertTrue(audit.queues("alice", mine).pools().isEmpty());
    verify(runtime, never()).pools();
    assertEquals(12, audit.queues("alice", fleet).pools().getFirst().queued());
    when(repository.queues("bob", fleet)).thenThrow(new CallerFault("not authorized"));
    clearInvocations(runtime);
    assertThrows(CallerFault.class, () -> audit.queues("bob", fleet));
    verifyNoInteractions(runtime);
  }

  private ProcQuery query(String conversation) {
    return new ProcQuery(null, null, conversation, null, null, null, null, null);
  }
}
