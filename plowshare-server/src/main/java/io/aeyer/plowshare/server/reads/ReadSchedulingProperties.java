package io.aeyer.plowshare.server.reads;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bounded waiting owns no database connections. Background reports always yield instead. A cache
 * fetch waits for a FETCH seat until its cache read's single deadline ({@code
 * plowshare.projection-cache.timeout}, see {@link ReadDeadline}); {@code fetchWaitMillis} bounds a
 * FETCH wait outside any cache read. HTTP and WebSocket admission waiting use {@code
 * interactiveWaitMillis}; it never limits a handler/provider/workflow lifetime. The pure
 * authoritative-read fallback queue shares the interactive queue/wait bounds, with its own
 * AUTHORITY active limits.
 */
@ConfigurationProperties("plowshare.read-scheduling")
public record ReadSchedulingProperties(
    @DefaultValue("64") int waitingReports,
    @DefaultValue("4") int waitingReportsPerPrincipal,
    @DefaultValue("2000") int reportWaitMillis,
    @DefaultValue("2000") int fetchWaitMillis,
    @DefaultValue("256") int waitingInteractive,
    @DefaultValue("16") int waitingInteractivePerPrincipal,
    @DefaultValue("2000") int interactiveWaitMillis) {
  @ConstructorBinding
  public ReadSchedulingProperties {
    if (waitingReports < 1
        || waitingReports > 4096
        || waitingReportsPerPrincipal < 1
        || waitingReportsPerPrincipal > waitingReports
        || reportWaitMillis < 1
        || reportWaitMillis > 5000
        || fetchWaitMillis < 1
        || fetchWaitMillis > 30000
        || waitingInteractive < 1
        || waitingInteractive > 4096
        || waitingInteractivePerPrincipal < 1
        || waitingInteractivePerPrincipal > waitingInteractive
        || interactiveWaitMillis < 1
        || interactiveWaitMillis > 60000)
      throw new IllegalArgumentException("Invalid read scheduling bounds");
  }

  public ReadSchedulingProperties(
      int waitingReports,
      int waitingReportsPerPrincipal,
      int reportWaitMillis,
      int fetchWaitMillis) {
    this(
        waitingReports,
        waitingReportsPerPrincipal,
        reportWaitMillis,
        fetchWaitMillis,
        256,
        16,
        2000);
  }

  public ReadSchedulingProperties(
      int waitingReports, int waitingReportsPerPrincipal, int reportWaitMillis) {
    this(waitingReports, waitingReportsPerPrincipal, reportWaitMillis, 2000);
  }

  public static ReadSchedulingProperties defaults() {
    return new ReadSchedulingProperties(64, 4, 2000, 2000);
  }
}
