package io.aeyer.plowshare.server.orchestrations;

/** Parent/result delivery behind a live, renewable distributed consumer lease. */
@FunctionalInterface
public interface ChildCompletionDelivery {
  /** Rechecks the terminal attempt; ownership must be checked before effects and settlement. */
  void childCompleted(ChildCompletions.Completion completion, Runnable requireLease);
}
