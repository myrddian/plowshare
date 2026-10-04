package io.aeyer.plowshare.server.board;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Bounded deadline sweep. It never retries inference or mutating agent tools. */
public final class MessageMaintenance
    implements AutoCloseable,
        org.springframework.context.ApplicationListener<
            org.springframework.boot.context.event.ApplicationReadyEvent> {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(MessageMaintenance.class);
  private final ScheduledExecutorService thread =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread timer = new Thread(r, "message-deadlines");
            timer.setDaemon(true);
            return timer;
          });
  private final BoardMessaging messages;
  private final java.util.concurrent.atomic.AtomicBoolean started =
      new java.util.concurrent.atomic.AtomicBoolean();

  public MessageMaintenance(BoardMessaging messages) {
    this.messages = messages;
  }

  @Override
  public void onApplicationEvent(
      org.springframework.boot.context.event.ApplicationReadyEvent ready) {
    if (!started.compareAndSet(false, true)) return;
    thread.scheduleWithFixedDelay(
        () -> {
          try {
            messages.expire();
          } catch (RuntimeException failed) {
            LOG.warn("message deadlines could not be swept", failed);
          }
        },
        30,
        30,
        TimeUnit.SECONDS);
  }

  @Override
  public void close() {
    thread.shutdownNow();
  }
}
