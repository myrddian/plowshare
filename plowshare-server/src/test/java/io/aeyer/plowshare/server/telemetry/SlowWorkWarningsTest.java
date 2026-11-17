package io.aeyer.plowshare.server.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.aeyer.plowshare.server.telemetry.WorkPerformanceReporter.Kind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SlowWorkWarningsTest {
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @AfterEach
  void closeRegistry() {
    registry.close();
  }

  @Test
  void thresholdOnlyWarnsOnTheLoggingWorkerAndCompletionCancelsOnlyItsOwnAlarm() throws Exception {
    var clock = new AtomicLong();
    var alarms = new ManualAlarms();
    var logged = new CountDownLatch(1);
    var event = new AtomicReference<ILoggingEvent>();
    var logger = (Logger) LoggerFactory.getLogger(SlowWorkWarnings.class);
    var captured =
        new AppenderBase<ILoggingEvent>() {
          @Override
          protected void append(ILoggingEvent value) {
            value.prepareForDeferredProcessing();
            event.set(value);
            logged.countDown();
          }
        };
    captured.start();
    logger.addAppender(captured);
    try (var warnings =
        new SlowWorkWarnings(WorkPerformanceProperties.defaults(), clock::get, alarms)) {
      warnings.bind(registry);
      var finished = warnings.watch(Kind.SEARCH, "conversation-search");
      var continuing = warnings.watch(Kind.SEARCH, "conversation-search.replay");
      finished.close();
      assertTrue(alarms.tasks.getFirst().cancelled);
      assertFalse(alarms.tasks.getLast().cancelled);
      clock.set(Duration.ofSeconds(3).toNanos());
      alarms.tasks.getFirst().fire(); // A raced callback after cancellation must not resurrect it.
      alarms.tasks.getLast().fire();
      assertTrue(logged.await(5, TimeUnit.SECONDS));
      continuing.close();
      continuing.close();
      assertEquals(Level.WARN, event.get().getLevel());
      assertNull(event.get().getThrowableProxy());
      assertEquals("slow-work-warnings", event.get().getThreadName());
      assertTrue(event.get().getFormattedMessage().contains("conversation-search.replay"));
      assertTrue(event.get().getFormattedMessage().contains("duration_ms=3000"));
      assertEquals(1, count(registry, "slow"));
      assertEquals(
          0, registry.get("plowshare.performance.active").tag("kind", "search").gauge().value());
      assertTrue(
          registry.getMeters().stream()
              .allMatch(
                  meter ->
                      meter.getId().getTags().size() == 1
                          && meter.getId().getTags().getFirst().getKey().equals("kind")));
    } finally {
      logger.detachAppender(captured);
      captured.stop();
    }
  }

  @Test
  void blockedLoggingAndAFullQueueNeverBlockOrFailActualWork() throws Exception {
    var clock = new AtomicLong();
    var alarms = new ManualAlarms();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var warnings =
        new SlowWorkWarnings(
            new WorkPerformanceProperties(Duration.ofSeconds(2), 1, 2),
            clock::get,
            alarms,
            warning -> {
              entered.countDown();
              try {
                release.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            })) {
      warnings.bind(registry);
      var first = warnings.watch(Kind.SEARCH, "conversation-search");
      clock.addAndGet(Duration.ofSeconds(3).toNanos());
      alarms.tasks.getLast().fire();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      first.close();
      // The worker is still blocked. These task completions only offer events; the second fills
      // the single queue slot and the third loses its diagnostic rather than waiting on logging.
      for (int i = 0; i < 2; i++) {
        var work = warnings.watch(Kind.SEARCH, "conversation-search");
        clock.addAndGet(Duration.ofSeconds(3).toNanos());
        alarms.tasks.getLast().fire();
        work.close();
      }
      assertEquals(3, count(registry, "slow"));
      assertEquals(1, count(registry, "dropped"));
      assertEquals(
          0, registry.get("plowshare.performance.active").tag("kind", "search").gauge().value());
      release.countDown();
    } finally {
      release.countDown();
    }
  }

  @Test
  void observerPressureIsBoundedAndClosingReleasesItsPermit() {
    var alarms = new ManualAlarms();
    try (var warnings =
        new SlowWorkWarnings(
            new WorkPerformanceProperties(Duration.ofSeconds(2), 1, 1), () -> 0, alarms)) {
      warnings.bind(registry);
      var first = warnings.watch(Kind.SEARCH, "conversation-search");
      warnings.watch(Kind.SEARCH, "conversation-search").close();
      assertEquals(1, alarms.tasks.size());
      assertEquals(1, count(registry, "pressure"));
      first.close();
      var next = warnings.watch(Kind.SEARCH, "conversation-search");
      assertEquals(2, alarms.tasks.size());
      next.close();
      assertEquals(0, count(registry, "slow"));
    }
  }

  @Test
  void completionCanReportALateAlarmAndLoggingFailuresDoNotEscape() throws Exception {
    var clock = new AtomicLong();
    var alarms = new ManualAlarms();
    var calls = new AtomicInteger();
    var nextLogged = new CountDownLatch(1);
    try (var warnings =
        new SlowWorkWarnings(
            WorkPerformanceProperties.defaults(),
            clock::get,
            alarms,
            warning -> {
              if (calls.incrementAndGet() == 1) throw new IllegalStateException("broken backend");
              nextLogged.countDown();
            })) {
      warnings.bind(registry);
      for (int i = 0; i < 2; i++) {
        var work = warnings.watch(Kind.SEARCH, "conversation-search");
        clock.addAndGet(Duration.ofSeconds(3).toNanos());
        work.close(); // No alarm ran; completion still observes the slow lifetime once.
        alarms.tasks.getLast().fire();
      }
      assertTrue(nextLogged.await(5, TimeUnit.SECONDS));
      assertEquals(2, calls.get());
      assertEquals(2, count(registry, "slow"));
      assertEquals(1, count(registry, "dropped"));
    }
  }

  @Test
  void invalidNamesAndShutdownRacesDropOnlyTelemetry() {
    var alarms = new ManualAlarms();
    try (var warnings =
        new SlowWorkWarnings(WorkPerformanceProperties.defaults(), () -> 0, alarms)) {
      warnings.bind(registry);
      warnings.watch(Kind.SEARCH, "private query and account").close();
      warnings.watch(Kind.SEARCH, "a".repeat(97)).close();
      assertTrue(alarms.tasks.isEmpty());
      alarms.shutdown();
      warnings.watch(Kind.SEARCH, "conversation-search").close();
      assertEquals(3, count(registry, "dropped"));
      assertEquals(
          0, registry.get("plowshare.performance.active").tag("kind", "search").gauge().value());
    }
  }

  @Test
  void configurationBoundsAreStrictAndDefaultThresholdIsTwoSeconds() {
    var defaults = WorkPerformanceProperties.defaults();
    assertEquals(Duration.ofSeconds(2), defaults.warningThreshold());
    assertEquals(256, defaults.eventQueueCapacity());
    assertEquals(1024, defaults.maxActiveWatches());
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorkPerformanceProperties(Duration.ZERO, 256, 1024));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorkPerformanceProperties(Duration.ofSeconds(2), 0, 1024));
    assertThrows(
        IllegalArgumentException.class,
        () -> new WorkPerformanceProperties(Duration.ofSeconds(2), 256, 0));
  }

  @Test
  void compositionBindsExplicitBoundsAndPublishesOneReporter() {
    var runner =
        new ApplicationContextRunner().withUserConfiguration(WorkPerformanceConfiguration.class);
    runner
        .withPropertyValues(
            "plowshare.telemetry.performance.warning-threshold=PT3S",
            "plowshare.telemetry.performance.event-queue-capacity=2",
            "plowshare.telemetry.performance.max-active-watches=4")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var properties = context.getBean(WorkPerformanceProperties.class);
              assertEquals(Duration.ofSeconds(3), properties.warningThreshold());
              assertEquals(2, properties.eventQueueCapacity());
              assertEquals(4, properties.maxActiveWatches());
              assertSame(
                  context.getBean(WorkPerformanceReporter.class),
                  context.getBean(WorkPerformanceReporter.class));
            });
    runner
        .withPropertyValues("plowshare.telemetry.performance.event-queue-capacity=0")
        .run(context -> assertNotNull(context.getStartupFailure()));
  }

  private static double count(SimpleMeterRegistry registry, String suffix) {
    return registry
        .get("plowshare.performance." + suffix)
        .tag("kind", "search")
        .functionCounter()
        .count();
  }

  private static final class ManualAlarms extends ScheduledThreadPoolExecutor {
    final List<Alarm> tasks = new ArrayList<>();

    ManualAlarms() {
      super(1);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable action, long delay, TimeUnit unit) {
      if (isShutdown()) throw new RejectedExecutionException();
      assertEquals(Duration.ofSeconds(2).toNanos(), unit.toNanos(delay));
      var alarm = new Alarm(action);
      tasks.add(alarm);
      return alarm;
    }
  }

  private static final class Alarm implements ScheduledFuture<Object> {
    final Runnable action;
    boolean cancelled;

    Alarm(Runnable action) {
      this.action = action;
    }

    void fire() {
      action.run();
    }

    public boolean cancel(boolean interrupt) {
      assertFalse(interrupt);
      cancelled = true;
      return true;
    }

    public boolean isCancelled() {
      return cancelled;
    }

    public boolean isDone() {
      return cancelled;
    }

    public long getDelay(TimeUnit unit) {
      return 0;
    }

    public int compareTo(Delayed other) {
      return 0;
    }

    public Object get() {
      throw new UnsupportedOperationException("test alarm has no result");
    }

    public Object get(long timeout, TimeUnit unit) {
      return get();
    }
  }
}
