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
    @DefaultValue("32") int subscriptionsPerPrincipal) {
  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public ReadCapacityProperties {
    check(interactive, interactivePerPrincipal);
    check(reports, reportsPerPrincipal);
    check(observers, observersPerPrincipal);
    check(sockets, socketsPerPrincipal);
    check(subscriptions, subscriptionsPerPrincipal);
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
      case REPORT -> reports;
      case OBSERVER -> observers;
      case SOCKET -> sockets;
      case SUBSCRIPTION -> subscriptions;
    };
  }

  int principal(ReadAdmission.Work work) {
    return switch (work) {
      case INTERACTIVE -> interactivePerPrincipal;
      case REPORT -> reportsPerPrincipal;
      case OBSERVER -> observersPerPrincipal;
      case SOCKET -> socketsPerPrincipal;
      case SUBSCRIPTION -> subscriptionsPerPrincipal;
    };
  }
}
