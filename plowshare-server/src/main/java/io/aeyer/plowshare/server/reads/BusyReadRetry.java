package io.aeyer.plowshare.server.reads;

import java.time.Duration;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Bounded retry of a side-effect-free check refused with {@link ReadUnavailableException}. A busy
 * refusal means a cached read reached its deadline, usually while another request's reload was
 * still running; that reload stores its value, so a short pause usually turns the next attempt into
 * a hit.
 *
 * <p>Only checks that change nothing may be retried, such as the agent job runtime's per-step log
 * check. A caller holding a Spring-managed transaction or synchronization is never retried: it may
 * hold locks or connections that the reload it waits for needs, so it keeps refusing at once, like
 * {@code JdbcInformationLogVisibility.read}'s callback. Any other failure propagates at once.
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
   * Four attempts with pauses of 100, 200 and 400 ms: at most 0.7 s of waiting on top of each
   * attempt's own read deadline.
   */
  public static BusyReadRetry defaults() {
    return new BusyReadRetry(4, Duration.ofMillis(100), Thread::sleep);
  }

  /**
   * Runs {@code check}, retrying it after a busy refusal until it passes or the attempts are spent.
   * The last refusal is rethrown unchanged; an interrupt during a pause ends the retries at once,
   * with the interrupt status restored.
   */
  public void run(Runnable check) {
    Objects.requireNonNull(check);
    Duration next = backoff;
    for (int attempt = 1; ; attempt++) {
      try {
        check.run();
        return;
      } catch (ReadUnavailableException busy) {
        if (attempt >= attempts || holdsTransaction()) throw busy;
        try {
          pause.sleep(next);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw busy;
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
