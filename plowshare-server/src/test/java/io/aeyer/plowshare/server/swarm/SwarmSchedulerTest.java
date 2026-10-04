package io.aeyer.plowshare.server.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.aeyer.plowshare.server.swarm.SwarmScheduler.Grant;
import io.aeyer.plowshare.server.swarm.SwarmScheduler.Share;
import io.aeyer.plowshare.server.swarm.SwarmScheduler.Turns;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Who runs now — spec 2026-09-29 §5. Every wait that must be seen queueing runs on its own thread;
 * {@link #untilWaiting} is the only way the test learns it has queued, so no assertion here depends
 * on a sleep being long enough.
 */
class SwarmSchedulerTest {

  private static final Duration POLL = Duration.ofMillis(10);

  private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

  @AfterEach
  void stop() {
    threads.shutdownNow();
  }

  /** Pools that serve model "m", with the given swarm slots, in declared order. */
  private record FakePools(Map<String, Integer> slots) implements SwarmScheduler.Pools {
    @Override
    public List<String> serving(String specifier) {
      return specifier.equals("m") ? List.copyOf(slots.keySet()) : List.of();
    }

    @Override
    public int slots(String pool) {
      return slots.getOrDefault(pool, 0);
    }

    @Override
    public List<String> all() {
      return List.copyOf(slots.keySet());
    }
  }

  /**
   * Wraps another {@link SwarmScheduler.Pools} and, once {@link #arm}ed, interrupts a chosen thread
   * on the first call to {@link #slots} afterward — {@code dispatch()} calls {@code slots} under
   * the scheduler's lock while placing a waiter, before it signals, which is exactly the window the
   * interrupt-after-grant race (task 4 fix round 1) needs to land in.
   */
  private static final class InterruptingPools implements SwarmScheduler.Pools {
    private final SwarmScheduler.Pools delegate;
    private volatile Thread target;
    private final AtomicBoolean armed = new AtomicBoolean();
    private final AtomicBoolean fired = new AtomicBoolean();

    InterruptingPools(SwarmScheduler.Pools delegate) {
      this.delegate = delegate;
    }

    void arm(Thread target) {
      this.target = target;
      armed.set(true);
    }

    @Override
    public List<String> serving(String specifier) {
      return delegate.serving(specifier);
    }

    @Override
    public int slots(String pool) {
      if (armed.get() && fired.compareAndSet(false, true)) {
        target.interrupt();
        // The interrupted waiter and this thread's later signalAll() race to claim the
        // waiter's condition node with the same lock-free CAS; left alone, this thread
        // (already running, holding the lock) reaches signalAll() well before the
        // parked virtual thread is rescheduled, so the interrupt is silently absorbed
        // as a reinterrupt instead of surfacing as the race under test. Sleeping here —
        // still holding the scheduler's lock, so dispatch() cannot reach signalAll()
        // until this returns — gives the waiter's continuation time to run first and
        // claim the node itself, forcing the InterruptedException path deterministically.
        try {
          Thread.sleep(200);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return delegate.slots(pool);
    }

    @Override
    public List<String> all() {
      return delegate.all();
    }
  }

  /**
   * Wraps another {@link SwarmScheduler.Pools} and, while {@link #arm}ed, throws from every {@link
   * #slots} call — standing in for anything that can fail under the scheduler's lock after a waiter
   * has queued. The waiter's own {@code dispatch()}, right after it enqueues, asks {@code slots},
   * so an armed stub fails that wait with its entry already in the queue.
   */
  private static final class ThrowingPools implements SwarmScheduler.Pools {
    private final SwarmScheduler.Pools delegate;
    private final AtomicBoolean armed = new AtomicBoolean();

    ThrowingPools(SwarmScheduler.Pools delegate) {
      this.delegate = delegate;
    }

    void arm() {
      armed.set(true);
    }

    void disarm() {
      armed.set(false);
    }

    @Override
    public List<String> serving(String specifier) {
      return delegate.serving(specifier);
    }

    @Override
    public int slots(String pool) {
      if (armed.get()) {
        throw new IllegalStateException("pools unreadable");
      }
      return delegate.slots(pool);
    }

    @Override
    public List<String> all() {
      return delegate.all();
    }
  }

  /**
   * Wraps another {@link SwarmScheduler.Pools} and, once {@link #arm}ed, sets a flag on the first
   * {@link #slots} call afterward. {@code dispatch()} asks {@code slots} under the lock before it
   * grants, so a release's dispatch sets a waiter's cancel flag and then grants that same waiter,
   * and the waiter — which needs the lock to look at either — can only ever see both at once: a
   * grant that lands after its cancel.
   */
  private static final class CancellingPools implements SwarmScheduler.Pools {
    private final SwarmScheduler.Pools delegate;
    private volatile AtomicBoolean target;

    CancellingPools(SwarmScheduler.Pools delegate) {
      this.delegate = delegate;
    }

    void arm(AtomicBoolean cancel) {
      this.target = cancel;
    }

    @Override
    public List<String> serving(String specifier) {
      return delegate.serving(specifier);
    }

    @Override
    public int slots(String pool) {
      AtomicBoolean cancel = target;
      if (cancel != null) {
        target = null;
        cancel.set(true);
      }
      return delegate.slots(pool);
    }

    @Override
    public List<String> all() {
      return delegate.all();
    }
  }

  private static FakePools pools(Object... nameThenSlots) {
    Map<String, Integer> slots = new LinkedHashMap<>();
    for (int i = 0; i < nameThenSlots.length; i += 2) {
      slots.put((String) nameThenSlots[i], (Integer) nameThenSlots[i + 1]);
    }
    return new FakePools(slots);
  }

  /**
   * Pools with per-specifier candidates, for tests where two specifiers route to two different
   * pools with no candidate in common.
   */
  private record RoutedPools(Map<String, Integer> slots, Map<String, List<String>> serving)
      implements SwarmScheduler.Pools {
    @Override
    public List<String> serving(String specifier) {
      return serving.getOrDefault(specifier, List.of());
    }

    @Override
    public int slots(String pool) {
      return slots.getOrDefault(pool, 0);
    }

    @Override
    public List<String> all() {
      return List.copyOf(slots.keySet());
    }
  }

  /** A clock the test moves by hand. */
  private static final class Ticking extends Clock {
    private volatile Instant now = Instant.parse("2026-09-29T09:00:00Z");

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static SwarmScheduler scheduler(SwarmScheduler.Pools pools, int quantum) {
    return new SwarmScheduler(
        pools, () -> quantum, () -> Duration.ofMinutes(10), new Ticking(), POLL);
  }

  private static Share share(String account, String topic, String member) {
    return new Share(account, topic, member);
  }

  private static final BooleanSupplier NEVER = () -> false;

  private CompletableFuture<Grant> waiting(Turns turns, BooleanSupplier cancelled) {
    return CompletableFuture.supplyAsync(() -> turns.await("m", cancelled), threads);
  }

  private static void untilWaiting(SwarmScheduler scheduler, int count)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (scheduler.snapshot().ready().size() != count) {
      if (System.nanoTime() > deadline) {
        fail("expected " + count + " waiting, saw " + scheduler.snapshot().ready());
      }
      Thread.sleep(5);
    }
  }

  private static Grant granted(CompletableFuture<Grant> wait) throws Exception {
    Grant grant = wait.get(5, TimeUnit.SECONDS);
    assertNotNull(grant, "the wait ended without a slot");
    return grant;
  }

  private static int used(SwarmScheduler scheduler, String pool) {
    return scheduler.snapshot().pools().stream()
        .filter(use -> use.pool().equals(pool))
        .findFirst()
        .orElseThrow()
        .used();
  }

  @Test
  void a_run_is_given_a_slot_on_a_pool_that_serves_its_model() {
    SwarmScheduler scheduler = scheduler(pools("spark", 2), 4);
    Grant grant = scheduler.enter(share("a", "t", "m")).await("m", NEVER);
    assertEquals("spark", grant.pool());
    assertEquals(1, used(scheduler, "spark"));
    grant.release();
    assertEquals(0, used(scheduler, "spark"));
  }

  @Test
  void a_run_waits_while_every_slot_is_held_and_takes_the_one_released() throws Exception {
    SwarmScheduler scheduler = scheduler(pools("spark", 1), 4);
    Grant holder = scheduler.enter(share("a", "t", "m1")).await("m", NEVER);
    CompletableFuture<Grant> second = waiting(scheduler.enter(share("a", "t", "m2")), NEVER);
    untilWaiting(scheduler, 1);
    assertFalse(second.isDone());
    holder.release();
    assertEquals("spark", granted(second).pool());
    assertEquals(1, used(scheduler, "spark"));
  }

  @Test
  void the_least_used_pool_takes_the_run_and_a_tie_goes_to_the_first_declared() {
    SwarmScheduler scheduler = scheduler(pools("p1", 2, "p2", 2), 4);
    assertEquals("p1", scheduler.enter(share("a", "t", "m1")).await("m", NEVER).pool());
    assertEquals("p2", scheduler.enter(share("a", "t", "m2")).await("m", NEVER).pool());
    assertEquals("p1", scheduler.enter(share("a", "t", "m3")).await("m", NEVER).pool());
  }

  @Test
  void a_cancelled_wait_leaves_the_queue_and_returns_no_slot() throws Exception {
    SwarmScheduler scheduler = scheduler(pools("spark", 1), 4);
    Grant holder = scheduler.enter(share("a", "t", "m1")).await("m", NEVER);
    AtomicBoolean cancelled = new AtomicBoolean();
    CompletableFuture<Grant> second =
        waiting(scheduler.enter(share("a", "t", "m2")), cancelled::get);
    untilWaiting(scheduler, 1);
    cancelled.set(true);
    assertNull(second.get(5, TimeUnit.SECONDS));
    assertEquals(0, scheduler.snapshot().ready().size());
    holder.release();
    assertEquals(0, used(scheduler, "spark"), "a slot went to a wait that had already gone");
  }

  @Test
  void releasing_twice_gives_back_one_slot() {
    SwarmScheduler scheduler = scheduler(pools("spark", 2), 4);
    Grant first = scheduler.enter(share("a", "t", "m1")).await("m", NEVER);
    scheduler.enter(share("a", "t", "m2")).await("m", NEVER);
    first.release();
    first.release();
    assertEquals(1, used(scheduler, "spark"));
  }

  @Test
  void a_model_no_swarm_pool_serves_waits_and_is_reported_overdue_after_the_warning()
      throws Exception {
    Ticking clock = new Ticking();
    SwarmScheduler scheduler =
        new SwarmScheduler(pools("spark", 1), () -> 4, () -> Duration.ofMinutes(10), clock, POLL);
    AtomicBoolean cancelled = new AtomicBoolean();
    Turns turns = scheduler.enter(share("a", "t", "m"));
    CompletableFuture<Grant> wait =
        CompletableFuture.supplyAsync(() -> turns.await("unserved", cancelled::get), threads);
    untilWaiting(scheduler, 1);
    SwarmScheduler.Waiting before = scheduler.snapshot().ready().get(0);
    assertEquals("unserved", before.specifier());
    assertFalse(before.overdue());
    clock.advance(Duration.ofMinutes(11));
    SwarmScheduler.Waiting after = scheduler.snapshot().ready().get(0);
    assertTrue(after.overdue());
    assertEquals(Duration.ofMinutes(11), after.waited());
    cancelled.set(true);
    assertNull(wait.get(5, TimeUnit.SECONDS));
  }

  @Test
  void the_snapshot_names_each_waiter_and_its_place() throws Exception {
    SwarmScheduler scheduler = scheduler(pools("spark", 1), 4);
    Grant holder = scheduler.enter(share("a", "t", "m1")).await("m", NEVER);
    waiting(scheduler.enter(share("a", "t", "m2")), NEVER);
    untilWaiting(scheduler, 1);
    waiting(scheduler.enter(share("b", "t", "m1")), NEVER);
    untilWaiting(scheduler, 2);
    List<SwarmScheduler.Waiting> ready = scheduler.snapshot().ready();
    assertEquals(share("a", "t", "m2"), ready.get(0).share());
    assertEquals(1, ready.get(0).position());
    assertEquals(share("b", "t", "m1"), ready.get(1).share());
    assertEquals(2, ready.get(1).position());
    assertEquals(List.of(new SwarmScheduler.PoolUse("spark", 1, 1)), scheduler.snapshot().pools());
    holder.release();
  }

  @Test
  void an_interrupt_that_races_a_grant_gives_the_slot_back() throws Exception {
    InterruptingPools pools = new InterruptingPools(pools("spark", 1));
    SwarmScheduler scheduler =
        new SwarmScheduler(pools, () -> 4, () -> Duration.ofMinutes(10), new Ticking(), POLL);
    Grant holder = scheduler.enter(share("a", "t", "m1")).await("m", NEVER);
    Turns turns = scheduler.enter(share("a", "t", "m2"));
    AtomicReference<Thread> waiter = new AtomicReference<>();
    CompletableFuture<Grant> second =
        CompletableFuture.supplyAsync(
            () -> {
              waiter.set(Thread.currentThread());
              return turns.await("m", NEVER);
            },
            threads);
    untilWaiting(scheduler, 1);

    // Arm the interrupt: the next call into pools.slots() — dispatch() placing this waiter,
    // triggered by the release below — fires it while the waiter's node is still on the
    // condition queue, so the pending grant races the InterruptedException it causes.
    pools.arm(waiter.get());
    holder.release();

    assertNull(second.get(5, TimeUnit.SECONDS), "an interrupted wait must hold nothing");
    assertEquals(0, used(scheduler, "spark"), "the slot leaked after an interrupt raced the grant");
    assertEquals(0, scheduler.snapshot().ready().size());

    Grant third = scheduler.enter(share("a", "t", "m3")).await("m", NEVER);
    assertEquals("spark", third.pool(), "the slot the interrupt gave back must still be usable");
  }

  /**
   * A failure under the lock after a waiter has queued — here the pools throwing from the waiter's
   * own dispatch — propagates out of {@code await}, and takes the waiter out of the queue with it.
   * Left behind, the entry would later be granted a slot by somebody else's dispatch, and nobody
   * would ever release it.
   */
  @Test
  void a_wait_that_fails_after_queueing_leaves_no_waiter_behind() throws Exception {
    ThrowingPools pools = new ThrowingPools(pools("spark", 1));
    SwarmScheduler scheduler =
        new SwarmScheduler(pools, () -> 4, () -> Duration.ofMinutes(10), new Ticking(), POLL);
    Grant holder = scheduler.enter(share("a", "t", "m1")).await("m", NEVER);

    pools.arm();
    CompletableFuture<Grant> failed = waiting(scheduler.enter(share("a", "t", "m2")), NEVER);
    ExecutionException thrown =
        assertThrows(ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS));
    assertEquals("pools unreadable", thrown.getCause().getMessage());
    pools.disarm();

    assertEquals(List.of(), scheduler.snapshot().ready(), "the failed wait is still queued");
    holder.release();
    assertEquals(0, used(scheduler, "spark"), "a slot went to a wait that had already failed");
    Grant next = scheduler.enter(share("a", "t", "m3")).await("m", NEVER);
    assertEquals("spark", next.pool());
  }

  /**
   * A cancel set while the waiter sleeps, with a grant landing before it wakes, is still a cancel:
   * the wait returns nothing, holds nothing, and the slot goes to whoever is next — a run cancelled
   * while waiting spends no model call.
   */
  @Test
  void a_grant_that_lands_after_the_cancel_is_given_back() throws Exception {
    CancellingPools pools = new CancellingPools(pools("spark", 1));
    SwarmScheduler scheduler =
        new SwarmScheduler(pools, () -> 4, () -> Duration.ofMinutes(10), new Ticking(), POLL);
    Grant holder = scheduler.enter(share("a", "t", "m1")).await("m", NEVER);
    AtomicBoolean cancelled = new AtomicBoolean();
    CompletableFuture<Grant> second =
        waiting(scheduler.enter(share("a", "t", "m2")), cancelled::get);
    untilWaiting(scheduler, 1);

    // The release's dispatch sets the cancel, then grants the waiter, before the waiter can
    // look again: it wakes with both the grant and the cancel.
    pools.arm(cancelled);
    holder.release();

    assertNull(second.get(5, TimeUnit.SECONDS), "a cancelled wait was handed a slot");
    assertEquals(0, used(scheduler, "spark"), "the slot a cancelled wait was granted leaked");
    assertEquals(0, scheduler.snapshot().ready().size());
    Grant next = scheduler.enter(share("a", "t", "m3")).await("m", NEVER);
    assertEquals("spark", next.pool(), "the slot the cancelled wait gave back must be usable");
  }

  /**
   * The two live keys are read from the config store, which can fail. A read that fails falls back
   * — to the last value read, or to the defaults (4, ten minutes) when none ever was — and neither
   * a wait nor the snapshot fails with it.
   */
  @Test
  void a_live_key_that_cannot_be_read_falls_back_and_fails_neither_a_wait_nor_the_snapshot()
      throws Exception {
    Ticking clock = new Ticking();
    SwarmScheduler scheduler =
        new SwarmScheduler(
            pools("spark", 1),
            () -> {
              throw new IllegalStateException("config store unreachable");
            },
            () -> {
              throw new IllegalStateException("config store unreachable");
            },
            clock,
            POLL);
    Turns a = scheduler.enter(share("a", "t", "m"));
    a.await("m", NEVER).release();
    // The second wait is inside the quantum A's first one opened, so the quantum is asked.
    Grant a2 = a.await("m", NEVER);
    assertEquals("spark", a2.pool());

    CompletableFuture<Grant> b = waiting(scheduler.enter(share("b", "t", "m")), NEVER);
    untilWaiting(scheduler, 1);
    clock.advance(Duration.ofMinutes(9));
    assertFalse(scheduler.snapshot().ready().get(0).overdue());
    clock.advance(Duration.ofMinutes(2));
    assertTrue(
        scheduler.snapshot().ready().get(0).overdue(),
        "with no warning ever read, the fallback is ten minutes");
    a2.release();
    granted(b).release();
  }

  @Test
  void a_live_key_that_stops_being_readable_keeps_its_last_value() throws Exception {
    Ticking clock = new Ticking();
    AtomicBoolean failing = new AtomicBoolean();
    SwarmScheduler scheduler =
        new SwarmScheduler(
            pools("spark", 1),
            () -> 4,
            () -> {
              if (failing.get()) {
                throw new IllegalStateException("config store unreachable");
              }
              return Duration.ofMinutes(2);
            },
            clock,
            POLL);
    Grant holder = scheduler.enter(share("a", "t", "m1")).await("m", NEVER);
    CompletableFuture<Grant> b = waiting(scheduler.enter(share("b", "t", "m")), NEVER);
    untilWaiting(scheduler, 1);
    failing.set(true);
    clock.advance(Duration.ofMinutes(3));
    assertTrue(
        scheduler.snapshot().ready().get(0).overdue(),
        "the last warning read was two minutes, not the ten-minute default");
    holder.release();
    granted(b).release();
  }

  /**
   * Four waiters behind one slot, arriving a/t1/m1, a/t1/m2, a/t2/m1, b/t1/m1. First come, first
   * served would give account a three turns before b's first; fair-share alternates accounts, then
   * topics within an account, then members within a topic.
   */
  @Test
  void accounts_take_turns_then_topics_then_members() throws Exception {
    SwarmScheduler scheduler = scheduler(pools("spark", 1), 4);
    Grant holder = scheduler.enter(share("h", "t", "m")).await("m", NEVER);
    List<Share> order = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    List<Share> arrivals =
        List.of(
            share("a", "t1", "m1"),
            share("a", "t1", "m2"),
            share("a", "t2", "m1"),
            share("b", "t1", "m1"));
    List<CompletableFuture<Void>> done = new java.util.ArrayList<>();
    for (int i = 0; i < arrivals.size(); i++) {
      Turns turns = scheduler.enter(arrivals.get(i));
      done.add(
          CompletableFuture.runAsync(
              () -> {
                Grant grant = turns.await("m", NEVER);
                order.add(turns.share());
                grant.release();
              },
              threads));
      untilWaiting(scheduler, i + 1);
    }
    holder.release();
    CompletableFuture.allOf(done.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
    assertEquals(
        List.of(
            share("a", "t1", "m1"),
            share("b", "t1", "m1"),
            share("a", "t2", "m1"),
            share("a", "t1", "m2")),
        order);
  }

  /**
   * Q = 3. A holds, gives the slot back for a tool, and B takes it. A comes back inside its quantum
   * while C, who arrived first, is waiting: A goes ahead.
   */
  @Test
  void a_run_inside_its_quantum_goes_ahead_of_a_waiter_who_arrived_before_it() throws Exception {
    SwarmScheduler scheduler = scheduler(pools("spark", 1), 3);
    Turns a = scheduler.enter(share("a", "t", "m"));
    Grant a1 = a.await("m", NEVER);
    CompletableFuture<Grant> b = waiting(scheduler.enter(share("b", "t", "m")), NEVER);
    untilWaiting(scheduler, 1);
    a1.release();
    Grant b1 = granted(b);
    CompletableFuture<Grant> c = waiting(scheduler.enter(share("c", "t", "m")), NEVER);
    untilWaiting(scheduler, 1);
    CompletableFuture<Grant> a2 = waiting(a, NEVER);
    untilWaiting(scheduler, 2);
    b1.release();
    Grant a2granted = granted(a2);
    assertFalse(c.isDone(), "the newcomer went ahead of a run inside its quantum");
    a2granted.release();
    granted(c);
  }

  /**
   * Q = 2. A's second step used its quantum; its third wait is a fresh one, and D, waiting before
   * it, is served first.
   */
  @Test
  void a_run_whose_quantum_is_spent_waits_behind_one_that_arrived_before_it() throws Exception {
    SwarmScheduler scheduler = scheduler(pools("spark", 1), 2);
    Turns a = scheduler.enter(share("a", "t", "m"));
    Grant a1 = a.await("m", NEVER);
    CompletableFuture<Grant> b = waiting(scheduler.enter(share("b", "t", "m")), NEVER);
    untilWaiting(scheduler, 1);
    a1.release();
    Grant b1 = granted(b);
    CompletableFuture<Grant> a2 = waiting(a, NEVER);
    untilWaiting(scheduler, 1);
    b1.release();
    Grant a2granted = granted(a2);
    CompletableFuture<Grant> d = waiting(scheduler.enter(share("d", "t", "m")), NEVER);
    untilWaiting(scheduler, 1);
    CompletableFuture<Grant> a3 = waiting(a, NEVER);
    untilWaiting(scheduler, 2);
    a2granted.release();
    Grant d1 = granted(d);
    assertFalse(a3.isDone(), "a run past its quantum was served ahead of an earlier waiter");
    d1.release();
    granted(a3);
  }

  /** Q = 1, so every wait is a fresh one — and a run alone still never waits for nobody. */
  @Test
  void a_run_alone_keeps_going_past_its_quantum() {
    SwarmScheduler scheduler = scheduler(pools("spark", 1), 1);
    Turns a = scheduler.enter(share("a", "t", "m"));
    assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () -> {
          for (int step = 0; step < 5; step++) {
            a.await("m", NEVER).release();
          }
        });
  }

  /**
   * The quantum is asked at every wait, so an operator's change takes effect at the next step
   * boundary: Q starts at 1 (A's second wait would be fresh) and is raised to 3.
   */
  @Test
  void the_quantum_is_read_at_every_wait() throws Exception {
    AtomicInteger quantum = new AtomicInteger(1);
    SwarmScheduler scheduler =
        new SwarmScheduler(
            pools("spark", 1), quantum::get, () -> Duration.ofMinutes(10), new Ticking(), POLL);
    Turns a = scheduler.enter(share("a", "t", "m"));
    Grant a1 = a.await("m", NEVER);
    CompletableFuture<Grant> b = waiting(scheduler.enter(share("b", "t", "m")), NEVER);
    untilWaiting(scheduler, 1);
    a1.release();
    Grant b1 = granted(b);
    CompletableFuture<Grant> c = waiting(scheduler.enter(share("c", "t", "m")), NEVER);
    untilWaiting(scheduler, 1);
    quantum.set(3);
    CompletableFuture<Grant> a2 = waiting(a, NEVER);
    untilWaiting(scheduler, 2);
    b1.release();
    granted(a2);
    assertFalse(c.isDone());
  }

  @Test
  void the_snapshot_lists_a_run_inside_its_quantum_first() throws Exception {
    SwarmScheduler scheduler = scheduler(pools("spark", 1), 3);
    Turns a = scheduler.enter(share("a", "t", "m"));
    Grant a1 = a.await("m", NEVER);
    CompletableFuture<Grant> b = waiting(scheduler.enter(share("b", "t", "m")), NEVER);
    untilWaiting(scheduler, 1);
    a1.release();
    Grant b1 = granted(b);
    waiting(scheduler.enter(share("c", "t", "m")), NEVER);
    untilWaiting(scheduler, 1);
    waiting(a, NEVER);
    untilWaiting(scheduler, 2);
    List<SwarmScheduler.Waiting> ready = scheduler.snapshot().ready();
    assertEquals(share("a", "t", "m"), ready.get(0).share());
    assertEquals(share("c", "t", "m"), ready.get(1).share());
    b1.release();
  }

  /**
   * Two pools, X and Y, with no candidate in common ("x" serves only X, "y" only Y). a and b queue
   * for X while it is held; c queues for Y while it is held. Releasing X serves a (the only account
   * with a placeable waiter). d then joins the X queue. Releasing Y serves c — a dispatch on a pool
   * a's and b's rotation has no stake in, which must not disturb b's or d's place in the rotation
   * for X. Releasing a's grant must then serve d, not b: b and d both sit in the same rotation
   * ring, and d's ring position (right after c, where the ring's pointer already stood) is reached
   * before the ring wraps back around to b — exactly the position the ring would occupy had b's and
   * d's waits never been interrupted by c's unrelated dispatch on Y. Before the fix, dispatching c
   * on Y wiped b (and every other account not placeable on Y that moment) out of the ring; b was
   * then re-added, on arrival of d, in front of d — handing b a turn on X it hadn't earned yet and
   * jumping d's place.
   */
  @Test
  void an_account_stays_in_the_rotation_while_its_pool_is_full_and_another_pool_dispatches()
      throws Exception {
    Map<String, Integer> slots = new LinkedHashMap<>();
    slots.put("X", 1);
    slots.put("Y", 1);
    Map<String, List<String>> serving = Map.of("x", List.of("X"), "y", List.of("Y"));
    RoutedPools pools = new RoutedPools(slots, serving);
    SwarmScheduler scheduler =
        new SwarmScheduler(pools, () -> 4, () -> Duration.ofMinutes(10), new Ticking(), POLL);

    Grant xHolder = scheduler.enter(share("hx", "t", "m")).await("x", NEVER);
    Grant yHolder = scheduler.enter(share("hy", "t", "m")).await("y", NEVER);

    CompletableFuture<Grant> a =
        CompletableFuture.supplyAsync(
            () -> scheduler.enter(share("a", "t", "m1")).await("x", NEVER), threads);
    untilWaiting(scheduler, 1);
    CompletableFuture<Grant> b =
        CompletableFuture.supplyAsync(
            () -> scheduler.enter(share("b", "t", "m1")).await("x", NEVER), threads);
    untilWaiting(scheduler, 2);
    CompletableFuture<Grant> c =
        CompletableFuture.supplyAsync(
            () -> scheduler.enter(share("c", "t", "m1")).await("y", NEVER), threads);
    untilWaiting(scheduler, 3);

    xHolder.release();
    Grant aGrant = granted(a);

    CompletableFuture<Grant> d =
        CompletableFuture.supplyAsync(
            () -> scheduler.enter(share("d", "t", "m1")).await("x", NEVER), threads);
    untilWaiting(scheduler, 3); // b, c, d

    yHolder.release();
    Grant cGrant = granted(c);

    aGrant.release();
    Grant dGrant = granted(d);
    assertFalse(b.isDone(), "b was served on X ahead of d, though d held the ring position");

    dGrant.release();
    Grant bGrant = granted(b);

    cGrant.release();
    bGrant.release();
  }
}
