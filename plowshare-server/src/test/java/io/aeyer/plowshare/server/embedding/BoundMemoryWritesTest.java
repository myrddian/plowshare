package io.aeyer.plowshare.server.embedding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.agents.scribe.Scribe;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.*;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;

class BoundMemoryWritesTest {
  final Home home = Home.of("project");
  final Archive archive = mock(Archive.class);
  final MemoryWriteRepository memories = mock(MemoryWriteRepository.class);
  final QuerySources sources = mock(QuerySources.class);
  final EmbeddingWorkRepository outputs = mock(EmbeddingWorkRepository.class);
  final EmbeddingClient client = mock(EmbeddingClient.class);
  final SemanticEmbedding foundation = mock(SemanticEmbedding.class);
  final SystemOperations system = mock(SystemOperations.class);
  final ScopedPermissions permissions = mock(ScopedPermissions.class);
  final ScopedAuthority authority = mock(ScopedAuthority.class);
  final IdentityRepository identities = mock(IdentityRepository.class);
  final LlmDispatcher dispatcher = mock(LlmDispatcher.class);
  final QuerySources.Pin pin = new QuerySources.Pin(UUID.randomUUID(), UUID.randomUUID());
  final AtomicReference<Integer> policy = new AtomicReference<>();
  final AtomicReference<CallAuthorization> lastEmbedding = new AtomicReference<>();
  final AtomicInteger ordinals = new AtomicInteger(),
      embedded = new AtomicInteger(),
      chatted = new AtomicInteger();
  final AtomicBoolean alive = new AtomicBoolean(true), allowed = new AtomicBoolean(true);
  final float[] vector = {1, 0};
  final MemoryProposal proposal = new MemoryProposal("claim", "scope", "body", "writer", "test");
  final Memory memory =
      Memory.formed(
          "mem_result",
          "claim",
          "scope",
          "body",
          new Provenance(Instant.EPOCH, "writer", "test"),
          home);
  final EmbeddingWorkRepository.Source output =
      new EmbeddingWorkRepository.Source(
          EmbeddingWorkRepository.Key.of(EmbeddingWorkRepository.Store.MEMORIES, memory.id()),
          1,
          "claim\nscope",
          home.project());
  AgentRegistry registry;
  BoundMemoryWrites writes;
  Runnable afterEmbedding = () -> {};
  Runnable beforeChat = () -> {};
  Runnable beforeCommit = () -> {};
  boolean candidates;

  @BeforeEach
  void setup() {
    when(archive.maxBodyChars()).thenReturn(1000);
    when(memories.pin(any(MemoryWrites.Request.class))).thenReturn(pin);
    when(authority.allows(any(), any(), any())).thenReturn(true);
    when(identities.scope(any()))
        .thenReturn(new AuthorityScope.Project(new AuthorityIds.ScopeId(pin.scope()), 1));
    when(permissions.allows(any(), any(), any())).thenAnswer(i -> allowed.get());
    when(memories.current(memory.id())).thenReturn(memory);
    when(outputs.source(output.key())).thenReturn(Optional.of(output));
    when(sources.openWrite(eq(pin), anyString(), any(), any()))
        .thenAnswer(
            i -> new QuerySources.Source(UUID.randomUUID(), UUID.randomUUID(), i.getArgument(3)));
    when(sources.claim(any()))
        .thenAnswer(
            i -> {
              QuerySources.Source source = i.getArgument(0);
              return new Budget.Claim(source.budget(), ordinals.incrementAndGet(), source.limit());
            });
    when(foundation.authorize(any(), any(), any()))
        .thenAnswer(
            i -> {
              SemanticEmbedding.Operation op = i.getArgument(0);
              Supplier<Budget.Claim> claim = i.getArgument(2);
              var authorization = binding(claim.get(), op.validity());
              lastEmbedding.set(authorization);
              return authorization;
            });
    when(system.admit(any(), any(), any(), any(), anyString(), any()))
        .thenAnswer(
            i -> {
              Runnable valid = i.getArgument(5);
              var work = mock(SystemOperations.Work.class);
              when(work.claimed(any(ChatRequest.class), any()))
                  .thenAnswer(call -> binding(call.getArgument(1), valid));
              return work;
            });
    when(client.forOperation(any()))
        .thenAnswer(
            i -> {
              EmbeddingPermit permit = i.getArgument(0);
              EmbeddingClient bound = mock(EmbeddingClient.class);
              when(bound.embed(anyString(), any()))
                  .thenAnswer(
                      call -> {
                        String text = call.getArgument(0);
                        var authorization =
                            permit.authorize(
                                new EmbeddingPermit.Task(
                                    permit.mode(),
                                    "encoder",
                                    EmbeddingRequest.of("encoder", List.of(text))));
                        authorization.require();
                        embedded.incrementAndGet();
                        afterEmbedding.run();
                        authorization.require();
                        return vector;
                      });
              return bound;
            });
    when(memories.searchByVector(eq(vector), eq(home), eq(5)))
        .thenAnswer(i -> candidates ? List.of(memory) : List.of());
    when(memories.used(any(), any())).thenAnswer(i -> i.getArgument(0));
    when(dispatcher.complete(any()))
        .thenAnswer(
            i -> {
              ChatRequest request = i.getArgument(0);
              beforeChat.run();
              request.authorization().requireRequest(request);
              chatted.incrementAndGet();
              return new Completion(
                  "{\"verdict\":\"new\",\"reason\":\"different claim\"}",
                  "stop",
                  TokenUsage.UNKNOWN,
                  List.of());
            });
    when(archive.applyAdmitted(any(), any(), any(), any()))
        .thenAnswer(
            i -> {
              Verdict verdict = i.getArgument(1);
              Archive.WriteBinding write = i.getArgument(3);
              beforeCommit.run();
              write.mutation(verdict);
              write.provenance(memory.id());
              write.embed(memory);
              return new WriteResult(
                  verdict.kind(), memory.id(), verdict.reason(), verdict.targetId(), List.of());
            });
    registry =
        new AgentRegistry(
            Map.of(
                "scribe",
                new AgentDefinition(
                    "scribe",
                    "Scribe",
                    "model",
                    List.of(),
                    List.of(),
                    List.of(),
                    1,
                    1,
                    "Judge shape")));
    configure(true);
  }

