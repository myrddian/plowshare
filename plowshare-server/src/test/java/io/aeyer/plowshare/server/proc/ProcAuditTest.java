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
  void callPagesKeysetOnCreationTimeAndIdInOneDirectionEverywhere() {
    for (boolean active : new boolean[] {false, true})
      for (boolean tree : new boolean[] {false, true}) {
        String oldest = JdbcProcRepository.callsPageSql(active, tree, false, false);
        String newest = JdbcProcRepository.callsPageSql(active, tree, true, true);
        assertEquals(JdbcProcRepository.callsPageSql(active, tree), oldest);
        // Neither direction may fall back to the random UUID order or the untyped cursor.
        for (String sql : new String[] {oldest, newest}) {
          assertFalse(sql.contains("call_id::text>"));
          assertFalse(sql.contains(":after "));
          assertFalse(sql.contains(":after)"));
          assertFalse(sql.contains("ORDER BY call_id"));
          assertFalse(sql.contains("ORDER BY ic.call_id"));
        }
        assertFalse(oldest.contains(":afterAt"), "a first page carries no cursor predicate");
        assertFalse(oldest.contains("DESC"));
        assertTrue(oldest.contains("page AS MATERIALIZED (SELECT * FROM matched"));
        assertTrue(oldest.contains("ORDER BY created_at,call_id LIMIT :limit)"));
        assertTrue(oldest.endsWith("ORDER BY ic.created_at,ic.call_id\n"));
        assertTrue(newest.contains("ORDER BY created_at DESC,call_id DESC LIMIT :limit)"));
        assertTrue(newest.endsWith("ORDER BY ic.created_at DESC,ic.call_id DESC\n"));
        String seek =
            "(ic.created_at,ic.call_id)<(CAST(:afterAt AS timestamptz),CAST(:afterCall AS uuid))";
        // Attributed (outer filter and ancestry fence) and orphan branches all seek the cursor.
        assertEquals(tree ? 2 : 3, count(newest, seek));
        assertFalse(newest.contains("(ic.created_at,ic.call_id)>"));
        if (!tree) {
          assertEquals(2, count(oldest, "ORDER BY ic.created_at,ic.call_id LIMIT :limit"));
          assertEquals(
              2, count(newest, "ORDER BY ic.created_at DESC,ic.call_id DESC LIMIT :limit"));
        }
        String ascendingContinued = JdbcProcRepository.callsPageSql(active, tree, false, true);
        assertEquals(tree ? 2 : 3, count(ascendingContinued, seek.replace(")<(", ")>(")));
        assertEquals(active ? (tree ? 2 : 3) : 0, count(oldest, "ic.ended_at IS NULL"));
      }
  }

  @Test
  void toolPagesUseNumericOrdinalRowValuesInOneDirection() {
    String oldest = JdbcProcRepository.toolsSql(false, false, false);
    assertFalse(oldest.contains(":after"));
    assertTrue(
        oldest.contains(" ORDER BY e.conversation_id,e.ordinal,tool->>'id' LIMIT :limit) page"));
    assertTrue(oldest.endsWith(" ORDER BY page.conversation_id,page.ordinal,page.tool_id"));
    assertFalse(oldest.contains("ORDER BY id"), "the concatenated text id is not the sort key");
    String newest = JdbcProcRepository.toolsSql(true, true, true);
    assertTrue(
        newest.contains(
            " AND (e.conversation_id,e.ordinal,tool->>'id')<(CAST(:afterConversation AS text),"
                + "CAST(:afterOrdinal AS integer),CAST(:afterTool AS text))"));
    assertTrue(
        newest.contains(
            " ORDER BY e.conversation_id DESC,e.ordinal DESC,tool->>'id' DESC LIMIT :limit) page"));
    assertTrue(
        newest.endsWith(" ORDER BY page.conversation_id DESC,page.ordinal DESC,page.tool_id DESC"));
    assertTrue(newest.contains("AND NOT EXISTS(SELECT 1 FROM entries result"));
    assertTrue(JdbcProcRepository.toolsSql(false, false, true).contains("tool->>'id')>(CAST("));
  }

  @Test
  void toolReceiptStateIsProbedOnlyForThePage() {
    for (boolean active : new boolean[] {false, true}) {
      String sql = JdbcProcRepository.toolsSql(active, true, false);
      int inner = sql.indexOf("FROM (SELECT e.");
      int page = sql.indexOf(" LIMIT :limit) page");
      assertTrue(inner > 0 && page > inner);
      String outer = sql.substring(0, inner);
      String candidates = sql.substring(inner, page);
      assertTrue(outer.contains("'RESULT_RECORDED'"), "state is derived over the chosen page");
      assertEquals(1, count(outer, "FROM entries result"));
      // Only the active filter probes candidates, because it decides which rows are listed.
      assertEquals(active ? 1 : 0, count(candidates, "FROM entries result"));
      assertFalse(candidates.contains("'RESULT_RECORDED'"));
      assertTrue(
          sql.contains(" AND e.tool_calls IS NOT NULL"), "entries without calls are skipped");
    }
  }

  @Test
  void queuePagesOrderByRevisionTimeWhileSummariesStayFirstAndFixed() {
    for (boolean active : new boolean[] {false, true}) {
      String oldest = JdbcProcRepository.queueSql(active, false, false);
      assertEquals(JdbcProcRepository.queueSql(active), oldest);
      String newest = JdbcProcRepository.queueSql(active, true, true);
      assertFalse(oldest.contains(":after"));
      assertTrue(oldest.contains("FROM selected ORDER BY revision_created_at,id LIMIT :limit)"));
      assertTrue(
          oldest.endsWith(
              "ORDER BY summary DESC,CASE WHEN summary THEN id END,revision_created_at,id\n"));
      assertTrue(
          newest.contains(
              "FROM selected WHERE (revision_created_at,id)<(CAST(:afterAt AS timestamptz),"
                  + "CAST(:afterKey AS text)) ORDER BY revision_created_at DESC,id DESC"
                  + " LIMIT :limit)"));
      assertTrue(
          newest.endsWith(
              "ORDER BY summary DESC,CASE WHEN summary THEN id END,"
                  + "revision_created_at DESC,id DESC\n"));
      // Summaries are computed over the whole selection before any cursor filter.
      String summaries = "FROM selected GROUP BY unit,stage";
      assertEquals(1, count(oldest, summaries));
      assertEquals(
          oldest.substring(0, oldest.indexOf(summaries)),
          newest.substring(0, newest.indexOf(summaries)));
      assertTrue(
          JdbcProcRepository.queueSql(active, false, true)
              .contains("WHERE (revision_created_at,id)>(CAST(:afterAt"));
    }
  }

  @Test
  void malformedOrOldCursorsAreRejectedBeforeAnyDatabaseWork() {
    var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
    var repository =
        new JdbcProcRepository(
            jdbc, mock(org.springframework.transaction.PlatformTransactionManager.class));
    var oldCall =
        new ProcQuery(
            null, null, null, null, null, null, "0f8fad5b-d9cb-469f-a165-70867728950e", null, null);
    assertThrows(CallerFault.class, () -> repository.calls("alice", oldCall));
    var oldQueue =
        new ProcQuery(
            null,
            null,
            null,
            null,
            null,
            null,
            "0f8fad5b-d9cb-469f-a165-70867728950e:stages",
            null,
            "newest");
    assertThrows(CallerFault.class, () -> repository.queues("alice", oldQueue));
    var badTool = new ProcQuery(null, null, null, null, null, null, "cnv_1:x:t", null, null);
    assertThrows(CallerFault.class, () -> repository.tools("alice", badTool));
    verifyNoInteractions(jdbc);
  }

  @Test
  void toolSectionsAndContinuationsKeepTheRequestedDirection() {
    var repository = mock(ProcRepository.class);
    var audit = new ProcAudit(repository, () -> new ProcRepository.Live(Instant.now(), List.of()));
    var newest = new ProcQuery(null, null, "cnv_1", null, null, null, null, 1, "newest");
    var receipt = new ProcViews.Tool("cnv_1:10:t", "cnv_1", null, "web_search", "UNKNOWN", null);
    var older = new ProcViews.Tool("cnv_1:9:t", "cnv_1", null, "web_search", "UNKNOWN", null);
    when(repository.tools("alice", newest)).thenReturn(List.of(receipt, older));
    var page = audit.tools("alice", newest);
    assertEquals(List.of(receipt), page.tools());
    assertEquals("cnv_1:10:t", page.cursor(), "the reserved extra row only proves continuation");
    assertTrue(page.selection().newest());
  }

  private static int count(String text, String part) {
    int found = 0;
    for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) found++;
    return found;
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
