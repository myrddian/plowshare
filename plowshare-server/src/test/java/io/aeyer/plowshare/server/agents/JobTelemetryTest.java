package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JobTelemetryTest {
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final MicrometerJobTelemetry telemetry = new MicrometerJobTelemetry(registry);

  @AfterEach
  void closeRegistry() {
    registry.close();
  }

  private JobStore store() {
    return new JobStore(
        mock(JobRuntime.class), JobEvents.NONE, null, null, Duration.ZERO, telemetry);
  }

  @Test
  void actualTasksCountStartsActiveEndingsAndDurationsWithoutJobOrAgentLabels() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var jobs = store()) {
      String id =
          jobs.submit(
              "private-agent",
              cancelled -> {
                entered.countDown();
                await(release);
                return outcome(Outcome.Ending.ANSWERED);
              });
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(1, counter("started"));
        assertEquals(1, active());
        assertEquals(0, completed("answered"));
        assertEquals(Job.State.RUNNING, jobs.get(id).state());
      } finally {
        release.countDown();
      }
      until(() -> active() == 0);
      assertEquals(1, completed("answered"));
      assertEquals(Job.State.DONE, jobs.get(id).state());
      var duration =
          registry.get("plowshare.jobs.duration").tag("ending", "answered").functionTimer();
      assertEquals(1, duration.count());
      assertTrue(duration.totalTime(TimeUnit.SECONDS) >= 0);
      assertTrue(
          registry.getMeters().stream()
              .flatMap(m -> m.getId().getTags().stream())
              .allMatch(tag -> tag.getKey().equals("ending")));
    }
  }

  @Test
  void cancellationCountsOnlyWhenWorkActuallyEndsAndRuntimeFailureCountsUnavailable()
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var jobs = store()) {
      String id =
          jobs.submit(
              "cancellable",
              cancelled -> {
                entered.countDown();
                await(release);
                return outcome(
                    cancelled.getAsBoolean() ? Outcome.Ending.CANCELLED : Outcome.Ending.ANSWERED);
              });
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertTrue(jobs.cancel(id));
        assertFalse(jobs.cancel(id));
        assertEquals(1, active());
        assertEquals(0, completed("cancelled"));
      } finally {
        release.countDown();
      }
      until(() -> active() == 0);
      assertEquals(1, completed("cancelled"));
      jobs.submit(
          "failing",
          cancelled -> {
            throw new IllegalStateException("private-failure");
          });
      until(() -> completed("unavailable") == 1 && active() == 0);
      assertEquals(2, counter("started"));
    }
  }

  @Test
  void shutdownRefusalDoesNotInventAStartOrCompletion() {
    var jobs = store();
    jobs.close();
    assertThrows(
        IllegalStateException.class,
        () -> jobs.submit("rejected", cancelled -> outcome(Outcome.Ending.ANSWERED)));
    assertEquals(1, counter("rejected"));
    assertEquals(0, counter("started"));
    assertEquals(0, active());
    assertEquals(0, completed("answered"));
  }

  @Test
  void concurrentDuplicateFinishesCannotDoubleCountOrMakeActiveNegative() throws Exception {
    var run = telemetry.started();
    try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 50; i++) callers.submit(() -> run.finished(Outcome.Ending.ANSWERED));
    }
    assertEquals(1, counter("started"));
    assertEquals(1, completed("answered"));
    assertEquals(0, active());
  }

  private static Outcome outcome(Outcome.Ending ending) {
    return new Outcome(ending, "", 0, 0, "");
  }

  private double active() {
    return registry.get("plowshare.jobs.active").gauge().value();
  }

  private double counter(String suffix) {
    return registry.get("plowshare.jobs." + suffix).functionCounter().count();
  }

  private double completed(String ending) {
    return registry.get("plowshare.jobs.completed").tag("ending", ending).functionCounter().count();
  }

  private static void until(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) fail("condition not reached");
      Thread.sleep(5);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }
}
