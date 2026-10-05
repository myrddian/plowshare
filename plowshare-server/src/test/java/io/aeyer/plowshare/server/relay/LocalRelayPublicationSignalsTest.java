package io.aeyer.plowshare.server.relay;

import static io.aeyer.plowshare.server.relay.RelayDispatchFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LocalRelayPublicationSignalsTest {
  @Test
  void signals_wake_each_group_and_preserve_a_signal_between_reading_and_waiting()
      throws Exception {
    var signals = new LocalRelayPublicationSignals();
    var other = new Relay.SubscriptionKey(SUB.topic(), "relay.notices.other");
    try (var first = signals.listen(SUB);
        var second = signals.listen(other);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      signals.published(SUB.topic());
      executor
          .submit(
              () -> {
                first.await(Duration.ofSeconds(30));
                return true;
              })
          .get(1, TimeUnit.SECONDS);
      executor
          .submit(
              () -> {
                second.await(Duration.ofSeconds(30));
                return true;
              })
          .get(1, TimeUnit.SECONDS);
      signals.published(SUB.topic());
      executor
          .submit(
              () -> {
                second.await(Duration.ofSeconds(30));
                return true;
              })
          .get(1, TimeUnit.SECONDS);
    }
  }

  @Test
  void absent_signals_fall_back_without_requiring_a_publication() throws Exception {
    var signals = new LocalRelayPublicationSignals();
    try (var waiting = signals.listen(SUB);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      assertTrue(
          executor
              .submit(
                  () -> {
                    waiting.await(Duration.ofMillis(1));
                    return true;
                  })
              .get(1, TimeUnit.SECONDS));
    }
  }
}
