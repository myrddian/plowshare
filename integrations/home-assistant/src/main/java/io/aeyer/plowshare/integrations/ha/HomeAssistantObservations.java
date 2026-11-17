package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.IntegrationAdapter.Observation;
import io.aeyer.plowshare.integrations.IntegrationContracts.Gap;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Ephemeral FIFO observer owner, not another durable runtime. Native callbacks only enqueue;
 * arbitrary observer/journal work runs on one separate thread. A queued Gap has a reserved slot,
 * and reconnect waits until old-generation delivery settles. An uncertain observer is never invoked
 * again. Its physical slot remains occupied until the callback actually returns.
 */
final class HomeAssistantObservations implements AutoCloseable {
  private record Delivery(Observation observation, CompletableFuture<Void> acknowledged) {
    boolean gap() {
      return observation.data().event() instanceof Gap;
    }
  }

  private static final int MAX_PENDING = 256;
  private final Object lock = new Object();
  private final ArrayDeque<Delivery> pending = new ArrayDeque<>();
  private final Consumer<Observation> observer;
  private final Consumer<Observation> unavailable;
  private final long callbackNanos;
  private final long cleanupNanos;
  private final Thread worker;
  private final ScheduledThreadPoolExecutor deadlines;
  private Delivery active;
  private Delivery unconfirmed;
  private boolean stopped;
  private boolean closing;
  private boolean stalled;
  private int pendingGaps;

  HomeAssistantObservations(
      Consumer<Observation> observer,
      Consumer<Observation> unavailable,
      Duration callbackBudget,
      Duration cleanupBudget) {
    this.observer = Objects.requireNonNull(observer);
    this.unavailable = Objects.requireNonNull(unavailable);
    callbackNanos = checked(callbackBudget).toNanos();
    cleanupNanos = checked(cleanupBudget).toNanos();
    ThreadFactory threads =
        task -> {
          Thread thread = new Thread(task, "plowshare-ha-observer");
          thread.setDaemon(true);
          return thread;
        };
    deadlines = new ScheduledThreadPoolExecutor(1, threads);
    deadlines.setRemoveOnCancelPolicy(true);
    worker = threads.newThread(this::run);
    worker.start();
  }

  /** Nonblocking admission; one extra slot is reserved for the loss-of-continuity Gap. */
  CompletableFuture<Void> offer(Observation observation) throws IOException {
    Delivery delivery = new Delivery(observation, new CompletableFuture<>());
    synchronized (lock) {
      if (stopped || closing && !delivery.gap())
        throw new IOException("HA observation owner closing or closed");
      if (delivery.gap()) {
        if (pendingGaps != 0) throw new IOException("HA connection Gap already pending");
        pendingGaps++;
      } else if (unconfirmed != null || stalled || pending.size() >= MAX_PENDING) {
        throw new IOException("HA observation intake unavailable; continuity must be fenced");
      }
      pending.addLast(delivery);
      lock.notifyAll();
    }
    return delivery.acknowledged();
  }

  boolean readyForConnection() {
    synchronized (lock) {
      return !stopped && !stalled && unconfirmed == null && active == null && pending.isEmpty();
    }
  }

  void check() throws IOException {
    synchronized (lock) {
      if (unconfirmed != null)
        throw new IOException("HA observation delivery unconfirmed; reconciliation required");
      if (stalled)
        throw new IOException("HA observation callback overdue; physical work still retained");
    }
  }

  static void await(CompletableFuture<Void> acknowledged, OperationBudget budget)
      throws IOException {
    try {
      acknowledged.get(budget.remaining().toNanos(), TimeUnit.NANOSECONDS);
      budget.remaining();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("HA observation acknowledgment interrupted");
    } catch (ExecutionException failed) {
      throw new IOException("HA observation acknowledgment unconfirmed");
    } catch (TimeoutException expired) {
      throw new IOException("HA observation acknowledgment deadline expired");
    }
  }

