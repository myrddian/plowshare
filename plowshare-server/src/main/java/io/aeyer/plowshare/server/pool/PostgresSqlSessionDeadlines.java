package io.aeyer.plowshare.server.pool;

import io.aeyer.plowshare.server.reads.ReadFailedException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

/**
 * Scoped server-side cancellation, complementary to the client socket/watchdog boundary. Limits
 * apply to actual commands; transaction-local settings roll back normally. Autocommit settings are
 * restored before pool return, or the exact physical lease is discarded. No SQL/data API is exposed
 * to application services.
 */
final class PostgresSqlSessionDeadlines implements SqlSessionDeadlines {
  private static final String LIMIT =
      """
      WITH previous AS MATERIALIZED (
        SELECT max(setting::bigint) FILTER (WHERE name='statement_timeout') AS statement_ms,
               max(setting::bigint) FILTER (WHERE name='lock_timeout') AS lock_ms
          FROM pg_settings WHERE name IN ('statement_timeout','lock_timeout')
      )
      SELECT statement_ms,lock_ms,
             set_config('statement_timeout',
               least(nullif(statement_ms,0),nullif(?,0),?)::text || 'ms',?),
             set_config('lock_timeout',
               least(nullif(lock_ms,0),nullif(?,0),?)::text || 'ms',?)
        FROM previous
      """;
  private static final String RESTORE =
      "SELECT set_config('statement_timeout',?::text || 'ms',false),"
          + "set_config('lock_timeout',?::text || 'ms',false)";

  @Override
  public Session attach(Connection connection) {
    return new Session() {
      private boolean attempted;
      private boolean initialized;
      private boolean sessionTouched;
      private long statementOriginal;
      private long lockOriginal;
      private long lastSetupMillis;

      @Override
      public void beforeStatement(Remaining remaining) throws SQLException {
        // Setup uses the same query deadline, including its round trips. A measured reserve
        // prevents setup latency itself from extending the server command past that deadline.
        for (int attempt = 0; attempt < 3; attempt++) {
          long available = remaining.millis();
          long reserve = Math.max(2, Math.multiplyExact(lastSetupMillis, 2));
          long target = available - reserve;
          if (target < 1) throw ReadFailedException.deadlineExceeded();
          boolean local = !connection.getAutoCommit();
          attempted = true;
          long started = System.nanoTime();
          long previousStatement;
          long previousLock;
          try (PreparedStatement setup = connection.prepareStatement(LIMIT)) {
            setup.setQueryTimeout(2);
            setup.setLong(1, initialized ? statementOriginal : 0);
            setup.setLong(2, target);
            setup.setBoolean(3, local);
            setup.setLong(4, initialized ? lockOriginal : 0);
            setup.setLong(5, target);
            setup.setBoolean(6, local);
            try (ResultSet values = setup.executeQuery()) {
              if (!values.next())
                throw new SQLException("Scoped SQL deadline setup returned no settings");
              previousStatement = values.getLong(1);
              previousLock = values.getLong(2);
            }
          }
          lastSetupMillis =
              Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + 1);
          if (!initialized) {
            statementOriginal = previousStatement;
            lockOriginal = previousLock;
            initialized = true;
          }
          if (!local) sessionTouched = true;
          long applied = limit(previousStatement, statementOriginal, target);
          if (applied <= remaining.millis()) return;
        }
        throw ReadFailedException.deadlineExceeded();
      }

      @Override
      public void restore(Remaining remaining) throws SQLException {
        if (!attempted) return;
        if (!initialized) throw new SQLException("Scoped SQL session settings are uncertain");
        if (!sessionTouched) return; // owning transaction commits/rolls back its local settings
        if (!connection.getAutoCommit())
          throw new SQLException(
              "Scoped session settings cannot be restored inside unfinished work");
        remaining.millis();
        try (PreparedStatement restore = connection.prepareStatement(RESTORE)) {
          restore.setQueryTimeout(2);
          restore.setLong(1, statementOriginal);
          restore.setLong(2, lockOriginal);
          restore.execute();
        }
        remaining.millis();
      }
    };
  }

  private static long limit(long previous, long baseline, long requested) {
    long result = requested;
    if (previous > 0) result = Math.min(result, previous);
    if (baseline > 0) result = Math.min(result, baseline);
    return result;
  }
}
