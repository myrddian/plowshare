package io.aeyer.plowshare.server.cache;

import static io.aeyer.plowshare.server.cache.CacheTestSupport.namespace;
import static io.aeyer.plowshare.server.cache.ProjectionCacheStatistics.Event.LOAD;
import static io.aeyer.plowshare.server.cache.ProjectionCacheStatistics.Event.TRUSTED_HIT;
import static io.aeyer.plowshare.server.cache.ProjectionCacheStatistics.Event.VERSIONED_HIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.cache.CacheTestSupport.Value;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

/**
 * Item-scoped projections: a keyed change invalidates only the entries that read the changed item,
 * every other entry keeps serving hits, and the fallback item, listener recovery and domain-scoped
 * projections keep their whole-projection behaviour. Versions are an in-memory fake of the item
 * counters; the real triggers are covered by {@code ItemProjectionVersionsDatabaseTest}.
 */
class ItemProjectionCacheTest {
  private static final ProjectionVersions.Domain READABILITY =
      ProjectionVersions.Domain.READABILITY;
  private static final Set<ProjectionVersions.Domain> DOMAINS = Set.of(READABILITY);
  private static final ProjectionVersions.Item TREE_A = item("conversation:root-a");
  private static final ProjectionVersions.Item TREE_B = item("conversation:root-b");
  private static final SnapshotCache.Key KEY_A = new SnapshotCache.Key("log-readable", "root-a");
  private static final SnapshotCache.Key KEY_B = new SnapshotCache.Key("log-readable", "root-b");

  private static final SnapshotCache.Limits LIMITS =
      new SnapshotCache.Limits(8, 2_000_000, 4, 4_000_000, 16, 8, 8, 64, Duration.ofSeconds(30));

  private static ProjectionVersions.Item item(String address) {
    return new ProjectionVersions.Item(READABILITY, address);
  }

  /** Item counters as the triggers keep them: a write moves the counters of its own items. */
  private static final class Counters implements ProjectionItemVersions {
    final UUID database = new UUID(0, 7);
    final ConcurrentHashMap<ProjectionVersions.Item, Long> values = new ConcurrentHashMap<>();
    final AtomicLong allocations = new AtomicLong();
    final AtomicInteger captures = new AtomicInteger();
    final AtomicInteger checks = new AtomicInteger();

    void write(ProjectionVersions.Item item) {
      values.put(item, allocations.incrementAndGet());
    }

    @Override
    public Optional<ItemToken> captureItems(Set<ProjectionVersions.Item> items) {
      captures.incrementAndGet();
      var counters = new HashMap<ProjectionVersions.Item, Long>();
      for (var item : items) {
        counters.put(item, values.getOrDefault(item, 0L));
        var all = ProjectionVersions.Item.all(item.domain());
        counters.put(all, values.getOrDefault(all, 0L));
      }
      return Optional.of(new ItemToken(database, counters));
    }

    @Override
    public boolean currentItems(ItemToken token) {
      checks.incrementAndGet();
      return token.counters().entrySet().stream()
          .allMatch(entry -> values.getOrDefault(entry.getKey(), 0L).equals(entry.getValue()));
    }

    final AtomicInteger confirmations = new AtomicInteger();

    @Override
    public Set<ProjectionVersions.Item> staleItems(Collection<ItemToken> tokens) {
      confirmations.incrementAndGet();
      var stale = new HashSet<ProjectionVersions.Item>();
      for (var token : tokens)
        token
            .counters()
            .forEach(
                (item, counter) -> {
                  if (!values.getOrDefault(item, 0L).equals(counter)) stale.add(item);
                });
      return stale;
    }

    @Override
    public boolean canShare() {
      return true;
    }
  }

  private static final class Fixture implements AutoCloseable {
    final Counters counters = new Counters();
    final VersionedProjectionCacheTest.Signals signals = new VersionedProjectionCacheTest.Signals();
    final BoundedSnapshotCache<VersionedProjectionCache.Versioned<Value>> backing =
        new BoundedSnapshotCache<>(
            namespace(), LIMITS, Runnable::run, new CacheTestSupport.ManualTimer(), Runnable::run);
    final Map<SnapshotCache.Key, AtomicInteger> loads = new ConcurrentHashMap<>();
    final VersionedProjectionCache<Value> cache;

