package io.aeyer.plowshare.server.ws;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** One handler/server's shared read permits, acquired before creating a read worker. */
final class EventReadCapacity {
  private final int perConnection;
  private final Semaphore permits;

  EventReadCapacity(int perConnection, int global) {
    if (perConnection < 1 || global < perConnection)
      throw new IllegalArgumentException(
          "event read concurrency must be positive and not exceed the global bound");
    this.perConnection = perConnection;
    permits = new Semaphore(global, true);
  }

  int perConnection() {
    return perConnection;
  }

  boolean acquire(BooleanSupplier open) throws InterruptedException {
    // Only the existing connection coordinator waits here. No read worker or executor queue exists
    // before admission. A bounded wait lets a closed socket discard its selected, unstarted frame.
    while (open.getAsBoolean()) {
      if (permits.tryAcquire(100, TimeUnit.MILLISECONDS)) return true;
    }
    return false;
  }

  void release() {
    permits.release();
  }
}
