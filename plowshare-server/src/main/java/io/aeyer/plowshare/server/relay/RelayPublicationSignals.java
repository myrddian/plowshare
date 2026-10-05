package io.aeyer.plowshare.server.relay;

import java.time.Duration;

/** Local wakeup hints only. Durable offsets and fallback reads recover missed or remote signals. */
public interface RelayPublicationSignals {
  void published(Relay.TopicKey topic);

  /** Registers before the first read so a publication between reading and waiting is observed. */
  Waiting listen(Relay.SubscriptionKey subscription);

  interface Waiting extends AutoCloseable {
    void await(Duration maximum) throws InterruptedException;

    @Override
    void close();
  }
}
