package io.aeyer.plowshare.server.reads;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import org.postgresql.jdbc.PgStatement;

/** PostgreSQL cancellation for one read statement within an existing monotonic read deadline. */
public final class JdbcReadDeadline {
  private JdbcReadDeadline() {}

  /**
   * An owned JDBC wrapper can tighten its cancellation and materialization deadline without
   * exposing a native handle. Applying this capability must never extend a previously applied
   * absolute deadline, including when the statement is reused.
   */
  public interface StatementDeadline {
    void constrainReadDeadline(long deadline) throws SQLException;
  }

  /**
   * Includes acquisition and row mapping in the per-query ceiling without renewing the operation.
   */
  public static long completionDeadline(long operationDeadline) {
    long queryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    return operationDeadline - queryDeadline < 0 ? operationDeadline : queryDeadline;
  }

  /**
   * Uses pgJDBC's millisecond timer rather than discarding a sub-second remainder through JDBC's
   * whole-second API. Preserve any shorter transaction/template timeout and cap each execution at
   * one second. The caller still owns admission, result materialization and late-result rejection;
   * cancellation does not authorize replay or prove an uncertain transaction rolled back.
   */
  public static void apply(Statement statement, long deadline) throws SQLException {
    long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
    if (remaining < 1) throw new ReadUnavailableException();
    if (statement.isWrapperFor(StatementDeadline.class)) {
      statement.unwrap(StatementDeadline.class).constrainReadDeadline(deadline);
      return;
    }
    PgStatement postgres = statement.unwrap(PgStatement.class);
    long configured = postgres.getQueryTimeoutMs();
    long timeout = Math.min(1000, remaining);
    postgres.setQueryTimeoutMs(configured > 0 ? Math.min(configured, timeout) : timeout);
  }
}
