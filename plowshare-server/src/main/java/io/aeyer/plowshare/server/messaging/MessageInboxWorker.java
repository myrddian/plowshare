package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.server.relay.RelayWorkerProperties;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded admission discovery after recovery. No model or network work occurs in this worker. */
public final class MessageInboxWorker implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(MessageInboxWorker.class);
  private final MessageInboxes inboxes;
  private final RelayWorkerProperties properties;
  private final MessageDefinitions definitions;
  private final AtomicBoolean started = new AtomicBoolean();
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            var thread = new Thread(task, "message-inbox-admission");
            thread.setDaemon(true);
            return thread;
          });

  public MessageInboxWorker(
      MessageInboxes inboxes, RelayWorkerProperties properties, MessageDefinitions definitions) {
    this.inboxes = Objects.requireNonNull(inboxes);
    this.properties = Objects.requireNonNull(properties);
    this.definitions = Objects.requireNonNull(definitions);
  }

  /** Started only after native recovery has settled interrupted paid work. */
  public void start() {
    if (started.compareAndSet(false, true))
      executor.scheduleWithFixedDelay(
          () -> {
            try {
              inboxes.poll();
              inboxes.retireMissing(definitions);
              inboxes.cleanupRetired();
            } catch (RuntimeException failure) {
              LOG.warn("Messaging inbox admission is blocked; durable positions remain unchanged");
            }
          },
          0,
          properties.getIdleInterval().toMillis(),
          TimeUnit.MILLISECONDS);
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
