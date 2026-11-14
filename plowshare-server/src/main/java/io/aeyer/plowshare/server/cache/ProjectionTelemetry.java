package io.aeyer.plowshare.server.cache;

import java.util.function.Supplier;

/** Composition-owned export of bounded, identity-free cache and listener observations. */
public interface ProjectionTelemetry {
  /** Called once for each successfully registered projection, never for an individual read. */
  void observeCache(
      String projection,
      ProjectionCacheStatistics outcomes,
      Supplier<SnapshotCache.Statistics> backing);

  /**
   * Observes listener memory only; scraping must not send probes or acquire database connections.
   */
  void observeListener(
      ProjectionChangeSignals signals, ProjectionNotificationProperties properties);

  /** Contexts without a metrics registry keep the same cache and listener behavior. */
  ProjectionTelemetry NONE =
      new ProjectionTelemetry() {
        public void observeCache(
            String projection,
            ProjectionCacheStatistics outcomes,
            Supplier<SnapshotCache.Statistics> backing) {}

        public void observeListener(
            ProjectionChangeSignals signals, ProjectionNotificationProperties properties) {}
      };
}
