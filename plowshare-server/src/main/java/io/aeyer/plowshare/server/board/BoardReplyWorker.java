package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.server.relay.RelayWorkerProperties;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * Bounded durable outbox drain; starts after application recovery and stops with the application.
 */
public final class BoardReplyWorker
    implements AutoCloseable, ApplicationListener<ApplicationReadyEvent> {
  private static final Logger LOG = LoggerFactory.getLogger(BoardReplyWorker.class);
  private final BoardReplyPublications replies;
  private final RelayWorkerProperties properties;
  private final AtomicBoolean started = new AtomicBoolean();
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            var thread = new Thread(task, "board-relay-replies");
            thread.setDaemon(true);
            return thread;
          });

  public BoardReplyWorker(BoardReplyPublications replies, RelayWorkerProperties properties) {
    this.replies = Objects.requireNonNull(replies);
    this.properties = Objects.requireNonNull(properties);
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    if (started.compareAndSet(false, true))
      executor.scheduleWithFixedDelay(
          this::drain, 0, properties.getIdleInterval().toMillis(), TimeUnit.MILLISECONDS);
  }

  private void drain() {
    try {
      for (int index = 0;
          index < properties.getBatchSize() && !Thread.currentThread().isInterrupted();
          index++) if (!replies.publishNext()) break;
    } catch (RuntimeException failed) {
      // The owning transaction rolled back, leaving the answer durably pending. Diagnostics from
      // serializers and SQL can contain message text, so do not log the exception or its cause.
      LOG.warn("Board Relay replies remain pending after a publication failure");
    }
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
