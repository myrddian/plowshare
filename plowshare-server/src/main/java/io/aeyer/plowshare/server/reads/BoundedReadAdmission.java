package io.aeyer.plowshare.server.reads;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded admission before the existing database throttle. A single short monitor protects the
 * compound global/principal counters; no owner code, database work or completion runs in it. Only
 * admitted work retains principal entries, bounding metadata even under arbitrary key churn. There
 * is a bounded FIFO queue for foreground report reads only. Background refreshes yield to waiting
 * foreground reports, share the same total/principal budget, and leave a global foreground seat
 * when the report budget has more than one seat. No waiting read owns a database connection.
 */
public final class BoundedReadAdmission implements ReadAdmission {
  private record Key(String principal, Work work) {}

  private final ReadCapacityProperties limits;
  private final ReadSchedulingProperties scheduling;
  private final java.util.ArrayList<Key> waiting = new java.util.ArrayList<>();
  private final ConcurrentMap<Key, Integer> principals = new ConcurrentHashMap<>();
  private final int[] active = new int[Work.values().length];
  private final AtomicLong rejected = new AtomicLong();

  public BoundedReadAdmission(ReadCapacityProperties limits) {
    this(limits, ReadSchedulingProperties.defaults());
  }

  public BoundedReadAdmission(ReadCapacityProperties limits, ReadSchedulingProperties scheduling) {
    this.limits = Objects.requireNonNull(limits);
    this.scheduling = Objects.requireNonNull(scheduling);
  }

  public Lease admit(String principal, Work work) {
    Objects.requireNonNull(work);
    var key = key(principal, work);
    synchronized (this) {
      if (!available(key, work) || report(work) && !waiting.isEmpty()) throw rejectedRead();
      return reserve(key, work);
    }
  }

  @Override
  public <T> T read(String principal, Work work, java.util.function.Supplier<T> action) {
    // Only pure report loaders use bounded waiting. Mutations, authority and observer seats keep
    // their existing refusal semantics. Interruption/timeout removes the waiter before any IO.
    // An enclosing synchronization may already retain a caller/version-pool connection, even
    // without an actual transaction. Never park such a caller while it holds connections/locks.
    boolean waitable =
        work == Work.REPORT
            && !org.springframework.transaction.support.TransactionSynchronizationManager
                .isSynchronizationActive()
            && !org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive();
    var lease = waitable ? awaitReport(principal) : admit(principal, work);
    try (lease) {
      return action.get();
    }
  }

  private synchronized Lease awaitReport(String principal) {
    var key = key(principal, Work.REPORT);
    if (waiting.isEmpty() && available(key, Work.REPORT)) return reserve(key, Work.REPORT);
    if (waiting.size() >= scheduling.waitingReports()
        || waiting.stream().filter(key::equals).count() >= scheduling.waitingReportsPerPrincipal())
      throw rejectedRead();
    // Identity distinguishes concurrent requests for one principal. Equality remains the budget
    // key; removal below uses identity, never an earlier equal waiter for the same principal.
    var ticket = new Key(principal, Work.REPORT);
    waiting.add(ticket);
    long deadline =
        System.nanoTime()
            + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(scheduling.reportWaitMillis());
    try {
      while (true) {
        Key eligible =
            waiting.stream().filter(next -> available(next, Work.REPORT)).findFirst().orElse(null);
        if (eligible == ticket) return reserve(key, Work.REPORT);
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw rejectedRead();
        try {
          java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, remaining);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw rejectedRead();
        }
      }
    } finally {
      waiting.removeIf(candidate -> candidate == ticket);
      notifyAll();
    }
  }

  private static boolean report(Work work) {
    return work == Work.REPORT || work == Work.REPORT_REFRESH;
  }

  private static Key key(String principal, Work work) {
    return new Key(principal, report(work) ? Work.REPORT : work);
  }

  private boolean available(Key key, Work work) {
    int total =
        report(work)
            ? active[Work.REPORT.ordinal()] + active[Work.REPORT_REFRESH.ordinal()]
            : active[work.ordinal()];
    if (total >= limits.global(work) || principals.getOrDefault(key, 0) >= limits.principal(work))
      return false;
    return work != Work.REPORT_REFRESH
        || active[work.ordinal()] < Math.max(1, limits.global(work) - 1);
  }

  private ReadUnavailableException rejectedRead() {
    rejected.incrementAndGet();
    return new ReadUnavailableException();
  }

  private Lease reserve(Key key, Work work) {
    active[work.ordinal()]++;
    principals.merge(key, 1, Integer::sum);
    var open = new AtomicBoolean(true);
    return () -> {
      if (!open.compareAndSet(true, false)) return;
      synchronized (this) {
        active[work.ordinal()]--;
        principals.compute(key, (ignored, count) -> count == 1 ? null : count - 1);
        notifyAll();
      }
    };
  }

  public synchronized int waitingReports() {
    return waiting.size();
  }

  public synchronized int active(Work work) {
    return active[work.ordinal()];
  }

  public long rejected() {
    return rejected.get();
  }
}
