package io.aeyer.plowshare.server.approvals;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ToolCall;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HarnessRunApprovalContinuationsTest {
  private final SuspendedRunCalls calls = mock(SuspendedRunCalls.class);
  private final RunApprovalStore approvals = mock(RunApprovalStore.class);
  private final ActiveRunApprovals active = new ActiveRunApprovals();
  private final RetainedRunStarter starter = mock(RetainedRunStarter.class);
  private final List<RunApprovalContinuations.RecoveryProblem> problems = new ArrayList<>();
  private final HarnessRunApprovalContinuations continuations =
      new HarnessRunApprovalContinuations(
          calls,
          approvals,
          active,
          starter,
          () -> ConductorContinuation.NONE,
          () -> null,
          problems::add);

  private RunApproval approval() {
    return new RunApproval(
        "apr_1",
        7,
        "cnv_1",
        "cnv_1",
        "coder",
        "local",
        List.of("program"),
        "/fixture",
        null,
        RunApproval.ALLOWED,
        RunApproval.ONCE,
        null,
        "reviewer",
        Instant.now(),
        Instant.now());
  }

  private SuspendedRunCalls.Call call(String conversation, boolean released) {
    return new SuspendedRunCalls.Call(
        "apr_1",
        conversation,
        "original-session",
        "local",
        new ToolCall("original-call", "run", "{\"command\":[\"program\"]}"),
        "waiting",
        "allowed",
        "reviewer",
        null,
        null,
        "original-owner",
        released);
  }

  @Test
  void a_live_stack_never_starts_another_model_turn() {
    when(calls.find("apr_1")).thenReturn(Optional.of(call("cnv_1", true)));
    active.entered("apr_1", "cnv_1");
    assertFalse(continuations.resume("apr_1").orElseThrow().busy());
    verifyNoInteractions(starter, approvals);
  }

  @Test
  void recovery_uses_the_original_account_and_session_instead_of_the_approvers() {
    var retained = call("cnv_1", true);
    when(calls.find("apr_1")).thenReturn(Optional.of(retained));
    when(calls.pending("cnv_1")).thenReturn(List.of(retained));
    when(approvals.find("apr_1")).thenReturn(Optional.of(approval()));
    when(starter.start(any(), any(), any())).thenReturn("job_recovered");
    assertEquals("job_recovered", continuations.resume("apr_1").orElseThrow().job());
    var original = ArgumentCaptor.forClass(SuspendedRunCalls.Call.class);
    verify(starter).start(any(), original.capture(), any());
    assertEquals("original-session", original.getValue().session());
    assertEquals("original-owner", original.getValue().owner());
  }

  @Test
  void interrupted_handoff_records_post_before_releasing_the_wait() {
    var retained = new AtomicReference<>(call("cnv_1", false));
    when(calls.find("apr_1")).thenAnswer(ignored -> Optional.of(retained.get()));
    when(calls.recoverable()).thenAnswer(ignored -> List.of(retained.get()));
    when(calls.pending("cnv_1")).thenAnswer(ignored -> List.of(retained.get()));
    when(approvals.find("apr_1")).thenReturn(Optional.of(approval()));
    when(starter.start(any(), any(), any())).thenReturn("job_recovered");
    var postTold = new java.util.concurrent.atomic.AtomicBoolean();
    continuations.whenDecisionRecovered(answer -> postTold.set(true));
    doAnswer(
            ignored -> {
              assertTrue(postTold.get());
              retained.set(call("cnv_1", true));
              return null;
            })
        .when(calls)
        .release("apr_1");
    continuations.recover();
    verify(starter).start(any(), any(), any());
    verify(calls).release("apr_1");
  }

  @Test
  void a_lost_delegation_owner_is_reported_instead_of_prompting_the_root_to_repeat_it() {
    var retained = call("cnv_child", true);
    when(calls.find("apr_1")).thenReturn(Optional.of(retained));
    when(calls.pending("cnv_child")).thenReturn(List.of(retained));
    when(approvals.find("apr_1")).thenReturn(Optional.of(approval()));
    assertTrue(continuations.resume("apr_1").orElseThrow().busy());
    verifyNoInteractions(starter);
    assertEquals(1, problems.size());
    assertEquals("original-owner", problems.getFirst().owner());
  }
}
