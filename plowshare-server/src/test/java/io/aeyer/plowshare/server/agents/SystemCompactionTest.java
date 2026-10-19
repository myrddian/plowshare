package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class SystemCompactionTest {
  @Test
  void skill_summary_keeps_finite_exhausted_and_unlimited_conversation_spending_unchanged() {
    Budget exhausted = Budget.of(1);
    assertTrue(exhausted.trySpend());
    for (Budget task : List.of(Budget.of(3), exhausted, Budget.none(), Budget.noCalls())) {
      Fixture fixture = new Fixture(new AgentsProperties()::compactionAllowance);
      when(fixture.source.budget()).thenReturn(task);
      int spent = task.spent();
      try {
        assertEquals("summary", fixture.summary());
        InferenceBinding binding = fixture.claims.getFirst();
        assertNotEquals(task.identity(), binding.budget());
        assertNull(binding.conversation());
        assertNull(binding.limit());
        assertEquals(1, binding.ordinal());
        assertEquals(spent, task.spent());
        verify(fixture.receipts).retain(binding);
        verify(fixture.evidence)
            .retain(argThat(e -> e.purpose() == SystemOperations.Purpose.SKILL_SUMMARY));
      } finally {
        fixture.compaction.close();
      }
    }
  }

  @Test
  void a_configured_service_policy_limits_or_disables_only_the_summary_operation() {
    AgentsProperties policy = new AgentsProperties();
    policy.setCompactionBudget("1");
    Fixture fixture = new Fixture(policy::compactionAllowance);
    try {
      assertEquals("summary", fixture.summary());
      assertEquals(1, fixture.claims.getFirst().limit());
      policy.setCompactionBudget("0");
      assertThrows(IllegalStateException.class, fixture::summary);
      assertEquals(1, fixture.claims.size());
      policy.setCompactionBudget("unlimited");
      assertEquals("summary", fixture.summary());
      assertNull(fixture.claims.getLast().limit());
      assertNotEquals(fixture.claims.getFirst().budget(), fixture.claims.getLast().budget());
      assertNotEquals(fixture.claims.getFirst().operation(), fixture.claims.getLast().operation());
      assertThrows(IllegalArgumentException.class, () -> policy.setCompactionBudget("-1"));
      assertThrows(IllegalArgumentException.class, () -> policy.setCompactionBudget("invalid"));
    } finally {
      fixture.compaction.close();
    }
  }

  @Test
  void source_invalidation_and_cancellation_still_refuse_system_summary() {
    Fixture fixture = new Fixture(Budget::none);
    try {
      assertThrows(
          IllegalStateException.class,
          () -> fixture.compaction.summaryForSkill("data", fixture.parent, () -> true));
      assertTrue(fixture.claims.isEmpty());
      assertEquals("summary", fixture.summary());
      when(fixture.conversations.lifecycleOf("conversation"))
          .thenReturn(Optional.of(ConversationLifecycle.EJECTED));
      assertThrows(IllegalStateException.class, fixture.request.authorization()::require);
      assertThrows(IllegalStateException.class, fixture::summary);
      assertEquals(1, fixture.claims.size());
    } finally {
      fixture.compaction.close();
    }
  }

  @Test
  void asynchronous_compaction_runs_with_an_exhausted_task_and_a_separate_system_claim() {
    Fixture fixture = new Fixture(Budget::none);
    Budget task = Budget.of(1);
    assertTrue(task.trySpend());
    when(fixture.source.budget()).thenReturn(task);
    when(fixture.source.id()).thenReturn("conversation");
    when(fixture.conversations.log(
            eq(Origin.MEMORY),
            eq(Home.global()),
            eq(fixture.folder.name()),
            isNull(),
            same(task),
            isNull(),
            isNull()))
        .thenReturn(fixture.source);
    when(fixture.models.contextLength(any(), anyInt())).thenReturn(OptionalInt.of(100_000));
    when(fixture.models.compactionThreshold(anyString())).thenReturn(OptionalInt.of(60_000));
    when(fixture.models.compactionNowThreshold(anyString())).thenReturn(OptionalInt.of(80_000));
    when(fixture.turns.forConversation("conversation"))
        .thenReturn(
            List.of(
                new TurnRecord(
                    "conversation",
                    1,
                    "first",
                    "answer",
                    Outcome.Ending.ANSWERED,
                    1000,
                    "worker",
                    null),
                new TurnRecord(
                    "conversation",
                    2,
                    "second",
                    "answer",
                    Outcome.Ending.ANSWERED,
                    90000,
                    "worker",
                    null)));
    when(fixture.entries.thatProjectFor("conversation"))
        .thenReturn(
            List.of(
                entry(1, 1, EntryKind.UTTERANCE, "first"),
                entry(2, 1, EntryKind.ANSWER, "answer"),
                entry(3, 2, EntryKind.UTTERANCE, "second"),
                entry(4, 2, EntryKind.ANSWER, "answer")));
    try {
      Compaction.TurnTranscript transcript =
          assertInstanceOf(
              Compaction.TurnTranscript.class,
              fixture.compaction.logFor(Origin.MEMORY, Home.global(), fixture.folder, null, task));
      transcript.promptMeasured(90000);
      transcript.foldWhenTheTurnIsOver();
      verify(fixture.compactions, timeout(5000)).record("conversation", 1, "summary");
      assertEquals(1, task.spent());
      assertEquals(1, fixture.claims.size());
      assertNotEquals(task.identity(), fixture.claims.getFirst().budget());
      assertNull(fixture.claims.getFirst().conversation());
      assertNull(fixture.claims.getFirst().limit());
      verify(fixture.evidence)
          .retain(argThat(e -> e.purpose() == SystemOperations.Purpose.CONVERSATION_COMPACTION));
    } finally {
      fixture.compaction.close();
    }
  }

  private static EntryRecord entry(int ordinal, int turn, EntryKind kind, String text) {
    return new EntryRecord(
        "conversation",
        ordinal,
        kind,
        text,
        null,
        List.of(),
        null,
        null,
        turn,
        null,
        null,
        null,
        null);
  }

  @Test
  void in_turn_compaction_uses_service_policy_and_retains_history_when_disabled() {
    for (String selected : List.of("unlimited", "1", "0")) {
      AgentsProperties policy = new AgentsProperties();
      policy.setCompactionBudget(selected);
      Fixture fixture = new Fixture(policy::compactionAllowance);
      Budget task = Budget.of(1);
      assertTrue(task.trySpend());
      when(fixture.source.budget()).thenReturn(task);
      when(fixture.models.contextLength(any(), anyInt())).thenReturn(OptionalInt.of(100_000));
      when(fixture.models.compactionThreshold(anyString())).thenReturn(OptionalInt.of(60_000));
      when(fixture.models.compactionNowThreshold(anyString())).thenReturn(OptionalInt.of(80_000));
      List<ChatMessage> history =
          new ArrayList<>(
              List.of(
                  ChatMessage.system("worker instructions"), ChatMessage.user("current request")));
      List<EntryRecord> log = new ArrayList<>();
      log.add(entry(1, 1, EntryKind.UTTERANCE, "current request"));
      for (int step = 0; step < 4; step++) {
        io.aeyer.plowshare.protocol.ToolCall call =
            new io.aeyer.plowshare.protocol.ToolCall("call-" + step, "read", "{}");
        history.add(ChatMessage.assistant("working", List.of(call)));
        history.add(ChatMessage.tool(call.id(), "result"));
        log.add(
            new EntryRecord(
                "conversation",
                2 + step * 2,
                EntryKind.ANSWER,
                "working",
                null,
                List.of(call),
                null,
                null,
                1,
                null,
                null,
                null,
                null));
        log.add(
            new EntryRecord(
                "conversation",
                3 + step * 2,
                EntryKind.TOOL_RESULT,
                "result",
                call.id(),
                List.of(),
                null,
                null,
                1,
                null,
                null,
                null,
                null));
      }
      when(fixture.entries.thatProjectFor("conversation")).thenReturn(log);
      List<ChatMessage> original = List.copyOf(history);
      try {
        Compaction.TurnTranscript transcript =
            fixture.compaction.transcriptFor("conversation", fixture.folder, Speaker.person(null));
        transcript.promptMeasured(90000);
        transcript.stepEnded(history, 1);
        assertEquals(1, task.spent());
        if (selected.equals("0")) {
          assertEquals(original, history);
          assertTrue(fixture.claims.isEmpty());
          verify(fixture.entries, never())
              .foldWithinATurn(anyString(), anyInt(), anyInt(), anyInt(), anyInt(), any());
        } else {
          assertEquals(1, fixture.claims.size());
          assertNull(fixture.claims.getFirst().conversation());
          assertNotEquals(task.identity(), fixture.claims.getFirst().budget());
          verify(fixture.entries)
              .foldWithinATurn(eq("conversation"), eq(0), eq(1), eq(2), eq(3), any());
          assertTrue(history.size() < original.size());
        }
      } finally {
        fixture.compaction.close();
      }
    }
  }

  private static final class Fixture {
    final SystemOperationRecords evidence = mock(SystemOperationRecords.class);
    final InferenceAdmissions receipts = mock(InferenceAdmissions.class);
    final LlmDispatcher models = mock(LlmDispatcher.class);
    final ConversationStore conversations = mock(ConversationStore.class);
    final ConversationRecord source = mock(ConversationRecord.class);
    final TurnStore turns = mock(TurnStore.class);
    final EntryStore entries = mock(EntryStore.class);
    final CompactionStore compactions = mock(CompactionStore.class);
    final Transcript parent = mock(Transcript.class, CALLS_REAL_METHODS);
    final List<InferenceBinding> claims = new CopyOnWriteArrayList<>();
    final AgentDefinition folder =
        new AgentDefinition(
            "conversation_folder",
            "folder",
            "model",
            List.of(),
            List.of(),
            List.of(),
            1,
            2,
            "Summarise");
    final Compaction compaction;
    volatile ChatRequest request;

    Fixture(Supplier<Budget> allowances) {
      IdentityRepository identities = mock(IdentityRepository.class);
      ScopedGrantRepository grants = mock(ScopedGrantRepository.class);
      ScopedAuthority authority = mock(ScopedAuthority.class);
      AuthorityScope.AccountKnowledge target =
          new AuthorityScope.AccountKnowledge(
              BuiltInIdentities.GLOBAL_KNOWLEDGE, BuiltInIdentities.GLOBAL);
      when(identities.scope(target.id())).thenReturn(target);
      ScopedGrantRepository.Binding root = mock(ScopedGrantRepository.Binding.class);
      UUID grant = UUID.randomUUID();
      when(root.id()).thenReturn(grant);
      when(root.role()).thenReturn(StandardRole.SYSTEM);
      when(grants.live(any())).thenReturn(List.of(root));
      when(authority.allowsUsing(any(), eq(target), any(), eq(grant))).thenReturn(true);
      SystemOperations system =
          new BoundSystemOperations(
              identities,
              grants,
              authority,
              evidence,
              new OperationInference(mock(OperationAuthority.class), receipts));
      when(source.home()).thenReturn(Home.global());
      when(conversations.find("conversation")).thenReturn(Optional.of(source));
      when(conversations.lifecycleOf("conversation"))
          .thenReturn(Optional.of(ConversationLifecycle.ACTIVE));
      when(parent.conversationId()).thenReturn("conversation");
      when(models.streamFold(any(), any(), any())).thenAnswer(call -> answer(call.getArgument(0)));
      when(models.streamFold(any(), any())).thenAnswer(call -> answer(call.getArgument(0)));
      compaction =
          new Compaction(
              models,
              () -> folder,
              turns,
              compactions,
              entries,
              4096,
              conversations,
              Learning.NONE,
              Citing.NONE,
              system,
              allowances);
    }

    String summary() {
      return compaction.summaryForSkill("bounded historical data", parent, () -> false);
    }

    private Completion answer(ChatRequest asked) {
      asked.authorization().requireRequest(asked);
      request = asked;
      claims.add(asked.authorization().binding());
      return new Completion("summary", "stop", TokenUsage.UNKNOWN, List.of());
    }
  }
}
