package io.aeyer.plowshare.server.information;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;

/**
 * Starts durable information workers only when the application is ready, and drains them during
 * Spring's lifecycle stop phase, before repository and datasource destruction. The bean destroy
 * callbacks remain idempotent fallbacks for partially constructed contexts.
 */
final class InformationQueueLifecycle
    implements SmartLifecycle, ApplicationListener<ApplicationReadyEvent> {
  private final InformationLifecycle lifecycle;
  private final InformationAcquisitions acquisitions;
  private final InformationInvalidationPublisher invalidations;
  private volatile boolean running;
  private boolean closed;
  private final CompletableFuture<Void> stopped = new CompletableFuture<>();

  InformationQueueLifecycle(
      InformationLifecycle lifecycle,
      InformationAcquisitions acquisitions,
      InformationInvalidationPublisher invalidations) {
    this.lifecycle = Objects.requireNonNull(lifecycle);
    this.acquisitions = Objects.requireNonNull(acquisitions);
    this.invalidations = Objects.requireNonNull(invalidations);
  }

  @Override
  public boolean isAutoStartup() {
    return false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public void start() {
    synchronized (this) {
      if (closed || running) return;
      running = true;
    }
    try {
      lifecycle.start();
      acquisitions.start();
      invalidations.start();
    } catch (RuntimeException failure) {
      stop();
      throw failure;
    }
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    start();
  }

  @Override
  public void stop() {
    synchronized (this) {
      if (closed) return;
      closed = true;
    }
    try {
      invalidations.close();
      // Each close first rejects new scheduling and drains its current pass. Drain both in
      // parallel: summing independent 15-second deadlines would exceed Spring's phase budget.
      // Callbacks run outside the state lock, so reentrant stop is an idempotent no-op. Unfinished
      // leases remain durable for the next server generation. Information events need no drain of
      // their own: they are published inside the mutation's transaction onto Relay, so there is no
      // outbox pass left here that a shutdown could cut short.
      Executor stops = command -> Thread.ofVirtual().name("information-stop").start(command);
      CompletableFuture.allOf(
              CompletableFuture.runAsync(acquisitions::close, stops)
                  .thenCompose(ignored -> acquisitions.shutdownCompletion()),
              CompletableFuture.runAsync(lifecycle::close, stops)
                  .thenCompose(ignored -> lifecycle.shutdownCompletion()))
          .join();
      running = false;
      stopped.complete(null);
    } catch (RuntimeException | Error failure) {
      running = false;
      stopped.completeExceptionally(failure);
      throw failure;
    }
  }

  @Override
  public void stop(Runnable callback) {
    // Every observer waits for the owning drain, even when plain stop already started it. Spring
    // must not destroy dependencies after a second caller sees only the terminal scheduling flag.
    stopped.whenComplete((ignored, failure) -> callback.run());
    Thread.ofVirtual().name("information-shutdown").start(this::stop);
  }
}
