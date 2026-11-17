package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.reads.ReadDeadline;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Same-thread Ex job-admission deadline. This is neither continuing authority nor cancellation of
 * admitted work. The final JobStore gate linearizes admission before its first start effect; an
 * admitted durable job may subsequently start on its worker under the ordinary lifecycle.
 */
public final class ExternalConversationAdmission {
  private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();

  private ExternalConversationAdmission() {}

  public static <T> T within(Duration budget, Supplier<T> action) {
    Objects.requireNonNull(action);
    return ReadDeadline.within(
        budget,
        () -> {
          Long previous = DEADLINE.get();
          long end = ReadDeadline.deadlineNanos(budget);
          if (previous != null && previous - end < 0) end = previous;
          DEADLINE.set(end);
          try {
            return action.get();
          } finally {
            if (previous == null) DEADLINE.remove();
            else DEADLINE.set(previous);
          }
        });
  }

  /** No-op for ordinary submissions. Call once before any Ex job start linkage, never after it. */
  static void requireAdmission() {
    Long end = DEADLINE.get();
    if (end != null && System.nanoTime() - end >= 0)
      throw new ExternalConversationUnavailableException(
          "External run admission expired; no new job was admitted at this boundary", null);
  }
}
