package io.aeyer.plowshare.server.llm.openai;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.aeyer.plowshare.server.llm.dispatch.ReasoningPolicy;
import io.aeyer.plowshare.server.llm.dispatch.ReasoningStoppedException;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ReasoningMonitorTest {
  static ReasoningPolicy policy() {
    return new ReasoningPolicy(
        4096,
        Duration.ofSeconds(10),
        Duration.ofSeconds(30),
        128,
        3,
        Optional.of(Sampling.Effort.LOW),
        0);
  }

  @Test
  void reasoningTimeoutStartsAtFirstThoughtAndCatchesAQuietStream() {
    AtomicLong clock = new AtomicLong(1);
    ReasoningMonitor monitor = new ReasoningMonitor(policy(), clock::get);
    clock.addAndGet(Duration.ofSeconds(20).toNanos());
    assertDoesNotThrow(monitor::check);
    monitor.thought("still considering alternatives");
    clock.addAndGet(Duration.ofSeconds(10).toNanos());
    assertThrows(ReasoningStoppedException.class, monitor::check);
  }

  @Test
  void repetitionDetectionAccumulatesAcrossUnequalChunkBoundaries() {
    ReasoningMonitor monitor = new ReasoningMonitor(policy(), () -> 1);
    String repeated = "Let me reconsider that same assumption once again. ".repeat(5);
    assertThrows(
        ReasoningStoppedException.class,
        () -> {
          for (int at = 0; at < repeated.length(); at += 7)
            monitor.thought(repeated.substring(at, Math.min(at + 7, repeated.length())));
        });
  }

  @Test
  void anyAnswerOrToolFragmentDisablesReasoningOnlyRecovery() {
    AtomicLong clock = new AtomicLong(1);
    ReasoningMonitor monitor = new ReasoningMonitor(policy(), clock::get);
    monitor.thought("considering");
    monitor.progress();
    clock.addAndGet(Duration.ofMinutes(1).toNanos());
    assertDoesNotThrow(
        () -> monitor.thought("This repeats a reasonably long sentence. ".repeat(1000)));
    assertDoesNotThrow(monitor::check);
  }

  @Test
  void recoveryRetainsDeadlineAndSamplingCopiesKeepLimits() {
    ReasoningPolicy initial = policy().admitted(100);
    ReasoningPolicy recovery = initial.withoutRecovery().admitted(1_000_000);
    assertEquals(initial.deadlineNanos(), recovery.deadlineNanos());
    assertEquals(Optional.empty(), recovery.recoveryEffort());
    Sampling sampling =
        Sampling.NONE
            .withMaxTokens(8192)
            .withReasoningEffort(Sampling.Effort.MEDIUM)
            .withReasoning(initial);
    assertEquals(
        initial,
        sampling
            .withTopP(0.95)
            .withTemperature(1)
            .withTopK(20)
            .overriddenBy(Sampling.NONE)
            .reasoning()
            .orElseThrow());
    assertThrows(IllegalArgumentException.class, () -> sampling.withMaxTokens(4096));
    assertThrows(
        IllegalArgumentException.class, () -> sampling.withReasoningEffort(Sampling.Effort.LOW));
    assertEquals(
        Sampling.Effort.LOW,
        sampling.recovering(initial, Sampling.Effort.LOW).reasoningEffort().orElseThrow());
  }
}
