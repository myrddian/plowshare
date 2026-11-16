package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.server.hooks.Deadline;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Pure policy coordination. Persistence and current-authority confirmation belong to the calling
 * lifecycle owner. No decision is an execution claim. Review failures are human attention, never
 * synthetic security rejections or allows.
 */
public final class PolicyApprovalReviews implements ApprovalReviews {
  private final SecurityReviewer security;
  private final PersonalBotReviewer bots;
  private final Duration budget;

  public PolicyApprovalReviews(SecurityReviewer security, PersonalBotReviewer bots) {
    this(security, bots, Duration.ofSeconds(60));
  }

  public PolicyApprovalReviews(
      SecurityReviewer security, PersonalBotReviewer bots, Duration budget) {
    this.security = Objects.requireNonNull(security);
    this.bots = Objects.requireNonNull(bots);
    this.budget = Objects.requireNonNull(budget);
    if (budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofMinutes(5)) > 0)
      throw new IllegalArgumentException("Review budget must be positive and at most five minutes");
  }

  @Override
  public Decision review(
      ApprovalPolicy policy, ApprovalIntent intent, Consent consent, BooleanSupplier cancelled) {
    Objects.requireNonNull(consent);
    Objects.requireNonNull(cancelled);
    ReviewWork work = new ReviewWork(Deadline.after(budget), cancelled);
    List<ApprovalPolicy.Rule> matched =
        policy.rules().stream().filter(r -> r.matches(intent)).toList();
    // Cross-tool attempts matter: a command may edit a file, and a file edit may change Git
    // identity. Reviewers see all rules in this machine/project scope; only exact matches below
    // can establish deterministic consent eligibility.
    List<ApprovalPolicy.Rule> reviewRules =
        policy.rules().stream()
            .filter(
                rule ->
                    (rule.project() == null || rule.project().equals(intent.project()))
                        && (rule.provider() == null || rule.provider().equals(intent.provider())))
            .toList();
    List<String> ids = matched.stream().map(ApprovalPolicy.Rule::id).toList();
    if (cancelled.getAsBoolean())
      return decision(
          Action.REJECT, "The requesting operation was cancelled.", policy, ids, null, null);
    if (matched.stream().anyMatch(r -> r.effect() == ApprovalPolicy.Effect.REJECT))
      return decision(
          Action.REJECT,
          "Personal approval rules prohibit this operation.",
          policy,
          ids,
          null,
          null);
    if (work.expired())
      return decision(
          Action.ASK_HUMAN,
          "Review deadline expired; a human decision is required.",
          policy,
          ids,
          null,
          null);
    SecurityReviewer.Verdict reviewed;
    try {
      reviewed = Objects.requireNonNull(security.review(intent, reviewRules, work));
    } catch (RuntimeException unavailable) {
      if (cancelled.getAsBoolean())
        return decision(
            Action.REJECT, "The requesting operation was cancelled.", policy, ids, null, null);
      return decision(
          Action.ASK_HUMAN,
          "SECURITY review is unavailable; a human decision is required.",
          policy,
          ids,
          null,
          null);
    }
    if (cancelled.getAsBoolean())
      return decision(
          Action.REJECT, "The requesting operation was cancelled.", policy, ids, reviewed, null);
    if (work.expired())
      return decision(
          Action.ASK_HUMAN,
          "SECURITY review exceeded its deadline; a human decision is required.",
          policy,
          ids,
          reviewed,
          null);
    if (reviewed.outcome() != SecurityReviewer.Outcome.CLEAR)
      return decision(
          reviewed.outcome() == SecurityReviewer.Outcome.REJECT ? Action.REJECT : Action.ASK_HUMAN,
          reviewed.reason(),
          policy,
          ids,
          reviewed,
          null);
    // A model Clear cannot manufacture missing authenticated intent or authorize input whose
    // contents were withheld from review. Keep this uncertainty deterministic and human-only.
    if (intent.userIntent() == null
        || (intent.inputDigest() != null && intent.inputPreview() == null))
      return decision(
          Action.ASK_HUMAN,
          "Authenticated task intent or complete input evidence is missing; a human must review the exact operation.",
          policy,
          ids,
          reviewed,
          null);
    if (consent.humanOnly()
        || matched.stream().anyMatch(r -> r.effect() == ApprovalPolicy.Effect.ASK_HUMAN))
      return decision(
          Action.ASK_HUMAN,
          "This operation requires direct human consent.",
          policy,
          ids,
          reviewed,
          null);
    if (policy.mode() == ApprovalPolicy.Mode.MANUAL)
      return decision(
          consent.existingConsent() ? Action.ALLOW : Action.ASK_HUMAN,
          consent.existingConsent()
              ? "Existing consent covers the reviewed operation."
              : "Manual mode requires human approval.",
          policy,
          ids,
          reviewed,
          null);
    boolean eligible =
        consent.existingConsent()
            || consent.humanHistoryEligible()
            || matched.stream().anyMatch(r -> r.effect() == ApprovalPolicy.Effect.MAY_APPROVE);
    if (!eligible)
      return decision(
          Action.ASK_HUMAN,
          "No personal rule or matching human approval establishes automatic eligibility.",
          policy,
          ids,
          reviewed,
          null);
    if (policy.mode() == ApprovalPolicy.Mode.SMART)
      return decision(
          Action.ALLOW,
          "Smart checks and SECURITY cleared the operation.",
          policy,
          ids,
          reviewed,
          null);
    PersonalBotReviewer.Verdict bot;
    try {
      bot = Objects.requireNonNull(bots.review(policy.bot(), intent, reviewRules, reviewed, work));
    } catch (RuntimeException unavailable) {
      if (cancelled.getAsBoolean())
        return decision(
            Action.REJECT, "The requesting operation was cancelled.", policy, ids, reviewed, null);
      return decision(
          Action.ASK_HUMAN,
          "The selected personal bot could not approve; a human decision is required.",
          policy,
          ids,
          reviewed,
          null);
    }
    if (cancelled.getAsBoolean())
      return decision(
          Action.REJECT, "The requesting operation was cancelled.", policy, ids, reviewed, bot);
    if (work.expired())
      return decision(
          Action.ASK_HUMAN,
          "Personal bot review exceeded its deadline; a human decision is required.",
          policy,
          ids,
          reviewed,
          bot);
    return decision(
        bot.approved() ? Action.ALLOW : Action.ASK_HUMAN, bot.reason(), policy, ids, reviewed, bot);
  }

  private static Decision decision(
      Action action,
      String reason,
      ApprovalPolicy policy,
      List<String> ids,
      SecurityReviewer.Verdict security,
      PersonalBotReviewer.Verdict bot) {
    return new Decision(action, reason, policy.revision(), ids, security, bot);
  }
}
