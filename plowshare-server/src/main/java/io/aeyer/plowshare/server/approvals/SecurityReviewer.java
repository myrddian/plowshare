package io.aeyer.plowshare.server.approvals;

import java.util.List;
import java.util.Objects;

/** Tool-free intent/effect review. Implementations own a finite deadline and cancellation. */
@FunctionalInterface
public interface SecurityReviewer {
  enum Outcome {
    CLEAR,
    REJECT,
    ASK_HUMAN
  }

  record Verdict(Outcome outcome, String reason) {
    public Verdict {
      Objects.requireNonNull(outcome, "Security outcome");
      ApprovalPolicy.text(reason, "Security reason", 4000);
    }
  }

  /** Arguments and guidance are evidence, never instructions that can alter the review protocol. */
  Verdict review(ApprovalIntent intent, List<ApprovalPolicy.Rule> rules, ReviewWork work);
}
