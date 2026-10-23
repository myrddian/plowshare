package io.aeyer.plowshare.server.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.pool.AdmissionThrottle.Admission;
import io.aeyer.plowshare.server.pool.AdmissionThrottle.OverdraftListener;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Exercises the throttle policy with a fake clock, fake randomness and a recording sleeper. */
class AdmissionThrottleTest {

  private static final int PERMITS = 20;
  private static final int THRESHOLD = 10;
  private static final Duration BASE = Duration.ofMillis(20);
  private static final Duration CEILING = Duration.ofSeconds(3);

  private final AtomicLong clock = new AtomicLong();
  private final List<Duration> slept = new ArrayList<>();
  private double random = 0.0;

  private AdmissionThrottle throttle() {
    return new AdmissionThrottle(
        PERMITS, THRESHOLD, BASE, CEILING, clock::get, () -> random, slept::add);
  }

  /** Same fixture, with the permit-free nested admissions reported to {@code overdraft}. */
  private AdmissionThrottle throttle(OverdraftListener overdraft) {
    return new AdmissionThrottle(
        PERMITS,
        THRESHOLD,
        BASE,
        CEILING,
        AdmissionThrottle.DEFAULT_PRESSURE_WINDOW,
        clock::get,
        () -> random,
        slept::add,
        waited -> {},
        overdraft);
  }

