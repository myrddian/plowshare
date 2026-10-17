package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SystemCompactionTest {
  @Test
  void a_skill_summary_uses_the_conversation_allowance_and_retained_system_authority() {
    var identities = mock(IdentityRepository.class);
    var grants = mock(ScopedGrantRepository.class);
    var authority = mock(ScopedAuthority.class);
    var evidence = mock(SystemOperationRecords.class);
    var receipts = mock(InferenceAdmissions.class);
    var target =
        new AuthorityScope.AccountKnowledge(
            BuiltInIdentities.GLOBAL_KNOWLEDGE, BuiltInIdentities.GLOBAL);
    when(identities.scope(target.id())).thenReturn(target);
    var root = mock(ScopedGrantRepository.Binding.class);
    UUID id = UUID.randomUUID();
    when(root.id()).thenReturn(id);
    when(root.role()).thenReturn(StandardRole.SYSTEM);
    when(grants.live(any())).thenReturn(List.of(root));
    when(authority.allowsUsing(any(), eq(target), any(), eq(id))).thenReturn(true);
    var system =
        new BoundSystemOperations(
            identities,
            grants,
            authority,
            evidence,
            new OperationInference(mock(OperationAuthority.class), receipts));
    var models = mock(LlmDispatcher.class);
    when(models.streamFold(any(), any(), any()))
        .thenAnswer(
            call -> {
              ChatRequest request = call.getArgument(0);
              request.authorization().requireRequest(request);
              assertEquals("conversation", request.authorization().binding().conversation());
              return new Completion("summary", "stop", TokenUsage.UNKNOWN, List.of());
            });
    var conversations = mock(ConversationStore.class);
    var record = mock(ConversationRecord.class);
    when(record.home()).thenReturn(Home.global());
    when(conversations.find("conversation")).thenReturn(Optional.of(record));
    when(conversations.lifecycleOf("conversation"))
        .thenReturn(Optional.of(ConversationLifecycle.ACTIVE));
    var folder =
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
    var compaction =
        new Compaction(
            models,
            () -> folder,
            mock(TurnStore.class),
            mock(CompactionStore.class),
            mock(EntryStore.class),
            4096,
            conversations,
            Learning.NONE,
            Citing.NONE,
            system);
    var parent = mock(Transcript.class, CALLS_REAL_METHODS);
    when(parent.conversationId()).thenReturn("conversation");
    var persisted = new AtomicInteger();
    var budget = Budget.of(1);
    budget.bindConversation("conversation", claim -> persisted.set(claim.ordinal()));
    assertEquals(
        "summary",
        compaction.summaryForSkill("bounded historical data", parent, budget, () -> false));
    assertEquals(1, budget.spent());
    assertEquals(1, persisted.get());
    assertThrows(
        IllegalStateException.class,
        () -> compaction.summaryForSkill("bounded historical data", parent, budget, () -> false));
    verify(models, times(1)).streamFold(any(), any(), any());
    verify(evidence).retain(argThat(e -> e.purpose() == SystemOperations.Purpose.SKILL_SUMMARY));
  }
}
