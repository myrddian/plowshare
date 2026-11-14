package io.aeyer.plowshare.server.cache;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Composition-owned typed cache factory; no repository or SQL is exposed to domain services. */
public final class ProjectionCaches implements AutoCloseable {
  private final ProjectionVersions versions;
  private final ProjectionItemVersions itemVersions;
  private final ProjectionChangeSignals signals;
  private final ProjectionCacheProperties properties;
  private final UUID database;
  private final Duration notifiedStaleness;
  private final ProjectionTelemetry telemetry;
  private final ConcurrentMap<String, VersionedProjectionCache<?>> caches =
      new ConcurrentHashMap<>();
  // One memory budget shared by every line; the number of lines is never limited.
  private final ResidentBudget budget;
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
      Duration notifiedStaleness) {
    this(versions, signals, properties, notifiedStaleness, ProjectionTelemetry.NONE);
  }

  public ProjectionCaches(
      ProjectionVersions versions,
      ProjectionChangeSignals signals,
      ProjectionCacheProperties properties,
      Duration notifiedStaleness,
      ProjectionTelemetry telemetry) {
    this(versions, null, signals, properties, notifiedStaleness, telemetry);
  }

  /**
   * {@code itemVersions} enables {@link #createItemScoped}; null leaves only domain-scoped caches.
   */
  public ProjectionCaches(
      ProjectionVersions versions,
      ProjectionItemVersions itemVersions,
      ProjectionChangeSignals signals,
      ProjectionCacheProperties properties,
      Duration notifiedStaleness,
      ProjectionTelemetry telemetry) {
    this.versions = Objects.requireNonNull(versions);
    this.itemVersions = itemVersions;
    this.signals = Objects.requireNonNull(signals);
    this.properties = Objects.requireNonNull(properties);
    this.notifiedStaleness = notifiedStaleness;
    this.telemetry = Objects.requireNonNull(telemetry);
    budget = new ResidentBudget(properties.totalResidentBytes());
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
    return register(
        projection,
        freshness,
        (SnapshotCache<VersionedProjectionCache.Versioned<T>> backing, Duration staleness) ->
            new VersionedProjectionCache<>(
                backing,
                versions,
                signals,
                domains,
                properties.maxValueBytes(),
                staleness,
                properties.timeout()));
  }

  /**
   * An item-scoped projection: entries name the items they read and a write invalidates only the
   * entries of the items it changed. {@code domains} must all be item-tracked.
   */
  public <T> ItemProjectionCache<T> createItemScoped(
      String projection, Set<ProjectionVersions.Domain> domains, Freshness freshness) {
    if (itemVersions == null) throw new IllegalStateException("Item versions are not configured");
    return register(
        projection,
        freshness,
        (SnapshotCache<VersionedProjectionCache.Versioned<T>> backing, Duration staleness) ->
            VersionedProjectionCache.itemScoped(
                backing,
                itemVersions,
                signals,
                domains,
                properties.maxValueBytes(),
                staleness,
                properties.timeout()));
  }

  /** Builds one projection over its backing cache and staleness bound (null when versioned). */
  @FunctionalInterface
  private interface Factory<T> {
    VersionedProjectionCache<T> create(
        SnapshotCache<VersionedProjectionCache.Versioned<T>> backing, Duration staleness);
  }

  private <T> VersionedProjectionCache<T> register(
      String projection, Freshness freshness, Factory<T> factory) {
    Objects.requireNonNull(freshness);
    var namespace = new SnapshotCache.Namespace(database.toString(), projection);
    if (closed.get()) throw new IllegalStateException("Projection caches closed");
    var backing =
        new BoundedSnapshotCache<VersionedProjectionCache.Versioned<T>>(
            namespace, properties.limits());
    backing.attach(budget);
    VersionedProjectionCache<T> cache;
    try {
      cache = factory.create(backing, freshness == Freshness.NOTIFIED ? notifiedStaleness : null);
    } catch (RuntimeException | Error failure) {
      backing.close();
      throw failure;
    }
    if (caches.putIfAbsent(projection, cache) != null) {
      cache.close();
      throw new IllegalStateException("Projection cache already registered");
    }
    if (closed.get()) {
      if (caches.remove(projection, cache)) {
        cache.close();
      }
      throw new IllegalStateException("Projection caches closed");
    }
    try {
      // Failed subscriptions and duplicate/capacity refusals must not register orphaned meters.
      telemetry.observeCache(projection, cache.statistics(), backing::statistics);
    } catch (RuntimeException | Error failure) {
      if (caches.remove(projection, cache)) {
        cache.close();
      }
      throw failure;
    }
    return cache;
  }

  /** Bytes held by every line together, and the shared budget they must fit. */
  public long residentBytes() {
    return budget.resident();
  }

  public long residentBudgetBytes() {
    return budget.total();
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
        }
      }
    }
    if (failure instanceof Error fatal) throw fatal;
    if (failure instanceof RuntimeException runtime) throw runtime;
  }
}
