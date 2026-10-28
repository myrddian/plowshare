package io.aeyer.plowshare.server.reads;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Bounded waiting owns no database connections. Background reports always yield instead. */
@ConfigurationProperties("plowshare.read-scheduling")
public record ReadSchedulingProperties(
    @DefaultValue("64") int waitingReports,
    @DefaultValue("4") int waitingReportsPerPrincipal,
    @DefaultValue("2000") int reportWaitMillis) {
  public ReadSchedulingProperties {
    if (waitingReports < 1
        || waitingReports > 4096
        || waitingReportsPerPrincipal < 1
        || waitingReportsPerPrincipal > waitingReports
        || reportWaitMillis < 1
        || reportWaitMillis > 5000)
      throw new IllegalArgumentException("Invalid read scheduling bounds");
  }

  public static ReadSchedulingProperties defaults() {
    return new ReadSchedulingProperties(64, 4, 2000);
  }
}
