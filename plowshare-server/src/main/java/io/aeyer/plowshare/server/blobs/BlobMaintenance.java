package io.aeyer.plowshare.server.blobs;

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
 * Bounded cleanup with durable markers; never scans unregistered disk files or repeats producers.
 */
public final class BlobMaintenance
    implements AutoCloseable, ApplicationListener<ApplicationReadyEvent> {
  private static final Logger LOG = LoggerFactory.getLogger(BlobMaintenance.class);
  private final BinaryBlobStorage blobs;
  private final BlobProperties properties;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon().name("binary-blob-cleanup").factory());
  private final AtomicBoolean started = new AtomicBoolean();

  public BlobMaintenance(BinaryBlobStorage blobs, BlobProperties properties) {
    this.blobs = Objects.requireNonNull(blobs);
    this.properties = Objects.requireNonNull(properties);
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent ready) {
    if (!properties.isCleanupEnabled() || !started.compareAndSet(false, true)) return;
    long interval = properties.getCleanupInterval().toMillis();
    executor.scheduleWithFixedDelay(
        () -> {
          try {
            blobs.collect(properties.getCleanupBatchSize());
          } catch (RuntimeException failure) {
            // The registry retains DELETING markers for the next pass. Avoid private disk paths in
            // logs.
            LOG.warn(
                "Binary blob cleanup failed; will retry ({})", failure.getClass().getSimpleName());
          }
        },
        interval,
        interval,
        TimeUnit.MILLISECONDS);
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
