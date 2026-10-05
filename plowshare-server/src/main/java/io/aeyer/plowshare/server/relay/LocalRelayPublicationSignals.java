package io.aeyer.plowshare.server.relay;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Each group has an independent, coalesced wakeup; no central consumer or publication queue. */
public final class LocalRelayPublicationSignals implements RelayPublicationSignals {
  private final ConcurrentHashMap<Relay.TopicKey, CopyOnWriteArrayList<Listener>> listeners =
      new ConcurrentHashMap<>();

  @Override
  public void published(Relay.TopicKey topic) {
    var current = listeners.get(Objects.requireNonNull(topic));
    if (current != null) current.forEach(Listener::signal);
  }

  @Override
  public Waiting listen(Relay.SubscriptionKey subscription) {
    var listener = new Listener(Objects.requireNonNull(subscription).topic());
    listeners.compute(
        listener.topic,
        (key, current) -> {
          if (current == null) current = new CopyOnWriteArrayList<>();
          current.add(listener);
          return current;
        });
    return listener;
  }

  private final class Listener implements Waiting {
    private final Relay.TopicKey topic;
    private final Semaphore wakeup = new Semaphore(0);
    private boolean closed;

    private Listener(Relay.TopicKey topic) {
      this.topic = topic;
    }

    // Coalesce under a short lock; waiting never holds it or pins a carrier thread.
    private synchronized void signal() {
      if (!closed && wakeup.availablePermits() == 0) wakeup.release();
    }

    @Override
    public void await(Duration maximum) throws InterruptedException {
      Objects.requireNonNull(maximum);
      if (maximum.isNegative() || maximum.isZero())
        throw new IllegalArgumentException("wait must be positive");
      wakeup.tryAcquire(maximum.toNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public void close() {
      synchronized (this) {
        if (closed) return;
        closed = true;
        wakeup.release();
      }
      listeners.computeIfPresent(
          topic,
          (key, current) -> {
            current.remove(this);
            return current.isEmpty() ? null : current;
          });
    }
  }
}
