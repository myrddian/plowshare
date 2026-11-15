package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.archive.CompactionStore;
import io.aeyer.plowshare.server.archive.EntryRecord;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.TurnJournal;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompactionBudgetResumeTest {
  private static EntryRecord entry(
      int ordinal, int turn, EntryKind kind, String text, String callId, List<ToolCall> calls) {
    return new EntryRecord(
        "cnv_original",
        ordinal,
        kind,
        text,
        callId,
        calls,
        null,
        null,
        turn,
        Instant.EPOCH,
        null,
        null,
        null);
  }

  @Test
  void resumed_work_retains_original_task_and_results_across_all_budget_segments()
      throws Exception {
    AgentRegistry agents =
        AgentRegistry.of(
            Path.of(getClass().getResource("/agents").toURI()),
            Set.of("probe_read", "probe_write", "agent_run", MemoryTools.WRITE_NAME));
    TurnJournal turns = mock(TurnJournal.class);
    EntryStore entries = mock(EntryStore.class);
    when(turns.reserve("cnv_original")).thenReturn(3);
    when(entries.thatProjectFor("cnv_original"))
        .thenReturn(
            List.of(
                entry(1, 1, EntryKind.UTTERANCE, "The original task", null, List.of()),
                entry(
                    2,
                    1,
                    EntryKind.ANSWER,
                    "",
                    null,
                    List.of(new ToolCall("read_1", "probe_read", "{}"))),
                entry(
                    3,
                    1,
                    EntryKind.TOOL_RESULT,
                    "Evidence retained before exhaustion",
                    "read_1",
                    List.of()),
                entry(4, 2, EntryKind.NOTICE, "Budget approved", null, List.of()),
                entry(
                    5,
                    2,
                    EntryKind.ANSWER,
                    "",
                    null,
                    List.of(new ToolCall("read_2", "probe_read", "{}"))),
                entry(
                    6,
                    2,
                    EntryKind.TOOL_RESULT,
                    "Evidence retained in the second segment",
                    "read_2",
                    List.of())));
    Compaction compaction =
        new Compaction(
            mock(LlmDispatcher.class),
            () -> agents.get("helper"),
            turns,
            mock(CompactionStore.class),
            entries,
            100000);
    var transcript =
        compaction.transcriptFor(
            "cnv_original", agents.get("helper"), Speaker.approval("apr_original"));
    transcript.continueBudget(1);
    List<ChatMessage> history = transcript.before();
    assertEquals(1, transcript.continuingTurn());
    assertEquals(
        1,
        history.stream().filter(message -> message.content().equals("The original task")).count());
    assertTrue(
        history.stream()
            .anyMatch(
                message -> message.content().contains("Evidence retained before exhaustion")));
    assertTrue(
        history.stream()
            .anyMatch(
                message -> message.content().contains("Evidence retained in the second segment")));
    when(entries.thatProjectFor("cnv_original"))
        .thenThrow(new IllegalStateException("History unavailable"));
    assertThrows(IllegalStateException.class, transcript::before);
  }
}
