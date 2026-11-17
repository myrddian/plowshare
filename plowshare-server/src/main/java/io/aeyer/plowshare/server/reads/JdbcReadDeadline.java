package io.aeyer.plowshare.server.reads;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import org.postgresql.jdbc.PgStatement;

/** PostgreSQL cancellation for one read statement within an existing monotonic SQL deadline. */
public final class JdbcReadDeadline {
  private JdbcReadDeadline() {}

  /**
   * Includes acquisition and row mapping in the per-query ceiling and preserves any earlier
   * deadline belonging to the current SQL transaction.
   */
  public static long completionDeadline(long sqlDeadline) {
    long queryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    return sqlDeadline - queryDeadline < 0 ? sqlDeadline : queryDeadline;
  }

  /**
   * Uses pgJDBC's millisecond timer rather than discarding a sub-second remainder through JDBC's
   * whole-second API. Preserve any shorter transaction/template timeout and cap each execution at
   * two seconds. The caller still owns admission, result materialization and late-result rejection;
   * cancellation does not authorize replay or prove an uncertain transaction rolled back.
   */
  public static void apply(Statement statement, long deadline) throws SQLException {
    long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
    if (remaining < 1) throw ReadFailedException.deadlineExceeded();
    PgStatement postgres = statement.unwrap(PgStatement.class);
    long configured = postgres.getQueryTimeoutMs();
    long timeout = Math.min(2000, remaining);
    postgres.setQueryTimeoutMs(configured > 0 ? Math.min(configured, timeout) : timeout);
  }
}
