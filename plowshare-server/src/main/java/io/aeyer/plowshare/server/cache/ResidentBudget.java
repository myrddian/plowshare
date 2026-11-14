package io.aeyer.plowshare.server.cache;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One resident-memory budget shared by every projection line.
 *
 * <p>Each projection is exactly one line: an LRU of its own entries with its own per-line cap.
 * Lines are fixed by server composition, so their number is not configured or limited; memory is
 * the only control. When the lines together hold more than {@code total} bytes, the budget evicts
 * the coldest entry of the line furthest above its fair share ({@code total} divided by the number
 * of lines), one entry at a time, until the total fits. A line is never cleared to make room for
 * another, and a line at or below its share is not touched while another line is above it.
 *
 * <p>The bound is enforced after a store completes, outside every line's lock, so concurrent stores
 * may exceed it briefly by at most their own entries. Rebalancing takes one line's lock at a time,
 * so it cannot deadlock with a store.
 */
final class ResidentBudget {
  /** The view of one line that the budget needs; implemented by {@link BoundedSnapshotCache}. */
  interface Line {
    long resident();

    /** Evicts this line's least recently used entry and returns its weight, or 0 when empty. */
    long evictColdest();
  }

  private final long total;
  private final Set<Line> lines = ConcurrentHashMap.newKeySet();
  private final AtomicLong resident = new AtomicLong();
  private final ReentrantLock rebalancing = new ReentrantLock();

  ResidentBudget(long total) {
    if (total < 1) throw new IllegalArgumentException("Resident budget must be positive");
    this.total = total;
  }

  void register(Line line) {
    lines.add(Objects.requireNonNull(line));
  }

  void unregister(Line line) {
    lines.remove(line);
  }

  void added(long weight) {
    resident.addAndGet(weight);
  }

  void removed(long weight) {
    resident.addAndGet(-weight);
  }

  long total() {
    return total;
  }

  long resident() {
    return resident.get();
  }

  /**
   * Evicts until the lines fit the budget. A thread that finds rebalancing already in progress
   * returns at once: the running pass re-reads the total, which includes this thread's store.
   */
  void rebalance() {
    if (resident.get() <= total || !rebalancing.tryLock()) return;
    try {
      while (resident.get() > total) {
        long share = total / Math.max(1, lines.size());
        Line victim = null;
        long excess = 0;
        for (Line line : lines) {
          long over = line.resident() - share;
          if (line.resident() > 0 && (victim == null || over > excess)) {
            victim = line;
            excess = over;
          }
        }
        if (victim == null || victim.evictColdest() == 0) return;
      }
    } finally {
      rebalancing.unlock();
    }
  }
}
