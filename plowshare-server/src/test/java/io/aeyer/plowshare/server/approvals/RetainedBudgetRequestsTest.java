package io.aeyer.plowshare.server.approvals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.BudgetCheckpoint;
import io.aeyer.plowshare.server.agents.BudgetRequests;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.allowances.AllowanceId;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RetainedBudgetRequestsTest {
  private static BudgetCheckpoint checkpoint() {
    return new BudgetCheckpoint(
        null,
        "owner",
        List.of(
            new BudgetCheckpoint.Frame("cnv_root", "boss", 1, 2, 4, List.of()),
            new BudgetCheckpoint.Frame("cnv_child", "helper", 1, 3, 3, List.of())));
  }

  @Test
  void approved_wait_adds_once_and_keeps_the_original_spending_identity_and_cap() throws Exception {
    Budget budget = Budget.of(2);
    assertTrue(budget.trySpend());
    assertTrue(budget.trySpend());
    var original = budget.identity();
    TestBudgetRequests repository = new TestBudgetRequests(budget);
    BudgetRequestAccess access = mock(BudgetRequestAccess.class);
    when(access.require(any(), any())).thenReturn(1L);
    var ready = new CountDownLatch(1);
    var active = new ActiveRunApprovals();
    var continuations = mock(BudgetRequestContinuations.class);
    var service =
        new RetainedBudgetRequests(
            repository, access, active, root -> ready.countDown(), continuations);
    TurnCap cap = TurnCap.of(3);
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var waiting =
          workers.submit(
              () ->
                  service.await(
                      checkpoint(),
                      budget,
                      cap,
                      new BudgetExtension(4, 2),
                      "Finish the work",
                      () -> false));
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      assertFalse(waiting.isDone());
      assertTrue(active.waitingIn("cnv_root"));
      service.recover();
      verifyNoInteractions(continuations);
      var question = repository.approval();
      assertTrue(service.answer(question, true, "reviewer").orElseThrow().live());
      service.answer(question, true, "reviewer");
      var result = waiting.get(10, TimeUnit.SECONDS).orElseThrow();
      assertTrue(result.allowed());
      assertEquals(original, budget.identity());
      assertEquals(2, budget.spent());
      assertEquals(6, budget.limit());
      assertEquals(5, cap.turns());
      assertFalse(active.waitingIn("cnv_root"));
      service.acknowledged(result.approval());
      service.recover();
      verifyNoInteractions(continuations);
    }
  }

  @Test
  void interrupted_stack_retains_consent_and_recovers_the_original_frames_once() throws Exception {
    Budget budget = Budget.of(1);
    TestBudgetRequests repository = new TestBudgetRequests(budget);
    BudgetRequestAccess access = mock(BudgetRequestAccess.class);
    when(access.require(any(), any())).thenReturn(1L);
    var ready = new CountDownLatch(1);
    var first =
        new RetainedBudgetRequests(
            repository,
            access,
            new ActiveRunApprovals(),
            root -> ready.countDown(),
            mock(BudgetRequestContinuations.class));
    var result = new CompletableFuture<BudgetRequests.Decision>();
    Thread thread =
        Thread.ofVirtual()
            .start(
                () ->
                    result.complete(
                        first
                            .await(
                                checkpoint(),
                                budget,
                                TurnCap.of(3),
                                new BudgetExtension(2, 2),
                                "Finish",
                                () -> false)
                            .orElseThrow()));
    assertTrue(ready.await(10, TimeUnit.SECONDS));
    thread.interrupt();
    assertTrue(result.get(10, TimeUnit.SECONDS).pending());
    assertTrue(repository.awaiting("cnv_root"));
    assertEquals(RunApproval.ASKED, repository.approval().state());
    var continuations = mock(BudgetRequestContinuations.class);
    when(continuations.resume(any())).thenReturn(true);
    var restarted =
        new RetainedBudgetRequests(
            repository, access, new ActiveRunApprovals(), root -> {}, continuations);
    restarted.answer(repository.approval(), true, "reviewer");
    restarted.recover();
    restarted.recover();
    verify(continuations, times(1))
        .resume(
            argThat(
                request ->
                    request.root().equals("cnv_root")
                        && request.checkpoint().equals(checkpoint())));
    assertEquals("cnv_child", restarted.restoringChild("cnv_root").orElseThrow().conversation());
    assertEquals(5, restarted.restoring("cnv_child").orElseThrow().stepLimit());
    assertEquals(4, restarted.restoring("cnv_root").orElseThrow().stepLimit());
    restarted.recoveryStarted("cnv_root");
    restarted.restored("cnv_child");
    restarted.restored("cnv_root");
    assertFalse(repository.awaiting("cnv_root"));
    assertEquals(1, repository.questions());
  }

  @Test
  void cancellation_withdraws_the_question_without_a_continuation() {
    Budget budget = Budget.of(1);
    TestBudgetRequests repository = new TestBudgetRequests(budget);
    BudgetRequestAccess access = mock(BudgetRequestAccess.class);
    var continuations = mock(BudgetRequestContinuations.class);
    var service =
        new RetainedBudgetRequests(
            repository, access, new ActiveRunApprovals(), root -> {}, continuations);
    assertTrue(
        service
            .await(
                checkpoint(),
                budget,
                TurnCap.of(3),
                new BudgetExtension(2, 0),
                "Finish",
                () -> true)
            .isEmpty());
    assertEquals(RunApproval.DENIED, repository.approval().state());
    assertFalse(repository.awaiting("cnv_root"));
    service.recover();
    verifyNoInteractions(continuations);
    assertEquals(1, budget.limit());
  }

  @Test
  void interrupted_recovery_reports_uncertain_effects_without_replaying_or_stranding_the_request() {
    Budget budget = Budget.of(2);
    TestBudgetRequests repository = new TestBudgetRequests(budget);
    var question =
        repository.ask(
            1,
            checkpoint(),
            new AllowanceId(budget.identity()),
            new BudgetExtension(2, 0),
            "Finish");
    repository.answer(question.approval(), true, "reviewer");
    repository.recoveryStarted(question.approval());
    var continuations = mock(BudgetRequestContinuations.class);
    var service =
        new RetainedBudgetRequests(
            repository,
            mock(BudgetRequestAccess.class),
            new ActiveRunApprovals(),
            root -> {},
            continuations);
    service.recover();
    service.recover();
    verify(continuations).interrupted(any(), any());
    verify(continuations, never()).resume(any());
    assertFalse(repository.awaiting("cnv_root"));
  }

  @Test
  void revoked_authority_cannot_grant_or_resume_but_does_not_prevent_denial() {
    Budget budget = Budget.of(1);
    TestBudgetRequests repository = new TestBudgetRequests(budget);
    repository.ask(
        1, checkpoint(), new AllowanceId(budget.identity()), new BudgetExtension(2, 0), "Finish");
    BudgetRequestAccess access = mock(BudgetRequestAccess.class);
    doThrow(new IllegalStateException("Authority revoked")).when(access).requireRecovery(any());
    var continuations = mock(BudgetRequestContinuations.class);
    var service =
        new RetainedBudgetRequests(
            repository, access, new ActiveRunApprovals(), root -> {}, continuations);
    assertThrows(
        IllegalStateException.class, () -> service.answer(repository.approval(), true, "reviewer"));
    service.answer(repository.approval(), false, "reviewer");
    service.recover();
    verify(continuations).denied(any());
    verify(continuations, never()).resume(any());
    assertEquals(1, budget.limit());
  }
}
