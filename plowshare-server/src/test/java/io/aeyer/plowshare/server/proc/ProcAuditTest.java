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
          CallerFault.class,
          () -> new ProcQuery(null, null, null, null, null, null, null, limit, null));
    assertThrows(
        CallerFault.class,
        () -> new ProcQuery("admin", null, null, null, null, null, null, null, null));
    assertThrows(
        CallerFault.class,
        () -> new ProcQuery(null, null, "x\0y", null, null, null, null, null, null));
  }

  @Test
  void orderDefaultsToOldestAndRejectsAnythingButTheTwoDirections() {
    var absent = query(null);
    assertEquals("oldest", absent.order());
    assertFalse(absent.newest());
    var newest = new ProcQuery(null, null, null, null, null, null, null, null, "newest");
    assertTrue(newest.newest());
    // A continuation keeps the direction that produced its cursor.
    assertTrue(newest.page("cursor").newest());
    assertEquals("cursor", newest.page("cursor").after());
    for (String invalid : new String[] {"", "NEWEST", "desc", "newest "})
      assertThrows(
          CallerFault.class,
          () -> new ProcQuery(null, null, null, null, null, null, null, null, invalid));
  }

  @Test
  void conversationPageDirectionOnlyChangesItsKeysetAndOrder() {
    for (boolean active : new boolean[] {false, true})
      for (boolean targeted : new boolean[] {false, true}) {
        String oldest = JdbcProcRepository.conversationPageSql(active, targeted, false);
        String newest = JdbcProcRepository.conversationPageSql(active, targeted, true);
        // Existing callers keep the exact ascending statement.
        assertEquals(JdbcProcRepository.conversationPageSql(active, targeted), oldest);
        assertTrue(
            oldest.contains(
                " WHERE CAST(:after AS text) IS NULL OR id>:after ORDER BY id LIMIT 101)"
                    + " bounded ORDER BY id LIMIT :limit)"));
        assertTrue(oldest.endsWith("ORDER BY enriched.id\n"));
        assertFalse(oldest.contains("id<:after"));
        assertTrue(
            newest.contains(
                " WHERE CAST(:after AS text) IS NULL OR id<:after ORDER BY id DESC LIMIT 101)"
                    + " bounded ORDER BY id DESC LIMIT :limit)"));
        assertTrue(newest.endsWith("ORDER BY enriched.id DESC\n"));
        assertFalse(newest.contains("id>:after"));
        // Totals and enrichment are direction-independent.
        assertEquals(
            oldest
                .replace("id>:after ORDER BY id LIMIT", "id<:after ORDER BY id DESC LIMIT")
                .replace("bounded ORDER BY id LIMIT", "bounded ORDER BY id DESC LIMIT")
                .replace("ORDER BY enriched.id\n", "ORDER BY enriched.id DESC\n"),
            newest);
      }
  }

  @Test
  void localPoolQueuesAreDisclosedOnlyAfterAuthorizedFleetRead() {
    var repository = mock(ProcRepository.class);
    var runtime = mock(ProcRuntime.class);
    var mine = query(null);
    var fleet = new ProcQuery("server", null, null, null, null, null, null, null, null);
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
    return new ProcQuery(null, null, conversation, null, null, null, null, null, null);
  }
}
