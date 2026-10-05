package io.aeyer.plowshare.server.relay;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/** Bounded retention and expired-claim recovery; never dispatches or retries receiver effects. */
public final class RelayMaintenance
    implements AutoCloseable, ApplicationListener<ApplicationReadyEvent> {
  private static final Logger LOG = LoggerFactory.getLogger(RelayMaintenance.class);
  private final RelayRepository repository;
  private final RelayDeliveryRepository deliveries;
  private final RelayExecutions executions;
  private final RelayProperties properties;
  private final RelayOperationRepository operations;
  private final RelayNativeRepository nativeInboxes;
  private final Supplier<Instant> clock;
  private final ScheduledExecutorService executor;
  private final AtomicBoolean started = new AtomicBoolean();

  public RelayMaintenance(
      RelayRepository repository,
      RelayDeliveryRepository deliveries,
      RelayExecutions executions,
      RelayProperties properties,
      Supplier<Instant> clock,
      RelayOperationRepository operations,
      RelayNativeRepository nativeInboxes) {
    this.nativeInboxes = Objects.requireNonNull(nativeInboxes);
    this.operations = Objects.requireNonNull(operations);
    this.repository = Objects.requireNonNull(repository, "repository");
    this.deliveries = Objects.requireNonNull(deliveries, "deliveries");
    this.executions = Objects.requireNonNull(executions, "executions");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.executor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "relay-retention");
              thread.setDaemon(true);
              return thread;
            });
  }

  /** One bounded pass, also usable with a controlled clock in tests. */
  public int sweep() {
    Instant now = RelayValues.time(clock.get());
    deliveries.recoverExpired(now, properties.getDeliveryBatchSize());
    deliveries.pruneSettled(
        now, properties.getSettledRetention(), properties.getDeliveryBatchSize());
    executions.prune(now, properties.getSettledRetention(), properties.getDeliveryBatchSize());
    operations.prune(properties.getOperatorRetention(), properties.getDeliveryBatchSize());
    int deleted =
        nativeInboxes.prune(properties.getOperatorRetention(), properties.getDeliveryBatchSize());
    for (var topic : repository.topicsToPrune(now, properties.getTopicBatchSize())) {
      deleted += repository.prune(topic, now, properties.getPublicationBatchSize());
    }
    return deleted;
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent ready) {
    if (!properties.isCleanupEnabled() || !started.compareAndSet(false, true)) return;
    long interval = properties.getCleanupInterval().toMillis();
    executor.scheduleWithFixedDelay(
        () -> {
          try {
            sweep();
          } catch (RuntimeException failure) {
            // Later passes can safely retry atomic eviction; this cannot replay a receiver effect.
            LOG.warn("Relay retention sweep failed", failure);
          }
        },
        interval,
        interval,
        TimeUnit.MILLISECONDS);
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
