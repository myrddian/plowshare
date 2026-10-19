package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.allowances.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;

class BoardStoreReuseTest {
  @Test
  void postReturnsTheInsertedProjectionWithoutAReadAfterWrite() throws Exception {
    var jdbc = mock(JdbcTemplate.class);
    var connection = mock(Connection.class);
    var statement = mock(PreparedStatement.class);
    var sql = new AtomicReference<String>();
    when(connection.prepareStatement(anyString()))
        .thenAnswer(
            call -> {
              sql.set(call.getArgument(0));
              return statement;
            });
    var now = Instant.parse("2026-10-20T00:00:00Z");
    var row = mock(ResultSet.class);
    when(row.getString("id")).thenReturn("inserted");
    when(row.getString("topic")).thenReturn("topic");
    when(row.getString("body")).thenReturn("body");
    when(row.getObject("posted_at", java.time.OffsetDateTime.class))
        .thenReturn(now.atOffset(ZoneOffset.UTC));
    doAnswer(
            call -> {
              PreparedStatementCreator creator = call.getArgument(0);
              assertSame(statement, creator.createPreparedStatement(connection));
              RowMapper<BoardMessage> mapper = call.getArgument(1);
              return List.of(mapper.mapRow(row, 0));
            })
        .when(jdbc)
        .query(
            any(PreparedStatementCreator.class),
            org.mockito.ArgumentMatchers.<RowMapper<BoardMessage>>any());
    var store = new BoardStore(jdbc, () -> now, mock(AllowanceRepository.class));
    var result =
        store.post(
            new BoardStore.NewMessage(
                "topic", null, "person", "alice", null, null, "post", "title", "body", false,
                List.of()));
    assertEquals("inserted", result.id());
    assertEquals("body", result.body());
    assertEquals(now, result.postedAt());
    assertTrue(sql.get().contains("RETURNING id, topic, reply_to"));
    verify(statement).setString(10, "body");
    verify(statement).setObject(13, now.atOffset(ZoneOffset.UTC));
    verify(jdbc)
        .query(
            any(PreparedStatementCreator.class),
            org.mockito.ArgumentMatchers.<RowMapper<BoardMessage>>any());
    verifyNoMoreInteractions(jdbc);
  }

  @Test
  void topupReusesTheTopicAlreadyReturnedUnderItsLock() throws Exception {
    var jdbc = mock(JdbcTemplate.class);
    var dataSource = mock(DataSource.class);
    when(jdbc.getDataSource()).thenReturn(dataSource);
    when(dataSource.getConnection()).thenReturn(mock(Connection.class));
    var allowances = mock(AllowanceRepository.class);
    var store = spy(new BoardStore(jdbc, Instant::now, allowances));
    var topic = mock(BoardTopic.class);
    when(topic.potTotal()).thenReturn(10);
    var allowance = mock(AllowanceSnapshot.class);
    var id = new AllowanceId(UUID.randomUUID());
    when(allowance.id()).thenReturn(id);
    doReturn(Optional.of(topic)).when(store).lockTopic("root");
    doReturn(allowance).when(store).allowance("root");
    store.topup("root", 20, 1);
    verify(store).lockTopic("root");
    verify(store, never()).topic(anyString());
    verify(allowances).ceiling(id, 20);
    verify(jdbc).update("UPDATE board_topics SET reserve=?,state='open' WHERE id=?", 1, "root");
  }
}
