package io.aeyer.plowshare.server.reads;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import org.postgresql.jdbc.PgStatement;

/** PostgreSQL cancellation for one read statement within an existing monotonic SQL deadline. */
public final class JdbcReadDeadline {
  private JdbcReadDeadline() {}

  /**
   * An owned JDBC wrapper can tighten cancellation/materialization without exposing a native
   * handle. Reused statements never extend an earlier applied absolute deadline.
   */
  public interface StatementDeadline {
    void constrainReadDeadline(long deadline) throws SQLException;
  }

  /** Includes acquisition/mapping and preserves an earlier active SQL transaction deadline. */
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
    if (statement.isWrapperFor(StatementDeadline.class)) {
      statement.unwrap(StatementDeadline.class).constrainReadDeadline(completionDeadline(deadline));
      return;
    }
    PgStatement postgres = statement.unwrap(PgStatement.class);
    long configured = postgres.getQueryTimeoutMs();
    long timeout = Math.min(2000, remaining);
    postgres.setQueryTimeoutMs(configured > 0 ? Math.min(configured, timeout) : timeout);
  }
}
