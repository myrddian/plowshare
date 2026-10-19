package io.aeyer.plowshare.server.cache;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * Versions, not notification delivery, decide whether a value may be disclosed. Inline execution
 * preserves caller-bound database locks. Transaction-local dirty reads are never shared, including
 * a loader that dirties its transaction after initially passing the eligibility check.
 */
public final class VersionedProjectionCache<T> implements ProjectionCache<T>, AutoCloseable {
  record Versioned<T>(ProjectionVersions.Token token, T value) {}

  private static final long TOKEN_RESERVATION = 2048L + 128L * 4096;
  private final SnapshotCache<Versioned<T>> cache;
  private final ProjectionVersions versions;
  private final Set<ProjectionVersions.Domain> domains;
  private final long maxWeight;
  private final ProjectionChangeSignals.Subscription subscription;
  private final AtomicReference<ProjectionChangeSignals.State> state = new AtomicReference<>();
  private final AtomicBoolean transitioning = new AtomicBoolean();
  private volatile long readyGeneration = -1;
  private volatile boolean closed;

  public VersionedProjectionCache(
      SnapshotCache<Versioned<T>> cache,
      ProjectionVersions versions,
      ProjectionChangeSignals signals,
      Set<ProjectionVersions.Domain> domains,
      long maxWeight) {
    this.cache = Objects.requireNonNull(cache);
    this.versions = Objects.requireNonNull(versions);
    this.domains = Set.copyOf(domains);
    if (this.domains.isEmpty() || maxWeight < 1)
      throw new IllegalArgumentException("Invalid projection limits");
    this.maxWeight = maxWeight;
    subscription =
        signals.subscribe(
            this.domains,
            new ProjectionChangeSignals.Observer() {
              public void stateChanged(ProjectionChangeSignals.State state) {
                transition(state);
              }

              public void changed(Set<ProjectionVersions.Domain> changed) {
                cache.invalidateAll();
              }
            });
  }

  private void transition(ProjectionChangeSignals.State state) {
    ProjectionChangeSignals.State seen;
    do {
      seen = this.state.get();
      if (closed || seen != null && state.generation() <= seen.generation()) return;
    } while (!this.state.compareAndSet(seen, state));
    drainTransitions();
  }

  private boolean ready() {
    var current = state.get();
    return !closed && current != null && current.ready() && readyGeneration == current.generation();
  }

  private void drainTransitions() {
    while (!closed && transitioning.compareAndSet(false, true)) {
      ProjectionChangeSignals.State applied = null;
      try {
        while (!closed) {
          var latest = state.get();
          if (latest == applied) break;
          readyGeneration = -1;
          var ticket = cache.beginRecovery();
          if (latest.ready() && latest == state.get() && !closed) {
            if (cache.completeRecovery(ticket)) readyGeneration = latest.generation();
          }
          applied = latest;
        }
      } catch (SnapshotCache.CacheException stopped) {
        if (!closed || stopped.failure() != SnapshotCache.Failure.CLOSED) throw stopped;
      } finally {
        transitioning.set(false);
      }
      // A transition arriving between the last snapshot and flag release needs another pass,
      // without recursive drain calls or an unbounded callback queue.
      if (closed || applied == state.get()) return;
    }
  }

  public boolean enabled() {
    return true;
  }

  public long maxWeight() {
    return maxWeight;
  }

  public T read(
      SnapshotCache.Key key,
      boolean cacheable,
      Supplier<T> boundedLoader,
      Supplier<T> uncachedLoader,
      ToLongFunction<T> weight) {
    Objects.requireNonNull(key);
    Objects.requireNonNull(boundedLoader);
    Objects.requireNonNull(uncachedLoader);
    Objects.requireNonNull(weight);
    if (!ready() || !cacheable) return uncachedLoader.get();
    var captured = versions.capture(domains);
    if (captured.isEmpty()) return uncachedLoader.get();
    var token = captured.orElseThrow();
    var request =
        new SnapshotCache.Request(
            key,
            token.sourceVersion(),
            token.dependencies(),
            Math.addExact(maxWeight, TOKEN_RESERVATION));
    try (var read =
        cache.readInline(
            request,
            () -> {
              // capture already checked this caller's transaction. No owner code or SQL runs
              // between capture and an elected inline loader; do not repeat those probes here.
              T value = Objects.requireNonNull(boundedLoader.get());
              long charged = weight.applyAsLong(value);
              if (charged < 1
                  || charged > maxWeight
                  || !versions.canShare()
                  || !versions.current(token)) throw new Bypass();
              return new SnapshotCache.Loaded<>(
                  token.sourceVersion(),
                  new Versioned<>(token, value),
                  charged + 2048L + 128L * token.pendingTransactions().size());
            })) {
      var stored = read.result().toCompletableFuture().join().value();
      // Pending transactions are evidence attached to the value, not part of its allocation
      // counter identity. Validating the newly captured token here would launder an old value
      // after a previously pending writer committed without another counter allocation.
      // Hits/joiners execute no caller-side SQL after capture. The elected loader checks its
      // dirty state before publication; expiry is covered by the stored token's time window.
      if (ready() && versions.current(stored.token())) return stored.value();
      cache.invalidate(key);
    } catch (CompletionException failed) {
      Throwable cause = failed.getCause();
      if (cause instanceof SnapshotCache.CacheException failure
          && (failure.failure() == SnapshotCache.Failure.REENTRANT_LOAD
              || failure.failure() == SnapshotCache.Failure.INCONSISTENT_REQUEST)) throw failure;
      if (!(cause instanceof Bypass) && !(cause instanceof SnapshotCache.CacheException)) {
        if (cause instanceof RuntimeException runtime) throw runtime;
        if (cause instanceof Error fatal) throw fatal;
        throw failed;
      }
    }
    // Only pure repository projections are repeated here. Provider work, authorization admission,
    // mutation decisions and per-reader auditing are outside this capability and never replayed.
    return uncachedLoader.get();
  }

  public void close() {
    closed = true;
    readyGeneration = -1;
    try {
      subscription.close();
    } finally {
      cache.close();
    }
  }
}
