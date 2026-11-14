package io.aeyer.plowshare.server.relay;

/** Atomic retirement for a domain's own subscription and empty topic; never a public authority. */
public interface RelayRetirementRepository {
  /**
   * The caller must hold its owning binding/lifecycle lock in the enclosing transaction. Removes
   * only that subscription, after publications, admitted input and live claims have drained. Other
   * subscriptions keep the topic alive. Returns true when the topic is absent, including an earlier
   * successful retirement. A restored owner must register through its normal binding transaction.
   */
  boolean retireOwned(Relay.SubscriptionKey owner);
}
