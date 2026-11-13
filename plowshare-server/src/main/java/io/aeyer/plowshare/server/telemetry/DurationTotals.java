package io.aeyer.plowshare.server.telemetry;

import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/** In-memory timer totals. Recording never invokes a registry or retains a per-execution sample. */
public final class DurationTotals {
  private final LongAdder count = new LongAdder();
  private final DoubleAdder seconds = new DoubleAdder();

  public void recordSeconds(double value) {
    if (!Double.isFinite(value) || value < 0)
      throw new IllegalArgumentException("Duration must be finite and nonnegative");
    seconds.add(value);
    count.increment();
  }

  public long count() {
    return count.sum();
  }

  public double seconds() {
    return seconds.sum();
  }
}
