package io.aeyer.plowshare.server.hooks.script;

import io.aeyer.plowshare.server.hooks.Stage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The contexts one hook runs in.
 *
 * <h2>Why a pool, and why it grows only when it has to</h2>
 *
 * <p>A GraalJS context is single-threaded and concurrent turns fire the same hook, so one context
 * would queue every run behind the slowest call. Contexts on a shared engine are cheap (1–3 ms,
 * spec §7.1), so a call that finds every context busy builds another rather than waiting — up to a
 * cap, past which it queues. Idle contexts shrink back to one.
 *
 * <h2>The limit includes the wait</h2>
 *
 * <p>A turn is bounded by the hook's time limit, not by the time limit plus however long the queue
 * was. A call that cannot get a context in time fails, and the stage's failure policy applies.
 *
 * <h2>A runaway costs one context</h2>
 *
 * <p>GraalJS stops a runaway only by closing its context. The timer does that at the deadline, the
 * call fails, and the pool forgets that context; the others keep serving and a replacement is built
 * lazily.
 *
 * <p><b>Module state is a cache, not a record</b>: each context has its own, and a timeout, a
 * retirement or a restart discards it.
 */
public final class ContextPool implements AutoCloseable {

  @FunctionalInterface
  public interface Loader {
    PooledHook load() throws HookFailure;
  }

  private record Idle(PooledHook hook, Instant since) {}

  private final String hookName;
  private final Loader loader;
  private final int max;
  private final Duration idle;
  private final Supplier<Instant> clock;
  private final ScheduledExecutorService timer;
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition returned = lock.newCondition();
  private final Deque<Idle> waiting = new ArrayDeque<>();
  private int live;
  private boolean retired;

  public ContextPool(
      String hookName,
      Loader loader,
      int max,
      Duration idle,
      Supplier<Instant> clock,
      ScheduledExecutorService timer) {
    this(hookName, null, loader, max, idle, clock, timer);
  }

  /** Seeds the pool with the context the descriptor was already read from. */
  public ContextPool(
      String hookName,
      PooledHook first,
      Loader loader,
      int max,
      Duration idle,
      Supplier<Instant> clock,
      ScheduledExecutorService timer) {
    this.hookName = Objects.requireNonNull(hookName, "hookName");
    this.loader = Objects.requireNonNull(loader, "loader");
    if (max < 1) {
      throw new IllegalArgumentException("a pool needs room for at least one context");
    }
    this.max = max;
    this.idle = Objects.requireNonNull(idle, "idle");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.timer = Objects.requireNonNull(timer, "timer");
    if (first != null) {
      waiting.push(new Idle(first, clock.get()));
      live = 1;
    }
  }

  /**
   * Runs {@code stage} on a pooled context; {@code limit} bounds the wait for one plus the call
   * itself.
   */
  public String call(Stage stage, String eventJson, Duration limit) throws HookFailure {
    return call(stage, eventJson, limit, null);
  }

  /**
   * The same, its time-limit failures naming {@code limitNamed} rather than "its time limit of N
   * ms". A call on a stage's shared deadline is given only what is left of that deadline (spec
   * 2026-09-28-hooks-reach-the-log decision 4, amended 2026-09-29), and a record naming the
   * leftover — "497 ms" — would name a limit nobody configured.
   *
   * <p><b>Here a call the timer won is worded by the pool</b>, whatever the stopped context threw,
   * so every hook stopped on a shared deadline reads the same. The overload without a name keeps
   * the context's own words, as it always has.
   *
   * @param limitNamed e.g. "the time left of fold.post's shared 2000 ms limit"; {@code null} for
   *     the per-call wording
   */
  public String call(Stage stage, String eventJson, Duration limit, String limitNamed)
      throws HookFailure {
    String passed =
        limitNamed != null ? limitNamed : "its time limit of " + limit.toMillis() + " ms";
    String within = limitNamed != null ? limitNamed : "its limit of " + limit.toMillis() + " ms";
    long deadline = System.nanoTime() + limit.toNanos();
    PooledHook hook = borrow(deadline, within);
    // `settled` is the one compare-and-set that decides who wins: this call
    // claiming its own success, or the timer's runnable claiming the
    // context first. `stop.cancel(false)`'s own return value cannot be
    // trusted for that: a ScheduledFuture stays in its NEW state for the
    // runnable's *entire* execution (Java's FutureTask only leaves NEW
    // once the runnable has fully returned), so calling cancel(false)
    // while hook::cancel is already running -- or has already finished
    // running -- can still return true, as if it had stopped a timer that
    // never fired. And a decision computed by hook.call() does not, on
    // its own, mean the call finished in time: the timer can fire in the
    // narrow window between hook.call() returning and this method
    // noticing, so the very same CAS that decides whether the context is
    // reusable also decides whether the call itself succeeds -- the
    // pool's contract is that a call the timer won FAILS, even when a
    // decision was computed, because a context mid-cancellation is not
    // one whose answer can be trusted.
    AtomicBoolean settled = new AtomicBoolean();
    ScheduledFuture<?> stop = null;
    boolean usable = false;
    try {
      long remaining = Math.max(1, deadline - System.nanoTime());
      // Scheduling happens inside the try so a timer that cannot accept
      // more work (e.g. a shut-down ScheduledExecutorService throwing
      // RejectedExecutionException) still reaches the finally below --
      // otherwise the borrowed hook would never be given back and
      // `live` would leak a slot forever.
      stop =
          timer.schedule(
              () -> {
                if (settled.compareAndSet(false, true)) {
                  hook.cancel();
                }
              },
              remaining,
              TimeUnit.NANOSECONDS);
      String decided;
      try {
        decided = hook.call(stage, eventJson);
      } catch (HookFailure stopped) {
        if (limitNamed != null && !settled.compareAndSet(false, true)) {
          throw new HookFailure("the hook '" + hookName + "' passed " + passed, stopped);
        }
        throw stopped;
      }
      if (!settled.compareAndSet(false, true)) {
        // The timer won: it may already have called hook.cancel(), or
        // be about to. Either way this is a timeout, whatever
        // `decided` holds -- it must not be returned as a success.
        throw new HookFailure("the hook '" + hookName + "' passed " + passed);
      }
      usable = true;
      return decided;
    } finally {
      if (stop != null) {
        stop.cancel(false);
      }
      giveBack(hook, usable && hook.isAlive());
    }
  }

