package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.JobDelta;
import io.aeyer.plowshare.protocol.JobEvent;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.archive.CompactionStore;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryRecord;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.TurnRecord;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.images.ImageStore;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.accounting.UsageLineage;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmException;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Retry policy and recorded-history recovery use fake repositories and no provider or database. */
class ConversationRetryTest {
  private static final Home HOME = Home.of("game");
  private static final ToolCall DELEGATE =
      new ToolCall(
          "original-delegate", "agent_run", "{\"agent\":\"reviewer\",\"task\":\"Review\"}");
  private static final AgentDefinition CHILD =
      new AgentDefinition(
          "reviewer",
          "review",
          "fast",
          List.of(),
          List.of(),
          List.of(),
          5,
          20,
          "Review",
          false,
          true);
  private static final AgentDefinition PARENT =
      new AgentDefinition(
          "parent",
          "parent",
          "fast",
          List.of("agent_run"),
          List.of("reviewer"),
          List.of(),
          5,
          20,
          "Do the task",
          true,
          false);

  @Test
  void transient_failures_offer_explicit_continuation_but_a_finished_answer_does_not() {
    assertTrue(Turn.continuationIsOffered(Outcome.Ending.UNAVAILABLE));
    assertTrue(Turn.continuationIsOffered(Outcome.Ending.SUB_AGENT_FAILED));
    assertFalse(Turn.continuationIsOffered(Outcome.Ending.ANSWERED));
    assertFalse(Turn.continuationIsOffered(Outcome.Ending.CANCELLED));
    assertFalse(Turn.originIsResumable(Origin.DELEGATION));
  }

  @Test
  void missing_delegation_evidence_stays_closed_across_repeated_retry_turns() {
    try (Fixture f = new Fixture()) {
      when(f.entries.thatProjectFor("parent"))
          .thenReturn(List.of(entry("parent", 1, 1, EntryKind.UTTERANCE, "Task", null, List.of())));
      when(f.turns.forConversation("parent"))
          .thenReturn(
              List.of(
                  stopped("parent", 1, "child failed", Outcome.Ending.SUB_AGENT_FAILED),
                  stopped("parent", 2, "recovery link missing", Outcome.Ending.UNAVAILABLE)));
      for (int continuing : List.of(1, 2)) {
        Transcript retry = f.compaction.resumedTranscriptFor("parent", PARENT, continuing);
        retry.before();
        assertThrows(IllegalStateException.class, retry::pendingDelegationRetry);
      }
    }
  }

  @Test
  void failed_turn_close_records_only_nonprojecting_error_entries_and_preserves_recovery_work() {
    try (Fixture f = new Fixture()) {
      Transcript trace = f.compaction.transcriptFor("child", CHILD, Speaker.harness());
      trace.closed(
          "Review", new Outcome(Outcome.Ending.UNAVAILABLE, "model timed out", 2, 3, "timeout"));
      var written = ArgumentCaptor.forClass(LoggedEntry.class);
      verify(f.entries, atLeastOnce()).append(eq("child"), anyInt(), written.capture(), any());
      assertTrue(
          written.getAllValues().stream().anyMatch(e -> e.kind() == EntryKind.ATTEMPT_FAILED));
      assertTrue(written.getAllValues().stream().noneMatch(e -> e.kind().projects()));
      verify(f.dispatcher, never()).contextLength(any(), anyInt());
    }
  }

  @Test
  void repeated_retry_retains_original_tools_and_excludes_old_synthetic_failure_answer() {
    try (Fixture f = new Fixture()) {
      ToolCall read = new ToolCall("read", "file_read", "{}");
      when(f.entries.thatProjectFor("child"))
          .thenReturn(
              List.of(
                  entry("child", 1, 1, EntryKind.UTTERANCE, "Review", null, List.of()),
                  entry("child", 2, 1, EntryKind.ANSWER, "", null, List.of(read)),
                  entry(
                      "child",
                      3,
                      1,
                      EntryKind.TOOL_RESULT,
                      "paid-for file contents",
                      "read",
                      List.of()),
                  entry("child", 4, 1, EntryKind.ANSWER, "old timeout summary", null, List.of()),
                  entry(
                      "child", 5, 2, EntryKind.ANSWER, "second timeout summary", null, List.of())));
      when(f.turns.forConversation("child"))
          .thenReturn(
              List.of(
                  stopped("child", 1, "old timeout summary", Outcome.Ending.UNAVAILABLE),
                  stopped("child", 2, "second timeout summary", Outcome.Ending.UNAVAILABLE)));
      List<ChatMessage> history = f.compaction.resumedTranscriptFor("child", CHILD, 2).before();
      assertTrue(history.stream().anyMatch(m -> m.content().equals("paid-for file contents")));
      assertFalse(history.stream().anyMatch(m -> m.content().contains("timeout summary")));
    }
  }

