package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Lease row locks fence admission/claim commits against reassignment. Nested delivery repository
 * transactions join this unit of work; no source reads, routing or external calls run under a lock.
 */
public final class JdbcRelayConsumerRepository implements RelayConsumerRepository {
  private final JdbcTemplate jdbc;
  private final UnitOfWork transactions;
  private final RelayDeliveryRepository deliveries;

  public JdbcRelayConsumerRepository(
      JdbcTemplate jdbc, UnitOfWork transactions, RelayDeliveryRepository deliveries) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.transactions = Objects.requireNonNull(transactions);
    this.deliveries = Objects.requireNonNull(deliveries);
  }

  @Override
  public Optional<Lease> acquire(
      Relay.SubscriptionKey subscription, String worker, String account, Duration duration) {
    Objects.requireNonNull(subscription);
    RelayValues.identity(worker, "consumer worker");
    RelayValues.identity(account, "consumer account");
    RelayDeliveries.requireLease(duration);
    return jdbc
        .query(
            """
        INSERT INTO relay_consumer_leases(scope_key,topic,subscriber,worker,account,epoch,lease_until)
        VALUES (?,?,?,?,?,1,clock_timestamp() + (? * interval '1 microsecond'))
        ON CONFLICT (scope_key,topic,subscriber) DO UPDATE SET
          worker=EXCLUDED.worker, account=EXCLUDED.account,
          epoch=relay_consumer_leases.epoch+1,
          lease_until=clock_timestamp() + (? * interval '1 microsecond')
        WHERE relay_consumer_leases.lease_until<=clock_timestamp()
           OR (relay_consumer_leases.worker=EXCLUDED.worker AND relay_consumer_leases.account=EXCLUDED.account)
        RETURNING epoch,lease_until
        """,
            (row, index) ->
                new Lease(
                    subscription,
                    worker,
                    account,
                    row.getLong("epoch"),
                    row.getObject("lease_until", OffsetDateTime.class).toInstant()),
            RelayScopeCodec.write(subscription.topic()),
            subscription.topic().name(),
            subscription.subscriber(),
            worker,
            account,
            duration.toNanos() / 1000,
            duration.toNanos() / 1000)
        .stream()
        .findFirst();
  }

  @Override
  public RelayDeliveries.Admission admit(
      Lease lease, RelayDeliveries.AdmissionKey key, RelayDeliveries.Decision decision) {
    Objects.requireNonNull(key);
    Objects.requireNonNull(decision);
    if (!Objects.requireNonNull(lease).subscription().equals(key.subscription()))
      throw new IllegalArgumentException("consumer lease differs from admission scope");
    return transactions.inTransaction(
        () -> {
          requireLive(lease);
          var admitted = deliveries.admit(key, decision, now());
          requireLive(lease);
          return admitted;
        });
  }

  @Override
  public Optional<RelayDeliveries.Delivery> claim(Lease lease, Duration duration) {
    Objects.requireNonNull(lease);
    RelayDeliveries.requireLease(duration);
    return transactions.inTransaction(
        () -> {
          requireLive(lease);
          var claimed = deliveries.claim(lease.subscription(), lease.worker(), duration, now());
          requireLive(lease);
          return claimed;
        });
  }

  @Override
  public void release(Lease lease) {
    Objects.requireNonNull(lease);
    jdbc.update(
        """
        UPDATE relay_consumer_leases SET lease_until=clock_timestamp()
        WHERE scope_key=? AND topic=? AND subscriber=? AND worker=? AND account=? AND epoch=?
        """,
        RelayScopeCodec.write(lease.subscription().topic()),
        lease.subscription().topic().name(),
        lease.subscription().subscriber(),
        lease.worker(),
        lease.account(),
        lease.epoch());
  }

  @Override
  public void requireOwned(Lease lease) {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive())
      throw new IllegalStateException("Consumer fencing requires an active transaction");
    requireLive(Objects.requireNonNull(lease));
  }

  private void requireLive(Lease lease) {
    var live =
        jdbc
            .query(
                """
        SELECT worker,account,epoch,lease_until FROM relay_consumer_leases
        WHERE scope_key=? AND topic=? AND subscriber=? FOR UPDATE
        """,
                (row, index) ->
                    new Lease(
                        lease.subscription(),
                        row.getString("worker"),
                        row.getString("account"),
                        row.getLong("epoch"),
                        row.getObject("lease_until", OffsetDateTime.class).toInstant()),
                RelayScopeCodec.write(lease.subscription().topic()),
                lease.subscription().topic().name(),
                lease.subscription().subscriber())
            .stream()
            .findFirst();
    // Read the clock after obtaining the row lock; transaction-start time can predate a long wait.
    if (live.isEmpty()
        || !live.get().worker().equals(lease.worker())
        || !live.get().account().equals(lease.account())
        || live.get().epoch() != lease.epoch()
        || !live.get().until().isAfter(now()))
      throw new IllegalStateException("Relay consumer ownership is stale");
  }

  private Instant now() {
    return Objects.requireNonNull(
            jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class))
        .toInstant();
  }
}
