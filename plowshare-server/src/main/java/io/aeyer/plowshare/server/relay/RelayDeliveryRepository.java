package io.aeyer.plowshare.server.relay;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Atomic persistence of admitted input, branches, fenced claims and dispatch receipts. */
public interface RelayDeliveryRepository {
  /** Admission and subscriber advancement share the topic transaction/lock with append/expiry. */
  RelayDeliveries.Admission admit(
      RelayDeliveries.AdmissionKey key, RelayDeliveries.Decision decision, Instant now);

  Optional<RelayDeliveries.Admission> admission(RelayDeliveries.AdmissionKey key);

  Optional<RelayDeliveries.Delivery> delivery(RelayDeliveries.DeliveryKey key);

  /** Claim selection uses row locks; each available branch receives only one active generation. */
  Optional<RelayDeliveries.Delivery> claim(
      Relay.SubscriptionKey key, String worker, Duration lease, Instant now);

  RelayDeliveries.Delivery prepareDispatch(RelayDeliveries.Claim claim, Instant now);

  RelayDeliveries.Delivery renew(RelayDeliveries.Claim claim, Duration lease, Instant now);

  RelayDeliveries.Delivery accepted(
      RelayDeliveries.Claim claim, RelayDeliveries.Receipt receipt, Instant now);

  RelayDeliveries.Delivery failed(RelayDeliveries.Claim claim, String code, Instant now);

  RelayDeliveries.Delivery uncertain(RelayDeliveries.Claim claim, String code, Instant now);

  RelayDeliveries.Delivery reconcile(
      RelayDeliveries.DeliveryKey key, RelayDeliveries.Resolution resolution, Instant now);

  /**
   * Expired claimed work becomes ready; expired prepared dispatch becomes uncertain, never ready.
   */
  int recoverExpired(Instant now, int limit);

  /**
   * Evicts at most 1,000 fully settled admissions older than the execution retention, measured from
   * admission and the latest outcome. Pending/uncertain work is never silently deleted.
   */
  int pruneSettled(Instant now, Duration retention, int limit);
}
