package io.aeyer.plowshare.server.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmPool;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransport;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransportException;
import io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import io.aeyer.plowshare.server.llm.openai.OpenAiTransport;
import io.aeyer.plowshare.server.llm.tokens.RatioTokenizer;
import io.aeyer.plowshare.server.llm.tokens.TokenCount;
import io.aeyer.plowshare.server.llm.tokens.Tokenizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * The seam that keeps {@code Archive} unchanged.
 *
 * <p>Everything the dispatcher can fail with arrives here as an {@code EmbeddingException}, because
 * that is what the write path catches — and a failure that is not one destroys the memory instead
 * of keeping it.
 */
class DispatchingEmbeddingClientTest {

  /** Answers with whatever width the test asks for, and records what it was asked for. */
  private static final class StubTransport implements LlmTransport {
    /**
     * Not final: the width has to be able to change between calls, which is the whole of what
     * a_model_that_changes_width_between_batches_is_still_ caught asserts.
     */
    int dim;

    final List<String> modelsSeen = new ArrayList<>();
    RuntimeException failWith;
    int shortBy;

    StubTransport(int dim) {
      this.dim = dim;
    }

    @Override
    public String poolName() {
      return "studio";
    }

    @Override
    public Completion complete(String m, List<ChatMessage> c, Sampling s, List<ToolSchema> x) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Completion stream(
        String m,
        List<ChatMessage> c,
        Sampling s,
        List<ToolSchema> tools,
        Deltas sink,
        BooleanSupplier abandoned) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Embeddings embed(String wireModel, List<String> input) {
      modelsSeen.add(wireModel);
      if (failWith != null) {
        throw failWith;
      }
      List<float[]> vectors = new ArrayList<>();
      for (int i = 0; i < input.size() - shortBy; i++) {
        vectors.add(new float[dim]);
      }
      return new Embeddings(vectors, TokenUsage.UNKNOWN);
    }

    @Override
    public void close() {}
  }

  private static LlmDispatcher dispatcherOver(LlmTransport transport) {
    return new LlmDispatcher(
        List.of(
            new LlmPool(
                "studio",
                List.of("nomic-embed-text"),
                Map.of(),
                1,
                1,
                Duration.ofSeconds(5),
                transport)),
        new NoOpTokenLedger());
  }

  /**
   * Deterministic fixture vocabulary for transport behavior; real tokenizer coverage is separate.
   */
  private static final Tokenizer TOKENIZER =
      new io.aeyer.plowshare.server.llm.tokens.FixtureTokenizer(
          RatioTokenizer.DEFAULT_CHARACTERS_PER_TOKEN);

  /** The shipped ceiling, {@code plowshare.llm.embedding-max-input-tokens}. */
  private static final int MAX = 1536;

  private static DispatchingEmbeddingClient clientOver(LlmDispatcher dispatcher, int dim) {
    return clientOver(dispatcher, dim, TOKENIZER);
  }

  private static DispatchingEmbeddingClient clientOver(
      LlmDispatcher dispatcher, int dim, Tokenizer tokenizer) {
    LlmProperties props = new LlmProperties();
    props.setEmbeddingModel("nomic-embed-text");
    props.setEmbeddingDim(dim);
    // The shipped ceiling, so this helper builds the client production
    // builds. A missing bound is refused at boot and refused again on the
    // first call that needs one; a fixture that left it out would be
    // testing a client no server can start with.
    props.setEmbeddingMaxInputTokens(MAX);
    return new DispatchingEmbeddingClient(dispatcher, props, tokenizer);
  }

  @Test
  void it_submits_the_one_configured_embedding_model() {
    StubTransport transport = new StubTransport(768);
    LlmDispatcher dispatcher = dispatcherOver(transport);
    try {
      float[] vector = clientOver(dispatcher, 768).embed("hello");
      assertEquals(768, vector.length);
      assertEquals(List.of("nomic-embed-text"), transport.modelsSeen);
    } finally {
      dispatcher.close();
    }
  }

  /**
   * An archive with nothing to embed is an ordinary first-run state: not an error, and not a round
   * trip.
   */
  @Test
  void an_empty_batch_is_not_a_call() {
    StubTransport transport = new StubTransport(768);
    LlmDispatcher dispatcher = dispatcherOver(transport);
    try {
      assertEquals(List.of(), clientOver(dispatcher, 768).embedAll(List.of()));
      assertEquals(List.of(), transport.modelsSeen);
    } finally {
      dispatcher.close();
    }
  }

