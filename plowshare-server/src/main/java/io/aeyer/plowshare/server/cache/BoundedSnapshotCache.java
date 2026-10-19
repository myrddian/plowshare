package io.aeyer.plowshare.server.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Hash-indexed DTOs and a doubly linked LRU, with no cache scans for lookup, recency or one
 * eviction. Dependency/key invalidation visits indexed affected references only. Dependency cleanup
 * is O(d) for d bounded declared dependencies; evicting k entries is O(k*d), not a constant-time
 * bulk action.
 *
 * <p>All state transitions linearize under one short monitor. Loader, scheduler/executor calls,
 * future completion and timer cancellation happen outside it. Successful read/fill linearization
 * precedes completion callbacks: an invalidation that wins before that decision fails the read; an
 * invalidation afterwards cannot revoke a value already logically returned to an operation. Owners
 * still fence current authority and disclosure, including cache hits.
 *
 * <p>Invalidated/abandoned flights leave the join index but retain their invalid bit, unique load
 * generation, epoch and physical capacity until the loader and its completion tasks exit.
 * Consequently eviction/recreation cannot resurrect an older fill, and there is no unbounded
 * tombstone map for absent keys.
 */
public final class BoundedSnapshotCache<T> implements SnapshotCache<T> {
  private record Identity(Key key, SourceVersion version) {}

  private record Infrastructure(ExecutorService executor, ScheduledThreadPoolExecutor timer) {}

  private static final class DeliveryRejected extends RuntimeException {
    final Runnable completion;

    DeliveryRejected(Throwable cause, Runnable completion) {
      super(cause);
      this.completion = completion;
    }
  }

  private abstract class Reference {
    final Request request;
    final Identity identity;
    final long metadata;
    final int links;
    boolean indexed;

    Reference(Request request) {
      this.request = request;
      identity = new Identity(request.key(), request.version());
      metadata = metadataWeight(request);
      links = request.dependencies().size() + 1;
    }

    abstract void invalidate(Failure reason, List<Runnable> actions);
  }

  private final class Entry extends Reference {
    final Tracked<T> snapshot;
    Entry previous;
    Entry next;

    Entry(Request request, Tracked<T> snapshot) {
      super(request);
      this.snapshot = snapshot;
    }

    @Override
    void invalidate(Failure reason, List<Runnable> actions) {
      remove(this);
    }
  }

  private final class Flight extends Reference {
    final Fence fence;
    final long reservation;
    final Set<Waiter> waiting = new LinkedHashSet<>();
    Failure invalid;
    boolean finished;
    boolean loaderExited;
    int deliveries;

    Flight(Request request, long generation) {
      super(request);
      fence = new Fence(epoch, generation);
      reservation = metadata + request.maxLoadWeight();
    }

    @Override
    void invalidate(Failure reason, List<Runnable> actions) {
      if (invalid != null || finished) return;
      invalid = reason;
      joining.remove(identity, this);
      unregister(this);
      for (Waiter waiter : List.copyOf(waiting))
        settle(waiter, null, new CacheException(reason), actions);
    }
  }

  private final class Waiter implements Read<T> {
    final Flight flight;
    final CompletableFuture<Tracked<T>> future = new CompletableFuture<>();
    final CompletionStage<Tracked<T>> view = future.minimalCompletionStage();
    boolean active = true;
    ScheduledFuture<?> timer;

    Waiter(Flight flight) {
      this.flight = flight;
    }

    @Override
    public CompletionStage<Tracked<T>> result() {
      return view;
    }

    @Override
    public boolean cancel() {
      return detach(this, new CancellationException("Cache read cancelled"));
    }
  }

  private final Object lock = new Object();
  private final Namespace namespace;
  private final Limits limits;
  private final Executor executor;
  private final Executor results;
  private final ScheduledExecutorService timer;
  private final ExecutorService ownedExecutor;
  private final boolean ownedTimer;
  private final Map<Identity, Entry> entries = new HashMap<>();
  private final Map<Identity, Flight> joining = new HashMap<>();
  private final Set<Flight> physical = new HashSet<>();
  private final Map<Key, Set<Reference>> keys = new HashMap<>();
  private final Map<Dependency, Set<Reference>> dependencies = new HashMap<>();
  private Entry first;
  private Entry last;
  private long residentWeight;
  private long loadingWeight;
  private int waiterCount;
  private int links;
  private int residentLinks;
  private long epoch = 1;
  private long generation;
  private boolean recovering = true;
  private boolean closed;
  private long hits;
  private long misses;
  private long coalesced;
  private long evictions;
  private long bypassed;
  private long invalidations;
  private long loadFailures;

