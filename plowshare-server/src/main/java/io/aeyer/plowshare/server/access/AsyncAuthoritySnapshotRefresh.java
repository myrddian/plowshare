package io.aeyer.plowshare.server.access;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * At most one refresh is queued or running. The worker has no caller transaction, so its ordinary
 * cache load sees committed rows and cannot deadlock its caller waiting for another pool lease.
 * Concurrent requests share the in-flight refresh; a later SQL miss may request another attempt.
 * Attempts start at least one second apart, so an oversized installation or a transaction older
 * than the snapshot's authority window cannot cause one full reload per item.
 */
public final class AsyncAuthoritySnapshotRefresh
    implements AuthoritySnapshotRefresh, AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(AsyncAuthoritySnapshotRefresh.class);
  private final AuthoritySnapshots snapshots;
  private final AtomicBoolean scheduled = new AtomicBoolean();
  private final ThreadPoolExecutor worker;
  private final LongSupplier nanoTime;
  private final long retryNanos;
  private volatile long nextAttempt;

  public AsyncAuthoritySnapshotRefresh(AuthoritySnapshots snapshots) {
    this(snapshots, Duration.ofSeconds(1), System::nanoTime);
  }

  AsyncAuthoritySnapshotRefresh(
      AuthoritySnapshots snapshots, Duration retryInterval, LongSupplier nanoTime) {
    this(snapshots, retryInterval, nanoTime, worker());
  }

  AsyncAuthoritySnapshotRefresh(
      AuthoritySnapshots snapshots,
      Duration retryInterval,
      LongSupplier nanoTime,
      ThreadPoolExecutor worker) {
    this.snapshots = Objects.requireNonNull(snapshots);
    this.nanoTime = Objects.requireNonNull(nanoTime);
    retryNanos = retryInterval.toNanos();
    if (retryNanos < 1)
      throw new IllegalArgumentException("Positive snapshot refresh retry interval required");
    nextAttempt = nanoTime.getAsLong();
    this.worker = Objects.requireNonNull(worker);
  }

  private static ThreadPoolExecutor worker() {
    return new ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1),
        task -> {
          var thread = new Thread(task, "authority-snapshot-refresh");
          thread.setDaemon(true);
          return thread;
        });
  }

  @Override
  public void request() {
    if (worker.isShutdown() || !scheduled.compareAndSet(false, true)) return;
    long now = nanoTime.getAsLong();
    if (now - nextAttempt < 0) {
      scheduled.set(false);
      return;
    }
    // Bound repeated cold/oversized/historical misses after a completed but unhelpful refresh.
    // This delays optimization only; every caller already has its own authoritative SQL rows.
    nextAttempt = now + retryNanos;
    try {
      worker.execute(
          () -> {
            try {
              snapshots.refresh();
            } catch (RuntimeException failure) {
              // Refresh is opportunistic; the checked statement already returned authoritative
              // rows. Retain observable failure without replacing that successful SQL answer.
              LOG.warn(
                  "Authority snapshot refresh failed; grant reads keep their SQL fallback",
                  failure);
            } finally {
              scheduled.set(false);
            }
          });
    } catch (RejectedExecutionException stopped) {
      scheduled.set(false);
      if (!worker.isShutdown())
        LOG.warn("Authority snapshot refresh could not be scheduled", stopped);
    }
  }

  @Override
  public void close() {
    worker.shutdownNow();
  }
}
