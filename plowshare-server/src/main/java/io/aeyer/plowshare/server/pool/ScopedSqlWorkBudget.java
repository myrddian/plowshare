package io.aeyer.plowshare.server.pool;

import com.zaxxer.hikari.HikariDataSource;
import io.aeyer.plowshare.server.reads.ReadDeadline;
import io.aeyer.plowshare.server.reads.ReadFailedException;
import io.aeyer.plowshare.server.reads.ReadUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.SocketTimeoutException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Database infrastructure for an explicit caller scope. Watchdogs only retire logical work and
 * enqueue bounded physical cleanup; they never wait for JDBC while holding a scope monitor.
 * Connection leases remain owned until abort/close actually settles, including after caller exit.
 */
public final class ScopedSqlWorkBudget implements SqlWorkBudget, AutoCloseable {
  private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
  private final SqlWorkBudgetProperties limits;
  private final MeterRegistry metrics;
  private final Counter refusedResults;
  private final ScheduledThreadPoolExecutor alarms;
  private final ExecutorService cleanup;
  private final SqlSessionDeadlines sessionDeadlines;
  private final Set<Scope> active = ConcurrentHashMap.newKeySet();
  private final Object lifecycle = new Object();
  private boolean closed;

  public ScopedSqlWorkBudget(SqlWorkBudgetProperties limits, MeterRegistry metrics) {
    this(limits, metrics, new PostgresSqlSessionDeadlines());
  }

  ScopedSqlWorkBudget(
      SqlWorkBudgetProperties limits, MeterRegistry metrics, SqlSessionDeadlines sessionDeadlines) {
    this.sessionDeadlines = Objects.requireNonNull(sessionDeadlines);
    this.limits = Objects.requireNonNull(limits);
    this.metrics = metrics;
    this.refusedResults =
        metrics == null ? null : metrics.counter("plowshare.sql.work.results.refused");
    alarms =
        new ScheduledThreadPoolExecutor(
            2, Thread.ofPlatform().daemon().name("sql-work-deadline-", 0).factory());
    alarms.setRemoveOnCancelPolicy(true);
    alarms.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    cleanup =
        new ThreadPoolExecutor(
            limits.cleanupWorkers(),
            limits.cleanupWorkers(),
            0,
            TimeUnit.NANOSECONDS,
            new ArrayBlockingQueue<>(Math.multiplyExact(limits.scopes(), limits.leasesPerScope())),
            Thread.ofPlatform().daemon().name("sql-work-cleanup-", 0).factory());
    if (metrics != null) {
      metrics.gauge("plowshare.sql.work.owned.scopes", active, Set::size);
      metrics.gauge(
          "plowshare.sql.work.physical.leases",
          active,
          scopes -> scopes.stream().mapToInt(scope -> scope.leases.size()).sum());
    }
  }

  static Scope current() {
    return CURRENT.get();
  }

  /** Composition wraps repository/independent-version sources, never the raw primary pool bean. */
  public DataSource decorate(DataSource source) {
    return decorate(source, source instanceof HikariDataSource pool ? pool : null);
  }

  /** Exact pool ownership remains private to database composition and scoped lease cleanup. */
  public DataSource decorate(DataSource source, HikariDataSource pool) {
    return new ScopedSqlDataSource(source, pool);
  }

  @Override
  public <T> T within(Duration budget, Supplier<T> action) {
    SqlWorkBudgetProperties.positive(budget);
    Objects.requireNonNull(action);
    return ReadDeadline.within(
        budget,
        () -> {
          Scope previous = CURRENT.get();
          long end = ReadDeadline.deadlineNanos(budget);
          if (previous != null) {
            var nested = previous.deadline(end);
            try {
              previous.check();
              T result = action.get();
              previous.check();
              return result;
            } finally {
              nested.close();
            }
          }
          if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException(
                "SQL work scope must begin before its transaction acquires a connection");
          Scope scope;
          synchronized (lifecycle) {
            // Physical SQL ownership remains counted until the driver/cleanup really settles.
            if (closed) throw ReadFailedException.closed();
            if (active.size() >= limits.scopes())
              throw new ReadUnavailableException(
                  "SQL work capacity is busy; do not replay an enclosing operation", null);
            scope = new Scope(end);
            active.add(scope);
          }
          long started = System.nanoTime();
          CURRENT.set(scope);
          try {
            scope.check();
            T result = action.get();
            scope.check();
            scope.checkReleased();
            return result;
          } catch (RuntimeException failure) {
            if (scope.preserves(failure)) throw failure;
            if (failure instanceof ReadUnavailableException) throw failure;
            if (failure instanceof ReadFailedException read) {
              if (read.reason() == ReadFailedException.Reason.INTERRUPTED && scope.cancelled())
                throw scope.failure(read);
              throw read;
            }
            if (scope.cancelled()) throw scope.failure(failure);
            throw failure;
          } finally {
            CURRENT.remove();
            scope.finish();
            duration("operation", started);
          }
        });
  }

