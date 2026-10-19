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
  private final ConcurrentMap<String, VersionedProjectionCache<?>> caches =
      new ConcurrentHashMap<>();
  private final AtomicInteger reservations = new AtomicInteger();
  private final AtomicBoolean closed = new AtomicBoolean();

  public ProjectionCaches(
      ProjectionVersions versions,
      ProjectionChangeSignals signals,
      ProjectionCacheProperties properties) {
    this.versions = java.util.Objects.requireNonNull(versions);
    this.signals = java.util.Objects.requireNonNull(signals);
    this.properties = java.util.Objects.requireNonNull(properties);
    database = versions.databaseIdentity();
  }

  /** Each named projection has exactly one lifecycle owner and a compile-time DTO type. */
  public <T> ProjectionCache<T> create(String projection, Set<ProjectionVersions.Domain> domains) {
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
              backing, versions, signals, domains, properties.maxValueBytes());
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
