package io.aeyer.plowshare.server.cache;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * One typed repository projection's bounded, process-local cache. This capability neither performs
 * authorization nor establishes database freshness. Owners must supply current version evidence,
 * deeply immutable DTOs and complete dependencies, including collection membership. Loaders run
 * outside cache locks and must bound allocation before returning, not merely declare a small
 * weight. Transaction-local DTOs must not be published here before their owning transaction
 * commits.
 */
public interface SnapshotCache<T> extends AutoCloseable {
  /** Deployment-independent opaque database/server identity and owning projection/schema. */
  record Namespace(String database, String projection) {
    public Namespace {
      text(database, 256);
      text(projection, 256);
    }
  }

  /** Identity plus every visibility/selection input that changes this projection. */
  record Key(String identity, String selection) {
    public Key {
      text(identity, 1024);
      text(selection, 2048);
    }
  }

  /** Equality-only durable evidence supplied by the owner, never an ordered local counter. */
  record SourceVersion(String value) {
    public SourceVersion {
      text(value, 1024);
    }
  }

  /** Opaque entity or collection address; never content, credentials or an allow decision. */
  record Dependency(String value) {
    public Dependency {
      text(value, 1024);
    }
  }

  /**
   * Complete dependency set must be known before dispatch. Reserve a conservative maximum DTO
   * weight for this load. A key/version must always declare the same dependencies and reservation;
   * changes require a new version or invalidation. Null/negative-result caching is not implicit.
   */
  record Request(Key key, SourceVersion version, Set<Dependency> dependencies, long maxLoadWeight) {
    public Request {
      Objects.requireNonNull(key);
      Objects.requireNonNull(version);
      if (dependencies == null || dependencies.size() > 256 || maxLoadWeight < 1)
        throw new IllegalArgumentException("Invalid snapshot load bounds");
      dependencies = Set.copyOf(dependencies);
    }
  }

  /** Loader's actual version and conservative DTO weight, including its nested retained objects. */
  record Loaded<T>(SourceVersion version, T value, long weight) {
    public Loaded {
      Objects.requireNonNull(version);
      Objects.requireNonNull(value);
      if (weight < 1) throw new IllegalArgumentException("Positive snapshot weight required");
    }
  }

  /** Local recovery epoch and unique physical-load generation; neither versions durable state. */
  record Fence(long epoch, long generation) {}

  /**
   * Cache tracking is immutable and does not mutate a DTO held after eviction. The fence identifies
   * this local read's admission, not a permission lease or proof of unseen database commits.
   */
  record Tracked<T>(Namespace namespace, Request request, Fence fence, T value, long weight) {
    public Tracked {
      Objects.requireNonNull(namespace);
      Objects.requireNonNull(request);
      Objects.requireNonNull(fence);
      Objects.requireNonNull(value);
      if (fence.epoch() < 1 || fence.generation() < 1 || weight < 1)
        throw new IllegalArgumentException("Invalid tracked snapshot");
    }
  }

  /**
   * Weight includes DTOs and conservatively charged key/dependency/node metadata. Physical loads
   * reserve maxLoadWeight plus metadata until actual exit, even after cancellation. Waiter counts
   * bound futures/timers separately. All limits are per configured projection/cache instance.
   */
  record Limits(
      int entries,
      long residentWeight,
      int loads,
      long loadingWeight,
      int waiters,
      int waitersPerLoad,
      int dependenciesPerKey,
      int dependencyLinks,
      Duration waitTimeout) {
    public Limits {
      Objects.requireNonNull(waitTimeout);
      if (entries < 1
          || residentWeight < 1
          || loads < 1
          || loadingWeight < 1
          || waiters < 1
          || waitersPerLoad < 1
          || waitersPerLoad > waiters
          || dependenciesPerKey < 0
          || dependenciesPerKey > 256
          || dependencyLinks < 1
          || waitTimeout.isNegative()
          || waitTimeout.isZero()) throw new IllegalArgumentException("Invalid cache limits");
      // Scheduling uses nanoseconds. Reject overflow rather than silently weakening a deadline.
      waitTimeout.toNanos();
    }
  }

  @FunctionalInterface
  interface Loader<T> {
    Loaded<T> load() throws Exception;
  }

  /** Caller-local cancellation never cancels another caller's read. */
  interface Read<T> extends AutoCloseable {
    CompletionStage<Tracked<T>> result();

    boolean cancel();

    @Override
    default void close() {
      cancel();
    }
  }

  enum Failure {
    CLOSED,
    RECOVERING,
    CAPACITY,
    INVALIDATED,
    VERSION_MISMATCH,
    LOAD_TOO_LARGE,
    INCONSISTENT_REQUEST,
    REENTRANT_LOAD
  }

  final class CacheException extends RuntimeException {
    private final Failure failure;

    public CacheException(Failure failure) {
      super("Snapshot cache: " + Objects.requireNonNull(failure));
      this.failure = failure;
    }

    public Failure failure() {
      return failure;
    }
  }

  /** Only the cache that issued this current outage ticket can accept recovery completion. */
  final class Recovery {
    final Object owner;
    final long epoch;

    Recovery(Object owner, long epoch) {
      this.owner = owner;
      this.epoch = epoch;
    }

    public long epoch() {
      return epoch;
    }
  }

  record Statistics(
      int entries,
      long residentWeight,
      int loads,
      long loadingWeight,
      int waiters,
      int dependencyLinks,
      long hits,
      long misses,
      long coalesced,
      long evictions,
      long bypassed,
      long invalidations,
      long loadFailures,
      boolean recovering,
      boolean closed) {}

  /**
   * No retry or uncached fallback: a rejected read explicitly returns a failed caller-local stage.
   * A loader may read other keys, but a cyclic dependency on an unfinished load in this cache fails
   * with {@link Failure#REENTRANT_LOAD} instead of waiting for its own deadline. Waiters have no
   * completion ordering guarantee. Nested reads are tracked on loader threads; work that a loader
   * submits to an unrelated external executor does not carry that dependency context. Loaders
   * execute independently of the caller's thread-bound transaction; transaction-local or
   * uncommitted reads must bypass this capability.
   */
  Read<T> read(Request request, Loader<T> loader);

  /**
   * The elected loader runs on the calling thread, outside cache locks, with the same bounded
   * coalescing and invalidation fences as {@link #read}. Joining callers do not rerun the loader.
   * This preserves an owning repository's thread-bound committed-read transaction; it does not make
   * transaction-local data safe to share. The owner must prove cache eligibility before the call
   * and immediately before returning the loaded DTO, reject dirty/uncommitted state and
   * independently enforce every caller's current authorization and disclosure/audit boundary.
   */
  Read<T> readInline(Request request, Loader<T> loader);

  /** Invalidates all versions for this address in O(affected entries/loads/dependencies). */
  void invalidate(Key key);

  void invalidate(Dependency dependency);

  /** Clears the projection and advances its epoch without proving recovery or reopening it. */
  void invalidateAll();

  /**
   * Clears and blocks cache reads until the exact ticket is completed after authoritative recovery.
   */
  Recovery beginRecovery();

  boolean completeRecovery(Recovery ticket);

  Statistics statistics();

  @Override
  void close();

  private static void text(String value, int max) {
    if (value == null
        || value.isBlank()
        || value.length() > max
        || value.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid bounded cache identity");
  }
}
