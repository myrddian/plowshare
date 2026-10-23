package io.aeyer.plowshare.server.pool;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * Wait-only admission policy placed in front of database connection acquisition.
 *
 * <p><b>The throttle waits; it never refuses.</b> {@link #acquire()} always eventually returns an
 * {@link Admission}. It may throw {@link InterruptedException}, because thread interruption is not
 * a refusal, and nothing else. This is a hard contract, not a preference: the TypeScript SDK treats
 * a {@code CAPACITY_EXHAUSTED} reply as flow control and resubmits under the same request id, and a
 * refusal raised from inside a database call happens mid-handler, after authorization and possibly
 * after earlier committed statements. Refusing there would let the SDK silently replay a
 * partially-applied operation. Pressure is therefore expressed only as delay.
 *
 * <p><b>The hazard this contract creates.</b> Because the throttle never refuses, waiting is
 * unbounded: once every permit is held a caller blocks in the semaphore with no timeout. Work that
 * holds a permit while waiting on another thread's database work therefore changes character. Under
 * the pool alone such a shape failed after Hikari's acquire timeout, five seconds by default, and
 * the request ended with an error naming the pool. Here nothing times out: the holder waits for the
 * other thread, the other thread waits for a permit the holder owns, and the request is wedged for
 * the life of the process. Operationally it shows up as a rising {@code
 * plowshare.pool.admission.saturation.wait} and requests that never complete, not as a failure.
 *
 * <p>Avoiding it is a caller-side obligation: do not hold a pooled connection, or a permit, across
 * a wait on another thread. The project's "hold connections briefly" rule already forbids network
 * and model calls inside a transaction for the same reason, and this contract raises the cost of
 * breaking it from a slow failure to a permanent one.
 *
 * <p><b>Where the audit stops.</b> The review that produced this note traced a structurally live
 * shape — {@code LlmPool.submit} blocking in {@code task.get()} while the lane thread's {@code
 * InferenceObserver.beforeDispatch()} reaches {@code CallAuthorization.require()}, which does
 * database work — but could not prove a live instance of it, and a grep found no file that both
 * opens a transaction and blocks on a future. So this is a documented hazard of the contract, not a
 * known defect, and no code here guards against it: a guard would have to be a timeout or a
 * refusal, which the paragraph above rules out.
 *
 * <p><b>Shape of the delay.</b> Below {@code threshold} occupied permits an acquire is granted
 * immediately. At occupancy {@code n >= threshold} the quantum is {@code base * ratio^(n -
 * threshold)}, clamped to {@code ceiling}, and the actual wait is drawn uniformly from {@code [q/2,
 * q]} so that threads released together do not retry in lock step. The curve stops growing once
 * occupancy passes {@code permits - 1}; at that point a caller additionally blocks on the
 * underlying semaphore until a permit is freed, which is waiting, not refusal.
 *
 * <p>{@code ratio = (ceiling / base) ^ (1 / (permits - threshold - 1))} is computed from the
 * configured values so that the last step before full occupancy lands exactly on the ceiling. It is
 * deliberately not a literal: a fixed doubling silently stops matching the pool the moment the pool
 * is resized, the same way a dispatch limit of 8 once became an unwritten proxy for a pool of 10.
 *
 * <p><b>Re-entrancy.</b> A permit bounds one concurrent unit of work, and a thread already holding
 * one must never block for another: a non-transactional path that holds a connection and issues a
 * nested query (a Spring {@code RowMapper} that itself queries) would deadlock the throttle if it
 * had to wait. A nested acquire therefore never waits and never delays.
 *
 * <p>But a nested acquire is not always free of cost to the pool. Spring's {@code
 * PROPAGATION_REQUIRES_NEW} suspends the outer {@code ConnectionHolder} and borrows again on the
 * same thread, so that thread legitimately holds two physical connections. Treating every nesting
 * level as permit-free would let such threads overrun the pool and fail on the pool's own acquire
 * timeout, which is exactly the indistinguishable failure this throttle exists to remove.
 *
 * <p>So a nested acquire attempts a <em>non-blocking</em>, fairness-respecting permit take. If a
 * permit is immediately available that nesting level owns it and releases it when its handle
 * closes, and the second connection is accounted for. If none is available the level proceeds
 * permit-free exactly as before, which preserves the no-deadlock property, and the fall-through is
 * counted as an overdraft so the residual over-subscription is visible to an operator rather than
 * silent. Depth is tracked per nesting level: permit ownership belongs to the level that took the
 * permit, so no level can release another level's permit.
 *
 * <p><b>Pressure signal.</b> The curve is scaled by a pressure level fed from observed acquire
 * latency ({@link #observed(Duration)}), not from occupancy. The documented incident behind this
 * choice was an embedding-model call left inside a database transaction, pinning a pooled
 * connection for up to eighty seconds: occupancy barely moves when two threads hold connections for
 * that long, while acquire latency moves immediately. The level is an exponentially weighted moving
 * average of latency normalised to the ceiling, additionally decayed by elapsed time. The time
 * decay is what makes the level recover when traffic stops entirely, since a sample-only average
 * would stay high until the next sample arrived; the averaging makes a single outlier unable to
 * dominate. Pressure can at most double the quantum (it is still clamped at the ceiling), so it
 * sharpens the curve without replacing it.
 *
 * <p>Occupancy counts permits held, not threads already sleeping in their delay, so a simultaneous
 * burst may all observe low occupancy. The semaphore is fair so that a waiting caller is eventually
 * admitted.
 *
 * <p>Time, randomness and sleeping are injected so the policy is testable without a database or
 * real time.
 */
public final class AdmissionThrottle {

  /** Weight of each new latency sample in the pressure average. */
  private static final double SAMPLE_WEIGHT = 0.2;

  /** Pressure window used by the constructor that does not name one. */
  static final Duration DEFAULT_PRESSURE_WINDOW = Duration.ofSeconds(10);

  /** Maximum extra scaling of the quantum at full pressure (1.0 means up to double). */
  private static final double PRESSURE_GAIN = 1.0;

  /** Pauses the calling thread; injected so tests never sleep for real. */
  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration d) throws InterruptedException;
  }

  /**
   * Told how long an acquire blocked on the permit semaphore because every permit was held. This is
   * the saturation stall, distinct from the curve delay applied through the {@link Sleeper}. Called
   * on the acquiring thread, also when the wait ends in an interrupt.
   *
   * <p><b>Implementations must not throw.</b> This is observability on the hot path of every
   * database acquisition and must not be able to damage what it observes. As a backstop the
   * throttle discards any {@link RuntimeException} raised here: a permit has already been taken
   * when the listener runs, and letting the exception escape would strand that permit for the life
   * of the process and make {@link AdmissionThrottle#acquire()} throw something other than {@link
   * InterruptedException}. A discarded failure means the wait goes unreported, nothing more.
   */
  @FunctionalInterface
  public interface SaturationListener {
    void waited(Duration waited);
  }

  /**
   * Told that a nested acquire could not take a permit and was admitted without one. This is the
   * residual over-subscription of the pool: a thread holding a second physical connection (Spring
   * {@code PROPAGATION_REQUIRES_NEW}, or a nested query inside a {@code RowMapper}) against a
   * single permit, which the throttle allows rather than deadlocking the thread against itself.
   *
   * <p><b>Implementations must not throw</b>, for the same reason as {@link SaturationListener}:
   * this runs after the nesting level's depth has already been raised, and an escaping exception
   * would strand that depth and make {@link AdmissionThrottle#acquire()} throw something other than
   * {@link InterruptedException}. The throttle discards any {@link RuntimeException} raised here.
   */
  @FunctionalInterface
  public interface OverdraftListener {
    void overdrawn();
  }

  /**
   * Proof that the caller holds a unit of work's slot. {@link #close()} is idempotent per handle,
   * so closing a nested handle twice cannot release the outer scope's permit early.
   *
   * <p>Any thread may close a handle. Each close atomically decrements the acquiring thread's depth
   * and releases the permit this handle itself took, if it took one. Ownership is per handle, so
   * closing out of order cannot make one nesting level release another's permit, and a double close
   * cannot release anything twice. If another thread closes the outermost handle while the owner is
   * still running, the owner's depth is lowered from then on and a later acquire at depth zero
   * takes a fresh permit rather than being treated as nested.
   */
  public interface Admission extends AutoCloseable {
    @Override
    void close();
  }

  /**
   * Per-thread nesting depth. Only the owning thread raises it from zero; any thread may lower it.
   * The depth decides whether an acquire is nested; it no longer decides permit ownership, which
   * belongs to the individual {@link Handle}.
   */
  private static final class Depth {
    final AtomicInteger value = new AtomicInteger();

    /** Joins an enclosing scope unless its depth has already reached zero. */
    boolean tryNest() {
      int current = value.get();
      while (current > 0) {
        if (value.compareAndSet(current, current + 1)) {
          return true;
        }
        current = value.get();
      }
      return false;
    }
  }

  private final int permits;
  private final int threshold;
  private final long baseNanos;
  private final long ceilingNanos;
  private final double ratio;
  private final Duration pressureWindow;
  private final double halfLifeNanos;
  private final LongSupplier nanoClock;
  private final DoubleSupplier random;
  private final Sleeper sleeper;
  private final SaturationListener saturation;
  private final OverdraftListener overdraft;
  private final Semaphore semaphore;
  private final ThreadLocal<Depth> depth = ThreadLocal.withInitial(Depth::new);

  private final Object pressureLock = new Object();
  private double pressure;
  private long pressureAtNanos;

  private volatile Duration lastDelay = Duration.ZERO;

  /**
   * Test convenience: pressure halves over {@link #DEFAULT_PRESSURE_WINDOW}. Deliberately not
   * public, so production code cannot default the window and bypass the configured property.
   *
   * @see #AdmissionThrottle(int, int, Duration, Duration, Duration, LongSupplier, DoubleSupplier,
   *     Sleeper, SaturationListener)
   */
  AdmissionThrottle(
      int permits,
      int threshold,
      Duration baseQuantum,
      Duration ceiling,
      LongSupplier nanoClock,
      DoubleSupplier random,
      Sleeper sleeper) {
    this(
        permits,
        threshold,
        baseQuantum,
        ceiling,
        DEFAULT_PRESSURE_WINDOW,
        nanoClock,
        random,
        sleeper);
  }

  /**
   * @param permits total concurrent units of work admitted; must exceed {@code threshold + 1}
   * @param threshold occupancy at which delays begin
   * @param baseQuantum quantum at occupancy equal to the threshold
   * @param ceiling quantum at occupancy {@code permits - 1} and the inclusive upper bound of any
   *     delay; a delay may equal it (maximal jitter at the final step) but never exceeds it
   * @param pressureWindow time over which an idle pressure level halves; must be positive
   * @throws IllegalArgumentException if the configuration cannot define the curve. This validates
   *     configuration at construction; it is never raised from {@link #acquire()}.
   */
  public AdmissionThrottle(
      int permits,
      int threshold,
      Duration baseQuantum,
      Duration ceiling,
      Duration pressureWindow,
      LongSupplier nanoClock,
      DoubleSupplier random,
      Sleeper sleeper) {
    this(
        permits,
        threshold,
        baseQuantum,
        ceiling,
        pressureWindow,
        nanoClock,
        random,
        sleeper,
        waited -> {});
  }

  /**
   * As above, additionally reporting each wait on the permit semaphore to {@code saturation}.
   *
   * @param saturation receives the blocked time of every acquire that could not take a permit
   *     immediately; an uncontended acquire is never reported
   */
  public AdmissionThrottle(
      int permits,
      int threshold,
      Duration baseQuantum,
      Duration ceiling,
      Duration pressureWindow,
      LongSupplier nanoClock,
      DoubleSupplier random,
      Sleeper sleeper,
      SaturationListener saturation) {
    this(
        permits,
        threshold,
        baseQuantum,
        ceiling,
        pressureWindow,
        nanoClock,
        random,
        sleeper,
        saturation,
        () -> {});
  }

  /**
   * As above, additionally reporting every nested acquire that had to proceed without a permit.
   *
   * @param overdraft receives one report per permit-free nested admission; a nested acquire that
   *     did take a permit is never reported
   */
  public AdmissionThrottle(
      int permits,
      int threshold,
      Duration baseQuantum,
      Duration ceiling,
      Duration pressureWindow,
      LongSupplier nanoClock,
      DoubleSupplier random,
      Sleeper sleeper,
      SaturationListener saturation,
      OverdraftListener overdraft) {
    if (threshold < 0 || permits - threshold < 2) {
      throw new IllegalArgumentException(
          "permits must exceed threshold by at least 2: permits="
              + permits
              + " threshold="
              + threshold);
    }
    if (baseQuantum.isZero() || baseQuantum.isNegative() || ceiling.compareTo(baseQuantum) <= 0) {
      throw new IllegalArgumentException("require 0 < baseQuantum < ceiling");
    }
    if (pressureWindow.isZero() || pressureWindow.isNegative()) {
      throw new IllegalArgumentException("pressureWindow must be positive: " + pressureWindow);
    }
    this.permits = permits;
    this.pressureWindow = pressureWindow;
    this.halfLifeNanos = pressureWindow.toNanos();
    this.threshold = threshold;
    this.baseNanos = baseQuantum.toNanos();
    this.ceilingNanos = ceiling.toNanos();
    this.ratio = Math.pow((double) ceilingNanos / baseNanos, 1.0 / (permits - threshold - 1));
    this.nanoClock = nanoClock;
    this.random = random;
    this.sleeper = sleeper;
    this.saturation = saturation;
    this.overdraft = overdraft;
    this.semaphore = new Semaphore(permits, true);
    this.pressureAtNanos = nanoClock.getAsLong();
  }

  /**
   * Admits one logical unit of work, delaying it according to current occupancy and pressure.
   * Always returns eventually; see the class contract.
   *
   * @throws InterruptedException if the thread is interrupted while waiting. Nothing is held then.
   */
  public Admission acquire() throws InterruptedException {
    Depth threadDepth = depth.get();
    if (threadDepth.tryNest()) {
      return new Handle(threadDepth, takeNestedPermit());
    }
    Duration delay = delayAt(held());
    lastDelay = delay;
    if (!delay.isZero()) {
      applyDelay(delay);
    }
    acquirePermit();
    threadDepth.value.set(1);
    return new Handle(threadDepth, true);
  }

  /**
   * Takes a permit for a nested acquire if one is free right now, and reports an overdraft if not.
   *
   * <p>The attempt must not block for even a moment: the thread already holds a permit and, at
   * saturation, only itself could free one, so blocking here is the self-deadlock re-entrancy
   * exists to prevent. The zero-timeout form honours the fair queue; the untimed {@code
   * tryAcquire()} would barge past callers already waiting, which is why it is not used.
   *
   * <p>It must also not throw. The nesting depth has already been raised by {@link Depth#tryNest},
   * so an exception escaping before a {@link Handle} reaches the caller would strand that depth for
   * the life of the thread. An already-interrupted thread makes the timed attempt throw
   * immediately; the flag is restored and the level proceeds permit-free, leaving the interrupt for
   * the caller's own next blocking point to observe.
   *
   * @return whether this nesting level owns a permit
   */
  private boolean takeNestedPermit() {
    try {
      if (semaphore.tryAcquire(0, TimeUnit.NANOSECONDS)) {
        return true;
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    reportOverdraft();
    return false;
  }

  /**
   * Reports a permit-free nested admission without ever throwing, for the reason given on {@link
   * OverdraftListener}. Only the metric sample is lost when the report fails.
   */
  private void reportOverdraft() {
    try {
      overdraft.overdrawn();
    } catch (RuntimeException observabilityFailure) {
      // Intentionally discarded; see the method contract above.
    }
  }

  /**
   * Applies the pacing delay, which is the whole of the backpressure, without letting an
   * observability failure turn into a refusal.
   *
   * <p>In production the sleeper is wrapped so each applied delay is counted and timed, so a broken
   * meter registry would otherwise throw a {@link RuntimeException} straight out of {@link
   * #acquire()} and out of {@code getConnection()} untranslated, which the class contract forbids.
   * The same reasoning as {@link #reportSaturation} applies: a {@link RuntimeException} is
   * discarded, while {@link InterruptedException} (not a refusal) and {@link Error} propagate.
   *
   * <p>This guard cannot distinguish a failure before the sleep from one after it, so it cannot
   * retry; the instrumentation is responsible for guarding its own meter calls so that a metric
   * failure never skips the sleep. This is the backstop for every other sleeper.
   */
  private void applyDelay(Duration delay) throws InterruptedException {
    try {
      sleeper.sleep(delay);
    } catch (RuntimeException observabilityFailure) {
      // Intentionally discarded; see the method contract above.
    }
  }

  /**
   * Takes a permit, reporting the time spent blocked if none was free. The zero-timeout attempt
   * honours the fair queue (unlike the untimed {@code tryAcquire()}, which would barge), so the
   * fast path keeps the semaphore's fairness and only a genuinely contended acquire is timed.
   */
  private void acquirePermit() throws InterruptedException {
    if (semaphore.tryAcquire(0, TimeUnit.NANOSECONDS)) {
      return;
    }
    long started = nanoClock.getAsLong();
    try {
      semaphore.acquire();
    } finally {
      reportSaturation(started);
    }
  }

  /**
   * Reports a saturation wait without ever throwing. This runs in a {@code finally} after the
   * permit was taken, so an escaping exception would leak that permit (depth is still zero, no
   * {@link Admission} reaches the caller to release it) and would replace an in-flight {@link
   * InterruptedException}. Swallowing {@link RuntimeException} here is a deliberate, narrow
   * exception to the project's no-swallowing rule: the only thing lost is a metric sample, whereas
   * propagating would permanently shrink database capacity. {@link Error}s still propagate.
   */
  private void reportSaturation(long started) {
    try {
      saturation.waited(Duration.ofNanos(Math.max(0L, nanoClock.getAsLong() - started)));
    } catch (RuntimeException observabilityFailure) {
      // Intentionally discarded; see the method contract above.
    }
  }

  /** Total permits this throttle admits, for tests and metrics. */
  public int permits() {
    return permits;
  }

  /** Occupancy at which delay begins, for tests and metrics. */
  public int threshold() {
    return threshold;
  }

  /** Time over which an idle pressure level halves, for tests and metrics. */
  public Duration pressureWindow() {
    return pressureWindow;
  }

  /** Permits currently held, for tests and metrics. */
  public int held() {
    return permits - semaphore.availablePermits();
  }

  /** Delay applied to the most recent non-nested acquire, for tests and metrics. */
  public Duration lastDelay() {
    return lastDelay;
  }

  /**
   * Feeds the pressure signal with how long a connection acquisition actually took. The level rises
   * with slow acquisitions and falls again as latency falls or time passes.
   */
  public void observed(Duration acquireLatency) {
    // A negative duration would drive the level below zero and could make the delay non-positive,
    // which a real Sleeper rejects; the contract allows no exception but interruption.
    double sample = Math.max(0.0, Math.min(1.0, (double) acquireLatency.toNanos() / ceilingNanos));
    synchronized (pressureLock) {
      double current = decayedPressure();
      pressure = current + SAMPLE_WEIGHT * (sample - current);
    }
  }

  private Duration delayAt(int occupancy) {
    if (occupancy < threshold) {
      return Duration.ZERO;
    }
    int step = Math.min(occupancy - threshold, permits - threshold - 1);
    double quantum = baseNanos * Math.pow(ratio, step);
    double scaled = quantum * (1.0 + PRESSURE_GAIN * currentPressure());
    double bounded = Math.min(scaled, ceilingNanos);
    double jitter = Math.min(1.0, Math.max(0.0, random.getAsDouble()));
    return Duration.ofNanos(Math.round(bounded / 2 + bounded / 2 * jitter));
  }

  private double currentPressure() {
    synchronized (pressureLock) {
      return decayedPressure();
    }
  }

  /** Applies idle-time decay and advances the reference time. Caller holds {@code pressureLock}. */
  private double decayedPressure() {
    long now = nanoClock.getAsLong();
    long elapsed = Math.max(0L, now - pressureAtNanos);
    pressureAtNanos = now;
    pressure *= Math.pow(0.5, elapsed / halfLifeNanos);
    return pressure;
  }

  /**
   * One nesting level. {@code ownsPermit} records whether this level took a permit of its own, so
   * the release is decided by the level that took it rather than by whichever close happens to
   * bring the depth to zero. The {@code closed} flag makes that release happen exactly once.
   */
  private final class Handle implements Admission {
    private final Depth owner;
    private final boolean ownsPermit;
    private final AtomicBoolean closed = new AtomicBoolean();

    Handle(Depth owner, boolean ownsPermit) {
      this.owner = owner;
      this.ownsPermit = ownsPermit;
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      owner.value.decrementAndGet();
      if (ownsPermit) {
        semaphore.release();
      }
    }
  }
}
