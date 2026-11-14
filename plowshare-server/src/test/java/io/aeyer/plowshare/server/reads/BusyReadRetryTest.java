package io.aeyer.plowshare.server.reads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class BusyReadRetryTest {
  private final List<Duration> pauses = new ArrayList<>();
  private final BusyReadRetry retry = new BusyReadRetry(4, Duration.ofMillis(100), pauses::add);

  @Test
  void aTransientBusyRefusalIsRetriedWithGrowingPausesUntilTheCheckPasses() {
    var attempts = new AtomicInteger();
    retry.run(
        () -> {
          if (attempts.incrementAndGet() < 3) throw new ReadUnavailableException();
        });
    assertEquals(3, attempts.get());
    assertEquals(List.of(Duration.ofMillis(100), Duration.ofMillis(200)), pauses);
  }

  @Test
  void theLastBusyRefusalIsRethrownOnceTheAttemptsAreSpent() {
    var attempts = new AtomicInteger();
    var last = new ReadUnavailableException[1];
    var thrown =
        assertThrows(
            ReadUnavailableException.class,
            () ->
                retry.run(
                    () -> {
                      attempts.incrementAndGet();
                      last[0] = new ReadUnavailableException();
                      throw last[0];
                    }));
    assertSame(last[0], thrown);
    assertEquals(4, attempts.get());
    assertEquals(3, pauses.size());
  }

  @Test
  void otherFailuresAreNeverRetried() {
    var attempts = new AtomicInteger();
    assertThrows(
        IllegalStateException.class,
        () ->
            retry.run(
                () -> {
                  attempts.incrementAndGet();
                  throw new IllegalStateException("denied");
                }));
    assertEquals(1, attempts.get());
    assertTrue(pauses.isEmpty());
  }

  @Test
  void aCallerHoldingATransactionIsNotRetriedBecauseItMayHoldLocks() {
    var attempts = new AtomicInteger();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThrows(
          ReadUnavailableException.class,
          () ->
              retry.run(
                  () -> {
                    attempts.incrementAndGet();
                    throw new ReadUnavailableException();
                  }));
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    assertEquals(1, attempts.get());
    assertTrue(pauses.isEmpty());
  }

  @Test
  void anInterruptedPauseStopsRetryingAndKeepsTheInterrupt() {
    var interrupted =
        new BusyReadRetry(
            4,
            Duration.ofMillis(100),
            ignored -> {
              throw new InterruptedException();
            });
    var attempts = new AtomicInteger();
    try {
      assertThrows(
          ReadUnavailableException.class,
          () ->
              interrupted.run(
                  () -> {
                    attempts.incrementAndGet();
                    throw new ReadUnavailableException();
                  }));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    assertEquals(1, attempts.get());
  }

  @Test
  void boundsAreValidated() {
    assertThrows(
        IllegalArgumentException.class, () -> new BusyReadRetry(0, Duration.ofMillis(1), d -> {}));
    assertThrows(
        IllegalArgumentException.class, () -> new BusyReadRetry(11, Duration.ofMillis(1), d -> {}));
    assertThrows(
        IllegalArgumentException.class, () -> new BusyReadRetry(2, Duration.ZERO, d -> {}));
    assertThrows(
        IllegalArgumentException.class, () -> new BusyReadRetry(2, Duration.ofSeconds(2), d -> {}));
  }
}
