package io.aeyer.plowshare.server.relay.tools;

import io.aeyer.plowshare.server.cache.ProjectionChangeSignals;
import io.aeyer.plowshare.server.cache.ProjectionVersions.Domain;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Fans one database-notification subscription out to a bounded set of tool waiters.
 *
 * <p>The RELAY domain is coarse (any publication anywhere), which is enough for a wake-up: the
 * waiter reads its own retained result. AUTHORITY changes, and any listener state change (recovery
 * may have dropped hints), are reported as authority wakes. Registrations beyond the bound degrade
 * to a plain timed wait instead of failing the tool call, and one subscription is shared rather
 * than one per waiting call.
 */
public final class ProjectionRelayToolWakeups implements RelayToolWakeups, AutoCloseable {
  private final Set<Waiter> waiters = ConcurrentHashMap.newKeySet();
  private final int maximum;
  private final ProjectionChangeSignals.Subscription subscription;

  public ProjectionRelayToolWakeups(ProjectionChangeSignals signals, int maximum) {
    if (maximum < 1 || maximum > 65536)
      throw new IllegalArgumentException("Invalid tool waiter bound");
    this.maximum = maximum;
    this.subscription =
        Objects.requireNonNull(signals)
            .subscribe(
                Set.of(Domain.RELAY, Domain.AUTHORITY),
                new ProjectionChangeSignals.Observer() {
                  @Override
                  public void stateChanged(ProjectionChangeSignals.State state) {
                    waiters.forEach(waiter -> waiter.signal(true));
                  }

                  @Override
                  public void changed(Set<Domain> domains) {
                    boolean authority = domains.contains(Domain.AUTHORITY);
                    waiters.forEach(waiter -> waiter.signal(authority));
                  }
                });
  }

  @Override
  public Wakeup open() {
    var waiter = new Waiter();
    // The size check may race a concurrent open by a few entries; the bound is a safety limit.
    if (waiters.size() >= maximum) return TIMED.open();
    waiters.add(waiter);
    return waiter;
  }

  @Override
  public void close() {
    subscription.close();
    waiters.forEach(Waiter::close);
  }

  private final class Waiter implements Wakeup {
    private boolean signalled;
    private boolean authority;
    private boolean closed;

    synchronized void signal(boolean authorityChanged) {
      signalled = true;
      authority |= authorityChanged;
      notifyAll();
    }

    @Override
    public synchronized boolean await(Duration maximum) throws InterruptedException {
      long end = System.nanoTime() + maximum.toNanos();
      while (!signalled && !closed) {
        long remaining = end - System.nanoTime();
        if (remaining <= 0) break;
        TimeUnit.NANOSECONDS.timedWait(this, remaining);
      }
      if (closed) throw new InterruptedException("Tool wait is closed");
      boolean result = authority;
      signalled = false;
      authority = false;
      return result;
    }

    @Override
    public synchronized void close() {
      waiters.remove(this);
      closed = true;
      notifyAll();
    }
  }
}
