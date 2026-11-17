package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.ExternalConversationAdmission;
import io.aeyer.plowshare.server.agents.ExternalConversationUnavailableException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One timer and at most one notification per retained request slot. Timers only select and launch;
 * callbacks, serialization and network work never run on the shared alarm. A blocked callback or
 * route keeps its slot until it physically settles, including after a caller timeout.
 */
public final class BoundedExternalConversationCompletions
    implements ExternalConversationCompletions, AutoCloseable {
  private final Duration timeout;
  private final Semaphore slots;
  private final Set<Completion> active = ConcurrentHashMap.newKeySet();
  private final ScheduledThreadPoolExecutor alarms;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Object lifecycle = new Object();

  public BoundedExternalConversationCompletions(Duration timeout, int capacity) {
    this.timeout = Objects.requireNonNull(timeout);
    if (timeout.isZero() || timeout.isNegative() || capacity < 1)
      throw new IllegalArgumentException("Positive Ex completion timeout and capacity required");
    slots = new Semaphore(capacity);
    alarms =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().daemon().name("external-conversation-deadline-", 0).factory());
    alarms.setRemoveOnCancelPolicy(true);
    alarms.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
  }

  @Override
  public Request begin(long admittedAtNanos, Consumer<Outcome> notify) {
    Objects.requireNonNull(notify);
    synchronized (lifecycle) {
      if (closed.get() || !slots.tryAcquire())
        throw new ExternalConversationUnavailableException(
            "External request completion capacity is unavailable; nothing was started here", null);
      var completion = new Completion(admittedAtNanos + timeout.toNanos(), notify);
      active.add(completion);
      try {
        completion.alarm =
            alarms.schedule(
                completion::expire,
                Math.max(0L, completion.end - System.nanoTime()),
                TimeUnit.NANOSECONDS);
        return completion;
      } catch (RuntimeException unavailable) {
        active.remove(completion);
        slots.release();
        throw new ExternalConversationUnavailableException(
            "External request completion is unavailable", unavailable);
      }
    }
  }

  @Override
  public void close() {
    ArrayList<Completion> retained;
    synchronized (lifecycle) {
      if (!closed.compareAndSet(false, true)) return;
      alarms.shutdownNow();
      retained = new ArrayList<>(active);
    }
    // Do not free slots or cancel the original operation. Its existing physical owner settles it.
    retained.forEach(Completion::expire);
  }

  private final class Completion implements Request {
    private final Consumer<Outcome> notify;
    private final long end;
    private final AtomicBoolean selected = new AtomicBoolean();
    private final AtomicBoolean physical = new AtomicBoolean();
    private final AtomicBoolean notified = new AtomicBoolean();
    private final AtomicBoolean released = new AtomicBoolean();
    private volatile ScheduledFuture<?> alarm;

    private Completion(long end, Consumer<Outcome> notify) {
      this.end = end;
      this.notify = notify;
    }

    @Override
    public <T> T within(Supplier<T> action) {
      long remaining = end - System.nanoTime();
      if (remaining <= 0)
        throw new ExternalConversationUnavailableException(
            "External request expired before execution", null);
      return ExternalConversationAdmission.within(Duration.ofNanos(remaining), action);
    }

    @Override
    public void answer(Outcome outcome) {
      Objects.requireNonNull(outcome);
      if (System.nanoTime() - end >= 0) {
        expire();
      } else if (selected.compareAndSet(false, true)) {
        var pending = alarm;
        if (pending != null) pending.cancel(false);
        notify(outcome);
      }
    }

    private void expire() {
      if (!selected.compareAndSet(false, true)) return;
      // At most capacity such threads exist: a notification retains its original slot until both
      // this callback and its original physical request finish. No executor queue grows on expiry.
      Thread.ofVirtual()
          .name("external-conversation-timeout")
          .start(
              () ->
                  notify(
                      Outcome.failed(
                          Code.ARCHIVE_UNAVAILABLE,
                          "External request deadline expired; work may already have been admitted."
                              + " Inspect the original work and do not replay this mutation.")));
    }

    private void notify(Outcome outcome) {
      try {
        notify.accept(outcome);
      } finally {
        notified.set(true);
        release();
      }
    }

    @Override
    public void close() {
      physical.set(true);
      if (selected.compareAndSet(false, true)) {
        var pending = alarm;
        if (pending != null) pending.cancel(false);
        notified.set(true);
      }
      release();
    }

    private void release() {
      if (physical.get() && notified.get() && released.compareAndSet(false, true)) {
        active.remove(this);
        slots.release();
      }
    }
  }
}