  CallAuthorization binding(Budget.Claim claim, Runnable validity) {
    return new CallAuthorization() {
      public void require() {
        validity.run();
      }

      public InferenceBinding binding() {
        return new InferenceBinding(
            UUID.randomUUID(), UUID.randomUUID(), claim.budget(), claim.ordinal(), claim.limit());
      }
    };
  }

  void configure(boolean scribeAvailable) {
    var scribe = new Scribe(dispatcher, archive, () -> scribeAvailable ? registry : null, system);
    writes =
        new BoundMemoryWrites(
            archive,
            scribe,
            memories,
            sources,
            outputs,
            UnitOfWork.NONE,
            foundation,
            system,
            client,
            () -> null,
            policy::get,
            permissions,
            authority,
            identities);
  }

  MemoryWrites.Request request() {
    return new MemoryWrites.Request(
        "writer",
        home,
        proposal,
        new MemoryWrites.Origin(
            "tool:log:call",
            () -> {
              if (!alive.get()) throw new IllegalStateException("ended");
            },
            () -> {},
            null));
  }

  @Test
  void unlimitedWriteSharesClaimsAndReusesOnlyItsCompletedLegacyVector() {
    candidates = true;
    writes.write(request());
    assertEquals(1, embedded.get());
    assertEquals(1, chatted.get());
    assertEquals(2, ordinals.get());
    verify(sources).openWrite(eq(pin), anyString(), eq("memory-write:tool:log:call"), isNull());
    verify(memories).publishLegacy(any(), eq(pin), eq(output), eq(vector), any());
    verify(sources).close(any());
  }

  @Test
  void disabledWriteRetainsAdmissionAndContentWithoutAnyInference() {
    policy.set(0);
    var result = writes.write(request());
    assertEquals(VerdictKind.NEW, result.kind());
    assertTrue(result.reason().contains("disabled by policy"));
    assertEquals(0, embedded.get());
    assertEquals(0, chatted.get());
    verify(sources).openWrite(eq(pin), anyString(), anyString(), eq(0));
    verify(sources, never()).claim(any());
    verify(memories, never()).publishLegacy(any(), any(), any(), any(), any());
  }

  @Test
  void finitePolicyDoesNotCreateAnotherAllowanceForTheConsult() {
    candidates = true;
    policy.set(1);
    var result = writes.write(request());
    assertEquals(1, embedded.get());
    assertEquals(0, chatted.get());
    assertTrue(result.reason().contains("allowance exhausted"));
    assertEquals(1, ordinals.get());
    verify(memories).publishLegacy(any(), eq(pin), eq(output), eq(vector), any());
  }

  @Test
  void noScribeStillIndexesUnderItsOwnDocumentBinding() {
    configure(false);
    writes.write(request());
    assertEquals(1, embedded.get());
    verify(foundation)
        .authorize(
            argThat(op -> op.purpose() == SystemOperations.Purpose.MEMORY_WRITE),
            argThat(task -> task.mode() == EmbeddingPermit.Mode.DOCUMENT),
            any());
  }

  @Test
  void authorityAndCancellationFailuresCannotBecomeFlatFilingSuccess() {
    afterEmbedding = () -> allowed.set(false);
    assertThrows(CallerFault.class, () -> writes.write(request()));
    verify(archive, never()).applyAdmitted(any(), any(), any(), any());
    allowed.set(true);
    afterEmbedding = () -> alive.set(false);
    assertThrows(IllegalStateException.class, () -> writes.write(request()));
    verify(archive, never()).applyAdmitted(any(), any(), any(), any());
  }

  @Test
  void finalMutationChecksAuthorityEvenWhenOptionalInferenceWasSkipped() {
    policy.set(0);
    beforeCommit = () -> allowed.set(false);
    assertThrows(CallerFault.class, () -> writes.write(request()));
    verify(memories, never()).provenance(any(), any());
    verify(memories, never()).publishLegacy(any(), any(), any(), any(), any());
  }

