package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.allowances.AllowanceSnapshot;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only allowance advice for one model loop. Notices are emitted at opening, when a ceiling
 * changes, and once at each remaining-allowance threshold. They never grant calls or alter tools.
 *
 * <p>The successful claim supplies the shared ceiling and spending ordinal, including delegates and
 * earlier turns. Board wakes also report their separate local window from an in-memory snapshot.
 * Counts include the response about to be generated. If spending crosses several thresholds between
 * responses, only the most urgent warning is emitted. A changed ceiling starts a new set of
 * thresholds. The runtime records every notice in the transcript.
 */
final class AllowanceNotices {
  private final Checkpoints steps = new Checkpoints();
  private final Checkpoints calls = new Checkpoints();
  private final Checkpoints wakeCalls = new Checkpoints();
  private boolean opened;

  Optional<String> beforeCall(
      Integer stepCeiling,
      int taken,
      AllowanceSnapshot allowance,
      AllowanceSnapshot wake,
      Set<String> tools) {
    int stepsLeft = stepCeiling == null ? 0 : Math.max(0, stepCeiling - taken);
    int callsLeft =
        allowance.ceiling() == null ? 0 : Math.max(0, allowance.ceiling() - allowance.spent() + 1);
    boolean changedSteps = steps.update(stepCeiling, stepsLeft);
    boolean changedCalls = calls.update(allowance.ceiling(), callsLeft);
    int wakeLeft =
        wake == null || wake.ceiling() == null ? 0 : Math.max(0, wake.ceiling() - wake.spent() + 1);
    boolean changedWake = wakeCalls.update(wake == null ? null : wake.ceiling(), wakeLeft);
    if (opened && !changedSteps && !changedCalls && !changedWake) return Optional.empty();
    opened = true;
    int urgency = Math.max(steps.level, Math.max(calls.level, wakeCalls.level));
    String heading =
        urgency == 4
            ? "Final allowance warning"
            : urgency > 0 ? "Allowance warning" : "Runtime allowance";
    String counts =
        describe("steps (max-turns)", stepCeiling, stepsLeft)
            + "; "
            + describe("model calls in the shared allowance", allowance.ceiling(), callsLeft);
    if (wake != null)
      counts += "; " + describe("model calls in this wake", wake.ceiling(), wakeLeft);
    String checkpoint =
        urgency == 0
            ? ""
            : " At most "
                + Checkpoints.PERCENTAGES[urgency - 1]
                + "% remains for at least one limit, or this is its last step/call.";
    String advice =
        urgency == 4
            ? "Finish now with verified results and explicit remaining gaps, or request more"
                + " allowance from your caller before it runs out."
            : "Plan to finish within both limits; prioritize the evidence needed for an answer.";
    String request =
        tools.contains(RequestBudgetTool.NAME)
            ? " Use request_budget with additional_calls, additional_steps and a reason to ask the owning person."
                + " The harness pauses this same execution for approval, including after exhaustion."
            : tools.contains("orchestration_ask")
                ? " Use orchestration_ask to ask your caller when more allowance is essential."
                    + " The orchestration harness also asks about extending limits when a cap is hit."
                : tools.contains("send_message")
                    ? " Use send_message to request more allowance from your caller if needed."
                    : " No allowance-request tool is offered; finish with what you can verify"
                        + " and state any work that needs more allowance.";
    return Optional.of(
        "["
            + heading
            + ": "
            + counts
            + "."
            + checkpoint
            + " Counts include this step and this model call; other work sharing the allowance"
            + " can spend calls too. "
            + advice
            + request
            + " A request does not grant an increase; only an authorized caller or configured"
            + " policy can change the limits.]");
  }

  private static String describe(String unit, Integer ceiling, int remaining) {
    return ceiling == null
        ? unit + ": unlimited"
        : unit + ": " + remaining + " of " + ceiling + " remaining";
  }

  private static final class Checkpoints {
    private static final int[] PERCENTAGES = {50, 20, 10, 5};
    private Integer ceiling;
    private int level;

    boolean update(Integer total, int remaining) {
      boolean changed = !Objects.equals(ceiling, total);
      if (changed) {
        ceiling = total;
        level = 0;
      }
      int reached = 0;
      if (total != null) {
        for (int index = 0; index < PERCENTAGES.length; index++) {
          if ((long) remaining * 100 <= (long) total * PERCENTAGES[index]) reached = index + 1;
        }
        // Even a one-call task must know this response is its last chance to finish.
        if (remaining <= 1) reached = PERCENTAGES.length;
      }
      boolean crossed = reached > level;
      level = Math.max(level, reached);
      return changed || crossed;
    }
  }
}
