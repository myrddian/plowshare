package io.aeyer.plowshare.server.pool;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Caller-owned completion scope for one query or physical SQL transaction, not a facade/workflow
 * timer or SQL executor. The action remains on the calling thread and must not invoke hooks,
 * providers or job admission. Nested SQL inherits the earlier transaction deadline; independent
 * queries and asynchronous jobs retain their own budgets. No JDBC handles or lazy results may
 * escape the action.
 */
public interface SqlWorkBudget {
  <T> T within(Duration budget, Supplier<T> action);

  /** Applies the configured SQL deadline before the owning transaction acquires a connection. */
  default UnitOfWork transactions(UnitOfWork work, Duration budget) {
    return new UnitOfWork() {
      @Override
      public <T> T inTransaction(Supplier<T> action) {
        return within(budget, () -> work.inTransaction(action));
      }

      @Override
      public void afterCommit(Runnable action) {
        work.afterCommit(action);
      }
    };
  }

  /** Cache owners preserve hits/joiners but must not elect a shared load for scoped SQL work. */
  static boolean privateCacheMisses() {
    return ScopedSqlWorkBudget.current() != null;
  }
}
