package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.accounting.UsageLineage;
import java.util.List;
import org.junit.jupiter.api.Test;

class BudgetExecutionStackTest {
  @Test
  void a_legacy_transcript_keeps_its_retained_continuation_turn() {
    Transcript transcript = mock(Transcript.class);
    when(transcript.spokenIn()).thenReturn(new Transcript.Spoken("child", 3));
    when(transcript.continuingTurn()).thenReturn(2);
    when(transcript.usage()).thenReturn(UsageAttribution.LEGACY);
    AgentDefinition agent = mock(AgentDefinition.class);
    when(agent.name()).thenReturn("reviewer");
    BudgetExecutionStack stack = new BudgetExecutionStack();
    try (var running = stack.enter(agent, transcript, TurnCap.of(20))) {
      running.progress(7, List.of());
      BudgetCheckpoint.Frame frame = stack.snapshot().getFirst();
      assertEquals(2, frame.turn());
      assertEquals(7, frame.steps());
    }
  }

  @Test
  void another_conversations_attribution_cannot_choose_the_recovery_turn() {
    Transcript transcript = mock(Transcript.class);
    when(transcript.spokenIn()).thenReturn(new Transcript.Spoken("child", 3));
    when(transcript.usage())
        .thenReturn(
            UsageAttribution.global("owner", UsageAttribution.Operation.AGENT_CHAT)
                .withExecution(
                    UsageLineage.root("another"),
                    UsageLineage.NONE,
                    UsageLineage.NONE,
                    "reviewer",
                    1L,
                    null));
    AgentDefinition agent = mock(AgentDefinition.class);
    when(agent.name()).thenReturn("reviewer");
    BudgetExecutionStack stack = new BudgetExecutionStack();
    try (var running = stack.enter(agent, transcript, TurnCap.of(20))) {
      assertThrows(IllegalStateException.class, running::snapshot);
    }
  }
}