  @Test
  void repeated_parent_retry_recovers_the_original_child_and_memoizes_its_link() {
    try (Fixture f = new Fixture()) {
      f.failedParentAt(2);
      Transcript retry = f.compaction.resumedTranscriptFor("parent", PARENT, 2);
      retry.before();
      Transcript.DelegationRetry pending = retry.pendingDelegationRetry().orElseThrow();
      assertEquals(DELEGATE, pending.call());
      assertEquals("child", pending.conversation());
      assertEquals(pending, retry.pendingDelegationRetry().orElseThrow());
      assertEquals("child", retry.resumeDelegation(pending, CHILD).conversationId());
      verify(f.conversations).delegationOpenedBy("parent", DELEGATE.id());
      verify(f.conversations, never()).log(any(), any(), any(), any(), any());
    }
  }

  @Test
  void failed_history_reads_stop_retry_and_unrecorded_mutations_are_not_replayed() {
    try (Fixture f = new Fixture()) {
      when(f.entries.thatProjectFor("parent")).thenThrow(new IllegalStateException("archive down"));
      assertThrows(
          IllegalStateException.class,
          () -> f.compaction.resumedTranscriptFor("parent", PARENT, 1).before());
      doReturn(
              List.of(
                  entry("parent", 1, 1, EntryKind.UTTERANCE, "Task", null, List.of()),
                  entry(
                      "parent",
                      2,
                      1,
                      EntryKind.ANSWER,
                      "",
                      null,
                      List.of(new ToolCall("uncertain", "run", "{}")))))
          .when(f.entries)
          .thatProjectFor("parent");
      Transcript retry = f.compaction.resumedTranscriptFor("parent", PARENT, 1);
      retry.before();
      assertThrows(IllegalStateException.class, retry::pendingDelegationRetry);
      retry.recordDurably(LoggedEntry.toolResult("uncertain", "retained command receipt"));
      assertTrue(
          retry.pendingDelegationRetry().isEmpty(),
          "a command reconciled before delegate recovery is no longer uncertain");
    }
  }

  @Test
  void retry_runs_child_from_its_own_history_then_returns_original_tool_result_to_parent() {
    try (Fixture f = new Fixture()) {
      f.failedParentAt(1);
      when(f.entries.thatProjectFor("child"))
          .thenReturn(
              List.of(
                  entry("child", 1, 1, EntryKind.UTTERANCE, "Review", null, List.of()),
                  entry(
                      "child",
                      2,
                      1,
                      EntryKind.ANSWER,
                      "",
                      null,
                      List.of(new ToolCall("read", "file_read", "{}"))),
                  entry(
                      "child",
                      3,
                      1,
                      EntryKind.TOOL_RESULT,
                      "paid-for contents",
                      "read",
                      List.of())));
      List<List<ChatMessage>> requests = new ArrayList<>();
      when(f.dispatcher.stream(any(), any(), any()))
          .thenAnswer(
              invocation -> {
                ChatRequest request = invocation.getArgument(0);
                requests.add(request.messages());
                Deltas sink = invocation.getArgument(1);
                if (requests.size() == 1) sink.thought("child checks the retained file");
                return answer(requests.size() == 1 ? "review complete" : "parent complete");
              });
      JobRuntime runtime = f.runtime();
      List<JobDelta> previews = new ArrayList<>();
      Outcome result =
          runtime.run(
              PARENT,
              "Continue",
              HOME,
              Budget.of(20),
              () -> false,
              "session",
              watch(previews),
              f.compaction.resumedTranscriptFor("parent", PARENT, 1),
              TurnCap.of(5));
      assertEquals(Outcome.Ending.ANSWERED, result.ending());
      assertEquals("parent complete", result.text());
      assertEquals(2, requests.size(), "retry child before spending a parent inference");
      assertTrue(
          requests.getFirst().stream().anyMatch(m -> m.content().equals("paid-for contents")));
      assertTrue(
          requests.getLast().stream()
              .anyMatch(
                  m ->
                      DELEGATE.id().equals(m.toolCallId())
                          && m.content().contains("review complete")));
      assertTrue(
          previews.contains(JobDelta.thinking("root-job", "child checks the retained file")));
    }
  }

