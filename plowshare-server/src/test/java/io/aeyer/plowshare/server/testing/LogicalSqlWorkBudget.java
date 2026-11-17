package io.aeyer.plowshare.server.testing;

import io.aeyer.plowshare.server.pool.SqlWorkBudget;
import io.aeyer.plowshare.server.reads.ReadDeadline;
import java.time.Duration;
import java.util.function.Supplier;

/** Same-thread service fixture only; SQL cancellation is verified by the owning pool tests. */
public final class LogicalSqlWorkBudget implements SqlWorkBudget {
  @Override
  public <T> T within(Duration budget, Supplier<T> action) {
    return ReadDeadline.within(budget, action);
  }
}
