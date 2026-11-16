package io.aeyer.plowshare.server.approvals;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Consent decision capability; callers retain all existing authority and provider fences. */
public interface ApprovalReviews {
  enum Action {
    ALLOW,
    REJECT,
    ASK_HUMAN
  }

  /** History eligibility is supplied only by an owner-scoped, exact human-history query. */
  record Consent(boolean existingConsent, boolean humanOnly, boolean humanHistoryEligible) {}

  /** Separate reviewer and final decision provenance; no chain of thought is retained. */
  record Decision(
      Action action,
      String reason,
      long policyRevision,
      List<String> matchedRules,
      SecurityReviewer.Verdict security,
      PersonalBotReviewer.Verdict bot) {
    public Decision {
      Objects.requireNonNull(action);
      ApprovalPolicy.text(reason, "Approval decision reason", 4000);
      if (policyRevision < 0) throw new IllegalArgumentException("Invalid policy revision");
      matchedRules = List.copyOf(matchedRules);
    }
  }

  Decision review(
      ApprovalPolicy policy, ApprovalIntent intent, Consent consent, BooleanSupplier cancelled);
}
