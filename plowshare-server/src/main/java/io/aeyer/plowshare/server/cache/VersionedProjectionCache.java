package io.aeyer.plowshare.server.cache;

import static io.aeyer.plowshare.server.cache.ProjectionCacheStatistics.Event.BYPASS_CACHE_REFUSED;
import static io.aeyer.plowshare.server.cache.ProjectionCacheStatistics.Event.BYPASS_INELIGIBLE;
import static io.aeyer.plowshare.server.cache.ProjectionCacheStatistics.Event.BYPASS_LOAD_REFUSED;
import static io.aeyer.plowshare.server.cache.ProjectionCacheStatistics.Event.BYPASS_NOT_CACHEABLE;
import static io.aeyer.plowshare.server.cache.ProjectionCacheStatistics.Event.BYPASS_NOT_READY;
import static io.aeyer.plowshare.server.cache.ProjectionCacheStatistics.Event.BYPASS_STALE;
import static io.aeyer.plowshare.server.cache.ProjectionCacheStatistics.Event.CAPACITY_REJECTED;
import static io.aeyer.plowshare.server.cache.ProjectionCacheStatistics.Event.DEADLINE_REJECTED;
import static io.aeyer.plowshare.server.cache.ProjectionCacheStatistics.Event.LOAD;
import static io.aeyer.plowshare.server.cache.ProjectionCacheStatistics.Event.LOADED_RETURN;
import static io.aeyer.plowshare.server.cache.ProjectionCacheStatistics.Event.TRUSTED_HIT;
import static io.aeyer.plowshare.server.cache.ProjectionCacheStatistics.Event.VERSIONED_HIT;

