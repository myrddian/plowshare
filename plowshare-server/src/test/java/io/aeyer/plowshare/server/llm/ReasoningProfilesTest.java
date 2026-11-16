package io.aeyer.plowshare.server.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReasoningProfilesTest {
  @TempDir Path directory;

  private Sampling load(String reasoning, String effort, int maxTokens) throws Exception {
    String row =
        "{max_tokens: "
            + maxTokens
            + ", reasoning_effort: "
            + effort
            + ", reasoning: "
            + reasoning
            + "}";
    Files.writeString(
        directory.resolve("loop-control.yaml"),
        "modes:\n  thinking:\n    precise: "
            + row
            + "\n    balanced: "
            + row
            + "\n    exploratory: "
            + row
            + "\n");
    Files.writeString(
        directory.resolve("models.yaml"),
        "models: {fixture-model: {profile: loop-control, mode: thinking}}\n");
    return SamplingProfiles.read(directory)
        .resolve("fixture-model", Sampling.Intent.BALANCED)
        .sampling();
  }

  @Test
  void profileLoadsTypedLimitsAndAnExplicitLowerEffortRecovery() throws Exception {
    Sampling sampling =
        load(
            "{thinking_token_budget: 4096, reasoning_seconds: 120, completion_seconds: 240, recovery_effort: low}",
            "medium",
            8192);
    var policy = sampling.reasoning().orElseThrow();
    assertEquals(4096, policy.thinkingTokenBudget());
    assertEquals(Duration.ofSeconds(120), policy.reasoningTimeout());
    assertEquals(Duration.ofSeconds(240), policy.completionTimeout());
    assertEquals(Sampling.Effort.LOW, policy.recoveryEffort().orElseThrow());
    assertEquals(0, policy.deadlineNanos());
  }

  @Test
  void profileRefusesUnknownFieldsInvalidBudgetsAndNonDescendingRecovery() {
    String valid = "thinking_token_budget: 4096, reasoning_seconds: 120, completion_seconds: 240";
    for (String invalid :
        new String[] {
          "{" + valid + ", thinking_token_buget: 8}",
          "{thinking_token_budget: 0, reasoning_seconds: 120, completion_seconds: 240}",
          "{thinking_token_budget: 4096, reasoning_seconds: 240, completion_seconds: 120}",
          "{" + valid + ", recovery_effort: high}",
          "{" + valid + ", recovery_effort: medium}",
          "{" + valid + ", repetition_window_characters: 100000}"
        }) assertThrows(RuntimeException.class, () -> load(invalid, "medium", 8192));
    assertThrows(RuntimeException.class, () -> load("{" + valid + "}", "medium", 4096));
  }
}