  @Test
  void a_completed_child_is_reconciled_without_another_child_model_call() {
    try (Fixture f = new Fixture()) {
      f.failedParentAt(1);
      when(f.turns.forConversation("child"))
          .thenReturn(
              List.of(stopped("child", 1, "retained review answer", Outcome.Ending.ANSWERED)));
      List<ChatRequest> requests = new ArrayList<>();
      when(f.dispatcher.stream(any(), any(), any()))
          .thenAnswer(
              invocation -> {
                requests.add(invocation.getArgument(0));
                return answer("finished parent");
              });
      Outcome outcome =
          f.runtime()
              .run(
                  PARENT,
                  "Continue",
                  HOME,
                  Budget.of(20),
                  () -> false,
                  "session",
                  JobWatch.UNWATCHED,
                  f.compaction.resumedTranscriptFor("parent", PARENT, 1),
                  TurnCap.of(5));
      assertEquals(Outcome.Ending.ANSWERED, outcome.ending());
      assertEquals(1, requests.size());
      assertTrue(
          requests.getFirst().messages().stream()
              .anyMatch(
                  m ->
                      DELEGATE.id().equals(m.toolCallId())
                          && m.content().contains("retained review answer")));
    }
  }

  @Test
  void failed_child_returns_to_parent_and_explicit_retry_reuses_child_history() {
    try (Fixture f = new Fixture()) {
      ConversationRecord child = f.conversations.find("child").orElseThrow();
      when(f.conversations.log(
              eq(Origin.DELEGATION),
              eq(HOME),
              eq(CHILD.name()),
              eq("parent"),
              any(),
              any(),
              eq(DELEGATE.id())))
          .thenReturn(child);
      List<List<ChatMessage>> requests = new ArrayList<>();
      when(f.dispatcher.stream(any(), any(), any()))
          .thenAnswer(
              invocation -> {
                ChatRequest request = invocation.getArgument(0);
                requests.add(request.messages());
                return switch (requests.size()) {
                  case 1 -> new Completion("", "tool_calls", TokenUsage.UNKNOWN, List.of(DELEGATE));
                  case 2 -> throw new LlmException("model timed out");
                  case 3 -> answer("continued the other work");
                  case 4 ->
                      new Completion(
                          "",
                          "tool_calls",
                          TokenUsage.UNKNOWN,
                          List.of(
                              new ToolCall(
                                  "explicit-retry",
                                  "agent_run",
                                  "{\"agent\":\"reviewer\",\"task\":\"Review\",\"conversation\":\"child\"}")));
                  case 5 -> answer("review from retained work");
                  default -> answer("parent finished");
                };
              });
      JobRuntime runtime = f.runtime();
      Outcome first =
          runtime.run(
              PARENT,
              "Task",
              HOME,
              Budget.of(20),
              () -> false,
              "session",
              JobWatch.UNWATCHED,
              f.compaction.transcriptFor("parent", PARENT, Speaker.person("alice")),
              TurnCap.of(5));
      assertEquals(Outcome.Ending.ANSWERED, first.ending());
      assertEquals("continued the other work", first.text());
      assertTrue(
          requests.get(2).stream()
              .anyMatch(m -> m.content().contains("Retained child conversation: child")));
      when(f.entries.delegationCall("parent", "child")).thenReturn(Optional.of(DELEGATE));
      when(f.entries.thatProjectFor("child"))
          .thenReturn(
              List.of(
                  entry("child", 1, 1, EntryKind.UTTERANCE, "Review", null, List.of()),
                  entry(
                      "child",
                      2,
                      1,
                      EntryKind.ANSWER,
                      "",
                      null,
                      List.of(new ToolCall("already-read", "file_read", "{}"))),
                  entry(
                      "child",
                      3,
                      1,
                      EntryKind.TOOL_RESULT,
                      "expensive retained work",
                      "already-read",
                      List.of())));
      Outcome retried =
          runtime.run(
              PARENT,
              "Retry that review",
              HOME,
              Budget.of(20),
              () -> false,
              "session",
              JobWatch.UNWATCHED,
              f.compaction.transcriptFor("parent", PARENT, Speaker.person("alice")),
              TurnCap.of(5));
      assertEquals(Outcome.Ending.ANSWERED, retried.ending());
      assertTrue(
          requests.get(4).stream().anyMatch(m -> m.content().equals("expensive retained work")));
      verify(f.conversations)
          .log(
              eq(Origin.DELEGATION),
              eq(HOME),
              eq(CHILD.name()),
              eq("parent"),
              any(),
              any(),
              eq(DELEGATE.id()));
    }
  }

