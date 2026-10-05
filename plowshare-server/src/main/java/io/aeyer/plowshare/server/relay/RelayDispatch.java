package io.aeyer.plowshare.server.relay;

import java.time.Duration;
import java.util.Optional;

/** Authorized bounded dispatch of durable branches; there is no retry of a prepared effect. */
public interface RelayDispatch {
  /** Infrastructure guard: receiver calls cannot join an enclosing database transaction. */
  @FunctionalInterface
  interface Boundary {
    void requireOutsideTransaction();
  }

  /**
   * Claims at most one branch in the subscription. No ready work returns empty. Receiver refusal
   * settles definite failure; dispatch exceptions settle uncertainty. Repository failures
   * propagate, preserving the durable intent for recovery. No background loop or automatic producer
   * is started.
   */
  Optional<RelayDeliveries.Delivery> next(
      RelayProjectFiles.Access access,
      Relay.SubscriptionKey subscription,
      String worker,
      Duration lease);

  /** Atomic claim boundary; automatic workers use it to check subscription ownership as well. */
  @FunctionalInterface
  interface ClaimSource {
    Optional<RelayDeliveries.Delivery> claim();
  }

  /** Same effect lifecycle, with a caller-supplied ownership-fenced branch claim. */
  Optional<RelayDeliveries.Delivery> next(
      RelayProjectFiles.Access access,
      Relay.SubscriptionKey subscription,
      String worker,
      Duration lease,
      ClaimSource claims);

  /** Read-only owning receipt lookup; never prepares or dispatches an effect. */
  Optional<RelayDeliveries.Resolution> inspect(
      RelayProjectFiles.Access access, RelayDeliveries.DeliveryKey key);

  /** Applies a known owning resolution; an inconclusive inspection preserves uncertainty. */
  RelayDeliveries.Delivery reconcile(
      RelayProjectFiles.Access access, RelayDeliveries.DeliveryKey key);
}
