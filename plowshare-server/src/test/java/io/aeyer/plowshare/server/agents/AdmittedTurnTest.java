package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.access.GrantRecipient;
import io.aeyer.plowshare.server.access.InferenceCalls;
import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.access.OperationAuthority.Admission;
import io.aeyer.plowshare.server.access.OperationAuthority.Support;
import io.aeyer.plowshare.server.approvals.ApprovalDelivery;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.approvals.SuspendedRunCalls;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.hooks.ToolPre;
import io.aeyer.plowshare.server.images.ImageStore;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The real job/turn/loop over mocked persistence and dispatcher: no external model or database. */
class AdmittedTurnTest {
  AgentDefinition definition =
      new AgentDefinition(
          "chat",
          "chat",
          "fast",
          List.of(),
          List.of(),
          List.of(),
          4,
          8,
          "Answer clearly",
          true,
          false);
  final LlmDispatcher dispatcher = mock(LlmDispatcher.class);
  final ConversationStore conversations = mock(ConversationStore.class);
  final TurnStore history = mock(TurnStore.class);
  final Compaction compaction = mock(Compaction.class);
  final Compaction.TurnTranscript transcript = mock(Compaction.TurnTranscript.class);
  final AtomicBoolean allowed = new AtomicBoolean(true);
  final AtomicReference<String> admittedJob = new AtomicReference<>();
  final CountDownLatch ended = new CountDownLatch(1);
  final Budget budget = Budget.of(5);
  final Home home = Home.of("personal:616c696365");

  void fixture() {
    var row = mock(ConversationRecord.class);
    when(row.id()).thenReturn("cnv_ABC123");
    when(row.home()).thenReturn(home);
    when(row.origin()).thenReturn(Origin.TURN);
    when(row.lifecycle()).thenReturn(ConversationLifecycle.ACTIVE);
    when(row.budget()).thenReturn(budget);
    when(conversations.find("cnv_ABC123")).thenReturn(Optional.of(row));
    when(compaction.transcriptFor(eq("cnv_ABC123"), eq(definition), any())).thenReturn(transcript);
    when(transcript.conversationId()).thenReturn("cnv_ABC123");
    when(transcript.before()).thenReturn(List.of());
    when(transcript.speaker()).thenReturn(Speaker.person("alice"));
    when(transcript.usage())
        .thenReturn(io.aeyer.plowshare.server.llm.accounting.UsageAttribution.LEGACY);
    when(transcript.parentUsage())
        .thenReturn(io.aeyer.plowshare.server.llm.accounting.UsageAttribution.LEGACY);
  }

  Runnable check() {
    return () -> {
      if (!allowed.get()) throw new CallerFault("revoked");
    };
  }

  RunAdmission admission() {
    return new RunAdmission(
        check(),
        id -> assertTrue(admittedJob.compareAndSet(null, id)),
        ended::countDown,
        "alice",
        (at, refs) -> {
          check().run();
          assertEquals(home, at);
        });
  }

  /**
   * Admission settlement precedes JobStore publishing the outcome. Waiting on its callback alone
   * races publication; DONE is the store's guarantee that the outcome and accounting are visible.
   */
  void awaitCompletion(JobStore jobs, String id) throws InterruptedException {
    assertTrue(ended.await(5, TimeUnit.SECONDS), "admission did not settle");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (jobs.get(id).state() != Job.State.DONE && System.nanoTime() < deadline) {
      TimeUnit.MILLISECONDS.sleep(10);
    }
    assertEquals(Job.State.DONE, jobs.get(id).state(), "job did not publish its outcome");
  }

