package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.access.AuthorityAction;
import io.aeyer.plowshare.server.access.AuthorityWithdrawnException;
import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.hooks.ToolPost;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
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
  void revokedDuringToolWithholdsResultAndSaysWhy() {
    withheldAfterTool(() -> new AuthorityWithdrawnException("withdrawn"));
  }

  @Test
  void aPlainCallerFaultFromTheOperationCheckAfterTheToolIsWithheldToo() {
    withheldAfterTool(() -> new CallerFault("revoked"));
  }

  /**
   * Runs one tool call whose tool withdraws authority; the next after-tool check then refuses with
   * {@code refusal}. A confined (RunAdmission) runtime rejects custom tools, so the shared catch is
   * exercised through the operation check.
   */
  private void withheldAfterTool(Supplier<CallerFault> refusal) {
    var dispatcher = mock(LlmDispatcher.class);
    var authority = mock(OperationAuthority.class);
    var admission = mock(OperationAuthority.Admission.class);
    when(admission.executor()).thenReturn("alice");
    when(admission.home()).thenReturn(Home.of("one"));
    when(admission.actions())
        .thenReturn(Set.of(AuthorityAction.READ, AuthorityAction.WRITE, AuthorityAction.RUN_MODEL));
    AtomicBoolean revoked = new AtomicBoolean();
    // Withdrawn once, by the tool; later checks pass so the next history can be inspected.
    Runnable check =
        () -> {
          if (revoked.getAndSet(false)) throw refusal.get();
        };
    doAnswer(
            call -> {
              check.run();
              return null;
            })
        .when(authority)
        .require(admission);
    when(authority.admit(any(), any(), any())).thenReturn(admission);
    AgentTool probe =
        new AgentTool() {
          public ToolSchema schema() {
            return ToolSchema.from("probe", "fixture", Map.of("type", "object"));
          }

          public String run(String arguments, Home home) {
            revoked.set(true);
            return "private tool result";
          }
        };
    var histories = new ArrayList<String>();
    var calls = new AtomicInteger();
    when(dispatcher.stream(any(), any(), any()))
        .thenAnswer(
            call -> {
              histories.add(String.valueOf(call.getArguments()[0]));
              return calls.incrementAndGet() == 1
                  ? new Completion(
                      "",
                      "tool_calls",
                      TokenUsage.UNKNOWN,
                      List.of(new ToolCall("c1", "probe", "{}")))
                  : new Completion("done", "stop", TokenUsage.UNKNOWN, List.of());
            });
    var runtime = new JobRuntime(authority, dispatcher, List.of(probe));
    var postCalls = new AtomicInteger();
    runtime.useHooks(
        new Hooks() {
          @Override
          public ToolPost toolPost(
              HookContext context, String tool, String argumentsJson, String result) {
            postCalls.incrementAndGet();
            return new ToolPost(result, List.of());
          }
        });
    var definition =
        new AgentDefinition(
            "chat", "chat", "fast", List.of("probe"), List.of(), List.of(), 4, 4, "Answer");
    runtime.run(
        definition,
        "hello",
        Home.of("one"),
        Budget.of(4),
        () -> false,
        null,
        JobWatch.UNWATCHED,
        mock(Transcript.class, CALLS_REAL_METHODS),
        TurnCap.of(4),
        List.of(),
        "alice",
        false);
    String second = histories.get(1);
    assertTrue(second.contains("withheld because this run's authority changed"), second);
    assertTrue(second.contains("Any change the tool made has already happened."), second);
    assertFalse(second.contains("fault in the tool"), second);
    assertFalse(second.contains("private tool result"), second);
    assertEquals(0, postCalls.get(), "tool.post must not see a withheld result");
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

  @Test
  void existingBoardContinuationUsesAFreshChargedLeaseFromTheSameRetainedRoot() {
    var definition =
        new AgentDefinition(
            "worker", "worker", "fast", List.of(), List.of(), List.of(), 3, 3, "task");
    var account = new io.aeyer.plowshare.server.access.AuthorityIds.AccountId(UUID.randomUUID());
    var admission =
        new OperationAuthority.Admission(
            UUID.randomUUID(),
            "alice",
            account,
            new io.aeyer.plowshare.server.access.AuthorityIds.ScopeId(UUID.randomUUID()),
            Home.of("one"),
            io.aeyer.plowshare.server.access.GrantRecipient.account(account),
            List.of(
                new OperationAuthority.Support(
                    io.aeyer.plowshare.server.access.AuthorityAction.RUN_MODEL,
                    UUID.randomUUID())));
    var charges = new java.util.concurrent.atomic.AtomicInteger();
    UUID source = UUID.randomUUID();
    java.util.function.Supplier<Optional<Budget.Claim>> claim =
        () -> Optional.of(new Budget.Claim(source, charges.incrementAndGet(), null));
    Budget first = Budget.boardLease(source, 3, claim);
    var bound =
        new OperationAuthority.Conversation(
            "child",
            "worker",
            admission,
            "seat",
            first.snapshot().id(),
            OperationExecution.fingerprint(definition));
    first.tryClaim().orElseThrow();
    Budget continuation = Budget.boardLease(source, 2, claim);
    assertEquals(first.identity(), continuation.identity());
    assertDoesNotThrow(
        () ->
            OperationExecution.requireBinding(
                bound, "alice", Home.of("one"), definition, continuation));
    var second = continuation.tryClaim().orElseThrow();
    assertEquals(2, charges.get());
    assertEquals(first.tryClaim().orElseThrow().budget(), second.budget());
    assertEquals(
        1, continuation.spent(), "the wake window counts its own calls, not root ordinals");
    assertThrows(
        CallerFault.class,
        () ->
            OperationExecution.requireBinding(
                bound,
                "alice",
                Home.of("one"),
                definition,
                Budget.boardLease(UUID.randomUUID(), 3, Optional::empty)));
    assertThrows(
        CallerFault.class,
        () ->
            OperationExecution.requireBinding(
                bound, "alice", Home.of("one"), definition, Budget.none()));
  }
}
