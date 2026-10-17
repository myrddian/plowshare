package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Root authority is retained; materialized input and model-only bounds do not become account
 * grants.
 */
class BoundSystemOperationsTest {
  IdentityRepository identities = mock(IdentityRepository.class);
  ScopedGrantRepository grants = mock(ScopedGrantRepository.class);
  ScopedAuthority authority = mock(ScopedAuthority.class);
  SystemOperationRecords records = mock(SystemOperationRecords.class);
  InferenceAdmissions inference = mock(InferenceAdmissions.class);
  AgentDefinition definition =
      new AgentDefinition(
          "worker", "worker", "model", List.of(), List.of(), List.of(), 2, 2, "Bounded task");
  BoundSystemOperations service =
      new BoundSystemOperations(
          identities,
          grants,
          authority,
          records,
          new OperationInference(mock(OperationAuthority.class), inference));
  UUID root = UUID.randomUUID();

  BoundSystemOperationsTest() {
    var scope =
        new AuthorityScope.AccountKnowledge(
            BuiltInIdentities.GLOBAL_KNOWLEDGE, BuiltInIdentities.GLOBAL);
    when(identities.scope(scope.id())).thenReturn(scope);
    var binding = mock(ScopedGrantRepository.Binding.class);
    when(binding.id()).thenReturn(root);
    when(binding.role()).thenReturn(StandardRole.SYSTEM);
    when(grants.live(GrantRecipient.account(BuiltInIdentities.SYSTEM)))
        .thenReturn(List.of(binding));
    when(authority.allowsUsing(
            eq(GrantRecipient.account(BuiltInIdentities.SYSTEM)), eq(scope), any(), eq(root)))
        .thenReturn(true);
  }

  @Test
  void embeddingClaimsBindInputsAndModelAndCannotBeUsedByOtherPurposes() {
    var work =
        service.admit(
            SystemOperations.Purpose.DOCUMENT_PREPARATION,
            Home.global(),
            definition,
            "batch",
            () -> {});
    var request = EmbeddingRequest.of("model", List.of("exact preprocessed input"));
    var permit = work.claimed(request, Budget.of(1).tryClaim().orElseThrow());
    permit.requireRequest(request);
    assertThrows(
        CallerFault.class,
        () -> permit.requireRequest(EmbeddingRequest.of("model", List.of("other input"))));
    assertThrows(
        CallerFault.class,
        () -> permit.requireRequest(EmbeddingRequest.of("other", request.input())));
    var other =
        service.admit(
            SystemOperations.Purpose.MEMORY_DIGEST, Home.global(), definition, "batch", () -> {});
    assertThrows(
        CallerFault.class, () -> other.claimed(request, Budget.of(1).tryClaim().orElseThrow()));
    when(authority.allowsUsing(any(), any(), any(), eq(root))).thenReturn(false);
    assertThrows(CallerFault.class, permit::require);
  }

  @Test
  void intrinsicSystemCanProcessGlobalWithBoundInputsAndNoCreatorGrant() {
    var work =
        service.admit(
            SystemOperations.Purpose.DOCUMENT_PREPARATION,
            Home.global(),
            definition,
            "source-revision",
            () -> {});
    work.requireTask(Home.global(), definition, "source-revision");
    var request = ChatRequest.of("model", null, "materialized source");
    var permit = work.claimed(request, Budget.of(1).tryClaim().orElseThrow());
    assertNotNull(permit.binding());
    permit.require();
    verify(records)
        .retain(
            argThat(
                e ->
                    e.grant().equals(root)
                        && e.scope().equals(BuiltInIdentities.GLOBAL_KNOWLEDGE)));
    verify(identities, never()).account(any());
    assertThrows(
        CallerFault.class, () -> work.requireTask(Home.of("other"), definition, "source-revision"));
    assertThrows(
        CallerFault.class, () -> work.requireTask(Home.global(), definition, "different source"));
    assertThrows(
        CallerFault.class,
        () -> permit.requireRequest(ChatRequest.of("model", null, "unrelated secret")));
    assertThrows(
        CallerFault.class,
        () ->
            work.claimed(
                ChatRequest.of("other", null, "text"), Budget.of(1).tryClaim().orElseThrow()));
  }

  @Test
  void systemRuntimePreservesWorkerHooksWithoutBorrowingCreatorAuthority() {
    var work =
        service.admit(
            SystemOperations.Purpose.DOCUMENT_PREPARATION,
            Home.global(),
            definition,
            "materialized source",
            () -> {});
    var dispatcher = mock(LlmDispatcher.class);
    when(dispatcher.stream(any(), any(), any()))
        .thenAnswer(
            call -> {
              ChatRequest request = call.getArgument(0);
              request.authorization().requireRequest(request);
              assertNotNull(request.authorization().binding());
              return new Completion("prepared", "stop", TokenUsage.UNKNOWN, List.of());
            });
    var ordinary = mock(OperationAuthority.class);
    var runtime = new JobRuntime(ordinary, dispatcher, List.of());
    var harness = mock(io.aeyer.plowshare.server.harness.Harness.class);
    when(harness.begin())
        .thenAnswer(call -> io.aeyer.plowshare.server.harness.Harness.NONE.begin());
    runtime.useHarness(harness);
    var result =
        runtime
            .forSystemWork(work)
            .run(definition, "materialized source", Home.global(), Budget.of(1), null);
    assertEquals(Outcome.Ending.ANSWERED, result.ending());
    verify(harness).begin();
    verifyNoInteractions(ordinary);
  }

  @Test
  void operationInvalidationAndOriginalRootLossStopFurtherInference() {
    var valid = new AtomicBoolean(true);
    var work =
        service.admit(
            SystemOperations.Purpose.DOCUMENT_PREPARATION,
            Home.global(),
            definition,
            "revision",
            () -> {
              if (!valid.get()) throw new CallerFault("stale generation");
            });
    valid.set(false);
    assertThrows(CallerFault.class, work::require);
    valid.set(true);
    when(authority.allowsUsing(any(), any(), any(), eq(root))).thenReturn(false);
    assertThrows(CallerFault.class, work::require);
    verify(inference, never()).retain(any());
  }

  @Test
  void systemIdentityAndUsageMetadataCannotReplaceMissingRootAuthority() {
    when(grants.live(any())).thenReturn(List.of());
    assertThrows(
        CallerFault.class,
        () ->
            service.admit(
                SystemOperations.Purpose.DOCUMENT_PREPARATION,
                Home.global(),
                definition,
                "revision",
                () -> {}));
    verifyNoInteractions(records, inference);
  }

  @Test
  void workerConfigurationCannotAcquireToolsOrDelegation() {
    var tools =
        new AgentDefinition(
            "worker",
            "worker",
            "model",
            List.of("memory_read"),
            List.of(),
            List.of(),
            1,
            1,
            "task");
    assertThrows(
        IllegalStateException.class,
        () ->
            service.admit(
                SystemOperations.Purpose.DOCUMENT_PREPARATION,
                Home.global(),
                tools,
                "revision",
                () -> {}));
    verifyNoInteractions(records, inference);
  }
}
