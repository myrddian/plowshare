package io.aeyer.plowshare.server.relay;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
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
  private final AtomicBoolean sweeping = new AtomicBoolean();
  private final LongSupplier nanoTime;
  private RelayApprovalInboxRepository approvalInbox;
  private RelayPayloadStore payloads;

  public void useApprovalInbox(RelayApprovalInboxRepository repository) {
    approvalInbox = Objects.requireNonNull(repository);
  }

  /** Registers payload reclamation; without a store the pass is skipped. */
  public void usePayloads(RelayPayloadStore store) {
    payloads = Objects.requireNonNull(store);
  }

  public RelayMaintenance(
      RelayRepository repository,
      RelayDeliveryRepository deliveries,
      RelayExecutions executions,
      RelayProperties properties,
      Supplier<Instant> clock,
      RelayOperationRepository operations,
      RelayNativeRepository nativeInboxes) {
    this(
        repository,
        deliveries,
        executions,
        properties,
        clock,
        operations,
        nativeInboxes,
        System::nanoTime);
  }

  RelayMaintenance(
      RelayRepository repository,
      RelayDeliveryRepository deliveries,
      RelayExecutions executions,
      RelayProperties properties,
      Supplier<Instant> clock,
      RelayOperationRepository operations,
      RelayNativeRepository nativeInboxes,
      LongSupplier nanoTime) {
    this.nanoTime = Objects.requireNonNull(nanoTime);
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
    // Scheduled and manually requested passes share one local lane. Other processes partition
    // payload work with SKIP LOCKED; no connection is borrowed to wait for another local pass.
    if (!sweeping.compareAndSet(false, true)) return 0;
    try {
      return sweepOnce();
    } finally {
      sweeping.set(false);
    }
  }

  private int sweepOnce() {
    Instant now = RelayValues.time(clock.get());
    deliveries.recoverExpired(now, properties.getDeliveryBatchSize());
    deliveries.pruneSettled(
        now, properties.getSettledRetention(), properties.getDeliveryBatchSize());
    executions.prune(now, properties.getSettledRetention(), properties.getDeliveryBatchSize());
    operations.prune(properties.getOperatorRetention(), properties.getDeliveryBatchSize());
    int deleted =
        nativeInboxes.prune(properties.getOperatorRetention(), properties.getDeliveryBatchSize());
    if (approvalInbox != null)
      deleted +=
          approvalInbox.prune(properties.getOperatorRetention(), properties.getDeliveryBatchSize());
    for (var topic : repository.topicsToPrune(now, properties.getTopicBatchSize())) {
      deleted += repository.prune(topic, now, properties.getPublicationBatchSize());
    }
    // After the publication prune so references it just removed are already gone when payloads are
    // examined. Only payloads whose ejection_at has matured are examined, and each examination
    // re-checks reachability.
    if (payloads != null) deleted += sweepPayloads(now);
    else LOG.warn("Relay payload store is not registered; unreferenced payloads are not reclaimed");
    return deleted;
  }

  /**
   * Catch up without an unbounded loop or a catch-up thread pool. The time budget is checked
   * between atomic batches; one in-flight batch can exceed it, bounded by repository timeouts.
   * Empty/locked batches stop immediately. A full batch alone is ordinary progress, not overload.
   */
  private int sweepPayloads(Instant now) {
    long start = nanoTime.getAsLong();
    int examined = 0;
    int removed = 0;
    int batches = 0;
    java.util.Optional<Instant> oldest = java.util.Optional.empty();
    do {
      var result = payloads.sweepBatch(now, properties.getPayloadSweepBatch());
      batches++;
      examined += result.examined();
      removed += result.removed();
      oldest = result.oldestDueAt();
      if (result.examined() == 0 || oldest.isEmpty()) break;
    } while (batches < properties.getPayloadSweepMaxBatches()
        && nanoTime.getAsLong() - start < properties.getPayloadSweepBudget().toNanos());
    long elapsedMs = (nanoTime.getAsLong() - start) / 1_000_000;
    long oldestAgeSeconds =
        oldest.map(at -> Math.max(0, Duration.between(at, now).toSeconds())).orElse(0L);
    if (examined > 0)
      LOG.info(
          "Relay payload cleanup: examined={}, removed={}, batches={}, elapsedMs={}, backlog={}, oldestDueSeconds={}",
          examined,
          removed,
          batches,
          elapsedMs,
          oldest.isPresent(),
          oldestAgeSeconds);
    if (oldest.isPresent()
        && oldestAgeSeconds >= properties.getPayloadBacklogWarningAge().toSeconds())
      LOG.warn(
          "Relay payload cleanup backlog exceeded warning age: oldestDueSeconds={}, examined={}, batches={}",
          oldestAgeSeconds,
          examined,
          batches);
    return removed;
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