  @Test
  void real_turn_persists_identity_before_model_and_settles_existing_budget() throws Exception {
    fixture();
    when(dispatcher.stream(any(), any(), any()))
        .thenAnswer(
            call -> {
              assertNotNull(admittedJob.get(), "receipt identity precedes inference");
              return new Completion("answer", "stop", TokenUsage.UNKNOWN, List.of());
            });
    JobRuntime runtime = new JobRuntime(dispatcher, List.of());
    RunUsage accounting = mock(RunUsage.class);
    when(accounting.start(home, transcript, "chat", "alice")).thenReturn(transcript);
    runtime.useRunUsage(accounting);
    try (JobStore jobs = new JobStore(runtime)) {
      Turn turns = new Turn(jobs, conversations, history, compaction);
      String id = turns.speakAdmitted("cnv_ABC123", definition, "Hello", admission());
      awaitCompletion(jobs, id);
      assertEquals(id, admittedJob.get());
      verify(conversations).turnEnded("cnv_ABC123", budget);
      verify(transcript).confine(any(), eq("alice"));
      verify(accounting).start(home, transcript, "chat", "alice");
      var outcome = jobs.get(id).outcome().orElseThrow();
      assertEquals(Outcome.Ending.ANSWERED, outcome.ending());
      assertEquals("answer", outcome.text());
      assertEquals(1, budget.spent());
      assertFalse(turns.isSpeaking("cnv_ABC123"));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void goal_intrinsic_is_offered_on_the_retained_root_and_ends_the_turn_explicitly(
      boolean dedicated) throws Exception {
    fixture();
    if (dedicated) {
      var row = conversations.find("cnv_ABC123").orElseThrow();
      when(row.origin()).thenReturn(Origin.MESSAGE);
      when(row.agent()).thenReturn(definition.name());
      when(row.budget()).thenReturn(null);
    }
    var listener = mock(AgentTool.class);
    when(listener.schema())
        .thenReturn(
            ToolSchema.from("message_listen", "Listen on a Goal topic", Map.of("type", "object")));
    when(listener.run(anyString(), eq(home))).thenReturn("listener registered");
    var calls = new AtomicInteger();
    var operations = mock(OperationAuthority.class);
    var account = new AccountId(UUID.randomUUID());
    var retained =
        new Admission(
            UUID.randomUUID(),
            "alice",
            account,
            new ScopeId(UUID.randomUUID()),
            home,
            GrantRecipient.account(account),
            OperationExecution.actions(definition).stream()
                .map(action -> new Support(action, UUID.randomUUID()))
                .toList());
    when(operations.derive(eq(retained), any())).thenReturn(retained);
    when(dispatcher.stream(any(), any(), any()))
        .thenAnswer(
            call -> {
              ChatRequest request = call.getArgument(0);
              assertNotNull(admittedJob.get(), "claim precedes inference");
              assertEquals(
                  dedicated,
                  request.tools().stream().anyMatch(tool -> tool.name().equals("message_listen")));
              if (dedicated && calls.getAndIncrement() == 0)
                return new Completion(
                    null,
                    "tool_calls",
                    TokenUsage.UNKNOWN,
                    List.of(new ToolCall("bind", "message_listen", "{}")));
              assertTrue(
                  request.tools().stream().anyMatch(tool -> tool.name().equals("goal_update")));
              return new Completion(
                  null,
                  "tool_calls",
                  TokenUsage.UNKNOWN,
                  List.of(
                      new ToolCall(
                          "finished",
                          "goal_update",
                          """
{"state":"COMPLETE","explanation":"Verified the report","evidence":["report:1"]}
""")));
            });
    var decision = new AtomicReference<GoalTurn.Decision>();
    var goal =
        new GoalTurn(
            UUID.randomUUID(),
            "cnv_ABC123",
            retained,
            (id, ordinal, outcome, proposal, failed, writer) -> {
              assertEquals(admittedJob.get(), id);
              assertFalse(failed);
              assertTrue(outcome.requested());
              writer.run();
              decision.set(proposal);
            },
            dedicated ? List.of(listener) : List.of());
    doAnswer(
            call -> {
              goal.commit(admittedJob.get(), 1, call.getArgument(1), () -> {});
              return null;
            })
        .when(transcript)
        .closed(anyString(), any());
    var admitted = admission();
    var goalAdmission =
        new RunAdmission(
            admitted.check(),
            admitted.started(),
            admitted.ended(),
            admitted.subject(),
            admitted.authority(),
            goal);
    try (var jobs = new JobStore(operations, new JobRuntime(operations, dispatcher, List.of()))) {
      var turns = new Turn(jobs, conversations, history, compaction);
      String id =
          turns
              .speakGoal(
                  "cnv_ABC123",
                  definition,
                  "Goal task",
                  budget.identity(),
                  dedicated ? budget : null,
                  goalAdmission,
                  () -> {})
              .orElseThrow();
      awaitCompletion(jobs, id);
      assertEquals(GoalTurn.DecisionType.COMPLETE, decision.get().state());
      assertEquals("Verified the report", jobs.get(id).outcome().orElseThrow().text());
      assertEquals(dedicated ? 2 : 1, budget.spent());
      if (dedicated) verify(listener).run("{}", home);
      else verify(listener, never()).run(anyString(), any());
    }
  }

  @Test
  void admitted_turn_executes_a_standard_granted_tool() throws Exception {
    definition = definition.withTools(List.of("get_date"));
    fixture();
    AgentTool date = mock(AgentTool.class);
    when(date.schema()).thenReturn(ToolSchema.from("get_date", "date", Map.of("type", "object")));
    when(date.run("{}", home)).thenReturn("today");
    when(dispatcher.stream(any(), any(), any()))
        .thenReturn(
            new Completion(
                null,
                "tool_calls",
                TokenUsage.UNKNOWN,
                List.of(new ToolCall("one", "get_date", "{}"))),
            new Completion("answer", "stop", TokenUsage.UNKNOWN, List.of()));
    try (JobStore jobs = new JobStore(new JobRuntime(dispatcher, List.of(date)))) {
      String id =
          new Turn(jobs, conversations, history, compaction)
              .speakAdmitted("cnv_ABC123", definition, "Hello", admission());
      awaitCompletion(jobs, id);
      assertEquals(Outcome.Ending.ANSWERED, jobs.get(id).outcome().orElseThrow().ending());
      verify(date).run("{}", home);
      assertEquals(2, budget.spent());
    }
  }

  @Test
  void revoked_authority_never_dispatches_and_releases_the_turn() {
    fixture();
    allowed.set(false);
    try (JobStore jobs = new JobStore(new JobRuntime(dispatcher, List.of()))) {
      Turn turns = new Turn(jobs, conversations, history, compaction);
      assertThrows(
          CallerFault.class,
          () -> turns.speakAdmitted("cnv_ABC123", definition, "Hello", admission()));
      verifyNoInteractions(dispatcher);
      assertFalse(turns.isSpeaking("cnv_ABC123"));
    }
  }

  @Test
  void revocation_during_inference_withholds_the_answer_but_preserves_spending() throws Exception {
    fixture();
    when(dispatcher.stream(any(), any(), any()))
        .thenAnswer(
            call -> {
              allowed.set(false);
              return new Completion("must not escape", "stop", TokenUsage.UNKNOWN, List.of());
            });
    try (JobStore jobs = new JobStore(new JobRuntime(dispatcher, List.of()))) {
      String id =
          new Turn(jobs, conversations, history, compaction)
              .speakAdmitted("cnv_ABC123", definition, "Hello", admission());
      awaitCompletion(jobs, id);
      var result = jobs.get(id).outcome().orElseThrow();
      assertEquals(Outcome.Ending.UNAVAILABLE, result.ending());
      assertFalse(result.text().contains("must not escape"));
      assertEquals(1, result.modelCalls());
      assertEquals(1, budget.spent());
      verify(conversations).turnEnded("cnv_ABC123", budget);
    }
  }

  @Test
  void cancellation_reaches_the_existing_model_cancellation_flag() throws Exception {
    fixture();
    CountDownLatch streaming = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(dispatcher.stream(any(), any(), any()))
        .thenAnswer(
            call -> {
              streaming.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              java.util.function.BooleanSupplier cancelled = call.getArgument(2);
              assertTrue(cancelled.getAsBoolean());
              throw new CallerAbandonedException("fixture");
            });
    try (JobStore jobs = new JobStore(new JobRuntime(dispatcher, List.of()))) {
      String id =
          new Turn(jobs, conversations, history, compaction)
              .speakAdmitted("cnv_ABC123", definition, "Hello", admission());
      assertTrue(streaming.await(5, TimeUnit.SECONDS));
      jobs.cancel(id);
      release.countDown();
      awaitCompletion(jobs, id);
      assertEquals(Outcome.Ending.CANCELLED, jobs.get(id).outcome().orElseThrow().ending());
    } finally {
      release.countDown();
    }
  }

  @Test
  void failed_receipt_write_prevents_execution_and_frees_the_conversation() {
    fixture();
    var admission =
        new RunAdmission(
            check(),
            id -> {
              throw new IllegalStateException("storage failed");
            },
            ended::countDown,
            "alice",
            (at, refs) -> check().run());
    try (JobStore jobs = new JobStore(new JobRuntime(dispatcher, List.of()))) {
      Turn turns = new Turn(jobs, conversations, history, compaction);
      assertThrows(
          IllegalStateException.class,
          () -> turns.speakAdmitted("cnv_ABC123", definition, "Hello", admission));
      verifyNoInteractions(dispatcher);
      assertFalse(turns.isSpeaking("cnv_ABC123"));
    }
  }

  @Test
  void standard_hooks_are_inherited_but_ungranted_tools_and_independent_extras_are_not()
      throws Exception {
    fixture();
    Hooks hooks = spy(new Hooks() {});
    RunExtras extras = mock(RunExtras.class);
    AgentTool tool = mock(AgentTool.class);
    when(tool.schema()).thenReturn(ToolSchema.from("danger", "danger", Map.of("type", "object")));
    var runtime = new JobRuntime(dispatcher, List.of(tool));
    runtime.useHooks(hooks);
    runtime.useRunExtras(extras);
    when(dispatcher.stream(any(), any(), any()))
        .thenReturn(
            new Completion(
                null,
                "tool_calls",
                TokenUsage.UNKNOWN,
                List.of(new ToolCall("one", "danger", "{}"))))
        .thenReturn(new Completion("finished", "stop", TokenUsage.UNKNOWN, List.of()));
    try (JobStore jobs = new JobStore(runtime)) {
      String id =
          new Turn(jobs, conversations, history, compaction)
              .speakAdmitted("cnv_ABC123", definition, "Hello", admission());
      awaitCompletion(jobs, id);
      verifyNoInteractions(extras);
      verify(hooks, atLeastOnce()).promptPre(any(), anyString());
      verify(tool, never()).run(anyString(), any());
      assertTrue(jobs.get(id).outcome().isPresent());
    }
  }

  @Test
  void dedicated_goal_approval_parks_the_job_and_drains_only_after_commit() throws Exception {
    definition = definition.withTools(List.of("file_read", "todo_read"));
    fixture();
    var row = conversations.find("cnv_ABC123").orElseThrow();
    when(row.origin()).thenReturn(Origin.MESSAGE);
    when(row.agent()).thenReturn(definition.name());
    var file = mock(ReviewedFileTool.class);
    var sibling = mock(AgentTool.class);
    var calls = mock(SuspendedRunCalls.class);
    var approval = mock(RunApproval.class);
    when(approval.id()).thenReturn("apr_read");
    when(approval.conversation()).thenReturn("cnv_ABC123");
    when(approval.side()).thenReturn("local");
    when(file.schema()).thenReturn(ToolSchema.from("file_read", "Read", Map.of("type", "object")));
    when(file.gate(anyString(), eq(home), any(), any()))
        .thenReturn(new ToolPre("{}", "Waiting", List.of()));
    when(file.waitingApproval()).thenReturn(Optional.of(approval));
    when(file.retainedArguments(anyString(), eq(home))).thenReturn("{}");
    when(sibling.schema())
        .thenReturn(ToolSchema.from("todo_read", "Read", Map.of("type", "object")));
    when(transcript.spokenIn()).thenReturn(new Transcript.Spoken("cnv_ABC123", 3));
    when(transcript.recordRetainedCallBatch(any())).thenReturn(8);
    when(dispatcher.stream(any(), any(), any()))
        .thenReturn(
            new Completion(
                null,
                "tool_calls",
                TokenUsage.UNKNOWN,
                List.of(
                    new ToolCall("id-read", "file_read", "{}"),
                    new ToolCall("id-unstarted", "todo_read", "{}"))));
    var operations = mock(OperationAuthority.class);
    var retained = retainedAdmission();
    when(operations.derive(eq(retained), any())).thenReturn(retained);
    var runtime = new JobRuntime(operations, dispatcher, List.of(file, sibling));
    runtime.useSuspendedRunCalls(calls);
    var delivery = mock(ApprovalDelivery.class);
    runtime.useApprovalDelivery(delivery);
    var retainedCall = new AtomicBoolean();
    var committed = new AtomicBoolean();
    doAnswer(
            ignored -> {
              assertTrue(committed.get());
              return null;
            })
        .when(delivery)
        .drain("cnv_ABC123");
    var goal =
        new GoalTurn(
            UUID.randomUUID(),
            "cnv_ABC123",
            retained,
            (id, ordinal, outcome, decision, failed, write) -> {
              assertEquals(Outcome.Ending.AWAITING, outcome.ending());
              assertTrue(retainedCall.get());
              write.run();
              committed.set(true);
            },
            List.of(),
            null,
            new GoalTurn.Dependencies(
                (id, turn, ordinal, call, write) -> {
                  assertEquals("apr_read", id);
                  assertEquals(3, turn);
                  assertEquals(8, ordinal);
                  assertEquals("id-read", call);
                  write.run();
                  retainedCall.set(true);
                },
                null,
                false,
                () -> {},
                committed::get));
    doAnswer(
            call -> {
              goal.commit(admittedJob.get(), 3, call.getArgument(1), () -> {});
              return null;
            })
        .when(transcript)
        .closed(anyString(), any());
    try (var jobs = new JobStore(operations, runtime)) {
      var turns = new Turn(jobs, conversations, history, compaction);
      var admitted = admission();
      var goalAdmission =
          new RunAdmission(
              admitted.check(),
              admitted.started(),
              admitted.ended(),
              admitted.subject(),
              admitted.authority(),
              goal);
      String id =
          turns
              .speakGoal(
                  "cnv_ABC123",
                  definition,
                  "Read notes",
                  budget.identity(),
                  budget,
                  goalAdmission,
                  () -> {})
              .orElseThrow();
      awaitCompletion(jobs, id);
      assertFalse(turns.isSpeaking("cnv_ABC123"));
      assertEquals(Outcome.Ending.AWAITING, jobs.get(id).outcome().orElseThrow().ending());
      verify(calls)
          .retainAt(
              eq("apr_read"),
              eq("cnv_ABC123"),
              isNull(),
              eq("local"),
              eq("alice"),
              eq(new ToolCall("id-read", "file_read", "{}")),
              eq(8));
      verify(calls, never()).claim("apr_read");
      verify(sibling, never()).run(anyString(), any());
      verify(dispatcher).stream(any(), any(), any());
      verify(delivery).drain("cnv_ABC123");
    }
  }

  @Test
  void only_the_dedicated_goal_root_dispatches_its_declared_orchestration_wait() throws Exception {
    definition =
        new AgentDefinition(
            "chat",
            "chat",
            "fast",
            Sampling.Intent.DEFAULT,
            definition.sampling(),
            List.of(),
            List.of(),
            List.of(),
            4,
            8,
            "Answer clearly",
            true,
            false,
            false,
            false,
            false,
            AgentDefinition.Fallback.NONE,
            List.of("review"));
    fixture();
    var row = conversations.find("cnv_ABC123").orElseThrow();
    when(row.origin()).thenReturn(Origin.MESSAGE);
    when(row.agent()).thenReturn(definition.name());
    assertThrows(CallerFault.class, () -> GoalTurn.requireAgent(definition));
    assertThrows(CallerFault.class, () -> RetainedAgent.require(definition));
    var graph = mock(AgentRegistry.class);
    when(graph.find(definition.name())).thenReturn(Optional.of(definition));
    var lookup = mock(AgentDelegates.class);
    when(lookup.find(home, null, "alice", definition.name())).thenReturn(Optional.of(definition));
    var wait = mock(GoalTurn.WaitTool.class);
    when(wait.schema())
        .thenReturn(
            ToolSchema.from("goal_orchestrate", "Start and wait", Map.of("type", "object")));
    var end = new AtomicReference<TurnEnd>();
    doAnswer(
            call -> {
              end.set(call.getArgument(0));
              return null;
            })
        .when(wait)
        .calledAt(any(), any(), eq(8), eq("id-orchestration"));
    when(wait.run(anyString(), eq(home)))
        .thenAnswer(
            call -> {
              assertTrue(end.get().request(Outcome.Ending.AWAITING, "Waiting for review."));
              return "The invocation is retained.";
            });
    when(transcript.recordRetainedCallBatch(any())).thenReturn(8);
    when(transcript.spokenIn()).thenReturn(new Transcript.Spoken("cnv_ABC123", 3));
    String arguments = "{\"name\":\"review\",\"request\":\"Check the notes.\"}";
    when(dispatcher.stream(any(), any(), any()))
        .thenAnswer(
            call -> {
              var request = call.<ChatRequest>getArgument(0);
              assertTrue(
                  request.tools().stream()
                      .anyMatch(schema -> schema.name().equals("goal_orchestrate")));
              return new Completion(
                  null,
                  "tool_calls",
                  TokenUsage.UNKNOWN,
                  List.of(new ToolCall("id-orchestration", "goal_orchestrate", arguments)));
            });
    var operations = mock(OperationAuthority.class);
    var retained = retainedAdmission();
    when(operations.derive(eq(retained), any())).thenReturn(retained);
    var inference = mock(InferenceCalls.class);
    when(inference.claimed(eq(retained), any())).thenReturn(CallAuthorization.DIRECT);
    var runtime =
        new JobRuntime(
            dispatcher,
            List.of(),
            () -> graph,
            null,
            Instant::now,
            Reminding.NONE,
            ImageStore.NONE,
            RefusalDetector.PHRASES,
            System::nanoTime,
            lookup,
            operations,
            inference);
    var goal =
        new GoalTurn(
            UUID.randomUUID(),
            "cnv_ABC123",
            retained,
            (id, ordinal, outcome, decision, failed, write) -> write.run(),
            List.of(wait),
            null,
            new GoalTurn.Dependencies(
                (id, turn, answer, call, write) -> fail("No approval is required."),
                null,
                false,
                () -> {},
                () -> false));
    try (var jobs = new JobStore(operations, runtime)) {
      var turns = new Turn(jobs, conversations, history, compaction);
      var admitted = admission();
      var goalAdmission =
          new RunAdmission(
              admitted.check(),
              admitted.started(),
              admitted.ended(),
              admitted.subject(),
              admitted.authority(),
              goal);
      String id =
          turns
              .speakGoal(
                  "cnv_ABC123",
                  definition,
                  "Review notes",
                  budget.identity(),
                  budget,
                  goalAdmission,
                  () -> {})
              .orElseThrow();
      awaitCompletion(jobs, id);
      assertEquals(Outcome.Ending.AWAITING, jobs.get(id).outcome().orElseThrow().ending());
      verify(lookup, atLeastOnce()).find(home, null, "alice", definition.name());
      verify(wait).run(arguments, home);
      verify(dispatcher).stream(any(), any(), any());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void goal_approval_reconciles_the_exact_slot_before_inference(boolean exhausted)
      throws Exception {
    definition = definition.withTools(List.of("file_read"));
    fixture();
    var row = conversations.find("cnv_ABC123").orElseThrow();
    when(row.origin()).thenReturn(Origin.MESSAGE);
    when(row.agent()).thenReturn(definition.name());
    var file = mock(ReviewedFileTool.class);
    when(file.schema()).thenReturn(ToolSchema.from("file_read", "Read", Map.of("type", "object")));
    var original = new ToolCall("reused-id", "file_read", "{}");
    var declaration = ChatMessage.assistant("", List.of(original));
    when(transcript.before())
        .thenReturn(
            List.of(
                ChatMessage.assistant("", List.of(original)),
                ChatMessage.tool(original.id(), "Earlier result."),
                declaration,
                ChatMessage.tool(original.id(), "Parked result placeholder.")));
    when(transcript.projectedAnswer(8)).thenReturn(declaration);
    var pending =
        new SuspendedRunCalls.Call(
            "apr_exact",
            "cnv_ABC123",
            null,
            "local",
            original,
            "waiting",
            RunApproval.DENIED,
            "alice",
            "Denied by owner.",
            null,
            "alice",
            true);
    var calls = mock(SuspendedRunCalls.class);
    when(calls.pending("cnv_ABC123")).thenReturn(List.of(pending));
    when(calls.find(pending.approval())).thenReturn(Optional.of(pending));
    var acknowledged = new AtomicBoolean();
    when(dispatcher.stream(any(), any(), any()))
        .thenAnswer(
            call -> {
              assertTrue(acknowledged.get());
              var request = call.<ChatRequest>getArgument(0);
              var toolResults =
                  request.messages().stream()
                      .filter(message -> message.role() == ChatMessage.Role.TOOL)
                      .map(ChatMessage::content)
                      .toList();
              assertEquals(2, toolResults.size());
              assertEquals("Earlier result.", toolResults.getFirst());
              assertTrue(toolResults.getLast().contains("Denied by owner."));
              return new Completion("Understood.", "stop", TokenUsage.UNKNOWN, List.of());
            });
    var operations = mock(OperationAuthority.class);
    var retained = retainedAdmission();
    when(operations.derive(eq(retained), any())).thenReturn(retained);
    var runtime = new JobRuntime(operations, dispatcher, List.of(file));
    runtime.useSuspendedRunCalls(calls);
    var goal =
        new GoalTurn(
            UUID.randomUUID(),
            "cnv_ABC123",
            retained,
            (id, ordinal, outcome, decision, failed, write) -> write.run(),
            List.of(),
            null,
            new GoalTurn.Dependencies(
                (id, turn, answer, call, write) -> fail("The original approval already exists."),
                new GoalTurn.ApprovalContinuation(pending.approval(), original.id(), 3, 8),
                false,
                () -> acknowledged.set(true),
                () -> false));
    var allowance = exhausted ? Budget.restored(budget.identity(), 0, 0) : budget;
    try (var jobs = new JobStore(operations, runtime)) {
      var admitted = admission();
      var goalAdmission =
          new RunAdmission(
              admitted.check(),
              admitted.started(),
              admitted.ended(),
              admitted.subject(),
              admitted.authority(),
              goal);
      String id =
          new Turn(jobs, conversations, history, compaction)
              .speakGoal(
                  "cnv_ABC123",
                  definition,
                  "Continue the goal",
                  allowance.identity(),
                  allowance,
                  goalAdmission,
                  () -> {})
              .orElseThrow();
      awaitCompletion(jobs, id);
      assertTrue(acknowledged.get());
      assertEquals(
          exhausted ? Outcome.Ending.CALL_BUDGET : Outcome.Ending.ANSWERED,
          jobs.get(id).outcome().orElseThrow().ending());
      verify(dispatcher, times(exhausted ? 0 : 1)).stream(any(), any(), any());
      verify(calls).acknowledged(pending.approval());
      verify(calls, never()).claim(anyString());
      verify(transcript)
          .recordRetainedResult(
              argThat(entry -> entry.content().contains("Denied by owner.")),
              eq(pending.approval()));
    }
  }

  private Admission retainedAdmission() {
    var account = new AccountId(UUID.randomUUID());
    return new Admission(
        UUID.randomUUID(),
        "alice",
        account,
        new ScopeId(UUID.randomUUID()),
        home,
        GrantRecipient.account(account),
        OperationExecution.actions(definition).stream()
            .map(action -> new Support(action, UUID.randomUUID()))
            .toList());
  }

  @Test
  void independent_consumers_refuse_instead_of_being_stripped() {
    var effectful =
        new AgentDefinition(
            "chat",
            "chat",
            "fast",
            List.of("information_write"),
            List.of(),
            List.of(),
            4,
            8,
            "Answer",
            true,
            false);
    assertThrows(CallerFault.class, () -> RetainedAgent.requireRoot(effectful));
    try (JobStore jobs = new JobStore(new JobRuntime(dispatcher, List.of()))) {
      assertThrows(
          CallerFault.class,
          () ->
              jobs.submitAdmitted(
                  effectful,
                  "Hello",
                  home,
                  budget,
                  transcript,
                  Origin.TURN,
                  outcome -> {},
                  TurnCap.of(4),
                  admission()));
    }
  }
}
