package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * A real dispatcher lane verifies authorization after queueing, before the transport sees content.
 */
class QueuedAuthorityTest {
  @Test
  void revoked_queued_call_never_reaches_transport_and_accounting_still_closes() throws Exception {
    CountDownLatch occupied = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch queued = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    AtomicBoolean allowed = new AtomicBoolean(true);
    InferenceObserver observer = mock(InferenceObserver.class);
    doAnswer(
            call -> {
              queued.countDown();
              return null;
            })
        .when(observer)
        .queued();
    InferenceAccounting accounting =
        (pool, model, specifier, lane, attribution) ->
            attribution.status()
                    == io.aeyer.plowshare.server.llm.accounting.UsageAttribution.Status.SYSTEM
                ? observer
                : InferenceObserver.NONE;
    LlmTransport transport =
        new LlmTransport() {
          @Override
          public String poolName() {
            return "fixture";
          }

          @Override
          public Completion complete(
              String model, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
            throw new AssertionError("only streaming is used");
          }

          @Override
          public Completion stream(
              String model,
              List<ChatMessage> messages,
              Sampling sampling,
              List<ToolSchema> tools,
              Deltas sink,
              BooleanSupplier abandoned) {
            calls.incrementAndGet();
            occupied.countDown();
            try {
              assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(interrupted);
            }
            return new Completion("answer", "stop", TokenUsage.UNKNOWN, List.of());
          }

          @Override
          public Embeddings embed(String model, List<String> input) {
            throw new AssertionError("no embeddings");
          }

          @Override
          public void close() {}
        };
    var pool =
        new LlmPool(
            "fixture",
            List.of("model-fast"),
            Map.of("fast", "model-fast"),
            1,
            1,
            Duration.ofSeconds(5),
            transport);
    try (var dispatcher =
            new LlmDispatcher(List.of(pool), new NoOpTokenLedger(), type -> "", accounting);
        var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var ordinary = ChatRequest.of("fast", "System", "First");
      var first = threads.submit(() -> dispatcher.stream(ordinary, Deltas.DISCARDING));
      assertTrue(occupied.await(5, TimeUnit.SECONDS));
      var usage =
          io.aeyer.plowshare.server.llm.accounting.UsageAttribution.system(
              null, io.aeyer.plowshare.server.llm.accounting.UsageAttribution.Operation.FOLD);
      var delegated =
          ordinary
              .withAttribution(usage)
              .withAuthorization(
                  () -> {
                    if (!allowed.get()) throw new CallerFault("revoked");
                  });
      var second = threads.submit(() -> dispatcher.stream(delegated, Deltas.DISCARDING));
      assertTrue(queued.await(5, TimeUnit.SECONDS));
      allowed.set(false);
      release.countDown();
      assertEquals("answer", first.get(5, TimeUnit.SECONDS).content());
      assertInstanceOf(
          CallerFault.class,
          assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS)).getCause());
      assertEquals(1, calls.get());
      verify(observer).finished(io.aeyer.plowshare.server.llm.accounting.CallLifecycle.FAILED);
      assertEquals(
          usage, delegated.attribution(), "authorization never rewrites SYSTEM accounting");
    } finally {
      release.countDown();
    }
  }

  @Test
  void every_chat_request_copy_preserves_the_same_authority() {
    CallAuthorization authority =
        () -> {
          throw new CallerFault("denied");
        };
    var request = ChatRequest.of("fast", "System", "Hello").withAuthorization(authority);
    var copied =
        request
            .withBudget(Duration.ofSeconds(1))
            .withSampling(Sampling.NONE)
            .withTools(List.of())
            .withToolChoice(ToolChoice.AUTO)
            .withMessages(request.messages())
            .withAttribution(io.aeyer.plowshare.server.llm.accounting.UsageAttribution.LEGACY);
    assertSame(authority, copied.authorization());
    assertThrows(CallerFault.class, copied.authorization()::require);
  }
}
