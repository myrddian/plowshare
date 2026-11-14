package io.aeyer.plowshare.server.reads;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The one deadline of a cached read. A projection cache opens it when a request reaches the cache;
 * every wait on the requester's thread inside it, the FETCH slot wait included, draws on what
 * remains of the same budget. A nested cache read inherits the earlier, enclosing deadline.
 *
 * <p>The deadline is thread-confined: the elected loader runs inline on its requester's thread, so
 * its slot wait sees its requester's deadline. Work handed to another thread does not carry it.
 */
public final class ReadDeadline {
  private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();

  private ReadDeadline() {}

  /**
   * Runs {@code action} with a deadline {@code budget} from now, or the enclosing deadline if that
   * is earlier, and restores the enclosing one afterwards.
   */
  public static <T> T within(Duration budget, Supplier<T> action) {
    Objects.requireNonNull(action);
    long deadline = System.nanoTime() + checked(budget).toNanos();
    Long enclosing = DEADLINE.get();
    if (enclosing != null && enclosing - deadline < 0) deadline = enclosing;
    DEADLINE.set(deadline);
    try {
      return action.get();
    } finally {
      if (enclosing == null) DEADLINE.remove();
      else DEADLINE.set(enclosing);
    }
  }

  /**
   * {@link System#nanoTime()} of the current deadline, or {@code fallback} from now outside one.
   */
  public static long deadlineNanos(Duration fallback) {
    Long current = DEADLINE.get();
    return current != null ? current : System.nanoTime() + checked(fallback).toNanos();
  }

  private static Duration checked(Duration budget) {
    Objects.requireNonNull(budget);
    if (budget.isNegative() || budget.isZero())
      throw new IllegalArgumentException("Positive read budget required");
    return budget;
  }
}
