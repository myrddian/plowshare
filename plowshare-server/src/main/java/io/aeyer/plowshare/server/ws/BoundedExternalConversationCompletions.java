package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.reads.ReadFailedException;
import io.aeyer.plowshare.server.reads.ReadUnavailableException;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Bounded ownership of accepted Ex replies. Accepted work has no transport-created completion
 * deadline: SQL units and providers own their budgets, and slow requests are observed by the
 * router. A blocked reply callback retains its slot until both callback and physical route settle.
 */
public final class BoundedExternalConversationCompletions
    implements ExternalConversationCompletions, AutoCloseable {
  private final Semaphore slots;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Object lifecycle = new Object();

  public BoundedExternalConversationCompletions(int capacity) {
    if (capacity < 1)
      throw new IllegalArgumentException("Positive Ex completion capacity required");
    slots = new Semaphore(capacity);
  }

  @Override
  public Request begin(long admittedAtNanos, Consumer<Outcome> notify) {
    Objects.requireNonNull(notify);
    synchronized (lifecycle) {
      if (closed.get()) throw ReadFailedException.closed();
      // This is before dispatcher/handler admission. The transport alone may translate this
      // occupied-slot refusal into a no-start capacity response; no running work is interrupted.
      if (!slots.tryAcquire()) throw new ReadUnavailableException();
      return new Completion(notify);
    }
  }

  @Override
  public void close() {
    synchronized (lifecycle) {
      // Shutdown stops intake. Existing operations retain their physical owners and outcomes.
      closed.set(true);
    }
  }

  private final class Completion implements Request {
    private final Consumer<Outcome> notify;
    private final AtomicBoolean selected = new AtomicBoolean();
    private final AtomicBoolean physical = new AtomicBoolean();
    private final AtomicBoolean notified = new AtomicBoolean();
    private final AtomicBoolean released = new AtomicBoolean();

    private Completion(Consumer<Outcome> notify) {
      this.notify = notify;
    }

    @Override
    public <T> T within(Supplier<T> action) {
      // Retain the original execution thread without inventing a workflow/SQL deadline.
      return Objects.requireNonNull(action).get();
    }

    @Override
    public void answer(Outcome outcome) {
      Objects.requireNonNull(outcome);
      if (!selected.compareAndSet(false, true)) return;
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
      if (selected.compareAndSet(false, true)) notified.set(true);
      release();
    }

    private void release() {
      if (physical.get() && notified.get() && released.compareAndSet(false, true)) slots.release();
    }
  }
}
