package io.aeyer.plowshare.server.relay;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Internal delivery capability with broker-owned timestamps and repository-owned atomicity. */
public final class DurableRelayDeliveries implements RelayDeliveries {
  private final RelayDeliveryRepository repository;
  private final Supplier<Instant> clock;

  public DurableRelayDeliveries(RelayDeliveryRepository repository, Supplier<Instant> clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Admission admit(AdmissionKey key, Decision decision) {
    return repository.admit(
        Objects.requireNonNull(key, "key"), Objects.requireNonNull(decision, "decision"), now());
  }

  @Override
  public Optional<Admission> admission(AdmissionKey key) {
    return repository.admission(Objects.requireNonNull(key, "key"));
  }

  @Override
  public Optional<Delivery> delivery(DeliveryKey key) {
    return repository.delivery(Objects.requireNonNull(key, "key"));
  }

  @Override
  public Optional<Delivery> claim(
      Relay.SubscriptionKey subscription, String worker, Duration lease) {
    return repository.claim(
        Objects.requireNonNull(subscription, "subscription"),
        RelayValues.identity(worker, "worker"),
        RelayDeliveries.requireLease(lease),
        now());
  }

  @Override
  public Delivery prepareDispatch(Claim claim) {
    return repository.prepareDispatch(Objects.requireNonNull(claim, "claim"), now());
  }

  @Override
  public Delivery renew(Claim claim, Duration lease) {
    return repository.renew(
        Objects.requireNonNull(claim, "claim"), RelayDeliveries.requireLease(lease), now());
  }

  @Override
  public Delivery accepted(Claim claim, Receipt receipt) {
    return repository.accepted(
        Objects.requireNonNull(claim, "claim"), Objects.requireNonNull(receipt, "receipt"), now());
  }

  @Override
  public Delivery failed(Claim claim, String code) {
    return repository.failed(
        Objects.requireNonNull(claim, "claim"), RelayValues.name(code, "failure code"), now());
  }

  @Override
  public Delivery uncertain(Claim claim, String code) {
    return repository.uncertain(
        Objects.requireNonNull(claim, "claim"), RelayValues.name(code, "failure code"), now());
  }

  @Override
  public Delivery reconcile(DeliveryKey key, Resolution resolution) {
    return repository.reconcile(
        Objects.requireNonNull(key, "key"),
        Objects.requireNonNull(resolution, "resolution"),
        now());
  }

  private Instant now() {
    return RelayValues.time(clock.get());
  }
}
