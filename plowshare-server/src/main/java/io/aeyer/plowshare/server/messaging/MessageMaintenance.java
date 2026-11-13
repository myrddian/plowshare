package io.aeyer.plowshare.server.messaging;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/** Bounded deadline sweep. It never retries inference or mutating agent tools. */
public final class MessageMaintenance
    implements AutoCloseable, ApplicationListener<ApplicationReadyEvent> {
  private static final Logger LOG = LoggerFactory.getLogger(MessageMaintenance.class);
  private final ScheduledExecutorService thread =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread timer = new Thread(r, "message-deadlines");
            timer.setDaemon(true);
            return timer;
          });
  private final MessageService messages;
  private final AtomicBoolean started = new AtomicBoolean();

  public MessageMaintenance(MessageService messages) {
    this.messages = messages;
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent ready) {
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
