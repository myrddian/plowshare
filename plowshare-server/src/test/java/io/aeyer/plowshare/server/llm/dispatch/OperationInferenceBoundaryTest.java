package io.aeyer.plowshare.server.llm.dispatch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Real lane workers with fake transports; evidence never comes from attribution or a model name.
 */
class OperationInferenceBoundaryTest {
  InferenceAdmissions records = mock(InferenceAdmissions.class);
  OperationInference calls = new OperationInference(mock(OperationAuthority.class), records);
  RetainedInferenceBoundary boundary = new RetainedInferenceBoundary(records);

  CallAuthorization permit(Budget budget, AtomicBoolean live) {
    return calls.claimed(
        UUID.randomUUID(),
        budget.tryClaim().orElseThrow(),
        () -> {
          if (!live.get()) throw new CallerFault("revoked");
        });
  }

  LlmDispatcher dispatcher(FakeTransport transport) {
    return new LlmDispatcher(
        List.of(
            new LlmPool("one", List.of("model"), Map.of(), 1, 1, Duration.ofSeconds(5), transport)),
        new NoOpTokenLedger(),
        ignored -> "model",
        InferenceAccounting.NONE,
        null,
        null,
        io.aeyer.plowshare.server.security.ChatFiltering.NONE,
        boundary);
  }

  @Test
  void unclassified_chat_stream_fold_pinned_and_embedding_never_reach_transport() {
    var transport = FakeTransport.free("one");
    try (var dispatcher = dispatcher(transport)) {
      var request = ChatRequest.of("system", null, "untrusted");
      assertThrows(CallerFault.class, () -> dispatcher.complete(request));
      assertThrows(CallerFault.class, () -> dispatcher.stream(request, Deltas.DISCARDING));
      assertThrows(CallerFault.class, () -> dispatcher.streamFold(request, Deltas.DISCARDING));
      assertThrows(
          CallerFault.class,
          () -> dispatcher.streamOn("one", request, Deltas.DISCARDING, () -> false));
      assertThrows(
          CallerFault.class, () -> dispatcher.embed(EmbeddingRequest.of("model", List.of("text"))));
      assertEquals(0, transport.calls.get());
      verifyNoInteractions(records);
    }
  }

  @Test
  void the_last_claim_is_valid_and_queue_rechecks_do_not_spend_again() {
    var budget = Budget.of(1);
    var authorization = permit(budget, new AtomicBoolean(true));
    assertTrue(budget.exhausted());
    assertTrue(budget.tryClaim().isEmpty());
    var transport = FakeTransport.free("one");
    try (var dispatcher = dispatcher(transport)) {
      dispatcher.complete(
          ChatRequest.of("model", null, "hello").withAuthorization(authorization.and(() -> {})));
      assertEquals(1, transport.calls.get());
      assertEquals(1, budget.spent());
      verify(records, times(1)).retain(authorization.binding());
      verify(records, times(1)).submitted(authorization.binding());
      verify(records, atLeast(3)).require(authorization.binding());
    }
  }

  @Test
  void revocation_in_the_lane_prevents_dispatch_and_revocation_after_dispatch_withholds_output()
      throws Exception {
    for (boolean embedding : List.of(false, true)) {
      var live = new AtomicBoolean(true);
      var release = new CountDownLatch(1);
      var transport = new FakeTransport("one", 1, release);
      var authorization = permit(Budget.of(1), live);
      try (var dispatcher = dispatcher(transport);
          var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        Future<?> result =
            executor.submit(
                () -> {
                  if (embedding)
                    dispatcher.embed(
                        EmbeddingRequest.of("model", List.of("private"))
                            .withAuthorization(authorization));
                  else
                    dispatcher.stream(
                        ChatRequest.of("model", null, "private").withAuthorization(authorization),
                        delta -> {
                          fail("private delta escaped");
                        });
                });
        assertTrue(transport.entered.await(5, TimeUnit.SECONDS));
        live.set(false);
        release.countDown();
        assertInstanceOf(
            CallerFault.class,
            assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS))
                .getCause());
        assertEquals(1, transport.calls.get());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void revocation_while_waiting_in_a_real_lane_prevents_the_second_transport_call()
      throws Exception {
    for (boolean embedding : List.of(false, true)) {
      var release = new CountDownLatch(1);
      var transport = new FakeTransport("one", 1, release);
      var pool =
          new LlmPool("one", List.of("model"), Map.of(), 1, 1, Duration.ofSeconds(5), transport);
      var live = new AtomicBoolean(true);
      var first = permit(Budget.of(1), new AtomicBoolean(true));
      var second = permit(Budget.of(1), live);
      try (var dispatcher =
              new LlmDispatcher(
                  List.of(pool),
                  new NoOpTokenLedger(),
                  ignored -> "model",
                  InferenceAccounting.NONE,
                  null,
                  null,
                  io.aeyer.plowshare.server.security.ChatFiltering.NONE,
                  boundary);
          var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        java.util.function.Consumer<CallAuthorization> send =
            authorization -> {
              if (embedding)
                dispatcher.embed(
                    EmbeddingRequest.of("model", List.of("private"))
                        .withAuthorization(authorization));
              else
                dispatcher.complete(
                    ChatRequest.of("model", null, "private").withAuthorization(authorization));
            };
        var active = executor.submit(() -> send.accept(first));
        assertTrue(transport.entered.await(5, TimeUnit.SECONDS));
        var waiting = executor.submit(() -> send.accept(second));
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (pool.queueDepth(embedding ? Lane.EMBEDDING : Lane.CHAT) == 0
            && System.nanoTime() < end) Thread.sleep(2);
        assertEquals(1, pool.queueDepth(embedding ? Lane.EMBEDDING : Lane.CHAT));
        live.set(false);
        release.countDown();
        active.get(5, TimeUnit.SECONDS);
        assertInstanceOf(
            CallerFault.class,
            assertThrows(ExecutionException.class, () -> waiting.get(5, TimeUnit.SECONDS))
                .getCause());
        assertEquals(1, transport.calls.get());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void revoked_claims_and_missing_persisted_evidence_are_refused_before_transport() {
    var live = new AtomicBoolean(true);
    var authorization = permit(Budget.of(2), live);
    var transport = FakeTransport.free("one");
    try (var dispatcher = dispatcher(transport)) {
      live.set(false);
      assertThrows(
          CallerFault.class,
          () ->
              dispatcher.complete(
                  ChatRequest.of("model", null, "hello").withAuthorization(authorization)));
      live.set(true);
      doThrow(new IllegalStateException("missing claim"))
          .when(records)
          .require(authorization.binding());
      assertThrows(
          IllegalStateException.class,
          () ->
              dispatcher.embed(
                  EmbeddingRequest.of("model", List.of("text")).withAuthorization(authorization)));
      assertEquals(0, transport.calls.get());
    }
  }
}
