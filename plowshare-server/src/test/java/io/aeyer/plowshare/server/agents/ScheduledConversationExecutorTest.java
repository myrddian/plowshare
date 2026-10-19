package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** The event speaker, conversation custodian and admitted executor remain distinct. */
class ScheduledConversationExecutorTest {
  @Test
  void a_targeted_event_uses_its_executor_and_the_existing_conversation_allowance() {
    var conversations = mock(ConversationStore.class);
    var jobs = mock(JobStore.class);
    var compaction = mock(Compaction.class);
    var record = mock(ConversationRecord.class);
    var budget = Budget.of(3);
    var home = Home.of("project");
    when(record.id()).thenReturn("conversation");
    when(record.home()).thenReturn(home);
    when(record.origin()).thenReturn(Origin.TURN);
    when(record.lifecycle()).thenReturn(ConversationLifecycle.ACTIVE);
    when(record.budget()).thenReturn(budget);
    when(conversations.find("conversation")).thenReturn(Optional.of(record));
    when(conversations.ownerOf("conversation")).thenReturn(Optional.of("different-creator"));
    var definition =
        new AgentDefinition(
            "worker", "worker", "model", List.of(), List.of(), List.of(), 2, 9, "Answer");
    var transcript = mock(Compaction.TurnTranscript.class);
    var speaker = Speaker.event("daily");
    when(compaction.transcriptFor("conversation", definition, speaker)).thenReturn(transcript);
    when(jobs.submit(
            eq(definition),
            eq("task"),
            eq(home),
            isNull(),
            eq(budget),
            eq(transcript),
            eq(Origin.TURN),
            any(),
            any(),
            anyList(),
            eq("service-executor"),
            eq(true)))
        .thenReturn("job");
    var turns = new Turn(jobs, conversations, mock(TurnStore.class), compaction);
    assertEquals(
        "job",
        turns.speakForExecutor(
            "conversation",
            definition,
            "task",
            null,
            null,
            speaker,
            outcome -> {},
            "service-executor"));
    verify(jobs)
        .submit(
            eq(definition),
            eq("task"),
            eq(home),
            isNull(),
            eq(budget),
            eq(transcript),
            eq(Origin.TURN),
            any(),
            any(),
            anyList(),
            eq("service-executor"),
            eq(true));
    verify(conversations, never()).ownerOf(any());
    assertEquals(0, budget.spent());
  }

  @Test
  void missing_event_executor_is_refused_before_conversation_or_job_access() {
    var jobs = mock(JobStore.class);
    var conversations = mock(ConversationStore.class);
    var turns = new Turn(jobs, conversations, mock(TurnStore.class), mock(Compaction.class));
    assertThrows(
        Turn.Refused.class,
        () ->
            turns.speakForExecutor(
                "conversation",
                null,
                "task",
                null,
                null,
                Speaker.event("daily"),
                outcome -> {},
                " "));
    verifyNoInteractions(jobs, conversations);
  }
}
