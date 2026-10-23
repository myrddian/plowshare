package io.aeyer.plowshare.server.pool;

import io.aeyer.plowshare.server.pool.AdmissionThrottle.Admission;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * A {@link DataSource} that waits on an {@link AdmissionThrottle} before borrowing from the
 * delegate pool, and gives the permit back when the caller closes the connection.
 *
 * <p>The decorator only waits. It never refuses a borrow and never turns a borrow failure into a
 * capacity decision: a {@link SQLException} (or any other failure) from the delegate propagates
 * unchanged, because a capacity signal raised from inside a database call would let a client treat
 * a partially committed operation as flow control and replay it.
 *
 * <p>Ordering: the permit is taken first, then the delegate is asked for a connection and timed. If
 * the borrow fails, the permit is released before the failure propagates, so a failing pool cannot
 * leak permits. A successful borrow feeds its measured latency to {@link
 * AdmissionThrottle#observed}. A failed borrow feeds the maximum latency: a failure is never
 * evidence of health, and a fast failure sampled at its own tiny latency would lower pressure and
 * make a burst of failures look like an idle pool. A pool timeout is the real maximum anyway, and
 * borrow failures are database or pool faults rather than one bad statement, so they are not
 * attributable to a single caller.
 *
 * <p><b>Handles must be closed.</b> The permit is held by the admission handle behind the returned
 * connection and released by its {@code close()}. A caller that never closes its connection leaks
 * the permit until the process ends (pool leak detection only logs; it does not reclaim), and,
 * because the throttle's depth is per thread, every later borrow on that same thread is then
 * treated as nested: it still takes a permit while one is free, but at saturation it is admitted
 * permit-free rather than waiting. That is the same contract the pool itself has for unclosed
 * connections; the throttle only widens its consequence, so callers must use try-with-resources.
 */
public final class ThrottledDataSource extends AbstractDataSource {

  private final DataSource delegate;
  private final AdmissionThrottle throttle;
  private final LongSupplier nanoClock;

  public ThrottledDataSource(DataSource delegate, AdmissionThrottle throttle) {
    this(delegate, throttle, System::nanoTime);
  }

  /** Package-private so tests can drive the latency measurement with a fake clock. */
  ThrottledDataSource(DataSource delegate, AdmissionThrottle throttle, LongSupplier nanoClock) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.throttle = Objects.requireNonNull(throttle, "throttle");
    this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
  }

  @Override
  public Connection getConnection() throws SQLException {
    return borrow(delegate::getConnection);
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return borrow(() -> delegate.getConnection(username, password));
  }

  /** The delegate's borrow, which may fail with the database's own {@link SQLException}. */
  @FunctionalInterface
  private interface Borrow {
    Connection get() throws SQLException;
  }

  private Connection borrow(Borrow borrow) throws SQLException {
    Admission admission;
    try {
      admission = throttle.acquire();
    } catch (InterruptedException interrupted) {
      // Nothing is held when acquire is interrupted. Restore the flag and surface the interrupt as
      // the database-call failure callers already handle; it is not a capacity refusal.
      Thread.currentThread().interrupt();
      throw new SQLException("interrupted while waiting to borrow a connection", interrupted);
    }
    Connection connection = null;
    try {
      // Started after acquire() returns, so the throttle's own wait is not measured as latency.
      long started = nanoClock.getAsLong();
      try {
        connection = borrow.get();
      } catch (Throwable failure) {
        sampleFailure(failure);
        throw failure;
      }
      throttle.observed(Duration.ofNanos(nanoClock.getAsLong() - started));
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              new ReleasingHandler(connection, admission));
    } catch (Throwable failure) {
      // Any failure before hand-off, including an Error, must return the permit: otherwise it is
      // held for the life of the process, shrinking capacity, and the thread-local depth stays at 1
      // so every later acquire on this thread is treated as nested. A nested acquire takes a permit
      // only while one is free and otherwise proceeds permit-free as an overdraft. The
      // release is in a finally so that no throwable from closing the connection can skip it. A
      // connection already borrowed but not yet handed off is closed so it does not leak either.
      // The original failure propagates; problems during cleanup are attached as suppressed.
      try {
        if (connection != null) {
          connection.close();
        }
      } catch (Throwable closing) {
        failure.addSuppressed(closing);
      } finally {
        admission.close();
      }
      throw failure;
    }
  }

  /**
   * Records a failed borrow as a maximum-latency sample. If the sampling itself fails, that is
   * attached to the borrow's failure so the caller still sees the original instance.
   */
  private void sampleFailure(Throwable failure) {
    try {
      throttle.observed(Duration.ofNanos(Long.MAX_VALUE));
    } catch (Throwable sampling) {
      failure.addSuppressed(sampling);
    }
  }

  /**
   * Delegates every call to the real connection, except that {@code close()} also releases the
   * permit. {@code unwrap}/{@code isWrapperFor} are delegated untouched so callers reach the
   * driver's connection rather than this proxy. {@code abort(Executor)} is also delegated without
   * releasing the permit; callers must still {@code close()} an aborted connection.
   */
  private static final class ReleasingHandler implements InvocationHandler {
    private final Connection connection;
    private final Admission admission;
    private final AtomicBoolean closed = new AtomicBoolean();

    ReleasingHandler(Connection connection, Admission admission) {
      this.connection = connection;
      this.admission = admission;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if (method.getDeclaringClass() == Object.class) {
        return switch (method.getName()) {
          case "equals" -> proxy == args[0];
          case "hashCode" -> System.identityHashCode(proxy);
          default -> connection.toString();
        };
      }
      if (method.getName().equals("close") && method.getParameterCount() == 0) {
        close();
        return null;
      }
      try {
        return method.invoke(connection, args);
      } catch (InvocationTargetException failure) {
        // Rethrow the connection's own exception, not the reflection wrapper.
        throw failure.getCause();
      }
    }

    /**
     * Closes the real connection and releases the permit exactly once. Repeat closes are no-ops, as
     * {@link Connection#close()} requires; the permit is released even if the real close fails.
     */
    private void close() throws SQLException {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      try {
        connection.close();
      } finally {
        admission.close();
      }
    }
  }
}
