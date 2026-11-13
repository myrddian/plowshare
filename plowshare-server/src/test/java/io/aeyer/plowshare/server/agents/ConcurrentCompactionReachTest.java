package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.archive.CompactionStore;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.TurnJournal;
import io.aeyer.plowshare.server.archive.TurnRecord;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** A later ending cannot summarize through an earlier reservation still producing entries. */
class ConcurrentCompactionReachTest {
  @Test
  void completed_later_turn_waits_before_any_summary_or_compaction_write() throws Exception {
    var models = mock(LlmDispatcher.class);
    var turns = mock(TurnJournal.class);
    var records = mock(CompactionStore.class);
    var entries = mock(EntryStore.class);
    var definition =
        new AgentDefinition(
            "worker", "worker", "model", List.of(), List.of(), List.of(), 1, 2, "Work");
    when(models.contextLength(any(), anyInt())).thenReturn(OptionalInt.of(100_000));
    when(models.compactionThreshold(anyString())).thenReturn(OptionalInt.of(60_000));
    when(models.compactionNowThreshold(anyString())).thenReturn(OptionalInt.of(80_000));
    // Turn one has reserved and written partial entries, but only turns two/three have ended.
    when(turns.forConversation("conversation")).thenReturn(List.of(turn(2), turn(3)));
    var compaction =
        new Compaction(models, () -> definition, turns, records, entries, 100_000, Runnable::run);
    try {
      var transcript = compaction.transcriptFor("conversation", definition, Speaker.person(null));
      transcript.promptMeasured(90_000);
      transcript.foldWhenTheTurnIsOver();
      verifyNoInteractions(records);
      verify(models, never()).streamFold(any(), any());
      verify(entries, never()).supersede(anyString(), anyInt(), anyInt(), anyInt());
    } finally {
      compaction.close();
    }
  }

  @Test
  void in_turn_fold_does_not_cover_an_earlier_reservation_still_writing() {
    var models = mock(LlmDispatcher.class);
    var turns = mock(TurnJournal.class);
    var records = mock(CompactionStore.class);
    var entries = mock(EntryStore.class);
    var definition =
        new AgentDefinition(
            "worker", "worker", "model", List.of(), List.of(), List.of(), 1, 2, "Work");
    when(models.contextLength(any(), anyInt())).thenReturn(OptionalInt.of(100_000));
    when(models.compactionThreshold(anyString())).thenReturn(OptionalInt.of(60_000));
    when(models.compactionNowThreshold(anyString())).thenReturn(OptionalInt.of(80_000));
    when(turns.reserve("conversation")).thenReturn(2);
    when(turns.forConversation("conversation")).thenReturn(List.of());
    var history =
        new ArrayList<>(
            List.of(ChatMessage.system("Instructions"), ChatMessage.user("Current request")));
    var original = List.copyOf(history);
    var compaction =
        new Compaction(models, () -> definition, turns, records, entries, 100_000, Runnable::run);
    try {
      var transcript = compaction.transcriptFor("conversation", definition, Speaker.person(null));
      transcript.promptMeasured(90_000);
      assertEquals(1, transcript.stepEnded(history, 1));
      assertEquals(original, history);
      verify(models, never()).streamFold(any(), any());
      verify(entries, never())
          .foldWithinATurn(anyString(), anyInt(), anyInt(), anyInt(), anyInt(), any());
    } finally {
      compaction.close();
    }
  }

  @Test
  void background_fold_cannot_overlap_an_in_turn_fold_in_the_same_conversation() throws Exception {
    var models = mock(LlmDispatcher.class);
    var turns = mock(TurnJournal.class);
    var records = mock(CompactionStore.class);
    var entries = mock(EntryStore.class);
    var definition =
        new AgentDefinition(
            "worker", "worker", "model", List.of(), List.of(), List.of(), 1, 2, "Work");
    when(models.contextLength(any(), anyInt())).thenReturn(OptionalInt.of(100_000));
    when(models.compactionThreshold(anyString())).thenReturn(OptionalInt.of(60_000));
    when(models.compactionNowThreshold(anyString())).thenReturn(OptionalInt.of(80_000));
    when(turns.reserve("conversation")).thenReturn(2);
    when(turns.forConversation("conversation")).thenReturn(List.of(turn(1)));
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            call -> {
              entered.countDown();
              if (!release.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Unreleased fixture");
              return 0;
            })
        .when(entries)
        .foldedThrough("conversation");
    var compaction =
        new Compaction(models, () -> definition, turns, records, entries, 100_000, Runnable::run);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var running = compaction.transcriptFor("conversation", definition, Speaker.person(null));
      running.promptMeasured(90_000);
      var work =
          executor.submit(
              () -> running.stepEnded(new ArrayList<>(List.of(ChatMessage.user("Request"))), 0));
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var ended = compaction.transcriptFor("conversation", definition, Speaker.person(null));
        ended.promptMeasured(90_000);
        ended.foldWhenTheTurnIsOver();
        // A competing background fold must not even take a snapshot while the in-turn fold owns it.
        verify(turns, never()).forConversation("conversation");
        verifyNoInteractions(records);
      } finally {
        release.countDown();
      }
      work.get(5, TimeUnit.SECONDS);
    } finally {
      compaction.close();
    }
  }

  @Test
  void an_earlier_writer_finishing_releases_the_exact_prefix_without_covering_the_latest_turn() {
    assertEquals(1, Compaction.settledFoldThrough(List.of(turn(1), turn(3), turn(4)), 3));
    assertEquals(3, Compaction.settledFoldThrough(List.of(turn(1), turn(2), turn(3), turn(4)), 3));
    assertEquals(0, Compaction.settledFoldThrough(List.of(turn(2), turn(3)), 2));
    assertEquals(0, Compaction.settledFoldThrough(List.of(), 4));
  }

  private static TurnRecord turn(int ordinal) {
    return new TurnRecord(
        "conversation",
        ordinal,
        "request",
        "answer",
        Outcome.Ending.ANSWERED,
        90_000,
        "worker",
        null);
  }
}