  /** Contexts currently open, idle or in use. */
  public int live() {
    lock.lock();
    try {
      return live;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Closes idle contexts now; a context in use is closed when its call returns; later calls fail.
   */
  public void retire() {
    lock.lock();
    try {
      retired = true;
      while (!waiting.isEmpty()) {
        waiting.pop().hook().close();
        live--;
      }
      returned.signalAll();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void close() {
    retire();
  }

  private PooledHook borrow(long deadline, String within) throws HookFailure {
    lock.lock();
    try {
      while (true) {
        if (retired) {
          throw new HookFailure(
              "the hook '"
                  + hookName
                  + "' was reloaded while this call"
                  + " waited; it was not run");
        }
        if (!waiting.isEmpty()) {
          return waiting.pop().hook();
        }
        if (live < max) {
          live++;
          break;
        }
        long left = deadline - System.nanoTime();
        if (left <= 0) {
          throw new HookFailure(
              "the hook '"
                  + hookName
                  + "' had no free context within "
                  + within
                  + "; "
                  + max
                  + " calls were already running it");
        }
        try {
          returned.awaitNanos(left);
        } catch (InterruptedException stopped) {
          Thread.currentThread().interrupt();
          throw new HookFailure("interrupted while waiting for the hook '" + hookName + "'");
        }
      }
    } finally {
      lock.unlock();
    }
    // `loaded` guards the rollback below so it applies to every way this can
    // go wrong, an Error included: a StackOverflowError (or any other
    // Error) out of a hand-written Loader must still free the slot it
    // claimed, even though it is rethrown unchanged rather than wrapped —
    // only a RuntimeException becomes a HookFailure, since HookFailure
    // itself is already the right shape to just rethrow.
    boolean loaded = false;
    try {
      PooledHook fresh = loader.load();
      loaded = true;
      return fresh;
    } catch (HookFailure failed) {
      throw failed;
    } catch (RuntimeException failed) {
      throw new HookFailure(
          "the hook '" + hookName + "' could not be loaded: " + failed.getMessage(), failed);
    } finally {
      if (!loaded) {
        lock.lock();
        try {
          live--;
          returned.signal();
        } finally {
          lock.unlock();
        }
      }
    }
  }

  private void giveBack(PooledHook hook, boolean usable) {
    lock.lock();
    try {
      if (!usable || retired) {
        hook.close();
        live--;
      } else {
        Instant now = clock.get();
        waiting.push(new Idle(hook, now));
        shrink(now);
      }
      returned.signal();
    } finally {
      lock.unlock();
    }
  }

  /** Closes contexts idle longer than {@code idle}, keeping the most recently used one. */
  private void shrink(Instant now) {
    Iterator<Idle> oldestFirst = waiting.descendingIterator();
    while (live > 1 && oldestFirst.hasNext()) {
      Idle candidate = oldestFirst.next();
      if (candidate.hook() == waiting.peekFirst().hook()) {
        break;
      }
      if (Duration.between(candidate.since(), now).compareTo(idle) > 0) {
        candidate.hook().close();
        oldestFirst.remove();
        live--;
      }
    }
  }
}
