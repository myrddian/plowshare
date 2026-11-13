package io.aeyer.plowshare.server.cache;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

/**
 * Pull-based export: cache reads and listener recovery only update their own in-memory counters.
 */
public final class MicrometerProjectionTelemetry implements ProjectionTelemetry {
  private static final String CACHE = "plowshare.projection.cache";
  private static final String LISTENER = "plowshare.projection.listener";
  private final MeterRegistry registry;

  public MicrometerProjectionTelemetry(MeterRegistry registry) {
    this.registry = Objects.requireNonNull(registry);
  }

  @Override
  public void observeCache(
      String projection,
      ProjectionCacheStatistics outcomes,
      Supplier<SnapshotCache.Statistics> backing) {
    for (var event : ProjectionCacheStatistics.Event.values()) {
      FunctionCounter.builder(CACHE + ".events", outcomes, value -> value.count(event))
          .description("Projection outcomes and loader/fallback attempts; see event semantics")
          .tags("projection", projection, "event", event.name().toLowerCase(Locale.ROOT))
          .register(registry);
    }
    cacheGauge(projection, "entries", backing, SnapshotCache.Statistics::entries);
    cacheGauge(projection, "resident.bytes", backing, SnapshotCache.Statistics::residentWeight);
    cacheGauge(projection, "loads.active", backing, SnapshotCache.Statistics::loads);
    cacheGauge(projection, "loading.bytes", backing, SnapshotCache.Statistics::loadingWeight);
    cacheGauge(projection, "waiters", backing, SnapshotCache.Statistics::waiters);
    for (var event : BackingEvent.values()) {
      FunctionCounter.builder(CACHE + ".backing", backing, value -> event.count(value.get()))
          .description("Backing cache lookups and maintenance, before projection freshness checks")
          .tags("projection", projection, "event", event.name().toLowerCase(Locale.ROOT))
          .register(registry);
    }
  }

  private enum BackingEvent {
    HITS(SnapshotCache.Statistics::hits),
    MISSES(SnapshotCache.Statistics::misses),
    COALESCED(SnapshotCache.Statistics::coalesced),
    EVICTIONS(SnapshotCache.Statistics::evictions),
    BYPASSED(SnapshotCache.Statistics::bypassed),
    INVALIDATIONS(SnapshotCache.Statistics::invalidations),
    LOAD_FAILURES(SnapshotCache.Statistics::loadFailures);

    private final ToDoubleFunction<SnapshotCache.Statistics> counter;

    BackingEvent(ToDoubleFunction<SnapshotCache.Statistics> counter) {
      this.counter = counter;
    }

    double count(SnapshotCache.Statistics statistics) {
      return counter.applyAsDouble(statistics);
    }
  }

  private void cacheGauge(
      String projection,
      String name,
      Supplier<SnapshotCache.Statistics> backing,
      ToDoubleFunction<SnapshotCache.Statistics> value) {
    Gauge.builder(CACHE + "." + name, backing, source -> value.applyAsDouble(source.get()))
        .tag("projection", projection)
        .strongReference(true)
        .register(registry);
  }

  @Override
  public void observeListener(
      ProjectionChangeSignals signals, ProjectionNotificationProperties properties) {
    Gauge.builder(LISTENER + ".ready", signals, value -> value.healthy() ? 1 : 0)
        .description("LISTEN session has completed its authoritative recovery baseline")
        .strongReference(true)
        .register(registry);
    Gauge.builder(
            LISTENER + ".proof.available",
            signals,
            value -> value.deliveredThrough().isPresent() ? 1 : 0)
        .description("An answered probe proves delivery in the current listener generation")
        .strongReference(true)
        .register(registry);
    Gauge.builder(
            LISTENER + ".proof.age",
            signals,
            value -> {
              var through = value.deliveredThrough();
              return through.isPresent()
                  ? Math.max(0, System.nanoTime() - through.getAsLong()) / 1_000_000_000.0
                  : Double.NaN;
            })
        .baseUnit("seconds")
        .description("Age of proven delivery; NaN when no proof is available")
        .strongReference(true)
        .register(registry);
    Gauge.builder(LISTENER + ".notified.enabled", properties, value -> value.enabled() ? 1 : 0)
        .strongReference(true)
        .register(registry);
    Gauge.builder(LISTENER + ".staleness", properties, value -> value.staleness().toNanos() / 1e9)
        .baseUnit("seconds")
        .description("Configured maximum age of proof for notification-trusted cache hits")
        .strongReference(true)
        .register(registry);
    FunctionCounter.builder(
            LISTENER + ".reconnects", signals, ProjectionChangeSignals::reconnectAttempts)
        .description("Session open attempts after the first, including failed recovery attempts")
        .register(registry);
  }
}
