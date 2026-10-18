package io.aeyer.plowshare.server.embedding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.information.*;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class BoundQueryEmbeddingsTest {
  final InformationContext context =
      new InformationContext("reader", InformationContext.Selection.shared());
  final QueryEmbeddingRepository records = mock(QueryEmbeddingRepository.class);
  final InformationAccess access = mock(InformationAccess.class);
  final SemanticEmbedding foundation = mock(SemanticEmbedding.class);
  final QueryEmbeddingRepository.Pin pin =
      new QueryEmbeddingRepository.Pin(UUID.randomUUID(), BuiltInIdentities.ADMINISTRATION.value());
  final QueryEmbeddingRepository.Source source =
      new QueryEmbeddingRepository.Source(UUID.randomUUID(), UUID.randomUUID(), 1);

  BoundQueryEmbeddings service(int allowance) {
    when(records.pin(context)).thenReturn(pin);
    when(records.open(eq(context), eq(pin), anyString(), isNull(), eq(allowance)))
        .thenReturn(source);
    var authorization = mock(CallAuthorization.class);
    when(authorization.binding())
        .thenReturn(
            new InferenceBinding(
                UUID.randomUUID(), UUID.randomUUID(), source.budget(), 1, 1, null));
    when(foundation.authorize(any(), any(), any())).thenReturn(authorization);
    return new BoundQueryEmbeddings(records, access, UnitOfWork.NONE, foundation, () -> allowance);
  }

  @Test
  void emptyReadDoesNotAcquireAnAllowanceAndClosedCapabilityCannotBeReused() {
    var read =
        service(0)
            .open(
                context,
                new QueryEmbeddings.Query(QueryEmbeddings.Kind.SEARCH, "query", null, 5, "HYBRID"),
                QueryEmbeddings.Origin.request(() -> {}));
    assertEquals("empty", read.disclose(() -> "empty"));
    read.close();
    assertThrows(IllegalStateException.class, read::require);
    verify(records, never()).open(any(), any(), anyString(), any(), anyInt());
    verifyNoInteractions(foundation);
  }

  @Test
  void requestAndTaskAreBoundBeforeAnyClaimAndOnlyProviderBatchCosts() {
    var alive = new AtomicBoolean(true);
    var read =
        service(1)
            .open(
                context,
                new QueryEmbeddings.Query(QueryEmbeddings.Kind.STANCE, "claim", null, 1, null),
                QueryEmbeddings.Origin.request(
                    () -> {
                      if (!alive.get()) throw new IllegalStateException("ended");
                    }));
    assertThrows(
        IllegalStateException.class,
        () -> read.authorize(EmbeddingRequest.of("model", List.of("other"))));
    verifyNoInteractions(foundation);
    var batch = EmbeddingRequest.of("model", List.of("query: claim", "query: not claim"));
    read.authorize(
        new EmbeddingPermit.Task(
            EmbeddingPermit.Mode.QUERY, "profile", List.of("claim", "not claim"), batch));
    verify(foundation)
        .authorize(
            argThat(op -> op.purpose() == SystemOperations.Purpose.SEMANTIC_QUERY), any(), any());
    verify(records).bind(eq(source), any(), anyString());
    alive.set(false);
    assertThrows(IllegalStateException.class, read::require);
    read.close();
    verify(records).close(source);
  }

  @Test
  void systemFoundationSharesExecutionWithoutMintingOrExchangingSources() {
    var system = mock(SystemOperations.class);
    var work = mock(SystemOperations.Work.class);
    when(system.admit(any(), any(), any(), any(), anyString(), any())).thenReturn(work);
    var shared = new BoundSemanticEmbedding(system);
    var request = EmbeddingRequest.of("model", List.of("query"));
    var charged = new AtomicInteger();
    for (var purpose :
        List.of(
            SystemOperations.Purpose.DOCUMENT_PREPARATION,
            SystemOperations.Purpose.SEMANTIC_QUERY)) {
      var mode =
          purpose == SystemOperations.Purpose.DOCUMENT_PREPARATION
              ? EmbeddingPermit.Mode.DOCUMENT
              : EmbeddingPermit.Mode.QUERY;
      var operation =
          new SemanticEmbedding.Operation(
              purpose, Home.global(), BuiltInIdentities.ADMINISTRATION, "bound", () -> {});
      shared.authorize(
          operation,
          new EmbeddingPermit.Task(mode, "profile", request),
          () -> new Budget.Claim(UUID.randomUUID(), charged.incrementAndGet(), 5));
      assertThrows(
          IllegalStateException.class,
          () ->
              shared.authorize(
                  operation,
                  new EmbeddingPermit.Task(
                      mode == EmbeddingPermit.Mode.QUERY
                          ? EmbeddingPermit.Mode.DOCUMENT
                          : EmbeddingPermit.Mode.QUERY,
                      "profile",
                      request),
                  () -> {
                    fail("No claim for substituted task");
                    return null;
                  }));
    }
    assertEquals(2, charged.get());
    verify(work, times(2)).claimed(eq(request), any(Budget.Claim.class));
  }

  @Test
  void searchConfigurationIsIndependentOfEncoderModeAndRefusesNegativeLimits() {
    assertEquals(0, new EmbeddingProperties(false, null, null, false, null).searchBudget());
    assertEquals(1, new EmbeddingProperties(false, null, null, false, null, 1).searchBudget());
    assertThrows(
        IllegalArgumentException.class,
        () -> new EmbeddingProperties(false, null, null, false, null, -1));
  }
}
