package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.server.reads.ReadFailedException;
import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionSystemException;

/**
 * One alias transaction's private cancellation owner, not a general SQL execution API. It runs on
 * the requesting thread so existing authority/thread-bound transaction state is preserved. Owned
 * JDBC abort and server/network limits cancel SQL without interrupting the workflow thread.
 * Acquisition/materialization may return after expiry; its result is refused and the connection
 * remains owned until cleanup settles. Genuine caller interruption is observed, never cleared.
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
  private ReadFailedException.Reason terminalReason;
  private SQLException definiteSqlFailure;
  private Throwable ownedCancellation;

  /**
   * Record exact driver evidence at an owned query, never infer timeout from bare connection loss.
   */
  void observeSqlFailure(RuntimeException failure) {
    if (!(failure instanceof DataAccessException)) return;
    Throwable cause = failure;
    for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
      if (cause instanceof SocketTimeoutException
          || cause instanceof SQLTimeoutException
          || cause instanceof SQLException sql
              && ("57014".equals(sql.getSQLState()) || "55P03".equals(sql.getSQLState()))) {
        synchronized (lock) {
          if (ownedCancellation == null) ownedCancellation = cause;
        }
      }
      if (cause instanceof SQLException sql
          && sql.getSQLState() != null
          && (sql.getSQLState().startsWith("42") || sql.getSQLState().startsWith("23"))) {
        synchronized (lock) {
          if (definiteSqlFailure == null) definiteSqlFailure = sql;
        }
        return;
      }
    }
  }

  boolean preservesSqlDefect(RuntimeException failure) {
    SQLException observed;
    synchronized (lock) {
      observed = definiteSqlFailure;
    }
    return containsFailure(failure, observed);
  }

  boolean hasOwnedCancellation(RuntimeException failure) {
    Throwable observed;
    synchronized (lock) {
      observed = ownedCancellation;
    }
    return containsFailure(failure, observed);
  }

  private static boolean containsFailure(RuntimeException failure, Throwable observed) {
    if (observed == null) return false;
    for (int depth = 0; failure != null && depth < 16; ) {
      if (contains(failure, observed)) return true;
      if (failure instanceof TransactionSystemException transaction
          && contains(transaction.getApplicationException(), observed)) return true;
      Throwable next = failure.getCause();
      if (!(next instanceof RuntimeException runtime)) return false;
      failure = runtime;
      depth++;
    }
    return false;
  }

  private static boolean contains(Throwable cause, Throwable observed) {
    for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause())
      if (cause == observed) return true;
    return false;
  }

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
            () -> {
              expire();
            },
            Math.max(0, deadline - clock.getAsLong()),
            TimeUnit.NANOSECONDS);
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
    expire(ReadFailedException.Reason.DEADLINE_EXCEEDED);
  }

  private void expire(ReadFailedException.Reason reason) {
    synchronized (lock) {
      if (closed || expired) return;
      expired = true;
      terminalReason = reason;
      cancelled.run();
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
    expire(ReadFailedException.Reason.CLOSED);
  }

  ReadFailedException unavailable() {
    return unavailable(cancellationFailure);
  }

  ReadFailedException unavailable(Throwable failed) {
    synchronized (lock) {
      var reason = terminalReason;
      if (reason == null)
        reason =
            owner.isInterrupted()
                ? ReadFailedException.Reason.INTERRUPTED
                : clock.getAsLong() - deadline >= 0
                    ? ReadFailedException.Reason.DEADLINE_EXCEEDED
                    : ReadFailedException.Reason.QUERY_CANCELLED;
      return new AliasSqlFailure(reason, failed);
    }
  }

  private static final class AliasSqlFailure extends ReadFailedException {
    AliasSqlFailure(Reason reason, Throwable cause) {
      super(
          reason,
          "Conversation alias SQL deadline or cancellation prevented a result; commit may be uncertain. Inspect retained work and do not replay the operation.",
          cause);
    }
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
