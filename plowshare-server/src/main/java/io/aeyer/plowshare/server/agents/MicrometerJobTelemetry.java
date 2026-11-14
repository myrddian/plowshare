package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.telemetry.DurationTotals;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Pull-based lifecycle totals. Runtime updates are bounded and never invoke a metrics backend. */
public final class MicrometerJobTelemetry implements JobTelemetry {
  private final LongAdder started = new LongAdder();
  private final LongAdder rejected = new LongAdder();
  private final AtomicLong active = new AtomicLong();
  private final EnumMap<Outcome.Ending, DurationTotals> endings =
      new EnumMap<>(Outcome.Ending.class);

  public MicrometerJobTelemetry(MeterRegistry registry) {
    FunctionCounter.builder("plowshare.jobs.started", started, LongAdder::sum).register(registry);
    FunctionCounter.builder("plowshare.jobs.rejected", rejected, LongAdder::sum).register(registry);
    Gauge.builder("plowshare.jobs.active", active, AtomicLong::get).register(registry);
    for (var ending : Outcome.Ending.values()) {
      var totals = new DurationTotals();
      endings.put(ending, totals);
      var label = ending.name().toLowerCase(Locale.ROOT);
      FunctionCounter.builder("plowshare.jobs.completed", totals, DurationTotals::count)
          .tag("ending", label)
          .register(registry);
      FunctionTimer.builder(
              "plowshare.jobs.duration",
              totals,
              DurationTotals::count,
              DurationTotals::seconds,
              TimeUnit.SECONDS)
          .tag("ending", label)
          .register(registry);
    }
  }

  @Override
  public Run started() {
    long began = System.nanoTime();
    started.increment();
    active.incrementAndGet();
    var done = new AtomicBoolean();
    return ending -> {
      if (!done.compareAndSet(false, true)) return;
      endings.get(ending).recordSeconds(Math.max(0, System.nanoTime() - began) / 1e9);
      active.decrementAndGet();
    };
  }

  @Override
  public void rejected() {
    rejected.increment();
  }
}
