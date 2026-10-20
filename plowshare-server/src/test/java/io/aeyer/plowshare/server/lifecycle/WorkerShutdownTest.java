package io.aeyer.plowshare.server.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(5)
class WorkerShutdownTest {
  @Test
  void an_in_flight_transaction_finishes_without_interruption_and_no_periodic_pass_restarts()
      throws Exception {
    var worker = Executors.newSingleThreadScheduledExecutor();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var interrupted = new AtomicBoolean();
    worker.scheduleWithFixedDelay(
        () -> {
          entered.countDown();
          try {
            release.await();
          } catch (InterruptedException failure) {
            interrupted.set(true);
            Thread.currentThread().interrupt();
          }
        },
        0,
        1,
        TimeUnit.MILLISECONDS);
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    try (var stopping = Executors.newSingleThreadExecutor()) {
      var stopped = stopping.submit(() -> WorkerShutdown.stop(worker, "test"));
      while (!worker.isShutdown()) Thread.onSpinWait();
      assertFalse(stopped.isDone());
      release.countDown();
      stopped.get(2, TimeUnit.SECONDS);
      assertTrue(worker.isTerminated());
      assertFalse(interrupted.get());
      WorkerShutdown.stop(worker, "test");
    } finally {
      release.countDown();
      worker.shutdownNow();
    }
  }

  @Test
  void a_stuck_pass_is_cancelled_only_after_the_bounded_grace_period() throws Exception {
    var worker = Executors.newSingleThreadExecutor();
    var entered = new CountDownLatch(1);
    var interrupted = new AtomicBoolean();
    worker.execute(
        () -> {
          entered.countDown();
          try {
            new CountDownLatch(1).await();
          } catch (InterruptedException failure) {
            interrupted.set(true);
            Thread.currentThread().interrupt();
          }
        });
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    WorkerShutdown.stop(worker, "stuck-test", Duration.ofMillis(10), Duration.ofSeconds(1));
    assertTrue(interrupted.get());
    assertTrue(worker.isTerminated());
  }

  @Test
  void interruption_of_the_stopping_thread_is_retained_after_cancellation() {
    var worker = Executors.newSingleThreadExecutor();
    worker.execute(() -> {});
    Thread.currentThread().interrupt();
    try {
      WorkerShutdown.stop(worker, "interrupted-test");
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(worker.isShutdown());
    } finally {
      Thread.interrupted();
      worker.shutdownNow();
    }
  }
}
