package io.aeyer.plowshare.server.embedding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Existing operations retain their source/policy; neither attribution nor a vector authorizes IO.
 */
class BoundOperationEmbeddingsTest {
  final SemanticEmbedding foundation = mock(SemanticEmbedding.class);
  final EmbeddingWorkRepository outputs = mock(EmbeddingWorkRepository.class);
  final IdentityRepository identities = mock(IdentityRepository.class);
  final Home home = Home.of("project");
  final AuthorityIds.ScopeId scope = new AuthorityIds.ScopeId(UUID.randomUUID());
  final BoundOperationEmbeddings operations =
      new BoundOperationEmbeddings(foundation, outputs, UnitOfWork.NONE, identities);
  final List<Budget.Claim> charged = new ArrayList<>();
  final List<SemanticEmbedding.Operation> admitted = new ArrayList<>();

  @BeforeEach
  void dispatcherBinding() {
    when(identities.project("project")).thenReturn(new AuthorityScope.Project(scope, 1));
    when(foundation.authorize(any(), any(), any()))
        .thenAnswer(
            call -> {
              SemanticEmbedding.Operation operation = call.getArgument(0);
              Supplier<Budget.Claim> claim = call.getArgument(2);
              charged.add(claim.get());
              admitted.add(operation);
              return (CallAuthorization) operation.validity()::run;
            });
  }

  EmbeddingPermit.Task query(String profile, String text) {
    return new EmbeddingPermit.Task(
        EmbeddingPermit.Mode.QUERY,
        profile,
        List.of(text),
        EmbeddingRequest.of("model", List.of("prefix: " + text)));
  }

  @Test
  void exactQueryRetainsTheOwningUnlimitedSourceAndCannotReplayAnAttempt() {
    var budget = Budget.none();
    try (var query = operations.query(home, "question", budget, () -> {})) {
      for (int i = 0; i < 8; i++) query.authorize(query("profile" + i, "question"));
      assertEquals(8, budget.spent());
      assertTrue(charged.stream().allMatch(c -> c.limit() == null));
      assertEquals(1, charged.stream().map(Budget.Claim::budget).distinct().count());
      assertThrows(
          IllegalStateException.class, () -> query.authorize(query("profile0", "question")));
      assertThrows(
          IllegalStateException.class, () -> query.authorize(query("other", "substituted")));
      assertEquals(8, budget.spent());
    }
  }

  @Test
  void queuedValidityAndCloseFenceTheOriginalAuthorization() {
    var alive = new AtomicBoolean(true);
    var query =
        operations.query(
            home,
            "question",
            Budget.of(2),
            () -> {
              if (!alive.get()) throw new IllegalStateException("cancelled");
            });
    var authorization = query.authorize(query("profile", "question"));
    alive.set(false);
    assertThrows(IllegalStateException.class, authorization::require);
    alive.set(true);
    query.close();
    assertThrows(IllegalStateException.class, authorization::require);
  }

  @Test
  void disabledAndLimitedPoliciesCannotBorrowAnotherAllowance() {
    try (var query = operations.query(home, "question", Budget.noCalls(), () -> {})) {
      assertThrows(IllegalStateException.class, () -> query.authorize(query("p", "question")));
    }
    var budget = Budget.of(1);
    try (var query = operations.query(home, "question", budget, () -> {})) {
      query.authorize(query("first", "question"));
      assertThrows(IllegalStateException.class, () -> query.authorize(query("second", "question")));
      assertEquals(1, budget.spent());
    }
  }

  @Test
  void publicationCannotChangeHomeSourceRevisionOrUseAQueryPermit() {
    var source =
        new EmbeddingWorkRepository.Source(
            EmbeddingWorkRepository.Key.of(EmbeddingWorkRepository.Store.DIGESTS, "digest"),
            1,
            "summary",
            "project");
    when(outputs.source(source.key())).thenReturn(Optional.of(source));
    try (var permit = operations.publication(home, List.of(source), Budget.none(), () -> {})) {
      permit.sources(List.of(source.key()));
      assertThrows(IllegalStateException.class, () -> permit.authorize(query("p", "summary")));
      permit.authorize(
          new EmbeddingPermit.Task(
              EmbeddingPermit.Mode.DOCUMENT,
              "p",
              EmbeddingRequest.of("model", List.of("summary"))));
      assertEquals(SystemOperations.Purpose.EMBEDDING_REPAIR, admitted.getFirst().purpose());
      when(outputs.source(source.key()))
          .thenReturn(
              Optional.of(
                  new EmbeddingWorkRepository.Source(source.key(), 2, "changed", "project")));
      assertThrows(IllegalStateException.class, permit::publicationFence);
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> operations.publication(Home.global(), List.of(source), Budget.none(), () -> {}));
  }

  @Test
  void passageMaintenanceIsAnExactDocumentBatchWithoutGeneralPublicationAuthority() {
    try (var batch =
        operations.maintenanceBatch(home, List.of("one", "two"), Budget.none(), () -> {})) {
      assertEquals(EmbeddingPermit.Mode.DOCUMENT, batch.mode());
      assertFalse(batch instanceof EmbeddingAuthorization);
      assertThrows(
          IllegalStateException.class,
          () -> batch.authorize(EmbeddingRequest.of("model", List.of("one"))));
      batch.authorize(EmbeddingRequest.of("model", List.of("one", "two")));
    }
  }
}
