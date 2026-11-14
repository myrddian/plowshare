package io.aeyer.plowshare.server.cache;

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
}
