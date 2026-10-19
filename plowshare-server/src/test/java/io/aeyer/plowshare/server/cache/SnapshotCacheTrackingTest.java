package io.aeyer.plowshare.server.cache;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.information.InformationCatalogueRepository.AuthorizedText;
import io.aeyer.plowshare.server.information.InformationContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Synthetic owner versions exercise bookkeeping; production DTOs remain operation-local. */
class SnapshotCacheTrackingTest {
  private final List<Fixture> fixtures = new ArrayList<>();

  private static final class Fixture implements AutoCloseable {
    final CacheTestSupport.ManualTimer timer = new CacheTestSupport.ManualTimer();
    final BoundedSnapshotCache<String> cache;

    Fixture(SnapshotCache.Namespace namespace, SnapshotCache.Limits limits) {
      cache = new BoundedSnapshotCache<>(namespace, limits, Runnable::run, timer);
    }

    @Override
    public void close() {
      cache.close();
      timer.shutdownNow();
    }
  }

  @AfterEach
  void closeFixtures() {
    fixtures.forEach(Fixture::close);
  }

  private Fixture recovering(SnapshotCache.Namespace namespace, SnapshotCache.Limits limits) {
    var fixture = new Fixture(namespace, limits);
    fixtures.add(fixture);
    return fixture;
  }

  private Fixture ready(SnapshotCache.Limits limits) {
    return ready(CacheTestSupport.namespace(), limits);
  }

  private Fixture ready(SnapshotCache.Namespace namespace, SnapshotCache.Limits limits) {
    var fixture = recovering(namespace, limits);
    assertTrue(fixture.cache.completeRecovery(fixture.cache.beginRecovery()));
    return fixture;
  }

  private static SnapshotCache.Limits limits(
      int entries, long resident, long loading, int dependencies, int links) {
    return new SnapshotCache.Limits(
        entries, resident, 4, loading, 8, 4, dependencies, links, Duration.ofSeconds(30));
  }

  private static SnapshotCache.Request request(String identity) {
    return request(identity, "account-one", "fixture-v1", Set.of(), 10_000);
  }

  private static SnapshotCache.Request request(
      String identity,
      String selection,
      String version,
      Set<SnapshotCache.Dependency> dependencies,
      long reservation) {
    return new SnapshotCache.Request(
        new SnapshotCache.Key(identity, selection),
        new SnapshotCache.SourceVersion(version),
        dependencies,
        reservation);
  }

  private static SnapshotCache.Tracked<String> read(
      Fixture fixture, SnapshotCache.Request request, SnapshotCache.Loader<String> loader) {
    var result = fixture.cache.read(request, loader).result().toCompletableFuture();
    var tracked = assertDoesNotThrow(() -> result.get(5, TimeUnit.SECONDS));
    quiescent(fixture);
    return tracked;
  }

  private static SnapshotCache.Tracked<String> load(
      Fixture fixture, SnapshotCache.Request request, String value, long weight) {
    return read(
        fixture, request, () -> new SnapshotCache.Loaded<>(request.version(), value, weight));
  }

  private static SnapshotCache.Loader<String> mustNotLoad() {
    return () -> {
      throw new AssertionError("An admitted hit must reuse its tracked DTO");
    };
  }

  private static void failure(
      SnapshotCache.Failure expected, Fixture fixture, SnapshotCache.Read<?> read) {
    var failure =
        assertThrows(
            ExecutionException.class,
            () -> read.result().toCompletableFuture().get(5, TimeUnit.SECONDS));
    assertEquals(
        expected,
        assertInstanceOf(SnapshotCache.CacheException.class, failure.getCause()).failure());
    quiescent(fixture);
  }

