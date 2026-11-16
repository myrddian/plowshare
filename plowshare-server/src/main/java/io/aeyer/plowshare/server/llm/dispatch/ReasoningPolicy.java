package io.aeyer.plowshare.server.llm.dispatch;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Opt-in reasoning limits for a model profile. The token limit leaves room within max_tokens for an
 * answer. The host deadline is never sent to the provider; a recovery inherits it rather than
 * receiving a fresh completion budget. Zero denotes a configured policy not yet admitted.
 */
public record ReasoningPolicy(
    int thinkingTokenBudget,
    Duration reasoningTimeout,
    Duration completionTimeout,
    int repetitionWindowCharacters,
    int repetitionRepeats,
    Optional<Sampling.Effort> recoveryEffort,
    long deadlineNanos) {
  public ReasoningPolicy {
    Objects.requireNonNull(reasoningTimeout);
    Objects.requireNonNull(completionTimeout);
    Objects.requireNonNull(recoveryEffort);
    if (thinkingTokenBudget < 1
        || reasoningTimeout.isNegative()
        || reasoningTimeout.isZero()
        || completionTimeout.compareTo(reasoningTimeout) <= 0
        || completionTimeout.compareTo(Duration.ofDays(1)) > 0
        || repetitionWindowCharacters < 32
        || repetitionWindowCharacters > 512
        || repetitionRepeats < 3
        || repetitionRepeats > 8)
      throw new IllegalArgumentException("Invalid reasoning limits or completion budget");
    recoveryEffort.ifPresent(
        effort -> {
          if (effort != Sampling.Effort.LOW && effort != Sampling.Effort.MEDIUM)
            throw new IllegalArgumentException("Reasoning recovery effort must be low or medium");
        });
  }

  /** Start once, before queue admission. Copying an active policy preserves its deadline. */
  public ReasoningPolicy admitted(long now) {
    return deadlineNanos != 0
        ? this
        : new ReasoningPolicy(
            thinkingTokenBudget,
            reasoningTimeout,
            completionTimeout,
            repetitionWindowCharacters,
            repetitionRepeats,
            recoveryEffort,
            now + completionTimeout.toNanos());
  }

  public Duration remaining(long now) {
    return deadlineNanos == 0
        ? completionTimeout
        : Duration.ofNanos(Math.max(0, deadlineNanos - now));
  }

  /** A recovery cannot recursively authorize another recovery. */
  public ReasoningPolicy withoutRecovery() {
    return new ReasoningPolicy(
        thinkingTokenBudget,
        reasoningTimeout,
        completionTimeout,
        repetitionWindowCharacters,
        repetitionRepeats,
        Optional.empty(),
        deadlineNanos);
  }
}
