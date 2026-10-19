package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.agents.MemoryTools;
import io.aeyer.plowshare.server.agents.scribe.Scribe;
import io.aeyer.plowshare.server.api.*;
import io.aeyer.plowshare.server.information.InformationJobs;
import io.aeyer.plowshare.server.ws.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MemoryWriteEntryPointsTest {
  final Archive archive = mock(Archive.class);
  final Scribe scribe = mock(Scribe.class);
  final MemoryProposal proposal = new MemoryProposal("claim", "scope", "body", "by", "where");

  void admitting() {
    when(archive.maxBodyChars()).thenReturn(1000);
    when(archive.writing()).thenReturn(true);
    when(archive.write(any()))
        .thenReturn(new WriteResult(VerdictKind.NEW, "mem_one", "filed", null, List.of()));
  }

  @Test
  void frameAndHttpSupplyIdentityWithoutAccountingOrWireChanges() {
    admitting();
    var alive = new AtomicBoolean(true);
    var asking =
        new Asking(
            null,
            "writer",
            null,
            io.aeyer.plowshare.server.relay.RelayReadBudget.LEGACY,
            () -> {
              if (!alive.get()) throw new IllegalStateException("closed");
            });
    new MemoryWriteHandler(archive, scribe)
        .handle(Map.of("project", "project", "proposal", proposal), asking);
    new MemoryController(archive, scribe)
        .write(new WriteMemoryRequest("project", proposal, null), "service");
    var requests = ArgumentCaptor.forClass(MemoryWrites.Request.class);
    verify(archive, times(2)).write(requests.capture());
    assertEquals("writer", requests.getAllValues().getFirst().account());
    assertEquals("service", requests.getAllValues().getLast().account());
    alive.set(false);
    assertThrows(
        IllegalStateException.class, requests.getAllValues().getFirst().origin().alive()::run);
    verifyNoInteractions(scribe);
    verify(archive, never()).usage(any(), any(), any());
  }

  @Test
  void toolRequiresRetainedCallAndKeepsCallerFenceSeparateFromLifetime() {
    admitting();
    var alive = new AtomicBoolean(true);
    var authority = new AtomicBoolean(true);
    var tool =
        new MemoryTools.Write(archive, () -> scribe)
            .forRun(
                null,
                "log",
                "writer",
                () -> {
                  if (!alive.get()) throw new IllegalStateException("ended");
                },
                () -> {
                  if (!authority.get()) throw new IllegalStateException("revoked");
                });
    String args = "{\"summary\":\"claim\",\"scope\":\"scope\",\"body\":\"body\"}";
    assertThrows(IllegalStateException.class, () -> tool.run(args, Home.of("project")));
    tool.calledAs("original-call");
    tool.run(args, Home.of("project"));
    var request = ArgumentCaptor.forClass(MemoryWrites.Request.class);
    verify(archive).write(request.capture());
    var origin = request.getValue().origin();
    assertEquals("tool:log:original-call", origin.invocation());
    authority.set(false);
    assertDoesNotThrow(origin.alive()::run);
    assertThrows(IllegalStateException.class, origin.authority()::run);
    alive.set(false);
    assertThrows(IllegalStateException.class, origin.alive()::run);
  }

  @Test
  void documentDerivedToolMaterialStillCannotPublishAsMemory() {
    admitting();
    var inputs = mock(InformationJobs.class);
    when(inputs.hasInputs("log")).thenReturn(true);
    var tool =
        new MemoryTools.Write(archive, () -> scribe)
            .forRun(inputs, "log", "writer", () -> {}, () -> {});
    tool.calledAs("call");
    assertTrue(tool.run("{}", Home.of("project")).contains("information_write"));
    verify(archive, never()).write(any());
  }

  @Test
  void productionArchiveRefusesBareVerdictAndScribeHelper() {
    var store = mock(MemoryStore.class);
    var concrete =
        new Archive(
            store,
            mock(ReasonLog.class),
            mock(io.aeyer.plowshare.server.llm.EmbeddingClient.class),
            UnitOfWork.NONE,
            1000,
            50,
            30);
    concrete.useWrites(() -> mock(MemoryWrites.class));
    assertThrows(
        IllegalStateException.class,
        () ->
            concrete.applyVerdict(
                proposal, new Verdict(VerdictKind.NEW, null, "flat"), Home.of("project")));
    var concreteScribe =
        new Scribe(
            mock(io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher.class), concrete, () -> null);
    assertThrows(
        IllegalStateException.class, () -> concreteScribe.judge(proposal, Home.of("project")));
    verifyNoInteractions(store);
  }

  @Test
  void learnerFilingSuppliesSystemWindowAndStableProposalOrdinal() {
    admitting();
    var entries = mock(EntryStore.class);
    var conversations = mock(ConversationStore.class);
    var record = mock(ConversationRecord.class);
    when(record.lifecycle()).thenReturn(ConversationLifecycle.ARCHIVED);
    when(record.home()).thenReturn(Home.of("project"));
    when(conversations.rootOf("log")).thenReturn(Optional.of(record));
    when(conversations.find("log")).thenReturn(Optional.of(record));
    when(entries.conversationsAwaitingTheLearner(anyInt())).thenReturn(List.of("log"));
    var entry =
        new EntryRecord(
            "log",
            1,
            io.aeyer.plowshare.server.agents.EntryKind.UTTERANCE,
            "source",
            null,
            List.of(),
            null,
            null,
            1,
            java.time.Instant.EPOCH,
            null,
            null,
            null);
    when(entries.awaitingTheLearner(eq("log"), eq(ConversationLifecycle.ARCHIVED), anyInt()))
        .thenReturn(List.of(entry));
    when(entries.countAwaitingTheLearner("log", ConversationLifecycle.ARCHIVED)).thenReturn(1);
    var dispatcher = mock(io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher.class);
    when(dispatcher.complete(any()))
        .thenReturn(
            new io.aeyer.plowshare.server.llm.dispatch.Completion(
                "{\"memories\":[{\"summary\":\"claim\",\"scope\":\"scope\",\"body\":\"body\"}]}",
                "stop",
                io.aeyer.plowshare.server.llm.dispatch.TokenUsage.UNKNOWN,
                List.of()));
    var definition =
        new io.aeyer.plowshare.server.agents.AgentDefinition(
            "learner", "Learn", "model", List.of(), List.of(), List.of(), 1, 1, "Extract");
    new io.aeyer.plowshare.server.agents.learner.Learner(
            dispatcher,
            entries,
            conversations,
            archive,
            scribe,
            () -> new io.aeyer.plowshare.server.agents.AgentRegistry(Map.of("learner", definition)))
        .thereIsMaterial();
    var request = ArgumentCaptor.forClass(MemoryWrites.Request.class);
    verify(archive).write(request.capture());
    assertNull(request.getValue().account());
    assertEquals("log", request.getValue().origin().learning().conversation());
    assertEquals(List.of(entry), request.getValue().origin().learning().entries());
    assertEquals(0, request.getValue().origin().learning().proposalOrdinal());
    assertEquals("learning:log:[1]:0", request.getValue().origin().invocation());
    verify(entries).markLearned(eq("log"), eq(List.of(1)), any());
    verifyNoInteractions(scribe);
  }

  @Test
  void legacyNullConversationHomeDoesNotAdmitGlobalLearningPublication() {
    var entry =
        new EntryRecord(
            "legacy-log",
            1,
            io.aeyer.plowshare.server.agents.EntryKind.UTTERANCE,
            "source",
            null,
            List.of(),
            null,
            null,
            1,
            java.time.Instant.EPOCH,
            null,
            null,
            null);
    var origin =
        new MemoryWrites.Origin(
            "legacy-window",
            () -> {},
            () -> {},
            new MemoryWrites.Learning("legacy-log", List.of(entry), 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MemoryWrites.Request(null, Home.global(), proposal, origin));
  }
}