  @Test
  void a_transport_failure_arrives_as_an_embedding_failure() {
    StubTransport transport = new StubTransport(768);
    transport.failWith = new LlmTransportException("pool 'studio': HTTP 503 busy");
    LlmDispatcher dispatcher = dispatcherOver(transport);
    try {
      EmbeddingException failed =
          assertThrows(EmbeddingException.class, () -> clientOver(dispatcher, 768).embed("hello"));
      assertTrue(failed.getMessage().contains("503"), failed.getMessage());
    } finally {
      dispatcher.close();
    }
  }

  /**
   * The regression guard for the bug that cost a memory.
   *
   * <p>A malformed base URL escapes OkHttp as {@code IllegalArgumentException}, past {@code
   * Archive.embed}'s narrow catch, losing the write. This drives the real transport rather than a
   * stub, because a stub cannot reproduce it: the throw happens inside OkHttp.
   */
  @Test
  void a_misconfigured_endpoint_is_an_embedding_failure_and_never_the_caller_s() {
    PoolProperties props = new PoolProperties();
    props.setName("studio");
    props.setBaseUrl("localhost:1234/v1");

    LlmDispatcher dispatcher = dispatcherOver(new OpenAiTransport(props, new ObjectMapper()));
    try {
      EmbeddingException failed =
          assertThrows(EmbeddingException.class, () -> clientOver(dispatcher, 768).embed("hello"));
      assertTrue(failed.getMessage().contains("base-url"), failed.getMessage());
    } finally {
      dispatcher.close();
    }
  }

  /**
   * Saturation is an endpoint problem too, as far as the write path is concerned: keep the write,
   * leave the embedding NULL, carry on.
   */
  @Test
  void saturation_arrives_as_an_embedding_failure() throws InterruptedException {
    BlockingTransport transport = new BlockingTransport();
    // One slot, a 1ms default budget, and a call already holding it.
    LlmDispatcher dispatcher =
        new LlmDispatcher(
            List.of(
                new LlmPool(
                    "studio",
                    List.of("nomic-embed-text"),
                    Map.of(),
                    1,
                    1,
                    Duration.ofMillis(1),
                    transport)),
            new NoOpTokenLedger());
    DispatchingEmbeddingClient client = clientOver(dispatcher, 768);

    Thread occupant =
        new Thread(
            () -> {
              try {
                client.embed("held");
              } catch (RuntimeException ignored) {
                // The occupant's own outcome is not what this test asserts.
              }
            });
    occupant.setDaemon(true);

    try {
      occupant.start();
      assertTrue(transport.entered.await(2, TimeUnit.SECONDS));

      assertThrows(EmbeddingException.class, () -> client.embed("queued"));
    } finally {
      transport.release.countDown();
      occupant.join(5_000L);
      dispatcher.close();
    }
  }

  /**
   * Fail on the first response from a model of the wrong width, not on the INSERT.
   *
   * <p>{@code vector(768)} is fixed in the migration, so a 1024-wide model would otherwise surface
   * as a Postgres type error under the write path, several layers from the configuration that
   * caused it — after which every memory written before someone noticed has to be re-embedded.
   */
  @Test
  void a_model_of_the_wrong_width_fails_before_the_insert_does() {
    StubTransport transport = new StubTransport(1024);
    LlmDispatcher dispatcher = dispatcherOver(transport);
    try {
      EmbeddingException failed =
          assertThrows(EmbeddingException.class, () -> clientOver(dispatcher, 768).embed("hello"));
      assertTrue(failed.getMessage().contains("768"), failed.getMessage());
      assertTrue(failed.getMessage().contains("1024"), failed.getMessage());
      assertTrue(failed.getMessage().contains("embedding-dim"), failed.getMessage());
    } finally {
      dispatcher.close();
    }
  }

