package io.aeyer.plowshare.server.information;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One thread and at most one pending task per publisher, independent of account/socket count. */
final class ScheduledInformationInvalidationClock implements InformationInvalidationClock {
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon().name("information-hints").factory());

  public void schedule(Runnable action) {
    timer.schedule(action, 1, TimeUnit.SECONDS);
  }

  public void close() {
    timer.shutdownNow();
  }
}
