package io.aeyer.plowshare.server.llm.dispatch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.llm.accounting.CallLifecycle;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class InferenceProgressTest {
  @Test
  void retainsAccountingIdentityAndChecksAuthorityBeforeProgressAndTransport() {
    var accounting = mock(InferenceObserver.class);
    var wire = mock(InferenceObserver.Attempt.class);
    var progress = mock(InferenceProgress.class);
    var authority = mock(CallAuthorization.class);
    var call = new InferenceCapture(UUID.randomUUID(), false);
    var id = UUID.randomUUID();
    when(accounting.capture()).thenReturn(call);
    when(accounting.attempt()).thenReturn(wire);
    when(wire.id()).thenReturn(id);
    var observed =
        InferenceObserver.authorized(InferenceObserver.progress(accounting, progress), authority);
    assertSame(call, observed.capture());
    observed.queued();
    observed.started();
    observed.beforeDispatch();
    var attempt = observed.attempt();
    assertEquals(id, attempt.id());
    attempt.response(403, "request-id");
    attempt.finished(CallLifecycle.REFUSED);
    observed.finished(CallLifecycle.REFUSED);
    var order = inOrder(accounting, progress, authority, wire);
    order.verify(progress).accounted(call);
    order.verify(progress).queued();
    order.verify(accounting).queued();
    order.verify(accounting).started();
    order.verify(authority).require();
    order.verify(accounting).beforeDispatch();
    order.verify(progress).started();
    order.verify(accounting).attempt();
    order.verify(progress).attempting(id);
    order.verify(wire).response(403, "request-id");
    order.verify(progress).response(403);
    order.verify(wire).finished(CallLifecycle.REFUSED);
    order.verify(accounting).finished(CallLifecycle.REFUSED);
    order.verify(progress).finished(CallLifecycle.REFUSED);
  }

  @Test
  void failedDurableCheckpointNeverReachesTransportAndClosesItsAccountingAttempt() {
    var accounting = mock(InferenceObserver.class);
    var wire = mock(InferenceObserver.Attempt.class);
    var progress = mock(InferenceProgress.class);
    var id = UUID.randomUUID();
    when(accounting.attempt()).thenReturn(wire);
    when(wire.id()).thenReturn(id);
    var failure = new IllegalStateException("checkpoint unavailable");
    doThrow(failure).when(progress).attempting(id);
    var observed = InferenceObserver.progress(accounting, progress);
    var called = new AtomicBoolean();
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () ->
                InferenceObserver.once(
                    observed,
                    () -> {
                      called.set(true);
                      return "result";
                    },
                    v -> null,
                    v -> null)));
    assertFalse(called.get());
    verify(wire).finished(CallLifecycle.FAILED);
  }

  @Test
  void failedPreQueueCheckpointClosesTheCallWithoutSubmittingWork() {
    var accounting = mock(InferenceObserver.class);
    var progress = mock(InferenceProgress.class);
    doThrow(new IllegalStateException("checkpoint unavailable")).when(progress).queued();
    var observed = InferenceObserver.progress(accounting, progress);
    assertThrows(IllegalStateException.class, observed::queued);
    verify(accounting).finished(CallLifecycle.NOT_DISPATCHED);
    verify(accounting, never()).queued();
    verify(accounting, never()).attempt();
  }

  @Test
  void revokedWorkerAuthorityCannotBeReplacedByProgressOrItsAccountingReceipt() {
    var accounting = mock(InferenceObserver.class);
    var progress = mock(InferenceProgress.class);
    var authority = mock(CallAuthorization.class);
    doThrow(new io.aeyer.plowshare.server.faults.CallerFault("fixture revocation"))
        .when(authority)
        .require();
    var observed =
        InferenceObserver.authorized(InferenceObserver.progress(accounting, progress), authority);
    assertThrows(io.aeyer.plowshare.server.faults.CallerFault.class, observed::beforeDispatch);
    verify(progress, never()).started();
    verify(accounting, never()).attempt();
    verify(progress, never()).attempting(any());
  }

  @Test
  void requestDecoratorsRetainTheSameProgressAndAuthority() {
    var progress = mock(InferenceProgress.class);
    var authority = mock(CallAuthorization.class);
    var request =
        EmbeddingRequest.of("fixture", java.util.List.of("input"))
            .withProgress(progress)
            .withAuthorization(authority)
            .withBudget(java.time.Duration.ofSeconds(1))
            .withAttribution(
                io.aeyer.plowshare.server.llm.accounting.UsageAttribution.system(
                    null,
                    io.aeyer.plowshare.server.llm.accounting.UsageAttribution.Operation
                        .EMBEDDING_REPAIR));
    assertSame(progress, request.progress());
    assertSame(authority, request.authorization());
  }
}
