package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.CompactionStore;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.accounting.UsageLineage;
import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SkillContextsTest {
  @Test
  void new_has_no_parent_context_reads_and_inherited_copies_complete_log_data() {
    EntryStore entries = mock(EntryStore.class);
    Compaction compaction = mock(Compaction.class);
    SkillExecutions executions = mock(SkillExecutions.class);
    Transcript parent = mock(Transcript.class);
    when(parent.conversationId()).thenReturn("parent");
    var contexts = new SkillContexts(entries, compaction, executions);
    Budget budget = Budget.of(8);
    assertNull(contexts.prepare(SkillDefinition.Mode.NEW, parent, "alice", budget, () -> false));
    verifyNoInteractions(entries, compaction);
    String log =
        "[{\"conversation_id\":\"parent\",\"turn_ordinal\":1,\"ordinal\":1,\"kind\":\"utterance\",\"content\":\"Earlier context\",\"speaker\":\"person\"},"
            + "{\"conversation_id\":\"parent\",\"turn_ordinal\":1,\"ordinal\":2,\"kind\":\"answer\",\"tool_calls\":[{\"id\":\"call\",\"name\":\"run\",\"arguments\":\"{}\"}],\"content\":\"\"}]";
    when(entries.forAccount("alice")).thenReturn(entries);
    when(entries.snapshotForSkill("parent")).thenReturn(SkillContextLogs.read(log));
    var inherited =
        contexts.prepare(SkillDefinition.Mode.INHERITED, parent, "alice", budget, () -> false);
    assertEquals(SkillContextLogs.read(log), inherited.snapshot());
    assertTrue(inherited.prompt().contains(SkillContextLogs.write(SkillContextLogs.read(log))));
    assertTrue(inherited.prompt().contains("never replayed"));
    assertEquals(2, inherited.through());
    contexts.pin("alice", UUID.fromString("00000000-0000-0000-0000-000000000001"), inherited);
    verify(executions)
        .context(eq("alice"), any(), eq(SkillContextLogs.read(log)), eq(inherited.prompt()), eq(2));
    verifyNoInteractions(compaction);
    assertEquals(0, budget.spent());
  }

  @Test
  void missing_payloads_do_not_silently_change_the_context_mode() {
    EntryStore entries = mock(EntryStore.class);
    when(entries.forAccount("alice")).thenReturn(entries);
    when(entries.snapshotForSkill("parent"))
        .thenReturn(
            SkillContextLogs.read(
                "[{\"conversation_id\":\"parent\",\"turn_ordinal\":1,\"ordinal\":1,\"kind\":\"tool_result\",\"content\":null,\"ejected_at\":\"2026-10-04T00:00:00Z\"}]"));
    Transcript parent = mock(Transcript.class);
    when(parent.conversationId()).thenReturn("parent");
    Compaction compaction = mock(Compaction.class);
    var contexts = new SkillContexts(entries, compaction, mock(SkillExecutions.class));
    assertThrows(
        IllegalStateException.class,
        () ->
            contexts.prepare(
                SkillDefinition.Mode.INHERITED, parent, "alice", Budget.of(8), () -> false));
    verifyNoInteractions(compaction);
  }

  @Test
  void summarisation_is_a_cancel_aware_accounted_fold_on_the_shared_budget() {
    LlmDispatcher models = mock(LlmDispatcher.class);
    var folder =
        new AgentDefinition(
            "conversation_folder",
            "Folder",
            "model",
            List.of(),
            List.of(),
            List.of(),
            1,
            1,
            "Folder instructions");
    var compaction =
        new Compaction(
            models,
            () -> folder,
            mock(TurnStore.class),
            mock(CompactionStore.class),
            mock(EntryStore.class),
            10000);
    Transcript parent = mock(Transcript.class);
    var owner =
        UsageAttribution.global("alice", UsageAttribution.Operation.AGENT_CHAT)
            .withExecution(
                UsageLineage.root("parent"),
                UsageLineage.root("parent-run"),
                UsageLineage.NONE,
                "worker",
                1L,
                1L);
    when(parent.usage()).thenReturn(owner);
    java.util.function.BooleanSupplier cancel = () -> false;
    when(models.streamFold(any(), eq(Deltas.DISCARDING), same(cancel)))
        .thenReturn(new Completion("Parent summary", "stop", TokenUsage.UNKNOWN, List.of()));
    Budget budget = Budget.of(8);
    assertEquals(
        "Parent summary",
        compaction.summaryForSkill("Historical log data", parent, budget, cancel));
    assertEquals(1, budget.spent());
    var request = ArgumentCaptor.forClass(ChatRequest.class);
    verify(models).streamFold(request.capture(), eq(Deltas.DISCARDING), same(cancel));
    assertEquals(
        owner.forOperation(UsageAttribution.Operation.FOLD, folder.name()),
        request.getValue().attribution());
    assertTrue(
        request
            .getValue()
            .messages()
            .getLast()
            .content()
            .toString()
            .contains("Historical log data"));
    assertThrows(
        IllegalStateException.class,
        () -> compaction.summaryForSkill("data", parent, budget, () -> true));
    assertEquals(1, budget.spent());
    verifyNoMoreInteractions(models);
  }
}