  @Test
  void unknownQueryOutcomeDoesNotReplayEquivalentLegacyIndexing() {
    afterEmbedding =
        () -> {
          throw new EmbeddingException("uncertain");
        };
    var result = writes.write(request());
    assertEquals(VerdictKind.NEW, result.kind());
    assertEquals(1, embedded.get());
    verify(memories, never()).publishLegacy(any(), any(), any(), any(), any());
  }

  @Test
  void invalidProposalDoesNotOpenAnOperationOrDispatch() {
    var bad =
        new MemoryWrites.Request(
            "writer",
            home,
            new MemoryProposal("claim", "scope", "x".repeat(1001), "by", "where"),
            request().origin());
    assertThrows(ValidationException.class, () -> writes.write(bad));
    verifyNoInteractions(sources, foundation);
  }

  @Test
  void archivePolicyIsExplicitAndRejectsInvalidLimits() {
    var settings = new ArchiveProperties();
    assertNull(settings.writeLimit());
    settings.setWriteBudget("");
    assertNull(settings.writeLimit());
    settings.setWriteBudget("0");
    assertEquals(0, settings.writeLimit());
    settings.setWriteBudget("3");
    assertEquals(3, settings.writeLimit());
    assertThrows(IllegalArgumentException.class, () -> settings.setWriteBudget("-1"));
    assertThrows(IllegalArgumentException.class, () -> settings.setWriteBudget("broken"));
    assertEquals("unlimited", new ArchiveProperties().getWriteBudget());
  }

  @Test
  void unknownDualQueryCannotBeResubmittedAsAnEquivalentDocumentBatch() {
    var lanes = mock(DualEmbeddings.class);
    when(lanes.forQuery(any()))
        .thenAnswer(
            i -> {
              EmbeddingPermit permit = i.getArgument(0);
              var bound = mock(DualEmbeddings.class);
              when(bound.query(any(), anyString(), any()))
                  .thenAnswer(
                      query -> {
                        String text = query.getArgument(1);
                        permit.authorize(
                            new EmbeddingPermit.Task(
                                EmbeddingPermit.Mode.QUERY,
                                "prose-query",
                                EmbeddingRequest.of("encoder", List.of(text))));
                        embedded.incrementAndGet();
                        throw new EmbeddingException("uncertain query outcome");
                      });
              return bound;
            });
    when(lanes.forWrite(any()))
        .thenAnswer(
            i -> {
              EmbeddingAuthorization permit = i.getArgument(0);
              var bound = mock(DualEmbeddings.class);
              when(bound.repair(any(), any()))
                  .thenAnswer(
                      call -> {
                        permit.sources(List.of(output.key()));
                        permit.authorize(
                            new EmbeddingPermit.Task(
                                EmbeddingPermit.Mode.DOCUMENT,
                                "prose-document",
                                EmbeddingRequest.of("encoder", List.of(output.text()))));
                        fail("Equivalent unknown input must not be sent again");
                        return false;
                      });
              return bound;
            });
    var scribe = new Scribe(dispatcher, archive, () -> registry, system);
    writes =
        new BoundMemoryWrites(
            archive,
            scribe,
            memories,
            sources,
            outputs,
            UnitOfWork.NONE,
            foundation,
            system,
            client,
            () -> lanes,
            policy::get,
            permissions,
            authority,
            identities);
    assertEquals(VerdictKind.NEW, writes.write(request()).kind());
    assertEquals(1, embedded.get());
    assertEquals(1, ordinals.get());
    verify(sources, times(1)).claim(any());
  }

  @Test
  void cancellationAfterCommitPreservesFilingButRefusesFinalDisclosure() {
    configure(false);
    afterEmbedding = () -> alive.set(false);
    assertThrows(IllegalStateException.class, () -> writes.write(request()));
    verify(archive).applyAdmitted(any(), any(), any(), any());
    verify(memories, never()).publishLegacy(any(), any(), any(), any(), any());
    verify(sources).close(any());
    assertEquals(1, embedded.get());
  }

  @Test
  void candidatePermitCannotInheritPostCommitSystemContinuation() {
    doAnswer(
            invocation -> {
              assertThrows(IllegalStateException.class, () -> lastEmbedding.get().require());
              return null;
            })
        .when(memories)
        .publishLegacy(any(), any(), any(), any(), any());
    assertEquals(VerdictKind.NEW, writes.write(request()).kind());
    verify(memories).publishLegacy(any(), eq(pin), eq(output), eq(vector), any());
  }

  @Test
  void withdrawnCandidateCannotReachQueuedScribeDispatchOrFiling() {
    candidates = true;
    beforeChat = () -> when(memories.current(memory.id())).thenThrow(new CallerFault("withdrawn"));
    assertThrows(CallerFault.class, () -> writes.write(request()));
    assertEquals(0, chatted.get());
    verify(archive, never()).applyAdmitted(any(), any(), any(), any());
  }
}
