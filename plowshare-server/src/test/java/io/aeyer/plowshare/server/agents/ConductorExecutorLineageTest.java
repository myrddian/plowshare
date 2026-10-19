package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConductorExecutorLineageTest {
  @Test
  void a_conductor_uses_retained_executor_and_existing_allowance_instead_of_log_custody() {
    var conversations = mock(ConversationStore.class);
    var jobs = mock(JobStore.class);
    var compaction = mock(Compaction.class);
    var record = mock(ConversationRecord.class);
    var budget = Budget.of(3);
    var home = Home.of("project");
    when(record.id()).thenReturn("conversation");
    when(record.home()).thenReturn(home);
    when(record.budget()).thenReturn(budget);
    when(record.origin()).thenReturn(Origin.ORCHESTRATION);
    when(record.lifecycle()).thenReturn(ConversationLifecycle.ACTIVE);
    when(record.agent()).thenReturn("conductor");
    when(conversations.find("conversation")).thenReturn(Optional.of(record));
    when(conversations.ownerOf("conversation")).thenReturn(Optional.of("different-creator"));
    when(jobs.retainedExecutor("conversation")).thenReturn("retained-executor");
    var definition =
        new AgentDefinition(
            "conductor", "conductor", "model", List.of(), List.of(), List.of(), 2, 9, "Answer");
    var speaker = Speaker.harness();
    var transcript = mock(Compaction.TurnTranscript.class);
    when(compaction.transcriptFor("conversation", definition, speaker)).thenReturn(transcript);
    var turn = new Turn(jobs, conversations, mock(TurnStore.class), compaction);
    turn.speakToConductor(
        "conversation", definition, "continue", null, null, outcome -> {}, speaker);
    verify(jobs)
        .submit(
            eq(definition),
            eq("continue"),
            eq(home),
            isNull(),
            same(budget),
            same(transcript),
            eq(Origin.ORCHESTRATION),
            any(),
            any(),
            anyList(),
            eq("retained-executor"),
            eq(false));
    verify(conversations, never()).ownerOf(any());
    assertEquals(0, budget.spent());
    assertEquals(3, budget.limit());
  }

  @Test
  void
      missing_lineage_or_changed_delegate_executor_refuses_before_budget_or_conversation_effects() {
    var conversations = mock(ConversationStore.class);
    var jobs = mock(JobStore.class);
    var compaction = mock(Compaction.class);
    var turn = new Turn(jobs, conversations, mock(TurnStore.class), compaction);
    when(jobs.retainedExecutor("legacy")).thenThrow(new CallerFault("Missing lineage"));
    assertThrows(
        CallerFault.class, () -> turn.speakToConductor("legacy", null, "continue", outcome -> {}));
    when(jobs.retainedExecutor("child")).thenReturn("original");
    assertThrows(
        Turn.Refused.class,
        () ->
            turn.speakToDelegate(
                "child", "conductor", null, "continue", "replacement", outcome -> {}));
    verifyNoInteractions(conversations, compaction);
    verify(jobs, never())
        .submit(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            anyList(),
            any(),
            anyBoolean());
  }

  @Test
  void a_native_delegate_can_continue_with_the_original_zero_call_allowance_without_inference() {
    var conversations = mock(ConversationStore.class);
    var jobs = mock(JobStore.class);
    var compaction = mock(Compaction.class);
    var delegate = mock(ConversationRecord.class);
    var conductor = mock(ConversationRecord.class);
    var callee = mock(AgentDefinition.class);
    var budget = Budget.noCalls();
    var home = Home.of("project");
    when(callee.nativeOnly()).thenReturn(true);
    when(callee.name()).thenReturn("native");
    when(callee.maxTurns()).thenReturn(3);
    when(delegate.origin()).thenReturn(Origin.DELEGATION);
    when(delegate.parentId()).thenReturn("conductor");
    when(delegate.agent()).thenReturn("native");
    when(delegate.home()).thenReturn(home);
    when(conductor.id()).thenReturn("conductor");
    when(conductor.budget()).thenReturn(budget);
    when(conductor.lifecycle()).thenReturn(ConversationLifecycle.ACTIVE);
    when(conversations.find("child")).thenReturn(Optional.of(delegate));
    when(conversations.find("conductor")).thenReturn(Optional.of(conductor));
    when(jobs.retainedExecutor("child")).thenReturn("executor");
    var transcript = mock(Compaction.TurnTranscript.class);
    when(compaction.transcriptFor(eq("child"), eq(callee), any())).thenReturn(transcript);
    var turn = new Turn(jobs, conversations, mock(TurnStore.class), compaction);
    assertDoesNotThrow(
        () ->
            turn.speakToDelegate(
                "child", "conductor", callee, "continue", "executor", outcome -> {}));
    verify(jobs)
        .submit(
            same(callee),
            eq("continue"),
            eq(home),
            isNull(),
            same(budget),
            same(transcript),
            eq(Origin.DELEGATION),
            any(),
            any(),
            anyList(),
            eq("executor"),
            eq(false));
    assertEquals(0, budget.limit());
    assertEquals(0, budget.spent());
  }
}
