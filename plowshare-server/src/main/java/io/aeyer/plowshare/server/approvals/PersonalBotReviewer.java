package io.aeyer.plowshare.server.approvals;

import java.util.List;

/**
 * A constrained personal-bot consult, not an autonomous bot run. No tools, policy edits, SECURITY
 * override, identity fallback or recursive consent. Refusal and uncertainty ask the human.
 */
@FunctionalInterface
public interface PersonalBotReviewer {
  record Verdict(boolean approved, String reason) {
    public Verdict {
      ApprovalPolicy.text(reason, "Bot reason", 4000);
    }
  }

  Verdict review(
      String bot,
      ApprovalIntent intent,
      List<ApprovalPolicy.Rule> rules,
      SecurityReviewer.Verdict security,
      ReviewWork work);
}
