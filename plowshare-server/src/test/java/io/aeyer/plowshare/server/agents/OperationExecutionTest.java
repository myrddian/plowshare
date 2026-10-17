package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Ordinary operation checks intersect standard tools and withhold inference output on revocation.
 */
class OperationExecutionTest {
  @Test
  void revocation_during_inference_prevents_answer_and_subsequent_effects() {
    var dispatcher = mock(LlmDispatcher.class);
    var authority = mock(OperationAuthority.class);
    var admission = mock(OperationAuthority.Admission.class);
    when(admission.executor()).thenReturn("alice");
    when(admission.home()).thenReturn(Home.of("one"));
    when(admission.actions())
        .thenReturn(
            Set.of(
                io.aeyer.plowshare.server.access.AuthorityAction.READ,
                io.aeyer.plowshare.server.access.AuthorityAction.WRITE,
                io.aeyer.plowshare.server.access.AuthorityAction.RUN_MODEL));
    AtomicBoolean allowed = new AtomicBoolean(true);
    doAnswer(
            call -> {
              if (!allowed.get()) throw new CallerFault("revoked");
              return null;
            })
        .when(authority)
        .require(admission);
    when(authority.admit(any(), any(), any())).thenReturn(admission);
    when(dispatcher.stream(any(), any(), any()))
        .thenAnswer(
            call -> {
              allowed.set(false);
              return new Completion("private answer", "stop", TokenUsage.UNKNOWN, List.of());
            });
    var transcript = mock(Transcript.class, CALLS_REAL_METHODS);
    var runtime = new JobRuntime(authority, dispatcher, List.of());
    var definition =
        new AgentDefinition(
            "chat", "chat", "fast", List.of(), List.of(), List.of(), 4, 4, "Answer");
    assertThrows(
        CallerFault.class,
        () ->
            runtime.run(
                definition,
                "hello",
                Home.of("one"),
                Budget.of(4),
                () -> false,
                null,
                JobWatch.UNWATCHED,
                transcript,
                TurnCap.of(4),
                List.of(),
                "alice",
                false));
    verify(dispatcher, times(1)).stream(any(), any(), any());
    verify(transcript, never())
        .record(argThat(entry -> entry.toString().contains("private answer")));
  }

  @Test
  void revocation_after_job_binding_prevents_queued_work_from_reaching_transport()
      throws Exception {
    var dispatcher = mock(LlmDispatcher.class);
    var authority = mock(OperationAuthority.class);
    var admission = mock(OperationAuthority.Admission.class);
    when(admission.executor()).thenReturn("alice");
    when(admission.home()).thenReturn(Home.of("one"));
    when(admission.actions())
        .thenReturn(
            Set.of(
                io.aeyer.plowshare.server.access.AuthorityAction.READ,
                io.aeyer.plowshare.server.access.AuthorityAction.WRITE,
                io.aeyer.plowshare.server.access.AuthorityAction.RUN_MODEL));
    when(authority.admit(any(), any(), any())).thenReturn(admission);
    var live = new AtomicBoolean(true);
    doAnswer(
            call -> {
              live.set(false);
              return null;
            })
        .when(authority)
        .started(eq(admission), any());
    doAnswer(
            call -> {
              if (!live.get()) throw new CallerFault("revoked before dispatch");
              return null;
            })
        .when(authority)
        .requireTargets(eq(admission), any(), anyList());
    var runtime = new JobRuntime(authority, dispatcher, List.of());
    var done = new java.util.concurrent.CompletableFuture<Outcome>();
    var definition =
        new AgentDefinition(
            "chat", "chat", "fast", List.of(), List.of(), List.of(), 4, 4, "Answer");
    try (var jobs = new JobStore(authority, runtime)) {
      jobs.submit(
          definition,
          "hello",
          Home.of("one"),
          null,
          Budget.of(4),
          Transcript.NONE,
          io.aeyer.plowshare.server.archive.Origin.SUBMISSION,
          done::complete,
          TurnCap.of(4),
          List.of(),
          "alice",
          false);
      assertNotEquals(
          Outcome.Ending.ANSWERED, done.get(5, java.util.concurrent.TimeUnit.SECONDS).ending());
      verify(dispatcher, never()).stream(any(), any(), any());
    }
  }

  @Test
  void malformed_or_cross_scope_tool_targets_are_rejected_before_effects() {
    assertTrue(
        OperationToolTargets.decode("custom", "{\"project\":{\"vendorField\":true}}")
            .projects()
            .isEmpty());
    assertThrows(
        CallerFault.class, () -> OperationToolTargets.decode("memory_read", "{\"ids\":[null]}"));
    assertThrows(
        CallerFault.class,
        () -> OperationToolTargets.decode("conversation_context", "{\"conversation\":7}"));
    assertThrows(
        CallerFault.class,
        () ->
            OperationToolTargets.decode(
                "conversation_context", "{\"project\":\"one\",\"project\":\"two\"}"));
  }
}
