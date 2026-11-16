package io.aeyer.plowshare.server.approvals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PolicyApprovalReviewsTest {
  private final SecurityReviewer security = mock(SecurityReviewer.class);
  private final PersonalBotReviewer bots = mock(PersonalBotReviewer.class);
  private final ApprovalReviews reviews = new PolicyApprovalReviews(security, bots);
  private final ApprovalIntent git =
      new ApprovalIntent(
          "alice",
          "chat",
          "call",
          "project",
          "machine/session-generation",
          ApprovalPolicy.Kind.COMMAND,
          List.of("git", "config", "--local", "user.email", "fixture@example.invalid"),
          List.of(),
          "/fixture",
          null,
          "Inspect the project",
          "Prepare a commit");
  private final ApprovalReviews.Consent none = new ApprovalReviews.Consent(false, false, false);

  @BeforeEach
  void clear() {
    when(security.review(any(), anyList(), any()))
        .thenReturn(
            new SecurityReviewer.Verdict(SecurityReviewer.Outcome.CLEAR, "Matches the task"));
  }

  @Test
  void securityRejectionReturnsToModelWithoutConsultingBotEvenWithStandingConsent() {
    when(security.review(any(), anyList(), any()))
        .thenReturn(
            new SecurityReviewer.Verdict(
                SecurityReviewer.Outcome.REJECT,
                "Changing Git identity is outside the inspection task"));
    var decision =
        reviews.review(
            policy(ApprovalPolicy.Mode.AUTO, List.of()),
            git,
            new ApprovalReviews.Consent(true, false, true),
            () -> false);
    assertEquals(ApprovalReviews.Action.REJECT, decision.action());
    assertEquals("Changing Git identity is outside the inspection task", decision.reason());
    verifyNoInteractions(bots);
  }

  @Test
  void securityAskCannotBeOverriddenByBot() {
    when(security.review(any(), anyList(), any()))
        .thenReturn(
            new SecurityReviewer.Verdict(
                SecurityReviewer.Outcome.ASK_HUMAN, "Read outside project"));
    assertEquals(
        ApprovalReviews.Action.ASK_HUMAN,
        reviews
            .review(
                policy(ApprovalPolicy.Mode.AUTO, List.of()),
                git,
                new ApprovalReviews.Consent(false, false, true),
                () -> false)
            .action());
    verifyNoInteractions(bots);
  }

  @Test
  void manualAddsNoAutomaticConsentEvenWhenHistoryMatches() {
    assertEquals(
        ApprovalReviews.Action.ASK_HUMAN,
        reviews
            .review(
                policy(ApprovalPolicy.Mode.MANUAL, List.of()),
                git,
                new ApprovalReviews.Consent(false, false, true),
                () -> false)
            .action());
    assertEquals(
        ApprovalReviews.Action.ALLOW,
        reviews
            .review(
                ApprovalPolicy.manual(),
                git,
                new ApprovalReviews.Consent(true, false, false),
                () -> false)
            .action());
    verifyNoInteractions(bots);
  }

  @Test
  void smartNeedsEligibilityAndClearReview() {
    var smart = policy(ApprovalPolicy.Mode.SMART, List.of());
    assertEquals(
        ApprovalReviews.Action.ASK_HUMAN, reviews.review(smart, git, none, () -> false).action());
    assertEquals(
        ApprovalReviews.Action.ALLOW,
        reviews
            .review(smart, git, new ApprovalReviews.Consent(false, false, true), () -> false)
            .action());
    assertEquals(
        ApprovalReviews.Action.ALLOW,
        reviews
            .review(
                policy(
                    ApprovalPolicy.Mode.SMART,
                    List.of(rule("allow", ApprovalPolicy.Effect.MAY_APPROVE))),
                git,
                none,
                () -> false)
            .action());
  }

  @Test
  void autoRequiresBotAfterClearAndRetainsBothReviewResults() {
    when(bots.review(anyString(), any(), anyList(), any(), any()))
        .thenReturn(new PersonalBotReviewer.Verdict(false, "The user should decide"));
    var auto =
        policy(ApprovalPolicy.Mode.AUTO, List.of(rule("allow", ApprovalPolicy.Effect.MAY_APPROVE)));
    var decision = reviews.review(auto, git, none, () -> false);
    assertEquals(ApprovalReviews.Action.ASK_HUMAN, decision.action());
    assertEquals(SecurityReviewer.Outcome.CLEAR, decision.security().outcome());
    assertEquals(false, decision.bot().approved());
    when(bots.review(anyString(), any(), anyList(), any(), any()))
        .thenReturn(new PersonalBotReviewer.Verdict(true, "Approved for the task"));
    assertEquals(
        ApprovalReviews.Action.ALLOW, reviews.review(auto, git, none, () -> false).action());
  }

  @Test
  void personalRejectWinsOverAskAndAllowWithoutSpendingModelCalls() {
    var decision =
        reviews.review(
            policy(
                ApprovalPolicy.Mode.AUTO,
                List.of(
                    rule("allow", ApprovalPolicy.Effect.MAY_APPROVE),
                    rule("ask", ApprovalPolicy.Effect.ASK_HUMAN),
                    rule("reject", ApprovalPolicy.Effect.REJECT))),
            git,
            none,
            () -> false);
    assertEquals(ApprovalReviews.Action.REJECT, decision.action());
    assertEquals(List.of("allow", "ask", "reject"), decision.matchedRules());
    verifyNoInteractions(security, bots);
  }

  @Test
  void humanOnlyAndPersonalAskCannotBeAutomaticallyCleared() {
    var smart =
        policy(
            ApprovalPolicy.Mode.SMART, List.of(rule("allow", ApprovalPolicy.Effect.MAY_APPROVE)));
    assertEquals(
        ApprovalReviews.Action.ASK_HUMAN,
        reviews
            .review(smart, git, new ApprovalReviews.Consent(true, true, true), () -> false)
            .action());
    assertEquals(
        ApprovalReviews.Action.ASK_HUMAN,
        reviews
            .review(
                policy(
                    ApprovalPolicy.Mode.SMART,
                    List.of(
                        rule("ask", ApprovalPolicy.Effect.ASK_HUMAN),
                        rule("allow", ApprovalPolicy.Effect.MAY_APPROVE))),
                git,
                none,
                () -> false)
            .action());
  }

  @Test
  void reviewerFailuresAskHumanInsteadOfRejectingOrAllowing() {
    when(security.review(any(), anyList(), any()))
        .thenThrow(new IllegalStateException("private failure"));
    var decision =
        reviews.review(
            policy(ApprovalPolicy.Mode.SMART, List.of()),
            git,
            new ApprovalReviews.Consent(true, false, true),
            () -> false);
    assertEquals(ApprovalReviews.Action.ASK_HUMAN, decision.action());
    assertEquals(
        "SECURITY review is unavailable; a human decision is required.", decision.reason());
    verifyNoInteractions(bots);
  }

  @Test
  void cancelledWorkCannotUseLateReviewOrBotResults() {
    AtomicBoolean cancelled = new AtomicBoolean();
    when(security.review(any(), anyList(), any()))
        .thenAnswer(
            call -> {
              cancelled.set(true);
              return new SecurityReviewer.Verdict(SecurityReviewer.Outcome.CLEAR, "Late clear");
            });
    assertEquals(
        ApprovalReviews.Action.REJECT,
        reviews
            .review(
                policy(ApprovalPolicy.Mode.AUTO, List.of()),
                git,
                new ApprovalReviews.Consent(true, false, true),
                cancelled::get)
            .action());
    verify(bots, never()).review(anyString(), any(), anyList(), any(), any());
  }

  @Test
  void exactRulesCannotMatchAnotherCommandMachineOrOperation() {
    var rule = rule("allow", ApprovalPolicy.Effect.MAY_APPROVE);
    var status =
        new ApprovalIntent(
            git.owner(),
            git.conversation(),
            git.call(),
            git.project(),
            git.provider(),
            ApprovalPolicy.Kind.COMMAND,
            List.of("git", "status"),
            List.of(),
            git.cwd(),
            null,
            git.userIntent(),
            null);
    assertEquals(false, rule.matches(status));
    var elsewhere =
        new ApprovalPolicy.Rule(
            "other",
            ApprovalPolicy.Effect.MAY_APPROVE,
            ApprovalPolicy.Kind.COMMAND,
            git.project(),
            "different-machine",
            git.cwd(),
            null,
            git.argv(),
            List.of(),
            null);
    assertEquals(false, elsewhere.matches(git));
    var read =
        new ApprovalPolicy.Rule(
            "read",
            ApprovalPolicy.Effect.ASK_HUMAN,
            ApprovalPolicy.Kind.FILE_READ,
            null,
            null,
            null,
            null,
            List.of(),
            List.of(),
            null);
    assertEquals(false, read.matches(git));
  }

  @Test
  void invalidPoliciesCannotHideBroadPermissionsOrDuplicateRules() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ApprovalPolicy(0, ApprovalPolicy.Mode.AUTO, null, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ApprovalPolicy.Rule(
                "broad",
                ApprovalPolicy.Effect.MAY_APPROVE,
                ApprovalPolicy.Kind.COMMAND,
                null,
                null,
                null,
                null,
                List.of("git"),
                List.of(),
                null));
    var rule = rule("duplicate", ApprovalPolicy.Effect.ASK_HUMAN);
    assertThrows(
        IllegalArgumentException.class,
        () -> policy(ApprovalPolicy.Mode.MANUAL, List.of(rule, rule)));
  }

  private ApprovalPolicy.Rule rule(String id, ApprovalPolicy.Effect effect) {
    return new ApprovalPolicy.Rule(
        id,
        effect,
        git.kind(),
        git.project(),
        git.provider(),
        git.cwd(),
        git.inputDigest(),
        git.argv(),
        List.of(),
        null);
  }

  @Test
  void aClearCannotReplaceMissingAuthenticatedIntentOrOpaqueInput() {
    when(security.review(any(), any(), any()))
        .thenReturn(new SecurityReviewer.Verdict(SecurityReviewer.Outcome.CLEAR, "Looks safe"));
    for (ApprovalIntent incomplete :
        List.of(
            new ApprovalIntent(
                git.owner(),
                git.conversation(),
                git.call(),
                git.project(),
                git.provider(),
                git.kind(),
                git.argv(),
                git.paths(),
                git.cwd(),
                null,
                null,
                git.justification()),
            new ApprovalIntent(
                git.owner(),
                git.conversation(),
                git.call(),
                git.project(),
                git.provider(),
                git.kind(),
                git.argv(),
                git.paths(),
                git.cwd(),
                "a".repeat(64),
                git.userIntent(),
                git.justification()))) {
      assertEquals(
          ApprovalReviews.Action.ASK_HUMAN,
          reviews
              .review(
                  policy(ApprovalPolicy.Mode.AUTO, List.of()),
                  incomplete,
                  new ApprovalReviews.Consent(true, false, true),
                  () -> false)
              .action());
    }
    verifyNoInteractions(bots);
  }

  private static ApprovalPolicy policy(ApprovalPolicy.Mode mode, List<ApprovalPolicy.Rule> rules) {
    return new ApprovalPolicy(
        7, mode, mode == ApprovalPolicy.Mode.AUTO ? "personal-bot" : null, rules);
  }
}