  /** Composition owns one cache per namespace/projection and supplies explicit resource limits. */
  public BoundedSnapshotCache(Namespace namespace, Limits limits) {
    this(namespace, limits, infrastructure(namespace, limits));
  }

  private BoundedSnapshotCache(Namespace namespace, Limits limits, Infrastructure infrastructure) {
    this(
        namespace,
        limits,
        infrastructure.executor(),
        infrastructure.timer(),
        task -> Thread.ofVirtual().name("snapshot-cache-result").start(task),
        infrastructure.executor(),
        true);
  }

  /**
   * Borrowed deterministic execution infrastructure for package tests; lifecycle remains borrowed.
   */
  BoundedSnapshotCache(
      Namespace namespace, Limits limits, Executor executor, ScheduledExecutorService timer) {
    this(
        namespace,
        limits,
        executor,
        timer,
        task -> Thread.ofVirtual().name("snapshot-cache-result").start(task),
        null,
        false);
  }

  BoundedSnapshotCache(
      Namespace namespace,
      Limits limits,
      Executor executor,
      ScheduledExecutorService timer,
      Executor results) {
    this(namespace, limits, executor, timer, results, null, false);
  }

  private BoundedSnapshotCache(
      Namespace namespace,
      Limits limits,
      Executor executor,
      ScheduledExecutorService timer,
      Executor results,
      ExecutorService ownedExecutor,
      boolean ownedTimer) {
    this.namespace = Objects.requireNonNull(namespace);
    this.limits = Objects.requireNonNull(limits);
    this.executor = Objects.requireNonNull(executor);
    this.timer = Objects.requireNonNull(timer);
    this.results = Objects.requireNonNull(results);
    this.ownedExecutor = ownedExecutor;
    this.ownedTimer = ownedTimer;
  }

  private static Infrastructure infrastructure(Namespace namespace, Limits limits) {
    Objects.requireNonNull(namespace);
    Objects.requireNonNull(limits);
    return new Infrastructure(Executors.newVirtualThreadPerTaskExecutor(), newTimer());
  }

