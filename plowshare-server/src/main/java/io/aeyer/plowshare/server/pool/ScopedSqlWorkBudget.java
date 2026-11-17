package io.aeyer.plowshare.server.pool;

import com.zaxxer.hikari.HikariDataSource;
import io.aeyer.plowshare.server.reads.ReadDeadline;
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
  private final ThreadPoolExecutor interruptions;
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
    interruptions =
        new ThreadPoolExecutor(
            limits.scopes(),
            limits.scopes(),
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(limits.scopes()),
            Thread.ofPlatform().daemon().name("sql-work-interrupt-", 0).factory());
    interruptions.allowCoreThreadTimeOut(true);
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
            throw unavailable(
                null); // a connection acquired before this scope cannot be instrumented
          Scope scope;
          synchronized (lifecycle) {
            // Each registration reserves one interruption worker before any caller work or
            // checkout. A blocked interruptor retains this slot; another admitted scope never
            // queues behind it. Workers are created lazily only when interruption is needed.
            if (closed || active.size() >= limits.scopes()) throw unavailable(null);
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
            if (scope.cancelled()) throw unavailable(failure);
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

  private SqlWorkUnavailableException unavailable(Throwable cause) {
    return new SqlWorkUnavailableException(cause);
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
        interruptions.shutdown();
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
    scopes.forEach(Scope::expire);
    alarms.shutdown();
    synchronized (lifecycle) {
      if (active.isEmpty()) {
        cleanup.shutdown();
        interruptions.shutdown();
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
    private final Object interruptFence = new Object();
    private volatile boolean interruptEligible = true;
    private boolean interruptPending;
    private Throwable definiteSqlFailure;
    private final Set<Lease> leases = ConcurrentHashMap.newKeySet();
    private final Set<Query> queries = ConcurrentHashMap.newKeySet();
    private long end;
    private boolean expired;
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
          throw unavailable(null);
        }
      }
    }

    void checkReleased() {
      synchronized (lock) {
        if (acquiring != 0 || !leases.isEmpty()) throw unavailable(null);
      }
    }

    boolean cancelled() {
      synchronized (lock) {
        return expired || owner.isInterrupted() || System.nanoTime() - end >= 0;
      }
    }

    boolean preserves(Throwable failure) {
      Throwable observed;
      synchronized (lock) {
        observed = definiteSqlFailure;
      }
      if (observed == null) return false;
      for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause())
        if (failure == observed) return true;
      return false;
    }

    void preserveSqlDefect(Throwable failure) {
      synchronized (lock) {
        if (definiteSqlFailure == null) definiteSqlFailure = failure;
      }
    }

    void expire() {
      expire(null);
    }

    private void expire(Object expectedAlarm) {
      ArrayList<Lease> owned;
      synchronized (lock) {
        if (expectedAlarm != null && alarmToken != expectedAlarm) return;
        if (expired || !running) return;
        expired = true;
        owned = new ArrayList<>(leases);
        interruptPending = true;
      }
      if (metrics != null) metrics.counter("plowshare.sql.work.cancellations").increment();
      owned.forEach(Lease::abort);
      // Thread.interrupt may synchronously execute a blocking channel interruptor. It must never
      // run on an alarm thread or under the scope monitor. The fence prevents a queued or active
      // interrupt from hitting the owner's next task; physical ownership is retained until return.
      interruptions.execute(
          () -> {
            try {
              synchronized (interruptFence) {
                if (interruptEligible) owner.interrupt();
              }
            } finally {
              synchronized (lock) {
                interruptPending = false;
              }
              releaseIfDone();
            }
          });
    }

    Connection borrow(ScopedSqlDataSource.Borrow borrow, HikariDataSource pool)
        throws SQLException {
      long started = System.nanoTime();
      synchronized (lock) {
        check();
        if (acquiring + leases.size() >= limits.leasesPerScope()) throw unavailable(null);
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
          throw unavailable(null);
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
        if (cancelled()) throw unavailable(failure);
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
        if (queries.size() >= limits.queriesPerScope()) throw unavailable(null);
        long deadline = earlier(end, System.nanoTime() + limits.queryCeiling().toNanos());
        if (constrained) deadline = earlier(deadline, ceiling);
        for (Query parent : queries) deadline = earlier(deadline, parent.end);
        Query query = new Query(lease, deadline, phase);
        queries.add(query);
        return query;
      }
    }

    void finish() {
      // Do not let an already executing interruptor race reuse of this caller thread. An arbitrary
      // blocking interruptor is a retained physical cancellation, not evidence of bounded abort.
      synchronized (interruptFence) {
        interruptEligible = false;
      }
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
        if (running || acquiring != 0 || interruptPending || !leases.isEmpty()) return;
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
          if (closing.get()) throw new SQLException("Scoped JDBC lease is closed", "08003");
          long millis = TimeUnit.NANOSECONDS.toMillis(selected - System.nanoTime());
          if (millis < 1) throw unavailable(null);
          // Physical cleanup takes this same fence after logical retirement has been queued.
          // A clamp cannot target a recycled lease; a blocked setter cannot occupy alarm threads.
          connection.setNetworkTimeout(Runnable::run, (int) Math.min(Integer.MAX_VALUE, millis));
        }
      }

      void statementOpened() {
        if (statements.incrementAndGet() > limits.queriesPerScope()) {
          statements.decrementAndGet();
          throw unavailable(null);
        }
      }

      void statementClosed() {
        statements.decrementAndGet();
      }

      void check() throws SQLException {
        Scope.this.check();
        if (Thread.currentThread() != owner)
          throw new SQLException("Scoped JDBC must remain on its caller thread");
        if (closing.get()) throw new SQLException("Scoped JDBC lease is closed", "08003");
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
          throw unavailable(interrupted);
        } catch (TimeoutException late) {
          Scope.this.expire();
          throw unavailable(late);
        } catch (ExecutionException failed) {
          throw unavailable(failed.getCause());
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
                            if (remaining < 1) throw unavailable(null);
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
        if (remaining < 1) throw unavailable(null);
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
          Scope.this.expire();
          throw unavailable(failure);
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
          throw unavailable(null);
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
