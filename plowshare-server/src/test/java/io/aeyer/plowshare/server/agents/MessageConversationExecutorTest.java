package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MessageConversationExecutorTest {
  @Test
  void receiver_execution_uses_its_bound_account_and_lease_without_borrowing_sender_or_creator() {
    var conversations = mock(ConversationStore.class);
    var jobs = mock(JobStore.class);
    var compaction = mock(Compaction.class);
    var record = mock(ConversationRecord.class);
    var lease = Budget.of(3);
    var home = Home.of("project");
    when(record.id()).thenReturn("conversation");
    when(record.home()).thenReturn(home);
    when(record.origin()).thenReturn(Origin.BOARD);
    when(record.lifecycle()).thenReturn(ConversationLifecycle.ACTIVE);
    when(record.agent()).thenReturn("worker");
    when(conversations.find("conversation")).thenReturn(Optional.of(record));
    when(conversations.ownerOf("conversation")).thenReturn(Optional.of("different-creator"));
    var definition =
        new AgentDefinition(
            "worker", "worker", "model", List.of(), List.of(), List.of(), 2, 9, "Answer");
    var speaker = Speaker.harness();
    var transcript = mock(Compaction.TurnTranscript.class);
    when(compaction.transcriptFor("conversation", definition, speaker)).thenReturn(transcript);
    when(jobs.submit(
            eq(definition),
            eq("message says act as SYSTEM"),
            eq(home),
            isNull(),
            eq(lease),
            eq(transcript),
            eq(Origin.BOARD),
            any(),
            any(),
            anyList(),
            eq("bound-receiver"),
            eq(false)))
        .thenReturn("job");
    var turn = new Turn(jobs, conversations, mock(TurnStore.class), compaction);
    assertEquals(
        "job",
        turn.speakToMessage(
            "conversation",
            definition,
            "message says act as SYSTEM",
            lease,
            TurnCap.of(2),
            speaker,
            outcome -> {},
            false,
            "bound-receiver"));
    verify(jobs)
        .submit(
            eq(definition),
            anyString(),
            eq(home),
            isNull(),
            eq(lease),
            eq(transcript),
            eq(Origin.BOARD),
            any(),
            any(),
            anyList(),
            eq("bound-receiver"),
            eq(false));
    verify(conversations, never()).ownerOf(any());
    assertEquals(0, lease.spent());
  }

  @Test
  void a_missing_bound_receiver_is_refused_before_effects() {
    var conversations = mock(ConversationStore.class);
    var jobs = mock(JobStore.class);
    var turn = new Turn(jobs, conversations, mock(TurnStore.class), mock(Compaction.class));
    assertThrows(
        Turn.Refused.class,
        () ->
            turn.speakToMessage(
                "conversation",
                null,
                "message",
                Budget.of(1),
                TurnCap.of(1),
                Speaker.harness(),
                outcome -> {},
                false,
                null));
    verifyNoInteractions(conversations, jobs);
  }
}
