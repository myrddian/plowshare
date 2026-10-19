package io.aeyer.plowshare.server.cache;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One bounded lifecycle worker, with a fixed domain vocabulary and bounded observer registry. Each
 * session commits LISTEN before an authoritative baseline; outage/reconnect blocks shared reuse.
 * There is no replay queue or durable notification cursor: every read checks durable version
 * evidence, so a dropped/delayed/coalesced hint cannot authorize stale disclosure.
 *
 * <p>Observers run outside lifecycle locks and may reenter subscription, close or read APIs. They
 * must perform bounded bookkeeping, not SQL or blocking remote work. Concurrent registration may
 * reorder state callbacks; generations let consumers reject stale state transitions.
 */
public final class ListeningProjectionChangeSignals implements ProjectionChangeSignals {
  private static final Logger LOG = LoggerFactory.getLogger(ListeningProjectionChangeSignals.class);
  private static final Set<ProjectionVersions.Domain> ALL =
      Set.of(ProjectionVersions.Domain.values());

  private final class Registration implements Subscription {
    final long id;
    final Set<ProjectionVersions.Domain> domains;
    final Observer observer;

    Registration(long id, Set<ProjectionVersions.Domain> domains, Observer observer) {
      this.id = id;
      this.domains = domains;
      this.observer = observer;
    }

    @Override
    public void close() {
      observers.remove(id, this);
    }

    boolean active() {
      return observers.get(id) == this;
    }
  }

  private final Object lock = new Object();
  private final ProjectionNotificationSource source;
  private final ProjectionVersions versions;
  private final int maxObservers;
  private final Duration pollInterval;
  private final Duration reconnectDelay;
  private final ConcurrentMap<Long, Registration> observers = new ConcurrentHashMap<>();
  private State state = new State(1, false);
  private long registrationId;
  private boolean closed;
  private Thread worker;
  private ProjectionNotificationSource.Session session;

  public ListeningProjectionChangeSignals(
      ProjectionNotificationSource source,
      ProjectionVersions versions,
      int maxObservers,
      Duration pollInterval,
      Duration reconnectDelay) {
    this.source = Objects.requireNonNull(source);
    this.versions = Objects.requireNonNull(versions);
    if (maxObservers < 1) throw new IllegalArgumentException("Positive observer capacity required");
    checkDuration(pollInterval);
    checkDuration(reconnectDelay);
    this.maxObservers = maxObservers;
    this.pollInterval = pollInterval;
    this.reconnectDelay = reconnectDelay;
  }

  @Override
  public Subscription subscribe(Set<ProjectionVersions.Domain> domains, Observer observer) {
    if (domains == null || domains.isEmpty() || domains.size() > ALL.size())
      throw new IllegalArgumentException("Explicit listener domains required");
    Set<ProjectionVersions.Domain> selected = Set.copyOf(domains);
    Objects.requireNonNull(observer);
    Registration registration;
    State current;
    synchronized (lock) {
      if (closed) throw new IllegalStateException("Projection listener is closed");
      if (observers.size() >= maxObservers)
        throw new IllegalStateException("Projection observer capacity reached");
      registrationId = Math.incrementExact(registrationId);
      registration = new Registration(registrationId, selected, observer);
      observers.put(registration.id, registration);
      current = state;
    }
    try {
      observer.stateChanged(current);
    } catch (RuntimeException | Error failure) {
      registration.close();
      throw failure;
    }
    return registration;
  }

  @Override
  public void start() {
    Thread selected;
    synchronized (lock) {
      if (closed) throw new IllegalStateException("Projection listener is closed");
      if (worker != null) return;
      selected = Thread.ofVirtual().name("projection-change-listener").unstarted(this::run);
      worker = selected;
    }
    try {
      selected.start();
    } catch (RuntimeException | Error failure) {
      synchronized (lock) {
        if (worker == selected) worker = null;
      }
      throw failure;
    }
  }

  @Override
  public boolean healthy() {
    synchronized (lock) {
      return !closed && state.ready();
    }
  }

  private void run() {
    try {
      while (!isClosed()) {
        try (var opened = source.open()) {
          synchronized (lock) {
            if (closed) return;
            session = opened;
          }
          opened.listen();
          var baseline = versions.capture(ALL);
          if (baseline.isEmpty() || !versions.current(baseline.orElseThrow()))
            throw new IllegalStateException("Projection recovery baseline unavailable");
          transition(true);
          while (!isClosed()) changed(opened.poll(pollInterval));
        } catch (RuntimeException unavailable) {
          // Reconnect is an explicit infrastructure lifecycle, never replay of a source operation.
          // Log only the exception type: driver messages may contain deployment/credential data.
          if (!isClosed())
            LOG.warn("Projection listener recovering ({})", unavailable.getClass().getSimpleName());
        } finally {
          synchronized (lock) {
            session = null;
          }
          transition(false);
        }
        if (!isClosed()) {
          try {
            Thread.sleep(reconnectDelay);
          } catch (InterruptedException interrupted) {
            if (isClosed()) return;
            // An unexpected wakeup is a recovery hint; no source mutation is replayed.
          }
        }
      }
    } finally {
      transition(false);
    }
  }

  private boolean isClosed() {
    synchronized (lock) {
      return closed;
    }
  }

  private void transition(boolean ready) {
    State next;
    List<Registration> selected;
    synchronized (lock) {
      if (closed && ready || state.ready() == ready) return;
      next = new State(Math.incrementExact(state.generation()), ready);
      state = next;
      selected = List.copyOf(observers.values());
    }
    for (var registration : selected) {
      if (!registration.active()) continue;
      try {
        registration.observer.stateChanged(next);
      } catch (RuntimeException failure) {
        registration.close();
        LOG.warn("Projection observer removed ({})", failure.getClass().getSimpleName());
      }
    }
  }

  private void changed(Set<ProjectionVersions.Domain> changed) {
    if (changed.isEmpty()) return;
    for (var registration : List.copyOf(observers.values())) {
      if (!registration.active()) continue;
      var relevant =
          changed.stream()
              .filter(registration.domains::contains)
              .collect(java.util.stream.Collectors.toUnmodifiableSet());
      if (relevant.isEmpty()) continue;
      try {
        registration.observer.changed(relevant);
      } catch (RuntimeException failure) {
        registration.close();
        LOG.warn("Projection observer removed ({})", failure.getClass().getSimpleName());
      }
    }
  }

  @Override
  public void close() {
    ProjectionNotificationSource.Session active;
    Thread running;
    synchronized (lock) {
      if (closed) return;
      closed = true;
      active = session;
      running = worker;
    }
    try {
      transition(false);
    } finally {
      try {
        if (active != null) active.close();
      } finally {
        if (running != null) running.interrupt();
        observers.clear();
      }
    }
  }

  private static void checkDuration(Duration duration) {
    Objects.requireNonNull(duration);
    if (duration.isNegative()
        || duration.isZero()
        || duration.toMillis() < 1
        || duration.toMillis() > Integer.MAX_VALUE)
      throw new IllegalArgumentException("Positive bounded listener duration required");
  }
}