  @Test
  void explicit_child_retry_refuses_other_parent_home_agent_and_changed_task() {
    try (Fixture f = new Fixture()) {
      Transcript parent = f.compaction.transcriptFor("parent", PARENT, Speaker.harness());
      assertThrows(
          IllegalStateException.class,
          () -> parent.retainedDelegationRetry("child", CHILD, Home.of("other-home")));
      assertThrows(
          IllegalStateException.class, () -> parent.retainedDelegationRetry("child", PARENT, HOME));
      Transcript other = f.compaction.transcriptFor("other-parent", PARENT, Speaker.harness());
      assertThrows(
          IllegalStateException.class, () -> other.retainedDelegationRetry("child", CHILD, HOME));
      when(f.entries.delegationCall("parent", "child")).thenReturn(Optional.of(DELEGATE));
      AgentRunTool tool =
          new AgentRunTool(
              new AgentRegistry(Map.of(PARENT.name(), PARENT, CHILD.name(), CHILD)),
              f.runtime(),
              PARENT,
              Budget.of(20),
              () -> false,
              "session",
              parent,
              List.of(),
              ImageStore.NONE);
      String result =
          tool.run(
              "{\"agent\":\"reviewer\",\"task\":\"Changed task\",\"conversation\":\"child\"}",
              HOME);
      assertTrue(result.contains("original task and images unchanged"), result);
      String changedImages =
          tool.run(
              "{\"agent\":\"reviewer\",\"task\":\"Review\",\"images\":[\"different-image\"],\"conversation\":\"child\"}",
              HOME);
      assertTrue(changedImages.contains("original task and images unchanged"), changedImages);
      String blankConversation =
          tool.run("{\"agent\":\"reviewer\",\"task\":\"Review\",\"conversation\":\" \"}", HOME);
      assertTrue(blankConversation.contains("must not be blank"), blankConversation);
      verify(f.dispatcher, never()).stream(any(), any(), any());
    }
  }

  private static Completion answer(String text) {
    return new Completion(text, "stop", TokenUsage.UNKNOWN, List.of());
  }

  @Test
  void budget_checkpoint_after_a_failed_retry_keeps_the_original_task_execution_turn() {
    try (Fixture f = new Fixture()) {
      when(f.entries.thatProjectFor("child"))
          .thenReturn(
              List.of(
                  entry("child", 1, 1, EntryKind.UTTERANCE, "Review", null, List.of()),
                  entry(
                      "child",
                      2,
                      1,
                      EntryKind.ANSWER,
                      "",
                      null,
                      List.of(new ToolCall("already-read", "file_read", "{}"))),
                  entry(
                      "child",
                      3,
                      1,
                      EntryKind.TOOL_RESULT,
                      "paid-before-retry",
                      "already-read",
                      List.of())));
      UsageAttribution original =
          UsageAttribution.global("alice", UsageAttribution.Operation.AGENT_CHAT)
              .withExecution(
                  UsageLineage.root("child"),
                  UsageLineage.root("original-run"),
                  UsageLineage.NONE,
                  CHILD.name(),
                  1L,
                  1L);
      Transcript retried =
          new AttributedTranscript(
              f.compaction.resumedTranscriptFor("child", CHILD, 2), original, original);
      retried.before();
      BudgetExecutionStack stack = new BudgetExecutionStack();
      try (var running = stack.enter(CHILD, retried, TurnCap.of(5))) {
        running.progress(1, List.of());
        assertEquals(
            1,
            stack.snapshot().getFirst().turn(),
            "budget recovery must retain work from the original task, not only the last failed segment");
      }
    }
  }

