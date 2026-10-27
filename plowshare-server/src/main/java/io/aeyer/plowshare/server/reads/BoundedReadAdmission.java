package io.aeyer.plowshare.server.reads;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Non-blocking admission before the existing database throttle. A single short monitor protects the
 * compound global/principal counters; no owner code, database work or completion runs in it. Only
 * admitted work retains principal entries, bounding metadata even under arbitrary key churn. There
 * is no additional waiting queue: callers receive a service-unavailable outcome on overload.
 */
public final class BoundedReadAdmission implements ReadAdmission {
  private record Key(String principal, Work work) {}

  private final ReadCapacityProperties limits;
  private final ConcurrentMap<Key, Integer> principals = new ConcurrentHashMap<>();
  private final int[] active = new int[Work.values().length];
  private final AtomicLong rejected = new AtomicLong();

  public BoundedReadAdmission(ReadCapacityProperties limits) {
    this.limits = Objects.requireNonNull(limits);
  }

  public Lease admit(String principal, Work work) {
    Objects.requireNonNull(work);
    var key = new Key(principal, work);
    synchronized (this) {
      int held = principals.getOrDefault(key, 0);
      if (active[work.ordinal()] >= limits.global(work) || held >= limits.principal(work)) {
        rejected.incrementAndGet();
        throw new ReadUnavailableException();
      }
      active[work.ordinal()]++;
      principals.put(key, held + 1);
    }
    var open = new AtomicBoolean(true);
    return () -> {
      if (!open.compareAndSet(true, false)) return;
      synchronized (this) {
        active[work.ordinal()]--;
        principals.compute(key, (ignored, count) -> count == 1 ? null : count - 1);
      }
    };
  }

  public synchronized int active(Work work) {
    return active[work.ordinal()];
  }

  public long rejected() {
    return rejected.get();
  }
}