    Fixture(Duration staleness) {
      cache =
          VersionedProjectionCache.itemScoped(
              backing, counters, signals, DOMAINS, 1_000, staleness, Duration.ofSeconds(2));
    }

    Fixture() {
      this(null);
    }

    Value read(SnapshotCache.Key key, ProjectionVersions.Item item) {
      return cache.read(
          key,
          Set.of(item),
          true,
          () ->
              new Value(
                  key.selection()
                      + "#"
                      + loads
                          .computeIfAbsent(key, ignored -> new AtomicInteger())
                          .incrementAndGet()),
          () -> fail("A cacheable read must not bypass the cache"),
          ignored -> 100);
    }

    int loads(SnapshotCache.Key key) {
      return loads.getOrDefault(key, new AtomicInteger()).get();
    }

    void notifyItems(ProjectionVersions.Item... items) {
      signals.observers.forEach(observer -> observer.itemsChanged(Set.of(items)));
    }

    public void close() {
      cache.close();
    }
  }

  @Test
  void aWriteToOneConversationTreeLeavesAnotherTreesEntryInPlaceAndServedAsAHit() {
    try (var f = new Fixture()) {
      assertEquals(new Value("root-a#1"), f.read(KEY_A, TREE_A));
      assertEquals(new Value("root-b#1"), f.read(KEY_B, TREE_B));
      assertEquals(2, f.backing.statistics().entries());

      // An agent step in tree A commits: its counter moves and its keyed hint arrives.
      f.counters.write(TREE_A);
      f.notifyItems(TREE_A);

      assertEquals(1, f.backing.statistics().entries(), "Only tree A's entry is dropped");
      assertEquals(new Value("root-b#1"), f.read(KEY_B, TREE_B));
      assertEquals(1, f.loads(KEY_B), "Tree B is still served from memory");
      assertEquals(1, f.cache.statistics().count(VERSIONED_HIT));
      assertEquals(new Value("root-a#2"), f.read(KEY_A, TREE_A));
      assertEquals(2, f.loads(KEY_A));
    }
  }

  @Test
  void aCommittedWriteIsSeenOnTheVersionedPathEvenBeforeItsHintArrives() {
    try (var f = new Fixture()) {
      f.read(KEY_A, TREE_A);
      f.read(KEY_B, TREE_B);
      // Another process committed; this process has not been notified yet.
      f.counters.write(TREE_A);
      assertEquals(new Value("root-a#2"), f.read(KEY_A, TREE_A), "Read-your-writes");
      assertEquals(new Value("root-b#1"), f.read(KEY_B, TREE_B));
      assertEquals(1, f.loads(KEY_B));
    }
  }

  private static Optional<ItemProjectionCache.Unconfirmed<Value>> unconfirmed(
      Fixture f, SnapshotCache.Key key, ProjectionVersions.Item item) {
    return f.cache.readUnconfirmed(
        key,
        Set.of(item),
        () ->
            new Value(
                key.selection()
                    + "#"
                    + f.loads
                        .computeIfAbsent(key, ignored -> new AtomicInteger())
                        .incrementAndGet()),
        ignored -> 100);
  }

  @Test
  void unconfirmedReadsServeStoredValuesWithoutAnyVersionStatement() {
    try (var f = new Fixture()) {
      var first = unconfirmed(f, KEY_A, TREE_A).orElseThrow();
      unconfirmed(f, KEY_B, TREE_B).orElseThrow();
      int captures = f.counters.captures.get();
      var again = unconfirmed(f, KEY_A, TREE_A).orElseThrow();
      assertEquals(first, again);
      assertEquals(1, f.loads(KEY_A));
      assertEquals(captures, f.counters.captures.get(), "A stored entry needs no capture");
      assertEquals(0, f.counters.checks.get(), "Nor a per-entry version check");
      assertEquals(
          Set.of(), f.counters.staleItems(List.of(first.items(), again.items())), "Confirmed");
    }
  }

