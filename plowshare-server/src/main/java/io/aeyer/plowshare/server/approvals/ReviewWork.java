package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.server.hooks.Deadline;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** One monotonic budget shared by SECURITY, the bot and all queue waits in an approval review. */
public record ReviewWork(Deadline deadline, BooleanSupplier cancelled) {
  public ReviewWork {
    Objects.requireNonNull(deadline);
    Objects.requireNonNull(cancelled);
    if (deadline.limit().isZero() || deadline.limit().isNegative())
      throw new IllegalArgumentException("Review work needs a positive finite budget");
  }

  public boolean expired() {
    return deadline.remaining().isZero();
  }

  public boolean stopped() {
    return cancelled.getAsBoolean() || Thread.currentThread().isInterrupted() || expired();
  }

  public Duration remaining() {
    return deadline.remaining();
  }
}
