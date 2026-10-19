package io.aeyer.plowshare.server.cache;

import java.util.Set;

/**
 * Bounded lifecycle-owned invalidation hints; current version/authority checks remain mandatory.
 */
public interface ProjectionChangeSignals extends AutoCloseable {
  /** Monotonic process-local state order, not a database version or a durable catch-up cursor. */
  record State(long generation, boolean ready) {
    public State {
      if (generation < 1) throw new IllegalArgumentException("Positive listener state required");
    }
  }

  interface Observer {
    /** Ignore older state generations, including callbacks reordered by concurrent registration. */
    void stateChanged(State state);

    /** Immutable bounded domain set; changes may be coalesced or duplicated. */
    void changed(Set<ProjectionVersions.Domain> domains);
  }

  interface Subscription extends AutoCloseable {
    @Override
    void close();
  }

  /**
   * Idempotent subscription disposal; implementations bound registrations and invoke outside locks.
   */
  Subscription subscribe(Set<ProjectionVersions.Domain> domains, Observer observer);

  void start();

  boolean healthy();

  @Override
  void close();
}