  private static long earlier(long first, long second) {
    return first - second < 0 ? first : second;
  }

  /** Only owned JDBC boundaries classify driver/server timeout evidence. */
  private static boolean timedOut(Throwable failed) {
    for (int depth = 0; failed != null && depth < 16; depth++, failed = failed.getCause()) {
      if (failed instanceof SocketTimeoutException || failed instanceof SQLTimeoutException)
        return true;
      if (failed instanceof SQLException sql
          && ("57014".equals(sql.getSQLState()) || "55P03".equals(sql.getSQLState()))) return true;
    }
    return false;
  }

  private void duration(String phase, long start) {
    if (metrics == null) return;
    long elapsed = System.nanoTime() - start;
    Timer.builder("plowshare.sql.work.duration")
        .tag("phase", phase)
        .publishPercentileHistogram()
        .serviceLevelObjectives(
            Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2))
        .register(metrics)
        .record(elapsed, TimeUnit.NANOSECONDS);
    if (phase.equals("query") && elapsed >= Duration.ofMillis(500).toNanos())
      metrics.counter("plowshare.sql.work.query.breach", "threshold", "500ms").increment();
    if (phase.equals("query") && elapsed >= Duration.ofSeconds(1).toNanos())
      metrics.counter("plowshare.sql.work.query.breach", "threshold", "1s").increment();
  }

  private void retired(Scope scope) {
    active.remove(scope);
    synchronized (lifecycle) {
      if (closed && active.isEmpty()) {
        cleanup.shutdown();
      }
    }
  }

  @Override
  public void close() {
    ArrayList<Scope> scopes;
    synchronized (lifecycle) {
      if (closed) return;
      closed = true;
      scopes = new ArrayList<>(active);
    }
    scopes.forEach(scope -> scope.expire(ReadFailedException.Reason.CLOSED));
    alarms.shutdown();
    synchronized (lifecycle) {
      if (active.isEmpty()) {
        cleanup.shutdown();
      }
    }
    try {
      cleanup.awaitTermination(limits.cleanupBudget().toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    // A non-settled driver still owns its bounded cleanup slot. The last scope retires the
    // executor; shutdown does not fabricate physical release or abandon a late acquired lease.
  }

  final class Scope {
    private final Thread owner = Thread.currentThread();
    private final Object lock = new Object();
    private Throwable definiteSqlFailure;
    private final Set<Lease> leases = ConcurrentHashMap.newKeySet();
    private final Set<Query> queries = ConcurrentHashMap.newKeySet();
    private long end;
    private boolean expired;
    private ReadFailedException.Reason terminalReason;
    private boolean running = true;
    private int acquiring;
    private ScheduledFuture<?> alarm;
    private Object alarmToken;

    Scope(long end) {
      this.end = end;
      arm();
    }

    private void arm() {
      Object token = new Object();
      alarmToken = token;
      alarm =
          alarms.schedule(
              () -> {
                expire(token);
              },
              Math.max(0, end - System.nanoTime()),
              TimeUnit.NANOSECONDS);
    }

    AutoCloseableDeadline deadline(long selected) {
      synchronized (lock) {
        long previous = end;
        if (selected - end < 0) {
          end = selected;
          alarm.cancel(false);
          arm();
        }
        return () -> {
          synchronized (lock) {
            end = previous;
            if (!expired && running) {
              alarm.cancel(false);
              arm();
            }
          }
        };
      }
    }

    void check() {
      synchronized (lock) {
        if (expired || !running || owner.isInterrupted() || System.nanoTime() - end >= 0) {
          if (refusedResults != null) refusedResults.increment();
          throw failure(null);
        }
      }
    }

    void checkReleased() {
      synchronized (lock) {
        if (acquiring != 0 || !leases.isEmpty())
          throw new IllegalStateException(
              "SQL work cannot return live JDBC handles or lazy results");
      }
    }

    boolean cancelled() {
      synchronized (lock) {
        return expired || owner.isInterrupted() || System.nanoTime() - end >= 0;
      }
    }

    ReadFailedException failure(Throwable cause) {
      synchronized (lock) {
        var reason = terminalReason;
        if (reason == null)
          reason =
              !running
                  ? ReadFailedException.Reason.CLOSED
                  : owner.isInterrupted()
                      ? ReadFailedException.Reason.INTERRUPTED
                      : ReadFailedException.Reason.DEADLINE_EXCEEDED;
        return switch (reason) {
          case CLOSED -> ReadFailedException.closed(cause);
          case INTERRUPTED -> ReadFailedException.interrupted(cause);
          case QUERY_CANCELLED -> ReadFailedException.queryCancelled(cause);
          case DEADLINE_EXCEEDED -> ReadFailedException.deadlineExceeded(cause);
          case INVALIDATED -> ReadFailedException.invalidated();
        };
      }
    }

    boolean preserves(Throwable failure) {
      Throwable observed;
      synchronized (lock) {
        observed = definiteSqlFailure;
      }
      if (observed == null) return false;
      for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
        if (failure == observed) return true;
        if (failure instanceof TransactionSystemException transaction) {
          Throwable application = transaction.getApplicationException();
          for (int nested = 0;
              application != null && nested < 16;
              nested++, application = application.getCause())
            if (application == observed) return true;
        }
      }
      return false;
    }

    void preserveSqlDefect(Throwable failure) {
      synchronized (lock) {
        if (definiteSqlFailure == null) definiteSqlFailure = failure;
      }
    }

    void expire() {
      expire(null, ReadFailedException.Reason.DEADLINE_EXCEEDED);
    }

    void expire(ReadFailedException.Reason reason) {
      expire(null, reason);
    }

    private void expire(Object expectedAlarm) {
      expire(expectedAlarm, ReadFailedException.Reason.DEADLINE_EXCEEDED);
    }

    private void expire(Object expectedAlarm, ReadFailedException.Reason reason) {
      ArrayList<Lease> owned;
      synchronized (lock) {
        if (expectedAlarm != null && alarmToken != expectedAlarm) return;
        if (expired || !running) return;
        expired = true;
        terminalReason = reason;
        owned = new ArrayList<>(leases);
      }
      if (metrics != null) metrics.counter("plowshare.sql.work.cancellations").increment();
      owned.forEach(Lease::abort);
      // A SQL watchdog never interrupts the enclosing workflow thread: Java cannot distinguish
      // that signal from genuine caller cancellation. Owned connection abort plus server/network
      // limits end JDBC work; acquisition/materialization can return late, and no late result is
      // published. Its physical capacity remains owned until the driver and cleanup settle.
    }

    Connection borrow(ScopedSqlDataSource.Borrow borrow, HikariDataSource pool)
        throws SQLException {
      long started = System.nanoTime();
      synchronized (lock) {
        check();
        if (acquiring + leases.size() >= limits.leasesPerScope())
          throw new IllegalStateException("SQL work exceeded its configured connection bound");
        acquiring++;
      }
      Connection connection = null;
      Lease lease = null;
      try {
        connection = borrow.get();
        synchronized (lock) {
          // Register even a late lease so physical close remains owned until it settles.
          lease = new Lease(connection, pool);
          leases.add(lease);
        }
        if (cancelled()) {
          lease.abort();
          throw failure(null);
        }
        lease.networkDeadline(earlier(end, System.nanoTime() + limits.queryCeiling().toNanos()));
        check();
        return ScopedSqlDataSource.connection(lease);
      } catch (Throwable failure) {
        try {
          if (lease != null) lease.abort();
          else if (connection != null) connection.close();
        } catch (Throwable closing) {
          failure.addSuppressed(closing);
        }
        if (failure instanceof Error fatal) throw fatal;
        if (cancelled()) throw failure(failure);
        throw failure;
      } finally {
        synchronized (lock) {
          acquiring--;
        }
        duration("acquisition", started);
        releaseIfDone();
      }
    }

    Query query(Lease lease, String phase) {
      return query(lease, phase, false, 0);
    }

    private Query query(Lease lease, String phase, boolean constrained, long ceiling) {
      synchronized (lock) {
        check();
        if (queries.size() >= limits.queriesPerScope())
          throw new IllegalStateException("SQL work exceeded its configured query bound");
        long deadline = earlier(end, System.nanoTime() + limits.queryCeiling().toNanos());
        if (constrained) deadline = earlier(deadline, ceiling);
        for (Query parent : queries) deadline = earlier(deadline, parent.end);
        Query query = new Query(lease, deadline, phase);
        queries.add(query);
        return query;
      }
    }

    void finish() {
      ArrayList<Lease> owned;
      synchronized (lock) {
        running = false;
        alarmToken = null;
        alarm.cancel(false);
        owned = new ArrayList<>(leases);
      }
      // Escaping handles/lazy results are not successful scope completion. They are cancelled,
      // not handed to another caller as uninstrumented JDBC work.
      owned.forEach(
          lease -> {
            lease.detachCaller();
            lease.abort();
          });
      releaseIfDone();
    }

    private void releaseIfDone() {
      synchronized (lock) {
        if (running || acquiring != 0 || !leases.isEmpty()) return;
      }
      retired(this);
    }

    final class Lease {
      final Connection connection;
      private final Object state = new Object();
      private final AtomicBoolean closing = new AtomicBoolean();
      private final HikariDataSource pool;
      private final Connection poolHandle;
      private final SqlSessionDeadlines.Session session;
      private final AtomicInteger statements = new AtomicInteger();
      private final CompletableFuture<Void> settled = new CompletableFuture<>();

      Lease(Connection connection, HikariDataSource pool) {
        this.connection = connection;
        this.pool = pool;
        poolHandle =
            connection instanceof SqlPoolLease captured ? captured.poolHandle() : connection;
        session = sessionDeadlines.attach(connection);
      }

      void beforeStatement(Query query) throws SQLException {
        session.beforeStatement(
            () -> {
              query.check();
              return query.remainingMillis();
            });
        query.check();
      }

      void networkDeadline(long selected) throws SQLException {
        synchronized (state) {
          if (closing.get()) throw ReadFailedException.closed();
          long millis = TimeUnit.NANOSECONDS.toMillis(selected - System.nanoTime());
          if (millis < 1) throw ReadFailedException.deadlineExceeded();
          // Physical cleanup takes this same fence after logical retirement has been queued.
          // A clamp cannot target a recycled lease; a blocked setter cannot occupy alarm threads.
          connection.setNetworkTimeout(Runnable::run, (int) Math.min(Integer.MAX_VALUE, millis));
        }
      }

      void statementOpened() {
        if (statements.incrementAndGet() > limits.queriesPerScope()) {
          statements.decrementAndGet();
          throw new IllegalStateException("SQL work exceeded its configured statement bound");
        }
      }

      void statementClosed() {
        statements.decrementAndGet();
      }

      void check() throws SQLException {
        Scope.this.check();
        if (Thread.currentThread() != owner)
          throw new IllegalStateException("Scoped JDBC must remain on its caller thread");
        if (closing.get()) throw ReadFailedException.closed();
      }

      Query query(String phase) {
        return Scope.this.query(this, phase);
      }

      Query query(String phase, long ceiling) {
        return Scope.this.query(this, phase, true, ceiling);
      }

      void abort() {
        release(true);
      }

      void detachCaller() {
        if (connection instanceof SqlPoolLease owned) owned.callerFinished();
      }

      void close() throws SQLException {
        detachCaller();
        release(false);
        long remaining;
        synchronized (lock) {
          remaining = Math.max(0, end - System.nanoTime());
        }
        if (remaining == 0 || Thread.currentThread().isInterrupted()) return;
        try {
          settled.get(Math.min(remaining, limits.cleanupBudget().toNanos()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw Scope.this.failure(interrupted);
        } catch (TimeoutException late) {
          Scope.this.expire();
          throw Scope.this.failure(late);
        } catch (ExecutionException failed) {
          throw ReadFailedException.closed(failed.getCause());
        }
      }

      private void release(boolean abort) {
        // Logical retirement must not acquire a monitor held across a JDBC call: alarm threads
        // only claim this one bounded cleanup task. Physical cleanup waits for any in-flight
        // setter under its existing fence, preserving ownership until abort/close really settles.
        if (!closing.compareAndSet(false, true)) return;
        cleanup.execute(
            () -> {
              synchronized (state) {
                detachCaller();
                long started = System.nanoTime();
                Throwable failed = null;
                boolean discarded = abort;
                long cleanupEnd =
                    earlier(end, System.nanoTime() + limits.cleanupBudget().toNanos());
                try {
                  if (!discarded) {
                    try {
                      session.restore(
                          () -> {
                            long remaining =
                                TimeUnit.NANOSECONDS.toMillis(cleanupEnd - System.nanoTime());
                            if (remaining < 1) throw ReadFailedException.deadlineExceeded();
                            connection.setNetworkTimeout(
                                Runnable::run, (int) Math.min(Integer.MAX_VALUE, remaining));
                            return remaining;
                          });
                    } catch (SQLException | RuntimeException resetFailed) {
                      discarded = true;
                      failed = resetFailed;
                    }
                  }
                  if (discarded) {
                    // Exact lease eviction happens BEFORE outer close can recycle its Hikari proxy.
                    // Never use an old proxy to evict a replacement borrower after pool return.
                    if (pool != null) pool.evictConnection(poolHandle);
                    connection.abort(Runnable::run);
                  }
                } catch (SQLException | RuntimeException failure) {
                  if (failed == null) failed = failure;
                  else failed.addSuppressed(failure);
                } finally {
                  boolean physicallySettled = false;
                  try {
                    connection.close();
                    physicallySettled = true;
                  } catch (SQLException | RuntimeException failure) {
                    if (failed == null) failed = failure;
                    else failed.addSuppressed(failure);
                  } finally {
                    queries.stream().filter(q -> q.lease == this).toList().forEach(Query::close);
                    // Failed close is quarantined within this finite slot, never reported as
                    // release.
                    if (physicallySettled) leases.remove(this);
                    if (failed == null) settled.complete(null);
                    else settled.completeExceptionally(failed);
                    duration("cleanup", started);
                    releaseIfDone();
                  }
                }
              }
            });
      }
    }

    final class Query implements AutoCloseable {
      final Lease lease;
      final long end;
      private final long started = System.nanoTime();
      private final String phase;
      private final ScheduledFuture<?> alarm;
      private boolean done;

      Query(Lease lease, long end, String phase) {
        this.lease = lease;
        this.end = end;
        this.phase = phase;
        alarm =
            alarms.schedule(
                this::expire, Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS);
      }

      private void expire() {
        synchronized (this) {
          if (done) return;
          Scope.this.expire();
        }
      }

      long remainingMillis() {
        long selected;
        synchronized (lock) {
          selected = earlier(end, Scope.this.end);
        }
        long remaining = TimeUnit.NANOSECONDS.toMillis(selected - System.nanoTime());
        if (remaining < 1) throw ReadFailedException.deadlineExceeded();
        return remaining;
      }

      void failed(Throwable failure) {
        // Preserve only an exact defect observed at an owned JDBC invocation, even if its
        // response races expiry. Do not infer a business failure from an arbitrary cause chain.
        Throwable observed = failure;
        for (int depth = 0;
            observed != null && depth < 16;
            depth++, observed = observed.getCause()) {
          if (observed instanceof SQLException sql
              && sql.getSQLState() != null
              && (sql.getSQLState().startsWith("42") || sql.getSQLState().startsWith("23"))) {
            Scope.this.preserveSqlDefect(observed);
            return;
          }
          if (observed.getCause() == observed) break;
        }
        if (timedOut(failure)) {
          // A driver network/server deadline can win the watchdog race. Mark cancellation at
          // the actual JDBC boundary, not by relabelling arbitrary application exceptions.
          Scope.this.expire(ReadFailedException.Reason.QUERY_CANCELLED);
          throw Scope.this.failure(failure);
        }
      }

      boolean finished() {
        synchronized (this) {
          return done;
        }
      }

      void check() throws SQLException {
        lease.check();
        if (finished()) return;
        long selected;
        synchronized (lock) {
          selected = earlier(end, Scope.this.end);
        }
        if (System.nanoTime() - selected >= 0) {
          Scope.this.expire();
          throw Scope.this.failure(null);
        }
        lease.networkDeadline(selected);
      }

      @Override
      public void close() {
        synchronized (this) {
          if (done) return;
          done = true;
          alarm.cancel(false);
        }
        queries.remove(this);
        duration(phase, started);
      }
    }
  }

  @FunctionalInterface
  private interface AutoCloseableDeadline extends AutoCloseable {
    @Override
    void close();
  }
}
