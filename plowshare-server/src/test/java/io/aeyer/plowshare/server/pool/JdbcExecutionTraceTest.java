package io.aeyer.plowshare.server.pool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import javax.sql.DataSource;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcExecutionTraceTest {
  @TempDir Path temporary;

  @Test
  void tracePreservesBindsCancellationBatchCloseUnwrapAndOriginalFailures() throws Exception {
    var source = mock(DataSource.class);
    var connection = mock(Connection.class);
    var statement = mock(PreparedStatement.class);
    when(source.getConnection()).thenReturn(connection);
    when(connection.prepareStatement("SELECT ?")).thenReturn(statement);
    when(connection.unwrap(Connection.class)).thenReturn(connection);
    var trace = mock(JdbcExecutionTrace.class);
    var span = mock(JdbcExecutionTrace.Span.class);
    when(trace.begin(anyString(), anyString())).thenReturn(span);
    var failed = new SQLException("private details must stay out of trace");
    when(statement.executeQuery()).thenThrow(failed);
    var data = new TracedDataSource(source, trace);
    try (var borrowed = data.getConnection();
        var prepared = borrowed.prepareStatement("SELECT ?")) {
      assertEquals(borrowed, borrowed);
      assertEquals(prepared, prepared);
      assertNotEquals(connection, borrowed);
      assertEquals("TracedStatement", prepared.toString());
      prepared.setString(1, "private bind");
      assertSame(failed, assertThrows(SQLException.class, prepared::executeQuery));
      prepared.cancel();
      prepared.addBatch();
      prepared.executeBatch();
      assertSame(borrowed, prepared.getConnection());
      assertSame(connection, borrowed.unwrap(Connection.class));
    }
    verify(statement).setString(1, "private bind");
    verify(statement).cancel();
    verify(statement).addBatch();
    verify(statement).executeBatch();
    verify(statement).close();
    verify(connection).close();
    verify(span).failed();
    verify(span, times(2)).close();
    verify(trace).begin("SELECT ?", "executeQuery");
    verify(trace).begin("SELECT ?", "executeBatch");
  }

  @Test
  void optInRecordingContainsOnlyFingerprintsAndExactExecuteCounts() throws Exception {
    var trace = new JfrJdbcExecutionTrace();
    var source = mock(DataSource.class);
    var connection = mock(Connection.class);
    var statement = mock(Statement.class);
    when(source.getConnection()).thenReturn(connection);
    when(connection.createStatement()).thenReturn(statement);
    String secretSql = "SELECT 'sensitive literal must never be recorded'";
    Path file = temporary.resolve("jdbc.jfr");
    try (var recording = new Recording()) {
      recording.enable("plowshare.JdbcExecute").withThreshold(Duration.ZERO).withStackTrace();
      recording.start();
      try (var borrowed = new TracedDataSource(source, trace).getConnection();
          var query = borrowed.createStatement()) {
        query.execute(secretSql);
        when(statement.execute(secretSql)).thenThrow(new SQLException("sensitive error"));
        assertThrows(SQLException.class, () -> query.execute(secretSql));
      }
      recording.stop();
      recording.dump(file);
    }
    var events =
        RecordingFile.readAllEvents(file).stream()
            .filter(event -> event.getEventType().getName().equals("plowshare.JdbcExecute"))
            .toList();
    assertEquals(2, events.size());
    assertEquals(events.get(0).getString("fingerprint"), events.get(1).getString("fingerprint"));
    assertTrue(events.get(0).getString("fingerprint").matches("[a-f0-9]{64}"));
    assertFalse(events.get(0).getBoolean("failed"));
    assertTrue(events.get(1).getBoolean("failed"));
    assertNotNull(events.get(0).getStackTrace());
    for (var event : events) {
      assertFalse(event.toString().contains("sensitive literal"));
      assertFalse(event.toString().contains("sensitive error"));
    }
  }
}
