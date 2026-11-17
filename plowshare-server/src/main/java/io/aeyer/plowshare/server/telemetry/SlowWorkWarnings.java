package io.aeyer.plowshare.server.telemetry;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Best-effort slow-work diagnostics. Work threads and threshold callbacks only update fixed
 * counters and offer bounded events; the single logging worker owns backend IO. Observer pressure,
 * a full queue or a broken logging backend can lose diagnostics, never fail or cancel the observed
 * work.
 */
public final class SlowWorkWarnings implements WorkPerformanceReporter, AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(SlowWorkWarnings.class);
  private static final Pattern OPERATION = Pattern.compile("[a-z][a-z0-9_.-]{0,95}");

  record Warning(Kind kind, String operation, long elapsedNanos, long thresholdNanos) {}

  private static final class Total {
    final LongAdder observed = new LongAdder();
    final LongAdder slow = new LongAdder();
    final LongAdder dropped = new LongAdder();
    final LongAdder pressure = new LongAdder();
    final AtomicInteger active = new AtomicInteger();
  }

  private final EnumMap<Kind, Total> totals = new EnumMap<>(Kind.class);
  private final long thresholdNanos;
  private final LongSupplier ticker;
  private final ScheduledExecutorService alarms;
  private final Consumer<Warning> sink;
  private final ArrayBlockingQueue<Warning> events;
  private final Semaphore permits;
  private final ConcurrentMap<Watch, Boolean> active = new ConcurrentHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Thread worker;

  public SlowWorkWarnings(WorkPerformanceProperties properties) {
    this(properties, System::nanoTime, alarms());
  }

  SlowWorkWarnings(
      WorkPerformanceProperties properties, LongSupplier ticker, ScheduledExecutorService alarms) {
    this(properties, ticker, alarms, SlowWorkWarnings::log);
  }

  SlowWorkWarnings(
      WorkPerformanceProperties properties,
      LongSupplier ticker,
      ScheduledExecutorService alarms,
      Consumer<Warning> sink) {
    Objects.requireNonNull(properties);
    this.ticker = Objects.requireNonNull(ticker);
    this.alarms = Objects.requireNonNull(alarms);
    this.sink = Objects.requireNonNull(sink);
    thresholdNanos = properties.warningThreshold().toNanos();
    events = new ArrayBlockingQueue<>(properties.eventQueueCapacity());
    permits = new Semaphore(properties.maxActiveWatches());
    for (var kind : Kind.values()) totals.put(kind, new Total());
    worker = Thread.ofVirtual().name("slow-work-warnings").start(this::drain);
  }

  private static ScheduledExecutorService alarms() {
    var executor =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().daemon(true).name("slow-work-alarms").factory());
    // Closed observations must not retain their operation names until the old threshold expires.
    executor.setRemoveOnCancelPolicy(true);
    executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    return executor;
  }

  /** Composition-time binding only; request threads never call a metrics backend. */
  public void bind(MeterRegistry registry) {
    totals.forEach(
        (kind, total) -> {
          String label = kind.name().toLowerCase(Locale.ROOT);
          FunctionCounter.builder("plowshare.performance.observed", total.observed, LongAdder::sum)
              .tag("kind", label)
              .register(registry);
          FunctionCounter.builder("plowshare.performance.slow", total.slow, LongAdder::sum)
              .tag("kind", label)
              .register(registry);
          FunctionCounter.builder("plowshare.performance.dropped", total.dropped, LongAdder::sum)
              .tag("kind", label)
              .register(registry);
          FunctionCounter.builder("plowshare.performance.pressure", total.pressure, LongAdder::sum)
              .tag("kind", label)
              .register(registry);
          Gauge.builder("plowshare.performance.active", total.active, AtomicInteger::get)
              .tag("kind", label)
              .register(registry);
        });
  }

  @Override
  public Observation watch(Kind kind, String operation) {
    if (kind == null) return UNOBSERVED;
    var total = totals.get(kind);
    if (closed.get() || operation == null || !OPERATION.matcher(operation).matches()) {
      total.dropped.increment();
      return UNOBSERVED;
    }
    if (!permits.tryAcquire()) {
      total.pressure.increment();
      return UNOBSERVED;
    }
    var watch = new Watch(kind, operation, total, ticker.getAsLong());
    total.active.incrementAndGet();
    active.put(watch, true);
    try {
      watch.alarm = alarms.schedule(watch::threshold, thresholdNanos, TimeUnit.NANOSECONDS);
      total.observed.increment();
      if (closed.get() && watch.finish(false)) total.dropped.increment();
      return watch;
    } catch (RuntimeException unavailable) {
      // A stopped/unavailable telemetry scheduler cannot refuse the actual operation.
      if (watch.finish(false)) total.dropped.increment();
      return UNOBSERVED;
    }
  }

  private final class Watch implements Observation {
    private final Kind kind;
    private final String operation;
    private final Total total;
    private final long started;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicBoolean reported = new AtomicBoolean();
    private volatile ScheduledFuture<?> alarm;

    Watch(Kind kind, String operation, Total total, long started) {
      this.kind = kind;
      this.operation = operation;
      this.total = total;
      this.started = started;
    }

    void threshold() {
      if (!finished.get()) warn();
    }

    private void warn() {
      long elapsed = ticker.getAsLong() - started;
      if (elapsed < thresholdNanos || !reported.compareAndSet(false, true)) return;
      total.slow.increment();
      var warning = new Warning(kind, operation, elapsed, thresholdNanos);
      if (closed.get() || !events.offer(warning)) total.dropped.increment();
      else if (closed.get() && events.remove(warning)) total.dropped.increment();
    }

    boolean finish(boolean warnOnCompletion) {
      if (!finished.compareAndSet(false, true)) return false;
      var pending = alarm;
      if (pending != null) pending.cancel(false); // Only this observer's alarm, never client work.
      active.remove(this);
      total.active.decrementAndGet();
      permits.release();
      if (warnOnCompletion) warn();
      return true;
    }

    @Override
    public void close() {
      finish(true);
    }
  }

  private void drain() {
    while (!closed.get()) {
      try {
        var warning = events.take();
        try {
          sink.accept(warning);
        } catch (RuntimeException unavailable) {
          // The backend is outside the observed work's ownership and cannot change its outcome.
          totals.get(warning.kind()).dropped.increment();
        }
      } catch (InterruptedException stopped) {
        if (closed.get()) return;
      }
    }
  }

  private static void log(Warning warning) {
    log.warn(
        "Performance warning kind={} operation={} duration_ms={} threshold_ms={}",
        warning.kind(),
        warning.operation(),
        TimeUnit.NANOSECONDS.toMillis(warning.elapsedNanos()),
        TimeUnit.NANOSECONDS.toMillis(warning.thresholdNanos()));
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    active
        .keySet()
        .forEach(
            watch -> {
              if (watch.finish(false)) watch.total.dropped.increment();
            });
    alarms.shutdownNow();
    Warning pending;
    while ((pending = events.poll()) != null) totals.get(pending.kind()).dropped.increment();
    worker.interrupt();
    try {
      // Logging is isolated on a daemon/virtual worker; even a blocked backend has bounded cleanup.
      worker.join(1000);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
