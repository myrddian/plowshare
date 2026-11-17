package io.aeyer.plowshare.server.reads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.PreparedStatement;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.postgresql.jdbc.PgStatement;

class JdbcReadDeadlineTest {
  @Test
  void queryCompletionCeilingInheritsAnEarlierOperationDeadline() {
    long earlier = System.nanoTime() + Duration.ofMillis(500).toNanos();
    assertEquals(earlier, JdbcReadDeadline.completionDeadline(earlier));
    long started = System.nanoTime();
    long bounded = JdbcReadDeadline.completionDeadline(started + Duration.ofSeconds(10).toNanos());
    assertTrue(bounded - System.nanoTime() <= Duration.ofSeconds(2).toNanos());
    assertTrue(bounded - started > Duration.ofSeconds(1).toNanos());
  }

  @Test
  void aSubSecondRemainderStillStartsWithPhysicalCancellation() throws Exception {
    var statement = mock(PreparedStatement.class);
    var postgres = mock(PgStatement.class);
    when(statement.unwrap(PgStatement.class)).thenReturn(postgres);
    JdbcReadDeadline.apply(statement, System.nanoTime() + Duration.ofMillis(800).toNanos());
    verify(postgres).setQueryTimeoutMs(longThat(value -> value > 0 && value <= 800));
  }

  @Test
  void eachQueryIsCappedAtTwoSecondsAndPreservesAShorterConfiguredTimeout() throws Exception {
    var statement = mock(PreparedStatement.class);
    var postgres = mock(PgStatement.class);
    when(statement.unwrap(PgStatement.class)).thenReturn(postgres);
    JdbcReadDeadline.apply(statement, System.nanoTime() + Duration.ofSeconds(10).toNanos());
    verify(postgres).setQueryTimeoutMs(2000);
    when(postgres.getQueryTimeoutMs()).thenReturn(125L);
    JdbcReadDeadline.apply(statement, System.nanoTime() + Duration.ofSeconds(10).toNanos());
    verify(postgres).setQueryTimeoutMs(125);
  }

  @Test
  void expiryCannotStartAnotherStatement() {
    var statement = mock(PreparedStatement.class);
    assertThrows(
        ReadFailedException.class, () -> JdbcReadDeadline.apply(statement, System.nanoTime() - 1));
    verifyNoInteractions(statement);
  }
}
