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
   * Acquires or extends a lease on a <b>read-tier subscription this account owns</b> (decision
   * 0038). Empty means the subscription is not this account's — a work-tier, project-declared
   * subscriber or another reader's — or its lease is held live by another owner. Ownership is
   * decided by {@code relay_subscriptions.reader_account} in the same statement that writes the
   * lease, so the decision cannot race the write it authorizes.
   *
   * <p>A lapsed lease on the account's own subscription may be taken over, which is how its own
   * crashed consumer recovers. That is the only takeover this method permits: it is reclaiming the
   * caller's own lease, not acquiring someone else's.
   *
   * <p>This is the ownership half of the authority only. Topic read authority is a separate
   * condition the caller must have established.
   */
  Optional<Lease> acquireAsReader(
      Relay.SubscriptionKey subscription, String account, Duration duration);

  /**
   * Advances the leased subscription's own seen position atomically with a live ownership check, in
   * one transaction: the lease row is locked and verified against this exact owner, account and
   * epoch, and the cursor write commits with it. A caller holding no live lease cannot advance a
   * position, so an absent or lapsed lease is never an opportunity to move someone else's cursor.
   *
   * @throws IllegalStateException if the lease is not the live owner of the subscription
   */
  Relay.Subscription advanceSeen(Lease lease, long position);

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