  @Test
  void
      budget_checkpoint_of_an_explicit_failed_child_retry_uses_the_checkpoint_and_retained_history() {
    try (Fixture f = new Fixture()) {
      when(f.turns.forConversation("child"))
          .thenReturn(
              List.of(
                  stopped("child", 1, "old failure sentence", Outcome.Ending.UNAVAILABLE),
                  stopped("child", 2, "budget approval pending", Outcome.Ending.AWAITING)));
      when(f.entries.thatProjectFor("child"))
          .thenReturn(
              List.of(
                  entry("child", 1, 1, EntryKind.UTTERANCE, "Review", null, List.of()),
                  entry(
                      "child",
                      2,
                      1,
                      EntryKind.ANSWER,
                      "",
                      null,
                      List.of(new ToolCall("already-read", "file_read", "{}"))),
                  entry(
                      "child",
                      3,
                      1,
                      EntryKind.TOOL_RESULT,
                      "paid-before-budget",
                      "already-read",
                      List.of()),
                  entry("child", 4, 1, EntryKind.ANSWER, "old failure sentence", null, List.of()),
                  entry("child", 5, 2, EntryKind.NOTICE, "Budget approved", null, List.of())));
      List<ChatRequest> requests = new ArrayList<>();
      when(f.dispatcher.stream(any(), any(), any()))
          .thenAnswer(
              invocation -> {
                requests.add(invocation.getArgument(0));
                return answer("review finished after budget grant");
              });
      Transcript parent =
          new AttributedTranscript(
              f.compaction.transcriptFor("parent", PARENT, Speaker.harness()),
              UsageAttribution.LEGACY,
              UsageAttribution.LEGACY);
      AgentRunTool tool =
          new AgentRunTool(
              new AgentRegistry(Map.of(PARENT.name(), PARENT, CHILD.name(), CHILD)),
              f.runtime(),
              PARENT,
              Budget.of(20),
              () -> false,
              "session",
              parent,
              List.of(),
              ImageStore.NONE);
      ToolCall opening =
          new ToolCall(
              "explicit-retry",
              "agent_run",
              "{\"agent\":\"reviewer\",\"task\":\"Review\",\"conversation\":\"child\"}");
      String result =
          tool.resumeExisting(
              new BudgetCheckpoint.Frame("child", "reviewer", 1, 2, 5, List.of()), opening, HOME);

      assertTrue(result.contains("review finished after budget grant"), result);
      assertEquals(1, requests.size());
      assertTrue(
          requests.getFirst().messages().stream()
              .anyMatch(message -> message.content().equals("paid-before-budget")));
      assertFalse(
          requests.getFirst().messages().stream()
              .anyMatch(message -> message.content().equals("old failure sentence")));
      verify(f.entries, never()).delegationCall(any(), any());
      verify(f.conversations, never()).log(any(), any(), any(), any(), any(), any(), any());
    }
  }

