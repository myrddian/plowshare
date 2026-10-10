package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Continuation admission and session propagation require no PostgreSQL or model calls. */
class TurnDelegateSessionTest {
  @Test
  void delegate_inherits_the_validated_session_and_keeps_parent_budget_and_lifecycle() {
    var jobs = mock(JobStore.class);
    var conversations = mock(ConversationStore.class);
    var compaction = mock(Compaction.class);
    var turn = new Turn(jobs, conversations, mock(TurnStore.class), compaction);
    var parent = mock(ConversationRecord.class);
    var child = mock(ConversationRecord.class);
    var budget = Budget.of(8);
    when(parent.id()).thenReturn("parent");
    when(parent.lifecycle()).thenReturn(ConversationLifecycle.ACTIVE);
    when(parent.budget()).thenReturn(budget);
    when(child.origin()).thenReturn(Origin.DELEGATION);
    when(child.parentId()).thenReturn("parent");
    when(child.agent()).thenReturn("helper");
    when(child.home()).thenReturn(Home.of("application"));
    when(conversations.find("child")).thenReturn(Optional.of(child));
    when(conversations.find("parent")).thenReturn(Optional.of(parent));
    var agent =
        new AgentDefinition(
            "helper", "fixture", "m", List.of(), List.of(), List.of(), 2, 4, "helper", false, true);
    var trace = mock(Compaction.TurnTranscript.class);
    when(compaction.transcriptFor(eq("child"), eq(agent), any())).thenReturn(trace);
    when(jobs.submit(
            eq(agent),
            eq("continue"),
            eq(Home.of("application")),
            eq("session"),
            same(budget),
            same(trace),
            eq(Origin.DELEGATION),
            any(),
            any(),
            eq(List.of()),
            eq("service"),
            eq(false)))
        .thenReturn("job");
    var ended = new java.util.concurrent.atomic.AtomicBoolean();
    assertEquals(
        "job",
        turn.speakToDelegate(
            "child", "parent", agent, "continue", "service", "session", result -> ended.set(true)));
    assertTrue(turn.isSpeaking("child"));
    assertTrue(turn.isSpeaking("parent"));
    ArgumentCaptor<Consumer<Outcome>> callback = ArgumentCaptor.captor();
    verify(jobs)
        .submit(
            eq(agent),
            eq("continue"),
            eq(Home.of("application")),
            eq("session"),
            same(budget),
            same(trace),
            eq(Origin.DELEGATION),
            callback.capture(),
            any(),
            eq(List.of()),
            eq("service"),
            eq(false));
    callback.getValue().accept(new Outcome(Outcome.Ending.ANSWERED, "answer", 1, 1, ""));
    verify(conversations).turnEnded("parent", budget);
    assertTrue(ended.get());
    assertFalse(turn.isSpeaking("child"));
    assertFalse(turn.isSpeaking("parent"));
  }
}
