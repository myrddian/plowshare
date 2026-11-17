package io.aeyer.plowshare.server.reads;

import java.time.Duration;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Bounded recovery of a side-effect-free check refused by capacity or its own read deadline. A
 * still-running shared cache fill can serve a later independent attempt. Invalidation, closure,
 * interruption and query cancellation are different failures and are not retried here.
 *
 * <p>Only checks that change nothing may be retried, such as the agent job runtime's per-step log
 * check. A caller holding a Spring-managed transaction or synchronization is never retried: it may
 * hold locks or connections that the reload it waits for needs, so a busy result propagates at
 * once. Any other failure also propagates at once.
 */
public final class BusyReadRetry {
  /** Sleeps between attempts; tests substitute a recording pause. */
  @FunctionalInterface
  public interface Pause {
    void sleep(Duration duration) throws InterruptedException;
  }

  private final int attempts;
  private final Duration backoff;
  private final Pause pause;

  /**
   * @param attempts total attempts, 1 to 10; 1 disables retry
   * @param backoff the first pause, doubled before each later attempt; positive and at most 1s
   */
  public BusyReadRetry(int attempts, Duration backoff, Pause pause) {
    Objects.requireNonNull(backoff);
    if (attempts < 1
        || attempts > 10
        || backoff.isNegative()
        || backoff.isZero()
        || backoff.compareTo(Duration.ofSeconds(1)) > 0)
      throw new IllegalArgumentException("Invalid busy read retry bounds");
    this.attempts = attempts;
    this.backoff = backoff;
    this.pause = Objects.requireNonNull(pause);
  }

  /**
   * Four attempts with pauses of 100, 200 and 400 ms. Separate SQL transactions retain their own
   * completion budgets; this loop does not invent a deadline for the whole workflow. An already
   * enclosing SQL/cache deadline still limits every remaining attempt and pause.
   */
  public static BusyReadRetry defaults() {
    return new BusyReadRetry(4, Duration.ofMillis(100), Thread::sleep);
  }

  /**
   * Runs {@code check}, retrying it after a busy refusal until it passes or the attempts are spent.
   * Exhausted retryable failures are rethrown unchanged. An interrupt during a pause ends recovery
   * with an interrupted read failure and restores the interrupt status.
   */
  public void run(Runnable check) {
    Objects.requireNonNull(check);
    Duration next = backoff;
    for (int attempt = 1; ; attempt++) {
      ReadDeadline.check();
      if (Thread.currentThread().isInterrupted()) throw ReadFailedException.interrupted();
      try {
        check.run();
        ReadDeadline.check();
        return;
      } catch (ReadUnavailableException | ReadFailedException busy) {
        if (busy instanceof ReadFailedException failed
            && failed.reason() != ReadFailedException.Reason.DEADLINE_EXCEEDED) throw failed;
        if (attempt >= attempts || holdsTransaction() || Thread.currentThread().isInterrupted())
          throw busy;
        Duration waiting = next;
        var enclosing = ReadDeadline.currentDeadlineNanos();
        if (enclosing.isPresent()) {
          long remaining = enclosing.getAsLong() - System.nanoTime();
          if (remaining <= 0) throw ReadFailedException.deadlineExceeded();
          waiting = Duration.ofNanos(Math.min(next.toNanos(), remaining));
        }
        try {
          pause.sleep(waiting);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw ReadFailedException.interrupted(interrupted);
        }
        next = next.multipliedBy(2);
      }
    }
  }

  private static boolean holdsTransaction() {
    return TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive();
  }
}
