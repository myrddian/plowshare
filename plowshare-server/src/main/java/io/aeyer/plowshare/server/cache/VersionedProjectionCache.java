package io.aeyer.plowshare.server.cache;

import static io.aeyer.plowshare.server.cache.ProjectionCacheStatistics.Event.*;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Versions decide whether a value may be stored and, by default, whether it may be disclosed.
 * Inline execution preserves caller-bound database locks. Transaction-local dirty reads are never
 * shared, including a loader that dirties its transaction after initially passing the eligibility
 * check.
 *
 * <h2>Notification-trusted hits</h2>
 *
 * <p>A cache built with a {@code notifiedStaleness} also answers repeat reads from memory, with no
 * SQL, while the database's own notifications prove the value has not changed. Such a hit requires
 * all of the following, and otherwise takes the fully versioned path:
 *
 * <ul>
 *   <li>the listener is ready and {@link ProjectionChangeSignals#deliveredThrough} is no older than
 *       {@code notifiedStaleness}: every change committed before then has reached this cache;
 *   <li>no change to a watched domain and no listener state change has arrived since the value
 *       passed its own version check;
 *   <li>the caller has no transaction that could hold its own uncommitted writes: none, or a
 *       read-only READ COMMITTED one;
 *   <li>the stored token's authority window still covers now plus {@code notifiedStaleness}, so an
 *       expiry, including a little database clock skew, is decided by SQL.
 * </ul>
 *
 * <p>A trusted hit can therefore be at most {@code notifiedStaleness} behind a committed
 * revocation. It never loads, publishes or extends a value; values still enter only through the
 * versioned path. This is an explicit, bounded relaxation of the "no stale allow" rule, adopted per
 * cache.
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
  private final ProjectionChangeSignals signals;
  private final long notifiedNanos;
  private final Duration margin;
  // Moves on every watched change and listener transition; trusted entries record the value seen
  // before their versioned read began.
  private final AtomicLong changes = new AtomicLong();
  private final ConcurrentHashMap<SnapshotCache.Key, Trusted> trusted = new ConcurrentHashMap<>();
  private final ProjectionCacheStatistics statistics = new ProjectionCacheStatistics();

  ProjectionCacheStatistics statistics() {
    return statistics;
  }

  /** A value that passed its version check at {@code changes == epoch}. Holds no DTO. */
  private record Trusted(ProjectionVersions.Token token, long epoch) {}

  // Tokens are small, but a token may list many pending transactions; keep only modest ones.
  private static final int TRUSTED_ENTRIES = 256;
  private static final int TRUSTED_PENDING = 64;

  public VersionedProjectionCache(
      SnapshotCache<Versioned<T>> cache,
      ProjectionVersions versions,
      ProjectionChangeSignals signals,
      Set<ProjectionVersions.Domain> domains,
      long maxWeight) {
    this(cache, versions, signals, domains, maxWeight, null);
  }

  /** {@code notifiedStaleness} null keeps every disclosure on the versioned path. */
  public VersionedProjectionCache(
      SnapshotCache<Versioned<T>> cache,
      ProjectionVersions versions,
      ProjectionChangeSignals signals,
      Set<ProjectionVersions.Domain> domains,
      long maxWeight,
      Duration notifiedStaleness) {
    if (notifiedStaleness != null
        && (notifiedStaleness.isNegative()
            || notifiedStaleness.isZero()
            || notifiedStaleness.compareTo(Duration.ofMinutes(1)) > 0))
      throw new IllegalArgumentException("Notified staleness must be positive and at most 1m");
    this.signals = Objects.requireNonNull(signals);
    this.notifiedNanos = notifiedStaleness == null ? 0 : notifiedStaleness.toNanos();
    this.margin = notifiedStaleness;
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
                // Retire trusted hits before the stored values, so no hit sees an old epoch.
                forget();
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
    forget();
    drainTransitions();
  }

  private void forget() {
    changes.incrementAndGet();
    trusted.clear();
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
    if (!ready()) return bypass(BYPASS_NOT_READY, uncachedLoader);
    if (!cacheable) return bypass(BYPASS_NOT_CACHEABLE, uncachedLoader);
    if (notifiedNanos > 0) {
      var hit = trustedHit(key);
      if (hit != null) {
        statistics.record(TRUSTED_HIT);
        return hit;
      }
    }
    long epoch = changes.get();
    var captured = versions.capture(domains);
    if (captured.isEmpty()) return bypass(BYPASS_INELIGIBLE, uncachedLoader);
    var token = captured.orElseThrow();
    var request =
        new SnapshotCache.Request(
            key,
            token.sourceVersion(),
            token.dependencies(),
            Math.addExact(maxWeight, TOKEN_RESERVATION));
    // readInline elects its loader on this caller's thread. A joining caller does not set this
    // flag, so a successful coalesced return counts as reuse, not another physical load.
    boolean[] loaded = {false};
    var fallback = BYPASS_STALE;
    try (var read =
        cache.readInline(
            request,
            () -> {
              loaded[0] = true;
              statistics.record(LOAD);
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
      if (ready() && versions.current(stored.token())) {
        if (notifiedNanos > 0) remember(key, stored.token(), epoch);
        statistics.record(loaded[0] ? LOADED_RETURN : VERSIONED_HIT);
        return stored.value();
      }
      cache.invalidate(key);
    } catch (CompletionException failed) {
      Throwable cause = failed.getCause();
      // A cache capacity bound must also bound database demand. Bypassing it with a direct read
      // creates one new SQL load per rejected waiter. This is not a transport admission refusal:
      // an enclosing mutation may already have completed, so its client must not auto-replay it.
      if (cause instanceof SnapshotCache.CacheException failure
          && failure.failure() == SnapshotCache.Failure.CAPACITY) {
        statistics.record(CAPACITY_REJECTED);
        throw new io.aeyer.plowshare.server.reads.ReadUnavailableException();
      }
      if (cause instanceof SnapshotCache.CacheException failure
          && (failure.failure() == SnapshotCache.Failure.REENTRANT_LOAD
              || failure.failure() == SnapshotCache.Failure.INCONSISTENT_REQUEST)) throw failure;
      if (!(cause instanceof Bypass) && !(cause instanceof SnapshotCache.CacheException)) {
        if (cause instanceof RuntimeException runtime) throw runtime;
        if (cause instanceof Error fatal) throw fatal;
        throw failed;
      }
      fallback = cause instanceof Bypass ? BYPASS_LOAD_REFUSED : BYPASS_CACHE_REFUSED;
    }
    // Only pure repository projections are repeated here. Provider work, authorization admission,
    // mutation decisions and per-reader auditing are outside this capability and never replayed.
    return bypass(fallback, uncachedLoader);
  }

  private T bypass(ProjectionCacheStatistics.Event reason, Supplier<T> uncachedLoader) {
    // Count the fallback attempt even if the owner's ordinary SQL path fails. Some owners use
    // an unavailable marker here and decide their SQL fallback later; this is not a SQL count.
    statistics.record(reason);
    return uncachedLoader.get();
  }

  private void remember(SnapshotCache.Key key, ProjectionVersions.Token token, long epoch) {
    if (token.pendingTransactions().size() > TRUSTED_PENDING) return;
    if (trusted.size() >= TRUSTED_ENTRIES) trusted.clear();
    trusted.put(key, new Trusted(token, epoch));
    // A change arriving during the put may have cleared the map before this entry landed.
    if (changes.get() != epoch) trusted.remove(key);
  }

  /** The stored value when memory may answer without SQL, or null to take the versioned path. */
  private T trustedHit(SnapshotCache.Key key) {
    var entry = trusted.get(key);
    if (entry == null || entry.epoch() != changes.get()) return null;
    var delivered = signals.deliveredThrough();
    if (delivered.isEmpty() || System.nanoTime() - delivered.getAsLong() > notifiedNanos)
      return null;
    if (!callerHasNoOwnWrites()) return null;
    var token = entry.token();
    var now = java.time.Instant.now();
    if (token.windowStart() != null && now.isBefore(token.windowStart().plus(margin))) return null;
    if (token.validUntil() != null && !now.plus(margin).isBefore(token.validUntil())) return null;
    var request =
        new SnapshotCache.Request(
            key,
            token.sourceVersion(),
            token.dependencies(),
            Math.addExact(maxWeight, TOKEN_RESERVATION));
    // An evicted value is not reloaded here: the loader refuses, and the versioned path loads it.
    try (var read =
        cache.readInline(
            request,
            () -> {
              throw new Bypass();
            })) {
      var stored = read.result().toCompletableFuture().join().value();
      if (!stored.token().equals(token) || entry.epoch() != changes.get() || !ready()) return null;
      return stored.value();
    } catch (CompletionException | SnapshotCache.CacheException refused) {
      return null;
    }
  }

  /**
   * Spring-managed transactions are the only way server code holds a database transaction open
   * across statements. Outside one, every statement commits on its own, so the caller cannot see
   * writes the cache has not. A read-only READ COMMITTED transaction cannot write.
   */
  private static boolean callerHasNoOwnWrites() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) return true;
    if (!TransactionSynchronizationManager.isCurrentTransactionReadOnly()) return false;
    Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
    return isolation == null || isolation == java.sql.Connection.TRANSACTION_READ_COMMITTED;
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
