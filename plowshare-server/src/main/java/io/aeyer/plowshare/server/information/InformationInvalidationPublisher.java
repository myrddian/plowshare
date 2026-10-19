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
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicReference<ProjectionChangeSignals.Subscription> subscription =
      new AtomicReference<>();
  private final AtomicReference<ProjectionChangeSignals.State> state = new AtomicReference<>();

  public InformationInvalidationPublisher(
      ProjectionChangeSignals signals, InformationInvalidations pushes) {
    this.signals = Objects.requireNonNull(signals);
    this.pushes = Objects.requireNonNull(pushes);
  }

  /** Start after application composition; callbacks and transport offers run outside locks. */
  public void start() {
    if (closed.get() || !started.compareAndSet(false, true)) return;
    var opened =
        signals.subscribe(
            Set.of(Domain.DOCUMENTS, Domain.AUTHORITY),
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
                if (domains.contains(Domain.DOCUMENTS) || domains.contains(Domain.AUTHORITY))
                  publish();
              }
            });
    if (!subscription.compareAndSet(null, opened) || closed.get()) {
      subscription.compareAndSet(opened, null);
      opened.close();
    }
  }

  private void publish() {
    if (!closed.get()) pushes.invalidate();
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    var opened = subscription.getAndSet(null);
    if (opened != null) opened.close();
  }
}