  /**
   * The one failure this class can manufacture itself, and it must not wear the caller's currency.
   *
   * <p>{@code EmbeddingRequest} refuses a blank specifier with an {@code IllegalArgumentException},
   * which is right for a caller that named no model. This caller names no model either — it reads
   * {@code plowshare.llm.embedding-model} — so a blank value is a misconfiguration arriving as the
   * type reserved for a bad request: past {@code Archive.embed}'s narrow catch, taking the memory
   * with it, and out as a 500. That is the base-url incident with a different property in it.
   * Remove the guard in {@code embedAll} and this test fails with {@code IllegalArgumentException},
   * which is the whole point of it.
   */
  @Test
  void an_unconfigured_embedding_model_is_an_embedding_failure_not_a_caller_error() {
    StubTransport transport = new StubTransport(768);
    LlmDispatcher dispatcher = dispatcherOver(transport);
    LlmProperties props = new LlmProperties();
    props.setEmbeddingModel("   ");
    props.setEmbeddingDim(768);
    props.setEmbeddingMaxInputTokens(MAX);
    try {
      EmbeddingException failed =
          assertThrows(
              EmbeddingException.class,
              () -> new DispatchingEmbeddingClient(dispatcher, props, TOKENIZER).embed("hello"));
      assertTrue(failed.getMessage().contains("embedding-model"), failed.getMessage());
      assertEquals(List.of(), transport.modelsSeen, "nothing should have been submitted");
    } finally {
      dispatcher.close();
    }
  }

  // --- the input-side ceiling ----------------------------------------------

  /**
   * <b>Nothing this server cannot embed is ever sent to be embedded.</b>
   *
   * <p>The whole of what was guarded before this was the response: {@code verifyDim} checks the
   * width of what comes back, on every batch, with a careful account of why. Nothing at all checked
   * what went in. That is survivable while every embedded string is a short generated label — the
   * archive embeds a memory's summary plus its scope, short by construction — and it stops being
   * survivable the moment documents land, because a chunk's text is unbounded content somebody
   * uploaded.
   *
   * <p>Refused, and specifically not truncated. A silently truncated input produces a vector for
   * text that is not the text: the row is perfect, the search misses it, and nothing goes red. That
   * is the same class of failure as a wrong vector against a right memory, which is what {@code
   * verifyDim} exists for at the other end of the same call.
   */
  @Test
  void an_input_past_the_models_window_is_refused_before_the_call_is_made() {
    StubTransport transport = new StubTransport(768);
    LlmDispatcher dispatcher = dispatcherOver(transport);
    try {
      EmbeddingException refused =
          assertThrows(
              EmbeddingException.class, () -> clientOver(dispatcher, 768).embed("x".repeat(7000)));

      assertTrue(refused.getMessage().contains("1750 tokens"), refused.getMessage());
      assertTrue(refused.getMessage().contains("1536"), refused.getMessage());
      assertTrue(
          refused.getMessage().contains(TOKENIZER.describe()),
          "an operator reading the refusal has to be told the count is an estimate: "
              + refused.getMessage());
      assertEquals(
          List.of(), transport.modelsSeen, "an oversized input must not reach the endpoint at all");
    } finally {
      dispatcher.close();
    }
  }

  /**
   * The batch is checked whole before any of it is sent, and the message says which input is the
   * problem.
   *
   * <p>This is the half that costs a document in Anchor. {@code EmbeddingService.embedAll} sends
   * every chunk of a document as ONE HTTP request, so one oversized chunk fails the whole batch —
   * after the entire summarisation cascade has been paid for, and with nothing saying which chunk
   * it was. Naming the index is what turns that into something somebody can act on.
   */
  @Test
  void a_batch_is_refused_whole_and_the_message_names_which_input() {
    StubTransport transport = new StubTransport(768);
    LlmDispatcher dispatcher = dispatcherOver(transport);
    try {
      EmbeddingException refused =
          assertThrows(
              EmbeddingException.class,
              () ->
                  clientOver(dispatcher, 768)
                      .embedAll(List.of("short", "x".repeat(4 * MAX + 1), "also short")));

      assertTrue(refused.getMessage().contains("index 1"), refused.getMessage());
      assertEquals(List.of(), transport.modelsSeen);
    } finally {
      dispatcher.close();
    }
  }

  /**
   * The ceiling is counted by the configured {@link Tokenizer}, and by nothing this class works out
   * for itself.
   *
   * <p>Bytes are not tokens. The heuristic that ships is meant to be replaced by a real tokenizer
   * behind the same interface, and a client that measured bytes or characters of its own would go
   * on refusing by that measure whatever was configured. A tokenizer that charges one past the
   * ceiling for anything refuses a five-character input; one that charges a single token sends ten
   * thousand characters.
   */
  @Test
  void the_ceiling_is_counted_by_the_configured_tokenizer() {
    StubTransport transport = new StubTransport(768);
    LlmDispatcher dispatcher = dispatcherOver(transport);
    try {
      EmbeddingException refused =
          assertThrows(
              EmbeddingException.class,
              () -> clientOver(dispatcher, 768, flat(MAX + 1)).embed("hello"));
      assertTrue(refused.getMessage().contains((MAX + 1) + " tokens"), refused.getMessage());
      assertEquals(List.of(), transport.modelsSeen);

      clientOver(dispatcher, 768, flat(1)).embed("x".repeat(10_000));
      assertEquals(List.of("nomic-embed-text"), transport.modelsSeen);
    } finally {
      dispatcher.close();
    }
  }

