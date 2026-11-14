package io.aeyer.plowshare.server.cache;

import static io.aeyer.plowshare.server.cache.CacheTestSupport.limits;
import static io.aeyer.plowshare.server.cache.CacheTestSupport.loaded;
import static io.aeyer.plowshare.server.cache.CacheTestSupport.request;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.Test;

/**
 * Lines share one memory budget: past it, only the coldest entries of the line furthest above its
 * share are evicted, never a whole line, and the number of lines is not limited.
 */
class ResidentBudgetTest {
  /** A line of fixed-weight entries, coldest first. */
  private static final class FixedLine implements ResidentBudget.Line {
    final Deque<Long> entries = new ArrayDeque<>();
    final ResidentBudget budget;

    FixedLine(ResidentBudget budget, long... weights) {
      this.budget = budget;
      for (long weight : weights) {
        entries.addLast(weight);
        budget.added(weight);
      }
      budget.register(this);
    }

    public long resident() {
      return entries.stream().mapToLong(Long::longValue).sum();
    }

    public long evictColdest() {
      Long weight = entries.pollFirst();
      if (weight == null) return 0;
      budget.removed(weight);
      return weight;
    }
  }

  @Test
  void evictsOnlyTheColdestEntriesOfTheLineFurthestAboveItsShare() {
    var budget = new ResidentBudget(600);
    var large = new FixedLine(budget, 300, 300, 300);
    var small = new FixedLine(budget, 100);
    budget.rebalance();
    assertTrue(budget.resident() <= 600);
    assertEquals(400, budget.resident());
    assertEquals(1, large.entries.size(), "the line above its share gave up its coldest entries");
    assertEquals(1, small.entries.size(), "a line within its share is untouched");
  }

  @Test
  void aBudgetThatFitsEvictsNothing() {
    var budget = new ResidentBudget(1_000);
    var line = new FixedLine(budget, 300, 300);
    budget.rebalance();
    assertEquals(2, line.entries.size());
    assertEquals(600, budget.resident());
  }

  @Test
  void manyLinesRegisterWithoutACountLimit() {
    var budget = new ResidentBudget(1L << 30);
    for (int index = 0; index < 500; index++) new FixedLine(budget, 10);
    assertEquals(5_000, budget.resident());
  }

  @Test
  void realLinesShareOneBudgetAndKeepTheirMostRecentEntries() {
    var budget = new ResidentBudget(1L << 30);
    try (var probe =
        new BoundedSnapshotCache<CacheTestSupport.Value>(namespace("probe"), limits())) {
      assertTrue(probe.completeRecovery(probe.beginRecovery()));
      var request = request("probe");
      probe.readInline(request, () -> loaded(request, "x")).result().toCompletableFuture().join();
      long weight = probe.resident();
      assertTrue(weight > 0);
      budget = new ResidentBudget(3 * weight);
    }
    try (var busy = new BoundedSnapshotCache<CacheTestSupport.Value>(namespace("busy"), limits());
        var quiet =
            new BoundedSnapshotCache<CacheTestSupport.Value>(namespace("quiet"), limits())) {
      assertTrue(busy.completeRecovery(busy.beginRecovery()));
      assertTrue(quiet.completeRecovery(quiet.beginRecovery()));
      busy.attach(budget);
      quiet.attach(budget);
      var other = request("other");
      quiet.readInline(other, () -> loaded(other, "kept")).result().toCompletableFuture().join();
      for (String identity : new String[] {"a", "b", "c"}) {
        var each = request(identity);
        busy.readInline(each, () -> loaded(each, identity)).result().toCompletableFuture().join();
      }
      assertTrue(budget.resident() <= budget.total(), "the lines fit the shared budget");
      assertEquals(1, quiet.statistics().entries(), "a line within its share keeps its only entry");
      assertEquals(2, busy.statistics().entries(), "the busy line gave up only its coldest entry");
      var newest = request("c");
      assertEquals(
          "c",
          busy.readInline(newest, () -> fail("The most recent entry must still be a hit"))
              .result()
              .toCompletableFuture()
              .join()
              .value()
              .text());
    }
    assertEquals(0, budget.resident(), "closed lines release their bytes");
  }

  private static SnapshotCache.Namespace namespace(String projection) {
    return new SnapshotCache.Namespace("database-fixture", projection);
  }
}