  private static void quiescent(Fixture fixture) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      var statistics = fixture.cache.statistics();
      if (statistics.loads() == 0 && statistics.loadingWeight() == 0 && statistics.waiters() == 0)
        return;
      Thread.yield();
    }
    fail(
        "Completion delivery did not release its physical reservation: "
            + fixture.cache.statistics());
  }

  private static void empty(SnapshotCache.Statistics statistics) {
    assertEquals(0, statistics.entries());
    assertEquals(0, statistics.residentWeight());
    assertEquals(0, statistics.loads());
    assertEquals(0, statistics.loadingWeight());
    assertEquals(0, statistics.waiters());
    assertEquals(0, statistics.dependencyLinks());
  }

  @Test
  void hitsKeepTheTrackedSnapshotAndMoveTheEntryToTheRecentEnd() {
    var fixture = ready(limits(2, 100_000, 100_000, 4, 32));
    var a = request("a");
    var b = request("b");
    var c = request("c");
    var original = load(fixture, a, "retained-a", 100);
    load(fixture, b, "retained-b", 100);
    assertSame(original, read(fixture, a, mustNotLoad()));
    load(fixture, c, "retained-c", 100);
    assertSame(original, read(fixture, a, mustNotLoad()));
    assertEquals("retained-c", read(fixture, c, mustNotLoad()).value());
    assertEquals(2, fixture.cache.statistics().entries());
    assertEquals(1, fixture.cache.statistics().evictions());
    var reloads = new AtomicInteger();
    assertEquals(
        "reloaded-b",
        read(
                fixture,
                b,
                () -> {
                  reloads.incrementAndGet();
                  return new SnapshotCache.Loaded<>(b.version(), "reloaded-b", 100);
                })
            .value());
    assertEquals(1, reloads.get(), "b was oldest after the successful a hit");
    assertEquals("retained-a", original.value(), "Eviction cannot mutate a caller-held DTO");
    assertEquals(3, fixture.cache.statistics().hits());
  }

  @Test
  void oneWeightedInsertionCanEvictSeveralEntriesAndIncludesTrackingMetadata() {
    var fixture = ready(limits(10, 5_000, 100_000, 4, 32));
    var a = load(fixture, request("a"), "a", 1_000);
    var b = load(fixture, request("b"), "b", 1_000);
    assertTrue(a.weight() > 1_000, "Key/dependency/node metadata must be charged");
    assertEquals(a.weight() + b.weight(), fixture.cache.statistics().residentWeight());
    var c = load(fixture, request("c"), "c", 3_000);
    assertTrue(c.weight() <= 5_000);
    assertTrue(c.weight() + a.weight() > 5_000);
    assertTrue(c.weight() + b.weight() > 5_000);
    assertEquals(1, fixture.cache.statistics().entries());
    assertEquals(c.weight(), fixture.cache.statistics().residentWeight());
    assertEquals(2, fixture.cache.statistics().evictions());
    assertEquals(1, fixture.cache.statistics().dependencyLinks());
    assertEquals("a", a.value());
    assertEquals("b", b.value());
  }

  @Test
  void anOversizedResidentValueIsReturnedButNeverRetained() {
    var fixture = ready(limits(2, 128, 100_000, 4, 32));
    var request = request("large");
    var calls = new AtomicInteger();
    SnapshotCache.Loader<String> loader =
        () -> {
          calls.incrementAndGet();
          return new SnapshotCache.Loaded<>(request.version(), "large", 1_000);
        };
    assertEquals("large", read(fixture, request, loader).value());
    assertEquals("large", read(fixture, request, loader).value());
    assertEquals(2, calls.get());
    assertEquals(2, fixture.cache.statistics().bypassed());
    empty(fixture.cache.statistics());
  }

  @Test
  void aLoaderExceedingItsReservationFailsInsteadOfBypassingAsASuccessfulValue() {
    var fixture = ready(CacheTestSupport.limits());
    var request = request("too-large", "account-one", "fixture-v1", Set.of(), 100);
    failure(
        SnapshotCache.Failure.LOAD_TOO_LARGE,
        fixture,
        fixture.cache.read(
            request, () -> new SnapshotCache.Loaded<>(request.version(), "overshoot", 101)));
    empty(fixture.cache.statistics());
    assertEquals(0, fixture.cache.statistics().bypassed());
    assertEquals(1, fixture.cache.statistics().loadFailures());
  }

  @Test
  void loaderVersionMismatchCannotPopulateTheRequestedVersion() {
    var fixture = ready(CacheTestSupport.limits());
    var request = request("document");
    failure(
        SnapshotCache.Failure.VERSION_MISMATCH,
        fixture,
        fixture.cache.read(
            request,
            () ->
                new SnapshotCache.Loaded<>(
                    new SnapshotCache.SourceVersion("other-version"), "wrong", 100)));
    empty(fixture.cache.statistics());
    assertEquals("correct", load(fixture, request, "correct", 100).value());
    assertEquals(1, fixture.cache.statistics().loadFailures());
  }

  @Test
  void versionsIdentitiesSelectionsDatabasesAndProjectionsRemainSeparate() {
    var fixture = ready(limits(10, 100_000, 100_000, 4, 32));
    var original = request("same", "account-one", "v2", Set.of(), 100);
    var differentVersion = request("same", "account-one", "v10", Set.of(), 100);
    var differentSelection = request("same", "account-two", "v2", Set.of(), 100);
    var differentIdentity = request("other", "account-one", "v2", Set.of(), 100);
    var tracked = load(fixture, original, "original", 100);
    load(fixture, differentVersion, "different-version", 100);
    load(fixture, differentSelection, "different-selection", 100);
    load(fixture, differentIdentity, "different-identity", 100);
    assertSame(tracked, read(fixture, original, mustNotLoad()));
    assertEquals("different-version", read(fixture, differentVersion, mustNotLoad()).value());
    assertEquals("different-selection", read(fixture, differentSelection, mustNotLoad()).value());
    assertEquals("different-identity", read(fixture, differentIdentity, mustNotLoad()).value());
    var database =
        ready(
            new SnapshotCache.Namespace("second-database", "immutable-values-v1"),
            CacheTestSupport.limits());
    var projection =
        ready(
            new SnapshotCache.Namespace("database-fixture", "second-projection"),
            CacheTestSupport.limits());
    var databaseValue = load(database, original, "database", 100);
    var projectionValue = load(projection, original, "projection", 100);
    assertNotEquals(tracked.namespace(), databaseValue.namespace());
    assertNotEquals(tracked.namespace(), projectionValue.namespace());
    assertEquals("database", read(database, original, mustNotLoad()).value());
    assertEquals("projection", read(projection, original, mustNotLoad()).value());
    assertEquals(4, fixture.cache.statistics().entries());
  }

  @Test
  void dependencyInvalidationRemovesAllDependentKeysAndVersionsButKeepsOtherCollections() {
    var fixture = ready(limits(10, 100_000, 100_000, 4, 32));
    var changed = new SnapshotCache.Dependency("changed-collection");
    var untouched = new SnapshotCache.Dependency("untouched-collection");
    var a1 = request("a", "one", "v1", Set.of(changed), 100);
    var a2 = request("a", "one", "v2", Set.of(changed), 100);
    var b = request("b", "one", "v1", Set.of(changed), 100);
    var c = request("c", "one", "v1", Set.of(untouched), 100);
    var retained = load(fixture, a1, "a1", 100);
    load(fixture, a2, "a2", 100);
    load(fixture, b, "b", 100);
    var unaffected = load(fixture, c, "c", 100);
    fixture.cache.invalidate(changed);
    assertEquals(1, fixture.cache.statistics().entries());
    assertEquals(2, fixture.cache.statistics().dependencyLinks());
    assertSame(unaffected, read(fixture, c, mustNotLoad()));
    assertEquals("a1", retained.value());
    assertEquals("new-a1", load(fixture, a1, "new-a1", 100).value());
    assertEquals("new-a2", load(fixture, a2, "new-a2", 100).value());
    fixture.cache.invalidate(a1.key());
    assertEquals(
        1, fixture.cache.statistics().entries(), "Key invalidation removes both a versions");
    fixture.cache.invalidate(untouched);
    empty(fixture.cache.statistics());
  }

  @Test
  void dependencyAndReservationCapacityRefusalsDoNotCallLoadersOrRetainMetadata() {
    var fixture = ready(limits(2, 100_000, 100_000, 1, 4));
    var one = new SnapshotCache.Dependency("one");
    var two = new SnapshotCache.Dependency("two");
    var tooMany = request("many", "owner", "v1", Set.of(one, two), 100);
    failure(SnapshotCache.Failure.CAPACITY, fixture, fixture.cache.read(tooMany, mustNotLoad()));
    var unreservable = request("huge", "owner", "v1", Set.of(), Long.MAX_VALUE);
    failure(
        SnapshotCache.Failure.CAPACITY, fixture, fixture.cache.read(unreservable, mustNotLoad()));
    empty(fixture.cache.statistics());
    var a = request("a", "owner", "v1", Set.of(one), 100);
    load(fixture, a, "a", 100);
    load(fixture, request("b"), "b", 100);
    assertEquals(3, fixture.cache.statistics().dependencyLinks());
    var c = request("c", "owner", "v1", Set.of(two), 100);
    failure(SnapshotCache.Failure.CAPACITY, fixture, fixture.cache.read(c, mustNotLoad()));
    assertEquals(2, fixture.cache.statistics().entries());
    fixture.cache.invalidate(one);
    fixture.cache.invalidate(request("b").key());
    assertEquals("c", load(fixture, c, "c", 100).value());
    assertEquals(1, fixture.cache.statistics().entries());
    assertTrue(fixture.cache.statistics().dependencyLinks() <= 4);
    assertTrue(fixture.cache.statistics().residentWeight() <= 100_000);
  }

  @Test
  void aZeroDependencyLimitAllowsOnlyDependencyFreeSnapshots() {
    var fixture = ready(limits(2, 100_000, 100_000, 0, 4));
    assertEquals("plain", load(fixture, request("plain"), "plain", 100).value());
    var dependent =
        request(
            "dependent", "owner", "v1", Set.of(new SnapshotCache.Dependency("collection")), 100);
    failure(SnapshotCache.Failure.CAPACITY, fixture, fixture.cache.read(dependent, mustNotLoad()));
    assertEquals(1, fixture.cache.statistics().entries());
    assertEquals(1, fixture.cache.statistics().dependencyLinks());
  }

  @Test
  void retainedVersionCannotSilentlyChangeItsDependencySetOrLoadReservation() {
    var fixture = ready(CacheTestSupport.limits());
    var original = request("same");
    load(fixture, original, "same", 100);
    var changedDependencies =
        new SnapshotCache.Request(
            original.key(),
            original.version(),
            Set.of(new SnapshotCache.Dependency("new-dependency")),
            original.maxLoadWeight());
    var changedReservation =
        new SnapshotCache.Request(
            original.key(),
            original.version(),
            original.dependencies(),
            original.maxLoadWeight() - 1);
    failure(
        SnapshotCache.Failure.INCONSISTENT_REQUEST,
        fixture,
        fixture.cache.read(changedDependencies, mustNotLoad()));
    failure(
        SnapshotCache.Failure.INCONSISTENT_REQUEST,
        fixture,
        fixture.cache.read(changedReservation, mustNotLoad()));
    assertEquals("same", read(fixture, original, mustNotLoad()).value());
    assertEquals(1, fixture.cache.statistics().entries());
  }

  @Test
  void recoveryNeedsTheCurrentOwningTicketAndClearingDoesNotReopen() {
    var fixture = recovering(CacheTestSupport.namespace(), CacheTestSupport.limits());
    var request = request("document");
    failure(SnapshotCache.Failure.RECOVERING, fixture, fixture.cache.read(request, mustNotLoad()));
    fixture.cache.invalidateAll();
    assertTrue(fixture.cache.statistics().recovering());
    var first = fixture.cache.beginRecovery();
    var current = fixture.cache.beginRecovery();
    assertTrue(current.epoch() > first.epoch());
    assertFalse(fixture.cache.completeRecovery(first));
    var foreign = recovering(CacheTestSupport.namespace(), CacheTestSupport.limits());
    var foreignTicket = foreign.cache.beginRecovery();
    while (foreignTicket.epoch() < current.epoch()) foreignTicket = foreign.cache.beginRecovery();
    assertEquals(
        current.epoch(), foreignTicket.epoch(), "Ownership must be checked even when epochs match");
    assertFalse(fixture.cache.completeRecovery(foreignTicket));
    assertTrue(fixture.cache.completeRecovery(current));
    assertFalse(fixture.cache.completeRecovery(current), "Recovery completion is one-shot");
    var before = load(fixture, request, "before-outage", 100);
    var outage = fixture.cache.beginRecovery();
    assertTrue(fixture.cache.statistics().recovering());
    empty(fixture.cache.statistics());
    fixture.cache.invalidateAll();
    assertFalse(
        fixture.cache.completeRecovery(outage), "Clear invalidates an earlier baseline ticket");
    failure(SnapshotCache.Failure.RECOVERING, fixture, fixture.cache.read(request, mustNotLoad()));
    assertTrue(fixture.cache.completeRecovery(fixture.cache.beginRecovery()));
    var after = load(fixture, request, "after-recovery", 100);
    assertTrue(after.fence().epoch() > before.fence().epoch());
    assertTrue(after.fence().generation() > before.fence().generation());
  }

  @Test
  void normalInvalidationClearsValuesWithoutClosingAnAlreadyRecoveredNamespace() {
    var fixture = ready(CacheTestSupport.limits());
    var request = request("document");
    var first = load(fixture, request, "before", 100);
    fixture.cache.invalidateAll();
    assertFalse(fixture.cache.statistics().recovering());
    empty(fixture.cache.statistics());
    var next = load(fixture, request, "after", 100);
    assertTrue(next.fence().epoch() > first.fence().epoch());
  }

  @Test
  void closeClearsRetainedResourcesAndCannotBeReopenedByATicket() {
    var fixture = ready(CacheTestSupport.limits());
    var request = request("document");
    load(fixture, request, "value", 100);
    var ticket = fixture.cache.beginRecovery();
    fixture.cache.close();
    fixture.cache.close();
    assertTrue(fixture.cache.statistics().closed());
    assertTrue(fixture.cache.statistics().recovering());
    empty(fixture.cache.statistics());
    assertFalse(fixture.cache.completeRecovery(ticket));
    failure(SnapshotCache.Failure.CLOSED, fixture, fixture.cache.read(request, mustNotLoad()));
    var failure = assertThrows(SnapshotCache.CacheException.class, fixture.cache::beginRecovery);
    assertEquals(SnapshotCache.Failure.CLOSED, failure.failure());
  }

  @Test
  void repeatedAbsentAddressInvalidationDoesNotRetainIndexOrTombstoneResources() {
    var fixture = ready(CacheTestSupport.limits());
    long previous = fixture.cache.statistics().invalidations();
    for (int index = 0; index < 1_000; index++) {
      fixture.cache.invalidate(new SnapshotCache.Key("absent-" + index, "owner"));
      fixture.cache.invalidate(new SnapshotCache.Dependency("absent-dependency-" + index));
    }
    empty(fixture.cache.statistics());
    assertEquals(previous + 2_000, fixture.cache.statistics().invalidations());
    assertEquals("usable", load(fixture, request("usable"), "usable", 100).value());
    fixture.cache.invalidateAll();
    empty(fixture.cache.statistics());
  }

  @Test
  void trackingMetadataCopiesDependenciesAndDoesNotTurnAProductionDtoIntoSharedAdmission() {
    var dependencies = new HashSet<>(Set.of(new SnapshotCache.Dependency("fixture-source")));
    var request =
        request(
            "revision-fixture",
            "reader/personal/typed",
            "synthetic-owner-token",
            dependencies,
            10_000);
    dependencies.clear();
    assertEquals(1, request.dependencies().size());
    assertThrows(UnsupportedOperationException.class, () -> request.dependencies().clear());

    var context =
        new InformationContext("reader", InformationContext.Selection.personal()).withView(true);
    var retained =
        new AuthorizedText(
            context,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "document",
            "text",
            "legacy-source-fingerprint",
            "legacy-extraction-fingerprint",
            3,
            "retained text");
    // Constructor-only typed fixture, never submitted to cache.read: this synthetic owner token is
    // not a durable version of AuthorizedText. That production projection remains operation-local
    // until deletion/redaction, extraction and current authority have complete owner fences.
    var tracked =
        new SnapshotCache.Tracked<>(
            new SnapshotCache.Namespace("isolated-fixture", "operation-local-text-example"),
            request,
            new SnapshotCache.Fence(1, 1),
            retained,
            100);
    assertSame(retained, tracked.value());
    assertEquals(context, tracked.value().context());
    assertEquals(3, tracked.value().processingGeneration());
    assertEquals("legacy-source-fingerprint", tracked.value().contentHash());
    assertNotEquals("3", tracked.request().version().value());
    assertThrows(
        UnsupportedOperationException.class, () -> tracked.request().dependencies().clear());
  }

  @Test
  void malformedTrackingMetadataAndUnboundedIdentitiesAreRejected() {
    for (String invalid : List.of("", " ", "bad\nidentity")) {
      assertThrows(
          IllegalArgumentException.class, () -> new SnapshotCache.Namespace(invalid, "projection"));
      assertThrows(
          IllegalArgumentException.class, () -> new SnapshotCache.Namespace("database", invalid));
      assertThrows(IllegalArgumentException.class, () -> new SnapshotCache.Key(invalid, "owner"));
      assertThrows(
          IllegalArgumentException.class, () -> new SnapshotCache.Key("identity", invalid));
      assertThrows(IllegalArgumentException.class, () -> new SnapshotCache.SourceVersion(invalid));
      assertThrows(IllegalArgumentException.class, () -> new SnapshotCache.Dependency(invalid));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new SnapshotCache.Namespace("x".repeat(257), "projection"));
    assertThrows(
        IllegalArgumentException.class, () -> new SnapshotCache.Key("x".repeat(1025), "owner"));
    assertThrows(
        IllegalArgumentException.class, () -> new SnapshotCache.Key("identity", "x".repeat(2049)));
    assertThrows(
        IllegalArgumentException.class, () -> new SnapshotCache.SourceVersion("x".repeat(1025)));
    assertThrows(
        IllegalArgumentException.class, () -> new SnapshotCache.Dependency("x".repeat(1025)));
    var request = request("valid");
    assertThrows(
        NullPointerException.class,
        () -> new SnapshotCache.Request(null, request.version(), Set.of(), 1));
    assertThrows(
        NullPointerException.class,
        () -> new SnapshotCache.Request(request.key(), null, Set.of(), 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SnapshotCache.Request(request.key(), request.version(), null, 1));
    var nullDependency = new HashSet<SnapshotCache.Dependency>();
    nullDependency.add(null);
    assertThrows(
        NullPointerException.class,
        () -> new SnapshotCache.Request(request.key(), request.version(), nullDependency, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SnapshotCache.Request(request.key(), request.version(), Set.of(), 0));
    var oversized = new HashSet<SnapshotCache.Dependency>();
    for (int i = 0; i < 257; i++) oversized.add(new SnapshotCache.Dependency("dependency-" + i));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SnapshotCache.Request(request.key(), request.version(), oversized, 1));
    assertThrows(
        NullPointerException.class, () -> new SnapshotCache.Loaded<>(request.version(), null, 1));
    assertThrows(NullPointerException.class, () -> new SnapshotCache.Loaded<>(null, "value", 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SnapshotCache.Loaded<>(request.version(), "value", 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SnapshotCache.Tracked<>(
                CacheTestSupport.namespace(), request, new SnapshotCache.Fence(0, 1), "value", 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SnapshotCache.Tracked<>(
                CacheTestSupport.namespace(), request, new SnapshotCache.Fence(1, 0), "value", 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SnapshotCache.Tracked<>(
                CacheTestSupport.namespace(), request, new SnapshotCache.Fence(1, 1), "value", 0));
    assertThrows(IllegalArgumentException.class, () -> limits(0, 100, 100, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> limits(1, 0, 100, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> limits(1, 100, 0, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> limits(1, 100, 100, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> limits(1, 100, 100, 257, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SnapshotCache.Limits(1, 100, 1, 100, 1, 2, 1, 1, Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SnapshotCache.Limits(1, 100, 1, 100, 1, 1, 1, 1, Duration.ZERO));
    assertThrows(
        ArithmeticException.class,
        () ->
            new SnapshotCache.Limits(
                1, 100, 1, 100, 1, 1, 1, 1, Duration.ofSeconds(Long.MAX_VALUE)));
  }
}
