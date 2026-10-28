package io.aeyer.plowshare.server.pool;

/**
 * JDBC infrastructure tracing. Implementations must retain no SQL, binds, messages or identities.
 */
public interface JdbcExecutionTrace {
  interface Span extends AutoCloseable {
    void failed();

    @Override
    void close();
  }

  /** Starts one physical JDBC execute invocation; batch execution is one invocation. */
  Span begin(String sql, String method);
}
