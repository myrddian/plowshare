package io.aeyer.plowshare.server.agents.digests;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.accounting.UsageLineage;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DigestModelTest {
  @Test
  void interleaved_memory_operations_keep_their_original_requesting_execution() {
    var dispatcher = mock(LlmDispatcher.class);
    var logs = mock(Compaction.class);
    when(logs.logFor(any(), any(), any(), isNull(), any())).thenReturn(Transcript.NONE);
    when(dispatcher.complete(any()))
        .thenReturn(new Completion("answer", "stop", TokenUsage.UNKNOWN, List.of()));
    var model = new DigestModel(dispatcher, () -> logs, new MemoryProperties(), () -> "fast");
    var a =
        UsageAttribution.project("alice", "1", UsageAttribution.Operation.AGENT_CHAT)
            .withExecution(
                UsageLineage.root("alice-conversation"),
                UsageLineage.root("alice-run"),
                UsageLineage.NONE,
                "alice-agent",
                1L,
                2L);
    var b =
        UsageAttribution.project("bob", "2", UsageAttribution.Operation.AGENT_CHAT)
            .withExecution(
                UsageLineage.root("bob-conversation"),
                UsageLineage.root("bob-run"),
                UsageLineage.NONE,
                "bob-agent",
                1L,
                2L);
    var budgetA = Budget.of(3);
    var budgetB = Budget.of(3);
    try (var one = model.operation("memory_navigator", Home.of("a"), budgetA, a);
        var two = model.operation("memory_digester", Home.of("b"), budgetB, b)) {
      one.call("memory_navigator", "choose", "alice first", Home.of("a"), budgetA);
      two.call("memory_digester", "summarise", "bob", Home.of("b"), budgetB);
      one.call("memory_navigator", "choose", "alice second", Home.of("a"), budgetA);
    }
    var sent = ArgumentCaptor.forClass(ChatRequest.class);
    verify(dispatcher, times(3)).complete(sent.capture());
    assertEquals(
        List.of("alice", "bob", "alice"),
        sent.getAllValues().stream().map(r -> r.attribution().accountHandle()).toList());
    assertEquals(
        List.of("alice-run", "bob-run", "alice-run"),
        sent.getAllValues().stream().map(r -> r.attribution().runs().id()).toList());
    assertEquals("NAVIGATION", sent.getAllValues().getFirst().attribution().operation().name());
    assertEquals("DIGEST", sent.getAllValues().get(1).attribution().operation().name());
  }

  @Test
  void one_operation_owns_its_trace_and_each_descent_gets_a_fresh_context() {
    var dispatcher = mock(LlmDispatcher.class);
    var logs = mock(Compaction.class);
    var transcript = mock(Transcript.class);
    var completion = mock(Completion.class);
    when(completion.content()).thenReturn("dig_chosen");
    when(dispatcher.complete(any())).thenReturn(completion);
    Budget system = Budget.of(8);
    when(logs.logFor(eq(Origin.MEMORY), eq(Home.global()), any(), isNull(), same(system)))
        .thenReturn(transcript);
    var model = new DigestModel(dispatcher, () -> logs, new MemoryProperties(), () -> "fast");
    try (var operation = model.operation("memory_navigator", Home.global(), system)) {
      operation.call("memory_navigator", "choose", "first branch", Home.global(), system);
      operation.call("memory_navigator", "choose", "second branch", Home.global(), system);
      operation.result("returned evidence", true);
    }
    assertEquals(2, system.spent());
    ArgumentCaptor<ChatRequest> sent = ArgumentCaptor.forClass(ChatRequest.class);
    verify(dispatcher, times(2)).complete(sent.capture());
    assertEquals(2, sent.getAllValues().get(1).messages().size());
    assertFalse(sent.getAllValues().get(1).messages().toString().contains("first branch"));
    verify(transcript, times(1)).closed(eq("System memory operation"), any());
    verify(logs, times(1))
        .logFor(eq(Origin.MEMORY), eq(Home.global()), any(), isNull(), same(system));
  }

  @Test
  void truncated_generation_is_not_persisted_as_a_successful_summary() {
    var dispatcher = mock(LlmDispatcher.class);
    var logs = mock(Compaction.class);
    var transcript = mock(Transcript.class);
    var completion = mock(Completion.class);
    when(completion.finishReason()).thenReturn("length");
    when(dispatcher.complete(any())).thenReturn(completion);
    when(logs.logFor(any(), any(), any(), isNull(), any())).thenReturn(transcript);
    var model = new DigestModel(dispatcher, () -> logs, new MemoryProperties(), () -> "fast");
    Budget budget = Budget.of(2);
    try (var operation = model.operation("memory_digester", Home.global(), budget)) {
      assertNotNull(operation);
      assertThrows(
          IllegalStateException.class,
          () -> operation.call("memory_digester", "summarise", "evidence", Home.global(), budget));
    }
    verify(transcript)
        .closed(anyString(), argThat(outcome -> outcome.ending() == Outcome.Ending.UNAVAILABLE));
  }

  @Test
  void a_memory_call_is_sent_to_the_system_binding_and_not_to_a_capability_class() {
    var dispatcher = mock(LlmDispatcher.class);
    var logs = mock(Compaction.class);
    var transcript = mock(Transcript.class);
    var completion = mock(Completion.class);
    when(completion.content()).thenReturn("ok");
    when(dispatcher.complete(any())).thenReturn(completion);
    when(logs.logFor(any(), any(), any(), any(), any())).thenReturn(transcript);

    var properties = new MemoryProperties();
    var model = new DigestModel(dispatcher, () -> logs, properties, () -> "openai/gpt-oss-120b");
    var budget = Budget.of(4);
    try (var operation = model.operation("memory_digester", Home.of("p"), budget)) {
      assertNotNull(operation);
      operation.call("memory_digester", "instruction", "evidence", Home.of("p"), budget);
    }

    var sent = ArgumentCaptor.forClass(ChatRequest.class);
    verify(dispatcher).complete(sent.capture());
    assertEquals("openai/gpt-oss-120b", sent.getValue().specifier());
  }

  @Test
  void a_memory_operation_s_evidence_is_recorded_as_the_harness_s() {
    var dispatcher = mock(LlmDispatcher.class);
    var logs = mock(Compaction.class);
    var transcript = mock(Transcript.class);
    var completion = mock(Completion.class);
    when(completion.content()).thenReturn("dig_chosen");
    when(dispatcher.complete(any())).thenReturn(completion);
    Budget system = Budget.of(8);
    when(logs.logFor(any(), any(), any(), isNull(), any())).thenReturn(transcript);
    var model = new DigestModel(dispatcher, () -> logs, new MemoryProperties(), () -> "fast");
    try (var operation = model.operation("memory_navigator", Home.global(), system)) {
      operation.call("memory_navigator", "choose", "first branch", Home.global(), system);
      operation.result("returned evidence", true);
    }
    ArgumentCaptor<LoggedEntry> recorded = ArgumentCaptor.forClass(LoggedEntry.class);
    verify(transcript, atLeastOnce()).record(recorded.capture());
    assertEquals(Speaker.harness(), recorded.getAllValues().get(0).speaker());
  }
}
