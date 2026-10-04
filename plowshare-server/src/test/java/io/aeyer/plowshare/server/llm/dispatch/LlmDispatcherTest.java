package io.aeyer.plowshare.server.llm.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** Routing: a specifier in, a pool and a wire model out. */
class LlmDispatcherTest {

  @Test
  void global_prompt_timeouts_reach_every_dispatch_path_and_folds_take_the_larger_budget() {
    for (Duration fold : List.of(Duration.ofSeconds(20), Duration.ofSeconds(160))) {
      FakeTransport transport = FakeTransport.free("one");
      try (LlmDispatcher dispatcher =
          new LlmDispatcher(
              List.of(pool("one", List.of("model"), Map.of(), FIVE_SECONDS, transport)),
              new NoOpTokenLedger(),
              ignored -> "",
              InferenceAccounting.NONE,
              Duration.ofSeconds(60),
              fold)) {
        ChatRequest request = ChatRequest.of("model", null, "hello");
        dispatcher.complete(request);
        assertEquals(Duration.ofSeconds(60), transport.lastTimeout);
        dispatcher.stream(request, Deltas.DISCARDING);
        assertEquals(Duration.ofSeconds(60), transport.lastTimeout);
        dispatcher.streamOn("one", request, Deltas.DISCARDING, () -> false);
        assertEquals(Duration.ofSeconds(60), transport.lastTimeout);
        dispatcher.streamFold(request, Deltas.DISCARDING);
        assertEquals(
            fold.compareTo(Duration.ofSeconds(60)) < 0 ? Duration.ofSeconds(60) : fold,
            transport.lastTimeout);
      }
    }
  }