  @Test
  void aWriteCommittedAfterTheLoadIsReportedAndInvalidatesOnlyItsOwnEntry() {
    try (var f = new Fixture()) {
      var a = unconfirmed(f, KEY_A, TREE_A).orElseThrow();
      var b = unconfirmed(f, KEY_B, TREE_B).orElseThrow();
      // Another process commits a write to tree A; no hint has arrived here yet.
      f.counters.write(TREE_A);
      var stale = f.counters.staleItems(List.of(a.items(), b.items()));
      assertEquals(Set.of(TREE_A), stale);
      stale.forEach(f.cache::invalidate);
      assertEquals(1, f.backing.statistics().entries(), "Tree B stays");
      assertEquals(new Value("root-b#1"), unconfirmed(f, KEY_B, TREE_B).orElseThrow().value());
      var reloaded = unconfirmed(f, KEY_A, TREE_A).orElseThrow();
      assertEquals(new Value("root-a#2"), reloaded.value());
      assertEquals(Set.of(), f.counters.staleItems(List.of(reloaded.items())));
    }
  }

  @Test
  void unconfirmedReadsDeclineWhileTheListenerIsNotReady() {
    try (var f = new Fixture()) {
      f.signals.state(2, false);
      assertTrue(unconfirmed(f, KEY_A, TREE_A).isEmpty());
      assertEquals(0, f.loads(KEY_A));
    }
  }

  @Test
  void theFanOutFallbackClearsTheWholeProjection() {
    try (var f = new Fixture()) {
      f.read(KEY_A, TREE_A);
      f.read(KEY_B, TREE_B);
      var all = ProjectionVersions.Item.all(READABILITY);
      f.counters.write(all);
      f.notifyItems(all);
      assertEquals(0, f.backing.statistics().entries());
      assertEquals(new Value("root-b#2"), f.read(KEY_B, TREE_B));
    }
  }

  @Test
  void aMissedFallbackHintIsStillCaughtByTheEntrysFallbackCounter() {
    try (var f = new Fixture()) {
      f.read(KEY_B, TREE_B);
      f.counters.write(ProjectionVersions.Item.all(READABILITY));
      assertEquals(new Value("root-b#2"), f.read(KEY_B, TREE_B));
    }
  }

  @Test
  void domainHintsDoNotClearAnItemScopedProjectionButListenerRecoveryDoes() {
    try (var f = new Fixture()) {
      f.read(KEY_A, TREE_A);
      f.signals.observers.forEach(observer -> observer.changed(DOMAINS));
      assertEquals(1, f.backing.statistics().entries());
      f.read(KEY_A, TREE_A);
      assertEquals(1, f.loads(KEY_A));

      f.signals.state(2, false);
      f.signals.state(3, true);
      assertEquals(0, f.backing.statistics().entries(), "Recovery cannot name changed items");
      assertEquals(new Value("root-a#2"), f.read(KEY_A, TREE_A));
    }
  }

  @Test
  void aKeyedHintRetiresOnlyTheTrustedHitsOfItsItems() {
    var delivered = new AtomicLong(System.nanoTime());
    var signals =
        new VersionedProjectionCacheTest.Signals() {
          @Override
          public OptionalLong deliveredThrough() {
            return OptionalLong.of(delivered.get());
          }
        };
    var counters = new Counters();
    var backing =
        new BoundedSnapshotCache<VersionedProjectionCache.Versioned<Value>>(
            namespace(), LIMITS, Runnable::run, new CacheTestSupport.ManualTimer(), Runnable::run);
    try (var cache =
        VersionedProjectionCache.itemScoped(
            backing,
            counters,
            signals,
            DOMAINS,
            1_000,
            Duration.ofSeconds(2),
            Duration.ofSeconds(2))) {
      var loads = new AtomicInteger();
      BiFunction<SnapshotCache.Key, ProjectionVersions.Item, Value> read =
          (key, item) ->
              cache.read(
                  key,
                  Set.of(item),
                  true,
                  () -> new Value(key.selection() + "#" + loads.incrementAndGet()),
                  () -> fail("Cacheable"),
                  ignored -> 100);
      read.apply(KEY_A, TREE_A);
      read.apply(KEY_B, TREE_B);
      int captures = counters.captures.get();
      delivered.set(System.nanoTime());
      read.apply(KEY_A, TREE_A);
      read.apply(KEY_B, TREE_B);
      assertEquals(2, cache.statistics().count(TRUSTED_HIT));
      assertEquals(captures, counters.captures.get(), "Trusted hits run no version SQL");

      counters.write(TREE_A);
      signals.observers.forEach(observer -> observer.itemsChanged(Set.of(TREE_A)));
      delivered.set(System.nanoTime());
      read.apply(KEY_B, TREE_B);
      assertEquals(3, cache.statistics().count(TRUSTED_HIT), "Tree B stays trusted");
      assertEquals(captures, counters.captures.get());
      read.apply(KEY_A, TREE_A);
      assertEquals(captures + 1, counters.captures.get(), "Tree A takes the versioned path");
      assertEquals(3, loads.get());
    }
  }

