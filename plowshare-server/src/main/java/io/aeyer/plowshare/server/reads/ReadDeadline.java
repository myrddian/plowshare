package io.aeyer.plowshare.server.reads;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Supplier;

/**
 * An explicit monotonic SQL work-unit deadline. Statements participating in one transaction inherit
 * the earlier deadline instead of renewing it. This context is not a timer for an entire search,
 * cache workflow, model call or multi-transaction retry loop.
 *
 * <p>The deadline is thread-confined. A component that hands SQL work to another thread must carry
 * its remaining transaction budget explicitly; merely waiting for a worker does not propagate it.
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

  /** Existing enclosing SQL/cache work-unit deadline, without opening a new workflow-wide timer. */
  public static OptionalLong currentDeadlineNanos() {
    Long current = DEADLINE.get();
    return current == null ? OptionalLong.empty() : OptionalLong.of(current);
  }

  /** Rejects late results and prevents a nested stage from starting after the owning deadline. */
  public static void check() {
    Long deadline = DEADLINE.get();
    if (deadline != null && System.nanoTime() - deadline >= 0)
      throw ReadFailedException.deadlineExceeded();
  }

  private static Duration checked(Duration budget) {
    Objects.requireNonNull(budget);
    if (budget.isNegative() || budget.isZero())
      throw new IllegalArgumentException("Positive read budget required");
    return budget;
  }
}
