package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.server.relay.RelayWorkerProperties;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coalesced post-commit capture/publication; starts only after interrupted native work recovery.
 */
public final class MessageRelayWorker implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(MessageRelayWorker.class);
  private final MessageRelayOutbox outbox;
  private final RelayWorkerProperties properties;
  private final AtomicBoolean queued = new AtomicBoolean();
  private boolean started, closed;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            var thread = new Thread(task, "message-relay-snapshots");
            thread.setDaemon(true);
            return thread;
          });

  public MessageRelayWorker(MessageRelayOutbox outbox, RelayWorkerProperties properties) {
    this.outbox = Objects.requireNonNull(outbox);
    this.properties = Objects.requireNonNull(properties);
  }

  public synchronized void start() {
    if (started || closed) return;
    started = true;
    executor.scheduleWithFixedDelay(
        this::drain, 0, properties.getIdleInterval().toMillis(), TimeUnit.MILLISECONDS);
    signal();
  }

  public synchronized void signal() {
    if (started && !closed && queued.compareAndSet(false, true))
      executor.execute(
          () -> {
            try {
              drain();
            } finally {
              queued.set(false);
            }
          });
  }

  private void drain() {
    try {
      for (int count = 0; count < 32 && outbox.captureNext(); count++) {}
      for (int count = 0; count < 32 && outbox.publishNext(); count++) {}
    } catch (RuntimeException unavailable) {
      LOG.warn(
          "Native snapshot category=WORKER_FAILED; inspect scoped retained operation diagnostics");
    }
  }

  @Override
  public synchronized void close() {
    closed = true;
    started = false;
    executor.shutdownNow();
  }
}
