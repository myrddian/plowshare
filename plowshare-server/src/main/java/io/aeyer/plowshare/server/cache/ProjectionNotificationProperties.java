package io.aeyer.plowshare.server.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How far caches that opt in may trust database notifications instead of a per-read version check.
 *
 * @param staleness the most a notification-trusted hit may lag a committed change. Revocations,
 *     role changes and similar take effect within this bound, not instantly.
 * @param heartbeat how often the listener sends itself a delivery probe; well below {@code
 *     staleness}, so a healthy listener keeps trusted hits available.
 * @param heartbeatTimeout how long a probe may go unanswered before the listener abandons its
 *     session and recovers. Also the network timeout of that session's statements.
 */
@ConfigurationProperties("plowshare.projection-cache.notified")
public record ProjectionNotificationProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("2s") Duration staleness,
    @DefaultValue("500ms") Duration heartbeat,
    @DefaultValue("3s") Duration heartbeatTimeout) {
  public ProjectionNotificationProperties {
    if (staleness == null
        || heartbeat == null
        || heartbeatTimeout == null
        || staleness.toMillis() < 1
        || staleness.compareTo(Duration.ofMinutes(1)) > 0
        || heartbeat.toMillis() < 1
        || heartbeat.compareTo(staleness) >= 0
        || heartbeatTimeout.compareTo(heartbeat) <= 0
        || heartbeatTimeout.compareTo(Duration.ofMinutes(1)) > 0)
      throw new IllegalArgumentException(
          "Notified cache settings need 0 < heartbeat < staleness <= 1m and"
              + " heartbeat < heartbeat-timeout <= 1m");
  }
}
