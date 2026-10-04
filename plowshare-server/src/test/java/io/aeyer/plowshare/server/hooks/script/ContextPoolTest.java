package io.aeyer.plowshare.server.hooks.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.hooks.Stage;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ContextPoolTest {

  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
  private final ExecutorService callers = Executors.newFixedThreadPool(4);

  @AfterEach
  void stop() {
    timer.shutdownNow();
    callers.shutdownNow();
  }

  /** A hook that blocks until released, and can be cancelled like a real one. */
  private static final class Gate implements PooledHook {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release;
    volatile boolean alive = true;
    volatile boolean closed;

    Gate(CountDownLatch release) {
      this.release = release;
    }

    @Override
    public String call(Stage stage, String eventJson) throws HookFailure {
      entered.countDown();
      try {
        while (!release.await(10, TimeUnit.MILLISECONDS)) {
          if (!alive) {
            throw new HookFailure("stopped at its time limit");
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new HookFailure("interrupted");
      }
      return "null";
    }

    @Override
    public void cancel() {
      alive = false;
    }

    @Override
    public boolean isAlive() {
      return alive;
    }

    @Override
    public void close() {
      alive = false;
      closed = true;
    }
  }

  @Test
  void one_context_is_reused_for_calls_that_do_not_overlap() throws Exception {
    AtomicInteger built = new AtomicInteger();
    CountDownLatch open = new CountDownLatch(0);
    ContextPool pool =
        new ContextPool(
            "h",
            () -> {
              built.incrementAndGet();
              return new Gate(open);
            },
            4,
            Duration.ofMinutes(5),
            Instant::now,
            timer);

    for (int i = 0; i < 5; i++) {
      pool.call(Stage.PROMPT_PRE, "{}", Duration.ofSeconds(1));
    }

    assertEquals(1, built.get());
    assertEquals(1, pool.live());
  }

  @Test
  void overlapping_calls_grow_the_pool_up_to_its_cap_and_no_further() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    List<Gate> gates = new CopyOnWriteArrayList<>();
    ContextPool pool =
        new ContextPool(
            "h",
            () -> {
              Gate gate = new Gate(release);
              gates.add(gate);
              return gate;
            },
            2,
            Duration.ofMinutes(5),
            Instant::now,
            timer);

    Future<String> first =
        callers.submit(() -> pool.call(Stage.TOOL_PRE, "{}", Duration.ofSeconds(5)));
    Future<String> second =
        callers.submit(() -> pool.call(Stage.TOOL_PRE, "{}", Duration.ofSeconds(5)));
    Future<String> third =
        callers.submit(() -> pool.call(Stage.TOOL_PRE, "{}", Duration.ofSeconds(5)));
    for (int waited = 0; gates.size() < 2 && waited < 200; waited++) {
      Thread.sleep(5);
    }
    Thread.sleep(50);

    assertEquals(2, gates.size(), "the third call queued instead of building a third context");
    assertEquals(2, pool.live());
    release.countDown();
    assertEquals("null", first.get(5, TimeUnit.SECONDS));
    assertEquals("null", second.get(5, TimeUnit.SECONDS));
    assertEquals("null", third.get(5, TimeUnit.SECONDS));
  }

  @Test
  void waiting_counts_toward_the_limit() throws Exception {
    CountDownLatch never = new CountDownLatch(1);
    ContextPool pool =
        new ContextPool("h", () -> new Gate(never), 1, Duration.ofMinutes(5), Instant::now, timer);
    callers.submit(() -> pool.call(Stage.TOOL_PRE, "{}", Duration.ofSeconds(10)));
    Thread.sleep(50);

    long started = System.nanoTime();
    HookFailure waited =
        assertThrows(
            HookFailure.class, () -> pool.call(Stage.TOOL_PRE, "{}", Duration.ofMillis(100)));
    long elapsed = System.nanoTime() - started;

    assertTrue(waited.getMessage().contains("no free context"), waited.getMessage());
    // Well under the other call's 10 s limit: this call's own 100 ms
    // limit governed the wait, not whatever was left of someone else's.
    assertTrue(
        elapsed < TimeUnit.SECONDS.toNanos(2),
        "took " + TimeUnit.NANOSECONDS.toMillis(elapsed) + " ms");
    never.countDown();
  }

  @Test
  void a_timeout_destroys_that_context_only_and_the_pool_builds_another() throws Exception {
    CountDownLatch never = new CountDownLatch(1);
    CountDownLatch open = new CountDownLatch(0);
    AtomicInteger built = new AtomicInteger();
    ContextPool pool =
        new ContextPool(
            "h",
            () -> built.incrementAndGet() == 1 ? new Gate(never) : new Gate(open),
            2,
            Duration.ofMinutes(5),
            Instant::now,
            timer);

    assertThrows(HookFailure.class, () -> pool.call(Stage.TOOL_PRE, "{}", Duration.ofMillis(100)));
    assertEquals(0, pool.live(), "the runaway's context is gone");

    assertEquals("null", pool.call(Stage.TOOL_PRE, "{}", Duration.ofSeconds(1)));
    assertEquals(2, built.get());
    assertEquals(1, pool.live());
  }

  /**
   * A call on a shared deadline names the shared limit, not the leftover it was handed (spec
   * 2026-09-28-hooks-reach-the-log decision 4, amended 2026-09-29); a call without a name keeps the
   * stopped context's own words, as every other stage always has.
   */
  @Test
  void a_named_limit_words_the_timeout_and_an_unnamed_one_keeps_the_context_s_words()
      throws Exception {
    CountDownLatch never = new CountDownLatch(1);
    ContextPool pool =
        new ContextPool("h", () -> new Gate(never), 2, Duration.ofMinutes(5), Instant::now, timer);

    HookFailure named =
        assertThrows(
            HookFailure.class,
            () ->
                pool.call(
                    Stage.FOLD_POST,
                    "{}",
                    Duration.ofMillis(97),
                    "the time left of fold.post's shared 500 ms limit"));
    HookFailure unnamed =
        assertThrows(
            HookFailure.class, () -> pool.call(Stage.TOOL_PRE, "{}", Duration.ofMillis(97)));

    assertEquals(
        "the hook 'h' passed the time left of fold.post's shared 500 ms limit", named.getMessage());
    assertEquals("stopped at its time limit", unnamed.getMessage());
  }

  @Test
  void idle_contexts_shrink_back_to_one() throws Exception {
    AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-13T10:00:00Z"));
    CountDownLatch release = new CountDownLatch(1);
    ContextPool pool =
        new ContextPool("h", () -> new Gate(release), 3, Duration.ofMinutes(5), now::get, timer);
    Future<String> a = callers.submit(() -> pool.call(Stage.TOOL_PRE, "{}", Duration.ofSeconds(5)));
    Future<String> b = callers.submit(() -> pool.call(Stage.TOOL_PRE, "{}", Duration.ofSeconds(5)));
    Future<String> c = callers.submit(() -> pool.call(Stage.TOOL_PRE, "{}", Duration.ofSeconds(5)));
    for (int waited = 0; pool.live() < 3 && waited < 200; waited++) {
      Thread.sleep(5);
    }
    release.countDown();
    a.get(5, TimeUnit.SECONDS);
    b.get(5, TimeUnit.SECONDS);
    c.get(5, TimeUnit.SECONDS);
    assertEquals(3, pool.live());

    now.set(now.get().plus(Duration.ofMinutes(6)));
    pool.call(Stage.TOOL_PRE, "{}", Duration.ofSeconds(1));

    assertEquals(1, pool.live());
  }

  @Test
  void a_retired_pool_closes_what_it_holds_and_refuses_new_calls() throws Exception {
    List<Gate> gates = new CopyOnWriteArrayList<>();
    CountDownLatch open = new CountDownLatch(0);
    ContextPool pool =
        new ContextPool(
            "h",
            () -> {
              Gate gate = new Gate(open);
              gates.add(gate);
              return gate;
            },
            2,
            Duration.ofMinutes(5),
            Instant::now,
            timer);
    pool.call(Stage.TOOL_PRE, "{}", Duration.ofSeconds(1));

    pool.retire();

    assertTrue(gates.get(0).closed);
    assertEquals(0, pool.live());
    assertThrows(HookFailure.class, () -> pool.call(Stage.TOOL_PRE, "{}", Duration.ofSeconds(1)));
  }

  @Test
  void a_loader_that_throws_leaves_live_unchanged_and_a_later_call_works() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    CountDownLatch open = new CountDownLatch(0);
    ContextPool pool =
        new ContextPool(
            "h",
            () -> {
              if (attempts.incrementAndGet() == 1) {
                throw new HookFailure("could not load");
              }
              return new Gate(open);
            },
            2,
            Duration.ofMinutes(5),
            Instant::now,
            timer);

    assertThrows(HookFailure.class, () -> pool.call(Stage.TOOL_PRE, "{}", Duration.ofSeconds(1)));
    assertEquals(0, pool.live(), "the failed load claimed no slot");

    assertEquals("null", pool.call(Stage.TOOL_PRE, "{}", Duration.ofSeconds(1)));
    assertEquals(2, attempts.get());
    assertEquals(1, pool.live());
  }

  @Test
  void retiring_while_a_call_is_in_flight_closes_it_when_the_call_returns() throws Exception {
    List<Gate> gates = new CopyOnWriteArrayList<>();
    CountDownLatch release = new CountDownLatch(1);
    ContextPool pool =
        new ContextPool(
            "h",
            () -> {
              Gate gate = new Gate(release);
              gates.add(gate);
              return gate;
            },
            2,
            Duration.ofMinutes(5),
            Instant::now,
            timer);

    Future<String> inFlight =
        callers.submit(() -> pool.call(Stage.TOOL_PRE, "{}", Duration.ofSeconds(5)));
    for (int waited = 0; gates.isEmpty() && waited < 200; waited++) {
      Thread.sleep(5);
    }
    assertEquals(1, gates.size());
    assertTrue(gates.get(0).entered.await(5, TimeUnit.SECONDS), "the call is actually running");

    pool.retire();
    assertEquals(
        1, pool.live(), "the in-flight context is still open; retire only drains idle ones");
    assertFalse(gates.get(0).closed, "a call in flight is not interrupted by retire");

    release.countDown();
    assertEquals("null", inFlight.get(5, TimeUnit.SECONDS));

    assertTrue(
        gates.get(0).closed,
        "it is closed once returned, because the pool was retired underneath it");
    assertEquals(0, pool.live());
    assertThrows(HookFailure.class, () -> pool.call(Stage.TOOL_PRE, "{}", Duration.ofSeconds(1)));
  }

  @Test
  void a_timer_that_cannot_be_scheduled_still_returns_the_borrowed_context() throws Exception {
    ScheduledExecutorService broken = Executors.newSingleThreadScheduledExecutor();
    broken.shutdownNow();
    CountDownLatch open = new CountDownLatch(0);
    ContextPool pool =
        new ContextPool("h", () -> new Gate(open), 1, Duration.ofMinutes(5), Instant::now, broken);

    assertThrows(
        RejectedExecutionException.class,
        () -> pool.call(Stage.TOOL_PRE, "{}", Duration.ofSeconds(1)));

    assertEquals(0, pool.live(), "the borrowed context was not leaked when scheduling failed");
  }

  /**
   * A {@link ScheduledExecutorService} whose {@code schedule} does not actually hand the runnable
   * to a timer thread: it captures it and hands back a {@link ScheduledFuture} whose {@code cancel}
   * unconditionally reports success, exactly as a real one legitimately can while its runnable is
   * already running (a {@code FutureTask} stays in its {@code NEW} state for the runnable's whole
   * execution, so {@code cancel(false)} racing with it can return {@code true} even though the
   * runnable ran anyway). The test decides exactly when "the timer fires" by calling {@link
   * #fireNow()} itself, instead of hoping a real timer thread wins a real race.
   */
  private static final class ManualTimer implements ScheduledExecutorService {
    private Runnable captured;

    void fireNow() {
      captured.run();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      captured = command;
      return new ScheduledFuture<Void>() {
        @Override
        public long getDelay(TimeUnit unit) {
          return 0;
        }

        @Override
        public int compareTo(Delayed o) {
          return 0;
        }

        // The lie at the heart of the race this fixes: reporting
        // success here does not mean the runnable never ran.
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
          return true;
        }

        @Override
        public boolean isCancelled() {
          return false;
        }

        @Override
        public boolean isDone() {
          return false;
        }

        @Override
        public Void get() {
          return null;
        }

        @Override
        public Void get(long timeout, TimeUnit unit) {
          return null;
        }
      };
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command, long initialDelay, long period, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command, long initialDelay, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void shutdown() {}

    @Override
    public List<Runnable> shutdownNow() {
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Future<?> submit(Runnable task) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> List<Future<T>> invokeAll(
        Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void execute(Runnable command) {
      throw new UnsupportedOperationException();
    }
  }

  @Test
  void a_timer_that_fires_during_the_call_discards_the_context_even_if_cancel_lies()
      throws Exception {
    ManualTimer manual = new ManualTimer();
    AtomicBoolean cancelled = new AtomicBoolean();
    // A stale read of isAlive() -- as a genuinely racing read could see --
    // must not be enough on its own to keep a context that the timer
    // already claimed, and a decision the hook DID manage to compute must
    // not be enough either: the pool's contract is that a call the timer
    // won fails, full stop. Hard-coding isAlive() to `true` regardless of
    // `cancel()` stands in for a stale read deterministically, and the
    // fake future's own cancel() (above) stands in for cancel(false)
    // lying about whether the runnable ran. If any of those were still
    // load bearing (as in the pre-fix code), this test would see the
    // stale "null" decision returned as a success instead of a
    // HookFailure, with the context still wrongly kept alive.
    PooledHook lying =
        new PooledHook() {
          @Override
          public String call(Stage stage, String eventJson) {
            // Simulate the timer firing while this call is still running:
            // exactly the race the settled-CAS fix closes.
            manual.fireNow();
            return "null";
          }

          @Override
          public void cancel() {
            cancelled.set(true);
          }

          @Override
          public boolean isAlive() {
            return true;
          }

          @Override
          public void close() {}
        };
    ContextPool pool =
        new ContextPool("h", () -> lying, 1, Duration.ofMinutes(5), Instant::now, manual);

    assertThrows(
        HookFailure.class,
        () -> pool.call(Stage.TOOL_PRE, "{}", Duration.ofSeconds(1)),
        "the timer won the settled race, so this call timed out even though a decision was computed");

    assertTrue(cancelled.get(), "the timer's runnable did fire, during the call");
    assertEquals(
        0,
        pool.live(),
        "settled -- not isAlive() or cancel()'s return value -- decided the outcome");
  }

  @Test
  void a_real_graaljs_runaway_is_stopped_at_the_limit_and_the_next_call_works() throws Exception {
    try (HookEngine engine = new HookEngine()) {
      // Module-level state (`let spun`) resets on every fresh context, so it
      // cannot mark "already ran once" across a runaway's destruction and a
      // replacement context's build — a real Context is a fresh Realm each
      // time. The spin is instead keyed off the event itself: only an
      // utterance of "a" runs away, so the second call's "b" proves a fresh
      // context, loaded and run for real, decides normally.
      String js =
          """
                    export default {
                        name: 'spin-once',
                        stages: { 'prompt.pre': { handle(event) { if (event.utterance === 'a') { while (true) {} } return undefined } } },
                    }
                    """;
      ContextPool pool =
          new ContextPool(
              "spin-once",
              () -> LoadedHook.load(engine, "spin.js", js),
              2,
              Duration.ofMinutes(5),
              Instant::now,
              timer);

      long started = System.nanoTime();
      assertThrows(
          HookFailure.class,
          () ->
              pool.call(
                  Stage.PROMPT_PRE,
                  "{\"context\":{},\"utterance\":\"a\"}",
                  Duration.ofMillis(200)));
      assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3));

      assertEquals(
          "null",
          pool.call(
              Stage.PROMPT_PRE, "{\"context\":{},\"utterance\":\"b\"}", Duration.ofSeconds(2)),
          "a fresh context is loaded and run for real, and does not spin");
      pool.retire();
    }
  }
}
