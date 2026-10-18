package io.aeyer.plowshare.server.embedding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.*;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.llm.*;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BoundMemoryQueriesTest {
  final Home home = Home.of("project");
  final MemoryReadRepository memories = mock(MemoryReadRepository.class);
  final QuerySources sources = mock(QuerySources.class);
  final SemanticEmbedding foundation = mock(SemanticEmbedding.class);
  final EmbeddingClient client = mock(EmbeddingClient.class);
  final ScopedPermissions permissions = mock(ScopedPermissions.class);
  final AtomicReference<Integer> policy = new AtomicReference<>();
  final AtomicReference<EmbeddingPermit> permit = new AtomicReference<>();
  final AtomicReference<CallAuthorization> authorization = new AtomicReference<>();
  final AtomicInteger calls = new AtomicInteger();
  final AtomicBoolean alive = new AtomicBoolean(true);
  final QuerySources.Pin pin = new QuerySources.Pin(UUID.randomUUID(), UUID.randomUUID());
  final float[] vector = new float[] {1, 0};
  final Memory memory = mock(Memory.class);
  Runnable after = () -> {};
  BoundMemoryQueries queries;

  @BeforeEach
  void setup() {
    when(permissions.allows(any(), any(), eq(ScopedPermissions.Action.READ))).thenReturn(true);
    when(memories.pin(any()))
        .thenAnswer(
            invocation -> {
              MemoryQueries.Request request = invocation.getArgument(0);
              return new MemoryReadRepository.Selection(
                  pin, request.runBound() ? List.of(home) : List.of(home, Home.global()));
            });
    when(sources.open(eq(pin), anyString(), any(), any()))
        .thenAnswer(
            invocation ->
                new QuerySources.Source(
                    UUID.randomUUID(), UUID.randomUUID(), invocation.getArgument(3)));
    when(sources.claim(any()))
        .thenAnswer(
            invocation -> {
              QuerySources.Source source = invocation.getArgument(0);
              return new Budget.Claim(source.budget(), 1, source.limit());
            });
    when(foundation.authorize(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              SemanticEmbedding.Operation operation = invocation.getArgument(0);
              Supplier<Budget.Claim> charge = invocation.getArgument(2);
              operation.validity().run();
              Budget.Claim claim = charge.get();
              CallAuthorization bound =
                  new CallAuthorization() {
                    public void require() {
                      operation.validity().run();
                    }

                    public InferenceBinding binding() {
                      return new InferenceBinding(
                          UUID.randomUUID(),
                          UUID.randomUUID(),
                          claim.budget(),
                          claim.ordinal(),
                          claim.limit());
                    }
                  };
              authorization.set(bound);
              return bound;
            });
    when(client.forOperation(any()))
        .thenAnswer(
            invocation -> {
              permit.set(invocation.getArgument(0));
              var bound = mock(EmbeddingClient.class);
              when(bound.embed(anyString(), any()))
                  .thenAnswer(
                      call -> {
                        var request =
                            EmbeddingRequest.of(
                                "model", List.of(call.getArgument(0, String.class)));
                        var admitted = permit.get().authorize(request);
                        admitted.require();
                        calls.incrementAndGet();
                        after.run();
                        admitted.require();
                        return vector;
                      });
              return bound;
            });
    when(memories.searchByVector(eq(vector), any(), anyInt())).thenReturn(List.of(memory));
    when(memories.used(eq(memory), any())).thenReturn(memory);
    queries =
        new BoundMemoryQueries(
            memories,
            sources,
            UnitOfWork.NONE,
            foundation,
            client,
            () -> null,
            policy::get,
            Clock.systemUTC(),
            permissions);
  }

  MemoryQueries.Request request(MemoryQueries.Kind kind, boolean runBound, int limit) {
    return new MemoryQueries.Request(
        "reader",
        home,
        kind,
        "query",
        limit,
        runBound,
        new QueryEmbeddings.Origin(
            runBound ? "retained-turn:1" : null,
            () -> {
              if (!alive.get()) throw new IllegalStateException("ended");
            }));
  }

  @Test
  void unlimitedReadRetainsUsageAndRunBoundSelectionDoesNotFallBackToGlobal() {
    after =
        () -> {
          assertThrows(
              IllegalStateException.class,
              () ->
                  permit
                      .get()
                      .authorize(
                          new EmbeddingPermit.Task(
                              EmbeddingPermit.Mode.DOCUMENT,
                              "profile",
                              EmbeddingRequest.of("model", List.of("query")))));
          assertThrows(
              IllegalStateException.class,
              () -> permit.get().authorize(EmbeddingRequest.of("model", List.of("other"))));
        };
    var found = queries.read(request(MemoryQueries.Kind.RECALL, true, 5));
    assertEquals(List.of(memory), found.memories());
    assertEquals(1, calls.get());
    verify(sources).open(eq(pin), anyString(), eq("retained-turn:1"), isNull());
    verify(memories, never()).searchByVector(eq(vector), eq(Home.global()), anyInt());
    verify(memories).used(eq(memory), any());
    verify(sources).close(any());
    assertThrows(IllegalStateException.class, () -> authorization.get().require());
    assertThrows(
        IllegalStateException.class,
        () ->
            permit
                .get()
                .authorize(
                    new EmbeddingPermit.Task(
                        EmbeddingPermit.Mode.DOCUMENT,
                        "profile",
                        EmbeddingRequest.of("model", List.of("query")))));
  }

  @Test
  void reminderSurveyDoesNotCountUsesAndStandaloneReadKeepsAuthorizedTierOrder() {
    queries.read(request(MemoryQueries.Kind.REMINDER, true, 5));
    verify(memories, never()).used(any(), any());
    var found = queries.read(request(MemoryQueries.Kind.RECALL, false, 1));
    assertEquals(1, found.memories().size());
    verify(memories).searchByVector(vector, Home.global(), 1);
  }

  @Test
  void disabledPolicyRefusesBeforeProviderAndLimitedPolicyDoesNotFollowLaterChanges() {
    policy.set(0);
    when(sources.open(eq(pin), anyString(), any(), eq(0)))
        .thenThrow(new IllegalStateException("disabled"));
    assertThrows(
        IllegalStateException.class,
        () -> queries.read(request(MemoryQueries.Kind.RECALL, true, 5)));
    assertEquals(0, calls.get());
    policy.set(1);
    after = () -> policy.set(null);
    queries.read(request(MemoryQueries.Kind.RECALL, true, 5));
    verify(sources).open(eq(pin), anyString(), eq("retained-turn:1"), eq(1));
    assertEquals(1, calls.get());
  }

  @Test
  void scopeLossAndCancellationNeverBecomeEmptySuccessfulDisclosure() {
    after = () -> alive.set(false);
    assertThrows(
        IllegalStateException.class,
        () -> queries.read(request(MemoryQueries.Kind.RECALL, true, 5)));
    verify(memories, never()).searchByVector(eq(vector), any(), anyInt());
    verify(sources).close(any());
    alive.set(true);
    when(permissions.allows(any(), any(), any())).thenReturn(false);
    assertThrows(
        RuntimeException.class, () -> queries.read(request(MemoryQueries.Kind.RECALL, true, 5)));
    assertEquals(1, calls.get());
  }

  @Test
  void cancellationDuringAssemblyIsCheckedBeforeAnyResultLeavesTheRead() {
    when(memories.used(eq(memory), any()))
        .thenAnswer(
            invocation -> {
              alive.set(false);
              return memory;
            });
    assertThrows(
        IllegalStateException.class,
        () -> queries.read(request(MemoryQueries.Kind.RECALL, true, 5)));
    assertEquals(1, calls.get());
    verify(sources).close(any());
  }

  @Test
  void noInferenceShortcutHasNoSourceAndUnboundProductionHelpersStayClosed() {
    assertTrue(queries.read(request(MemoryQueries.Kind.RECALL, true, 0)).memories().isEmpty());
    verifyNoInteractions(client, sources, foundation);
    var archive =
        new Archive(
            mock(MemoryStore.class), mock(ReasonLog.class), client, UnitOfWork.NONE, 1000, 10, 30);
    archive.useQueries(queries);
    assertThrows(IllegalStateException.class, () -> archive.embedding("query"));
    assertThrows(IllegalStateException.class, () -> archive.recall("query", home, 5));
    assertThrows(IllegalStateException.class, () -> archive.survey("query", home, 5));
    assertThrows(
        IllegalStateException.class,
        () -> archive.recall(new Archive.Precomputed(vector, "query"), home, 5));
  }
}
