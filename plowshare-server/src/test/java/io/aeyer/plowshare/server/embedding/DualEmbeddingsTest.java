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
  void queryPermitBindsTheFinalProfileAndBatchWithoutAllowingPublication() {
    var profile = profile(EmbeddingSlot.PROSE, "query: ", EmbeddingSpace.Normalization.NONE);
    when(work.active(EmbeddingSlot.PROSE)).thenReturn(Optional.of(profile));
    when(work.inputLimit(profile.space())).thenReturn(100);
    when(dispatcher.embed(any())).thenReturn(new Embeddings(List.of(new float[] {1, 0, 0}), null));
    var permit = mock(EmbeddingPermit.class);
    when(permit.mode()).thenReturn(EmbeddingPermit.Mode.QUERY);
    when(permit.authorize(any(EmbeddingPermit.Task.class))).thenReturn(CallAuthorization.DIRECT);
    var scoped = dual.forQuery(permit);
    scoped.query(profile, "question", owner);
    verify(permit)
        .authorize(
            argThat(
                (EmbeddingPermit.Task task) ->
                    task.sources().equals(List.of("question"))
                        && task.request().input().equals(List.of("query: question"))
                        && task.profile().equals(profile.toString())));
    assertThrows(
        IllegalStateException.class,
        () ->
            scoped.repair(
                EmbeddingWorkRepository.Key.of(
                    EmbeddingWorkRepository.Store.DOCUMENTS, UUID.randomUUID().toString()),
                owner));
    verify(work, never()).source(any());
  }

  @Test
  void queuedQueryRefusesChangedProfileWithoutAnotherProviderAttempt() {
    var profile = profile(EmbeddingSlot.PROSE, "query: ", EmbeddingSpace.Normalization.NONE);
    when(work.inputLimit(profile.space())).thenReturn(100);
    when(work.active(EmbeddingSlot.PROSE)).thenReturn(Optional.empty());
    var permit = mock(EmbeddingPermit.class);
    when(permit.mode()).thenReturn(EmbeddingPermit.Mode.QUERY);
    when(permit.authorize(any(EmbeddingPermit.Task.class))).thenReturn(CallAuthorization.DIRECT);
    when(dispatcher.embed(any()))
        .thenAnswer(
            call -> {
              EmbeddingRequest request = call.getArgument(0);
              request.authorization().requireRequest(request);
              fail("Provider must not receive stale query");
              return null;
            });
    assertThrows(
        EmbeddingException.class, () -> dual.forQuery(permit).query(profile, "question", owner));
    verify(dispatcher, times(1)).embed(any());
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
    verify(work).failed(eq(code), eq(source), eq("InvalidRepairResult"));
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

  @Test
  void idleMaintenanceDoesNotReloadProfilesOrProbeEveryStore() {
    when(work.maintenancePlan(true))
        .thenReturn(new EmbeddingWorkRepository.MaintenancePlan(List.of(), List.of()));
    assertEquals(0, dual.tick());
    verify(work).maintenancePlan(true);
    verifyNoMoreInteractions(work);
    verifyNoInteractions(dispatcher);
  }

  @Test
  void onlySelectedStoreIsClaimedAndProfilesAreNotReloaded() {
    var p = profile(EmbeddingSlot.CODE, "", EmbeddingSpace.Normalization.NONE);
    var selected = new EmbeddingWorkRepository.RepairWork(p, EmbeddingWorkRepository.Store.CHUNKS);
    when(work.maintenancePlan(anyBoolean()))
        .thenReturn(new EmbeddingWorkRepository.MaintenancePlan(List.of(selected), List.of()));
    assertEquals(0, dual.tick());
    verify(work).maintenancePlan(true);
    verify(work).claimPending(p, EmbeddingWorkRepository.Store.CHUNKS, 4);
    verifyNoMoreInteractions(work);
    verifyNoInteractions(dispatcher);
  }

  @Test
  void indexAuditsDoNotFollowEveryQueueTickAndFailedAuditsStayDue() {
    var p = profile(EmbeddingSlot.PROSE, "", EmbeddingSpace.Normalization.NONE);
    when(work.maintenancePlan(true))
        .thenReturn(new EmbeddingWorkRepository.MaintenancePlan(List.of(), List.of(p)));
    when(work.activate(p)).thenReturn(false, true);
    when(work.maintenancePlan(false))
        .thenReturn(new EmbeddingWorkRepository.MaintenancePlan(List.of(), List.of()));
    dual.tick();
    dual.tick();
    dual.tick();
    verify(work, times(2)).maintenancePlan(true);
    verify(work).maintenancePlan(false);
    verify(work, times(2)).activate(p);
    verifyNoMoreInteractions(work);
    verifyNoInteractions(dispatcher);
  }

  @Test
  void successfulIndexAuditBecomesDueAgainAtItsOwnDeadline() {
    var nanos = new java.util.concurrent.atomic.AtomicLong();
    var scheduled = new DispatchingDualEmbeddings(work, dispatcher::embed, tokenizers, nanos::get);
    var p = profile(EmbeddingSlot.PROSE, "", EmbeddingSpace.Normalization.NONE);
    when(work.maintenancePlan(true))
        .thenReturn(new EmbeddingWorkRepository.MaintenancePlan(List.of(), List.of(p)));
    when(work.maintenancePlan(false))
        .thenReturn(new EmbeddingWorkRepository.MaintenancePlan(List.of(), List.of()));
    when(work.activate(p)).thenReturn(true);
    scheduled.tick();
    nanos.set(java.util.concurrent.TimeUnit.MINUTES.toNanos(4));
    scheduled.tick();
    nanos.set(java.util.concurrent.TimeUnit.MINUTES.toNanos(5));
    scheduled.tick();
    verify(work, times(2)).maintenancePlan(true);
    verify(work).maintenancePlan(false);
    verify(work, times(2)).activate(p);
  }

  @Test
  void maintenancePlansRejectUnboundedOrDuplicateWork() {
    var p = profile(EmbeddingSlot.CODE, "", EmbeddingSpace.Normalization.NONE);
    var selected = new EmbeddingWorkRepository.RepairWork(p, EmbeddingWorkRepository.Store.CHUNKS);
    assertThrows(
        IllegalArgumentException.class,
        () -> new EmbeddingWorkRepository.MaintenancePlan(List.of(selected, selected), List.of()));
    assertThrows(NullPointerException.class, () -> new EmbeddingWorkRepository.RepairWork(p, null));
  }

  @Test
  void exhaustedMaintenanceNeverDiscoversSourcesOrAttemptsProviders() {
    when(work.target(EmbeddingSlot.CODE))
        .thenReturn(
            Optional.of(profile(EmbeddingSlot.CODE, "", EmbeddingSpace.Normalization.NONE)));
    var operations = mock(OperationEmbeddings.class);
    dual.useOperations(operations, io.aeyer.plowshare.server.agents.Budget::noCalls);
    assertEquals(0, dual.tick());
    verify(work, never()).claimPending(any(), any(), anyInt());
    verify(work, never()).activate(any());
    verifyNoInteractions(dispatcher, operations);
  }

  private record Retained(
      EmbeddingProfile profile,
      EmbeddingWorkRepository.Source source,
      EmbeddingRepair.Attempt attempt,
      OperationEmbeddings.Publication permit) {}

  private Retained retainedMaintenance() {
    var profile = profile(EmbeddingSlot.PROSE, "", EmbeddingSpace.Normalization.NONE);
    var source =
        new EmbeddingWorkRepository.Source(
            EmbeddingWorkRepository.Key.of(EmbeddingWorkRepository.Store.MEMORIES, "m"),
            1,
            "summary",
            null);
    when(work.maintenancePlan(anyBoolean()))
        .thenReturn(
            new EmbeddingWorkRepository.MaintenancePlan(
                List.of(new EmbeddingWorkRepository.RepairWork(profile, source.key().store())),
                List.of()));
    when(work.claimPending(eq(profile), eq(source.key().store()), anyInt()))
        .thenReturn(List.of(source));
    when(work.inputLimit(profile.space())).thenReturn(100);
    var attempt = new EmbeddingRepair.Attempt(UUID.randomUUID(), profile, source);
    when(work.beginRepairAttempt(profile, source))
        .thenReturn(Optional.of(attempt))
        .thenReturn(Optional.empty());
    var operations = mock(OperationEmbeddings.class);
    var permit = mock(OperationEmbeddings.Publication.class);
    when(permit.authorize(any(EmbeddingPermit.Task.class))).thenReturn(CallAuthorization.DIRECT);
    when(operations.publication(any(), anyList(), any(), any())).thenReturn(permit);
    dual.useOperations(operations, io.aeyer.plowshare.server.agents.Budget::none);
    return new Retained(profile, source, attempt, permit);
  }

  @Test
  void activatedMaintenanceDoesNotAutomaticallyRepeatAnUncertainSubmission() {
    var retained = retainedMaintenance();
    var call = UUID.randomUUID();
    var wire = UUID.randomUUID();
    when(dispatcher.embed(any()))
        .thenAnswer(
            invocation -> {
              EmbeddingRequest request = invocation.getArgument(0);
              request.progress().accounted(new InferenceCapture(call, false));
              request.progress().attempting(wire);
              throw new EmbeddingException("unknown provider outcome");
            });
    assertEquals(0, dual.tick());
    assertEquals(0, dual.tick());
    verify(dispatcher, times(1)).embed(any());
    verify(work)
        .repairFailed(
            eq(retained.attempt()),
            argThat(e -> call.equals(e.callId()) && wire.equals(e.wireAttemptId())),
            eq(EmbeddingRepair.Reason.DISPATCH_FAILED),
            eq(EmbeddingException.class.getName()));
    verify(work, never()).failed(any(), any(), anyString());
  }

  @Test
  void capacityRejectionAndPublicationRevocationKeepTheirDistinctReasons() {
    var retained = retainedMaintenance();
    when(dispatcher.embed(any()))
        .thenThrow(
            new LlmSaturatedException(
                "fixture", Lane.EMBEDDING, java.time.Duration.ofMillis(1), 2));
    assertEquals(0, dual.tick());
    verify(work)
        .repairFailed(
            eq(retained.attempt()),
            any(),
            eq(EmbeddingRepair.Reason.CAPACITY_EXHAUSTED),
            eq(LlmSaturatedException.class.getName()));
    verify(work, never()).publishRepair(any(), any(), any());
    // A separate operation may obtain output but still lose publication authority.
    var second = retainedMaintenance();
    doReturn(new Embeddings(List.of(new float[] {1, 0, 0}), null)).when(dispatcher).embed(any());
    doThrow(new io.aeyer.plowshare.server.faults.CallerFault("fixture revocation"))
        .when(work)
        .publishRepair(eq(second.attempt()), any(), any());
    assertEquals(0, dual.tick());
    verify(work)
        .repairFailed(
            eq(second.attempt()),
            any(),
            eq(EmbeddingRepair.Reason.AUTHORIZATION_REJECTED),
            eq(io.aeyer.plowshare.server.faults.CallerFault.class.getName()));
    verify(work, never()).failed(any(), any(), anyString());
  }

  @Test
  void aValidatedResultWhoseCheckpointFailsIsRecordedAsPublicationFailure() {
    var retained = retainedMaintenance();
    doReturn(new Embeddings(List.of(new float[] {1, 0, 0}), null)).when(dispatcher).embed(any());
    doThrow(new IllegalStateException("fixture checkpoint failure"))
        .when(work)
        .repairProgress(
            eq(retained.attempt()), argThat(p -> p.phase() == EmbeddingRepair.Phase.PUBLISHING));
    assertEquals(0, dual.tick());
    verify(work)
        .repairFailed(
            eq(retained.attempt()),
            any(),
            eq(EmbeddingRepair.Reason.PUBLICATION_FAILED),
            eq(IllegalStateException.class.getName()));
    verify(work, never()).publishRepair(any(), any(), any());
  }
}
