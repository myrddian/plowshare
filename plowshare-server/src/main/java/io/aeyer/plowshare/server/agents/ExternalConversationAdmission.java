package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.reads.ReadFailedException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Same-thread Ex pre-start cancellation context. Elapsed workflow time never cancels admission. The
 * final JobStore gate checks genuine caller interruption before its first start effect; an admitted
 * durable job subsequently owns its ordinary lifecycle and never inherits this context.
 */
public final class ExternalConversationAdmission {
  private static final ThreadLocal<Object> CURRENT = new ThreadLocal<>();

  private ExternalConversationAdmission() {}

  public static <T> T within(Duration budget, Supplier<T> action) {
    Objects.requireNonNull(budget);
    if (budget.isZero() || budget.isNegative())
      throw new IllegalArgumentException("Positive observation threshold required");
    return within(action);
  }

  public static <T> T within(Supplier<T> action) {
    Objects.requireNonNull(action);
    Object previous = CURRENT.get();
    CURRENT.set(new Object());
    try {
      return action.get();
    } finally {
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    }
  }

  /** No-op for ordinary submissions. Call once before any Ex job start linkage, never after it. */
  static void requireAdmission() {
    if (CURRENT.get() != null && Thread.currentThread().isInterrupted())
      throw ReadFailedException.interrupted();
  }
}
