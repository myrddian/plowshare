package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class DigestStoreReuseTest {
  @Test
  void captureBatchesSourceEligibilityAndExcerptsAndResolvesTheHomeOnce() throws Exception {
    var jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(List.of(1L));
    var rows = List.of(turn("conversation-a", 1, "one"), turn("conversation-b", 3, "two"));
    var statements = new ArrayList<String>();
    var bindings = new ArrayList<List<Object>>();
    doAnswer(
            call -> {
              statements.add(call.getArgument(0));
              bindings.add(Arrays.asList(((Object[]) call.getRawArguments()[2]).clone()));
              RowMapper<?> mapper = call.getArgument(1);
              var result = new ArrayList<Object>();
              for (var row : rows) result.add(mapper.mapRow(row, result.size()));
              return result;
            })
        .when(jdbc)
        .query(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
            any(Object[].class));
    var store = new DigestStore(jdbc, UnitOfWork.NONE);
    store.protectInformation();
    store.captureUnfolded(Home.of("research"));
    assertEquals(1, statements.size());
    assertTrue(
        statements
            .getFirst()
            .contains("c.id NOT IN (SELECT log FROM information_unreadable_logs(NULL))"));
    assertTrue(
        statements.getFirst().contains("ORDER BY e.conversation_id,e.turn_ordinal LIMIT 64"));
    assertTrue(statements.getFirst().contains("string_agg(left(e.content,4000)"));
    assertEquals(List.of(1L, "", "", 0), bindings.getFirst());
    verify(jdbc).queryForList(anyString(), eq(Long.class), eq("research"));
    verify(jdbc, times(4)).update(anyString(), any(Object[].class));
  }

  @Test
  void retentionCapturesOnlyItsRequestedConversationAndDoesNotScanSiblingTurns() throws Exception {
    var jdbc = mock(JdbcTemplate.class);
    var sql = new ArrayList<String>();
    var arguments = new ArrayList<List<Object>>();
    doAnswer(
            call -> {
              String statement = call.getArgument(0);
              sql.add(statement);
              arguments.add(Arrays.asList(((Object[]) call.getRawArguments()[2]).clone()));
              return statement.contains("source_turns") ? List.of() : List.of(Home.global());
            })
        .when(jdbc)
        .query(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
            any(Object[].class));
    when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);
    assertTrue(new DigestStore(jdbc, UnitOfWork.NONE).covered("one-conversation"));
    assertEquals(2, sql.size());
    assertTrue(sql.getLast().contains("AND c.id=?"));
    assertEquals(Arrays.asList(null, "one-conversation", "", "", 0), arguments.getLast());
    verify(jdbc, never()).queryForList(anyString(), eq(Long.class), any(Object[].class));
  }

  @Test
  void groupLocksInOrderThenChecksOneFreshBoundedStateProjectionBeforeReturningTheInsert()
      throws Exception {
    var jdbc = mock(JdbcTemplate.class);
    var rows = List.of(grouping("a", false), grouping("b", false));
    var sql = new ArrayList<String>();
    doAnswer(
            call -> {
              sql.add(call.getArgument(0));
              RowMapper<?> mapper = call.getArgument(1);
              var result = new ArrayList<Object>();
              for (var row : rows) result.add(mapper.mapRow(row, result.size()));
              return result;
            })
        .when(jdbc)
        .query(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
            any(Object[].class));
    var inserted = new DigestStore.Node("grouped", 1, "summary", null, 1);
    when(jdbc.queryForObject(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<DigestStore.Node>>any(),
            any(Object[].class)))
        .thenReturn(inserted);
    var members =
        List.of(
            new DigestStore.Node("b", 0, "b", null, 1), new DigestStore.Node("a", 0, "a", null, 1));
    assertSame(
        inserted,
        new DigestStore(jdbc, UnitOfWork.NONE).group(Home.global(), members, "summary", null));
    assertEquals(1, sql.size());
    assertTrue(sql.getFirst().contains("d.revision,d.stale_at IS NOT NULL AS stale"));
    assertFalse(sql.getFirst().contains("d.summary"));
    var order = inOrder(jdbc);
    order
        .verify(jdbc)
        .queryForList("SELECT id FROM digests WHERE id=? FOR UPDATE", String.class, "a");
    order
        .verify(jdbc)
        .queryForList("SELECT id FROM digests WHERE id=? FOR UPDATE", String.class, "b");
    order
        .verify(jdbc)
        .query(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
            any(Object[].class));
    verify(jdbc)
        .queryForObject(
            contains("RETURNING id,depth,summary,stale_at,revision"),
            org.mockito.ArgumentMatchers.<RowMapper<DigestStore.Node>>any(),
            any(Object[].class));
  }

  @Test
  void groupRejectsChangedOrStaleMembersBeforeWritingAnything() throws Exception {
    var jdbc = mock(JdbcTemplate.class);
    var stale = grouping("a", true);
    doAnswer(
            call -> {
              RowMapper<?> mapper = call.getArgument(1);
              return List.of(mapper.mapRow(stale, 0));
            })
        .when(jdbc)
        .query(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),
            any(Object[].class));
    var members =
        List.of(
            new DigestStore.Node("a", 0, "a", null, 1), new DigestStore.Node("b", 0, "b", null, 1));
    assertThrows(
        ValidationException.class,
        () ->
            new DigestStore(jdbc, UnitOfWork.NONE).group(Home.global(), members, "summary", null));
    verify(jdbc, never()).update(anyString(), any(Object[].class));
    verify(jdbc, never())
        .queryForObject(
            anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<DigestStore.Node>>any(),
            any(Object[].class));
  }

  private ResultSet turn(String conversation, int ordinal, String text) throws Exception {
    var row = mock(ResultSet.class);
    when(row.getString("conversation_id")).thenReturn(conversation);
    when(row.getInt("turn_ordinal")).thenReturn(ordinal);
    when(row.getString("excerpt")).thenReturn(text);
    return row;
  }

  private ResultSet grouping(String id, boolean stale) throws Exception {
    var row = mock(ResultSet.class);
    when(row.getString("id")).thenReturn(id);
    when(row.getLong("revision")).thenReturn(1L);
    when(row.getBoolean("stale")).thenReturn(stale);
    return row;
  }
}