  @Test
  void domainScopedProjectionsIgnoreItemHintsAndStillClearOnDomainHints() {
    var versions = mock(ProjectionVersions.class);
    var token =
        new ProjectionVersions.Token(
            new UUID(0, 1), Map.of(READABILITY, 4L), List.of(), "999999", null, null);
    when(versions.capture(DOMAINS)).thenReturn(Optional.of(token));
    when(versions.canShare()).thenReturn(true);
    when(versions.current(any())).thenReturn(true);
    var signals = new VersionedProjectionCacheTest.Signals();
    var backing =
        new BoundedSnapshotCache<VersionedProjectionCache.Versioned<Value>>(
            namespace(), LIMITS, Runnable::run, new CacheTestSupport.ManualTimer(), Runnable::run);
    try (var cache = new VersionedProjectionCache<>(backing, versions, signals, DOMAINS, 1_000)) {
      cache.read(KEY_A, true, () -> new Value("a"), ignored -> 100);
      signals.observers.forEach(observer -> observer.itemsChanged(Set.of(TREE_A)));
      assertEquals(1, backing.statistics().entries(), "Item hints are not domain changes");
      signals.observers.forEach(observer -> observer.changed(DOMAINS));
      assertEquals(0, backing.statistics().entries(), "A domain hint still clears");
      assertThrows(
          IllegalStateException.class,
          () -> cache.read(KEY_A, Set.of(TREE_A), true, () -> new Value("a"), () -> null, v -> 1));
    }
  }

  @Test
  void itemScopedProjectionsRequireItemTrackedDomainsAndTheirOwnItems() {
    var counters = new Counters();
    var signals = new VersionedProjectionCacheTest.Signals();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            VersionedProjectionCache.<Value>itemScoped(
                new BoundedSnapshotCache<>(namespace(), LIMITS),
                counters,
                signals,
                Set.of(ProjectionVersions.Domain.AUTHORITY),
                1_000,
                null,
                Duration.ofSeconds(2)));
    try (var f = new Fixture()) {
      assertThrows(
          IllegalStateException.class,
          () -> f.cache.read(KEY_A, true, () -> new Value("a"), ignored -> 100));
      assertThrows(
          IllegalArgumentException.class,
          () -> f.cache.read(KEY_A, Set.of(), true, () -> new Value("a"), () -> null, v -> 1));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProjectionVersions.Item(ProjectionVersions.Domain.AUTHORITY, "scope:x"));
    for (String malformed : new String[] {"tree:x", "conversation:", "conversation:a|b", "*x"})
      assertThrows(IllegalArgumentException.class, () -> item(malformed));
  }

  @Test
  void anIneligibleCallerBypassesWithoutStoring() {
    var counters = mock(ProjectionItemVersions.class);
    when(counters.captureItems(any())).thenReturn(Optional.empty());
    var signals = new VersionedProjectionCacheTest.Signals();
    var backing =
        new BoundedSnapshotCache<VersionedProjectionCache.Versioned<Value>>(
            namespace(), LIMITS, Runnable::run, new CacheTestSupport.ManualTimer(), Runnable::run);
    try (var cache =
        VersionedProjectionCache.itemScoped(
            backing, counters, signals, DOMAINS, 1_000, null, Duration.ofSeconds(2))) {
      assertEquals(
          new Value("direct"),
          cache.read(
              KEY_A,
              Set.of(TREE_A),
              true,
              () -> fail("Dirty callers must not fill"),
              () -> new Value("direct"),
              ignored -> 100));
      assertEquals(0, backing.statistics().entries());
      verify(counters, never()).currentItems(any());
      verify(counters, times(1)).captureItems(any());
      assertEquals(0, cache.statistics().count(LOAD));
    }
  }
}
