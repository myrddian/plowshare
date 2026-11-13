package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.server.archive.CompactionStore;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.TurnJournal;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CausalMessageTurnTest {
  private final JobStore jobs = mock(JobStore.class);
  private final ConversationStore conversations = mock(ConversationStore.class);
  private final Compaction compaction = mock(Compaction.class);
  private final Turn turn = new Turn(jobs, conversations, mock(TurnStore.class), compaction);
  private final Speaker speaker = Speaker.message("bdm_Original");

  private void conversation() {
    var record = mock(ConversationRecord.class);
    when(record.id()).thenReturn("cnv_Shared");
    when(record.home()).thenReturn(Home.of("project"));
    when(record.origin()).thenReturn(Origin.TURN);
    when(record.lifecycle()).thenReturn(ConversationLifecycle.ACTIVE);
    when(conversations.find("cnv_Shared")).thenReturn(Optional.of(record));
  }

  private static AgentDefinition definition(String name) {
    return new AgentDefinition(name, name, "model", List.of(), List.of(), List.of(), 2, 3, "Help");
  }

  private static Budget lease() {
    var lease = Budget.of(3);
    lease.messageOwned();
    return lease;
  }

  @Test
  void each_definition_submits_its_exact_reserved_cause_in_shared_history() {
    conversation();
    for (var name : List.of("first", "second")) {
      var definition = definition(name);
      var cause = new RelayCausation(name + "-root", name + "-source", 2);
      var transcript = mock(Compaction.TurnTranscript.class);
      when(compaction.transcriptFor("cnv_Shared", definition, speaker, cause))
          .thenReturn(transcript);
      when(jobs.submit(
              eq(definition),
              eq("Work"),
              eq(Home.of("project")),
              isNull(),
              any(),
              eq(transcript),
              eq(Origin.TURN),
              any(),
              any(),
              anyList(),
              eq("account"),
              eq(false),
              eq(cause)))
          .thenReturn("job_" + name);
      assertEquals(
          "job_" + name,
          turn.speakToMessage(
              "cnv_Shared",
              definition,
              "Work",
              lease(),
              TurnCap.of(2),
              speaker,
              outcome -> {},
              false,
              "account",
              cause));
      var order = inOrder(transcript, jobs);
      order.verify(transcript).reserveCausalSource();
      order
          .verify(jobs)
          .submit(
              eq(definition),
              eq("Work"),
              eq(Home.of("project")),
              isNull(),
              any(),
              eq(transcript),
              eq(Origin.TURN),
              any(),
              any(),
              anyList(),
              eq("account"),
              eq(false),
              eq(cause));
    }
  }

  @Test
  void source_reservation_failure_starts_no_job_and_releases_the_run_seat() {
    conversation();
    var definition = definition("first");
    var cause = new RelayCausation("root", "source", 1);
    var transcript = mock(Compaction.TurnTranscript.class);
    when(compaction.transcriptFor("cnv_Shared", definition, speaker, cause)).thenReturn(transcript);
    doThrow(new IllegalStateException("storage unavailable"))
        .when(transcript)
        .reserveCausalSource();
    assertThrows(
        IllegalStateException.class,
        () ->
            turn.speakToMessage(
                "cnv_Shared",
                definition,
                "Work",
                lease(),
                TurnCap.of(2),
                speaker,
                outcome -> {},
                false,
                "account",
                cause));
    verifyNoInteractions(jobs);
    assertFalse(turn.isMessageSpeaking("cnv_Shared", "first"));
  }

  @Test
  void delegated_and_attributed_transcripts_keep_the_executing_turns_source() {
    conversation();
    var cause = new RelayCausation("current-root", "current-message", 3);
    var childRecord = mock(ConversationRecord.class);
    when(childRecord.id()).thenReturn("cnv_Child");
    var callee = definition("child");
    when(conversations.logCausalDelegate(
            Home.of("project"), "child", "cnv_Shared", null, "call", cause))
        .thenReturn(childRecord);
    var reservations = mock(TurnJournal.class);
    when(reservations.reserve("cnv_Child", cause)).thenReturn(1);
    var actual =
        new Compaction(
            mock(LlmDispatcher.class),
            () -> definition("folder"),
            reservations,
            mock(CompactionStore.class),
            mock(EntryStore.class),
            100_000,
            Runnable::run,
            conversations,
            null,
            null);
    try {
      var parent = actual.transcriptFor("cnv_Shared", definition("first"), speaker, cause);
      var attributed =
          new AttributedTranscript(parent, UsageAttribution.LEGACY, UsageAttribution.LEGACY);
      Transcript child = attributed.delegate(callee, Home.of("project"), "call");
      assertEquals(cause, child.causation());
      child.reserveCausalSource();
      verify(reservations).reserve("cnv_Child", cause);
      verify(conversations)
          .logCausalDelegate(Home.of("project"), "child", "cnv_Shared", null, "call", cause);
    } finally {
      actual.close();
    }
  }

  @Test
  void causal_child_creation_cannot_fall_back_to_an_unbound_transcript() {
    conversation();
    var cause = new RelayCausation("root", "request", 1);
    when(conversations.logCausalDelegate(
            any(), anyString(), anyString(), isNull(), anyString(), eq(cause)))
        .thenThrow(new IllegalStateException("storage failure"));
    var actual =
        new Compaction(
            mock(LlmDispatcher.class),
            () -> definition("folder"),
            mock(TurnJournal.class),
            mock(CompactionStore.class),
            mock(EntryStore.class),
            100_000,
            Runnable::run,
            conversations,
            null,
            null);
    try {
      var parent = actual.transcriptFor("cnv_Shared", definition("first"), speaker, cause);
      assertThrows(
          IllegalStateException.class,
          () -> parent.delegate(definition("child"), Home.of("project"), "call"));
    } finally {
      actual.close();
    }
  }
}
