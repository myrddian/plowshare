package io.aeyer.plowshare.server.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Exercises the decorator with a fake pool and a fake connection; no database is involved. */
class ThrottledDataSourceTest {

  private static final Duration CEILING = Duration.ofSeconds(1);

  private final AtomicLong clock = new AtomicLong();
  private final AdmissionThrottle throttle =
      new AdmissionThrottle(
          4, 0, Duration.ofMillis(10), CEILING, clock::get, () -> 1.0, delay -> {});
  private final FakePool pool = new FakePool();
  private final ThrottledDataSource dataSource =
      new ThrottledDataSource(pool, throttle, clock::get);

  /** Records every call made to the underlying connection, by method name. */
  private static final class FakeConnection implements InvocationHandler {
    final List<String> calls = new ArrayList<>();
    Throwable closeFailure;
    final Connection connection =
        (Connection)
            Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, this);

    @Override
    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args)
        throws Throwable {
      calls.add(method.getName());
      if (method.getName().equals("close") && closeFailure != null) {
        throw closeFailure;
      }
      return switch (method.getName()) {
        case "isWrapperFor" -> true;
        case "unwrap" -> connection;
        case "isClosed" -> false;
        case "nativeSQL" -> "native:" + args[0];
        default -> null;
      };
    }
  }

  private final class FakePool extends AbstractDataSource {
    private final AtomicLong poolClock;

    FakePool() {
      this(clock);
    }

    FakePool(AtomicLong poolClock) {
      this.poolClock = poolClock;
    }

    final FakeConnection fake = new FakeConnection();
    SQLException failure;
    Error error;
    long borrowNanos;
    String lastUser;
    String lastPassword;

    @Override
    public Connection getConnection() throws SQLException {
      poolClock.addAndGet(borrowNanos);
      if (error != null) {
        throw error;
      }
      if (failure != null) {
        throw failure;
      }
      return fake.connection;
    }

    @Override
    public Connection getConnection(String user, String password) throws SQLException {
      lastUser = user;
      lastPassword = password;
      return getConnection();
    }
  }

  @Test
  void a_connection_is_borrowed_from_the_delegate_and_the_permit_is_held() throws Exception {
    Connection connection = dataSource.getConnection();

    assertEquals(1, throttle.held());
    assertFalse(connection == pool.fake.connection, "the caller must receive the proxy");
    connection.close();
  }

  @Test
  void the_credentialed_overload_is_throttled_too() throws Exception {
    Connection connection = dataSource.getConnection("user", "secret");

    assertEquals(1, throttle.held());
    assertEquals("user", pool.lastUser);
    assertEquals("secret", pool.lastPassword);
    connection.close();
    assertEquals(0, throttle.held());
  }

  @Test
  void closing_the_connection_releases_the_permit() throws Exception {
    Connection connection = dataSource.getConnection();
    connection.close();

    assertEquals(0, throttle.held());
    assertEquals(List.of("close"), pool.fake.calls);
  }

  @Test
  void closing_twice_releases_once() throws Exception {
    Connection outer = dataSource.getConnection();
    // A nested borrow is a second physical connection, so it takes a permit of its own while one
    // is free. Each handle releases only the permit it took; the throttle's own handle is already
    // idempotent, so the discriminating assertion here is that the real close() is reached once
    // per handle and the repeat is absorbed.
    Connection inner = dataSource.getConnection();
    assertEquals(2, throttle.held());

    outer.close();
    outer.close();
    assertEquals(1, throttle.held(), "the inner connection still holds its own permit");

    inner.close();
    assertEquals(0, throttle.held());
    assertEquals(
        2,
        pool.fake.calls.stream().filter("close"::equals).count() - 0,
        "each distinct handle reaches the real close() once; the repeat is absorbed");
  }

  @Test
  void a_failure_to_borrow_releases_the_permit() {
    SQLException failure = new SQLException("pool exhausted", "08001");
    pool.failure = failure;

    SQLException thrown = assertThrows(SQLException.class, dataSource::getConnection);

    assertSame(failure, thrown, "a database failure is propagated unchanged, never converted");
    assertEquals(0, throttle.held());
  }

  @Test
  void a_runtime_failure_to_borrow_releases_the_permit() {
    DataSource exploding =
        new AbstractDataSource() {
          @Override
          public Connection getConnection() {
            throw new IllegalStateException("boom");
          }

          @Override
          public Connection getConnection(String user, String password) {
            throw new IllegalStateException("boom");
          }
        };
    ThrottledDataSource broken = new ThrottledDataSource(exploding, throttle, clock::get);

    assertThrows(IllegalStateException.class, broken::getConnection);
    assertEquals(0, throttle.held());
  }

  private void assertNextBorrowTakesARealPermit() {
    try (Connection connection = dataSource.getConnection()) {
      assertEquals(1, throttle.held());
      assertFalse(connection.isClosed());
    } catch (SQLException e) {
      throw new AssertionError(e);
    }
    assertEquals(0, throttle.held());
  }

  /**
   * Delay applied to a borrow that follows one taking {@code borrowNanos}, while the throttle's own
   * sleeper advances the clock by {@code sleepNanos}. Each call builds fresh state so scenarios do
   * not share pressure.
   */
  private Duration delayAfterBorrowing(long borrowNanos, long sleepNanos, boolean fail)
      throws Exception {
    AtomicLong localClock = new AtomicLong();
    AdmissionThrottle local =
        new AdmissionThrottle(
            4,
            0,
            Duration.ofMillis(10),
            CEILING,
            localClock::get,
            () -> 1.0,
            delay -> localClock.addAndGet(sleepNanos));
    FakePool localPool = new FakePool(localClock);
    ThrottledDataSource source = new ThrottledDataSource(localPool, local, localClock::get);
    localPool.borrowNanos = borrowNanos;
    if (fail) {
      localPool.failure = new SQLException("timeout");
      assertThrows(SQLException.class, source::getConnection);
      localPool.failure = null;
    } else {
      source.getConnection().close();
    }
    localPool.borrowNanos = 0;
    source.getConnection().close();
    return local.lastDelay();
  }

  @Test
  void the_observed_acquire_latency_is_fed_to_the_throttle() throws Exception {
    Duration fast = delayAfterBorrowing(0, 0, false);
    Duration slow = delayAfterBorrowing(CEILING.toNanos() / 4, 0, false);

    assertTrue(slow.compareTo(fast) > 0, "a slow borrow must lengthen the next delay");
  }

  @Test
  void the_interval_excludes_the_throttles_own_wait() throws Exception {
    // A huge throttle sleep must not register as latency: only the delegate's borrow is timed.
    Duration quiet = delayAfterBorrowing(CEILING.toNanos() / 4, 0, false);
    Duration slept = delayAfterBorrowing(CEILING.toNanos() / 4, CEILING.toNanos() * 10, false);

    assertEquals(quiet, slept);
  }

  @Test
  void a_failed_borrow_still_feeds_a_sample() throws Exception {
    Duration fast = delayAfterBorrowing(0, 0, false);
    Duration timedOut = delayAfterBorrowing(CEILING.toNanos() / 4, 0, true);

    assertTrue(timedOut.compareTo(fast) > 0, "a pool timeout must raise pressure");
  }

  @Test
  void an_error_from_the_pool_releases_the_permit_and_propagates() {
    StackOverflowError error = new StackOverflowError();
    pool.error = error;

    assertSame(error, assertThrows(StackOverflowError.class, dataSource::getConnection));
    assertEquals(0, throttle.held());
    pool.error = null;
    // The thread must not be left looking nested: the next borrow takes a real permit.
    assertNextBorrowTakesARealPermit();
  }

  @Test
  void an_interrupt_while_waiting_holds_nothing_and_is_reported_as_a_database_failure() {
    AdmissionThrottle interrupting =
        new AdmissionThrottle(
            4,
            0,
            Duration.ofMillis(10),
            CEILING,
            clock::get,
            () -> 1.0,
            delay -> {
              throw new InterruptedException();
            });
    ThrottledDataSource interrupted = new ThrottledDataSource(pool, interrupting, clock::get);

    SQLException thrown = assertThrows(SQLException.class, interrupted::getConnection);

    assertTrue(thrown.getCause() instanceof InterruptedException);
    assertTrue(Thread.interrupted(), "the interrupt flag must be restored (and cleared here)");
    assertEquals(0, interrupting.held());
  }

  @Test
  void a_fast_failure_never_lowers_pressure() throws Exception {
    pool.borrowNanos = CEILING.toNanos() / 4;
    dataSource.getConnection().close();
    pool.borrowNanos = 0;
    dataSource.getConnection().close();
    Duration established = throttle.lastDelay();

    pool.failure = new SQLException("refused immediately");
    assertThrows(SQLException.class, dataSource::getConnection);
    pool.failure = null;
    dataSource.getConnection().close();

    assertTrue(
        throttle.lastDelay().compareTo(established) >= 0,
        "a failure must not read as health and shorten the delay");
  }

  /** Makes the post-borrow clock read fail, which happens after the connection was borrowed. */
  private ThrottledDataSource failingAfterBorrow() {
    AtomicInteger reads = new AtomicInteger();
    return new ThrottledDataSource(
        pool,
        throttle,
        () -> {
          if (reads.incrementAndGet() == 2) {
            throw new IllegalStateException("clock failed");
          }
          return 0L;
        });
  }

  @Test
  void a_failure_after_the_borrow_closes_the_connection_and_releases_the_permit() {
    assertThrows(IllegalStateException.class, failingAfterBorrow()::getConnection);

    assertEquals(List.of("close"), pool.fake.calls);
    assertEquals(0, throttle.held());
  }

  @Test
  void a_failing_cleanup_close_is_suppressed_and_still_releases_the_permit() {
    SQLException closing = new SQLException("close failed");
    pool.fake.closeFailure = closing;

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, failingAfterBorrow()::getConnection);

    assertSame(closing, thrown.getSuppressed()[0]);
    assertEquals(0, throttle.held());
  }

  @Test
  void an_error_from_the_cleanup_close_cannot_skip_the_permit_release() {
    StackOverflowError closing = new StackOverflowError();
    pool.fake.closeFailure = closing;

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, failingAfterBorrow()::getConnection);

    assertSame(closing, thrown.getSuppressed()[0]);
    assertEquals(0, throttle.held());
  }

  @Test
  void unwrap_and_isWrapperFor_reach_the_underlying_connection() throws Exception {
    Connection connection = dataSource.getConnection();

    assertTrue(connection.isWrapperFor(Connection.class));
    assertSame(pool.fake.connection, connection.unwrap(Connection.class));
    assertTrue(pool.fake.calls.containsAll(List.of("isWrapperFor", "unwrap")));
    connection.close();
  }

  @Test
  void the_proxy_delegates_every_other_call() throws Exception {
    Connection connection = dataSource.getConnection();

    assertEquals("native:SELECT 1", connection.nativeSQL("SELECT 1"));
    connection.setAutoCommit(false);
    assertFalse(connection.isClosed());

    assertEquals(List.of("nativeSQL", "setAutoCommit", "isClosed"), pool.fake.calls);
    connection.close();
  }

  @Test
  void an_exception_thrown_by_the_real_connection_is_propagated_and_a_failing_close_still_releases()
      throws Exception {
    AtomicInteger seen = new AtomicInteger();
    SQLException failure = new SQLException("broken");
    DataSource failing =
        new AbstractDataSource() {
          @Override
          public Connection getConnection() {
            return (Connection)
                Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                      seen.incrementAndGet();
                      throw failure;
                    });
          }

          @Override
          public Connection getConnection(String user, String password) {
            return getConnection();
          }
        };
    Connection connection = new ThrottledDataSource(failing, throttle, clock::get).getConnection();

    SQLException thrown = assertThrows(SQLException.class, () -> connection.nativeSQL("x"));
    assertSame(failure, thrown);
    // Even when the real close() fails, the permit must come back.
    assertThrows(SQLException.class, connection::close);
    assertEquals(0, throttle.held());
  }
}