  @Test
  void a_fold_without_a_global_prompt_override_cannot_shorten_the_pools_general_budget() {
    FakeTransport transport = FakeTransport.free("one");
    transport.defaultPromptTimeout = Duration.ofMinutes(10);
    try (LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(pool("one", List.of("model"), Map.of(), FIVE_SECONDS, transport)),
            new NoOpTokenLedger(),
            ignored -> "",
            InferenceAccounting.NONE,
            null,
            Duration.ofSeconds(160))) {
      dispatcher.streamFold(ChatRequest.of("model", null, "hello"), Deltas.DISCARDING);
      assertEquals(Duration.ofMinutes(10), transport.lastTimeout);
    }
  }

  @Test
  void context_maximums_cap_capacity_per_model_and_across_all_eligible_pools() {
    FakeTransport one = FakeTransport.free("one");
    one.contextLengths.put("model", 131072);
    one.contextMaximums.put("model", 65536);
    one.contextLengths.put("other", 32768);
    one.contextMaximums.put("other", 131072);
    FakeTransport two = FakeTransport.free("two");
    two.contextLengths.put("model", 131072);
    two.contextMaximums.put("model", 98304);
    try (LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                pool(
                    "one",
                    List.of("model", "other", "unknown"),
                    Map.of("fast", "model"),
                    FIVE_SECONDS,
                    one),
                pool("two", List.of("model"), Map.of("fast", "model"), FIVE_SECONDS, two)),
            new NoOpTokenLedger())) {
      assertEquals(OptionalInt.of(65536), dispatcher.contextLength("model", 64000));
      assertEquals(OptionalInt.of(65536), dispatcher.contextLength("fast", 64000));
      assertEquals(OptionalInt.of(32768), dispatcher.contextLength("other", 64000));
      one.contextMaximums.put("unknown", 16384);
      assertEquals(OptionalInt.of(16384), dispatcher.contextLength("unknown", 64000));
    }
  }

  private static final Duration FIVE_SECONDS = Duration.ofSeconds(5);

  private static LlmPool pool(
      String name,
      List<String> models,
      Map<String, String> classes,
      Duration defaultBudget,
      LlmTransport transport) {
    return new LlmPool(name, models, classes, 1, 1, defaultBudget, transport);
  }

  private static final class RecordingLedger implements TokenLedger {
    final List<LedgerEntry> entries = java.util.Collections.synchronizedList(new ArrayList<>());

    @Override
    public void record(LedgerEntry entry) {
      entries.add(entry);
    }

    List<LedgerEntry> entries() {
      synchronized (entries) {
        return List.copyOf(entries);
      }
    }
  }

  /** A ledger that fails the way a metering API does: every call, loudly. */
  private static final class ThrowingLedger implements TokenLedger {
    final AtomicInteger attempts = new AtomicInteger();

    @Override
    public void record(LedgerEntry entry) {
      attempts.incrementAndGet();
      throw new IllegalStateException("metering endpoint returned 503");
    }
  }

  @Test
  void a_model_specifier_reaches_the_pool_that_declares_it() {
    FakeTransport one = FakeTransport.free("one");
    FakeTransport two = FakeTransport.free("two");
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                pool("one", List.of("model-a"), Map.of(), Duration.ofSeconds(5), one),
                pool("two", List.of("model-b"), Map.of(), Duration.ofSeconds(5), two)),
            new NoOpTokenLedger());
    try {
      dispatcher.complete(ChatRequest.of("model-b", null, "hello"));
      assertEquals(0, one.calls.get());
      assertEquals(1, two.calls.get());
    } finally {
      dispatcher.close();
    }
  }

  /**
   * The point of a class, and the half a list of class names cannot supply: {@code fast} is not a
   * name any endpoint answers to, so what goes on the wire is the model the pool maps it to. An
   * implementation that passed the specifier straight through would route correctly and then ask a
   * server for a model called "fast".
   */
  @Test
  void a_class_specifier_sends_the_model_it_maps_to() {
    FakeTransport transport = FakeTransport.free("studio");
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                pool(
                    "studio",
                    List.of("qwen3.5-9b"),
                    Map.of("fast", "qwen3.5-9b"),
                    Duration.ofSeconds(5),
                    transport)),
            new NoOpTokenLedger());
    try {
      dispatcher.complete(ChatRequest.of("fast", null, "hello"));
      assertEquals(List.of("qwen3.5-9b"), transport.modelsSeen());
    } finally {
      dispatcher.close();
    }
  }

  @Test
  void an_unsatisfiable_specifier_names_what_was_asked_for_and_what_exists() {
    FakeTransport transport = FakeTransport.free("studio");
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                pool(
                    "studio",
                    List.of("qwen3.5-9b"),
                    Map.of("fast", "qwen3.5-9b"),
                    Duration.ofSeconds(5),
                    transport)),
            new NoOpTokenLedger());
    try {
      UnknownSpecifierException failed =
          assertThrows(
              UnknownSpecifierException.class,
              () -> dispatcher.complete(ChatRequest.of("gpt-5", null, "hello")));

      assertTrue(failed.getMessage().contains("gpt-5"), failed.getMessage());
      assertTrue(failed.getMessage().contains("studio"), failed.getMessage());
      assertTrue(failed.getMessage().contains("qwen3.5-9b"), failed.getMessage());
      assertTrue(failed.getMessage().contains("fast"), failed.getMessage());

      // And it never quietly used the one model that was loaded. A silent
      // substitution produces worse answers with no signal, and there is
      // no layer above this one that could notice.
      assertEquals(0, transport.calls.get());
    } finally {
      dispatcher.close();
    }
  }

  /**
   * The refusal lists, per pool, only the classes that pool can actually answer.
   *
   * <h2>Written after the claim it defends survived its own mutant</h2>
   *
   * <p>{@link LlmPool#describe} argues at length that it must filter the class map by what the pool
   * serves. That argument was made and then measured: reverting {@code describe} to {@code
   * classes.keySet()} left the whole 2997-test suite green, because every other fixture that
   * reaches a refusal has one pool, and with one pool the filtered set and the whole set are the
   * same list. A claim argued that hard with nothing able to break it is the shape this project
   * deletes rather than keeps, so this is the assertion that earns it.
   *
   * <p>What it defends is not cosmetic. The map is server-wide now, handed whole to every pool, so
   * an unfiltered {@code describe} prints an identical class list against every pool in the fleet —
   * and the one thing this message exists to tell an operator is which pool could have taken their
   * request. A three-pool refusal would read as three interchangeable pools, which is exactly the
   * wrong answer.
   *
   * <p>Asserted as two whole rendered pools rather than as {@code contains}, because the fault is a
   * class appearing under the <em>wrong</em> pool: {@code contains("deep")} is true of the broken
   * output too.
   */
  @Test
  void an_unsatisfiable_specifier_lists_only_the_classes_each_pool_can_answer() {
    FakeTransport alpha = FakeTransport.free("alpha");
    FakeTransport beta = FakeTransport.free("beta");
    // One map for the server, as LlmConfig hands it out, naming a model on
    // each of the two pools.
    Map<String, String> classes = new LinkedHashMap<>();
    classes.put("fast", "model-a");
    classes.put("deep", "model-b");
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                pool("alpha", List.of("model-a"), classes, Duration.ofSeconds(5), alpha),
                pool("beta", List.of("model-b"), classes, Duration.ofSeconds(5), beta)),
            new NoOpTokenLedger());
    try {
      UnknownSpecifierException failed =
          assertThrows(
              UnknownSpecifierException.class,
              () -> dispatcher.complete(ChatRequest.of("gpt-5", null, "hello")));

      assertTrue(
          failed.getMessage().contains("alpha[models=[model-a], classes=[fast]]"),
          failed.getMessage());
      assertTrue(
          failed.getMessage().contains("beta[models=[model-b], classes=[deep]]"),
          failed.getMessage());
    } finally {
      dispatcher.close();
    }
  }

  /**
   * Two nodes serving one model both answer its class, and the lighter takes the call.
   *
   * <h2>This is the case {@code plowshare.llm.classes} exists for</h2>
   *
   * <p>{@code application.yml} put it as "the field exists so that adding a second [node] is
   * config" — a second box serving the <b>same</b> model, for capacity. Both pools list {@code
   * model-x}, so both resolve {@code fast} to it, and routing is free to pick between them because
   * the answer is the same model either way.
   *
   * <p><b>Both pools serving one model is the whole fixture, and it used to be two.</b> This test
   * was written with {@code busy} mapping {@code fast} to {@code model-x} and {@code idle} mapping
   * it to {@code model-y} — which is the fault the 2026-09-07 move was made to remove, sitting
   * green in the suite as the specification of correct behaviour. It passed for a reason worth
   * recording: nothing it asserted was wrong about <em>routing</em>. The defect was that the wire
   * model changed with the queue depth, and the test asserted {@code modelsSeen()} on the pool it
   * expected to win, so the one observation that would have exposed it was the observation it made.
   *
   * <p>So the assertion below is deliberately the other way round: the model on the wire is the
   * same string whichever pool answered, and it is compared against a constant rather than against
   * the winner's own map.
   */
  @Test
  void two_pools_serving_one_model_both_answer_its_class_and_the_lighter_wins() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    FakeTransport busy = new FakeTransport("busy", 1, release);
    FakeTransport idle = FakeTransport.free("idle");
    LlmPool busyPool =
        pool("busy", List.of("model-x"), Map.of("fast", "model-x"), Duration.ofSeconds(5), busy);
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                busyPool,
                pool(
                    "idle",
                    List.of("model-x"),
                    Map.of("fast", "model-x"),
                    Duration.ofSeconds(5),
                    idle)),
            new NoOpTokenLedger());
    ExecutorService callers = Executors.newFixedThreadPool(2);

    try {
      // One call running on 'busy' and one queued behind it: depth 1.
      callers.execute(
          () ->
              busyPool.complete(
                  "model-x",
                  ChatMessage.conversation(null, "held"),
                  Sampling.NONE,
                  List.of(),
                  null,
                  Duration.ofSeconds(5)));
      assertTrue(busy.entered.await(2, TimeUnit.SECONDS));
      callers.execute(
          () ->
              busyPool.complete(
                  "model-x",
                  ChatMessage.conversation(null, "queued"),
                  Sampling.NONE,
                  List.of(),
                  null,
                  Duration.ofSeconds(5)));
      awaitDepth(busyPool, Lane.CHAT);

      dispatcher.complete(ChatRequest.of("fast", null, "hello"));

      assertEquals(1, idle.calls.get(), "the empty queue should have won");
      // The point of the capacity case: whichever pool answered, `fast`
      // meant one model.
      assertEquals(List.of("model-x"), idle.modelsSeen());
    } finally {
      release.countDown();
      callers.shutdown();
      dispatcher.close();
      assertTrue(callers.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  /**
   * A class naming a model only one pool serves goes to that pool, however loaded it is and however
   * idle the other one is.
   *
   * <h2>The case that was broken, and the one the {@code models.contains} clause in {@link
   * LlmPool#resolve} exists for</h2>
   *
   * <p>{@code plowshare.llm.classes} is one map handed to <em>every</em> pool, so without that
   * clause both pools here would answer {@code fast} and {@link LlmDispatcher} would hand the call
   * to the idle one — which does not serve {@code model-x} at all. What comes back is an endpoint
   * 404 on a model name, one layer below the configuration that caused it, on a deployment whose
   * YAML reads correctly.
   *
   * <p><b>The busy pool is deliberately the one that must win.</b> Routing prefers the lighter
   * load, so a fixture where the correct pool is also the idle one would pass under either
   * implementation. Here the correct answer and the cheap answer point at different pools, which is
   * the only arrangement that can tell resolution from routing apart.
   *
   * <p>Asserted on which transport was reached rather than on the result: both fakes answer, so the
   * returned completion looks the same from either.
   */
  @Test
  void a_class_naming_a_model_one_pool_serves_routes_there_however_loaded() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    FakeTransport busy = new FakeTransport("busy", 1, release);
    FakeTransport idle = FakeTransport.free("idle");
    // One server-wide map, exactly as LlmConfig hands it out: `fast` names
    // model-x, and only `busy` serves model-x.
    Map<String, String> classes = Map.of("fast", "model-x");
    LlmPool busyPool = pool("busy", List.of("model-x"), classes, Duration.ofSeconds(30), busy);
    LlmPool idlePool = pool("idle", List.of("model-y"), classes, Duration.ofSeconds(30), idle);
    LlmDispatcher dispatcher =
        new LlmDispatcher(List.of(busyPool, idlePool), new NoOpTokenLedger());
    ExecutorService callers = Executors.newFixedThreadPool(3);

    try {
      // One running and one queued on 'busy': load 2.0 against 'idle''s 0.
      callers.execute(
          () ->
              busyPool.complete(
                  "model-x",
                  ChatMessage.conversation(null, "held"),
                  Sampling.NONE,
                  List.of(),
                  null,
                  Duration.ofSeconds(30)));
      assertTrue(busy.entered.await(2, TimeUnit.SECONDS));
      callers.execute(
          () ->
              busyPool.complete(
                  "model-x",
                  ChatMessage.conversation(null, "queued"),
                  Sampling.NONE,
                  List.of(),
                  null,
                  Duration.ofSeconds(30)));
      awaitDepth(busyPool, Lane.CHAT);
      assertTrue(
          busyPool.load(Lane.CHAT) > idlePool.load(Lane.CHAT),
          "the fixture only means anything if the correct pool is the loaded one");

      // Routed while 'busy' is loaded, and the wait for depth 2 is what
      // proves it: the routed call is the second thing queued on 'busy',
      // so the routing decision was taken before anything was released.
      // Releasing first would let the two loads equalise, and the strict
      // '<' in route() keeps the first pool declared — so a broken resolve
      // would land on 'busy' anyway and the test would pass for the wrong
      // reason.
      Future<?> routed =
          callers.submit(() -> dispatcher.complete(ChatRequest.of("fast", null, "hello")));
      awaitDepth(busyPool, Lane.CHAT, 2);

      release.countDown();
      routed.get(10, TimeUnit.SECONDS);

      assertEquals(
          0,
          idle.calls.get(),
          "the idle pool does not serve model-x and must not have answered for `fast`");
      assertEquals(List.of("model-x", "model-x", "model-x"), busy.modelsSeen());
    } finally {
      release.countDown();
      callers.shutdown();
      dispatcher.close();
      assertTrue(callers.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  /**
   * A context length is still a property of the pool serving the model, and two pools serving one
   * model may report two different ones.
   *
   * <h2>What did <em>not</em> move on 2026-09-07</h2>
   *
   * <p>{@code classes} became server-wide; {@code context-lengths} and {@code
   * compaction-thresholds} deliberately did not. A class is a statement about the fleet — "{@code
   * fast} means this model" — with no per-box half. A context length is the opposite: two nodes may
   * load one model at different {@code --ctx-size}, and an operator may hold a margin under what a
   * node reports. Moving it would assert that a model has one length wherever it runs, which is
   * false the moment this fixture is a real deployment.
   *
   * <p>So the two pools here serve the same model — the shape the class map now requires of
   * anything answering one class — and answer with different lengths, which is only expressible
   * while the key is per pool. The dispatcher takes the smallest, because the next call may go to
   * either and a prompt is only safe if it fits the tightest; that rule is argued on {@link
   * LlmDispatcher#contextLength(String, int)} and is unchanged here.
   *
   * <p>Asked twice, through the class and through the wire model, because the two take different
   * paths into {@link LlmPool#resolve} and only the first touches the map that moved.
   */
  @Test
  void a_context_length_is_still_per_pool_for_one_model_two_pools_serve() {
    FakeTransport roomy = FakeTransport.free("roomy");
    roomy.contextLengths.put("model-x", 128000);
    FakeTransport tight = FakeTransport.free("tight");
    tight.contextLengths.put("model-x", 32000);
    Map<String, String> classes = Map.of("fast", "model-x");
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                pool("roomy", List.of("model-x"), classes, Duration.ofSeconds(5), roomy),
                pool("tight", List.of("model-x"), classes, Duration.ofSeconds(5), tight)),
            new NoOpTokenLedger());
    try {
      assertEquals(
          OptionalInt.of(32000),
          dispatcher.contextLength("fast", 64000),
          "the tightest node bounds the prompt, because the next call may go to it");
      assertEquals(
          OptionalInt.of(32000),
          dispatcher.contextLength("model-x", 64000),
          "and naming the wire model directly answers the same, or the two paths"
              + " into resolve() have drifted apart");
    } finally {
      dispatcher.close();
    }
  }

  /**
   * A bare model name resolves without the class map being consulted at all.
   *
   * <p>The class map is server-wide now, so every pool holds one whether it needs it or not. This
   * pins that a specifier which <em>is</em> a model name never reaches it: the map here maps {@code
   * model-x} to something else entirely, and if the lookup happened first the wire would carry
   * {@code a-different-model}.
   *
   * <p>It is the same precedence {@code a_model_name_wins_over_a_class_of_the _same_name} asserts,
   * made once more against a map that is no longer the pool's own — because "the pool declared
   * both" was the old reading of the collision and is no longer how one arises.
   */
  @Test
  void a_bare_model_name_resolves_without_consulting_the_class_map() {
    FakeTransport transport = FakeTransport.free("studio");
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                pool(
                    "studio",
                    List.of("model-x"),
                    Map.of("model-x", "a-different-model"),
                    Duration.ofSeconds(5),
                    transport)),
            new NoOpTokenLedger());
    try {
      dispatcher.complete(ChatRequest.of("model-x", null, "hello"));
      assertEquals(List.of("model-x"), transport.modelsSeen());
    } finally {
      dispatcher.close();
    }
  }

  /**
   * A pool that declares the same string as both a model and a class has a naming collision. The
   * more specific reading wins, and it is written down because either answer is defensible and only
   * one can be the behaviour.
   */
  @Test
  void a_model_name_wins_over_a_class_of_the_same_name() {
    FakeTransport transport = FakeTransport.free("studio");
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                pool(
                    "studio",
                    List.of("fast"),
                    Map.of("fast", "something-else"),
                    Duration.ofSeconds(5),
                    transport)),
            new NoOpTokenLedger());
    try {
      dispatcher.complete(ChatRequest.of("fast", null, "hello"));
      assertEquals(List.of("fast"), transport.modelsSeen());
    } finally {
      dispatcher.close();
    }
  }

  /**
   * The pool's default is a default, and a caller that knows better wins. Interactive recall:
   * thirty seconds of queueing is already a failed interaction.
   */
  @Test
  void a_request_budget_shortens_the_pool_default() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    FakeTransport transport = new FakeTransport("studio", 1, release);
    LlmPool busy = pool("studio", List.of("m"), Map.of(), Duration.ofSeconds(30), transport);
    LlmDispatcher dispatcher = new LlmDispatcher(List.of(busy), new NoOpTokenLedger());
    ExecutorService occupant = Executors.newSingleThreadExecutor();

    try {
      occupant.execute(
          () ->
              busy.complete(
                  "m",
                  ChatMessage.conversation(null, "held"),
                  Sampling.NONE,
                  List.of(),
                  null,
                  Duration.ofSeconds(5)));
      assertTrue(transport.entered.await(2, TimeUnit.SECONDS));

      long began = System.nanoTime();
      assertThrows(
          LlmSaturatedException.class,
          () ->
              dispatcher.complete(
                  ChatRequest.of("m", null, "hello").withBudget(Duration.ofMillis(50))));
      long elapsedMillis = (System.nanoTime() - began) / 1_000_000L;

      // Not merely "it failed": with the pool's 30s default it would have
      // failed too, thirty seconds later. The budget is the assertion.
      assertTrue(elapsedMillis < 2_000L, "waited " + elapsedMillis + "ms on a 50ms budget");
    } finally {
      release.countDown();
      occupant.shutdown();
      dispatcher.close();
      assertTrue(occupant.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  /**
   * And the other direction, which a one-way implementation would fail: a batch nobody is watching
   * outwaits a default meant for a recall.
   */
  @Test
  void a_request_budget_lengthens_the_pool_default() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    FakeTransport transport = new FakeTransport("studio", 1, release);
    LlmPool impatient = pool("studio", List.of("m"), Map.of(), Duration.ofMillis(1), transport);
    LlmDispatcher dispatcher = new LlmDispatcher(List.of(impatient), new NoOpTokenLedger());
    ExecutorService occupant = Executors.newSingleThreadExecutor();

    try {
      occupant.execute(
          () ->
              impatient.complete(
                  "m",
                  ChatMessage.conversation(null, "held"),
                  Sampling.NONE,
                  List.of(),
                  null,
                  Duration.ofSeconds(5)));
      assertTrue(transport.entered.await(2, TimeUnit.SECONDS));

      Thread releaser =
          new Thread(
              () -> {
                try {
                  Thread.sleep(200L);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                release.countDown();
              });
      releaser.setDaemon(true);
      releaser.start();

      // The pool's 1ms default would have given up long before the slot
      // freed; the request's 5s budget waits for it.
      Completion completion =
          dispatcher.complete(ChatRequest.of("m", null, "hello").withBudget(Duration.ofSeconds(5)));
      assertEquals("answer for hello", completion.content());
      releaser.join(2_000L);
    } finally {
      release.countDown();
      occupant.shutdown();
      dispatcher.close();
      assertTrue(occupant.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void streaming_emits_tokens_and_returns_the_same_kind_of_result() {
    FakeTransport transport = FakeTransport.free("studio");
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(pool("studio", List.of("m"), Map.of(), Duration.ofSeconds(5), transport)),
            new NoOpTokenLedger());
    List<String> tokens = new ArrayList<>();
    try {
      Completion completion = dispatcher.stream(ChatRequest.of("m", null, "hello"), tokens::add);
      assertEquals(List.of("an", "swer"), tokens);
      assertEquals("answer", completion.content());
    } finally {
      dispatcher.close();
    }
  }

  @Test
  void every_call_site_reports_to_the_ledger() {
    FakeTransport transport = FakeTransport.free("studio");
    RecordingLedger ledger = new RecordingLedger();
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                pool(
                    "studio",
                    List.of("m", "wire-model"),
                    Map.of("fast", "wire-model"),
                    Duration.ofSeconds(5),
                    transport)),
            ledger);
    try {
      // All three by class, and not by the model name the plan first used
      // here: with the specifier and the wire model spelled the same, an
      // entry recording the specifier is indistinguishable from one
      // recording the model, and the ledger is what a per-token bill would
      // be reconstructed from. "fast" and "wire-model" differ, so the
      // assertion can tell them apart.
      dispatcher.complete(ChatRequest.of("fast", null, "hello"));
      dispatcher.stream(ChatRequest.of("fast", null, "hello"), token -> {});
      dispatcher.embed(EmbeddingRequest.of("fast", List.of("hello")));

      List<LedgerEntry> entries = ledger.entries();
      assertEquals(3, entries.size(), "blocking, streaming and embedding all cost tokens");
      // The specifier is carried as well as the wire model: the dispatcher
      // holds the only copy of the map between them, so an entry without
      // it cannot be attributed to the class that was asked for.
      assertEquals(
          new LedgerEntry(
              "studio", "wire-model", "fast", Lane.CHAT, TokenUsage.of(3, 5, 8), "stop"),
          entries.get(0));
      assertEquals(
          new LedgerEntry(
              "studio", "wire-model", "fast", Lane.CHAT, TokenUsage.of(3, 5, 8), "stop"),
          entries.get(1));
      assertEquals(
          new LedgerEntry(
              "studio", "wire-model", "fast", Lane.EMBEDDING, TokenUsage.of(7, null, 7), null),
          entries.get(2));
    } finally {
      dispatcher.close();
    }
  }

  @Test
  void a_specifier_can_be_checked_without_calling_anything() {
    FakeTransport transport = FakeTransport.free("studio");
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                pool(
                    "studio",
                    List.of("nomic-embed-text"),
                    Map.of(),
                    Duration.ofSeconds(5),
                    transport)),
            new NoOpTokenLedger());
    try {
      dispatcher.requireServed("nomic-embed-text");
      assertThrows(UnknownSpecifierException.class, () -> dispatcher.requireServed("bge-m3"));
      assertEquals(0, transport.calls.get());
    } finally {
      dispatcher.close();
    }
  }

  /**
   * A capability is asked of every pool that serves the specifier, and one silent pool is a
   * refusal.
   *
   * <h2>The mutation this is written against</h2>
   *
   * <p>Change the conjunction to a disjunction — "some pool serving it sees" — and the two-pool
   * case below goes green while production becomes a coin flip: {@code route} picks the
   * least-loaded pool, so the same agent would work or not depending on which box happened to be
   * idle, and the failure would arrive as the model itself saying it cannot see an image. That
   * reads as a model limitation rather than a configuration one, and there is nothing in a log to
   * contradict it.
   *
   * <p>It is {@code contextLength}'s rule with a different operator: that method takes the smallest
   * known length because the next call may go anywhere and a prompt is only safe if it fits the
   * tightest.
   */
  @Test
  void a_specifier_sees_only_when_every_pool_serving_it_does() {
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                vision("studio", List.of("gemma-4-e4b"), Set.of("gemma-4-e4b")),
                vision("spark", List.of("gemma-4-e4b", "glm-4.7-flash"), Set.of("gemma-4-e4b"))),
            new NoOpTokenLedger());
    try {
      dispatcher.requireSees("gemma-4-e4b");

      LlmException blind =
          assertThrows(LlmException.class, () -> dispatcher.requireSees("glm-4.7-flash"));
      assertTrue(blind.getMessage().contains("spark"), blind.getMessage());
      assertTrue(blind.getMessage().contains("glm-4.7-flash"), blind.getMessage());
    } finally {
      dispatcher.close();
    }
  }

  /**
   * A system specifier is resolved before {@code requireSees} asks any pool whether it can see, not
   * merely before the loud "does anything serve this" check that precedes the loop.
   *
   * <h2>The hole this pins shut</h2>
   *
   * <p>{@code requireSees} used to call {@code route(specifier, ...)} once for that loud check and
   * then loop over every pool calling {@code pool.resolve(specifier)} again with the same, raw
   * {@code specifier} — fine while nothing but {@code route} needed resolving, and silently wrong
   * the moment {@code system} specifiers existed. {@code route} resolves {@code "system"} to a real
   * wire model internally and finds the pool serving it, so the loud check passes; the loop then
   * asks {@code pool.resolve("system")} literally, which is {@code null} for every pool because no
   * model or class is ever actually named {@code "system"}. With every {@code wireModel} {@code
   * null}, {@code blind} never gains an entry, and the method returns having asked nothing —
   * success on the same terms a disjunction would produce, which is the exact failure shape {@code
   * a_specifier_sees_only_when_every_pool_serving_it_does} exists to catch for an ordinary
   * specifier.
   *
   * <p>This is why the assertion below has to be "throws", not "does not throw": both the fixed
   * method and the reintroduced bug answer "no exception" for a pool that <em>does</em> declare
   * vision, so a successful call proves nothing. Only a pool that does <b>not</b> declare it
   * separates the two — the fix must throw, naming the model the binding actually resolved to; the
   * bug returns silently, having never asked.
   *
   * <p><b>The mutation:</b> revert the loop in {@code requireSees} to read {@code
   * pool.resolve(specifier)} instead of the resolved local, and this goes red — {@code
   * assertThrows} finds no exception — while {@code
   * a_system_specifier_routes_through_the_configured_binding} stays green, since {@code complete}
   * never shared this bug.
   */
  @Test
  void
      requireSees_resolves_a_system_specifier_before_asking_any_pool_and_not_only_before_the_loud_check() {
    io.aeyer.plowshare.server.llm.LlmProperties props =
        new io.aeyer.plowshare.server.llm.LlmProperties();
    props.setSystem("blind-model");
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(vision("studio", List.of("blind-model"), Set.of())),
            new NoOpTokenLedger(),
            props::systemSpecifier);
    try {
      LlmException blind = assertThrows(LlmException.class, () -> dispatcher.requireSees("system"));
      assertTrue(blind.getMessage().contains("blind-model"), blind.getMessage());
      assertTrue(blind.getMessage().contains("studio"), blind.getMessage());
    } finally {
      dispatcher.close();
    }
  }

  /**
   * One of two pools declaring it is not enough, which is the whole of the conjunction and the case
   * a disjunction would pass.
   */
  @Test
  void one_pool_of_two_declaring_vision_is_still_a_refusal() {
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                vision("studio", List.of("shared"), Set.of("shared")),
                vision("spark", List.of("shared"), Set.of())),
            new NoOpTokenLedger());
    try {
      LlmException blind = assertThrows(LlmException.class, () -> dispatcher.requireSees("shared"));
      assertTrue(blind.getMessage().contains("spark"), blind.getMessage());
      assertFalse(
          blind.getMessage().contains("studio"),
          "and it names the pool that has to change rather than every pool: " + blind.getMessage());
    } finally {
      dispatcher.close();
    }
  }

  /**
   * A name nothing serves at all is the other fault, and it stays the other fault.
   *
   * <p>Answering "the pools serving it cannot see" for a specifier no pool serves would send an
   * operator to {@code vision:} when the mistake is in the name itself. {@code
   * UnknownSpecifierException}'s message lists what <em>is</em> configured, which is what they
   * actually need.
   */
  @Test
  void a_specifier_nothing_serves_is_unknown_and_not_blind() {
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(vision("studio", List.of("gemma-4-e4b"), Set.of("gemma-4-e4b"))),
            new NoOpTokenLedger());
    try {
      assertThrows(
          UnknownSpecifierException.class, () -> dispatcher.requireSees("nothing-serves-this"));
    } finally {
      dispatcher.close();
    }
  }

  /**
   * A capability is a fact about a wire model, so a class resolves through to the model it names
   * rather than being declarable itself.
   */
  @Test
  void a_class_specifier_is_answered_by_the_model_it_resolves_to() {
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                new LlmPool(
                    "studio",
                    List.of("gemma-4-e4b"),
                    Map.of("eyes", "gemma-4-e4b"),
                    1,
                    1,
                    Duration.ofSeconds(5),
                    FakeTransport.free("studio"),
                    Set.of("gemma-4-e4b"))),
            new NoOpTokenLedger());
    try {
      dispatcher.requireSees("eyes");
    } finally {
      dispatcher.close();
    }
  }

  private static LlmPool vision(String name, List<String> models, Set<String> sees) {
    return new LlmPool(
        name, models, Map.of(), 1, 1, Duration.ofSeconds(5), FakeTransport.free(name), sees);
  }

  /**
   * The other two call sites, which {@code a_class_specifier_sends_the_model _it_maps_to} does not
   * reach.
   *
   * <p>Every other test that streams or embeds does so through a specifier that <em>is</em> a model
   * name, where passing the specifier through and resolving it are the same string — so an
   * implementation that resolved only for {@code complete} was green on all ten of this task's
   * tests. That mutant was run; this is what kills it. A class is the case that tells the two
   * apart, because {@code fast} is not a name any endpoint answers to.
   */
  @Test
  void a_class_specifier_reaches_the_wire_on_every_call_site() {
    FakeTransport transport = FakeTransport.free("studio");
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                pool(
                    "studio",
                    List.of("qwen3.5-9b", "nomic-embed-text"),
                    Map.of("fast", "qwen3.5-9b", "vectors", "nomic-embed-text"),
                    Duration.ofSeconds(5),
                    transport)),
            new NoOpTokenLedger());
    try {
      dispatcher.stream(ChatRequest.of("fast", null, "hello"), token -> {});
      dispatcher.embed(EmbeddingRequest.of("vectors", List.of("hello")));

      // Both calls are blocking and run in sequence, so the order is the
      // order they were made in.
      assertEquals(List.of("qwen3.5-9b", "nomic-embed-text"), transport.modelsSeen());
    } finally {
      dispatcher.close();
    }
  }

  /**
   * A busy chat lane says nothing about the embedding lane, and routing has to measure the one the
   * call will wait in.
   *
   * <p>The lanes exist because the two kinds of work do not contend: a box generating one token
   * stream will happily embed two batches while it does it. An implementation that routed every
   * call on the chat queue would therefore steer an embedding away from a host whose embedding lane
   * is empty — and it would do so silently, because both hosts serve the specifier and both answers
   * look correct. That mutant survives all ten of this task's tests.
   *
   * <p>It moves on two guards, so <b>read a red here rather than acting on it</b>: the two
   * embedding lanes are both empty, so {@code first} wins only under the strict {@code <} as well
   * as only under a lane-aware read. Check {@code with_equal_queues_the_first_pool_declared_wins}
   * first — if that is red too the tie-break is what broke; if it is green, this is the lane. That
   * second test exists precisely so this one does not have to be diagnosed by guessing.
   */
  @Test
  void an_embedding_routes_on_the_embedding_queue_and_not_the_chat_one() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    OneLaneBlockingTransport first = new OneLaneBlockingTransport("first", Lane.CHAT, release);
    FakeTransport second = FakeTransport.free("second");
    // One model on both pools, which is the only way two pools may answer
    // one class since 2026-09-07: a class names one model server-wide, so
    // two pools answering it are two nodes with that model loaded. Which
    // pool won is read off the transports, which are per pool, and not off
    // the model name — see the note on
    // two_pools_serving_one_model_both_answer_its_class_and_the_lighter_wins
    // for why reading it off the name is the observation that hid the bug.
    LlmPool firstPool =
        pool(
            "first",
            List.of("model-shared"),
            Map.of("vectors", "model-shared"),
            Duration.ofSeconds(5),
            first);
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                firstPool,
                pool(
                    "second",
                    List.of("model-shared"),
                    Map.of("vectors", "model-shared"),
                    Duration.ofSeconds(5),
                    second)),
            new NoOpTokenLedger());
    ExecutorService callers = Executors.newFixedThreadPool(2);

    try {
      // Chat lane on 'first': one running, one queued. Depth 1.
      callers.execute(
          () ->
              firstPool.complete(
                  "model-shared",
                  ChatMessage.conversation(null, "held"),
                  Sampling.NONE,
                  List.of(),
                  null,
                  Duration.ofSeconds(5)));
      assertTrue(first.entered.await(2, TimeUnit.SECONDS));
      callers.execute(
          () ->
              firstPool.complete(
                  "model-shared",
                  ChatMessage.conversation(null, "queued"),
                  Sampling.NONE,
                  List.of(),
                  null,
                  Duration.ofSeconds(5)));
      awaitDepth(firstPool, Lane.CHAT);
      assertEquals(0, firstPool.queueDepth(Lane.EMBEDDING));

      dispatcher.embed(EmbeddingRequest.of("vectors", List.of("hello")));

      assertEquals(
          List.of("model-shared"),
          first.embedded,
          "the busy chat lane should not have moved the embedding");
      assertEquals(0, second.calls.get());
    } finally {
      release.countDown();
      callers.shutdown();
      dispatcher.close();
      assertTrue(callers.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  /**
   * The dispatcher owns the pools, so shutting it down has to reach all of them — every one, not
   * just the first.
   *
   * <p>What leaks otherwise is per pool an OkHttp connection pool and the threads its dispatcher
   * runs, once per shutdown, with nothing left running to report it. {@code FakeTransport.closed}
   * exists for exactly this evidence: Task 2 found that deleting {@code transport.close()} from
   * {@link LlmPool#close} left its whole suite green, and commenting out this class's {@code close}
   * body left all ten of this task's tests green too.
   */
  @Test
  void closing_the_dispatcher_closes_every_pool() {
    FakeTransport one = FakeTransport.free("one");
    FakeTransport two = FakeTransport.free("two");
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                pool("one", List.of("model-a"), Map.of(), Duration.ofSeconds(5), one),
                pool("two", List.of("model-b"), Map.of(), Duration.ofSeconds(5), two)),
            new NoOpTokenLedger());

    dispatcher.close();

    assertTrue(one.closed, "pool 'one' was not closed");
    assertTrue(two.closed, "pool 'two' was not closed");
  }

  /**
   * And one pool that fails to close does not strand the pools after it.
   *
   * <p>The contract this rests on — {@code LlmTransport.close} must not throw — is enforced
   * nowhere, and its first real implementation, an OkHttp client, lands in the next task. What a
   * break costs is invisible: at shutdown, per pool, a connection pool and the threads its
   * dispatcher runs, with nothing left running to report the leak. Cheap enough to hold for real
   * rather than by documentation.
   */
  @Test
  void a_pool_that_fails_to_close_does_not_strand_the_rest() {
    FakeTransport one = FakeTransport.free("one");
    FakeTransport two = FakeTransport.free("two");
    one.failCloseWith = new IllegalStateException("the client would not shut down");
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                pool("one", List.of("model-a"), Map.of(), Duration.ofSeconds(5), one),
                pool("two", List.of("model-b"), Map.of(), Duration.ofSeconds(5), two)),
            new NoOpTokenLedger());

    dispatcher.close();

    assertTrue(two.closed, "the pool after the failing one was never closed");
  }

  /**
   * Every argument of a chat request reaches the transport, at both chat call sites.
   *
   * <p>The same bug as the wire model, one argument over, and it was live in five places at once:
   * replacing {@code system} with {@code null} or {@code temperature} with a constant in
   * <em>either</em> {@code complete} or {@code stream}, or {@code user} with {@code ""} in {@code
   * stream}, left all thirteen earlier tests green. A silently discarded system prompt is exactly
   * the "worse answers with no signal, and no layer above could notice" failure this task exists to
   * prevent — it just does not announce itself the way an unknown specifier does.
   *
   * <p>Note what made it invisible: every other test builds with {@link ChatRequest#of}, which
   * hardcodes a null system and temperature {@code 0.0d}, so even a mutant substituting {@code
   * 0.0d} would have matched. Only a request whose values are all distinguishable from the defaults
   * can see this, which is why this one is built through the full constructor. {@code
   * withTemperature} is public API with nothing else between it and the wire.
   */
  /**
   * The tools a request carries reach the transport.
   *
   * <p>The dispatcher builds its transport call by listing every component of the request, so a
   * component added later is dropped by whichever call site the author forgot — and dropped
   * silently, as a model that was offered nothing rather than as an error. That is the failure this
   * pins, one layer above {@code tools_are_sent_in_the_openai_shape}, which pins the wire.
   */
  @Test
  void a_blocking_chat_offers_the_transport_the_tools_the_request_carried() {
    FakeTransport transport = FakeTransport.free("studio");
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(pool("studio", List.of("m"), Map.of(), Duration.ofSeconds(5), transport)),
            new NoOpTokenLedger());
    ToolSchema tool = new ToolSchema("memory_recall", "Search by meaning.", Map.of());
    try {
      dispatcher.complete(ChatRequest.of("m", null, "hello").withTools(List.of(tool)));
      assertEquals(List.of(List.of(tool)), transport.toolsSeen());
    } finally {
      dispatcher.close();
    }
  }

  /**
   * A streaming request carries its tools to the pool, as a blocking one does.
   *
   * <p><b>The inverse of what this used to assert, and the inversion is the slice.</b> It was
   * {@code a_streaming_request_that_carries_tools_is_refused} and it was right while nothing
   * reassembled {@code tool_calls} deltas out of an SSE body: letting the request through would
   * have returned a {@link Completion} with no tool calls in it, which is exactly what a model that
   * decided to call none looks like, and a turn loop would have recorded "it answered" for a run in
   * which its tools were never on the wire.
   *
   * <p>{@code OpenAiTransport.stream} reassembles them now, so the refusal is gone and the hazard
   * it named has moved one layer down: what must be true is that the tools <em>reach the
   * transport</em>. Asserted on {@code toolsSeen()} rather than on the result, for the reason the
   * old test gave — a completion with no tool calls is not evidence either way, since a model may
   * simply not have called one.
   */
  @Test
  void a_streaming_request_carries_its_tools_to_the_transport() {
    FakeTransport transport = FakeTransport.free("studio");
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(pool("studio", List.of("m"), Map.of(), Duration.ofSeconds(5), transport)),
            new NoOpTokenLedger());
    try {
      ToolSchema recall = new ToolSchema("memory_recall", "d", Map.of());
      ChatRequest request = ChatRequest.of("m", null, "hello").withTools(List.of(recall));

      dispatcher.stream(request, token -> {});

      assertEquals(List.of(List.of(recall)), transport.toolsSeen());
    } finally {
      dispatcher.close();
    }
  }

  /**
   * A stream stops on the caller's own flag, and says so as a cancellation rather than as a
   * failure.
   *
   * <p><b>Not routed through the sink, and the reason is a measurement.</b> On 2026-09-02 a
   * tool-carrying request came back as 63 chunks of which none carried {@code delta.content}: sixty
   * were reasoning and two were tool-call fragments. A sink is handed the answer's tokens, so on an
   * agent's ordinary turn a sink would never be called at all — and a cancellation that rode on it
   * would have waited out the whole generation while a green suite said otherwise. Hence the
   * predicate, and hence this assertion at the layer that passes it down.
   *
   * <p>{@link CallerAbandonedException} and not an {@link LlmException}: the endpoint was
   * generating perfectly well and somebody pressed stop, and a caller that could not tell those
   * apart would report a healthy host as unreachable.
   */
  @Test
  void a_stream_the_caller_gave_up_on_is_not_an_endpoint_failure() {
    FakeTransport transport = FakeTransport.free("studio");
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(pool("studio", List.of("m"), Map.of(), Duration.ofSeconds(5), transport)),
            new NoOpTokenLedger());
    try {
      List<String> tokens = new ArrayList<>();
      CallerAbandonedException gaveUp =
          assertThrows(
              CallerAbandonedException.class,
              () -> dispatcher.stream(ChatRequest.of("m", null, "hello"), tokens::add, () -> true));

      // Through isInstance and not `instanceof`, which does not compile
      // here — javac already knows the two hierarchies are disjoint, which
      // is a stronger guarantee than this line and the reason it is worth
      // saying that the line is therefore a regression guard against
      // someone making CallerAbandonedException extend LlmException, not a
      // discovery.
      assertFalse(
          LlmException.class.isInstance(gaveUp),
          "a cancelled stream must not be reportable as the endpoint failing");
      assertEquals(List.of(), tokens, "nothing is emitted after the caller has gone");
      assertEquals(1, transport.calls.get(), "the call was made before it was abandoned");
    } finally {
      dispatcher.close();
    }
  }

  @Test
  void every_chat_call_site_sends_the_prompts_and_the_sampling_it_was_given() {
    FakeTransport transport = FakeTransport.free("studio");
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(pool("studio", List.of("m"), Map.of(), Duration.ofSeconds(5), transport)),
            new NoOpTokenLedger());
    Sampling asked = Sampling.NONE.withTemperature(0.25d);
    ChatRequest request =
        new ChatRequest(
            "m",
            ChatMessage.conversation("answer in one word", "hello"),
            asked,
            null,
            List.of(),
            null);
    try {
      dispatcher.complete(request);
      dispatcher.stream(request, token -> {});

      assertEquals(List.of("answer in one word", "answer in one word"), transport.systemsSeen());
      assertEquals(List.of("hello", "hello"), transport.promptsSeen());
      assertEquals(List.of(asked, asked), transport.samplingSeen());
    } finally {
      dispatcher.close();
    }
  }

  /**
   * A parameter the transport cannot send is removed before the call, and the rest of the request
   * survives.
   *
   * <p><b>The failure this stops is a silent one and not a loud one.</b> An endpoint that does not
   * know {@code top_k} ignores the field; it does not refuse. So a profile an operator can read in
   * a file would not be the configuration that ran, and neither end would say so. {@code
   * LlmTransport.carries()} is each transport's own statement of its subset, the dispatcher applies
   * it, and a warning naming the pool and the parameter is what turns the silence into something
   * readable.
   *
   * <p>Asked at a value the default {@code carries()} does not include and at one it does, in the
   * same request, so this cannot pass against a filter that simply dropped everything or kept
   * everything.
   */
  @Test
  void a_parameter_the_transport_cannot_carry_is_dropped_and_the_rest_survives() {
    FakeTransport transport = FakeTransport.free("studio");
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(pool("studio", List.of("m"), Map.of(), Duration.ofSeconds(5), transport)),
            new NoOpTokenLedger());
    try {
      dispatcher.complete(
          new ChatRequest(
              "m",
              ChatMessage.conversation(null, "hello"),
              Sampling.NONE.withTemperature(0.6d).withTopK(20).withTopP(0.95d),
              null,
              List.of(),
              null));

      assertEquals(
          List.of(Sampling.NONE.withTemperature(0.6d)),
          transport.samplingSeen(),
          "a transport that declares only TEMPERATURE must be sent only a"
              + " temperature, and must still be sent that");
    } finally {
      dispatcher.close();
    }
  }

  /**
   * And the embedding call site's payload, which is the worst of the family to lose.
   *
   * <p>Substituting a constant batch for {@code request.input()} left all thirteen earlier tests
   * green, because the only embedding assertions were on the model, the lane and the ledger — never
   * on the text. The archive would then store vectors for a string the caller never sent, and
   * {@code Archive.embed} swallows on write, so nothing would report it and every later recall
   * would be quietly wrong.
   */
  @Test
  void an_embedding_sends_the_batch_it_was_given() {
    FakeTransport transport = FakeTransport.free("studio");
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(pool("studio", List.of("m"), Map.of(), Duration.ofSeconds(5), transport)),
            new NoOpTokenLedger());
    try {
      Embeddings embeddings =
          dispatcher.embed(EmbeddingRequest.of("m", List.of("first", "second", "third")));

      assertEquals(List.of(List.of("first", "second", "third")), transport.inputsSeen());
      // Element-wise, never by equality: Embeddings is a record over
      // float[], so two holding identical vectors are never equal.
      assertEquals(3, embeddings.vectors().size());
    } finally {
      dispatcher.close();
    }
  }

  /**
   * The budget reaches the pool when streaming, which the class javadoc claims for all three call
   * sites and only {@code complete} was testing.
   *
   * <p>Dropping it to {@code null} here is invisible to every other test and silently reinstates
   * the pool's default — thirty seconds of queueing on a call whose caller asked for fifty
   * milliseconds.
   */
  @Test
  void a_request_budget_shortens_the_pool_default_when_streaming() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    FakeTransport transport = new FakeTransport("studio", 1, release);
    LlmPool busy = pool("studio", List.of("m"), Map.of(), Duration.ofSeconds(30), transport);
    LlmDispatcher dispatcher = new LlmDispatcher(List.of(busy), new NoOpTokenLedger());
    ExecutorService occupant = Executors.newSingleThreadExecutor();

    try {
      occupant.execute(
          () ->
              busy.complete(
                  "m",
                  ChatMessage.conversation(null, "held"),
                  Sampling.NONE,
                  List.of(),
                  null,
                  Duration.ofSeconds(5)));
      assertTrue(transport.entered.await(2, TimeUnit.SECONDS));

      long began = System.nanoTime();
      assertThrows(
          LlmSaturatedException.class,
          () ->
              dispatcher.stream(
                  ChatRequest.of("m", null, "hello").withBudget(Duration.ofMillis(50)),
                  token -> {}));
      long elapsedMillis = (System.nanoTime() - began) / 1_000_000L;

      assertTrue(elapsedMillis < 2_000L, "waited " + elapsedMillis + "ms on a 50ms budget");
    } finally {
      release.countDown();
      occupant.shutdown();
      dispatcher.close();
      assertTrue(occupant.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  /**
   * The same for the embedding lane. {@link EmbeddingRequest#withBudget} exists precisely so a
   * batch ingest nobody is watching can outwait a default sized for a recall, and nothing was
   * checking it arrived.
   */
  @Test
  void a_request_budget_shortens_the_pool_default_when_embedding() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    FakeTransport transport = new FakeTransport("studio", 1, release);
    LlmPool busy = pool("studio", List.of("m"), Map.of(), Duration.ofSeconds(30), transport);
    LlmDispatcher dispatcher = new LlmDispatcher(List.of(busy), new NoOpTokenLedger());
    ExecutorService occupant = Executors.newSingleThreadExecutor();

    try {
      occupant.execute(() -> busy.embed("m", List.of("held"), Duration.ofSeconds(5)));
      assertTrue(transport.entered.await(2, TimeUnit.SECONDS));

      long began = System.nanoTime();
      assertThrows(
          LlmSaturatedException.class,
          () ->
              dispatcher.embed(
                  EmbeddingRequest.of("m", List.of("hello")).withBudget(Duration.ofMillis(50))));
      long elapsedMillis = (System.nanoTime() - began) / 1_000_000L;

      assertTrue(elapsedMillis < 2_000L, "waited " + elapsedMillis + "ms on a 50ms budget");
    } finally {
      release.countDown();
      occupant.shutdown();
      dispatcher.close();
      assertTrue(occupant.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  /**
   * Equal queues, and the pool declared first wins.
   *
   * <p>Split out so it is testable on its own. Until this existed the strict {@code <} had exactly
   * one witness — {@code an_embedding_routes_on_the_embedding_queue_and_not_the_chat_one}, which is
   * also the only witness for routing being lane-aware — so a red there could have meant either
   * guard and had to be read rather than acted on. Two empty queues and no threads at all
   * discriminate the tie-break alone: with every depth at zero, chat and embedding read the same,
   * so the lane bug cannot move this test and this test cannot mask it.
   *
   * <p>The rule itself matters most where there is nothing to balance: a single-pool deployment,
   * and the first entry of a list an operator wrote in a deliberate order.
   */
  @Test
  void with_equal_queues_the_first_pool_declared_wins() {
    FakeTransport first = FakeTransport.free("first");
    FakeTransport second = FakeTransport.free("second");
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                pool(
                    "first",
                    List.of("model-shared"),
                    Map.of("fast", "model-shared"),
                    Duration.ofSeconds(5),
                    first),
                pool(
                    "second",
                    List.of("model-shared"),
                    Map.of("fast", "model-shared"),
                    Duration.ofSeconds(5),
                    second)),
            new NoOpTokenLedger());
    try {
      dispatcher.complete(ChatRequest.of("fast", null, "hello"));

      // Both pools serve the class, which is what makes this a tie at all.
      // Which one answered is read off the transports and not off the
      // model, because one class now means one model.
      assertEquals(List.of("model-shared"), first.modelsSeen());
      assertEquals(0, second.calls.get());
    } finally {
      dispatcher.close();
    }
  }

  /**
   * The mirror of the embedding case: a streaming call routes on the chat queue.
   *
   * <p>Routing {@code stream} on the embedding lane's depth survives every other test, and it is
   * the same silent mis-steer — both pools serve the class, both answers look correct, and the only
   * symptom is a slower reply from a host that was busy with work of an entirely different kind.
   *
   * <p><b>Deliberately not a tie.</b> Each pool has one busy lane, and they are different lanes, so
   * reading the chat depth picks {@code first} on {@code 0 < 1} and reading the embedding depth
   * picks {@code second} on the same comparison. That keeps this case sole evidence for one guard
   * only: unlike {@code an_embedding_routes_on_the_embedding_queue_and_not_the_chat _one}, which is
   * also the only thing holding the strict {@code <} tie-break, a red here can only mean the lane.
   * Relaxing {@code <} to {@code <=} does not move this test, and that is on purpose.
   */
  @Test
  void a_streaming_call_routes_on_the_chat_queue_and_not_the_embedding_one() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    OneLaneBlockingTransport first = new OneLaneBlockingTransport("first", Lane.EMBEDDING, release);
    OneLaneBlockingTransport second = new OneLaneBlockingTransport("second", Lane.CHAT, release);
    LlmPool firstPool =
        pool(
            "first",
            List.of("model-shared"),
            Map.of("fast", "model-shared"),
            Duration.ofSeconds(5),
            first);
    LlmPool secondPool =
        pool(
            "second",
            List.of("model-shared"),
            Map.of("fast", "model-shared"),
            Duration.ofSeconds(5),
            second);
    LlmDispatcher dispatcher =
        new LlmDispatcher(List.of(firstPool, secondPool), new NoOpTokenLedger());
    ExecutorService callers = Executors.newFixedThreadPool(4);

    try {
      // 'first': embedding lane one deep, chat lane empty.
      callers.execute(
          () -> firstPool.embed("model-shared", List.of("held"), Duration.ofSeconds(5)));
      assertTrue(first.entered.await(2, TimeUnit.SECONDS));
      callers.execute(
          () -> firstPool.embed("model-shared", List.of("queued"), Duration.ofSeconds(5)));
      // 'second': chat lane one deep, embedding lane empty.
      callers.execute(
          () ->
              secondPool.complete(
                  "model-shared",
                  ChatMessage.conversation(null, "held"),
                  Sampling.NONE,
                  List.of(),
                  null,
                  Duration.ofSeconds(5)));
      assertTrue(second.entered.await(2, TimeUnit.SECONDS));
      callers.execute(
          () ->
              secondPool.complete(
                  "model-shared",
                  ChatMessage.conversation(null, "queued"),
                  Sampling.NONE,
                  List.of(),
                  null,
                  Duration.ofSeconds(5)));

      awaitDepth(firstPool, Lane.EMBEDDING);
      awaitDepth(secondPool, Lane.CHAT);
      assertEquals(0, firstPool.queueDepth(Lane.CHAT));
      assertEquals(0, secondPool.queueDepth(Lane.EMBEDDING));

      dispatcher.stream(ChatRequest.of("fast", null, "hello"), token -> {});

      assertEquals(
          List.of("model-shared"),
          first.chatted,
          "the free chat lane should have won, whatever the embedding lanes are doing");
      // One entry: the held call this test made directly. Its queued
      // sibling is still in the lane and has not reached the transport, so
      // it is not recorded — and the routed call is not here, which is the
      // claim. Both pools now serve one model, so what discriminates is
      // which transport saw anything at all: a stream routed on the
      // embedding depth would have picked 'second', whose chat lane is
      // full, and been shed as saturated rather than recorded.
      assertEquals(
          List.of("model-shared"),
          second.chatted,
          "'second' should have seen only the call this test made directly");
    } finally {
      release.countDown();
      callers.shutdown();
      dispatcher.close();
      assertTrue(callers.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  /**
   * Spin until {@code expected} calls have entered the transport. Bounded, so a routing mistake
   * fails the assertion that follows rather than hanging the build.
   */
  private static void awaitCalls(FakeTransport transport, int expected) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (transport.calls.get() < expected && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(expected, transport.calls.get(), "expected " + expected + " calls in flight");
  }

  /**
   * Spin until one request is queued behind the running one, or give up and let the assertion that
   * follows report the depth it actually found.
   */
  private static void awaitDepth(LlmPool pool, Lane lane) throws Exception {
    awaitDepth(pool, lane, 1);
  }

  /**
   * As above, for a test that has to know a second request queued behind the first — which is how a
   * routing decision taken under load is observed without a sleep.
   */
  private static void awaitDepth(LlmPool pool, Lane lane, int expected) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (pool.queueDepth(lane) < expected && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(
        expected,
        pool.queueDepth(lane),
        "expected "
            + expected
            + " queued on the "
            + lane.wireName()
            + " lane of '"
            + pool.name()
            + "'");
  }

  /**
   * Accounting must not destroy a call the box has already been paid for.
   *
   * <p>{@link TokenLedger}'s stated purpose is an external provider that bills per token, so its
   * natural implementation is an HTTP POST to a metering API — the kind of thing that returns 503.
   * Letting that propagate would discard a completion the model has already generated, and the
   * obvious response to the failure is a retry, which generates and pays for it a second time.
   */
  @Test
  void a_throwing_ledger_does_not_destroy_a_call_that_already_happened() {
    FakeTransport transport = FakeTransport.free("studio");
    ThrowingLedger ledger = new ThrowingLedger();
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(pool("studio", List.of("m"), Map.of(), Duration.ofSeconds(5), transport)),
            ledger);
    try {
      Completion completion = dispatcher.complete(ChatRequest.of("m", null, "hello"));
      assertEquals("answer for hello", completion.content());

      Embeddings embeddings = dispatcher.embed(EmbeddingRequest.of("m", List.of("hello")));
      assertEquals(1, embeddings.vectors().size());

      // Both call sites, because embed routed around the shared helper
      // once already and that is exactly the line a guard gets added
      // without.
      assertEquals(2, ledger.attempts.get(), "every call site must still try to record");
    } finally {
      dispatcher.close();
    }
  }

  /**
   * And the streaming case, which is worse than losing a result.
   *
   * <p>Every token has reached the caller's sink before the ledger runs. An exception at that point
   * tells a caller that a call it has already watched succeed did not happen — the same lie {@code
   * LlmPool.submit} spends four paragraphs refusing to tell about saturation, and there is nothing
   * above this line that could reconcile the two accounts.
   */
  @Test
  void a_throwing_ledger_does_not_contradict_tokens_the_sink_already_saw() {
    FakeTransport transport = FakeTransport.free("studio");
    ThrowingLedger ledger = new ThrowingLedger();
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(pool("studio", List.of("m"), Map.of(), Duration.ofSeconds(5), transport)),
            ledger);
    List<String> tokens = new ArrayList<>();
    try {
      Completion completion = dispatcher.stream(ChatRequest.of("m", null, "hello"), tokens::add);

      assertEquals(List.of("an", "swer"), tokens);
      assertEquals("answer", completion.content());
      assertEquals(1, ledger.attempts.get());
    } finally {
      dispatcher.close();
    }
  }

  /**
   * A pool whose every slot is busy loses to an idle one — the case no depth-based router can see.
   *
   * <p>Both lanes are {@code ThreadPoolExecutor}s with {@code corePoolSize == maximumPoolSize} over
   * an unbounded queue, and such an executor only queues once every core thread is busy. So a fully
   * occupied lane reports a queue depth of zero, identical to an idle one, and the strict {@code <}
   * then keeps the pool declared first: below saturation the whole fleet goes to one host while the
   * rest sit idle. The two assertions on {@code queueDepth} and {@code load} below are there to
   * show the reading itself, not just its consequence.
   */
  @Test
  void a_pool_with_every_slot_busy_loses_to_an_idle_one() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    FakeTransport busy = new FakeTransport("busy", 1, release);
    FakeTransport idle = FakeTransport.free("idle");
    LlmPool busyPool =
        pool(
            "busy",
            List.of("model-shared"),
            Map.of("fast", "model-shared"),
            Duration.ofSeconds(5),
            busy);
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                busyPool,
                pool(
                    "idle",
                    List.of("model-shared"),
                    Map.of("fast", "model-shared"),
                    Duration.ofSeconds(5),
                    idle)),
            new NoOpTokenLedger());
    ExecutorService occupant = Executors.newSingleThreadExecutor();

    try {
      // The one slot taken, and deliberately nothing queued behind it.
      occupant.execute(
          () ->
              busyPool.complete(
                  "model-shared",
                  ChatMessage.conversation(null, "held"),
                  Sampling.NONE,
                  List.of(),
                  null,
                  Duration.ofSeconds(5)));
      assertTrue(busy.entered.await(2, TimeUnit.SECONDS));

      assertEquals(
          0,
          busyPool.queueDepth(Lane.CHAT),
          "a full lane with an empty queue is what depth cannot see");
      assertEquals(1.0d, busyPool.load(Lane.CHAT));
      assertEquals(0.0d, busyPool.load(Lane.EMBEDDING));

      dispatcher.complete(ChatRequest.of("fast", null, "hello"));

      assertEquals(1, idle.calls.get(), "the idle pool should have won");
      assertEquals(1, busy.calls.get(), "the busy pool saw only its occupant");
    } finally {
      release.countDown();
      occupant.shutdown();
      dispatcher.close();
      assertTrue(occupant.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  /**
   * Capacity is half the metric: three of eight slots beats one of one.
   *
   * <p>Occupancy alone still misjudges hosts of different sizes. Three requests on an eight-slot
   * box leave five slots free and the next call starts at once; one request on a one-slot box
   * leaves none and the next call queues. An unnormalised comparison reads 3 against 1 and picks
   * the box that is completely full — and the reference machines being an M1 Max and an M5 Max,
   * differently sized pools are the expected deployment, not an edge case.
   *
   * <p>Every other pool in this class has exactly one slot, which makes dividing by the slot count
   * and not dividing by it the same arithmetic. This is the only case that can tell them apart.
   */
  @Test
  void a_big_pool_with_slots_to_spare_beats_a_small_one_that_is_full() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    // Four: the three occupants, and then the call the dispatcher routes.
    FakeTransport big = new FakeTransport("big", 4, release);
    FakeTransport small = new FakeTransport("small", 1, release);
    // Both pools serve the SAME model, which is the only shape a class may
    // be answered by two pools in: `fast` names one model server-wide, so
    // two pools answering it are two nodes with that model loaded. This
    // fixture used to give each pool its own model behind `fast`, which is
    // the configuration LlmPool.resolve now refuses to produce.
    LlmPool bigPool =
        new LlmPool(
            "big",
            List.of("model-shared"),
            Map.of("fast", "model-shared"),
            8,
            1,
            Duration.ofSeconds(5),
            big);
    LlmPool smallPool =
        new LlmPool(
            "small",
            List.of("model-shared"),
            Map.of("fast", "model-shared"),
            1,
            1,
            Duration.ofSeconds(5),
            small);
    LlmDispatcher dispatcher =
        new LlmDispatcher(List.of(bigPool, smallPool), new NoOpTokenLedger());
    ExecutorService callers = Executors.newFixedThreadPool(5);

    try {
      for (int i = 0; i < 3; i++) {
        callers.execute(
            () ->
                bigPool.complete(
                    "model-shared",
                    ChatMessage.conversation(null, "held"),
                    Sampling.NONE,
                    List.of(),
                    null,
                    Duration.ofSeconds(5)));
      }
      callers.execute(
          () ->
              smallPool.complete(
                  "model-shared",
                  ChatMessage.conversation(null, "held"),
                  Sampling.NONE,
                  List.of(),
                  null,
                  Duration.ofSeconds(5)));
      awaitCalls(big, 3);
      assertTrue(small.entered.await(2, TimeUnit.SECONDS));

      assertEquals(0.375d, bigPool.load(Lane.CHAT));
      assertEquals(1.0d, smallPool.load(Lane.CHAT));
      // Neither has queued anything, so depth says the two are identical.
      assertEquals(0, bigPool.queueDepth(Lane.CHAT));
      assertEquals(0, smallPool.queueDepth(Lane.CHAT));

      callers.execute(() -> dispatcher.complete(ChatRequest.of("fast", null, "routed")));

      assertTrue(
          big.entered.await(2, TimeUnit.SECONDS),
          "the routed call never reached the pool with slots to spare");
      assertEquals(1, small.calls.get(), "the full pool saw only its own occupant");
    } finally {
      release.countDown();
      callers.shutdown();
      dispatcher.close();
      assertTrue(callers.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  /**
   * A request that names no budget gets the pool's, and the dispatcher does not invent one.
   *
   * <p>The class javadoc leads with this — "passed through untouched, {@code null} included" — and
   * it was the one claim nothing tested. Every case that makes the dispatcher actually wait passes
   * an explicit budget, and every case with a null budget routes to a free lane and never queues,
   * so a dispatcher substituting its own default <em>only for null</em> was green on the whole
   * suite. The argument mutants cannot reach it either: they replace the value unconditionally,
   * which the explicit-budget tests catch first. Only a null budget that has to queue can see it,
   * which is this.
   */
  @Test
  void a_request_with_no_budget_waits_only_as_long_as_the_pool_default() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    FakeTransport transport = new FakeTransport("studio", 1, release);
    LlmPool impatient = pool("studio", List.of("m"), Map.of(), Duration.ofMillis(1), transport);
    LlmDispatcher dispatcher = new LlmDispatcher(List.of(impatient), new NoOpTokenLedger());
    ExecutorService occupant = Executors.newSingleThreadExecutor();

    try {
      occupant.execute(
          () ->
              impatient.complete(
                  "m",
                  ChatMessage.conversation(null, "held"),
                  Sampling.NONE,
                  List.of(),
                  null,
                  Duration.ofSeconds(5)));
      assertTrue(transport.entered.await(2, TimeUnit.SECONDS));

      long began = System.nanoTime();
      assertThrows(
          LlmSaturatedException.class,
          () -> dispatcher.complete(ChatRequest.of("m", null, "hello")));
      long elapsedMillis = (System.nanoTime() - began) / 1_000_000L;

      // Any default of the dispatcher's own would be orders of magnitude
      // longer than the 1ms this pool declares.
      assertTrue(elapsedMillis < 2_000L, "waited " + elapsedMillis + "ms on a 1ms default");
    } finally {
      release.countDown();
      occupant.shutdown();
      dispatcher.close();
      assertTrue(occupant.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  /**
   * {@code system} and {@code system.<type>} are not classes and not wire models: {@link
   * io.aeyer.plowshare.server.llm.LlmProperties#systemSpecifier} names what they resolve to, and
   * the dispatcher is what makes that name actually routable, since nothing before this fed it to
   * {@link #route}.
   *
   * <h2>Why this lives here and not only on {@code LlmPropertiesTest}</h2>
   *
   * <p>{@code LlmPropertiesTest} proves {@code systemSpecifier} picks the right string. It proves
   * nothing about whether a call made with {@code "system"} actually reaches a pool — that is this
   * class's whole job, and the constructor overload below is how a caller hands the dispatcher the
   * binding to resolve against, {@code props::systemSpecifier} included, without {@code
   * LlmDispatcher} taking a compile-time dependency on {@code LlmProperties} itself.
   */
  @Test
  void a_system_specifier_routes_through_the_configured_binding() {
    FakeTransport transport = FakeTransport.free("studio");
    io.aeyer.plowshare.server.llm.LlmProperties props =
        new io.aeyer.plowshare.server.llm.LlmProperties();
    props.setSystem("wire-model");
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                pool("studio", List.of("wire-model"), Map.of(), Duration.ofSeconds(5), transport)),
            new NoOpTokenLedger(),
            props::systemSpecifier);
    try {
      dispatcher.complete(ChatRequest.of("system", null, "hello"));
      assertEquals(List.of("wire-model"), transport.modelsSeen());
    } finally {
      dispatcher.close();
    }
  }

  /**
   * {@code system.memory} is not {@code system}: a type with its own override must reach the pool
   * that override names, not the plain binding's pool.
   */
  @Test
  void a_type_specific_system_specifier_prefers_its_override_over_the_plain_binding() {
    FakeTransport general = FakeTransport.free("general");
    FakeTransport memoryHost = FakeTransport.free("memory-host");
    io.aeyer.plowshare.server.llm.LlmProperties props =
        new io.aeyer.plowshare.server.llm.LlmProperties();
    props.setSystem("general-model");
    props.setSystemOverrides(Map.of("memory", "memory-model"));
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                pool("general", List.of("general-model"), Map.of(), Duration.ofSeconds(5), general),
                pool(
                    "memory-host",
                    List.of("memory-model"),
                    Map.of(),
                    Duration.ofSeconds(5),
                    memoryHost)),
            new NoOpTokenLedger(),
            props::systemSpecifier);
    try {
      dispatcher.complete(ChatRequest.of("system.memory", null, "hello"));
      assertEquals(List.of("memory-model"), memoryHost.modelsSeen());
      assertEquals(List.of(), general.modelsSeen());
    } finally {
      dispatcher.close();
    }
  }

  /**
   * The two-argument constructor is not a shorthand for "no system binding configured yet"
   * answering some default — it is the same refusal as an unserved model or class, naming the
   * specifier the caller actually wrote rather than the empty string {@code systemSpecifier}
   * answers when nothing is bound. An operator reading "no pool serves ''" would have nothing to
   * grep for; "no pool serves 'system.memory'" names the key to set.
   */
  @Test
  void a_system_specifier_with_no_binding_set_is_refused_by_the_name_it_was_asked_under() {
    FakeTransport transport = FakeTransport.free("studio");
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                pool("studio", List.of("wire-model"), Map.of(), Duration.ofSeconds(5), transport)),
            new NoOpTokenLedger());
    try {
      UnknownSpecifierException plain =
          assertThrows(
              UnknownSpecifierException.class,
              () -> dispatcher.complete(ChatRequest.of("system", null, "hello")));
      assertTrue(plain.getMessage().contains("system"), plain.getMessage());

      UnknownSpecifierException typed =
          assertThrows(
              UnknownSpecifierException.class,
              () -> dispatcher.complete(ChatRequest.of("system.memory", null, "hello")));
      assertTrue(typed.getMessage().contains("system.memory"), typed.getMessage());
    } finally {
      dispatcher.close();
    }
  }

  /**
   * {@code plowshare.llm.system} naming {@code system} or {@code system.<type>} would need a second
   * resolution step this class does not take — not because a second step is hard, but because
   * nothing bounds how many are left to take before one of them finally names a class or a wire
   * model, and {@code system: system} loops forever on the first. Refused outright instead, on the
   * same terms an unsatisfiable specifier is.
   */
  @Test
  void a_system_binding_that_points_at_another_system_binding_is_refused_rather_than_followed() {
    FakeTransport transport = FakeTransport.free("studio");
    io.aeyer.plowshare.server.llm.LlmProperties props =
        new io.aeyer.plowshare.server.llm.LlmProperties();
    props.setSystem("system.memory");
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                pool("studio", List.of("wire-model"), Map.of(), Duration.ofSeconds(5), transport)),
            new NoOpTokenLedger(),
            props::systemSpecifier);
    try {
      LlmException failed =
          assertThrows(
              LlmException.class,
              () -> dispatcher.complete(ChatRequest.of("system", null, "hello")));
      assertFalse(failed instanceof UnknownSpecifierException, failed.getMessage());
      assertTrue(failed.getMessage().contains("system.memory"), failed.getMessage());
    } finally {
      dispatcher.close();
    }
  }

  /**
   * {@code wireModelFor} answers a {@code system} specifier with the wire model the binding
   * actually names, not {@code null}.
   *
   * <h2>Why {@code null} would have been the wrong kind of green</h2>
   *
   * <p>Before {@code resolveSpecifier} reached this method, {@code pool.resolve("system")} answered
   * {@code null} for every pool — no pool's {@code models} or {@code classes} ever contains the
   * literal string {@code "system"} — so {@code wireModelFor("system")} returned {@code null}
   * exactly as it does for a specifier that really is unserved. That is the shape {@code
   * application.yml}'s comment on {@code SamplingProfiles.resolve} warns about one call further on:
   * {@code profiles.resolve(null, ...)} would not throw, it would silently answer the default
   * profile, which is indistinguishable from the model itself having none. Asserting the real wire
   * model name here is what a mutant deleting {@code resolveSpecifier}'s call from this method
   * cannot survive — deleting it leaves this equal to {@code null}, not {@code "wire-model"}.
   */
  @Test
  void wireModelFor_answers_a_system_specifier_with_the_wire_model_it_is_bound_to() {
    FakeTransport transport = FakeTransport.free("studio");
    io.aeyer.plowshare.server.llm.LlmProperties props =
        new io.aeyer.plowshare.server.llm.LlmProperties();
    props.setSystem("wire-model");
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                pool("studio", List.of("wire-model"), Map.of(), Duration.ofSeconds(5), transport)),
            new NoOpTokenLedger(),
            props::systemSpecifier);
    try {
      assertEquals("wire-model", dispatcher.wireModelFor("system"));
    } finally {
      dispatcher.close();
    }
  }

  /**
   * {@code contextLength} bounds a {@code system.<type>} specifier against the pool its override
   * actually names, not against {@code fallback} by way of "nothing serves it".
   *
   * <p>Two pools serve two different models here, exactly as {@link
   * #a_context_length_is_still_per_pool_for_one_model_two_pools_serve} sets up for an ordinary
   * class — except the specifier under test is {@code system.memory}, whose override names the
   * tight pool's model. Before {@code resolveSpecifier} reached this method, {@code
   * pool.resolve("system.memory")} was {@code null} on both pools, {@code served} stayed {@code
   * false}, and this answered {@code OptionalInt.empty()} — not {@code fallback} either, which is
   * the exact "advisory, not a typo" case {@link LlmDispatcher#contextLength(String, int)
   * contextLength}'s own javadoc distinguishes from an unbound {@code system} key.
   */
  @Test
  void contextLength_bounds_a_type_specific_system_specifier_against_the_pool_its_override_names() {
    FakeTransport roomy = FakeTransport.free("roomy");
    roomy.contextLengths.put("general-model", 128000);
    FakeTransport tight = FakeTransport.free("tight");
    tight.contextLengths.put("memory-model", 32000);
    io.aeyer.plowshare.server.llm.LlmProperties props =
        new io.aeyer.plowshare.server.llm.LlmProperties();
    props.setSystem("general-model");
    props.setSystemOverrides(Map.of("memory", "memory-model"));
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                pool("roomy", List.of("general-model"), Map.of(), Duration.ofSeconds(5), roomy),
                pool("tight", List.of("memory-model"), Map.of(), Duration.ofSeconds(5), tight)),
            new NoOpTokenLedger(),
            props::systemSpecifier);
    try {
      assertEquals(
          OptionalInt.of(32000),
          dispatcher.contextLength("system.memory", 64000),
          "the override's own pool bounds it, not the plain binding's, and not the"
              + " fallback either");
    } finally {
      dispatcher.close();
    }
  }

  /**
   * {@code contextLength} and {@code compactionThreshold} both raise for an unbound {@code system}
   * key rather than answering {@code fallback} or empty the way an ordinary unserved specifier
   * does.
   *
   * <h2>Why this, and not a numeric answer, is what pins the fix</h2>
   *
   * <p>Neither method's javadoc lets an unserved specifier throw — that is the deliberate, argued
   * behaviour for a name that simply is not served, so it could not be what this test asserts. An
   * unbound {@code system} key is the one case both javadocs carve out as an exception: {@code
   * resolveSpecifier} raises {@link UnknownSpecifierException} before either method's loop ever
   * runs, because nothing later in either caller's own flow would surface a misconfigured binding
   * otherwise. Reverting the {@code resolveSpecifier} call at either site turns this from a thrown
   * exception into a quiet {@code OptionalInt.empty()} — which is exactly what {@code assertThrows}
   * below would fail to observe.
   */
  @Test
  void contextLength_and_compactionThreshold_both_raise_for_an_unbound_system_key() {
    FakeTransport transport = FakeTransport.free("studio");
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                pool("studio", List.of("wire-model"), Map.of(), Duration.ofSeconds(5), transport)),
            new NoOpTokenLedger());
    try {
      UnknownSpecifierException fromContextLength =
          assertThrows(
              UnknownSpecifierException.class, () -> dispatcher.contextLength("system", 64000));
      assertTrue(fromContextLength.getMessage().contains("system"), fromContextLength.getMessage());

      UnknownSpecifierException fromCompactionThreshold =
          assertThrows(
              UnknownSpecifierException.class,
              () -> dispatcher.compactionThreshold("system.compaction"));
      assertTrue(
          fromCompactionThreshold.getMessage().contains("system.compaction"),
          fromCompactionThreshold.getMessage());
    } finally {
      dispatcher.close();
    }
  }

  @Test
  void the_pools_serving_a_specifier_are_every_one_that_resolves_it_in_declared_order() {
    LlmPool first =
        pool("first", List.of("m"), Map.of("fast", "m"), FIVE_SECONDS, FakeTransport.free("first"));
    LlmPool other =
        pool("other", List.of("x"), Map.of(), FIVE_SECONDS, FakeTransport.free("other"));
    LlmPool second =
        pool(
            "second",
            List.of("m"),
            Map.of("fast", "m"),
            FIVE_SECONDS,
            FakeTransport.free("second"));
    LlmDispatcher dispatcher =
        new LlmDispatcher(List.of(first, other, second), new NoOpTokenLedger());
    try {
      assertEquals(List.of(first, second), dispatcher.poolsServing("fast"));
      assertEquals(List.of(other), dispatcher.poolsServing("x"));
      assertEquals(List.of(), dispatcher.poolsServing("nobody-serves-this"));
      assertEquals(List.of(first, other, second), dispatcher.pools());
    } finally {
      first.close();
      other.close();
      second.close();
    }
  }

  @Test
  void streaming_on_a_named_pool_goes_there_however_light_the_other_is() {
    FakeTransport firstTransport = FakeTransport.free("first");
    FakeTransport secondTransport = FakeTransport.free("second");
    LlmPool first = pool("first", List.of("m"), Map.of(), FIVE_SECONDS, firstTransport);
    LlmPool second = pool("second", List.of("m"), Map.of(), FIVE_SECONDS, secondTransport);
    LlmDispatcher dispatcher = new LlmDispatcher(List.of(first, second), new NoOpTokenLedger());
    try {
      Completion completion =
          dispatcher.streamOn(
              "second",
              ChatRequest.of("m", List.of(ChatMessage.user("hello"))),
              Deltas.DISCARDING,
              () -> false);
      assertEquals("second", completion.servedBy().pool());
      assertEquals(0, firstTransport.calls.get());
      assertEquals(1, secondTransport.calls.get());
    } finally {
      first.close();
      second.close();
    }
  }

  @Test
  void streaming_on_a_pool_that_does_not_serve_the_model_is_refused() {
    LlmPool first =
        pool("first", List.of("m"), Map.of(), FIVE_SECONDS, FakeTransport.free("first"));
    LlmDispatcher dispatcher = new LlmDispatcher(List.of(first), new NoOpTokenLedger());
    try {
      assertThrows(
          UnknownSpecifierException.class,
          () ->
              dispatcher.streamOn(
                  "first",
                  ChatRequest.of("x", List.of(ChatMessage.user("hello"))),
                  Deltas.DISCARDING,
                  () -> false));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              dispatcher.streamOn(
                  "missing",
                  ChatRequest.of("m", List.of(ChatMessage.user("hello"))),
                  Deltas.DISCARDING,
                  () -> false));
    } finally {
      first.close();
    }
  }

  /**
   * Blocks in one lane and answers at once in the other.
   *
   * <p>Not a {@link FakeTransport} option: that fake holds one release latch for every call site,
   * which is right for the pool's tests and makes it impossible to hold one of a pool's lanes open
   * while the other still answers — which is the whole of the two routing cases above. Kept local
   * rather than widening the shared fake's blocking semantics.
   */
  private static final class OneLaneBlockingTransport implements LlmTransport {

    private final String name;
    private final Lane blocking;
    private final CountDownLatch release;

    final CountDownLatch entered = new CountDownLatch(1);
    final List<String> chatted = java.util.Collections.synchronizedList(new ArrayList<>());
    final List<String> embedded = java.util.Collections.synchronizedList(new ArrayList<>());

    OneLaneBlockingTransport(String name, Lane blocking, CountDownLatch release) {
      this.name = name;
      this.blocking = blocking;
      this.release = release;
    }

    @Override
    public String poolName() {
      return name;
    }

    @Override
    public Completion complete(
        String wireModel, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      chatted.add(wireModel);
      hold(Lane.CHAT);
      // "answer for <the prompt>", as before the conversation became a
      // list: the fake's behaviour is unchanged, only where it reads the
      // prompt from.
      return new Completion(
          "answer for " + messages.get(messages.size() - 1).content(),
          "stop",
          TokenUsage.UNKNOWN,
          List.of());
    }

    @Override
    public Completion stream(
        String wireModel,
        List<ChatMessage> messages,
        Sampling sampling,
        List<ToolSchema> tools,
        Deltas sink,
        BooleanSupplier abandoned) {
      chatted.add(wireModel);
      hold(Lane.CHAT);
      sink.answered("answer");
      return new Completion("answer", "stop", TokenUsage.UNKNOWN, List.of());
    }

    @Override
    public Embeddings embed(String wireModel, List<String> input) {
      embedded.add(wireModel);
      hold(Lane.EMBEDDING);
      List<float[]> vectors = new ArrayList<>(input.size());
      for (int i = 0; i < input.size(); i++) {
        vectors.add(new float[] {1.0f});
      }
      return new Embeddings(vectors, TokenUsage.UNKNOWN);
    }

    private void hold(Lane lane) {
      if (lane != blocking) {
        return;
      }
      entered.countDown();
      try {
        // Bounded, so a mistake in the test fails it rather than hanging
        // the build.
        if (!release.await(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException(
              "the " + lane.wireName() + " lane of '" + name + "' was never released");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted in the fake transport", e);
      }
    }

    @Override
    public void close() {
      // Nothing held.
    }
  }
}