  private void run() {
    while (true) {
      Delivery delivery;
      synchronized (lock) {
        while (pending.isEmpty() && !stopped && !closing) {
          try {
            lock.wait();
          } catch (InterruptedException interrupted) {
            if (stopped) return;
            // Expiry interrupts only the active callback. Clear its own interrupt when it has
            // physically returned so the retained FIFO/Gap can continue without replaying it.
          }
        }
        if (stopped || unconfirmed != null) return;
        if (pending.isEmpty() && closing) {
          stopped = true;
          lock.notifyAll();
          deadlines.shutdownNow();
          return;
        }
        delivery = pending.removeFirst();
        active = delivery;
      }
      ScheduledFuture<?> deadline;
      try {
        deadline = deadlines.schedule(() -> expire(delivery), callbackNanos, TimeUnit.NANOSECONDS);
      } catch (RejectedExecutionException closed) {
        synchronized (lock) {
          // Final close can win after removal but before invocation. Retain this never-invoked
          // original identity (including a Gap); do not lose it or dispatch it after close.
          pending.addFirst(delivery);
          active = null;
          lock.notifyAll();
        }
        delivery
            .acknowledged()
            .completeExceptionally(new IOException("HA observation owner closed"));
        return;
      }
      boolean succeeded = false;
      try {
        observer.accept(delivery.observation());
        succeeded = true;
      } catch (RuntimeException failed) {
        // Observer failure can follow a journal commit. Keep the original identity and do not
        // retry it or later callbacks around an uncertain position in the observation sequence.
      } finally {
        deadline.cancel(false);
        synchronized (lock) {
          active = null;
          if (succeeded) {
            if (delivery.gap()) pendingGaps--;
            if (delivery.gap() && pendingGaps == 0 && pending.isEmpty()) stalled = false;
          } else {
            unconfirmed = delivery;
            stalled = true;
          }
          lock.notifyAll();
        }
      }
      if (succeeded) delivery.acknowledged().complete(null);
      else {
        delivery
            .acknowledged()
            .completeExceptionally(new IOException("HA observation delivery unconfirmed"));
        unavailable.accept(delivery.observation());
        return;
      }
      // An observer may preserve interruption after its late normal return. No external caller's
      // thread flag is touched; only this dedicated owner thread clears its own expiry signal.
      Thread.interrupted();
    }
  }

  private void expire(Delivery delivery) {
    synchronized (lock) {
      if (stopped || active != delivery) return;
      stalled = true;
    }
    delivery
        .acknowledged()
        .completeExceptionally(new IOException("HA observation callback deadline expired"));
    unavailable.accept(delivery.observation());
    synchronized (lock) {
      // The callback can return while fencing its connection. Interrupt only that retained
      // physical delivery, never a subsequent callback/generation picked up in the meantime.
      if (active == delivery) worker.interrupt();
    }
  }

  @Override
  public void close() {
    long deadline = System.nanoTime() + cleanupNanos;
    boolean interrupted = false;
    synchronized (lock) {
      closing = true;
      lock.notifyAll();
      if (Thread.currentThread() == worker) return;
      // Drain already accepted observations and the reserved Gap without extending cleanup.
      while ((active != null || !pending.isEmpty()) && unconfirmed == null) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) break;
        try {
          TimeUnit.NANOSECONDS.timedWait(lock, remaining);
        } catch (InterruptedException cancelled) {
          interrupted = true;
          break;
        }
      }
      stopped = true;
      IOException closed = new IOException("HA observation owner closed; delivery unconfirmed");
      pending.forEach(delivery -> delivery.acknowledged().completeExceptionally(closed));
      if (active != null) active.acknowledged().completeExceptionally(closed);
      lock.notifyAll();
    }
    deadlines.shutdownNow();
    worker.interrupt();
    long remaining = deadline - System.nanoTime();
    if (remaining > 0) {
      try {
        TimeUnit.NANOSECONDS.timedJoin(worker, remaining);
      } catch (InterruptedException cancelled) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
    synchronized (lock) {
      if (active != null || !pending.isEmpty() || unconfirmed != null)
        System.getLogger(HomeAssistantObservations.class.getName())
            .log(
                System.Logger.Level.WARNING,
                "HA observation cleanup incomplete; retained identities require reconciliation before replacing the journal owner");
    }
    // An uncooperative callback may outlive cleanup. Its daemon thread still owns active and its
    // original delivery; no capacity is falsely reused and the callback is never submitted again.
  }

  private static Duration checked(Duration budget) {
    Objects.requireNonNull(budget);
    if (budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofDays(1)) > 0)
      throw new IllegalArgumentException("positive finite HA observation budget required");
    return budget;
  }
}
