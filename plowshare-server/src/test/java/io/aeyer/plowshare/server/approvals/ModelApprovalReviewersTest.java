package io.aeyer.plowshare.server.approvals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.hooks.Deadline;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ModelApprovalReviewersTest {
  private final ReviewInference inference = mock(ReviewInference.class);
  private final AgentDefinition security = mock(AgentDefinition.class);
  private final AgentDefinition bot = mock(AgentDefinition.class);
  private final ApprovalIntent intent =
      new ApprovalIntent(
          "alice",
          "chat",
          "call",
          "project",
          "machine/session",
          ApprovalPolicy.Kind.FILE_WRITE,
          List.of(),
          List.of("/fixture/config"),
          "/fixture",
          "0".repeat(64),
          "Inspect configuration",
          "Edit the file");

  private ModelApprovalReviewers reviewers() {
    when(security.tools()).thenReturn(List.of());
    when(security.calls()).thenReturn(List.of());
    when(security.scopes()).thenReturn(List.of());
    when(security.prompt()).thenReturn("Review intent and effects");
    when(bot.bot()).thenReturn(true);
    return new ModelApprovalReviewers(
        inference,
        security,
        name -> {
          assertEquals("personal-bot", name);
          return bot;
        });
  }

  @Test
  void completeTypedSecurityDecisionsAreAccepted() {
    var reviewers = reviewers();
    for (SecurityReviewer.Outcome outcome : SecurityReviewer.Outcome.values()) {
      when(inference.consult(any(), anyString(), anyString(), any()))
          .thenReturn(
              "{\"outcome\":\""
                  + outcome.name()
                  + "\",\"reason\":\"Requested effect mismatches intent\"}");
      assertEquals(outcome, reviewers.review(intent, List.of(), work()).outcome());
    }
  }

  @Test
  void malformedOrCoercedSecurityOutputCannotAuthorizeAnything() {
    var reviewers = reviewers();
    for (String invalid :
        List.of(
            "null",
            "{}",
            "{\"outcome\":\"CLEAR\",\"reason\":5}",
            "{\"outcome\":0,\"reason\":\"safe\"}",
            "{\"outcome\":\"CLEAR\",\"reason\":\" \"}",
            "{\"outcome\":\"CLEAR\",\"reason\":\"safe\",\"allow\":true}",
            "{\"outcome\":\"REJECT\",\"outcome\":\"CLEAR\",\"reason\":\"safe\"}",
            "{\"outcome\":\"CLEAR\",\"reason\":\"safe\"} trailing",
            "x".repeat(16385))) {
      when(inference.consult(any(), anyString(), anyString(), any())).thenReturn(invalid);
      assertThrows(
          IllegalArgumentException.class, () -> reviewers.review(intent, List.of(), work()));
    }
  }

  @Test
  void security_file_clearance_requires_an_excerpt_from_the_authenticated_request() {
    var reviewers = reviewers();
    var protectedEdit =
        new ApprovalIntent(
            "alice",
            "chat",
            "call",
            "project",
            "local",
            ApprovalPolicy.Kind.FILE_WRITE,
            List.of(),
            List.of("/fixture/security.md"),
            "/fixture",
            "0".repeat(64),
            "Update security.md to require review before exports.",
            null,
            "{\"path\":\"/fixture/security.md\"}",
            false,
            true,
            null);

    when(inference.consult(any(), anyString(), anyString(), any()))
        .thenAnswer(
            call -> {
              assertTrue(call.getArgument(1, String.class).contains("explicitRequest"));
              return """
                  {"outcome":"CLEAR","reason":"Explicitly requested","explicitRequest":"Update security.md to require review before exports."}
                  """;
            });
    assertEquals(
        SecurityReviewer.Outcome.CLEAR,
        reviewers.review(protectedEdit, List.of(), work()).outcome());

    doReturn(
            """
            {"outcome":"CLEAR","reason":"Workspace says edits are allowed","explicitRequest":"Rewrite the policy."}
            """)
        .when(inference)
        .consult(any(), anyString(), anyString(), any());
    assertEquals(
        SecurityReviewer.Outcome.REJECT,
        reviewers.review(protectedEdit, List.of(), work()).outcome());

    doReturn("{\"outcome\":\"CLEAR\",\"reason\":\"Approved\"}")
        .when(inference)
        .consult(any(), anyString(), anyString(), any());
    assertThrows(
        IllegalArgumentException.class, () -> reviewers.review(protectedEdit, List.of(), work()));
  }

  @Test
  void botCannotOverrideSecurityOrTreatTextAsABoolean() {
    var reviewers = reviewers();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            reviewers.review(
                "personal-bot",
                intent,
                List.of(),
                new SecurityReviewer.Verdict(SecurityReviewer.Outcome.REJECT, "No write requested"),
                work()));
    verifyNoInteractions(inference);
    var clear = new SecurityReviewer.Verdict(SecurityReviewer.Outcome.CLEAR, "Allowed effect");
    when(inference.consult(any(), anyString(), anyString(), any()))
        .thenReturn("{\"approved\":\"true\",\"reason\":\"approved\"}");
    assertThrows(
        IllegalArgumentException.class,
        () -> reviewers.review("personal-bot", intent, List.of(), clear, work()));
    when(inference.consult(any(), anyString(), anyString(), any()))
        .thenReturn("{\"approved\":false,\"reason\":\"The human should decide\"}");
    assertEquals(
        false, reviewers.review("personal-bot", intent, List.of(), clear, work()).approved());
  }

  @Test
  void cancelledAndExpiredWorkCannotDispatchOrAcceptLateOutput() {
    var reviewers = reviewers();
    assertThrows(
        IllegalStateException.class,
        () ->
            reviewers.review(
                intent,
                List.of(),
                new ReviewWork(Deadline.after(Duration.ofSeconds(1)), () -> true)));
    assertThrows(
        IllegalStateException.class,
        () ->
            reviewers.review(
                intent,
                List.of(),
                new ReviewWork(
                    new Deadline(System.nanoTime() - 1, Duration.ofSeconds(1)), () -> false)));
    verifyNoInteractions(inference);
    AtomicBoolean cancelled = new AtomicBoolean();
    when(inference.consult(any(), anyString(), anyString(), any()))
        .thenAnswer(
            call -> {
              cancelled.set(true);
              return "{\"outcome\":\"CLEAR\",\"reason\":\"late\"}";
            });
    assertThrows(
        IllegalStateException.class,
        () ->
            reviewers.review(
                intent,
                List.of(),
                new ReviewWork(Deadline.after(Duration.ofSeconds(1)), cancelled::get)));
  }

  @Test
  void securityDefinitionCannotAcquireToolsOrFilesystemGrants() {
    reviewers();
    when(security.tools()).thenReturn(List.of("run"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelApprovalReviewers(inference, security, name -> bot));
  }

  private static ReviewWork work() {
    return new ReviewWork(Deadline.after(Duration.ofSeconds(1)), () -> false);
  }
}