  /**
   * What this change was for: a digest summary, "at most 300 words" of prose, lands between two and
   * three kilobytes, and the old 2048-byte ceiling refused every one of them — 26 digests on the
   * reference deployment kept a NULL embedding. As tokens it is well inside the window.
   */
  @Test
  void a_digest_sized_summary_is_sent() {
    StubTransport transport = new StubTransport(768);
    LlmDispatcher dispatcher = dispatcherOver(transport);
    String summary = "The archive distinguishes retired records from current ones. ".repeat(45);
    try {
      assertTrue(summary.length() > 2700, "the fixture is past the old byte ceiling");
      clientOver(dispatcher, 768).embed(summary);
      assertEquals(List.of("nomic-embed-text"), transport.modelsSeen);
    } finally {
      dispatcher.close();
    }
  }

  @Test
  void an_estimate_below_the_ceiling_cannot_authorize_an_embedding_call() {
    StubTransport transport = new StubTransport(768);
    LlmDispatcher dispatcher = dispatcherOver(transport);
    try {
      assertThrows(
          EmbeddingException.class,
          () -> clientOver(dispatcher, 768, new RatioTokenizer(4)).embed("a:2 ".repeat(1000)));
      assertTrue(transport.modelsSeen.isEmpty());
    } finally {
      dispatcher.close();
    }
  }

  private static Tokenizer flat(int tokens) {
    return new Tokenizer() {
      @Override
      public TokenCount count(String text) {
        return TokenCount.measured(tokens, "fixture vocabulary");
      }

      @Override
      public String describe() {
        return "a flat " + tokens;
      }
    };
  }

  /**
   * An input exactly at the ceiling is inside it. An off-by-one here throws away a slice of the
   * window on every chunk in the corpus.
   */
  @Test
  void an_input_exactly_at_the_ceiling_is_sent() {
    StubTransport transport = new StubTransport(768);
    LlmDispatcher dispatcher = dispatcherOver(transport);
    try {
      clientOver(dispatcher, 768).embed("x".repeat(4 * MAX));
      assertEquals(List.of("nomic-embed-text"), transport.modelsSeen);
    } finally {
      dispatcher.close();
    }
  }

  /**
   * A missing ceiling is a misconfiguration and never "no ceiling".
   *
   * <p>The exact twin of the blank-model guard above, and refused in this class's currency for the
   * same reason: nobody who calls {@code embedAll} named this number — it is read from {@code
   * plowshare.llm}, so a missing value is the server's fault and not the caller's. Treating zero as
   * "no bound" would be a silent off-switch on the one guard standing between an uploaded document
   * and a vector for text that is not the text.
   */
  @Test
  void a_missing_ceiling_is_a_misconfiguration_and_not_an_absent_bound() {
    StubTransport transport = new StubTransport(768);
    LlmDispatcher dispatcher = dispatcherOver(transport);
    LlmProperties props = new LlmProperties();
    props.setEmbeddingModel("nomic-embed-text");
    props.setEmbeddingDim(768);
    try {
      EmbeddingException failed =
          assertThrows(
              EmbeddingException.class,
              () -> new DispatchingEmbeddingClient(dispatcher, props, TOKENIZER).embed("hello"));
      assertTrue(failed.getMessage().contains("embedding-max-input-tokens"), failed.getMessage());
      assertEquals(List.of(), transport.modelsSeen);
    } finally {
      dispatcher.close();
    }
  }