import io.aeyer.plowshare.server.reads.ReadDeadline;
import io.aeyer.plowshare.server.reads.ReadUnavailableException;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Versions decide whether a value may be stored and, by default, whether it may be disclosed.
 * Inline execution preserves caller-bound database locks. Transaction-local dirty reads are never
 * shared, including a loader that dirties its transaction after initially passing the eligibility
 * check.
 *
 * <h2>Domain-scoped and item-scoped projections</h2>
 *
 * <p>A domain-scoped cache (the constructors) stores each value with a {@link
 * ProjectionVersions.Token} over whole domains; any change to a watched domain clears it. An
 * item-scoped cache ({@link #itemScoped}) stores each value with the counters of the {@link
 * ProjectionVersions.Item items} its loader reads (an {@link ProjectionItemVersions.ItemToken}). A
 * keyed notification invalidates only the entries that depend on a changed item ({@link
 * SnapshotCache#invalidate(SnapshotCache.Dependency)}) and retires only their trusted hits; a hit
 * validates only its own item counters. The domains' fallback item, a listener outage or recovery
 * still clears the whole projection, because they cannot say which items changed. Every domain of
 * an item-scoped cache must be {@link ProjectionVersions.Domain#itemTracked item-tracked}; its
 * domain-level hints are ignored, because the same commits also arrive as item hints.
 *
 * <h2>One deadline per read</h2>
 *
 * <p>Each read opens a {@link ReadDeadline} of {@code budget} when it reaches the cache. The FETCH
 * slot wait of an elected loader and the wait of a caller joined to another caller's reload both
 * draw on it. A caller still waiting at the deadline, or an elected loader whose load outlived it,
 * receives {@link ReadUnavailableException} and no uncached SQL load is started. The elected
 * loader's value is still stored and serves the next read.
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
 *   <li>no change to a watched domain (for an item-scoped cache: to one of the value's items) and
 *       no listener state change has arrived since the value passed its own version check;
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
public final class VersionedProjectionCache<T>
    implements ProjectionCache<T>, ItemProjectionCache<T>, AutoCloseable {
  /** A stored value and the evidence it passed: a domain token or item counters, never both. */
  record Versioned<T>(
      ProjectionVersions.Token token, ProjectionItemVersions.ItemToken items, T value) {
    Versioned {
      if ((token == null) == (items == null))
        throw new IllegalArgumentException("Exactly one kind of version evidence required");
      Objects.requireNonNull(value);
    }
  }

  /** The default single deadline of one read; production supplies its configured budget. */
  static final Duration DEFAULT_BUDGET = Duration.ofSeconds(2);

  private static final long TOKEN_RESERVATION = 2048L + 128L * 4096;
  private static final long ITEM_RESERVATION = 2048L + 512L * ProjectionItemVersions.MAX_ITEMS;
  private final SnapshotCache<Versioned<T>> cache;
  private final ProjectionVersions versions;
  private final ProjectionItemVersions itemVersions;
  private final Set<ProjectionVersions.Domain> domains;
  private final long maxWeight;
  private final Duration budget;
  private final ProjectionChangeSignals.Subscription subscription;
  private final AtomicReference<ProjectionChangeSignals.State> state = new AtomicReference<>();
  private final AtomicBoolean transitioning = new AtomicBoolean();
  private volatile long readyGeneration = -1;
  private volatile boolean closed;
  private final ProjectionChangeSignals signals;
  private final long notifiedNanos;
  private final Duration margin;
  // Moves on every domain-wide change and listener transition; trusted entries record the value
  // seen before their versioned read began.
  private final AtomicLong changes = new AtomicLong();
  // Item-scoped only: the sequence of item notifications, and the latest sequence at which each
  // recently notified item changed. Bounded: past ITEM_CHANGES the map is dropped together with
  // every trusted entry (see forget), so a forgotten item can never vouch for a stale hit.
  private final AtomicLong itemSequence = new AtomicLong();
  private final ConcurrentHashMap<SnapshotCache.Dependency, Long> itemChanged =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<SnapshotCache.Key, Trusted> trusted = new ConcurrentHashMap<>();
  private final ProjectionCacheStatistics statistics = new ProjectionCacheStatistics();

  ProjectionCacheStatistics statistics() {
    return statistics;
  }

  /**
   * A value that passed its version check at {@code changes == epoch} and, for an item-scoped
   * value, {@code itemSequence == itemEpoch}. Holds no DTO.
   */
  private record Trusted(
      ProjectionVersions.Token token,
      ProjectionItemVersions.ItemToken items,
      long epoch,
      long itemEpoch) {}

  // Tokens are small, but a token may list many pending transactions; keep only modest ones.
  private static final int TRUSTED_ENTRIES = 256;
  private static final int TRUSTED_PENDING = 64;
  private static final int ITEM_CHANGES = 4096;

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
    this(cache, versions, signals, domains, maxWeight, notifiedStaleness, DEFAULT_BUDGET);
  }

  /** Domain-scoped cache whose reads each have one deadline of {@code budget}. */
  public VersionedProjectionCache(
      SnapshotCache<Versioned<T>> cache,
      ProjectionVersions versions,
      ProjectionChangeSignals signals,
      Set<ProjectionVersions.Domain> domains,
      long maxWeight,
      Duration notifiedStaleness,
      Duration budget) {
    this(
        cache,
        Objects.requireNonNull(versions),
        null,
        signals,
        domains,
        maxWeight,
        notifiedStaleness,
        budget);
  }

  /**
   * An item-scoped cache over item-tracked {@code domains}. Its values are read only through {@link
   * #read(SnapshotCache.Key, Set, boolean, Supplier, Supplier, ToLongFunction)}.
   */
  public static <T> VersionedProjectionCache<T> itemScoped(
      SnapshotCache<Versioned<T>> cache,
      ProjectionItemVersions items,
      ProjectionChangeSignals signals,
      Set<ProjectionVersions.Domain> domains,
      long maxWeight,
      Duration notifiedStaleness,
      Duration budget) {
    Objects.requireNonNull(items);
    if (domains == null || domains.stream().anyMatch(domain -> !domain.itemTracked()))
      throw new IllegalArgumentException("Item-scoped projections need item-tracked domains");
    return new VersionedProjectionCache<>(
        cache, null, items, signals, domains, maxWeight, notifiedStaleness, budget);
  }

  private VersionedProjectionCache(
      SnapshotCache<Versioned<T>> cache,
      ProjectionVersions versions,
      ProjectionItemVersions itemVersions,
      ProjectionChangeSignals signals,
      Set<ProjectionVersions.Domain> domains,
      long maxWeight,
      Duration notifiedStaleness,
      Duration budget) {
    if (notifiedStaleness != null
        && (notifiedStaleness.isNegative()
            || notifiedStaleness.isZero()
            || notifiedStaleness.compareTo(Duration.ofMinutes(1)) > 0))
      throw new IllegalArgumentException("Notified staleness must be positive and at most 1m");
    Objects.requireNonNull(budget);
    if (budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofMinutes(1)) > 0)
      throw new IllegalArgumentException("Read budget must be positive and at most 1m");
    this.signals = Objects.requireNonNull(signals);
    this.notifiedNanos = notifiedStaleness == null ? 0 : notifiedStaleness.toNanos();
    this.margin = notifiedStaleness;
    this.cache = Objects.requireNonNull(cache);
    this.versions = versions;
    this.itemVersions = itemVersions;
    this.budget = budget;
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
                // An item-scoped projection receives the same commits as item hints, including
                // the fallback item for changes its triggers cannot address; a domain hint alone
                // would only repeat them as a whole-projection clear.
                if (itemVersions != null) return;
                // Retire trusted hits before the stored values, so no hit sees an old epoch.
                forget();
                cache.invalidateAll();
              }

              public void itemsChanged(Set<ProjectionVersions.Item> items) {
                if (itemVersions != null) itemChanges(items);
              }
            });
  }

  private void itemChanges(Set<ProjectionVersions.Item> items) {
    if (items.stream().anyMatch(ProjectionVersions.Item::isAll)) {
      forget();
      cache.invalidateAll();
      return;
    }
    if (itemChanged.size() + items.size() > ITEM_CHANGES) forget();
    // Mark the items changed before dropping their entries, so no trusted hit of an entry that
    // is about to go can pass its item check in between.
    for (var item : items) {
      long sequence = itemSequence.incrementAndGet();
      itemChanged.merge(item.dependency(), sequence, Math::max);
    }
    for (var item : items) cache.invalidate(item.dependency());
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

  /** Retires every trusted hit. The epoch moves first, so a concurrent check cannot pass. */
  private void forget() {
    changes.incrementAndGet();
    trusted.clear();
    itemChanged.clear();
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
    if (versions == null) throw new IllegalStateException("Item-scoped projection needs items");
    return ReadDeadline.within(
        budget, () -> readDomains(key, cacheable, boundedLoader, uncachedLoader, weight));
  }

  @Override
  public T read(
      SnapshotCache.Key key,
      Set<ProjectionVersions.Item> items,
      boolean cacheable,
      Supplier<T> boundedLoader,
      Supplier<T> uncachedLoader,
      ToLongFunction<T> weight) {
    Objects.requireNonNull(key);
    Objects.requireNonNull(boundedLoader);
    Objects.requireNonNull(uncachedLoader);
    Objects.requireNonNull(weight);
    if (itemVersions == null) throw new IllegalStateException("Domain-scoped projection");
    var selected = Set.copyOf(items);
    if (selected.isEmpty() || selected.stream().anyMatch(item -> !domains.contains(item.domain())))
      throw new IllegalArgumentException("Items must belong to this projection's domains");
    return ReadDeadline.within(
        budget, () -> readItems(key, selected, cacheable, boundedLoader, uncachedLoader, weight));
  }

  private T readDomains(
      SnapshotCache.Key key,
      boolean cacheable,
      Supplier<T> boundedLoader,
      Supplier<T> uncachedLoader,
      ToLongFunction<T> weight) {
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
    return shared(
        request(key, token, null),
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
              new Versioned<>(token, null, value),
              charged + 2048L + 128L * token.pendingTransactions().size());
        },
        // Pending transactions are evidence attached to the value, not part of its allocation
        // counter identity. Validating the newly captured token here would launder an old value
        // after a previously pending writer committed without another counter allocation.
        // Hits/joiners execute no caller-side SQL after capture. The elected loader checks its
        // dirty state before publication; expiry is covered by the stored token's time window.
        stored -> versions.current(stored.token()),
        new Trusted(null, null, epoch, 0),
        uncachedLoader);
  }

  private T readItems(
      SnapshotCache.Key key,
      Set<ProjectionVersions.Item> items,
      boolean cacheable,
      Supplier<T> boundedLoader,
      Supplier<T> uncachedLoader,
      ToLongFunction<T> weight) {
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
    long itemEpoch = itemSequence.get();
    var captured = itemVersions.captureItems(items);
    if (captured.isEmpty()) return bypass(BYPASS_INELIGIBLE, uncachedLoader);
    var token = captured.orElseThrow();
    return shared(
        request(key, null, token),
        () -> {
          T value = Objects.requireNonNull(boundedLoader.get());
          long charged = weight.applyAsLong(value);
          // Counters are transactional, so equality after the load proves no write to these
          // items committed since the capture that preceded it.
          if (charged < 1
              || charged > maxWeight
              || !itemVersions.canShare()
              || !itemVersions.currentItems(token)) throw new Bypass();
          return new SnapshotCache.Loaded<>(
              token.sourceVersion(),
              new Versioned<>(null, token, value),
              charged + 2048L + 512L * token.counters().size());
        },
        // Read-your-writes on any process: a hit compares its own items' committed counters.
        stored -> itemVersions.currentItems(stored.items()),
        new Trusted(null, null, epoch, itemEpoch),
        uncachedLoader);
  }

  private SnapshotCache.Request request(
      SnapshotCache.Key key,
      ProjectionVersions.Token token,
      ProjectionItemVersions.ItemToken items) {
    return token != null
        ? new SnapshotCache.Request(
            key,
            token.sourceVersion(),
            token.dependencies(),
            Math.addExact(maxWeight, TOKEN_RESERVATION))
        : new SnapshotCache.Request(
            key,
            items.sourceVersion(),
            items.dependencies(),
            Math.addExact(maxWeight, ITEM_RESERVATION));
  }

  /**
   * Reads through the backing cache, electing this caller as inline loader on a miss, and returns
   * the stored value only if {@code current} still holds for its own evidence. {@code seen} carries
   * the epochs observed before capture, for the trusted bookkeeping of the returned value.
   */
  private T shared(
      SnapshotCache.Request request,
      SnapshotCache.Loader<Versioned<T>> loader,
      Predicate<Versioned<T>> current,
      Trusted seen,
      Supplier<T> uncachedLoader) {
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
              return loader.load();
            })) {
      var stored = await(read, loaded[0]).value();
      if (ready() && current.test(stored)) {
        if (notifiedNanos > 0)
          remember(
              request.key(),
              new Trusted(stored.token(), stored.items(), seen.epoch(), seen.itemEpoch()));
        statistics.record(loaded[0] ? LOADED_RETURN : VERSIONED_HIT);
        return stored.value();
      }
      cache.invalidate(request.key());
    } catch (CompletionException failed) {
      Throwable cause = failed.getCause();
      // A cache capacity bound must also bound database demand. Bypassing it with a direct read
      // creates one new SQL load per rejected waiter. This is not a transport admission refusal:
      // an enclosing mutation may already have completed, so its client must not auto-replay it.
      if (cause instanceof SnapshotCache.CacheException failure
          && failure.failure() == SnapshotCache.Failure.CAPACITY) {
        statistics.record(CAPACITY_REJECTED);
        throw new ReadUnavailableException();
      }
      // The backing cache's own wait bound. Like the read's deadline, it is a busy refusal: a
      // direct read here would add a load beside the one this caller was waiting for.
      if (cause instanceof TimeoutException) throw deadlinePassed();
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

  /**
   * Waits for the read until the current {@link ReadDeadline}. An elected loader has already
   * finished when this runs; if its load outlived the deadline its value stays stored, but its
   * caller is refused like a waiting one, so a slow fill never stretches one request's budget.
   */
  private SnapshotCache.Tracked<Versioned<T>> await(
      SnapshotCache.Read<Versioned<T>> read, boolean elected) {
    long deadline = ReadDeadline.deadlineNanos(budget);
    if (elected && System.nanoTime() - deadline >= 0) throw deadlinePassed();
    var future = read.result().toCompletableFuture();
    if (future.isDone()) return future.join();
    try {
      return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    } catch (TimeoutException late) {
      // Caller-local: the elected loader and any other waiter keep the shared reload.
      read.cancel();
      throw deadlinePassed();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      read.cancel();
      throw new ReadUnavailableException();
    } catch (ExecutionException failed) {
      throw new CompletionException(failed.getCause());
    }
  }

  private ReadUnavailableException deadlinePassed() {
    statistics.record(DEADLINE_REJECTED);
    return new ReadUnavailableException();
  }

  private T bypass(ProjectionCacheStatistics.Event reason, Supplier<T> uncachedLoader) {
    // Count the fallback attempt even if the owner's ordinary SQL path fails. Some owners use
    // an unavailable marker here and decide their SQL fallback later; this is not a SQL count.
    statistics.record(reason);
    return uncachedLoader.get();
  }

  private void remember(SnapshotCache.Key key, Trusted entry) {
    if (entry.token() != null && entry.token().pendingTransactions().size() > TRUSTED_PENDING)
      return;
    if (trusted.size() >= TRUSTED_ENTRIES) trusted.clear();
    trusted.put(key, entry);
    // A change arriving during the put may have cleared the map before this entry landed.
    if (changes.get() != entry.epoch() || itemsChangedSince(entry)) trusted.remove(key, entry);
  }

  /** Whether an item of an item-scoped entry was notified after the entry's read began. */
  private boolean itemsChangedSince(Trusted entry) {
    if (entry.items() == null) return false;
    for (var item : entry.items().counters().keySet()) {
      Long changed = itemChanged.get(item.dependency());
      if (changed != null && changed > entry.itemEpoch()) return true;
    }
    return false;
  }

  /** The stored value when memory may answer without SQL, or null to take the versioned path. */
  private T trustedHit(SnapshotCache.Key key) {
    var entry = trusted.get(key);
    if (entry == null || entry.epoch() != changes.get()) return null;
    if (itemsChangedSince(entry)) {
      trusted.remove(key, entry);
      return null;
    }
    var delivered = signals.deliveredThrough();
    if (delivered.isEmpty() || System.nanoTime() - delivered.getAsLong() > notifiedNanos)
      return null;
    if (!callerHasNoOwnWrites()) return null;
    var token = entry.token();
    if (token != null) {
      var now = Instant.now();
      if (token.windowStart() != null && now.isBefore(token.windowStart().plus(margin)))
        return null;
      if (token.validUntil() != null && !now.plus(margin).isBefore(token.validUntil())) return null;
    }
    var request = request(key, token, entry.items());
    // An evicted value is not reloaded here: the loader refuses, and the versioned path loads it.
    try (var read =
        cache.readInline(
            request,
            () -> {
              throw new Bypass();
            })) {
      var stored = read.result().toCompletableFuture().join().value();
      if (!Objects.equals(stored.token(), token)
          || !Objects.equals(stored.items(), entry.items())
          || entry.epoch() != changes.get()
          || itemsChangedSince(entry)
          || !ready()) return null;
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
    return isolation == null || isolation == Connection.TRANSACTION_READ_COMMITTED;
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
