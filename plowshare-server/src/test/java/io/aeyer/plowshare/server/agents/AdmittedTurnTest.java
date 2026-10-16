package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

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
      assertTrue(ended.await(5, TimeUnit.SECONDS));
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
      assertTrue(ended.await(5, TimeUnit.SECONDS));
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
      assertTrue(ended.await(5, TimeUnit.SECONDS));
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
      assertTrue(ended.await(5, TimeUnit.SECONDS));
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
      assertTrue(ended.await(5, TimeUnit.SECONDS));
      verifyNoInteractions(extras);
      verify(hooks, atLeastOnce()).promptPre(any(), anyString());
      verify(tool, never()).run(anyString(), any());
      assertTrue(jobs.get(id).outcome().isPresent());
    }
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
