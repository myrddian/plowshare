package io.aeyer.plowshare.server.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Explicit per-projection budgets; version connections use a separate bounded pool. {@code timeout}
 * is the single deadline of one cached read: its FETCH slot wait and its wait on a shared reload
 * both draw on it (see {@link io.aeyer.plowshare.server.reads.ReadDeadline}).
 */
@ConfigurationProperties("plowshare.projection-cache")
public record ProjectionCacheProperties(
    @DefaultValue("256") int entries,
    @DefaultValue("33554432") long residentBytes,
    @DefaultValue("8") int loads,
    @DefaultValue("67108864") long loadingBytes,
    @DefaultValue("256") int waiters,
    @DefaultValue("64") int waitersPerLoad,
    @DefaultValue("8192") int dependencyLinks,
    @DefaultValue("8388608") long maxValueBytes,
    @DefaultValue("2s") Duration timeout,
    @DefaultValue("1s") Duration notificationPoll,
    @DefaultValue("1s") Duration reconnectDelay,
    @DefaultValue("128") int maxPendingTransactions,
    @DefaultValue("24") int maxProjections,
    @DefaultValue("4") int versionConnections) {
  /**
   * A domain-scoped entry depends on at most every domain; an item-scoped one on at most {@link
   * ProjectionItemVersions#MAX_ITEMS} items. {@code dependencyLinks} still bounds the total.
   */
  private static final int DEPENDENCIES_PER_KEY =
      Math.max(ProjectionVersions.Domain.values().length, ProjectionItemVersions.MAX_ITEMS);

  public ProjectionCacheProperties {
    new SnapshotCache.Limits(
        entries,
        residentBytes,
        loads,
        loadingBytes,
        waiters,
        waitersPerLoad,
        DEPENDENCIES_PER_KEY,
        dependencyLinks,
        timeout);
    if (maxValueBytes < 1
        || maxValueBytes > loadingBytes - (2048L + 128L * 4096) - 16384
        || maxPendingTransactions < 1
        || maxPendingTransactions > 4096
        || maxProjections < 1
        || maxProjections > 64
        || versionConnections < 1
        || versionConnections > 32
        || notificationPoll == null
        || notificationPoll.isZero()
        || notificationPoll.isNegative()
        || notificationPoll.compareTo(Duration.ofSeconds(30)) > 0
        || reconnectDelay == null
        || reconnectDelay.isZero()
        || reconnectDelay.isNegative()
        || reconnectDelay.compareTo(Duration.ofMinutes(1)) > 0)
      throw new IllegalArgumentException("Invalid projection cache configuration");
  }

  public SnapshotCache.Limits limits() {
    return new SnapshotCache.Limits(
        entries,
        residentBytes,
        loads,
        loadingBytes,
        waiters,
        waitersPerLoad,
        DEPENDENCIES_PER_KEY,
        dependencyLinks,
        timeout);
  }
}
