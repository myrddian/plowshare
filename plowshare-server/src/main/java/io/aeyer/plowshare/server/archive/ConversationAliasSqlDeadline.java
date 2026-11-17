package io.aeyer.plowshare.server.archive;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * One alias transaction's private cancellation owner, not a general SQL execution API. It runs on
 * the requesting thread so existing authority/thread-bound transaction state is preserved.
 * Interruption bounds interruptible admission/acquisition; after acquisition JDBC abort closes the
 * still-owned connection. The transaction synchronization detaches it before pool cleanup/reuse.
 * Never clear an interrupt: caller cancellation and deadline interruption cannot safely be told
 * apart by Java's single interrupt bit.
 */
final class ConversationAliasSqlDeadline implements AutoCloseable {
  private final Object lock = new Object();
  private final long deadline;
  private final long queryNanos;
  private final LongSupplier clock;
  private final Thread owner;
  private final ScheduledExecutorService scheduler;
  private final Runnable cancelled;
  private final ScheduledFuture<?> alarm;
  private Connection connection;
  private Object query;
  private boolean expired;
  private boolean closed;

  ConversationAliasSqlDeadline(
      long deadline,
      Duration queryTimeout,
      ScheduledExecutorService scheduler,
      Runnable cancelled) {
    this(deadline, queryTimeout, scheduler, cancelled, System::nanoTime, Thread.currentThread());
  }

  ConversationAliasSqlDeadline(
      long deadline,
      Duration queryTimeout,
      ScheduledExecutorService scheduler,
      Runnable cancelled,
      LongSupplier clock,
      Thread owner) {
    this.deadline = deadline;
    this.queryNanos = queryTimeout.toNanos();
    this.scheduler = Objects.requireNonNull(scheduler);
    this.cancelled = Objects.requireNonNull(cancelled);
    this.clock = Objects.requireNonNull(clock);
    this.owner = Objects.requireNonNull(owner);
    this.alarm =
        scheduler.schedule(
            this::expire, Math.max(0, deadline - clock.getAsLong()), TimeUnit.NANOSECONDS);
  }

  void check() {
    synchronized (lock) {
      if (cancelledOrExpired()) throw unavailable();
    }
  }

  boolean cancelledOrExpired() {
    synchronized (lock) {
      return expired || clock.getAsLong() - deadline >= 0 || owner.isInterrupted();
    }
  }

  /** Register only a Spring transaction-bound connection, never a borrowed-and-returned handle. */
  void attach(Connection selected) throws SQLException {
    synchronized (lock) {
      if (closed) throw new IllegalStateException("Alias SQL scope is closed");
      if (expired || clock.getAsLong() - deadline >= 0) {
        selected.abort(Runnable::run);
        throw unavailable();
      }
      connection = Objects.requireNonNull(selected);
      // The actual driver IO is finite even if a cancellation races a broken socket. Hikari
      // observes this setter and restores the pooled connection's original setting at close.
      selected.setNetworkTimeout(Runnable::run, (int) Math.max(1, queryMillis()));
    }
  }

  /**
   * Called by afterCompletion while Spring still owns the handle, before cleanup can recycle it.
   */
  void detach() {
    synchronized (lock) {
      connection = null;
    }
  }

  long queryMillis() {
    check();
    return Math.max(1, Math.min(queryNanos, deadline - clock.getAsLong()) / 1_000_000L);
  }

  /** Covers result iteration/materialization as well as execution for each repository query. */
  Query beginQuery() {
    synchronized (lock) {
      check();
      if (query != null) throw new IllegalStateException("Alias SQL queries cannot overlap");
      Object token = new Object();
      query = token;
      var timeout =
          scheduler.schedule(
              () -> expireQuery(token),
              Math.min(queryNanos, deadline - clock.getAsLong()),
              TimeUnit.NANOSECONDS);
      return new Query(token, timeout);
    }
  }

  private void expireQuery(Object token) {
    synchronized (lock) {
      if (query == token) expire();
    }
  }

  private void expire() {
    synchronized (lock) {
      if (closed || expired) return;
      expired = true;
      cancelled.run();
      owner.interrupt();
      if (connection != null) {
        try {
          // Abort is performed while holding the ownership fence. Detach cannot let the pool
          // reuse the handle before this driver cleanup settles, even if cancellation is late.
          connection.abort(Runnable::run);
        } catch (SQLException failed) {
          // Keep the scope expired; the owning transaction still rolls back/closes. Cancellation
          // failure must never become a successful result or a retryable capacity refusal.
          cancellationFailure = failed;
        }
      }
    }
  }

  private SQLException cancellationFailure;

  /** Shutdown shares the same ownership fence as expiry; already detached/closed work is safe. */
  void cancel() {
    expire();
  }

  ArchiveUnavailableException unavailable() {
    return new ArchiveUnavailableException(
        "Conversation alias SQL deadline or cancellation prevented a result; commit may be uncertain",
        cancellationFailure);
  }

  ArchiveUnavailableException unavailable(Throwable failed) {
    return new ArchiveUnavailableException(
        "Conversation alias SQL deadline or cancellation prevented a result; commit may be uncertain",
        failed);
  }

  @Override
  public void close() {
    synchronized (lock) {
      closed = true;
      connection = null;
      query = null;
      alarm.cancel(false);
    }
  }

  final class Query implements AutoCloseable {
    private final Object token;
    private final ScheduledFuture<?> timeout;

    private Query(Object token, ScheduledFuture<?> timeout) {
      this.token = token;
      this.timeout = timeout;
    }

    @Override
    public void close() {
      synchronized (lock) {
        if (query == token) query = null;
        timeout.cancel(false);
      }
    }
  }
}