  /**
   * Runs the callable on a fresh thread and waits for it. Re-entrancy is per thread, so raising
   * occupancy means acquiring from distinct threads; the admission outlives the thread.
   */
  private static <T> T onNewThread(Callable<T> work) throws Exception {
    FutureTask<T> task = new FutureTask<>(work);
    Thread thread = new Thread(task);
    thread.setDaemon(true);
    thread.start();
    try {
      return task.get(5, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      throw new AssertionError(e.getCause());
    }
  }

  private static void fillTo(AdmissionThrottle throttle, int occupancy) throws Exception {
    while (throttle.held() < occupancy) {
      onNewThread(throttle::acquire);
    }
  }

  @Test
  void below_the_threshold_grants_immediately() throws Exception {
    AdmissionThrottle throttle = throttle();
    fillTo(throttle, THRESHOLD - 1);

    Admission admission = onNewThread(throttle::acquire);

    assertEquals(Duration.ZERO, throttle.lastDelay());
    assertTrue(slept.isEmpty());
    assertEquals(THRESHOLD, throttle.held());
    admission.close();
  }

  @Test
  void above_the_threshold_delays_on_a_geometric_curve() throws Exception {
    AdmissionThrottle throttle = throttle();
    fillTo(throttle, THRESHOLD);
    Duration previous = Duration.ZERO;
    for (int occupancy = THRESHOLD; occupancy < PERMITS; occupancy++) {
      assertEquals(occupancy, throttle.held());
      onNewThread(throttle::acquire);
      Duration delay = throttle.lastDelay();
      assertTrue(delay.compareTo(previous) > 0, "delay must grow at occupancy " + occupancy);
      assertTrue(delay.compareTo(CEILING) <= 0);
      previous = delay;
    }
  }

  @Test
  void the_curve_is_derived_from_the_configured_values_not_a_literal_doubling() throws Exception {
    AdmissionThrottle throttle = throttle();
    fillTo(throttle, THRESHOLD);
    onNewThread(throttle::acquire);
    double first = throttle.lastDelay().toNanos();
    onNewThread(throttle::acquire);
    double second = throttle.lastDelay().toNanos();

    double expectedRatio =
        Math.pow((double) CEILING.toNanos() / BASE.toNanos(), 1.0 / (PERMITS - THRESHOLD - 1));
    assertEquals(expectedRatio, second / first, 1e-3);
    assertTrue(Math.abs(expectedRatio - 2.0) > 0.1, "the fixture must not coincide with x2");
  }

  @Test
  void the_delay_never_exceeds_the_ceiling() throws Exception {
    random = 1.0;
    AdmissionThrottle throttle = throttle();
    fillTo(throttle, THRESHOLD);
    for (int occupancy = THRESHOLD; occupancy < PERMITS; occupancy++) {
      onNewThread(throttle::acquire);
      assertTrue(
          throttle.lastDelay().compareTo(CEILING) <= 0,
          "delay at occupancy " + occupancy + " exceeded the ceiling");
    }
  }

  @Test
  void at_full_occupancy_the_delay_stops_growing_and_the_acquire_still_proceeds() throws Exception {
    AdmissionThrottle throttle = throttle();
    fillTo(throttle, PERMITS - 1);
    List<Admission> holders = new ArrayList<>();
    holders.add(onNewThread(throttle::acquire));
    Duration lastBelowFull = throttle.lastDelay();
    assertEquals(PERMITS, throttle.held());

    // The pool is full: the next acquirer waits for a permit rather than being refused.
    FutureTask<Admission> waiter = new FutureTask<>(throttle::acquire);
    Thread waiting = new Thread(waiter);
    waiting.setDaemon(true);
    waiting.start();
    assertThrows(TimeoutException.class, () -> waiter.get(200, TimeUnit.MILLISECONDS));

    holders.get(0).close();
    Admission admitted = waiter.get(5, TimeUnit.SECONDS);
    assertEquals(lastBelowFull, throttle.lastDelay(), "the curve is clamped at the last step");
    assertEquals(PERMITS, throttle.held());
    admitted.close();
  }

  @Test
  void a_throwing_saturation_listener_neither_leaks_a_permit_nor_refuses_the_acquire()
      throws Exception {
    AtomicInteger reports = new AtomicInteger();
    AdmissionThrottle throttle =
        new AdmissionThrottle(
            PERMITS,
            THRESHOLD,
            BASE,
            CEILING,
            AdmissionThrottle.DEFAULT_PRESSURE_WINDOW,
            clock::get,
            () -> random,
            slept::add,
            waited -> {
              reports.incrementAndGet();
              throw new IllegalStateException("broken metrics registry");
            });
    List<Admission> holders = new ArrayList<>();
    for (int i = 0; i < PERMITS; i++) {
      holders.add(onNewThread(throttle::acquire));
    }
    assertEquals(PERMITS, throttle.held());

    // The pool is full, so this acquirer blocks, then fires the (throwing) listener once freed.
    FutureTask<Admission> waiter = new FutureTask<>(throttle::acquire);
    Thread waiting = new Thread(waiter);
    waiting.setDaemon(true);
    waiting.start();
    assertThrows(TimeoutException.class, () -> waiter.get(200, TimeUnit.MILLISECONDS));
    holders.remove(0).close();

    Admission admitted = waiter.get(5, TimeUnit.SECONDS);
    assertEquals(1, reports.get(), "the wait is still reported exactly once");
    assertEquals(PERMITS, throttle.held());

    admitted.close();
    holders.forEach(Admission::close);
    assertEquals(0, throttle.held());

    // Full capacity must still be available: no permit was lost to the failed report.
    List<Admission> again = new ArrayList<>();
    for (int i = 0; i < PERMITS; i++) {
      again.add(onNewThread(throttle::acquire));
    }
    assertEquals(PERMITS, throttle.held());
    again.forEach(Admission::close);
    assertEquals(0, throttle.held());
  }

  @Test
  void jitter_is_proportional_to_the_quantum() throws Exception {
    AdmissionThrottle throttle = throttle();
    fillTo(throttle, THRESHOLD);

    // At the threshold the quantum is exactly the base; the wait is drawn from [q/2, q].
    random = 0.0;
    onNewThread(throttle::acquire).close();
    assertEquals(BASE.dividedBy(2), throttle.lastDelay());

    random = 0.5;
    onNewThread(throttle::acquire).close();
    assertEquals(BASE.multipliedBy(3).dividedBy(4), throttle.lastDelay());

    random = 1.0;
    onNewThread(throttle::acquire).close();
    assertEquals(BASE, throttle.lastDelay());
    assertEquals(THRESHOLD, throttle.held());
  }

  /**
   * The gate bounds units of work, and a nested acquire is a second physical connection on one
   * thread (Spring's {@code PROPAGATION_REQUIRES_NEW} suspends the outer holder and borrows again).
   * While capacity is free that second connection must cost a permit, or enough nesting threads
   * overrun the pool and fail on the pool's own acquire timeout.
   */
  @Test
  void a_reentrant_acquire_takes_a_real_permit_when_one_is_immediately_free() throws Exception {
    AtomicInteger overdrafts = new AtomicInteger();
    AdmissionThrottle throttle = throttle(overdrafts::incrementAndGet);

    Admission outer = throttle.acquire();
    assertEquals(1, throttle.held());
    Admission inner = throttle.acquire();
    assertEquals(2, throttle.held(), "the nested connection must be accounted for");
    assertTrue(slept.isEmpty(), "a nested acquire is never delayed");
    assertEquals(0, overdrafts.get(), "a nested acquire that took a permit is not an overdraft");

    inner.close();
    assertEquals(1, throttle.held(), "the inner level releases its own permit, not the outer's");
    outer.close();
    assertEquals(0, throttle.held());
  }

  @Test
  void closing_nesting_levels_out_of_order_still_releases_each_permit_exactly_once()
      throws Exception {
    AdmissionThrottle throttle = throttle();
    Admission outer = throttle.acquire();
    Admission inner = throttle.acquire();
    assertEquals(2, throttle.held());

    outer.close();
    assertEquals(1, throttle.held(), "the outer close must not release the inner level's permit");
    outer.close();
    assertEquals(1, throttle.held());
    inner.close();
    assertEquals(0, throttle.held());
    inner.close();
    assertEquals(0, throttle.held());

    // Capacity is intact: nothing was double-released and nothing was stranded.
    List<Admission> all = new ArrayList<>();
    for (int i = 0; i < PERMITS; i++) {
      all.add(onNewThread(throttle::acquire));
    }
    assertEquals(PERMITS, throttle.held());
    all.forEach(Admission::close);
    assertEquals(0, throttle.held());
  }

  /**
   * The no-deadlock property. A thread holding the last permit must not block for a second one,
   * because only it could release one; the nested level proceeds permit-free, and that fall-through
   * is the only thing counted as an overdraft.
   */
  @Test
  void a_nested_acquire_at_saturation_proceeds_permit_free_instead_of_blocking() throws Exception {
    AtomicInteger overdrafts = new AtomicInteger();
    AdmissionThrottle throttle = throttle(overdrafts::incrementAndGet);
    fillTo(throttle, PERMITS - 1);

    int heldInside =
        onNewThread(
            () -> {
              Admission outer = throttle.acquire();
              assertEquals(PERMITS, throttle.held());
              assertEquals(0, overdrafts.get(), "an outer acquire is never an overdraft");
              Admission inner = throttle.acquire();
              int inside = throttle.held();
              inner.close();
              outer.close();
              return inside;
            });

    // onNewThread times out after 5s, so a nested acquire that blocked would fail this test.
    assertEquals(PERMITS, heldInside, "the nested level must hold no permit of its own");
    assertEquals(1, overdrafts.get(), "exactly one permit-free nested admission");
    assertEquals(PERMITS - 1, throttle.held());
  }

  /**
   * Pins the fairness of the nested take. A permit is released while a non-nested caller is parked
   * on the semaphore, and in that window the permit is nominally free: {@code availablePermits()}
   * is positive until the woken waiter claims it. The nested take must honour the queue (the timed
   * zero-wait {@code tryAcquire}) and fall through to an overdraft; the untimed {@code
   * tryAcquire()} would barge and steal the permit the waiter is owed.
   *
   * <p>The correct code is deterministic here: with a queued predecessor the take fails, and once
   * the waiter has claimed the permit none is left, so every round must count an overdraft. The
   * barging mutation can only succeed by winning the race against the waiter's wake-up, which the
   * releasing thread usually does because it proceeds straight into the acquire. That race cannot
   * be forced from outside the semaphore, so the scenario is repeated and the assertion fails on
   * the first round in which a barge steals the permit. Every wait is bounded, so a regression
   * fails instead of hanging.
   */
  @Test
  void a_nested_acquire_does_not_barge_past_a_queued_waiter() throws Exception {
    AtomicInteger overdrafts = new AtomicInteger();
    AdmissionThrottle throttle = throttle(overdrafts::incrementAndGet);
    ArrayDeque<Admission> releasable = new ArrayDeque<>();
    List<Admission> mine = new ArrayList<>();

    // This thread holds the outer permit; every other permit is held by someone else.
    mine.add(throttle.acquire());
    for (int i = 1; i < PERMITS; i++) {
      releasable.add(onNewThread(throttle::acquire));
    }
    assertEquals(PERMITS, throttle.held());

    int rounds = 200;
    for (int round = 1; round <= rounds; round++) {
      FutureTask<Admission> queued = new FutureTask<>(throttle::acquire);
      Thread waiter = new Thread(queued, "queued-waiter-" + round);
      waiter.setDaemon(true);
      waiter.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      // WAITING is a park on the semaphore; a curve delay would be TIMED_WAITING.
      while (waiter.getState() != Thread.State.WAITING) {
        assertTrue(System.nanoTime() < deadline, "waiter never parked on the semaphore");
        Thread.onSpinWait();
      }

      // Frees one permit from this thread, then nests at once, while the waiter is still queued.
      releasable.poll().close();
      mine.add(throttle.acquire());
      assertEquals(
          round,
          overdrafts.get(),
          "round " + round + ": the nested take barged past the queued waiter");

      releasable.add(queued.get(5, TimeUnit.SECONDS));
      assertEquals(PERMITS, throttle.held(), "the waiter, not the nested level, took the permit");
    }
    mine.forEach(Admission::close);
    releasable.forEach(Admission::close);
    assertEquals(0, throttle.held());
  }

  /**
   * The nesting depth is raised before the permit attempt, so an {@link InterruptedException}
   * escaping the nested take would strand it and leave the thread permanently nested. The take
   * instead restores the flag and proceeds permit-free. Afterwards the thread must still be able to
   * make a non-nested acquire, observed as the curve delay that only a non-nested acquire applies.
   */
  @Test
  void a_nested_acquire_on_an_interrupted_thread_neither_throws_nor_strands_the_depth()
      throws Exception {
    AtomicInteger overdrafts = new AtomicInteger();
    AdmissionThrottle throttle = throttle(overdrafts::incrementAndGet);
    fillTo(throttle, THRESHOLD);

    onNewThread(
        () -> {
          Admission outer = throttle.acquire();
          int delaysBefore = slept.size();
          Thread.currentThread().interrupt();

          Admission nested = throttle.acquire();
          assertNotNull(nested);
          assertTrue(Thread.currentThread().isInterrupted(), "the interrupt must be preserved");
          assertEquals(1, overdrafts.get(), "the permit-free nested level is an overdraft");
          assertEquals(THRESHOLD + 1, throttle.held(), "the nested level holds no permit");
          Thread.interrupted();

          nested.close();
          outer.close();
          assertEquals(THRESHOLD, throttle.held());
          assertEquals(delaysBefore, slept.size(), "nothing so far was delayed");

          // Depth back at zero: this is a fresh non-nested acquire, which at this occupancy is
          // delayed. A stranded depth would make it nested, which is never delayed.
          Admission fresh = throttle.acquire();
          assertEquals(delaysBefore + 1, slept.size(), "a stranded depth skips the curve delay");
          assertEquals(THRESHOLD + 1, throttle.held());
          fresh.close();
          return null;
        });
    assertEquals(THRESHOLD, throttle.held());
  }

  @Test
  void nothing_but_a_permit_free_nested_admission_counts_as_an_overdraft() throws Exception {
    AtomicInteger overdrafts = new AtomicInteger();
    AdmissionThrottle throttle = throttle(overdrafts::incrementAndGet);

    // Nesting while capacity is free takes a real permit, so it is not an overdraft.
    Admission outer = throttle.acquire();
    Admission inner = throttle.acquire();
    assertEquals(2, throttle.held());
    assertEquals(0, overdrafts.get());
    inner.close();
    outer.close();
    assertEquals(0, throttle.held());

    // Plain acquires, a delayed acquire and a saturation wait are not overdrafts either.
    fillTo(throttle, PERMITS - 1);
    assertEquals(0, overdrafts.get());
    Admission last = onNewThread(throttle::acquire);
    assertEquals(PERMITS, throttle.held());
    assertEquals(0, overdrafts.get(), "a delayed acquire is not an overdraft");

    FutureTask<Admission> waiter = new FutureTask<>(throttle::acquire);
    Thread waiting = new Thread(waiter);
    waiting.setDaemon(true);
    waiting.start();
    assertThrows(TimeoutException.class, () -> waiter.get(200, TimeUnit.MILLISECONDS));
    last.close();
    waiter.get(5, TimeUnit.SECONDS).close();
    assertEquals(0, overdrafts.get(), "a saturation wait is not an overdraft");
  }

  /**
   * In production the sleeper is wrapped by the metrics, so a broken registry used to throw a
   * {@link RuntimeException} out of {@code acquire()} and out of {@code getConnection()}
   * untranslated, which the class contract forbids.
   */
  @Test
  void a_throwing_sleeper_neither_refuses_the_acquire_nor_skips_the_delay() throws Exception {
    List<Duration> applied = new ArrayList<>();
    AdmissionThrottle throttle =
        new AdmissionThrottle(
            PERMITS,
            THRESHOLD,
            BASE,
            CEILING,
            AdmissionThrottle.DEFAULT_PRESSURE_WINDOW,
            clock::get,
            () -> random,
            delay -> {
              applied.add(delay);
              throw new IllegalStateException("broken metrics registry");
            });
    fillTo(throttle, THRESHOLD);

    Admission admitted = onNewThread(throttle::acquire);

    assertNotNull(admitted, "a metrics failure must not become a refusal");
    assertEquals(List.of(BASE.dividedBy(2)), applied, "the delay is still applied, not skipped");
    assertEquals(BASE.dividedBy(2), throttle.lastDelay());
    assertEquals(THRESHOLD + 1, throttle.held());
    admitted.close();
    assertEquals(THRESHOLD, throttle.held(), "no permit was stranded by the failure");
  }

  @Test
  void closing_a_nested_admission_twice_does_not_release_the_outer_permit() throws Exception {
    AdmissionThrottle throttle = throttle();
    Admission outer = throttle.acquire();
    Admission inner = throttle.acquire();

    inner.close();
    inner.close();

    assertEquals(1, throttle.held(), "a double close must release only the inner level's permit");
    outer.close();
    outer.close();
    assertEquals(0, throttle.held());
  }

  @Test
  void an_owner_whose_outer_handle_was_closed_by_another_thread_takes_a_fresh_permit()
      throws Exception {
    AdmissionThrottle throttle = throttle();
    AtomicReference<Admission> outer = new AtomicReference<>();
    AtomicInteger heldAfterReacquire = new AtomicInteger(-1);
    CountDownLatch acquired = new CountDownLatch(1);
    CountDownLatch foreignClosed = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(1);

    // The owner stays alive across the foreign close. The latches below order the two threads, so
    // the owner always sees the closed state: this test deliberately does not exercise the
    // memory-visibility race. It pins the contract's outcome instead: once another thread closes
    // the outermost handle, the owner's next acquire takes a fresh permit rather than running
    // without one.
    Thread owner =
        new Thread(
            () -> {
              try {
                outer.set(throttle.acquire());
                acquired.countDown();
                foreignClosed.await();
                Admission again = throttle.acquire();
                heldAfterReacquire.set(throttle.held());
                again.close();
                finished.countDown();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
    owner.setDaemon(true);
    owner.start();

    assertTrue(acquired.await(5, TimeUnit.SECONDS));
    outer.get().close();
    assertEquals(0, throttle.held());
    foreignClosed.countDown();

    assertTrue(finished.await(5, TimeUnit.SECONDS));
    assertEquals(1, heldAfterReacquire.get(), "the owner must hold a real permit, not run bare");
    assertEquals(0, throttle.held());
  }

  @Test
  void a_negative_latency_never_produces_a_negative_delay() throws Exception {
    AdmissionThrottle throttle = throttle();
    fillTo(throttle, THRESHOLD);
    for (int i = 0; i < 20; i++) {
      throttle.observed(Duration.ofSeconds(-30));
    }
    onNewThread(throttle::acquire);
    assertTrue(throttle.lastDelay().compareTo(BASE.dividedBy(2)) >= 0);
  }

  @Test
  void the_pressure_level_decays_with_elapsed_time_without_new_samples() throws Exception {
    AdmissionThrottle throttle = throttle();
    fillTo(throttle, THRESHOLD);
    onNewThread(throttle::acquire).close();
    Duration baseline = throttle.lastDelay();

    for (int i = 0; i < 10; i++) {
      throttle.observed(CEILING);
    }
    onNewThread(throttle::acquire).close();
    assertTrue(throttle.lastDelay().compareTo(baseline) > 0);

    clock.addAndGet(Duration.ofSeconds(60).toNanos());
    onNewThread(throttle::acquire).close();
    assertTrue(
        throttle.lastDelay().compareTo(baseline.plus(Duration.ofMillis(1))) < 0,
        "idle time alone must return the delay to baseline");
  }

  @Test
  void the_pressure_level_decays_when_latency_falls() throws Exception {
    AdmissionThrottle throttle = throttle();
    fillTo(throttle, THRESHOLD);

    for (int i = 0; i < 10; i++) {
      throttle.observed(CEILING);
    }
    Admission pressured = onNewThread(throttle::acquire);
    Duration underPressure = throttle.lastDelay();
    pressured.close();

    for (int i = 0; i < 50; i++) {
      throttle.observed(Duration.ZERO);
    }
    fillTo(throttle, THRESHOLD);
    onNewThread(throttle::acquire);

    assertTrue(
        throttle.lastDelay().compareTo(underPressure) < 0,
        "delay must fall once acquire latency falls");
  }

  @Test
  void the_pressure_window_sets_how_fast_an_idle_level_decays() throws Exception {
    Duration idle = Duration.ofSeconds(10);
    Duration shortWindow = delayAfterIdle(Duration.ofSeconds(1), idle);
    Duration longWindow = delayAfterIdle(Duration.ofSeconds(100), idle);

    assertTrue(
        longWindow.compareTo(shortWindow.plus(Duration.ofMillis(5))) > 0,
        "a longer window must retain more pressure over the same idle time");
  }

  @Test
  void a_window_equal_to_the_default_behaves_like_the_default_constructor() throws Exception {
    assertEquals(
        delayAfterIdle(AdmissionThrottle.DEFAULT_PRESSURE_WINDOW, Duration.ofSeconds(10)),
        delayAfterIdle(null, Duration.ofSeconds(10)));
  }

  @Test
  void a_non_positive_pressure_window_is_rejected_at_construction() {
    for (Duration bad : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1)}) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new AdmissionThrottle(
                  PERMITS, THRESHOLD, BASE, CEILING, bad, clock::get, () -> random, slept::add));
    }
  }

  /** Saturates pressure, idles for the given time and returns the next delay at the threshold. */
  private Duration delayAfterIdle(Duration window, Duration idle) throws Exception {
    AtomicLong localClock = new AtomicLong();
    AdmissionThrottle local =
        window == null
            ? new AdmissionThrottle(
                PERMITS, THRESHOLD, BASE, CEILING, localClock::get, () -> 0.0, d -> {})
            : new AdmissionThrottle(
                PERMITS, THRESHOLD, BASE, CEILING, window, localClock::get, () -> 0.0, d -> {});
    fillTo(local, THRESHOLD);
    for (int i = 0; i < 10; i++) {
      local.observed(CEILING);
    }
    localClock.addAndGet(idle.toNanos());
    onNewThread(local::acquire).close();
    return local.lastDelay();
  }
}
