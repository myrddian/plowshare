package io.aeyer.plowshare.server.pool;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Caller-owned SQL completion scope, not a transaction or SQL executor. The action remains on the
 * calling thread. Nested work inherits the earlier deadline; asynchronous jobs retain their own
 * lifecycle and do not inherit this scope. No JDBC handles or lazy results may escape the action.
 */
public interface SqlWorkBudget {
  <T> T within(Duration budget, Supplier<T> action);

  /** Cache owners preserve hits/joiners but must not elect a shared load for scoped SQL work. */
  static boolean privateCacheMisses() {
    return ScopedSqlWorkBudget.current() != null;
  }
}
