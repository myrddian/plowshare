package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.events.AccountPushes;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * At-least-once invalidation hints. Clients recover authoritative state through scoped cursor
 * reads.
 */
public final class InformationEventPublisher implements AutoCloseable {
  private final InformationEventRepository events;
  private final AccountPushes pushes;
  private final Clock clock;
  private ScheduledExecutorService worker;
  private boolean closed;
  private final java.util.concurrent.CompletableFuture<Void> stopped =
      new java.util.concurrent.CompletableFuture<>();

  public InformationEventPublisher(
      InformationEventRepository events, AccountPushes pushes, Clock clock) {
    this.events = events;
    this.pushes = pushes;
    this.clock = clock;
  }

  public boolean drainOne() {
    UUID token = UUID.randomUUID();
    var batch = events.claim(token, clock.instant());
    if (batch.isEmpty()) return false;
    for (var event : batch) {
      var body =
          new io.aeyer.plowshare.protocol.AccountEvent.InformationChanged(
              event.sequence(), event.revision(), event.generation());
      for (String account : events.recipients(event.sequence())) {
        // Revocation hints must reach former readers so they discard cached information.
        boolean reduction =
            List.of("withdrawn", "excluded", "deleted", "unshared", "unlinked")
                .contains(event.action());
        if (reduction || events.readable(event.revision(), account)) pushes.push(account, body);
      }
    }
    return events.complete(token, batch.getLast().sequence());
  }

  public synchronized void start() {
    if (closed || worker != null) return;
    worker =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("information-events").factory());
    worker.scheduleWithFixedDelay(
        () -> {
          try {
            drainOne();
          } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(InformationEventPublisher.class)
                .warn("information events could not be delivered", failure);
          }
        },
        0,
        1,
        TimeUnit.SECONDS);
  }

  @Override
  public void close() {
    ScheduledExecutorService stopping;
    synchronized (this) {
      if (closed) return;
      closed = true;
      stopping = worker;
    }
    try {
      io.aeyer.plowshare.server.lifecycle.WorkerShutdown.stop(stopping, "information-events");
      stopped.complete(null);
    } catch (RuntimeException | Error failure) {
      stopped.completeExceptionally(failure);
      throw failure;
    }
  }

  /** Acknowledges the owning close, including when another caller already started the drain. */
  java.util.concurrent.CompletionStage<Void> shutdownCompletion() {
    return stopped.minimalCompletionStage();
  }
}
