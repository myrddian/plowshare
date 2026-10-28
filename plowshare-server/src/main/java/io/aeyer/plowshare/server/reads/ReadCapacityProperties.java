package io.aeyer.plowshare.server.reads;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Logical read budgets complement, and never replace, database connection backpressure. */
@ConfigurationProperties("plowshare.read-capacity")
public record ReadCapacityProperties(
    @DefaultValue("16") int interactive,
    @DefaultValue("4") int interactivePerPrincipal,
    @DefaultValue("2") int reports,
    @DefaultValue("1") int reportsPerPrincipal,
    @DefaultValue("64") int observers,
    @DefaultValue("4") int observersPerPrincipal,
    @DefaultValue("1024") int sockets,
    @DefaultValue("32") int socketsPerPrincipal,
    @DefaultValue("512") int subscriptions,
    @DefaultValue("32") int subscriptionsPerPrincipal,
    @DefaultValue("2") int authority,
    @DefaultValue("1") int authorityPerPrincipal,
    @DefaultValue("1") int maintenance,
    @DefaultValue("1") int maintenancePerPrincipal) {
  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public ReadCapacityProperties {
    check(interactive, interactivePerPrincipal);
    check(reports, reportsPerPrincipal);
    check(observers, observersPerPrincipal);
    check(sockets, socketsPerPrincipal);
    check(subscriptions, subscriptionsPerPrincipal);
    check(authority, authorityPerPrincipal);
    check(maintenance, maintenancePerPrincipal);
  }

  public ReadCapacityProperties(
      int interactive,
      int interactivePerPrincipal,
      int reports,
      int reportsPerPrincipal,
      int observers,
      int observersPerPrincipal,
      int sockets,
      int socketsPerPrincipal,
      int subscriptions,
      int subscriptionsPerPrincipal) {
    this(
        interactive,
        interactivePerPrincipal,
        reports,
        reportsPerPrincipal,
        observers,
        observersPerPrincipal,
        sockets,
        socketsPerPrincipal,
        subscriptions,
        subscriptionsPerPrincipal,
        2,
        1,
        1,
        1);
  }

  public ReadCapacityProperties(
      int interactive,
      int interactivePerPrincipal,
      int reports,
      int reportsPerPrincipal,
      int observers,
      int observersPerPrincipal) {
    this(
        interactive,
        interactivePerPrincipal,
        reports,
        reportsPerPrincipal,
        observers,
        observersPerPrincipal,
        1024,
        32,
        512,
        32);
  }

  private static void check(int global, int principal) {
    if (global < 1 || global > 4096 || principal < 1 || principal > global)
      throw new IllegalArgumentException("Read capacity requires 1 <= principal <= global <= 4096");
  }

  int global(ReadAdmission.Work work) {
    return switch (work) {
      case INTERACTIVE -> interactive;
      case REPORT, REPORT_REFRESH -> reports;
      case AUTHORITY -> authority;
      case MAINTENANCE -> maintenance;
      case OBSERVER -> observers;
      case SOCKET -> sockets;
      case SUBSCRIPTION -> subscriptions;
    };
  }

  int principal(ReadAdmission.Work work) {
    return switch (work) {
      case INTERACTIVE -> interactivePerPrincipal;
      case REPORT, REPORT_REFRESH -> reportsPerPrincipal;
      case AUTHORITY -> authorityPerPrincipal;
      case MAINTENANCE -> maintenancePerPrincipal;
      case OBSERVER -> observersPerPrincipal;
      case SOCKET -> socketsPerPrincipal;
      case SUBSCRIPTION -> subscriptionsPerPrincipal;
    };
  }
}
