package io.aeyer.plowshare.server.reads;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bounded waiting owns no database connections. Background reports always yield instead. Cache
 * fetches wait at most {@code fetchWaitMillis} for a FETCH seat before refusing.
 */
@ConfigurationProperties("plowshare.read-scheduling")
public record ReadSchedulingProperties(
    @DefaultValue("64") int waitingReports,
    @DefaultValue("4") int waitingReportsPerPrincipal,
    @DefaultValue("2000") int reportWaitMillis,
    @DefaultValue("5000") int fetchWaitMillis) {
  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public ReadSchedulingProperties {
    if (waitingReports < 1
        || waitingReports > 4096
        || waitingReportsPerPrincipal < 1
        || waitingReportsPerPrincipal > waitingReports
        || reportWaitMillis < 1
        || reportWaitMillis > 5000
        || fetchWaitMillis < 1
        || fetchWaitMillis > 30000)
      throw new IllegalArgumentException("Invalid read scheduling bounds");
  }

  public ReadSchedulingProperties(
      int waitingReports, int waitingReportsPerPrincipal, int reportWaitMillis) {
    this(waitingReports, waitingReportsPerPrincipal, reportWaitMillis, 5000);
  }

  public static ReadSchedulingProperties defaults() {
    return new ReadSchedulingProperties(64, 4, 2000, 5000);
  }
}