  @Test
  void a_budget_checkpoint_rejects_a_different_explicit_child_selector() {
    try (Fixture f = new Fixture()) {
      AgentRunTool tool =
          new AgentRunTool(
              new AgentRegistry(Map.of(PARENT.name(), PARENT, CHILD.name(), CHILD)),
              f.runtime(),
              PARENT,
              Budget.of(20),
              () -> false,
              "session",
              f.compaction.transcriptFor("parent", PARENT, Speaker.harness()),
              List.of(),
              ImageStore.NONE);
      ToolCall changed =
          new ToolCall(
              "explicit-retry",
              "agent_run",
              "{\"agent\":\"reviewer\",\"task\":\"Review\",\"conversation\":\"foreign-child\"}");
      assertThrows(
          IllegalStateException.class,
          () ->
              tool.resumeExisting(
                  new BudgetCheckpoint.Frame("child", "reviewer", 1, 2, 5, List.of()),
                  changed,
                  HOME));
      verify(f.dispatcher, never()).stream(any(), any(), any());
      verify(f.conversations, never()).log(any(), any(), any(), any(), any(), any(), any());
    }
  }

  private static JobWatch watch(List<JobDelta> previews) {
    return new JobWatch(
        "root-job",
        "session",
        new JobEvents() {
          @Override
          public void publish(String session, JobEvent event) {}

          @Override
          public boolean streaming(String session) {
            return true;
          }

          @Override
          public void stream(String session, JobDelta delta) {
            previews.add(delta);
          }
        });
  }

  private static TurnRecord stopped(String id, int ordinal, String text, Outcome.Ending ending) {
    return new TurnRecord(
        id,
        ordinal,
        id.equals("child") ? "Review" : "Task",
        text,
        ending,
        null,
        id.equals("child") ? CHILD.name() : PARENT.name(),
        null);
  }

  private static EntryRecord entry(
      String id,
      int ordinal,
      int turn,
      EntryKind kind,
      String text,
      String call,
      List<ToolCall> calls) {
    return new EntryRecord(
        id, ordinal, kind, text, call, calls, null, null, turn, Instant.EPOCH, null, null, null);
  }

  private static final class Fixture implements AutoCloseable {
    final EntryStore entries = mock(EntryStore.class);
    final TurnStore turns = mock(TurnStore.class);
    final ConversationStore conversations = mock(ConversationStore.class);
    final LlmDispatcher dispatcher = mock(LlmDispatcher.class);
    final Compaction compaction =
        new Compaction(
            dispatcher,
            () -> CHILD,
            turns,
            mock(CompactionStore.class),
            entries,
            100000,
            Runnable::run,
            conversations);

    Fixture() {
      when(turns.reserve(any())).thenReturn(3);
      when(turns.forConversation(any())).thenReturn(List.of());
      when(entries.thatProjectFor(any())).thenReturn(List.of());
      ConversationRecord parent =
          new ConversationRecord(
              "parent",
              HOME,
              Origin.TURN,
              ConversationLifecycle.ACTIVE,
              null,
              null,
              Instant.EPOCH,
              Instant.EPOCH,
              Budget.of(20),
              TurnCap.of(5),
              null);
      ConversationRecord child =
          new ConversationRecord(
              "child",
              HOME,
              Origin.DELEGATION,
              null,
              "parent",
              "reviewer",
              Instant.EPOCH,
              Instant.EPOCH,
              null,
              null,
              null);
      when(conversations.find("parent")).thenReturn(Optional.of(parent));
      when(conversations.find("child")).thenReturn(Optional.of(child));
      when(conversations.delegationOpenedBy("parent", DELEGATE.id()))
          .thenReturn(Optional.of(child));
      when(turns.forConversation("child"))
          .thenReturn(List.of(stopped("child", 1, "model timed out", Outcome.Ending.UNAVAILABLE)));
    }

    void failedParentAt(int ordinal) {
      when(entries.thatProjectFor("parent"))
          .thenReturn(
              List.of(
                  entry("parent", 1, 1, EntryKind.UTTERANCE, "Task", null, List.of()),
                  entry("parent", 2, 1, EntryKind.ANSWER, "", null, List.of(DELEGATE))));
      when(turns.forConversation("parent"))
          .thenReturn(
              List.of(
                  stopped("parent", ordinal, "delegate failed", Outcome.Ending.SUB_AGENT_FAILED)));
    }

    JobRuntime runtime() {
      return new JobRuntime(
          dispatcher,
          List.of(),
          () -> new AgentRegistry(Map.of(PARENT.name(), PARENT, CHILD.name(), CHILD)));
    }

    @Override
    public void close() {
      compaction.close();
    }
  }
}
