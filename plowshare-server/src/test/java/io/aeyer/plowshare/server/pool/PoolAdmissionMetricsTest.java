package io.aeyer.plowshare.server.pool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import io.aeyer.plowshare.server.pool.AdmissionThrottle.Admission;
import io.aeyer.plowshare.server.pool.AdmissionThrottle.Sleeper;
import io.aeyer.plowshare.server.pool.PoolAdmissionConfiguration.GatedConnections;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Pins the operator-visible meters. The throttle never refuses, so these meters are the only place
 * pool pressure can be seen; a regression here silently reintroduces an unattributable timeout.
 */
class PoolAdmissionMetricsTest {

  private static final String PREFIX = "plowshare.pool.admission";
  private static final int POOL_SIZE = 20;

  /** A real Hikari bean (for size and timeout) whose borrow returns a no-op connection. */
  static final class FakeHikari extends HikariDataSource {
    @Override
    public Connection getConnection() {
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> method.getReturnType() == boolean.class ? false : null);
    }
  }

  @Configuration
  static class Pool {
    @Bean
    FakeHikari dataSource() {
      FakeHikari pool = new FakeHikari();
      pool.setMaximumPoolSize(POOL_SIZE);
      pool.setConnectionTimeout(30_000);
      return pool;
    }
  }

  @Configuration
  static class Registry {
    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }

  /** Threshold of 1 so that a second concurrent borrower is delayed, with real but tiny sleeps. */
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withPropertyValues("plowshare.pool-admission.threshold-fraction=0.05")
          .withUserConfiguration(Pool.class, Registry.class, PoolAdmissionConfiguration.class);

  private static double gauge(MeterRegistry registry, String name) {
    return registry.get(PREFIX + "." + name).gauge().value();
  }

  @Test
  void permit_occupancy_and_capacity_are_published_as_gauges() {
    runner.run(
        context -> {
          MeterRegistry registry = context.getBean(MeterRegistry.class);
          AdmissionThrottle throttle = context.getBean(AdmissionThrottle.class);
          DataSource gated = context.getBean(GatedConnections.class).source();

          assertEquals(throttle.permits(), gauge(registry, "permits"));
          assertEquals(throttle.threshold(), gauge(registry, "threshold"));
          assertEquals(0.0, gauge(registry, "held"));
          try (Connection held = gated.getConnection()) {
            assertFalse(held.isClosed());
            assertEquals(1.0, gauge(registry, "held"));
          }
          assertEquals(0.0, gauge(registry, "held"));
        });
  }

  @Test
  void applied_delay_is_published_and_records_a_delayed_acquire() {
    runner.run(
        context -> {
          MeterRegistry registry = context.getBean(MeterRegistry.class);
          DataSource gated = context.getBean(GatedConnections.class).source();
          assertEquals(0.0, registry.get(PREFIX + ".delayed").counter().count());

          // The second borrower runs on its own thread (permits are re-entrant per thread), so it
          // sees one permit held, which is at the threshold, and must be delayed.
          try (Connection first = gated.getConnection()) {
            assertFalse(first.isClosed());
            Thread other =
                new Thread(
                    () -> {
                      try (Connection second = gated.getConnection()) {
                        second.isClosed();
                      } catch (Exception e) {
                        throw new IllegalStateException(e);
                      }
                    });
            other.start();
            other.join();
          }

          assertEquals(1.0, registry.get(PREFIX + ".delayed").counter().count());
          // Pacing is not saturation: this borrower never waited for a permit.
          assertEquals(0.0, registry.get(PREFIX + ".saturated").counter().count());
          var timer = registry.get(PREFIX + ".delay").timer();
          assertEquals(1, timer.count());
          assertTrue(timer.totalTime(TimeUnit.NANOSECONDS) > 0);
          assertTrue(timer.max(TimeUnit.NANOSECONDS) > 0);
        });
  }

  @Test
  void an_undelayed_acquire_does_not_count_as_delayed() {
    runner.run(
        context -> {
          MeterRegistry registry = context.getBean(MeterRegistry.class);
          DataSource gated = context.getBean(GatedConnections.class).source();
          try (Connection only = gated.getConnection()) {
            only.isClosed();
          }
          assertEquals(0.0, registry.get(PREFIX + ".delayed").counter().count());
          assertEquals(0, registry.get(PREFIX + ".delay").timer().count());
          assertEquals(0.0, registry.get(PREFIX + ".saturated").counter().count());
          assertEquals(0, registry.get(PREFIX + ".saturation.wait").timer().count());
        });
  }

  @Test
  void meter_cardinality_is_fixed() {
    runner.run(
        context -> {
          MeterRegistry registry = context.getBean(MeterRegistry.class);
          DataSource gated = context.getBean(GatedConnections.class).source();
          Set<String> before = names(registry);
          assertEquals(
              Set.of(
                  PREFIX + ".held",
                  PREFIX + ".permits",
                  PREFIX + ".threshold",
                  PREFIX + ".delayed",
                  PREFIX + ".delay",
                  PREFIX + ".saturated",
                  PREFIX + ".saturation.wait",
                  PREFIX + ".overdraft"),
              before);

          // Meters register eagerly, so this guards against a meter or tag being added at
          // registration time or lazily on first use; it does not claim to force every path.
          AtomicReference<Throwable> failure = new AtomicReference<>();
          List<Thread> threads =
              IntStream.range(0, 8)
                  .mapToObj(
                      i ->
                          new Thread(
                              () -> {
                                try (Connection c = gated.getConnection()) {
                                  c.isClosed();
                                } catch (Throwable t) {
                                  failure.set(t);
                                }
                              }))
                  .toList();
          threads.forEach(Thread::start);
          for (Thread t : threads) {
            t.join();
          }

          assertNull(failure.get());
          assertEquals(before, names(registry));
          // No tags at all: nothing a caller supplies can ever become a tag value.
          assertTrue(
              registry.getMeters().stream()
                  .filter(m -> m.getId().getName().startsWith(PREFIX))
                  .allMatch(m -> m.getId().getTags().isEmpty()));
        });
  }

  @Test
  void a_context_without_a_registry_still_starts_and_admits() {
    new ApplicationContextRunner()
        .withUserConfiguration(Pool.class, PoolAdmissionConfiguration.class)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              try (Connection c =
                  context.getBean(GatedConnections.class).source().getConnection()) {
                c.isClosed();
              }
            });
  }

  /**
   * Three permits with a tiny curve so delays are milliseconds. Three threads hold every permit; a
   * fourth must block on the semaphore, which is observed as WAITING (a curve delay is
   * TIMED_WAITING), so the recorded wait is proven to be a real semaphore wait.
   */
  @Test
  void a_saturated_acquire_is_counted_and_timed_separately_from_pacing() {
    new ApplicationContextRunner()
        .withPropertyValues(
            "plowshare.pool-admission.permit-margin=17",
            "plowshare.pool-admission.threshold-fraction=0.05",
            "plowshare.pool-admission.base-quantum=1ms",
            "plowshare.pool-admission.ceiling=5ms")
        .withUserConfiguration(Pool.class, Registry.class, PoolAdmissionConfiguration.class)
        .run(
            context -> {
              MeterRegistry registry = context.getBean(MeterRegistry.class);
              AdmissionThrottle throttle = context.getBean(AdmissionThrottle.class);
              DataSource gated = context.getBean(GatedConnections.class).source();
              assertEquals(3, throttle.permits());

              // Bounded, so a regression that never blocks (or never releases) fails, not hangs.
              assertTimeoutPreemptively(
                  Duration.ofSeconds(10),
                  () -> {
                    CountDownLatch release = new CountDownLatch(1);
                    List<Thread> holders = new ArrayList<>();
                    for (int i = 0; i < 3; i++) {
                      Thread holder = new Thread(() -> hold(gated, release));
                      holders.add(holder);
                      holder.start();
                    }
                    while (throttle.held() < 3) {
                      Thread.sleep(1);
                    }
                    assertEquals(0.0, registry.get(PREFIX + ".saturated").counter().count());

                    Thread blocked = new Thread(() -> hold(gated, new CountDownLatch(0)));
                    blocked.start();
                    while (blocked.getState() != Thread.State.WAITING) {
                      Thread.sleep(1);
                    }
                    Thread.sleep(60);
                    release.countDown();
                    blocked.join();
                    for (Thread holder : holders) {
                      holder.join();
                    }

                    assertEquals(1.0, registry.get(PREFIX + ".saturated").counter().count());
                    var wait = registry.get(PREFIX + ".saturation.wait").timer();
                    assertEquals(1, wait.count());
                    assertTrue(wait.totalTime(TimeUnit.MILLISECONDS) >= 50);
                  });
            });
  }

  private static void hold(DataSource gated, CountDownLatch release) {
    try (Connection c = gated.getConnection()) {
      c.isClosed();
      release.await();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Loads Boot's real actuator registry autoconfiguration instead of a hand-built bean. */
  @Test
  void meters_land_in_the_registry_boots_actuator_autoconfigures() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                MetricsAutoConfiguration.class,
                CompositeMeterRegistryAutoConfiguration.class,
                SimpleMetricsExportAutoConfiguration.class))
        .withUserConfiguration(Pool.class, PoolAdmissionConfiguration.class)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              MeterRegistry registry = context.getBean(MeterRegistry.class);
              assertNotNull(registry.find(PREFIX + ".held").gauge());
              assertNotNull(registry.find(PREFIX + ".saturated").counter());
            });
  }

  /** A registry whose counters fail on every increment, standing in for a broken backend. */
  private static final class BrokenCounters extends SimpleMeterRegistry {
    @Override
    protected Counter newCounter(Meter.Id id) {
      Counter real = super.newCounter(id);
      return new Counter() {
        @Override
        public void increment(double amount) {
          throw new IllegalStateException("broken meter registry");
        }

        @Override
        public double count() {
          return real.count();
        }

        @Override
        public Id getId() {
          return id;
        }
      };
    }
  }

  /**
   * The delay is the whole of the backpressure, so a failing registry must neither throw out of the
   * acquire nor skip the sleep. The guard lives here because the throttle's own backstop cannot
   * tell a failure before the sleep from one after it.
   */
  @Test
  void a_failing_registry_neither_throws_nor_skips_the_applied_delay() {
    PoolAdmissionMetrics metrics = new PoolAdmissionMetrics(new BrokenCounters());
    List<Duration> slept = new ArrayList<>();
    Sleeper instrumented = metrics.instrument(slept::add);

    assertDoesNotThrow(() -> instrumented.sleep(Duration.ofMillis(7)));

    assertEquals(List.of(Duration.ofMillis(7)), slept, "the caller must still be paced");
  }

  @Configuration
  static class BrokenRegistry {
    @Bean
    MeterRegistry meterRegistry() {
      return new BrokenCounters();
    }
  }

  /**
   * End to end through {@code getConnection()}: a registry that fails on every counter increment
   * must not turn a delayed borrow into an unchecked exception out of a database call, which would
   * reach callers untranslated by Spring's exception hierarchy.
   */
  @Test
  void a_failing_registry_does_not_make_a_borrow_throw() {
    new ApplicationContextRunner()
        .withPropertyValues(
            "plowshare.pool-admission.threshold-fraction=0.05",
            "plowshare.pool-admission.base-quantum=1ms",
            "plowshare.pool-admission.ceiling=5ms")
        .withUserConfiguration(Pool.class, BrokenRegistry.class, PoolAdmissionConfiguration.class)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              DataSource gated = context.getBean(GatedConnections.class).source();
              try (Connection first = gated.getConnection()) {
                assertFalse(first.isClosed());
                // On its own thread, so it is above the threshold and must be delayed: the
                // instrumented sleeper's counters throw before the sleep.
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread other =
                    new Thread(
                        () -> {
                          try (Connection second = gated.getConnection()) {
                            second.isClosed();
                          } catch (Throwable t) {
                            failure.set(t);
                          }
                        });
                other.start();
                other.join();
                assertNull(failure.get(), String.valueOf(failure.get()));
              }
              assertEquals(0, context.getBean(AdmissionThrottle.class).held());
            });
  }

  /**
   * The overdraft counter is the only visible trace of the residual over-subscription: a nested
   * acquire admitted without a permit because none was free.
   */
  @Test
  void a_permit_free_nested_admission_is_counted_as_an_overdraft() {
    new ApplicationContextRunner()
        .withPropertyValues(
            "plowshare.pool-admission.permit-margin=17",
            "plowshare.pool-admission.threshold-fraction=0.05",
            "plowshare.pool-admission.base-quantum=1ms",
            "plowshare.pool-admission.ceiling=5ms")
        .withUserConfiguration(Pool.class, Registry.class, PoolAdmissionConfiguration.class)
        .run(
            context -> {
              MeterRegistry registry = context.getBean(MeterRegistry.class);
              AdmissionThrottle throttle = context.getBean(AdmissionThrottle.class);
              assertEquals(3, throttle.permits());
              assertEquals(0.0, registry.get(PREFIX + ".overdraft").counter().count());

              // Bounded and kept on one thread (a nested acquire must share the thread of its
              // outer one), so a regression to a blocking nested take fails instead of hanging.
              assertTimeoutPreemptively(
                  Duration.ofSeconds(10),
                  () -> {
                    // Every permit is held, one of them by this thread; the nested acquire then
                    // finds none free and is admitted without one, not blocked against itself.
                    Admission mine = throttle.acquire();
                    Admission second = onThread(throttle);
                    Admission third = onThread(throttle);
                    assertEquals(3, throttle.held());
                    assertEquals(0.0, registry.get(PREFIX + ".overdraft").counter().count());

                    Admission nested = throttle.acquire();
                    assertEquals(1.0, registry.get(PREFIX + ".overdraft").counter().count());
                    assertEquals(3, throttle.held(), "the nested level holds no permit");

                    nested.close();
                    mine.close();
                    second.close();
                    third.close();
                    assertEquals(0, throttle.held());
                    assertEquals(1.0, registry.get(PREFIX + ".overdraft").counter().count());
                  });
            });
  }

  /** Acquires on a separate thread, so the admission is not nested into the caller's scope. */
  private static Admission onThread(AdmissionThrottle throttle) throws Exception {
    AtomicReference<Admission> held = new AtomicReference<>();
    Thread thread =
        new Thread(
            () -> {
              try {
                held.set(throttle.acquire());
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
    thread.start();
    thread.join();
    return held.get();
  }

  private static Set<String> names(MeterRegistry registry) {
    return registry.getMeters().stream()
        .map(Meter::getId)
        .map(id -> id.getName())
        .filter(n -> n.startsWith(PREFIX))
        .collect(Collectors.toSet());
  }
}
