package io.aeyer.plowshare.server.relay;

import java.time.Duration;

/** Wakeup hints only. Durable offsets and periodic reads recover missed signals and outages. */
public interface RelayPublicationSignals extends AutoCloseable {
  void published(Relay.TopicKey topic);

  /** Registers before the first read so a publication between reading and waiting is observed. */
  Waiting listen(Relay.SubscriptionKey subscription);

  /**
   * Register global durable-work discovery before its first scan. Legacy/local owners still poll.
   */
  default Waiting discovery() {
    return new Waiting() {
      public void await(Duration maximum) throws InterruptedException {
        Thread.sleep(maximum);
      }

      public void close() {}
    };
  }

  /** Healthy notification transport permits a longer bounded reconciliation interval. */
  default Duration reconciliationWait(Duration fallback) {
    return fallback;
  }

  @Override
  default void close() {}

  /** One owner awaits each handle; close may run concurrently to interrupt that wait. */
  interface Waiting extends AutoCloseable {
    void await(Duration maximum) throws InterruptedException;

    @Override
    void close();
  }
}