  /**
   * The width is checked on every batch, not once for the process.
   *
   * <p>The first version of {@code verifyDim} returned early on a latch, on the reasoning that the
   * width cannot change under a running server. It can, twice over and without leaving this slice.
   * {@code LlmPool.resolve} matches a class name as well as a model name and {@code
   * LlmDispatcher.route} picks among every pool serving a specifier <em>by load</em>, so two pools
   * mapping one class to wire models of different widths make the width a load reading. And LM
   * Studio — the reference deployment — swaps the model behind a name with nothing restarted here.
   *
   * <p>What the latch cost is precisely this test: a 1024-wide vector waved through to an {@code
   * INSERT} against {@code vector(768)}, where it fails as a {@code DataAccessException} that
   * {@code Archive.embed} does not catch. Restore the early return and this is the test that fails;
   * nothing else in the suite does, which is why it is here.
   */
  @Test
  void a_model_that_changes_width_between_batches_is_still_caught() {
    StubTransport transport = new StubTransport(768);
    LlmDispatcher dispatcher = dispatcherOver(transport);
    try {
      DispatchingEmbeddingClient client = clientOver(dispatcher, 768);
      assertEquals(768, client.embed("first").length);

      // The same pool, the same specifier, a different model behind it.
      transport.dim = 1024;

      EmbeddingException failed =
          assertThrows(EmbeddingException.class, () -> client.embed("second"));
      assertTrue(failed.getMessage().contains("1024"), failed.getMessage());
    } finally {
      dispatcher.close();
    }
  }

  /**
   * A null input is the caller's bug and must not be dressed as the endpoint's.
   *
   * <p>The temptation is to make every failure here an {@code EmbeddingException} for symmetry with
   * the blank-model guard. That guard translates because a misconfiguration was wearing a
   * caller-error type; this is the inverse, and translating it would be the more expensive mistake.
   * {@code Archive.embed} swallows {@code EmbeddingException} by design, so a null bug in the
   * archive would write memories unembedded forever while the log blamed the endpoint, and the
   * agent would be told to retry something that can never succeed. A {@code 500} saying the server
   * is broken is the true answer, because it is.
   *
   * <p>Asserted rather than left implicit so that turning these into {@code EmbeddingException} has
   * to be a decision someone makes against a red test.
   */
  @Test
  void a_null_input_is_the_caller_s_bug_and_not_an_endpoint_failure() {
    StubTransport transport = new StubTransport(768);
    LlmDispatcher dispatcher = dispatcherOver(transport);
    try {
      DispatchingEmbeddingClient client = clientOver(dispatcher, 768);

      assertThrows(NullPointerException.class, () -> client.embed(null));
      assertThrows(NullPointerException.class, () -> client.embedAll(null));

      // A null inside the batch is EmbeddingRequest's refusal, which names
      // the offending index. Still not the endpoint's currency.
      List<String> withNull = new ArrayList<>();
      withNull.add("one");
      withNull.add(null);
      //
      // assertThrows on IllegalArgumentException is the whole assertion:
      // EmbeddingException extends RuntimeException directly, so the two
      // are unrelated types and nothing can satisfy both. An explicit
      // `refused instanceof EmbeddingException` check does not even
      // compile here, which is a stronger guarantee than a runtime one.
      assertThrows(IllegalArgumentException.class, () -> client.embedAll(withNull));

      assertEquals(List.of(), transport.modelsSeen, "nothing should have been submitted");
    } finally {
      dispatcher.close();
    }
  }

  @Test
  void a_short_batch_is_a_failure_here_too() {
    StubTransport transport = new StubTransport(768);
    transport.shortBy = 1;
    LlmDispatcher dispatcher = dispatcherOver(transport);
    try {
      assertThrows(
          EmbeddingException.class,
          () -> clientOver(dispatcher, 768).embedAll(List.of("one", "two")));
    } finally {
      dispatcher.close();
    }
  }

  /** A transport whose calls block until released, for the saturation case. */
  private static final class BlockingTransport implements LlmTransport {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    @Override
    public String poolName() {
      return "studio";
    }

    @Override
    public Completion complete(String m, List<ChatMessage> c, Sampling s, List<ToolSchema> x) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Completion stream(
        String m,
        List<ChatMessage> c,
        Sampling s,
        List<ToolSchema> tools,
        Deltas sink,
        BooleanSupplier abandoned) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Embeddings embed(String wireModel, List<String> input) {
      entered.countDown();
      try {
        if (!release.await(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException("blocking transport was never released");
        }
      } catch (InterruptedException e) {
        // Restored and then raised, never swallowed into a successful
        // answer: returning a vector after being interrupted would let
        // a real interruption bug in the pool pass as a clean call, and
        // the saturation assertion above would still be green.
        Thread.currentThread().interrupt();
        throw new IllegalStateException("blocking transport was interrupted", e);
      }
      return new Embeddings(List.of(new float[768]), TokenUsage.UNKNOWN);
    }

    @Override
    public void close() {}
  }
}
