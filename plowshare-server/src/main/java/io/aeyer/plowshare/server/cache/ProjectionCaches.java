package io.aeyer.plowshare.server.cache;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Composition-owned typed cache factory; no repository or SQL is exposed to domain services. */
public final class ProjectionCaches implements AutoCloseable {
  private final ProjectionVersions versions;
  private final ProjectionChangeSignals signals;
  private final ProjectionCacheProperties properties;
  private final UUID database;
  private final java.time.Duration notifiedStaleness;
  private final ProjectionTelemetry telemetry;
  private final ConcurrentMap<String, VersionedProjectionCache<?>> caches =
      new ConcurrentHashMap<>();
  private final AtomicInteger reservations = new AtomicInteger();
  private final AtomicBoolean closed = new AtomicBoolean();

  public ProjectionCaches(
      ProjectionVersions versions,
      ProjectionChangeSignals signals,
      ProjectionCacheProperties properties) {
    this(versions, signals, properties, null);
  }

  /**
   * {@code notifiedStaleness} is the bound for caches created with {@link Freshness#NOTIFIED}; null
   * makes every cache versioned.
   */
  public ProjectionCaches(
      ProjectionVersions versions,
      ProjectionChangeSignals signals,
      ProjectionCacheProperties properties,
      java.time.Duration notifiedStaleness) {
    this(versions, signals, properties, notifiedStaleness, ProjectionTelemetry.NONE);
  }

  public ProjectionCaches(
      ProjectionVersions versions,
      ProjectionChangeSignals signals,
      ProjectionCacheProperties properties,
      java.time.Duration notifiedStaleness,
      ProjectionTelemetry telemetry) {
    this.versions = java.util.Objects.requireNonNull(versions);
    this.signals = java.util.Objects.requireNonNull(signals);
    this.properties = java.util.Objects.requireNonNull(properties);
    this.notifiedStaleness = notifiedStaleness;
    this.telemetry = java.util.Objects.requireNonNull(telemetry);
    database = versions.databaseIdentity();
  }

  /** How a projection's repeat reads are confirmed before disclosure. */
  public enum Freshness {
    /** Every read checks its stored version against the database. */
    VERSIONED,
    /**
     * Repeat reads are answered from memory while database notifications prove no watched change,
     * lagging a committed change by at most the configured staleness. See {@link
     * VersionedProjectionCache}.
     */
    NOTIFIED
  }

  /** Each named projection has exactly one lifecycle owner and a compile-time DTO type. */
  public <T> ProjectionCache<T> create(String projection, Set<ProjectionVersions.Domain> domains) {
    return create(projection, domains, Freshness.VERSIONED);
  }

  public <T> ProjectionCache<T> create(
      String projection, Set<ProjectionVersions.Domain> domains, Freshness freshness) {
    java.util.Objects.requireNonNull(freshness);
    var namespace = new SnapshotCache.Namespace(database.toString(), projection);
    if (closed.get()) throw new IllegalStateException("Projection caches closed");
    if (reservations.incrementAndGet() > properties.maxProjections()) {
      reservations.decrementAndGet();
      throw new IllegalStateException("Projection cache capacity exceeded");
    }
    var backing =
        new BoundedSnapshotCache<VersionedProjectionCache.Versioned<T>>(
            namespace, properties.limits());
    VersionedProjectionCache<T> cache;
    try {
      cache =
          new VersionedProjectionCache<>(
              backing,
              versions,
              signals,
              domains,
              properties.maxValueBytes(),
              freshness == Freshness.NOTIFIED ? notifiedStaleness : null);
    } catch (RuntimeException | Error failure) {
      backing.close();
      reservations.decrementAndGet();
      throw failure;
    }
    if (caches.putIfAbsent(projection, cache) != null) {
      cache.close();
      reservations.decrementAndGet();
      throw new IllegalStateException("Projection cache already registered");
    }
    if (closed.get()) {
      if (caches.remove(projection, cache)) {
        try {
          cache.close();
        } finally {
          reservations.decrementAndGet();
        }
      }
      throw new IllegalStateException("Projection caches closed");
    }
    try {
      // Failed subscriptions and duplicate/capacity refusals must not register orphaned meters.
      telemetry.observeCache(projection, cache.statistics(), backing::statistics);
    } catch (RuntimeException | Error failure) {
      if (caches.remove(projection, cache)) {
        try {
          cache.close();
        } finally {
          reservations.decrementAndGet();
        }
      }
      throw failure;
    }
    return cache;
  }

  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    Throwable failure = null;
    for (var entry : caches.entrySet()) {
      if (caches.remove(entry.getKey(), entry.getValue())) {
        try {
          entry.getValue().close();
        } catch (RuntimeException | Error closing) {
          if (failure == null) failure = closing;
          else failure.addSuppressed(closing);
        } finally {
          reservations.decrementAndGet();
        }
      }
    }
    if (failure instanceof Error fatal) throw fatal;
    if (failure instanceof RuntimeException runtime) throw runtime;
  }
}
