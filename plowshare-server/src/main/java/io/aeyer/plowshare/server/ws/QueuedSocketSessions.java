package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.auth.SessionAuthority;
import io.aeyer.plowshare.server.reads.ReadUnavailableException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Shared admission for event/file session facts, including physical disclosure checks. One worker
 * queries only after admitting a bounded batch. Pending checks for the same identity share that
 * fresh database statement; a later caller never joins an already-running read or reuses its allow
 * result. Consequently every decision still has a current database observation during its call.
 *
 * <p>Principal/global bounds include physical work after a caller times out. Queueing retains no
 * database connection. Capacity exhaustion is unavailable, never evidence that a session is valid.
 * The owning database throttle remains in force underneath this single physical reader.
 */
public final class QueuedSocketSessions implements SessionAuthority, AutoCloseable {
  private record Check(Session session, long deadline, CompletableFuture<Boolean> result) {}

  private final SessionAuthority facts;
  private final SocketSessionProperties limits;
  private final Object state = new Object();
  private final ConcurrentLinkedQueue<Check> waiting = new ConcurrentLinkedQueue<>();
  private final ConcurrentHashMap<String, Integer> principals = new ConcurrentHashMap<>();
  private int pending;
  private int queued;
  private boolean closed;
  private final java.util.concurrent.atomic.AtomicLong rejected =
      new java.util.concurrent.atomic.AtomicLong();
  private final java.util.concurrent.atomic.AtomicLong timedOut =
      new java.util.concurrent.atomic.AtomicLong();
  private final java.util.concurrent.atomic.AtomicLong batches =
      new java.util.concurrent.atomic.AtomicLong();

  public QueuedSocketSessions(SessionAuthority facts, SocketSessionProperties limits) {
    this.facts = Objects.requireNonNull(facts);
    this.limits = Objects.requireNonNull(limits);
    Thread.ofPlatform().daemon().name("socket-session-checks").start(this::run);
  }

  @Override
  public boolean sessionCurrent(String handle, long version) {
    var session = new Session(handle, version);
    return currentSessions(Set.of(session)).contains(session);
  }

  @Override
  public Set<Session> currentSessions(Collection<Session> sessions) {
    var selected = List.copyOf(Set.copyOf(sessions));
    var current = new HashSet<Session>();
    // Scheduled sweeps may span many principals. Each bounded chunk preserves exact versions;
    // failure of any chunk fails the sweep rather than interpreting partial results as revocation.
    for (int start = 0; start < selected.size(); start += limits.batch()) {
      var checks =
          enqueue(selected.subList(start, Math.min(start + limits.batch(), selected.size())));
      for (var check : checks) if (await(check)) current.add(check.session());
    }
    return Set.copyOf(current);
  }

  private List<Check> enqueue(List<Session> sessions) {
    var counts = new java.util.HashMap<String, Integer>();
    for (var session : sessions) counts.merge(session.handle(), 1, Integer::sum);
    var checks = new ArrayList<Check>();
    synchronized (state) {
      if (closed
          || sessions.size() > limits.pending() - pending
          || counts.entrySet().stream()
              .anyMatch(
                  e ->
                      e.getValue()
                          > limits.pendingPerPrincipal()
                              - principals.getOrDefault(e.getKey(), 0))) {
        rejected.incrementAndGet();
        throw new ReadUnavailableException();
      }
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.waitMillis());
      for (var session : sessions) {
        var check = new Check(session, deadline, new CompletableFuture<>());
        checks.add(check);
        waiting.add(check);
        principals.merge(session.handle(), 1, Integer::sum);
        pending++;
        queued++;
      }
      state.notifyAll();
    }
    return checks;
  }

  private boolean await(Check check) {
    try {
      long remaining = check.deadline() - System.nanoTime();
      if (remaining <= 0) throw new TimeoutException();
      return check.result().get(remaining, TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw unavailable(check);
    } catch (TimeoutException timeout) {
      timedOut.incrementAndGet();
      throw unavailable(check);
    } catch (ExecutionException failed) {
      if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
      throw new IllegalStateException("Session check failed", failed.getCause());
    }
  }

  private static ReadUnavailableException unavailable(Check check) {
    var failure = new ReadUnavailableException();
    check.result().completeExceptionally(failure);
    return failure;
  }

  private List<Check> batch() throws InterruptedException {
    synchronized (state) {
      while (!closed && waiting.isEmpty()) state.wait();
      if (closed) return List.of();
      long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.gatherMillis());
      while (!closed && queued < limits.batch()) {
        long remaining = until - System.nanoTime();
        if (remaining <= 0) break;
        TimeUnit.NANOSECONDS.timedWait(state, remaining);
      }
      if (closed) return List.of();
      var result = new ArrayList<Check>();
      while (result.size() < limits.batch()) {
        var next = waiting.poll();
        if (next == null) break;
        result.add(next);
        queued--;
      }
      return result;
    }
  }

  private void run() {
    while (true) {
      List<Check> batch;
      try {
        batch = batch();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        close();
        return;
      }
      if (batch.isEmpty()) return;
      var active =
          batch.stream()
              .filter(c -> !c.result().isDone() && c.deadline() > System.nanoTime())
              .toList();
      Set<Session> current = Set.of();
      RuntimeException failed = null;
      try {
        if (!active.isEmpty()) {
          batches.incrementAndGet();
          current =
              Objects.requireNonNull(
                  facts.currentSessions(
                      active.stream()
                          .map(Check::session)
                          .collect(java.util.stream.Collectors.toSet())));
        }
      } catch (RuntimeException unavailable) {
        failed = unavailable;
      }
      // Release only after physical completion. Complete futures outside the state lock so a
      // dependent continuation cannot run owner work while admission metadata is locked.
      boolean stopped;
      synchronized (state) {
        stopped = closed;
        release(batch);
      }
      for (var check : batch) {
        if (failed != null) check.result().completeExceptionally(failed);
        else if (stopped || check.deadline() <= System.nanoTime()) unavailable(check);
        else check.result().complete(current.contains(check.session()));
      }
    }
  }

  private void release(List<Check> checks) {
    for (var check : checks) {
      pending--;
      principals.compute(check.session().handle(), (key, count) -> count == 1 ? null : count - 1);
    }
  }

  /** Admission retained by queued and physically executing checks; useful for bounded metrics. */
  int pending() {
    synchronized (state) {
      return pending;
    }
  }

  long rejected() {
    return rejected.get();
  }

  long timedOut() {
    return timedOut.get();
  }

  long batches() {
    return batches.get();
  }

  @Override
  public void close() {
    var removed = new ArrayList<Check>();
    synchronized (state) {
      closed = true;
      Check next;
      while ((next = waiting.poll()) != null) {
        removed.add(next);
        queued--;
      }
      release(removed);
      state.notifyAll();
    }
    for (var check : removed) unavailable(check);
  }
}
