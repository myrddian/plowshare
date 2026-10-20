package io.aeyer.plowshare.server.lifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;

/** Bounded shutdown for periodic workers whose current transaction must finish before teardown. */
public final class WorkerShutdown {
  private static final Duration DRAIN = Duration.ofSeconds(10);
  private static final Duration CANCEL = Duration.ofSeconds(5);

  private WorkerShutdown() {}

  /**
   * Stop scheduling first, then let the current pass release its connections without interruption.
   * A stuck pass is cancelled after the grace period; failures to terminate are reported rather
   * than hidden. Durable queue leases/checkpoints, not an executor resubmission, recover abandoned
   * work.
   */
  public static void stop(ExecutorService worker, String name) {
    stop(worker, name, DRAIN, CANCEL);
  }

  static void stop(ExecutorService worker, String name, Duration drain, Duration cancel) {
    if (worker == null) return;
    Objects.requireNonNull(name);
    var log = LoggerFactory.getLogger(WorkerShutdown.class);
    worker.shutdown();
    boolean interrupted = false;
    try {
      try {
        if (worker.awaitTermination(drain.toNanos(), TimeUnit.NANOSECONDS)) return;
        log.warn("Worker '{}' exceeded its shutdown grace period; cancelling remaining work", name);
      } catch (InterruptedException stop) {
        interrupted = true;
        log.warn("Interrupted while draining worker '{}'; cancelling remaining work", name);
      }
      worker.shutdownNow();
      try {
        if (!worker.awaitTermination(cancel.toNanos(), TimeUnit.NANOSECONDS))
          log.error("Worker '{}' did not terminate before its shutdown deadline", name);
      } catch (InterruptedException stop) {
        interrupted = true;
        log.error("Interrupted while awaiting cancelled worker '{}'", name);
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }
}
