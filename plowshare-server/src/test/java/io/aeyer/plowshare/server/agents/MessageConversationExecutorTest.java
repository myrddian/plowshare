package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.TurnStore;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MessageConversationExecutorTest {
  @Test
  void definitions_share_history_without_sharing_an_execution_gate_or_allowance() {
    var conversations = mock(ConversationStore.class);
    var jobs = mock(JobStore.class);
    var compaction = mock(Compaction.class);
    var record = mock(ConversationRecord.class);
    when(record.id()).thenReturn("conversation");
    when(record.home()).thenReturn(Home.of("project"));
    when(record.origin()).thenReturn(Origin.MESSAGE);
    when(record.lifecycle()).thenReturn(ConversationLifecycle.ACTIVE);
    when(conversations.find("conversation")).thenReturn(Optional.of(record));
    var turn = new Turn(jobs, conversations, mock(TurnStore.class), compaction);
    var first =
        new AgentDefinition(
            "first", "first", "model", List.of(), List.of(), List.of(), 2, 3, "Help");
    var second =
        new AgentDefinition(
            "second", "second", "model", List.of(), List.of(), List.of(), 2, 3, "Help");
    var one = Budget.of(3);
    one.messageOwned();
    var two = Budget.of(3);
    two.messageOwned();
    var speaker = Speaker.harness();
    when(compaction.transcriptFor(eq("conversation"), any(), eq(speaker)))
        .thenReturn(mock(Compaction.TurnTranscript.class));
    turn.speakToMessage(
        "conversation", first, "one", one, TurnCap.of(2), speaker, outcome -> {}, false, "account");
    assertTrue(turn.isMessageSpeaking("conversation", "first"));
    assertFalse(turn.isMessageSpeaking("conversation", "second"));
    turn.speakToMessage(
        "conversation",
        second,
        "two",
        two,
        TurnCap.of(2),
        speaker,
        outcome -> {},
        false,
        "account");
    assertTrue(turn.isMessageSpeaking("conversation", "second"));
    assertThrows(
        Turn.Refused.class,
        () ->
            turn.speakToMessage(
                "conversation",
                first,
                "duplicate",
                one,
                TurnCap.of(2),
                speaker,
                outcome -> {},
                false,
                "account"));
    assertEquals(0, one.spent());
    assertEquals(0, two.spent());
  }

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
    when(record.origin()).thenReturn(Origin.MESSAGE);
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
            eq(Origin.MESSAGE),
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
            eq(Origin.MESSAGE),
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
