package io.aeyer.plowshare.server.approvals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PersonalApprovalReviewSessionsTest {
  private final ApprovalPolicyRepository policies = mock(ApprovalPolicyRepository.class);
  private final AvailableApprovalBots bots = mock(AvailableApprovalBots.class);
  private final OperationAuthority.Admission operation = mock(OperationAuthority.Admission.class);
  private final AgentDefinition security = definition("security_reviewer");
  private final AtomicReference<String> output =
      new AtomicReference<>("{\"outcome\":\"CLEAR\",\"reason\":\"Inspects requested state.\"}");
  private final AtomicInteger consultations = new AtomicInteger();
  private final AtomicBoolean revoked = new AtomicBoolean();
  private final AtomicBoolean revokeDuringReview = new AtomicBoolean();
  private final AtomicInteger audits = new AtomicInteger();
  private final OperationApprovalReview.Request request =
      new OperationApprovalReview.Request(
          "project", "remote:session", List.of("git", "status"), "/project", null, "call");

  private OperationApprovalReview session() {
    when(operation.executor()).thenReturn("sam");
    when(operation.home()).thenReturn(Home.of("project"));
    when(policies.read("sam"))
        .thenReturn(
            new ApprovalPolicy(
                1,
                ApprovalPolicy.Mode.SMART,
                null,
                List.of(rule(ApprovalPolicy.Effect.MAY_APPROVE))));
    var sessions =
        new PersonalApprovalReviewSessions(
            policies,
            bots,
            (admission, budget, usage, validity) ->
                (definition, system, evidence, work) -> {
                  consultations.incrementAndGet();
                  if (revokeDuringReview.get()) revoked.set(true);
                  assertTrue(evidence.contains("Inspect repository status"));
                  return definition.name().equals("security_reviewer")
                      ? output.get()
                      : "{\"approved\":true,\"reason\":\"Matches personal instructions.\"}";
                },
            () -> security,
            Duration.ofSeconds(2));
    return sessions
        .operations(
            new ApprovalReviewSessions.Context(
                "sam",
                "conversation",
                "Inspect repository status",
                operation,
                Budget.of(5),
                UsageAttribution.LEGACY,
                () -> false,
                () -> {
                  if (revoked.get()) throw new WorkspaceRefusedException("Authority withdrawn");
                },
                (intent, decision) -> audits.incrementAndGet()))
        .orElseThrow();
  }

  @Test
  void approvalIsExactSingleUseAndRevisionFenced() {
    var session = session();
    assertEquals(
        ApprovalReviews.Action.ALLOW,
        session.review(request, new ApprovalReviews.Consent(false, false, false)).action());
    assertEquals(1, audits.get());
    var other =
        new OperationApprovalReview.Request(
            "project", "remote:another-session", request.argv(), request.cwd(), null, "call");
    assertThrows(WorkspaceRefusedException.class, () -> session.requireCurrent(other));
    var anotherCall =
        new OperationApprovalReview.Request(
            request.project(),
            request.provider(),
            request.argv(),
            request.cwd(),
            request.stdin(),
            "another-call");
    assertThrows(WorkspaceRefusedException.class, () -> session.requireCurrent(anotherCall));
    session.requireCurrent(request);
    assertThrows(WorkspaceRefusedException.class, () -> session.requireCurrent(request));
    session.review(request, new ApprovalReviews.Consent(false, false, false));
    when(policies.read("sam"))
        .thenReturn(new ApprovalPolicy(2, ApprovalPolicy.Mode.SMART, null, List.of()));
    assertThrows(WorkspaceRefusedException.class, () -> session.requireCurrent(request));
  }

  @Test
  void rejectAndAskNeverCreateExecutionGrants() {
    var session = session();
    for (String verdict : List.of("REJECT", "ASK_HUMAN")) {
      output.set(
          "{\"outcome\":\""
              + verdict
              + "\",\"reason\":\"Changing Git identity exceeds this task.\"}");
      var decision = session.review(request, new ApprovalReviews.Consent(true, false, false));
      assertNotEquals(ApprovalReviews.Action.ALLOW, decision.action());
      assertThrows(WorkspaceRefusedException.class, () -> session.requireCurrent(request));
    }
    verifyNoInteractions(bots);
  }

  @Test
  void personalRejectDoesNotDispatchReviewAndCannotBeHumanOverridden() {
    var session = session();
    when(policies.read("sam"))
        .thenReturn(
            new ApprovalPolicy(
                2,
                ApprovalPolicy.Mode.AUTO,
                "my-bot",
                List.of(rule(ApprovalPolicy.Effect.REJECT))));
    assertEquals(
        ApprovalReviews.Action.REJECT,
        session.review(request, new ApprovalReviews.Consent(true, false, true)).action());
    assertEquals(0, consultations.get());
    assertThrows(WorkspaceRefusedException.class, () -> session.humanOverride(request));
    verifyNoInteractions(bots);
  }

  @Test
  void humanOverrideSurvivesRevisionChangeButNotNewProhibitions() {
    var session = session();
    session.humanOverride(request);
    when(policies.read("sam"))
        .thenReturn(new ApprovalPolicy(2, ApprovalPolicy.Mode.MANUAL, null, List.of()));
    session.requireCurrent(request);
    session.humanOverride(request);
    when(policies.read("sam"))
        .thenReturn(
            new ApprovalPolicy(
                3, ApprovalPolicy.Mode.MANUAL, null, List.of(rule(ApprovalPolicy.Effect.REJECT))));
    assertThrows(WorkspaceRefusedException.class, () -> session.requireCurrent(request));
  }

  @Test
  void autoPinsBotDefinitionAndRechecksItsAvailability() {
    var session = session();
    when(policies.read("sam"))
        .thenReturn(
            new ApprovalPolicy(
                2,
                ApprovalPolicy.Mode.AUTO,
                "my-bot",
                List.of(rule(ApprovalPolicy.Effect.MAY_APPROVE))));
    AgentDefinition personalBot = mock(AgentDefinition.class);
    when(personalBot.name()).thenReturn("my-bot");
    when(personalBot.bot()).thenReturn(true);
    when(personalBot.prompt()).thenReturn("Review only");
    when(bots.selected("sam", "my-bot")).thenReturn(personalBot);
    assertEquals(
        ApprovalReviews.Action.ALLOW,
        session.review(request, new ApprovalReviews.Consent(false, false, false)).action());
    assertEquals(2, consultations.get());
    when(bots.selected("sam", "my-bot")).thenThrow(new CallerFault("Bot disabled"));
    assertThrows(WorkspaceRefusedException.class, () -> session.requireCurrent(request));
  }

  @Test
  void operationHomeCannotBeSelectedThroughReviewArguments() {
    var session = session();
    var elsewhere =
        new OperationApprovalReview.Request(
            "another-project", request.provider(), request.argv(), request.cwd(), null, "call");
    assertThrows(
        WorkspaceRefusedException.class,
        () -> session.review(elsewhere, new ApprovalReviews.Consent(true, false, false)));
    assertEquals(0, consultations.get());
  }

  @Test
  void revokedAuthorityCannotBecomeAnOverridableHumanQuestion() {
    var session = session();
    revokeDuringReview.set(true);
    output.set("{\"outcome\":\"ASK_HUMAN\",\"reason\":\"Confirm the change.\"}");
    assertThrows(
        WorkspaceRefusedException.class,
        () -> session.review(request, new ApprovalReviews.Consent(true, false, false)));
    assertEquals(0, audits.get());
    assertThrows(WorkspaceRefusedException.class, () -> session.humanOverride(request));
  }

  private ApprovalPolicy.Rule rule(ApprovalPolicy.Effect effect) {
    return new ApprovalPolicy.Rule(
        "status",
        effect,
        ApprovalPolicy.Kind.COMMAND,
        request.project(),
        request.provider(),
        request.cwd(),
        null,
        request.argv(),
        List.of(),
        null);
  }

  private static AgentDefinition definition(String name) {
    return new AgentDefinition(
        name, "review", "fast", List.of(), List.of(), List.of(), 1, 1, "Review only");
  }
}
