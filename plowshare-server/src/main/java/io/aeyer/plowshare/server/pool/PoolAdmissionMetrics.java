package io.aeyer.plowshare.server.pool;

import io.aeyer.plowshare.server.pool.AdmissionThrottle.Sleeper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

/**
 * Publishes {@link AdmissionThrottle} pressure to Micrometer.
 *
 * <p><b>Why this exists.</b> The throttle waits and never refuses, so no client ever sees a
 * capacity outcome. These meters are the only place pool pressure becomes legible, which is what
 * lets an operator attribute a slow request or a SQL timeout to admission delay rather than to the
 * database.
 *
 * <p>Meters, all under {@value #PREFIX}:
 *
 * <ul>
 *   <li>{@code held}, {@code permits}, {@code threshold}: gauges read live from the throttle.
 *   <li>{@code delayed}: counter of acquires that were delayed at all.
 *   <li>{@code delay}: timer of the delays applied (count, total, max, no histogram buckets).
 *   <li>{@code saturated}, {@code saturation.wait}: counter and timer of acquires that blocked
 *       because every permit was held. Kept apart from {@code delay} so an operator can tell
 *       "pacing" (the curve is slowing callers) from "full" (callers are queued), which is the
 *       longer stall.
 *   <li>{@code overdraft}: counter of nested acquires admitted without a permit because none was
 *       free. Each one is a thread holding a second physical connection against one permit, so a
 *       rising count is the amount by which the gate currently under-counts pool use.
 * </ul>
 *
 * <p><b>Why the delay is measured at the sleeper, not read from {@link
 * AdmissionThrottle#lastDelay()}.</b> {@code lastDelay()} is one volatile shared by every thread,
 * so sampling it from a gauge reports whichever acquire happened to finish last and silently drops
 * every other delay between two scrapes. That cannot support "how many acquires were delayed" or
 * "how long". The throttle calls its {@link Sleeper} exactly once per non-nested delayed acquire
 * with exactly the applied delay, so wrapping the sleeper counts every delay exactly once with no
 * race. {@code lastDelay()} is therefore deliberately not published.
 *
 * <p>A delay is recorded when it is applied, before the sleep, so an interrupted wait still counts:
 * the pressure was real even if the caller gave up.
 *
 * <p><b>Fixed cardinality.</b> No meter carries any tag. The throttle is process-wide and a tag
 * could only come from a call site, a statement or a request, all unbounded; the meter set is
 * constant for the life of the process whatever the traffic.
 */
final class PoolAdmissionMetrics {

  static final String PREFIX = "plowshare.pool.admission";

  private final Counter delayed;
  private final Timer delay;
  private final Counter saturated;
  private final Timer saturationWait;
  private final Counter overdraft;

  PoolAdmissionMetrics(MeterRegistry registry) {
    this.delayed =
        Counter.builder(PREFIX + ".delayed")
            .description("Connection acquisitions that the admission throttle delayed")
            .register(registry);
    this.delay =
        Timer.builder(PREFIX + ".delay")
            .description("Delay the admission throttle applied to each delayed acquisition")
            .register(registry);
    this.saturated =
        Counter.builder(PREFIX + ".saturated")
            .description("Acquisitions that blocked because every permit was held")
            .register(registry);
    this.saturationWait =
        Timer.builder(PREFIX + ".saturation.wait")
            .description("Time acquisitions spent blocked waiting for a free permit")
            .register(registry);
    this.overdraft =
        Counter.builder(PREFIX + ".overdraft")
            .description(
                "Nested acquisitions admitted without a permit: none free, a waiter queued"
                    + " ahead, or the thread already interrupted")
            .register(registry);
  }

  /** Records one wait on the full permit semaphore; pass to the throttle as its listener. */
  void saturationWaited(Duration waited) {
    saturated.increment();
    saturationWait.record(waited);
  }

  /** Records one permit-free nested admission; pass to the throttle as its overdraft listener. */
  void nestedOverdraft() {
    overdraft.increment();
  }

  /**
   * Wraps the throttle's sleeper so each applied delay is counted and timed exactly once.
   *
   * <p>The meter calls are guarded here rather than only in the throttle. The delay is the entire
   * backpressure mechanism, and the throttle's own backstop cannot tell a failure before the sleep
   * from one after it, so a failing registry must not be able to skip the sleep. A discarded
   * failure loses one sample; the caller is still paced.
   */
  Sleeper instrument(Sleeper sleeper) {
    return applied -> {
      try {
        delayed.increment();
        delay.record(applied);
      } catch (RuntimeException observabilityFailure) {
        // Intentionally discarded: the sleep below is the behaviour, the meters only describe it.
      }
      sleeper.sleep(applied);
    };
  }

  /** Registers the live gauges. The throttle is a singleton bean, so a strong reference is fine. */
  void bind(MeterRegistry registry, AdmissionThrottle throttle) {
    Gauge.builder(PREFIX + ".held", throttle, AdmissionThrottle::held)
        .description("Admission permits currently held")
        .strongReference(true)
        .register(registry);
    Gauge.builder(PREFIX + ".permits", throttle, AdmissionThrottle::permits)
        .description("Admission permits configured")
        .strongReference(true)
        .register(registry);
    Gauge.builder(PREFIX + ".threshold", throttle, AdmissionThrottle::threshold)
        .description("Occupancy at which admission delay begins")
        .strongReference(true)
        .register(registry);
  }
}
