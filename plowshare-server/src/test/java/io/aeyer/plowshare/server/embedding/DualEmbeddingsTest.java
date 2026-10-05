package io.aeyer.plowshare.server.embedding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.llm.EmbeddingException;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.*;
import io.aeyer.plowshare.server.llm.tokens.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DualEmbeddingsTest {
  private final EmbeddingWorkRepository work = mock(EmbeddingWorkRepository.class);
  private final LlmDispatcher dispatcher = mock(LlmDispatcher.class);
  private final Tokenizer tokenizer =
      new Tokenizer() {
        public TokenCount count(String text) {
          return TokenCount.measured(text.length(), "fixture characters");
        }

        public String describe() {
          return "fixture";
        }
      };
  private final EmbeddingTokenizers tokenizers = mock(EmbeddingTokenizers.class);

  {
    when(tokenizers.model(any())).thenReturn(tokenizer);
  }

  private final DispatchingDualEmbeddings dual =
      new DispatchingDualEmbeddings(work, dispatcher::embed, tokenizers);
  private final UsageAttribution owner =
      UsageAttribution.system(null, UsageAttribution.Operation.EMBEDDING_WRITE);

  private static EmbeddingProfile profile(
      EmbeddingSlot slot, String prefix, EmbeddingSpace.Normalization normalization) {
    return new EmbeddingProfile(
        slot,
        new EmbeddingSpace(
            (slot == EmbeddingSlot.CODE ? "a" : "b").repeat(64),
            new EmbeddingSpace.Definition(
                slot.stored() + "-encoder",
                "exact-weights",
                3,
                prefix,
                "document: ",
                "cls",
                normalization,
                "none")),
        new EmbeddingSearchPolicy(
            EmbeddingSearchPolicy.Mode.EXACT, EmbeddingSearchPolicy.Distance.COSINE),
        1);
  }

  @Test
  void queryPrefixAndOwnerAreAppliedOnceAndOutputsAreImmutable() {
    var profile = profile(EmbeddingSlot.CODE, "query: ", EmbeddingSpace.Normalization.L2);
    when(work.inputLimit(profile.space())).thenReturn(100);
    float[] output = {3, 4, 0};
    when(dispatcher.embed(any())).thenReturn(new Embeddings(List.of(output), null));
    var query = dual.query(profile, "find code", owner);
    var request = org.mockito.ArgumentCaptor.forClass(EmbeddingRequest.class);
    verify(dispatcher).embed(request.capture());
    assertEquals(List.of("query: find code"), request.getValue().input());
    assertEquals("code-encoder", request.getValue().specifier());
    assertEquals(
        UsageAttribution.Operation.EMBEDDING_QUERY, request.getValue().attribution().operation());
    assertEquals(owner.status(), request.getValue().attribution().status());
    assertArrayEquals(new float[] {0.6f, 0.8f, 0}, query.values());
    output[0] = 999;
    query.values()[0] = 999;
    assertEquals(0.6f, query.values()[0]);
  }

  @Test
  void transformedInputsAreCheckedBeforeDispatch() {
    var profile = profile(EmbeddingSlot.PROSE, "long prefix: ", EmbeddingSpace.Normalization.NONE);
    when(work.inputLimit(profile.space())).thenReturn(10);
    assertThrows(EmbeddingException.class, () -> dual.query(profile, "x", owner));
    verifyNoInteractions(dispatcher);
  }

  @Test
  void estimatedCountsAreRefusedBeforeAnyDispatch() {
    var profile = profile(EmbeddingSlot.CODE, "", EmbeddingSpace.Normalization.NONE);
    when(work.inputLimit(profile.space())).thenReturn(2048);
    when(tokenizers.model(any())).thenReturn(new RatioTokenizer(4));
    assertThrows(EmbeddingException.class, () -> dual.query(profile, "dense".repeat(1000), owner));
    verifyNoInteractions(dispatcher);
  }

  @Test
  void everyReturnedVectorIsValidated() {
    var profile = profile(EmbeddingSlot.PROSE, "", EmbeddingSpace.Normalization.NONE);
    when(work.inputLimit(profile.space())).thenReturn(100);
    when(dispatcher.embed(any()))
        .thenReturn(new Embeddings(List.of(new float[] {1, 0, 0}, new float[] {1, 0}), null));
    assertThrows(EmbeddingException.class, () -> dual.queries(profile, List.of("a", "b"), owner));
    when(dispatcher.embed(any()))
        .thenReturn(new Embeddings(List.of(new float[] {1, Float.NaN, 0}), null));
    assertThrows(EmbeddingException.class, () -> dual.query(profile, "a", owner));
    when(dispatcher.embed(any())).thenReturn(new Embeddings(List.of(new float[] {0, 0, 0}), null));
    assertThrows(EmbeddingException.class, () -> dual.query(profile, "a", owner));
  }

  @Test
  void aCurrentSlotIsNeverRecomputedDuringSourceRepair() {
    var source =
        new EmbeddingWorkRepository.Source(
            EmbeddingWorkRepository.Key.of(EmbeddingWorkRepository.Store.MEMORIES, "memory"),
            2,
            "retained source",
            null);
    when(work.source(source.key())).thenReturn(Optional.of(source));
    var code = profile(EmbeddingSlot.CODE, "", EmbeddingSpace.Normalization.NONE);
    var prose = profile(EmbeddingSlot.PROSE, "", EmbeddingSpace.Normalization.NONE);
    when(work.target(EmbeddingSlot.CODE)).thenReturn(Optional.of(code));
    when(work.target(EmbeddingSlot.PROSE)).thenReturn(Optional.of(prose));
    when(work.ready(prose, source)).thenReturn(true);
    when(work.inputLimit(code.space())).thenReturn(100);
    when(dispatcher.embed(any())).thenReturn(new Embeddings(List.of(new float[] {1, 0, 0}), null));
    when(work.publish(eq(code), eq(source), any())).thenReturn(true);
    assertTrue(dual.repair(source.key(), owner));
    verify(work, never()).publish(eq(prose), any(), any());
    verify(dispatcher, times(1))
        .embed(
            argThat(
                r ->
                    r.specifier().equals("code-encoder")
                        && r.input().equals(List.of("document: retained source"))));
  }

  @Test
  void failureInOneSlotDoesNotPreventPublicationInTheOther() {
    var source =
        new EmbeddingWorkRepository.Source(
            EmbeddingWorkRepository.Key.of(EmbeddingWorkRepository.Store.MEMORIES, "memory"),
            2,
            "text",
            null);
    when(work.source(source.key())).thenReturn(Optional.of(source));
    for (EmbeddingSlot slot : EmbeddingSlot.values()) {
      var profile = profile(slot, "", EmbeddingSpace.Normalization.NONE);
      when(work.target(slot)).thenReturn(Optional.of(profile));
      when(work.inputLimit(profile.space())).thenReturn(100);
    }
    when(dispatcher.embed(any()))
        .thenAnswer(
            call -> {
              EmbeddingRequest request = call.getArgument(0);
              return new Embeddings(
                  List.of(
                      request.specifier().equals("code-encoder")
                          ? new float[] {0, 0, 0}
                          : new float[] {1, 0, 0}),
                  null);
            });
    assertThrows(EmbeddingException.class, () -> dual.repair(source.key(), owner));
    var code = work.target(EmbeddingSlot.CODE).orElseThrow();
    var prose = work.target(EmbeddingSlot.PROSE).orElseThrow();
    verify(work).failed(eq(code), eq(source), eq("EmbeddingException"));
    verify(work).publish(eq(prose), eq(source), any());
  }

  @Test
  void aRebuildingSlotRepairsServingAndReplacementWithoutRecomputingTheOtherSlot() {
    var source =
        new EmbeddingWorkRepository.Source(
            EmbeddingWorkRepository.Key.of(
                EmbeddingWorkRepository.Store.CHUNKS, UUID.randomUUID().toString()),
            3,
            "source",
            null);
    var serving = profile(EmbeddingSlot.CODE, "", EmbeddingSpace.Normalization.NONE);
    var replacement =
        new EmbeddingProfile(
            EmbeddingSlot.CODE,
            new EmbeddingSpace(
                "c".repeat(64),
                new EmbeddingSpace.Definition(
                    "replacement-encoder",
                    "new-weights",
                    3,
                    "",
                    "document: ",
                    "cls",
                    EmbeddingSpace.Normalization.NONE,
                    "none")),
            serving.search(),
            serving.version());
    var prose = profile(EmbeddingSlot.PROSE, "", EmbeddingSpace.Normalization.NONE);
    when(work.source(source.key())).thenReturn(Optional.of(source));
    when(work.active(EmbeddingSlot.CODE)).thenReturn(Optional.of(serving));
    when(work.target(EmbeddingSlot.CODE)).thenReturn(Optional.of(replacement));
    when(work.active(EmbeddingSlot.PROSE)).thenReturn(Optional.of(prose));
    when(work.target(EmbeddingSlot.PROSE)).thenReturn(Optional.of(prose));
    when(work.ready(prose, source)).thenReturn(true);
    when(work.inputLimit(any())).thenReturn(100);
    when(work.publish(any(), eq(source), any())).thenReturn(true);
    when(dispatcher.embed(any())).thenReturn(new Embeddings(List.of(new float[] {1, 0, 0}), null));
    var result = dual.repairAll(List.of(source.key()), owner);
    assertEquals(new DualEmbeddings.RepairResult(1, 2), result);
    var requests = org.mockito.ArgumentCaptor.forClass(EmbeddingRequest.class);
    verify(dispatcher, times(2)).embed(requests.capture());
    assertEquals(
        List.of("code-encoder", "replacement-encoder"),
        requests.getAllValues().stream().map(EmbeddingRequest::specifier).toList());
    verify(work).publish(eq(serving), eq(source), any());
    verify(work).publish(eq(replacement), eq(source), any());
    verify(work, never()).publish(eq(prose), any(), any());
  }

  @Test
  void configurationRequiresBothCompleteModelsAndAnAllowedIndexChoice() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new EmbeddingProperties(true, null, null, false, List.of()).validate());
    var unsupported =
        new EmbeddingProperties.Model(
            "model",
            "weights",
            4096,
            "",
            "",
            "cls",
            EmbeddingSpace.Normalization.L2,
            "none",
            512,
            "hnsw_half",
            EmbeddingSearchPolicy.Distance.COSINE,
            null);
    assertThrows(IllegalArgumentException.class, unsupported::definition);
    var supported =
        new EmbeddingProperties.Model(
            "model",
            "weights",
            4096,
            "",
            "",
            "cls",
            EmbeddingSpace.Normalization.L2,
            "none",
            512,
            "hnsw_binary",
            EmbeddingSearchPolicy.Distance.COSINE,
            null);
    assertEquals(4096, supported.definition().dimensions());
  }
}
