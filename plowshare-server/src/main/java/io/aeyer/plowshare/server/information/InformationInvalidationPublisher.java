package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.cache.ProjectionChangeSignals;
import io.aeyer.plowshare.server.cache.ProjectionVersions.Domain;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lifecycle-owned bridge from committed document/authority hints to opaque socket invalidation.
 * Recovery transitions invalidate client snapshots too. No identifiers or source values cross this
 * bridge, and it never enumerates database accounts or retains a delivery/replay queue.
 */
public final class InformationInvalidationPublisher implements AutoCloseable {
  private final ProjectionChangeSignals signals;
  private final InformationInvalidations pushes;
  private final InformationInvalidationClock clock;
  // Only one soft deadline is retained. Source hints cannot grow a queue or postpone it.
  private boolean scheduled;
  private boolean content;
  private boolean progress;
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicReference<ProjectionChangeSignals.Subscription> subscription =
      new AtomicReference<>();
  private final AtomicReference<ProjectionChangeSignals.State> state = new AtomicReference<>();

  public InformationInvalidationPublisher(
      ProjectionChangeSignals signals, InformationInvalidations pushes) {
    this(signals, pushes, new ScheduledInformationInvalidationClock());
  }

  public InformationInvalidationPublisher(
      ProjectionChangeSignals signals,
      InformationInvalidations pushes,
      InformationInvalidationClock clock) {
    this.clock = Objects.requireNonNull(clock);
    this.signals = Objects.requireNonNull(signals);
    this.pushes = Objects.requireNonNull(pushes);
  }

  /** Start after application composition; callbacks and transport offers run outside locks. */
  public void start() {
    if (closed.get() || !started.compareAndSet(false, true)) return;
    var opened =
        signals.subscribe(
            Set.of(
                Domain.DOCUMENTS,
                Domain.KNOWLEDGE_ACCESS,
                Domain.AUTHORITY,
                Domain.PROGRESS,
                Domain.TYPES,
                Domain.EXTRACTIONS),
            new ProjectionChangeSignals.Observer() {
              @Override
              public void stateChanged(ProjectionChangeSignals.State next) {
                ProjectionChangeSignals.State previous;
                do {
                  previous = state.get();
                  if (closed.get()
                      || previous != null && next.generation() <= previous.generation()) return;
                } while (!state.compareAndSet(previous, next));
                publish();
              }

              @Override
              public void changed(Set<Domain> domains) {
                if (domains.contains(Domain.KNOWLEDGE_ACCESS)
                    || domains.contains(Domain.AUTHORITY)) {
                  publish();
                } else if (domains.contains(Domain.DOCUMENTS)
                    || domains.contains(Domain.TYPES)
                    || domains.contains(Domain.EXTRACTIONS)) {
                  soft(true);
                } else if (domains.contains(Domain.PROGRESS)) soft(false);
              }
            });
    if (!subscription.compareAndSet(null, opened) || closed.get()) {
      subscription.compareAndSet(opened, null);
      opened.close();
    }
  }

  private void publish() {
    synchronized (this) {
      if (closed.get()) return;
      // A revocation/recovery supersedes preceding soft work but never waits for its timer.
      content = progress = false;
    }
    pushes.invalidate();
  }

  private synchronized void soft(boolean changedContent) {
    if (closed.get()) return;
    content |= changedContent;
    progress = true;
    if (scheduled) return;
    scheduled = true;
    clock.schedule(this::flush);
  }

  private void flush() {
    boolean refresh;
    synchronized (this) {
      scheduled = false;
      if (closed.get() || !progress) return;
      refresh = content;
      content = progress = false;
    }
    // Transport callbacks are never executed under the publisher monitor.
    if (refresh) pushes.refresh();
    else pushes.progress();
  }

  @Override
  public void close() {
    synchronized (this) {
      if (!closed.compareAndSet(false, true)) return;
    }
    clock.close();
    var opened = subscription.getAndSet(null);
    if (opened != null) opened.close();
  }
}
