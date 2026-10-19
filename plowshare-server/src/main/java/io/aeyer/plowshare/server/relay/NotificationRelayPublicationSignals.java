package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.cache.ProjectionChangeSignals;
import io.aeyer.plowshare.server.cache.ProjectionVersions;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapts the shared, committed PostgreSQL LISTEN lifecycle to bounded Relay wakeups. No event IDs,
 * positions or payloads travel through hints. Startup/reconnect/outage wakes all readers to rescan
 * durable state; monotonic generations prevent reordered callbacks from restoring stale health.
 */
public final class NotificationRelayPublicationSignals implements RelayPublicationSignals {
  private final LocalRelayPublicationSignals local;
  private final ProjectionChangeSignals.Subscription subscription;
  private final Duration reconciliation;
  private final AtomicReference<ProjectionChangeSignals.State> state = new AtomicReference<>();
  private final AtomicBoolean closed = new AtomicBoolean();

  public NotificationRelayPublicationSignals(
      ProjectionChangeSignals signals, RelayWorkerProperties properties) {
    Objects.requireNonNull(properties);
    reconciliation = properties.getReconciliationInterval();
    local = new LocalRelayPublicationSignals(properties.getMaxSignalWaiters());
    subscription =
        Objects.requireNonNull(signals)
            .subscribe(
                Set.of(ProjectionVersions.Domain.RELAY, ProjectionVersions.Domain.AUTHORITY),
                new ProjectionChangeSignals.Observer() {
                  @Override
                  public void stateChanged(ProjectionChangeSignals.State next) {
                    ProjectionChangeSignals.State previous;
                    do {
                      previous = state.get();
                      if (closed.get()
                          || previous != null && previous.generation() >= next.generation()) return;
                    } while (!state.compareAndSet(previous, next));
                    local.wakeAll();
                  }

                  @Override
                  public void changed(Set<ProjectionVersions.Domain> domains) {
                    if (!closed.get()) local.wakeAll();
                  }
                });
  }

  @Override
  public void published(Relay.TopicKey topic) {
    local.published(topic);
  }

  @Override
  public Waiting listen(Relay.SubscriptionKey subscription) {
    return local.listen(subscription);
  }

  @Override
  public Waiting discovery() {
    return local.discovery();
  }

  @Override
  public Duration reconciliationWait(Duration fallback) {
    Objects.requireNonNull(fallback);
    var current = state.get();
    return !closed.get()
            && current != null
            && current.ready()
            && reconciliation.compareTo(fallback) > 0
        ? reconciliation
        : fallback;
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    try {
      subscription.close();
    } finally {
      local.close();
    }
  }
}
