package io.aeyer.plowshare.server.cache;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
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
 *
 * <p>With probing enabled the worker also sends itself a delivery probe every {@code heartbeat}.
 * Observers are called synchronously on this worker, so when a probe's echo is read, every change
 * committed before the probe has already been applied; {@link #deliveredThrough} publishes the
 * probe's send time. A probe unanswered for {@code heartbeatTimeout} means delivery has stopped,
 * however quiet the socket looks: the session is abandoned and recovery starts, exactly as for a
 * failed poll. A silent connection loss therefore costs at most that timeout plus the reconnect
 * delay, never an indefinite wait.
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
  private final Duration heartbeat;
  private final Duration heartbeatTimeout;
  private final ConcurrentMap<Long, Registration> observers = new ConcurrentHashMap<>();
  private State state = new State(1, false);
  private long registrationId;
  private boolean closed;
  private Thread worker;
  private ProjectionNotificationSource.Session session;
  // Written only by the worker; read by cache hits. Valid only for deliveredGeneration.
  private volatile long deliveredNanos;
  private volatile long deliveredGeneration = -1;
  private final AtomicLong reconnectAttempts = new AtomicLong();

  @Override
  public long reconnectAttempts() {
    return reconnectAttempts.get();
  }

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
    this.heartbeat = null;
    this.heartbeatTimeout = null;
  }

  /**
   * Probing listener. {@code heartbeat} must be shorter than {@code heartbeatTimeout}, so one late
   * probe does not by itself abandon a working session.
   */
  public ListeningProjectionChangeSignals(
      ProjectionNotificationSource source,
      ProjectionVersions versions,
      int maxObservers,
      Duration pollInterval,
      Duration reconnectDelay,
      Duration heartbeat,
      Duration heartbeatTimeout) {
    this.source = Objects.requireNonNull(source);
    this.versions = Objects.requireNonNull(versions);
    if (maxObservers < 1) throw new IllegalArgumentException("Positive observer capacity required");
    checkDuration(pollInterval);
    checkDuration(reconnectDelay);
    checkDuration(heartbeat);
    checkDuration(heartbeatTimeout);
    if (heartbeat.compareTo(heartbeatTimeout) >= 0)
      throw new IllegalArgumentException("Heartbeat must be shorter than its timeout");
    this.maxObservers = maxObservers;
    this.pollInterval = pollInterval;
    this.reconnectDelay = reconnectDelay;
    this.heartbeat = heartbeat;
    this.heartbeatTimeout = heartbeatTimeout;
  }

  @Override
  public OptionalLong deliveredThrough() {
    long generation = deliveredGeneration;
    long nanos = deliveredNanos;
    synchronized (lock) {
      if (closed || !state.ready() || state.generation() != generation) return OptionalLong.empty();
    }
    return OptionalLong.of(nanos);
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
    boolean firstAttempt = true;
    try {
      while (!isClosed()) {
        if (!firstAttempt) reconnectAttempts.incrementAndGet();
        firstAttempt = false;
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
          if (heartbeat == null) {
            while (!isClosed()) changed(opened.poll(pollInterval));
          } else {
            probe(opened);
          }
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

  /**
   * Polls with probing. Sends are numbered per session; {@code sent} keeps each unanswered send
   * time, oldest first, and is bounded because a probe left unanswered past the timeout ends the
   * session.
   */
  private void probe(ProjectionNotificationSource.Session opened) {
    long generation;
    synchronized (lock) {
      generation = state.generation();
    }
    var sent = new ArrayDeque<long[]>();
    long sequence = 0;
    long interval = heartbeat.toNanos();
    long timeout = heartbeatTimeout.toNanos();
    long nextSend = System.nanoTime();
    while (!isClosed()) {
      long now = System.nanoTime();
      if (now - nextSend >= 0) {
        sequence++;
        // Record the time before sending: the echo proves delivery of commits before this instant.
        if (!opened.heartbeat(sequence)) {
          // This session cannot probe; fall back to plain polling with no freshness claim.
          while (!isClosed()) changed(opened.poll(pollInterval));
          return;
        }
        sent.addLast(new long[] {sequence, now});
        nextSend = now + interval;
      }
      long wait = Math.min(pollInterval.toNanos(), Math.max(1_000_000L, nextSend - now));
      changed(opened.poll(Duration.ofNanos(wait)));
      // Observers have now applied every change returned so far, including those before the echo.
      long answered = opened.heartbeatsReceived();
      long through = -1;
      while (!sent.isEmpty() && sent.peekFirst()[0] <= answered) through = sent.pollFirst()[1];
      if (through != -1) {
        deliveredNanos = through;
        deliveredGeneration = generation;
      }
      if (!sent.isEmpty() && System.nanoTime() - sent.peekFirst()[1] > timeout)
        throw new IllegalStateException("Projection notification delivery stopped");
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
      deliveredGeneration = -1;
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
              .collect(Collectors.toUnmodifiableSet());
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
