package io.aeyer.plowshare.server.cache;

import java.util.EnumMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-memory projection outcomes, distinct from the backing cache's lookup statistics. Recording
 * never calls a metrics backend, SQL or a caller callback and cannot affect freshness decisions.
 */
public final class ProjectionCacheStatistics {
  /** Fixed vocabulary: no cache keys, principals, resource identifiers or SQL become labels. */
  public enum Event {
    TRUSTED_HIT,
    VERSIONED_HIT,
    LOADED_RETURN,
    LOAD,
    BYPASS_NOT_READY,
    BYPASS_NOT_CACHEABLE,
    BYPASS_INELIGIBLE,
    BYPASS_LOAD_REFUSED,
    BYPASS_CACHE_REFUSED,
    BYPASS_STALE,
    CAPACITY_REJECTED,
    /** The read's single deadline passed before a value was available; no SQL load was added. */
    DEADLINE_REJECTED
  }

  private final EnumMap<Event, LongAdder> counts = new EnumMap<>(Event.class);

  public ProjectionCacheStatistics() {
    for (var event : Event.values()) counts.put(event, new LongAdder());
  }

  void record(Event event) {
    counts.get(event).increment();
  }

  /** Cumulative since this projection was created; concurrent reads are weakly consistent. */
  public long count(Event event) {
    return counts.get(event).sum();
  }
}
