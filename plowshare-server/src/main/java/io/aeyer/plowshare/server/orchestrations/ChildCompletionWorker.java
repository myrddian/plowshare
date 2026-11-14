package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.server.relay.RelayWorkerProperties;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded, rotating discovery after orchestration recovery; no work lives in volatile cursors. */
public final class ChildCompletionWorker implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(ChildCompletionWorker.class);
  private final ChildCompletions repository;
  private final ChildCompletionConsumer consumer;
  private final RelayWorkerProperties properties;
  private final boolean enabled;
  private final AtomicBoolean started = new AtomicBoolean();
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            var thread = new Thread(task, "child-completion-discovery");
            thread.setDaemon(true);
            return thread;
          });
  private long after = -1;

  public ChildCompletionWorker(
      ChildCompletions repository,
      ChildCompletionConsumer consumer,
      RelayWorkerProperties properties,
      boolean enabled) {
    this.repository = Objects.requireNonNull(repository);
    this.consumer = Objects.requireNonNull(consumer);
    this.properties = Objects.requireNonNull(properties);
    this.enabled = enabled;
  }

  public void start() {
    if (enabled && started.compareAndSet(false, true))
      executor.scheduleWithFixedDelay(
          this::poll, 0, properties.getIdleInterval().toMillis(), TimeUnit.MILLISECONDS);
  }

  private void poll() {
    try {
      // One scope per tick keeps memory constant and rotates a busy project behind others.
      var projects = repository.ready(after, 1);
      if (projects.isEmpty()) {
        after = -1;
        return;
      }
      long project = projects.getFirst();
      after = project;
      consumer.consume(project, properties.getBatchSize());
    } catch (RuntimeException failed) {
      LOG.warn("Child completion delivery paused; owning reports remain pending");
    }
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
