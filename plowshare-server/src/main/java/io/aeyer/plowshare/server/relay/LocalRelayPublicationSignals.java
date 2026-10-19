package io.aeyer.plowshare.server.relay;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded, independently coalesced waiters; registrations precede each owner's first read. */
public final class LocalRelayPublicationSignals implements RelayPublicationSignals {
  private final ConcurrentMap<Relay.TopicKey, Set<Listener>> topics = new ConcurrentHashMap<>();
  private final Set<Listener> all = ConcurrentHashMap.newKeySet();
  private final Set<Listener> discovery = ConcurrentHashMap.newKeySet();
  private final Object lifecycle = new Object();
  private final int maximum;
  private boolean closed;

  public LocalRelayPublicationSignals() {
    this(4096);
  }

  public LocalRelayPublicationSignals(int maximum) {
    if (maximum < 1 || maximum > 65536)
      throw new IllegalArgumentException("Invalid Relay waiter bound");
    this.maximum = maximum;
  }

  @Override
  public void published(Relay.TopicKey topic) {
    var current = topics.get(Objects.requireNonNull(topic));
    if (current != null) current.forEach(Listener::signal);
    discovery.forEach(Listener::signal);
  }

  /**
   * Fixed-domain database hints deliberately reveal no topic identity; wake every bounded waiter.
   */
  public void wakeAll() {
    all.forEach(Listener::signal);
  }

  @Override
  public Waiting listen(Relay.SubscriptionKey subscription) {
    return register(Objects.requireNonNull(subscription).topic());
  }

  @Override
  public Waiting discovery() {
    return register(null);
  }

  private Waiting register(Relay.TopicKey topic) {
    var listener = new Listener(topic);
    // Compound registration/removal must maintain one resource bound across all topic indexes.
    synchronized (lifecycle) {
      if (closed) throw new IllegalStateException("Relay signals are closed");
      if (all.size() >= maximum) throw new IllegalStateException("Relay waiter capacity reached");
      all.add(listener);
      if (topic == null) discovery.add(listener);
      else topics.computeIfAbsent(topic, ignored -> ConcurrentHashMap.newKeySet()).add(listener);
    }
    return listener;
  }

  @Override
  public void close() {
    List<Listener> current;
    synchronized (lifecycle) {
      if (closed) return;
      closed = true;
      current = List.copyOf(all);
    }
    current.forEach(Listener::close);
  }

  private final class Listener implements Waiting {
    private final Relay.TopicKey topic;
    private final ArrayBlockingQueue<Boolean> wakeup = new ArrayBlockingQueue<>(1);
    private final AtomicBoolean disposed = new AtomicBoolean();

    Listener(Relay.TopicKey topic) {
      this.topic = topic;
    }

    private void signal() {
      // offer is atomic with the capacity check, including races with timeout/close. A full
      // queue already retains the only hint needed; publishers never block behind a consumer.
      if (!disposed.get()) wakeup.offer(Boolean.TRUE);
    }

    @Override
    public void await(Duration maximum) throws InterruptedException {
      Objects.requireNonNull(maximum);
      if (maximum.isNegative() || maximum.isZero())
        throw new IllegalArgumentException("wait must be positive");
      if (disposed.get()) throw new InterruptedException("Relay wait is closed");
      wakeup.poll(maximum.toNanos(), TimeUnit.NANOSECONDS);
      if (disposed.get()) throw new InterruptedException("Relay wait is closed");
    }

    @Override
    public void close() {
      if (!disposed.compareAndSet(false, true)) return;
      synchronized (lifecycle) {
        all.remove(this);
        if (topic == null) discovery.remove(this);
        else
          topics.computeIfPresent(
              topic,
              (key, current) -> {
                current.remove(this);
                return current.isEmpty() ? null : current;
              });
      }
      wakeup.offer(Boolean.TRUE);
    }
  }
}
