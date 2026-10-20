package io.aeyer.plowshare.sdk;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded observer of a shared lazy upgrade. Detaching never cancels sibling callers. */
final class OpeningWaiter {
  private static final ScheduledThreadPoolExecutor CLOCK = clock();
  private final CompletableFuture<Plowshare> answer;
  private final AtomicBoolean terminal;
  private final Semaphore capacity;
  private ScheduledFuture<?> deadline;
  private AutoCloseable cancellation;

  OpeningWaiter(CompletableFuture<Plowshare> answer, AtomicBoolean terminal, Semaphore capacity) {
    this.answer = answer;
    this.terminal = terminal;
    this.capacity = capacity;
  }

  private static ScheduledThreadPoolExecutor clock() {
    var result =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().daemon().name("plowshare-sdk-open-deadline").factory());
    result.setRemoveOnCancelPolicy(true);
    return result;
  }

  void arm(RequestOptions options) {
    synchronized (this) {
      deadline =
          CLOCK.schedule(
              () ->
                  finish(
                      null,
                      new Plowshare.TransportException(
                          Plowshare.Delivery.NOT_SUBMITTED, "Connection wait deadline expired")),
              options.timeout().toNanos(),
              TimeUnit.NANOSECONDS);
      if (options.cancellation() != null) {
        cancellation =
            options
                .cancellation()
                .register(
                    () ->
                        finish(
                            null,
                            new Plowshare.TransportException(
                                Plowshare.Delivery.NOT_SUBMITTED, "Connection wait cancelled")));
        if (terminal.get()) unregister();
      }
    }
    answer.whenComplete(
        (value, failure) -> {
          if (answer.isCancelled())
            finish(
                null,
                new Plowshare.TransportException(
                    Plowshare.Delivery.NOT_SUBMITTED, "Connection waiter cancelled"));
        });
  }

  void finish(Plowshare value, Throwable failure) {
    if (!terminal.compareAndSet(false, true)) return;
    synchronized (this) {
      if (deadline != null) deadline.cancel(false);
      unregister();
    }
    Thread.ofVirtual()
        .name("plowshare-sdk-open-waiter")
        .start(
            () -> {
              try {
                if (failure == null) answer.complete(value);
                else answer.completeExceptionally(failure);
              } finally {
                capacity.release();
              }
            });
  }

  private void unregister() {
    if (cancellation == null) return;
    try {
      cancellation.close();
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
    cancellation = null;
  }
}
