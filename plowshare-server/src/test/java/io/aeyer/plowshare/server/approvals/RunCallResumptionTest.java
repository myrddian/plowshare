package io.aeyer.plowshare.server.approvals;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ToolCall;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class RunCallResumptionTest {
  private final SuspendedRunCalls calls = mock(SuspendedRunCalls.class);
  private final RunCallResumption resumption = new RunCallResumption(calls);
  private final AtomicInteger executions = new AtomicInteger();

  private SuspendedRunCalls.Call call(String state, String decision, String result) {
    return new SuspendedRunCalls.Call(
        "apr_1",
        "cnv_1",
        "original-session",
        "local",
        new ToolCall(
            "call_1",
            "run",
            "{\"command\":[\"program\",\"one argument\",\"\"],\"stdin\":\"line\\n\",\"timeout_seconds\":17}"),
        state,
        decision,
        "reviewer",
        "External publishing is not needed",
        result);
  }

  private String execute(SuspendedRunCalls.Call call) {
    executions.incrementAndGet();
    assertEquals("original-session", call.session());
    assertTrue(call.tool().arguments().contains("one argument"));
    return "exit 0";
  }

  @Test
  void pending_consent_never_executes_or_asks_a_model() {
    assertFalse(
        resumption.resume(
            call("waiting", "asked", null),
            this::execute,
            result -> fail("No tool result exists yet")));
    assertEquals(0, executions.get());
    verifyNoInteractions(calls);
  }

  @Test
  void approval_claims_once_and_retains_the_result_before_transcript_append() {
    when(calls.claim("apr_1")).thenReturn(true);
    assertTrue(
        resumption.resume(
            call("waiting", "allowed", null),
            this::execute,
            result -> {
              assertEquals("exit 0", result);
              verify(calls).complete("apr_1", "exit 0");
            }));
    assertEquals(1, executions.get());
    InOrder order = inOrder(calls);
    order.verify(calls).claim("apr_1");
    order.verify(calls).complete("apr_1", "exit 0");
    order.verify(calls).acknowledged("apr_1");
  }

  @Test
  void denial_returns_who_and_why_without_executing() {
    assertTrue(
        resumption.resume(
            call("waiting", "denied", null),
            this::execute,
            result -> {
              assertTrue(result.contains("reviewer"));
              assertTrue(result.contains("External publishing is not needed"));
              assertTrue(result.contains("did not run"));
              assertFalse(result.contains("Run it again"));
            }));
    assertEquals(0, executions.get());
    verify(calls, never()).claim(anyString());
  }

  @Test
  void a_crash_after_dispatch_is_unknown_and_never_replays() {
    assertTrue(
        resumption.resume(
            call("started", "used", null),
            this::execute,
            result -> assertTrue(result.contains("outcome is unknown"))));
    assertEquals(0, executions.get());
    verify(calls, never()).claim(anyString());
  }

  @Test
  void a_crash_after_retaining_the_result_only_repeats_the_transcript_append() {
    assertTrue(
        resumption.resume(
            call("completed", "used", "exit 0"),
            this::execute,
            result -> assertEquals("exit 0", result)));
    assertEquals(0, executions.get());
    verify(calls, never()).claim(anyString());
    verify(calls, never()).complete(anyString(), anyString());
    verify(calls).acknowledged("apr_1");
  }

  @Test
  void lost_claim_and_dispatch_failure_do_not_acknowledge_or_hide_uncertainty() {
    assertThrows(
        IllegalStateException.class,
        () -> resumption.resume(call("waiting", "allowed", null), this::execute, result -> fail()));
    when(calls.claim("apr_1")).thenReturn(true);
    assertThrows(
        IllegalStateException.class,
        () ->
            resumption.resume(
                call("waiting", "allowed", null),
                retained -> {
                  throw new IllegalStateException("Bridge response lost");
                },
                result -> fail()));
    verify(calls, never()).complete(anyString(), anyString());
    verify(calls, never()).acknowledged(anyString());
  }
}
