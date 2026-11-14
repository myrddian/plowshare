package io.aeyer.plowshare.server.cache;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * Caller-thread reuse of one immutable repository projection whose entries each depend on named
 * {@link ProjectionVersions.Item items} instead of whole domains. A write invalidates only the
 * entries that read the items it changed; other entries stay and keep serving hits. Owners retain
 * current authorization, read auditing and disclosure locks, exactly as for {@link
 * ProjectionCache}.
 */
public interface ItemProjectionCache<T> {
  long maxWeight();

  /**
   * Returns the value for {@code key}, loading it at most once across concurrent callers.
   *
   * @param items every item the bounded loader reads, known before it runs; each must belong to one
   *     of the cache's domains. The cache adds each domain's fallback item. The same key and item
   *     set must always describe the same selection.
   * @param uncachedLoader the original read, used when sharing is unsafe or unavailable
   * @throws io.aeyer.plowshare.server.reads.ReadUnavailableException when the read's deadline
   *     passes or cache capacity is exhausted; no uncached load is started in that case
   */
  T read(
      SnapshotCache.Key key,
      Set<ProjectionVersions.Item> items,
      boolean cacheable,
      Supplier<T> boundedLoader,
      Supplier<T> uncachedLoader,
      ToLongFunction<T> weight);

  /** A value and the item counters it was loaded under, not yet confirmed to be current. */
  record Unconfirmed<T>(T value, ProjectionItemVersions.ItemToken items) {
    public Unconfirmed {
      Objects.requireNonNull(value);
      Objects.requireNonNull(items);
    }
  }

  /**
   * For a composite evaluation that reads many entries and then confirms all of them together with
   * one {@link ProjectionItemVersions#staleItems} statement, instead of one version check per
   * entry. Returns a stored value without SQL, or loads one under freshly captured counters; in
   * neither case are the counters compared again here. The caller must not disclose anything
   * derived from the value until that confirmation over every token it read reports no stale item,
   * and must {@link #invalidate(ProjectionVersions.Item) invalidate} the stale items it reports.
   *
   * <p>A value loaded after a write committed past its capture is stored under the older counters,
   * so it can never be confirmed; keyed notifications or the caller's invalidation remove it.
   *
   * @return empty when the cache cannot serve: the listener is not ready, the caller's transaction
   *     is ineligible, or the loader or backing cache refused the value. The caller then uses its
   *     ordinary SQL path.
   * @throws io.aeyer.plowshare.server.reads.ReadUnavailableException when the read's deadline
   *     passes or cache capacity is exhausted
   */
  Optional<Unconfirmed<T>> readUnconfirmed(
      SnapshotCache.Key key,
      Set<ProjectionVersions.Item> items,
      Supplier<T> boundedLoader,
      ToLongFunction<T> weight);

  /** Drops every entry that read {@code item}, as a keyed notification of it would. */
  void invalidate(ProjectionVersions.Item item);
}
