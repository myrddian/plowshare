package io.aeyer.plowshare.server.approvals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.agents.EventRuns;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.agents.Turn;
import io.aeyer.plowshare.server.events.AgentRunner;
import io.aeyer.plowshare.server.events.AgentRunner.ScheduledTask;
import io.aeyer.plowshare.server.events.EventPayload;
import io.aeyer.plowshare.server.events.FiringRecord;
import io.aeyer.plowshare.server.events.TriggerRecord;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.FileProvider.PersonalWorkspace;
import io.aeyer.plowshare.server.files.WorkspaceRefusedException;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
  private final AtomicReference<String> reviewerEvidence = new AtomicReference<>();
  private final AtomicReference<String> reviewerPrompt = new AtomicReference<>();
  private final AtomicReference<String> securityEvidence = new AtomicReference<>();
  private final OperationApprovalReview.Request request =
      new OperationApprovalReview.Request(
          "project", "remote:session", List.of("git", "status"), "/project", null, "call");

  private OperationApprovalReview session() {
    return session("Inspect repository status");
  }

  private OperationApprovalReview session(String authenticatedTask) {
    return session(authenticatedTask, DeploymentSecurityPolicy.NONE);
  }

  private OperationApprovalReview session(
      String authenticatedTask, DeploymentSecurityPolicy deployment) {
    return session(authenticatedTask, deployment, WorkspaceSecurityGuidance.NONE);
  }

  private OperationApprovalReview session(
      String authenticatedTask,
      DeploymentSecurityPolicy deployment,
      WorkspaceSecurityGuidance guidance) {
    return session(authenticatedTask, null, deployment, guidance);
  }

  private OperationApprovalReview session(
      String authenticatedTask,
      ScheduledTask scheduledTask,
      DeploymentSecurityPolicy deployment,
      WorkspaceSecurityGuidance guidance) {
    when(operation.executor()).thenReturn("sam");
    when(operation.home()).thenReturn(Home.of("project"));
    when(policies.read("sam"))
        .thenReturn(
            new ApprovalPolicy(
                1,
                ApprovalPolicy.Mode.SMART,
                null,
                List.of(rule(ApprovalPolicy.Effect.MAY_APPROVE))));
    ReviewInferenceFactory inference = mock(ReviewInferenceFactory.class);
    when(inference.within(any(), any(), any(), any()))
        .thenReturn(
            (definition, system, evidence, work) -> {
              consultations.incrementAndGet();
              if (revokeDuringReview.get()) revoked.set(true);
              reviewerEvidence.set(evidence);
              reviewerPrompt.set(system);
              if (definition.name().equals("security_reviewer")) securityEvidence.set(evidence);
              if (authenticatedTask != null && authenticatedTask.length() <= 16000)
                assertTrue(evidence.contains(authenticatedTask));
              return definition.name().equals("security_reviewer")
                  ? output.get()
                  : "{\"approved\":true,\"reason\":\"Matches personal instructions.\"}";
            });
    var sessions =
        new PersonalApprovalReviewSessions(
            policies,
            bots,
            inference,
            () -> security,
            Duration.ofSeconds(2),
            mock(HumanApprovalHistory.class),
            deployment,
            guidance);
    return sessions
        .operations(
            new ApprovalReviewSessions.Context(
                "sam",
                "conversation",
                authenticatedTask,
                scheduledTask,
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
  void a_saved_schedule_supplies_intent_for_an_exact_personal_mirror_edit() {
    String instruction = "Update the scheduled brief in /personal/Out.";
    var session = scheduledSession("sam", instruction);
    var edit = personalEdit("sam", "mirror", false, "new finding");
    var consent = new ApprovalReviews.Consent(true, false, false);

    assertEquals(ApprovalReviews.Action.ALLOW, session.review(edit, consent).action());
    assertTrue(securityEvidence.get().contains("\"scheduledTask\":\"" + instruction + "\""));
    assertTrue(securityEvidence.get().contains("\"userIntent\":null"));
    session.requireCurrent(edit);
    assertEquals(
        ApprovalReviews.Action.ASK_HUMAN,
        session.review(edit, new ApprovalReviews.Consent(true, true, false)).action());
    assertEquals(
        ApprovalReviews.Action.ASK_HUMAN,
        session.review(personalEdit("sam", "mirror", false, "x".repeat(16001)), consent).action());

    output.set("{\"outcome\":\"REJECT\",\"reason\":\"The edit is outside the scheduled task.\"}");
    assertEquals(ApprovalReviews.Action.REJECT, session.review(edit, consent).action());
  }

  @Test
  void a_saved_schedule_does_not_supply_intent_for_remote_or_other_owner_edits_or_security_files() {
    var session = scheduledSession("sam", "Update the scheduled brief.");
    var consent = new ApprovalReviews.Consent(true, false, false);
    for (var unrelated :
        List.of(
            personalEdit("sam", "remote:session", false, "note"),
            personalEdit("another", "mirror", false, "note"),
            request)) {
      assertEquals(ApprovalReviews.Action.ASK_HUMAN, session.review(unrelated, consent).action());
      assertTrue(securityEvidence.get().contains("\"scheduledTask\":null"));
    }
    var otherSchedule = scheduledSession("another", "Update the scheduled brief.");
    assertEquals(
        ApprovalReviews.Action.ASK_HUMAN,
        otherSchedule.review(personalEdit("sam", "mirror", false, "note"), consent).action());
    assertTrue(securityEvidence.get().contains("\"scheduledTask\":null"));
    assertEquals(
        ApprovalReviews.Action.REJECT,
        session.review(personalEdit("sam", "mirror", true, "note"), consent).action());
  }

  private OperationApprovalReview scheduledSession(String owner, String instruction) {
    var jobs = mock(EventRuns.class);
    var task = new AtomicReference<ScheduledTask>();
    when(jobs.submitTriggeredEvent(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            nullable(ScheduledTask.class)))
        .thenAnswer(
            call -> {
              task.set(call.getArgument(9));
              return new JobStore.EventRun("job", "conversation");
            });
    var trigger =
        new TriggerRecord(
            "daily", "daily", "project", null, "bot", instruction, null, null, 1, false, owner);
    var firing = mock(FiringRecord.class);
    when(firing.data()).thenReturn(new EventPayload.Scheduled("daily", Instant.EPOCH));
    new AgentRunner(mock(Callers.class), jobs, mock(Turn.class))
        .start(trigger, firing, instruction + "\nEvent payload is data.", (id, outcome) -> {});
    return session(null, task.get(), DeploymentSecurityPolicy.NONE, WorkspaceSecurityGuidance.NONE);
  }

  private static OperationApprovalReview.Request personalEdit(
      String owner, String provider, boolean protectedPath, String input) {
    String path = protectedPath ? "/personal/security.md" : "/personal/Out/scheduled-brief.md";
    return new OperationApprovalReview.Request(
        "project",
        provider,
        List.of(),
        Path.of(path).getParent().toString(),
        input,
        "edit",
        ApprovalPolicy.Kind.FILE_WRITE,
        List.of(path),
        Optional.of(new PersonalWorkspace(owner, Path.of("/personal"))),
        protectedPath);
  }

  @Test
  void deployment_intent_applies_without_an_account_rule_and_yields_to_a_personal_reject() {
    var session =
        session(
            null,
            new DeploymentSecurityPolicy(
                List.of(personalReadRule()), "Check explicit intent before security file edits."));
    when(policies.read("sam")).thenReturn(ApprovalPolicy.manual());
    var read = personalRead("sam");
    var consent = new ApprovalReviews.Consent(true, false, false);
    assertEquals(ApprovalReviews.Action.ALLOW, session.review(read, consent).action());
    assertTrue(reviewerPrompt.get().contains("Check explicit intent before security file edits."));
    session.requireCurrent(read);

    var block =
        new ApprovalPolicy.Rule(
            "block-read",
            ApprovalPolicy.Effect.REJECT,
            ApprovalPolicy.Kind.FILE_READ,
            null,
            null,
            null,
            null,
            List.of(),
            List.of(),
            null);
    when(policies.read("sam"))
        .thenReturn(new ApprovalPolicy(1, ApprovalPolicy.Mode.MANUAL, null, List.of(block)));
    assertEquals(ApprovalReviews.Action.REJECT, session.review(read, consent).action());
  }

  @Test
  void auto_requires_deployment_intent_for_verified_personal_reads_without_a_task() {
    var withoutStandingIntent = session(null);
    var session =
        session(
            null,
            new DeploymentSecurityPolicy(List.of(personalReadRule()), ""),
            (project, provider, work) ->
                new WorkspaceSecurityGuidance.Guidance("Require extra care.", ""));
    var bot = mock(AgentDefinition.class);
    when(bot.name()).thenReturn("my-bot");
    when(bot.bot()).thenReturn(true);
    when(bot.prompt()).thenReturn("Review only");
    when(bots.selected("sam", "my-bot")).thenReturn(bot);
    when(policies.read("sam"))
        .thenReturn(
            new ApprovalPolicy(2, ApprovalPolicy.Mode.AUTO, "my-bot", List.of(personalReadRule())));
    var consent = new ApprovalReviews.Consent(true, false, false);
    var read = personalRead("sam");
    assertEquals(
        ApprovalReviews.Action.ASK_HUMAN, withoutStandingIntent.review(read, consent).action());
    assertEquals(1, consultations.get());
    assertTrue(reviewerEvidence.get().contains("\"ownerPersonalRead\":true"));

    var approved = session.review(read, consent);
    assertEquals(ApprovalReviews.Action.ALLOW, approved.action());
    assertEquals(List.of("personal-reads"), approved.matchedRules());
    assertEquals(3, consultations.get());
    assertTrue(reviewerEvidence.get().contains("\"userIntent\":null"));
    assertTrue(reviewerEvidence.get().contains("\"effect\":\"PERSONAL_READ_INTENT\""));
    assertTrue(securityEvidence.get().contains("Require extra care."));
    assertFalse(reviewerEvidence.get().contains("Require extra care."));
    assertThrows(
        WorkspaceRefusedException.class, () -> session.requireCurrent(personalRead("other")));
    session.requireCurrent(read);

    assertEquals(
        ApprovalReviews.Action.ASK_HUMAN, session.review(personalRead("other"), consent).action());
    assertTrue(reviewerEvidence.get().contains("\"ownerPersonalRead\":false"));
    assertFalse(reviewerEvidence.get().contains("\"effect\":\"PERSONAL_READ_INTENT\""));
    assertEquals(ApprovalReviews.Action.ASK_HUMAN, session.review(request, consent).action());
    assertEquals(
        5, consultations.get(), "Missing task evidence for other work never reaches the bot");
  }

  @Test
  void verified_personal_read_still_honors_security_and_human_only_consent() {
    var session = session(null, new DeploymentSecurityPolicy(List.of(personalReadRule()), ""));
    var read = personalRead("sam");
    assertEquals(
        ApprovalReviews.Action.ASK_HUMAN,
        session.review(read, new ApprovalReviews.Consent(true, true, false)).action());
    output.set("{\"outcome\":\"REJECT\",\"reason\":\"This file is outside the review scope.\"}");
    assertEquals(
        ApprovalReviews.Action.REJECT,
        session.review(read, new ApprovalReviews.Consent(true, false, false)).action());
  }

  @Test
  void standing_read_intent_does_not_cover_a_task_too_large_to_review() {
    var session =
        session(
            "Do not read my preferences. " + "x".repeat(16000),
            new DeploymentSecurityPolicy(List.of(personalReadRule()), ""));
    assertEquals(
        ApprovalReviews.Action.ASK_HUMAN,
        session
            .review(personalRead("sam"), new ApprovalReviews.Consent(true, false, false))
            .action());
  }

  @Test
  void unmatched_remote_path_restriction_disables_standing_intent_without_canonical_identity() {
    var session = session(null, new DeploymentSecurityPolicy(List.of(personalReadRule()), ""));
    var restrict =
        new ApprovalPolicy.Rule(
            "restrict",
            ApprovalPolicy.Effect.REJECT,
            ApprovalPolicy.Kind.FILE_READ,
            null,
            "remote:session",
            null,
            null,
            List.of(),
            List.of("/personal/private.md"),
            null);
    when(policies.read("sam"))
        .thenReturn(new ApprovalPolicy(2, ApprovalPolicy.Mode.SMART, null, List.of(restrict)));
    assertEquals(
        ApprovalReviews.Action.ASK_HUMAN,
        session
            .review(personalRead("sam"), new ApprovalReviews.Consent(true, false, false))
            .action());
    assertFalse(reviewerEvidence.get().contains("\"effect\":\"PERSONAL_READ_INTENT\""));
  }

  @Test
  void protected_edit_without_task_or_verification_cannot_become_a_human_question() {
    var request = protectedEdit();
    var session = session(null);
    var consent = new ApprovalReviews.Consent(true, false, false);
    assertEquals(ApprovalReviews.Action.REJECT, session.review(request, consent).action());
    assertEquals(0, consultations.get());
    assertThrows(WorkspaceRefusedException.class, () -> session.humanOverride(request, "approval"));

    var inspection = session("Inspect the workspace.");
    output.set(
        "{\"outcome\":\"ASK_HUMAN\",\"reason\":\"No request for an edit.\",\"explicitRequest\":null}");
    assertEquals(ApprovalReviews.Action.REJECT, inspection.review(request, consent).action());
  }

  @Test
  void protected_edit_can_honor_human_consent_after_security_verifies_the_exact_request() {
    String task = "Update security.md to require review for exports.";
    var session = session(task);
    var request = protectedEdit();
    output.set(
        """
        {"outcome":"CLEAR","reason":"Explicitly requested","explicitRequest":"Update security.md to require review for exports."}
        """);
    assertEquals(
        ApprovalReviews.Action.ASK_HUMAN,
        session.review(request, new ApprovalReviews.Consent(true, true, false)).action());
    assertThrows(WorkspaceRefusedException.class, () -> session.humanOverride(request, "approval"));
    session.humanOverride(request, "approval", true);
    session.requireCurrent(request);
  }

  private static OperationApprovalReview.Request protectedEdit() {
    return new OperationApprovalReview.Request(
        "project",
        "remote:session",
        List.of(),
        "/project",
        "{\"path\":\"/project/security.md\",\"content\":\"# Security\"}",
        "call",
        ApprovalPolicy.Kind.FILE_WRITE,
        List.of("/project/security.md"),
        Optional.empty(),
        true);
  }

  private static ApprovalPolicy.Rule personalReadRule() {
    return new ApprovalPolicy.Rule(
        "personal-reads",
        ApprovalPolicy.Effect.PERSONAL_READ_INTENT,
        ApprovalPolicy.Kind.FILE_READ,
        null,
        null,
        null,
        null,
        List.of(),
        List.of(),
        null);
  }

  private static OperationApprovalReview.Request personalRead(String owner) {
    return new OperationApprovalReview.Request(
        "project",
        "remote:session",
        List.of(),
        "/personal",
        "{\"path\":\"/personal/preferences.md\"}",
        "call",
        ApprovalPolicy.Kind.FILE_READ,
        List.of("/personal/preferences.md"),
        Optional.of(new PersonalWorkspace(owner, Path.of("/personal"))));
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
