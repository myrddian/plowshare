package io.aeyer.plowshare.server.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmPool;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransport;
import io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class SwarmSchedulingTest {

  /** Answers every call; the first blocks until the test releases it. */
  private static final class Gate implements LlmTransport {
    final CountDownLatch firstEntered = new CountDownLatch(1);
    final CountDownLatch releaseFirst = new CountDownLatch(1);
    final AtomicInteger calls = new AtomicInteger();

    @Override
    public String poolName() {
      return "spark";
    }

    @Override
    public Completion complete(
        String wireModel, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      if (calls.getAndIncrement() == 0) {
        firstEntered.countDown();
        try {
          if (!releaseFirst.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the gate was never released");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
      return new Completion("done", "stop", TokenUsage.UNKNOWN, List.of());
    }

    @Override
    public Completion stream(
        String wireModel,
        List<ChatMessage> messages,
        Sampling sampling,
        List<ToolSchema> tools,
        Deltas sink,
        BooleanSupplier abandoned) {
      return complete(wireModel, messages, sampling, tools);
    }

    @Override
    public Embeddings embed(String wireModel, List<String> input) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {}
  }

  /**
   * Blocks its first two calls on a release latch (counting entries); every later call is answered
   * at once.
   */
  private static final class TwoHolds implements LlmTransport {
    final CountDownLatch entered = new CountDownLatch(2);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicInteger calls = new AtomicInteger();

    @Override
    public String poolName() {
      return "spark";
    }

    @Override
    public Completion complete(
        String wireModel, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      if (calls.getAndIncrement() < 2) {
        entered.countDown();
        try {
          if (!release.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the gate was never released");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
      return new Completion("done", "stop", TokenUsage.UNKNOWN, List.of());
    }

    @Override
    public Completion stream(
        String wireModel,
        List<ChatMessage> messages,
        Sampling sampling,
        List<ToolSchema> tools,
        Deltas sink,
        BooleanSupplier abandoned) {
      return complete(wireModel, messages, sampling, tools);
    }

    @Override
    public Embeddings embed(String wireModel, List<String> input) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {}
  }

  private static AgentDefinition echo() throws Exception {
    Path fixtures = Path.of(SwarmSchedulingTest.class.getResource("/agents").toURI());
    return AgentRegistry.of(
            fixtures,
            Set.of(
                "probe_read",
                "probe_write",
                AgentRegistry.AGENT_RUN,
                io.aeyer.plowshare.server.agents.MemoryTools.WRITE_NAME))
        .get("echo");
  }

  /**
   * Proves only that a scheduled run waits for the only swarm slot, rather than being failed while
   * it waits — not that the admitted call itself bypasses the pool's submit-timeout, which {@link
   * #an_admitted_scheduled_call_queues_at_the_pool_with_no_deadline_rather_than_meeting_its_submit_timeout}
   * covers: with two chat slots and one swarm slot, a chat-lane thread is free again by the time
   * the second run is admitted, so this test passes whether that call went with {@link
   * LlmPool#NO_DEADLINE} or under the pool's own 50 ms deadline.
   */
  @Test
  void a_scheduled_run_waits_for_the_only_swarm_slot_and_is_answered_when_it_frees()
      throws Exception {
    Gate gate = new Gate();
    // Two chat slots, one swarm slot, and a submit-timeout a test can outwait.
    LlmPool spark =
        new LlmPool(
            "spark",
            List.of("model-fast"),
            Map.of("fast", "model-fast"),
            2,
            1,
            Duration.ofMillis(50),
            gate,
            Set.of(),
            1);
    LlmDispatcher dispatcher = new LlmDispatcher(List.of(spark), new NoOpTokenLedger());
    SwarmScheduler scheduler =
        new SwarmScheduler(
            new DispatcherPools(dispatcher),
            () -> 4,
            () -> Duration.ofMinutes(10),
            Clock.systemUTC(),
            Duration.ofMillis(10));
    AtomicInteger member = new AtomicInteger();
    JobRuntime runtime = new JobRuntime(dispatcher, List.of());
    runtime.useScheduling(
        new SwarmScheduling(
            scheduler,
            context ->
                Optional.of(
                    new SwarmScheduler.Share(
                        "enzo", "topic", "member-" + member.incrementAndGet()))));
    ExecutorService runs = Executors.newVirtualThreadPerTaskExecutor();
    try {
      CompletableFuture<Outcome> first = CompletableFuture.supplyAsync(() -> run(runtime), runs);
      assertTrue(gate.firstEntered.await(5, TimeUnit.SECONDS));
      CompletableFuture<Outcome> second = CompletableFuture.supplyAsync(() -> run(runtime), runs);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (scheduler.snapshot().ready().isEmpty()) {
        assertTrue(System.nanoTime() < deadline, "the second run never queued");
        Thread.sleep(5);
      }
      // Four times the pool's submit-timeout, holding the only swarm slot.
      Thread.sleep(200);
      assertFalse(second.isDone(), "the scheduled run ended while it should be waiting");
      gate.releaseFirst.countDown();
      assertEquals(Ending.ANSWERED, first.get(5, TimeUnit.SECONDS).ending());
      assertEquals(Ending.ANSWERED, second.get(5, TimeUnit.SECONDS).ending());
      assertEquals(2, gate.calls.get());
      assertEquals(0, scheduler.snapshot().pools().get(0).used());
    } finally {
      gate.releaseFirst.countDown();
      runs.shutdownNow();
      spark.close();
    }
  }

  /**
   * The scheduler admits the run at once (its one swarm slot is free), and {@code
   * JobRuntime.streamed} sends that call with {@link LlmPool#NO_DEADLINE} rather than the pool's
   * own 50 ms submit-timeout. Both chat-lane threads are held by two unscheduled calls for the
   * whole test, so the admitted call itself has nowhere to run but the pool's queue — proving the
   * no-deadline budget reaches the submission, not just the scheduler's admission.
   */
  @Test
  void
      an_admitted_scheduled_call_queues_at_the_pool_with_no_deadline_rather_than_meeting_its_submit_timeout()
          throws Exception {
    TwoHolds gate = new TwoHolds();
    // Two chat slots, one swarm slot, and a submit-timeout a test can outwait.
    LlmPool spark =
        new LlmPool(
            "spark",
            List.of("model-fast"),
            Map.of("fast", "model-fast"),
            2,
            1,
            Duration.ofMillis(50),
            gate,
            Set.of(),
            1);
    LlmDispatcher dispatcher = new LlmDispatcher(List.of(spark), new NoOpTokenLedger());
    SwarmScheduler scheduler =
        new SwarmScheduler(
            new DispatcherPools(dispatcher),
            () -> 4,
            () -> Duration.ofMinutes(10),
            Clock.systemUTC(),
            Duration.ofMillis(10));
    JobRuntime runtime = new JobRuntime(dispatcher, List.of());
    runtime.useScheduling(
        new SwarmScheduling(
            scheduler,
            context -> Optional.of(new SwarmScheduler.Share("enzo", "topic", "member"))));
    ExecutorService holders = Executors.newVirtualThreadPerTaskExecutor();
    ExecutorService runs = Executors.newVirtualThreadPerTaskExecutor();
    try {
      // Not scheduled and not shed by its own budget: a generous timeout so the two holders
      // stay put on their own terms, not because a submit-timeout happened to outlast them.
      ChatRequest holdRequest =
          ChatRequest.of("fast", List.of(ChatMessage.user("hold")))
              .withBudget(Duration.ofSeconds(10));
      CompletableFuture<Completion> holderA =
          CompletableFuture.supplyAsync(
              () -> dispatcher.stream(holdRequest, Deltas.DISCARDING, () -> false), holders);
      CompletableFuture<Completion> holderB =
          CompletableFuture.supplyAsync(
              () -> dispatcher.stream(holdRequest, Deltas.DISCARDING, () -> false), holders);
      assertTrue(
          gate.entered.await(5, TimeUnit.SECONDS),
          "both chat-lane threads were never both occupied");

      CompletableFuture<Outcome> scheduled =
          CompletableFuture.supplyAsync(() -> run(runtime), runs);
      // Four times the pool's submit-timeout, both chat-lane threads still held: with the
      // pool's own deadline the admitted call would already have been shed and the run
      // ended UNAVAILABLE (LlmSaturatedException) well before this sleep returns.
      Thread.sleep(200);
      assertFalse(
          scheduled.isDone(),
          "the scheduled run ended while its call should still be queued at the pool");

      gate.release.countDown();
      assertEquals(Ending.ANSWERED, scheduled.get(5, TimeUnit.SECONDS).ending());
      assertEquals("done", holderA.get(5, TimeUnit.SECONDS).content());
      assertEquals("done", holderB.get(5, TimeUnit.SECONDS).content());
    } finally {
      gate.release.countDown();
      runs.shutdownNow();
      holders.shutdownNow();
      spark.close();
    }
  }

  @Test
  void a_run_the_share_function_does_not_name_is_not_scheduled() throws Exception {
    Gate gate = new Gate();
    gate.releaseFirst.countDown();
    LlmPool spark =
        new LlmPool(
            "spark",
            List.of("model-fast"),
            Map.of("fast", "model-fast"),
            2,
            1,
            Duration.ofSeconds(5),
            gate,
            Set.of(),
            1);
    LlmDispatcher dispatcher = new LlmDispatcher(List.of(spark), new NoOpTokenLedger());
    SwarmScheduler scheduler =
        new SwarmScheduler(
            new DispatcherPools(dispatcher),
            () -> 4,
            () -> Duration.ofMinutes(10),
            Clock.systemUTC(),
            Duration.ofMillis(10));
    JobRuntime runtime = new JobRuntime(dispatcher, List.of());
    runtime.useScheduling(new SwarmScheduling(scheduler, context -> Optional.empty()));
    try {
      assertEquals(Ending.ANSWERED, run(runtime).ending());
      assertEquals(0, scheduler.snapshot().pools().get(0).used());
    } finally {
      spark.close();
    }
  }

  @Test
  void the_dispatcher_pools_name_only_pools_with_swarm_slots_that_serve_the_model() {
    LlmPool none =
        new LlmPool(
            "studio",
            List.of("model-fast"),
            Map.of("fast", "model-fast"),
            1,
            1,
            Duration.ofSeconds(5),
            new Gate(),
            Set.of(),
            0);
    LlmPool some =
        new LlmPool(
            "spark",
            List.of("model-fast"),
            Map.of("fast", "model-fast"),
            8,
            1,
            Duration.ofSeconds(5),
            new Gate(),
            Set.of(),
            5);
    DispatcherPools pools =
        new DispatcherPools(new LlmDispatcher(List.of(none, some), new NoOpTokenLedger()));
    try {
      assertEquals(List.of("spark"), pools.serving("fast"));
      assertEquals(List.of(), pools.serving("unknown-model"));
      assertEquals(5, pools.slots("spark"));
      assertEquals(0, pools.slots("studio"));
      assertEquals(List.of("spark"), pools.all());
    } finally {
      none.close();
      some.close();
    }
  }

  private static Outcome run(JobRuntime runtime) {
    try {
      return runtime.run(echo(), "hello", Home.global(), Budget.of(10), null);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void a_delegated_model_without_swarm_slots_is_refused_before_waiting() {
    SwarmScheduler.Pools none =
        new SwarmScheduler.Pools() {
          public List<String> serving(String model) {
            return List.of();
          }

          public int slots(String pool) {
            return 0;
          }

          public List<String> all() {
            return List.of();
          }
        };
    SwarmScheduler scheduler =
        new SwarmScheduler(
            none, () -> 4, () -> Duration.ofMinutes(10), Clock.systemUTC(), Duration.ofMillis(10));
    SwarmScheduling scheduling =
        new SwarmScheduling(
            scheduler,
            context -> Optional.of(new SwarmScheduler.Share("enzo", "topic", "member")),
            none);
    var turn =
        scheduling.forRun(
            new io.aeyer.plowshare.server.agents.RunExtras.Context(null, null, null, null));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class, () -> turn.await("missing-model", () -> false));
  }

  @Test
  void messaging_can_mix_ordinary_and_dedicated_models_in_one_run() {
    SwarmScheduler.Pools pools =
        new SwarmScheduler.Pools() {
          public List<String> serving(String model) {
            return model.equals("dedicated") ? List.of("spark") : List.of();
          }

          public int slots(String pool) {
            return 1;
          }

          public List<String> all() {
            return List.of("spark");
          }
        };
    var scheduler =
        new SwarmScheduler(
            pools, () -> 4, () -> Duration.ofMinutes(10), Clock.systemUTC(), Duration.ofMillis(10));
    var scheduling =
        new SwarmScheduling(
            scheduler,
            context -> Optional.of(new SwarmScheduler.Share("enzo", "instance-topic", "instance")),
            pools,
            context -> true);
    var turn =
        scheduling.forRun(
            new io.aeyer.plowshare.server.agents.RunExtras.Context(null, null, null, null));
    var ordinary = turn.await("ordinary", () -> false);
    org.junit.jupiter.api.Assertions.assertSame(
        io.aeyer.plowshare.server.agents.Scheduling.Slot.ANY, ordinary);
    ordinary.release();
    var dedicated = turn.await("dedicated", () -> false);
    assertEquals("spark", dedicated.pool());
    dedicated.release();
    org.junit.jupiter.api.Assertions.assertSame(
        io.aeyer.plowshare.server.agents.Scheduling.Slot.ANY,
        turn.await("ordinary-fallback", () -> false));
  }
}
