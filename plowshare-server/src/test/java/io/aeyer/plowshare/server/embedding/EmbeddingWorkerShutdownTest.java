package io.aeyer.plowshare.server.embedding;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class EmbeddingWorkerShutdownTest {
  @Test
  void lifecycle_callback_waits_for_the_in_flight_pass_and_shutdown_cannot_restart_it()
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var stopped = new CountDownLatch(1);
    var interrupted = new AtomicBoolean();
    var properties = new EmbeddingProperties(true, null, null, false, java.util.List.of());
    var worker =
        new EmbeddingConfig()
            .dualEmbeddingRepairWorker(
                () -> {
                  entered.countDown();
                  try {
                    release.await();
                  } catch (InterruptedException failure) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                  }
                  return 0;
                },
                properties);
    worker.start();
    try (var closer = Executors.newSingleThreadExecutor()) {
      assertTrue(entered.await(4, TimeUnit.SECONDS));
      var draining =
          closer.submit(
              () -> {
                worker.stop();
              });
      worker.stop(stopped::countDown);
      assertFalse(
          stopped.await(100, TimeUnit.MILLISECONDS), "second stop cannot acknowledge early");
      assertTrue(worker.isRunning(), "Spring must observe the in-flight drain");
      release.countDown();
      assertTrue(stopped.await(2, TimeUnit.SECONDS));
      draining.get(2, TimeUnit.SECONDS);
      assertFalse(interrupted.get());
      worker.start();
      assertFalse(worker.isRunning());
    } finally {
      release.countDown();
      worker.stop();
    }
  }
}
