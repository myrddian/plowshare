package io.aeyer.plowshare.server.agents.digests;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import java.util.List;
import org.junit.jupiter.api.Test;

class NavigatorRetentionTest {
  private final DigestStore store = mock(DigestStore.class);
  private final Compaction logs = mock(Compaction.class);

  private Navigator navigator() {
    when(logs.logFor(any(), any(), any(), any(), any())).thenReturn(Transcript.NONE);
    var model =
        new DigestModel(
            mock(LlmDispatcher.class), () -> logs, new MemoryProperties(), () -> "fast");
    return new Navigator(
        store, mock(Archive.class), mock(EntryStore.class), mock(ReasonLog.class), model);
  }

  @Test
  void unlimitedDefaultNavigatesOnlyRetainedTiersAndDisabledPolicyReadsNothing() {
    var home = Home.of("project");
    var service = navigator();
    assertEquals(
        "empty",
        service
            .navigate(home, "why", Budget.none(), () -> false, null, false, ignored -> {})
            .level());
    verify(store, never()).roots(Home.global());
    clearInvocations(store);
    assertFalse(
        service
            .navigate(home, "why", Budget.noCalls(), () -> false, null, true, ignored -> {})
            .complete());
    verifyNoInteractions(store);
    assertFalse(new MemoryProperties().navigationAllowance().capped());
    assertFalse(new MemoryProperties().digestAllowance().capped());
  }

  @Test
  void globalKnowledgeReadDoesNotDiscloseHistoricalNullConversationTrees() {
    var node = new DigestStore.Node("historical", 0, "private historical text", null, 1);
    when(store.roots(Home.global())).thenReturn(List.of(node));
    when(store.readTargets(Home.global(), node.id()))
        .thenReturn(
            List.of(
                new AccessRequest.Resource(ResourceScopeRepository.Kind.CONVERSATION, "old-null")));
    var result =
        navigator()
            .navigate(
                Home.of("project"),
                "why",
                Budget.none(),
                () -> false,
                null,
                true,
                refs -> {
                  if (!refs.isEmpty()) throw new CallerFault("No retained conversation read");
                });
    assertEquals("empty", result.level());
    assertFalse(result.text().contains("private historical text"));
  }

  @Test
  void cancellationReturnsAnIncompleteAnswerWithoutReadingOrDisclosingEvidence() {
    var result =
        navigator()
            .navigate(
                Home.of("project"), "why", Budget.none(), () -> true, null, true, ignored -> {});
    assertFalse(result.complete());
    assertTrue(result.ids().isEmpty());
    assertEquals("Navigation cancelled", result.text());
    verifyNoInteractions(store);
  }
}
