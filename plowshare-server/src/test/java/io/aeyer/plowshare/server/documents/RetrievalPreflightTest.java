package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.embedding.*;
import io.aeyer.plowshare.server.information.*;
import io.aeyer.plowshare.server.llm.EmbeddingClient;
import io.aeyer.plowshare.server.llm.EmbeddingException;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Empty retrieval avoids inference; nonempty and refused profiles retain normal live fences. */
class RetrievalPreflightTest {
  final DocumentStore store = mock(DocumentStore.class);
  final EmbeddingClient client = mock(EmbeddingClient.class);
  final RetrievalService retrieval = new RetrievalService(store, client, "legacy", 2);
  final DualEmbeddings dual = mock(DualEmbeddings.class);
  final EmbeddingProfile profile =
      new EmbeddingProfile(
          EmbeddingSlot.PROSE,
          new EmbeddingSpace(
              "a".repeat(64),
              new EmbeddingSpace.Definition(
                  "model",
                  "revision",
                  2,
                  "",
                  "",
                  "mean",
                  EmbeddingSpace.Normalization.NONE,
                  "none")),
          new EmbeddingSearchPolicy(
              EmbeddingSearchPolicy.Mode.EXACT, EmbeddingSearchPolicy.Distance.COSINE),
          7);

  void dual() {
    when(dual.active(EmbeddingSlot.PROSE)).thenReturn(profile);
    when(dual.read(eq(profile), any()))
        .thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
    when(store.profiled(profile)).thenReturn(store);
    when(store.queried(any())).thenReturn(store);
    when(dual.queries(eq(profile), anyList(), any()))
        .thenReturn(List.of(new EmbeddingQuery(profile, new float[] {1, 0})));
    retrieval.useDualEmbeddings(dual);
  }

  @Test
  void emptyAdmittedSelectionDisclosesUnderLiveAuthorityWithoutMintingAnInferenceSource() {
    var access = mock(InformationAccess.class);
    var records = mock(QueryEmbeddingRepository.class);
    var foundation = mock(SemanticEmbedding.class);
    var context = new InformationContext("reader", InformationContext.Selection.personal());
    when(records.pin(context))
        .thenReturn(new QuerySources.Pin(UUID.randomUUID(), UUID.randomUUID()));
    when(store.scoped(access, context)).thenReturn(store);
    when(client.forOperation(any())).thenReturn(client);
    retrieval.useQueries(
        new BoundQueryEmbeddings(records, access, UnitOfWork.NONE, foundation, () -> 0));
    var reader =
        retrieval.scoped(access, context).forQueries(QueryEmbeddings.Origin.request(() -> {}));
    assertTrue(reader.retrieve("question", null, 5).isEmpty());
    verify(store).hasRetrievable(null);
    verify(records).lockRead(eq(context), any(), isNull());
    verify(records, never()).open(any(), any(), anyString(), any(), any());
    verify(records, never()).claim(any());
    verifyNoInteractions(foundation);
    verify(client, never()).embed(anyString(), any());
    verify(store, never()).retrieve(any(), any(), anyInt());
  }

  @Test
  void nonemptyLegacySelectionEmbedsOnceThenUsesTheNormalResultRead() {
    UUID document = UUID.randomUUID();
    when(store.hasRetrievable(document)).thenReturn(true);
    when(client.embed("question")).thenReturn(new float[] {1, 0});
    retrieval.retrieve("question", document, 5);
    var ordered = inOrder(store, client);
    ordered.verify(store).hasRetrievable(document);
    ordered.verify(client).embed("question");
    ordered.verify(store).retrieve(eq(document), any(), eq(5));
  }

  @Test
  void emptyDualSelectionUsesOneCapturedProfileAndRetainsItsDisclosureFence() {
    dual();
    assertTrue(retrieval.retrieve("question", null, 5).isEmpty());
    verify(dual).active(EmbeddingSlot.PROSE);
    verify(dual, times(2)).read(eq(profile), any());
    verify(store).profiled(profile);
    verify(dual, never()).queries(any(), any(), any());
    verifyNoInteractions(client);
  }

  @Test
  void nonemptyDualSelectionCapturesItsProfileOnceAndDoesNotRepeatTheExistenceRead() {
    dual();
    when(store.hasRetrievable(null)).thenReturn(true);
    retrieval.retrieve("question", null, 5);
    verify(dual).active(EmbeddingSlot.PROSE);
    verify(dual).queries(eq(profile), eq(List.of("question")), any());
    verify(store).hasRetrievable(null);
    verify(store).retrieve(isNull(), any(), eq(5));
    verifyNoInteractions(client);
  }

  @Test
  void unavailableOrChangedProfileIsRefusedInsteadOfFabricatingAnEmptyAnswer() {
    dual();
    when(dual.active(EmbeddingSlot.PROSE)).thenThrow(new EmbeddingException("slot is rebuilding"));
    assertThrows(EmbeddingException.class, () -> retrieval.retrieve("question", null, 5));
    verify(store, never()).hasRetrievable(any());
    doReturn(profile).when(dual).active(EmbeddingSlot.PROSE);
    when(dual.read(eq(profile), any()))
        .thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get())
        .thenThrow(new EmbeddingException("configuration changed"));
    assertThrows(EmbeddingException.class, () -> retrieval.retrieve("question", null, 5));
    verify(dual, never()).queries(any(), any(), any());
  }
}
