package io.aeyer.plowshare.server.reads;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Bounded admission before the existing database throttle. A single short monitor protects the
 * compound global/principal counters; no owner code, database work or completion runs in it. Only
 * admitted work retains principal entries, bounding metadata even under arbitrary key churn. There
 * are bounded eligible FIFO queues for interactive and foreground report reads. Background
 * refreshes yield to waiting foreground reports, share the same total/principal budget, and leave a
 * global foreground seat when the report budget has more than one seat. No waiting read owns a
 * database connection.
 */
public final class BoundedReadAdmission implements ReadAdmission {
  private record Key(String principal, Work work) {}

  private static final int MAX_FETCH_WAITERS = 256;

  private final ReadCapacityProperties limits;
  private final ReadSchedulingProperties scheduling;
  private final ArrayList<Key> waiting = new ArrayList<>();
  // FIFO tickets for cache fetches; identity, not equality, marks each caller's place.
  private final ArrayList<Key> fetching = new ArrayList<>();
  private final ArrayList<Key> interactive = new ArrayList<>();
  private final ArrayList<Key> authoritative = new ArrayList<>();
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
      if (!available(key, work)
          || report(work) && !waiting.isEmpty()
          || work == Work.INTERACTIVE
              && interactive.stream().anyMatch(next -> available(next, work))
          || work == Work.AUTHORITY
              && authoritative.stream().anyMatch(next -> available(next, work))
          || work == Work.FETCH && fetching.contains(key)) throw rejectedRead();
      return reserve(key, work);
    }
  }

  @Override
  public Duration interactiveWaitBudget() {
    return Duration.ofMillis(scheduling.interactiveWaitMillis());
  }

  @Override
  public Lease awaitInteractive(String principal) {
    ReadDeadline.check();
    if (Thread.currentThread().isInterrupted()) throw ReadFailedException.interrupted();
    // Entry points normally hold no transaction. A nested caller with retained connections must
    // not park behind work that may need those connections to release its seat.
    if (TransactionSynchronizationManager.isSynchronizationActive()
        || TransactionSynchronizationManager.isActualTransactionActive())
      return admit(principal, Work.INTERACTIVE);
    return awaitReadSeat(principal, Work.INTERACTIVE, interactive);
  }

  @Override
  public <T> T readAuthoritative(String principal, Supplier<T> action) {
    Objects.requireNonNull(action);
    ReadDeadline.check();
    if (Thread.currentThread().isInterrupted()) throw ReadFailedException.interrupted();
    var lease =
        TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isActualTransactionActive()
            ? admit(principal, Work.AUTHORITY)
            : awaitReadSeat(principal, Work.AUTHORITY, authoritative);
    try (lease) {
      ReadDeadline.check();
      T value = action.get();
      ReadDeadline.check();
      return value;
    }
  }

  /** Both pure-read queues share the interactive queue bounds, but retain separate active seats. */
  private synchronized Lease awaitReadSeat(String principal, Work work, ArrayList<Key> queue) {
    var key = key(principal, work);
    long deadline = waitDeadline(interactiveWaitBudget());
    requireTime(deadline);
    if (available(key, work) && queue.stream().noneMatch(next -> available(next, work)))
      return reserveBeforeDeadline(key, work, deadline);
    if (queue.size() >= scheduling.waitingInteractive()
        || queue.stream().filter(key::equals).count()
            >= scheduling.waitingInteractivePerPrincipal()) throw rejectedRead();
    var ticket = new Key(principal, work);
    queue.add(ticket);
    try {
      while (true) {
        requireTime(deadline);
        long remaining = deadline - System.nanoTime();
        var eligible =
            queue.stream().filter(next -> available(next, work)).findFirst().orElse(null);
        if (eligible == ticket) return reserveBeforeDeadline(key, work, deadline);
        try {
          TimeUnit.NANOSECONDS.timedWait(this, remaining);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw ReadFailedException.interrupted(interrupted);
        }
      }
    } finally {
      queue.removeIf(candidate -> candidate == ticket);
      notifyAll();
    }
  }

  public synchronized int waitingInteractive() {
    return interactive.size();
  }

  public synchronized int waitingAuthoritative() {
    return authoritative.size();
  }

  @Override
  public <T> T read(String principal, Work work, Supplier<T> action) {
    // Only pure report loaders use bounded waiting. Mutations, authority and observer seats keep
    // their existing refusal semantics. Interruption/timeout removes the waiter before any IO.
    // An enclosing synchronization may already retain a caller/version-pool connection, even
    // without an actual transaction. Never park such a caller while it holds connections/locks.
    // A cache fetch always waits for one of the cache's slots: the slots bound the cache's own
    // load on PostgreSQL, and a refused miss would fail every caller coalesced onto it. Waiting
    // inside a transaction cannot starve a slot holder, because a fetch runs inline on its
    // requester's thread and uses that requester's own connection when one is bound.
    boolean parkable =
        !TransactionSynchronizationManager.isSynchronizationActive()
            && !TransactionSynchronizationManager.isActualTransactionActive();
    var lease =
        work == Work.FETCH
            ? awaitFetch(principal)
            : parkable && work == Work.REPORT ? awaitReport(principal) : admit(principal, work);
    try (lease) {
      return action.get();
    }
  }

  private synchronized Lease awaitReport(String principal) {
    var key = key(principal, Work.REPORT);
    long deadline = waitDeadline(Duration.ofMillis(scheduling.reportWaitMillis()));
    requireTime(deadline);
    if (waiting.isEmpty() && available(key, Work.REPORT))
      return reserveBeforeDeadline(key, Work.REPORT, deadline);
    if (waiting.size() >= scheduling.waitingReports()
        || waiting.stream().filter(key::equals).count() >= scheduling.waitingReportsPerPrincipal())
      throw rejectedRead();
    // Identity distinguishes concurrent requests for one principal. Equality remains the budget
    // key; removal below uses identity, never an earlier equal waiter for the same principal.
    var ticket = new Key(principal, Work.REPORT);
    waiting.add(ticket);
    try {
      while (true) {
        requireTime(deadline);
        Key eligible =
            waiting.stream().filter(next -> available(next, Work.REPORT)).findFirst().orElse(null);
        if (eligible == ticket) return reserveBeforeDeadline(key, Work.REPORT, deadline);
        long remaining = deadline - System.nanoTime();
        requireTime(deadline);
        try {
          TimeUnit.NANOSECONDS.timedWait(this, remaining);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw ReadFailedException.interrupted(interrupted);
        }
      }
    } finally {
      waiting.removeIf(candidate -> candidate == ticket);
      notifyAll();
    }
  }

  /**
   * Waits in arrival order for one of its cache's FETCH slots until the request's {@link
   * ReadDeadline}: inside a cache read the wait uses what remains of that read's budget, so the
   * slot wait and the cache's reload wait share one deadline. Outside one it waits at most {@code
   * fetchWaitMillis}. The principal is the cache, never the requester; {@code fetches-total} bounds
   * all caches. A fetch holds one slot for one load, so the wait is bounded by the loads ahead of
   * it; the deadline only turns a stuck load into a refusal rather than an indefinite park.
   */
  private synchronized Lease awaitFetch(String principal) {
    var key = key(principal, Work.FETCH);
    long deadline = waitDeadline(Duration.ofMillis(scheduling.fetchWaitMillis()));
    requireTime(deadline);
    if (!fetching.contains(key) && available(key, Work.FETCH))
      return reserveBeforeDeadline(key, Work.FETCH, deadline);
    // Every fetch is a cache loader, so the cache's own load limits already bound this queue;
    // the cap keeps that true even for a caller outside a cache.
    if (fetching.size() >= MAX_FETCH_WAITERS) throw rejectedRead();
    var ticket = new Key(principal, Work.FETCH);
    fetching.add(ticket);
    try {
      while (true) {
        requireTime(deadline);
        // The oldest waiter whose cache has a free slot goes next, so one busy cache never
        // holds up another cache's fetch.
        Key eligible =
            fetching.stream().filter(next -> available(next, Work.FETCH)).findFirst().orElse(null);
        if (eligible == ticket) return reserveBeforeDeadline(key, Work.FETCH, deadline);
        long remaining = deadline - System.nanoTime();
        requireTime(deadline);
        try {
          TimeUnit.NANOSECONDS.timedWait(this, remaining);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw ReadFailedException.interrupted(interrupted);
        }
      }
    } finally {
      fetching.removeIf(candidate -> candidate == ticket);
      notifyAll();
    }
  }

  public synchronized int waitingFetches() {
    return fetching.size();
  }

  private static long waitDeadline(Duration limit) {
    long maximum = System.nanoTime() + limit.toNanos();
    long inherited = ReadDeadline.deadlineNanos(limit);
    return inherited - maximum < 0 ? inherited : maximum;
  }

  private void requireTime(long deadline) {
    ReadDeadline.check();
    if (Thread.currentThread().isInterrupted()) throw ReadFailedException.interrupted();
    if (deadline - System.nanoTime() <= 0) throw rejectedRead();
  }

  private Lease reserveBeforeDeadline(Key key, Work work, long deadline) {
    requireTime(deadline);
    return reserve(key, work);
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
