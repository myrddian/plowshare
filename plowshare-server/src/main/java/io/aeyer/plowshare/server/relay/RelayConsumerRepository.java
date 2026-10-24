package io.aeyer.plowshare.server.relay;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Distributed subscription ownership. Database time controls leases; routing and receiver effects
 * must run outside its transactions. Delivery fences remain independent of subscription epochs.
 */
public interface RelayConsumerRepository {
  /**
   * Acquires or extends this worker's lease with a fresh epoch; another live owner returns empty.
   */
  Optional<Lease> acquire(
      Relay.SubscriptionKey subscription, String worker, String account, Duration duration);

  /** Pins admission and advances the cursor atomically with a live ownership check. */
  RelayDeliveries.Admission admit(
      Lease lease, RelayDeliveries.AdmissionKey key, RelayDeliveries.Decision decision);

  /** Claims one branch atomically with a live ownership check; effects run after commit. */
  Optional<RelayDeliveries.Delivery> claim(Lease lease, Duration duration);

  /**
   * Locks and verifies ownership inside an existing short transaction; no effects may run there.
   */
  void requireOwned(Lease lease);

  /** Releases only the matching owner/epoch. A stale release cannot disturb its successor. */
  void release(Lease lease);

  /**
   * Whether this account may write the subscription's consumer state, which is the second half of
   * the authority to advance a position (decision 0038): the row being written must be the caller's
   * own. True when no lease row exists, when its owner's lease has expired — reassignment after a
   * crash — or when the account already holds it. False while another account holds it live.
   *
   * <p>This is an ownership question only. It is never a substitute for topic read authority, and a
   * caller must establish that separately.
   */
  boolean writableBy(Relay.SubscriptionKey subscription, String account);

  record Lease(
      Relay.SubscriptionKey subscription,
      String worker,
      String account,
      long epoch,
      Instant until) {
    public Lease {
      Objects.requireNonNull(subscription);
      worker = RelayValues.identity(worker, "consumer worker");
      account = RelayValues.identity(account, "consumer account");
      if (epoch < 1) throw new IllegalArgumentException("consumer epoch must be positive");
      until = RelayValues.time(until);
    }
  }
}