  private static ScheduledThreadPoolExecutor newTimer() {
    var timer =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().daemon().name("snapshot-cache-deadlines").factory());
    timer.setRemoveOnCancelPolicy(true);
    timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    return timer;
  }

  @Override
  public Read<T> read(Request request, Loader<T> loader) {
    Objects.requireNonNull(request);
    Objects.requireNonNull(loader);
    // Only bounded framework keys are hashed, never arbitrary DTO values or user hash callbacks.
    request.key().hashCode();
    request.version().hashCode();
    request.dependencies().forEach(Dependency::hashCode);
    Waiter waiter;
    Flight flight;
    boolean dispatch = false;
    synchronized (lock) {
      if (closed) return failed(Failure.CLOSED);
      if (recovering) return failed(Failure.RECOVERING);
      if (request.dependencies().size() > limits.dependenciesPerKey())
        return failed(Failure.CAPACITY);
      var identity = new Identity(request.key(), request.version());
      Entry entry = entries.get(identity);
      if (entry != null) {
        if (!entry.request.equals(request)) return failed(Failure.INCONSISTENT_REQUEST);
        hits++;
        recent(entry);
        return completed(entry.snapshot);
      }
      misses++;
      flight = joining.get(identity);
      if (flight != null && !flight.request.equals(request))
        return failed(Failure.INCONSISTENT_REQUEST);
      if (waiterCount >= limits.waiters()
          || flight != null && flight.waiting.size() + flight.deliveries >= limits.waitersPerLoad())
        return failed(Failure.CAPACITY);
      if (flight == null) {
        long metadata = metadataWeight(request);
        int requiredLinks = request.dependencies().size() + 1;
        if (physical.size() >= limits.loads()
            || request.maxLoadWeight() > limits.loadingWeight() - metadata
            || metadata + request.maxLoadWeight() > limits.loadingWeight() - loadingWeight
            || requiredLinks > limits.dependencyLinks() - links) return failed(Failure.CAPACITY);
        flight = new Flight(request, Math.incrementExact(generation));
        generation = flight.fence.generation();
        physical.add(flight);
        joining.put(identity, flight);
        loadingWeight += flight.reservation;
        links += flight.links;
        register(flight);
        dispatch = true;
      } else coalesced++;
      waiter = new Waiter(flight);
      waiterCount++;
      flight.waiting.add(waiter);
    }
    Flight selected = flight;
    try {
      schedule(waiter);
      if (dispatch) {
        executor.execute(() -> load(selected, loader));
      }
    } catch (RuntimeException | Error refused) {
      // A newly reserved task must retire even when deadline setup fails before submission.
      // Joining callers own only their waiter; they must never finish another caller's loader.
      try {
        if (dispatch) finish(selected, null, refused);
        else detach(waiter, refused);
      } finally {
        if (dispatch) loaderExited(selected);
      }
      if (refused instanceof Error fatal) throw fatal;
    }
    return waiter;
  }

  private Read<T> completed(Tracked<T> snapshot) {
    var future = CompletableFuture.completedFuture(snapshot).minimalCompletionStage();
    return new Read<>() {
      public CompletionStage<Tracked<T>> result() {
        return future;
      }

      public boolean cancel() {
        return false;
      }
    };
  }

  private Read<T> failed(Failure failure) {
    var future =
        CompletableFuture.<Tracked<T>>failedFuture(new CacheException(failure))
            .minimalCompletionStage();
    return new Read<>() {
      public CompletionStage<Tracked<T>> result() {
        return future;
      }

      public boolean cancel() {
        return false;
      }
    };
  }

  private void schedule(Waiter waiter) {
    ScheduledFuture<?> scheduled;
    try {
      scheduled =
          timer.schedule(
              () -> {
                detach(waiter, new TimeoutException("Cache read timed out"));
              },
              limits.waitTimeout().toNanos(),
              TimeUnit.NANOSECONDS);
    } catch (RuntimeException failure) {
      detach(waiter, failure);
      return;
    }
    boolean cancel;
    synchronized (lock) {
      cancel = !waiter.active;
      if (!cancel) waiter.timer = scheduled;
    }
    if (cancel) scheduled.cancel(false);
  }

  private boolean detach(Waiter waiter, Throwable reason) {
    var actions = new ArrayList<Runnable>();
    synchronized (lock) {
      if (!waiter.active) return false;
      settle(waiter, null, reason, actions);
    }
    run(actions);
    return true;
  }

  private void settle(Waiter waiter, Tracked<T> value, Throwable error, List<Runnable> actions) {
    if (!waiter.active) return;
    waiter.active = false;
    waiter.flight.waiting.remove(waiter);
    waiter.flight.deliveries++;
    if (waiter.timer != null) actions.add(() -> waiter.timer.cancel(false));
    // CompletableFuture completion runs arbitrary synchronous dependents. Give each completion
    // its own bounded task so one caller cannot stall siblings or the single deadline scheduler.
    // The waiter and flight reservations remain charged until that task (including callbacks)
    // exits.
    actions.add(() -> deliver(waiter, value, error));
    if (waiter.flight.waiting.isEmpty()
        && !waiter.flight.finished
        && waiter.flight.invalid == null) {
      // Abandon, but never release physical load/metadata capacity before the queued/running task
      // exits.
      waiter.flight.invalid = Failure.INVALIDATED;
      joining.remove(waiter.flight.identity, waiter.flight);
      unregister(waiter.flight);
    }
  }

  private void deliver(Waiter waiter, Tracked<T> value, Throwable error) {
    Runnable completion =
        () -> {
          try {
            if (error == null) waiter.future.complete(value);
            else waiter.future.completeExceptionally(error);
          } finally {
            deliveryExited(waiter);
          }
        };
    try {
      results.execute(completion);
    } catch (RuntimeException | Error unavailable) {
      // Try every sibling's dispatch before emergency synchronous failure delivery. Even on an
      // unavailable thread facility, a failed delivery retains its charge through its callbacks.
      throw new DeliveryRejected(
          unavailable,
          () -> {
            try {
              waiter.future.completeExceptionally(unavailable);
            } finally {
              deliveryExited(waiter);
            }
          });
    }
  }

  private void deliveryExited(Waiter waiter) {
    synchronized (lock) {
      waiterCount--;
      waiter.flight.deliveries--;
      releasePhysical(waiter.flight);
    }
  }

  private void load(Flight flight, Loader<T> loader) {
    try {
      boolean skip;
      synchronized (lock) {
        skip = flight.invalid != null || !physical.contains(flight);
      }
      if (skip) {
        finish(flight, null, new CacheException(Failure.INVALIDATED));
        return;
      }
      Loaded<T> loaded;
      try {
        loaded = Objects.requireNonNull(loader.load(), "Loader returned no snapshot");
      } catch (Throwable failure) {
        finish(flight, null, failure);
        if (failure instanceof Error fatal) throw fatal;
        return;
      }
      finish(flight, loaded, null);
    } finally {
      loaderExited(flight);
    }
  }

  private void loaderExited(Flight flight) {
    synchronized (lock) {
      flight.loaderExited = true;
      releasePhysical(flight);
    }
  }

  private void releasePhysical(Flight flight) {
    if (flight.finished
        && flight.loaderExited
        && flight.deliveries == 0
        && physical.remove(flight)) {
      loadingWeight -= flight.reservation;
      links -= flight.links;
    }
  }

  private void finish(Flight flight, Loaded<T> loaded, Throwable error) {
    var actions = new ArrayList<Runnable>();
    synchronized (lock) {
      if (!physical.contains(flight) || flight.finished) return;
      flight.finished = true;
      joining.remove(flight.identity, flight);
      unregister(flight);
      if (closed) error = new CacheException(Failure.CLOSED);
      else if (recovering || flight.fence.epoch() != epoch || flight.invalid != null)
        error = new CacheException(flight.invalid == null ? Failure.INVALIDATED : flight.invalid);
      else if (error == null && !loaded.version().equals(flight.request.version()))
        error = new CacheException(Failure.VERSION_MISMATCH);
      else if (error == null && loaded.weight() > flight.request.maxLoadWeight())
        error = new CacheException(Failure.LOAD_TOO_LARGE);
      Tracked<T> snapshot = null;
      if (error == null) {
        long weight = loaded.weight() + flight.metadata;
        snapshot = new Tracked<>(namespace, flight.request, flight.fence, loaded.value(), weight);
        if (weight > limits.residentWeight()
            || flight.links > limits.dependencyLinks() - physicalLinks()) bypassed++;
        else {
          while (entries.size() >= limits.entries()
              || weight > limits.residentWeight() - residentWeight
              || flight.links > limits.dependencyLinks() - links) {
            remove(last);
            evictions++;
          }
          var entry = new Entry(flight.request, snapshot);
          entries.put(entry.identity, entry);
          residentWeight += weight;
          links += entry.links;
          residentLinks += entry.links;
          register(entry);
          recent(entry);
        }
      } else loadFailures++;
      for (Waiter waiter : List.copyOf(flight.waiting)) settle(waiter, snapshot, error, actions);
    }
    run(actions);
  }

  private int physicalLinks() {
    // Never used on lookup or eviction. Admission must distinguish physical reservations (which
    // cannot be evicted) from resident edges, or a tiny metadata budget could evict past the tail.
    // Maintain this count explicitly rather than scanning physical loads.
    return links - residentLinks;
  }

  private void register(Reference reference) {
    reference.indexed = true;
    keys.computeIfAbsent(reference.request.key(), ignored -> new HashSet<>()).add(reference);
    for (var dependency : reference.request.dependencies())
      dependencies.computeIfAbsent(dependency, ignored -> new HashSet<>()).add(reference);
  }

  private void unregister(Reference reference) {
    if (!reference.indexed) return;
    reference.indexed = false;
    removeIndex(keys, reference.request.key(), reference);
    for (var dependency : reference.request.dependencies())
      removeIndex(dependencies, dependency, reference);
  }

  private <K> void removeIndex(Map<K, Set<Reference>> index, K key, Reference reference) {
    Set<Reference> references = index.get(key);
    references.remove(reference);
    if (references.isEmpty()) index.remove(key);
  }

  private void recent(Entry entry) {
    if (first == entry) return;
    unlink(entry);
    entry.previous = null;
    entry.next = first;
    if (first == null) last = entry;
    else first.previous = entry;
    first = entry;
  }

  private void unlink(Entry entry) {
    if (entry.previous != null) entry.previous.next = entry.next;
    else if (first == entry) first = entry.next;
    if (entry.next != null) entry.next.previous = entry.previous;
    else if (last == entry) last = entry.previous;
    entry.previous = null;
    entry.next = null;
  }

  private void remove(Entry entry) {
    entries.remove(entry.identity);
    unlink(entry);
    unregister(entry);
    residentWeight -= entry.snapshot.weight();
    links -= entry.links;
    residentLinks -= entry.links;
  }

  @Override
  public void invalidate(Key key) {
    Objects.requireNonNull(key).hashCode();
    invalidateIndexed(key, null);
  }

  @Override
  public void invalidate(Dependency dependency) {
    Objects.requireNonNull(dependency).hashCode();
    invalidateIndexed(null, dependency);
  }

  private void invalidateIndexed(Key key, Dependency dependency) {
    var actions = new ArrayList<Runnable>();
    synchronized (lock) {
      if (closed) return;
      invalidations++;
      Set<Reference> references = key == null ? dependencies.get(dependency) : keys.get(key);
      if (references != null)
        for (Reference reference : List.copyOf(references))
          reference.invalidate(Failure.INVALIDATED, actions);
    }
    run(actions);
  }

  @Override
  public void invalidateAll() {
    var actions = new ArrayList<Runnable>();
    synchronized (lock) {
      if (!closed) clear(Failure.INVALIDATED, actions);
    }
    run(actions);
  }

  private void clear(Failure reason, List<Runnable> actions) {
    // Never recycle an outage ticket, even after exhausting the local epoch space.
    if (epoch == Long.MAX_VALUE) {
      closed = true;
      reason = Failure.CLOSED;
    } else epoch++;
    invalidations++;
    for (Entry entry : List.copyOf(entries.values())) remove(entry);
    for (Flight flight : List.copyOf(physical)) flight.invalidate(reason, actions);
  }

  @Override
  public Recovery beginRecovery() {
    var actions = new ArrayList<Runnable>();
    Recovery ticket;
    synchronized (lock) {
      if (closed) throw new CacheException(Failure.CLOSED);
      recovering = true;
      clear(Failure.INVALIDATED, actions);
      ticket = new Recovery(this, epoch);
    }
    run(actions);
    return ticket;
  }

  @Override
  public boolean completeRecovery(Recovery ticket) {
    Objects.requireNonNull(ticket);
    synchronized (lock) {
      if (closed || !recovering || ticket.owner != this || ticket.epoch != epoch) return false;
      recovering = false;
      return true;
    }
  }

  @Override
  public Statistics statistics() {
    synchronized (lock) {
      return new Statistics(
          entries.size(),
          residentWeight,
          physical.size(),
          loadingWeight,
          waiterCount,
          links,
          hits,
          misses,
          coalesced,
          evictions,
          bypassed,
          invalidations,
          loadFailures,
          recovering,
          closed);
    }
  }

  @Override
  public void close() {
    var actions = new ArrayList<Runnable>();
    synchronized (lock) {
      if (!closed) {
        closed = true;
        recovering = true;
        clear(Failure.CLOSED, actions);
      }
    }
    try {
      run(actions);
    } finally {
      try {
        if (ownedExecutor != null) ownedExecutor.shutdown();
      } finally {
        if (ownedTimer) timer.shutdownNow();
      }
    }
  }

  private static long metadataWeight(Request request) {
    // Conservative declared-weight accounting, not instrumentation of JVM object layout. Owners
    // supply DTO weight; these charges additionally cover bounded strings, map/list nodes and
    // edges.
    long weight =
        1024L
            + 2L
                * (request.key().identity().length()
                    + request.key().selection().length()
                    + request.version().value().length());
    for (Dependency dependency : request.dependencies())
      weight += 192L + 2L * dependency.value().length();
    return weight;
  }

  private static void run(List<Runnable> actions) {
    var fallback = new ArrayList<Runnable>();
    Throwable failure = null;
    for (Runnable action : actions) {
      try {
        action.run();
      } catch (DeliveryRejected rejected) {
        fallback.add(rejected.completion);
        if (rejected.getCause() instanceof Error fatal) failure = fatal;
      } catch (RuntimeException | Error infrastructure) {
        if (failure == null) failure = infrastructure;
        else failure.addSuppressed(infrastructure);
      }
    }
    fallback.forEach(Runnable::run);
    if (failure instanceof Error fatal) throw fatal;
    if (failure instanceof RuntimeException runtime) throw runtime;
  }
}
