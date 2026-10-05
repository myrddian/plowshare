package io.aeyer.plowshare.server.llm.openai;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.llm.PoolProperties;
import io.aeyer.plowshare.server.llm.dispatch.CallerAbandonedException;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Content;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.JsonSchema;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransportException;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.aeyer.plowshare.server.llm.dispatch.ToolChoice;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The one part of this slice that opens a socket — to a loopback port.
 *
 * <p><b>Every test here is bounded, and on a separate thread so the bound can be enforced rather
 * than merely reported.</b> The tests that pin "a call returns in bounded time" prove it by
 * arranging a server that never answers, so a regression that removed the bound — {@code
 * readTimeout(Duration.ZERO)} is OkHttp's way of spelling "no timeout", one plausible slip in a
 * refactor of the client construction — would not fail them. It would hang them.
 *
 * <p>Since slice 3c there is a floor under the whole suite as well: each of the three modules
 * carries a {@code junit-platform.properties} setting a 120-second default timeout in {@code
 * SEPARATE_THREAD} mode, so a wedged test anywhere fails rather than stopping the build. The thirty
 * seconds here is the tighter local bound and stays. This is the class that deliberately arranges
 * servers which never answer, and it should not need two minutes to say so.
 *
 * <p>{@code SEPARATE_THREAD} is named here rather than inherited from that file, and it is the half
 * that does the enforcing. {@code SAME_THREAD} — JUnit's own default — schedules an interrupt on
 * the test thread and then reports the overrun once the method returns: enough for a test blocked
 * in something interruptible, and no use at all for one that is not. Measured at slice 3c: a method
 * that swallows interrupts for 20 seconds ran to its end under a 2-second {@code SAME_THREAD}
 * bound, and failed at 2.6 seconds under a {@code SEPARATE_THREAD} one. This is the counterpart of
 * {@code LlmPoolTest}'s bounded latch awaits, for tests that have no latch to bound.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class OpenAiTransportTest {

  @Test
  void an_explicit_prompt_budget_covers_silent_prefill_beyond_the_pool_read_limit()
      throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(sse(BORDEAUX_STREAM).setBodyDelay(400, TimeUnit.MILLISECONDS));
      server.start();
      PoolProperties props = poolAt(server.url("/v1").toString());
      props.setStreamingTimeout(Duration.ofMillis(50));
      props.setMaxStreamDuration(Duration.ofMillis(50));
      try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
        Completion result =
            transport.stream(
                "model",
                ChatMessage.conversation(null, "hello"),
                Sampling.NONE,
                List.of(),
                Deltas.DISCARDING,
                () -> false,
                null,
                io.aeyer.plowshare.server.llm.dispatch.InferenceObserver.NONE,
                Duration.ofSeconds(2));
        assertEquals("stop", result.finishReason());
        assertEquals(1, server.getRequestCount());
      }
    }
  }

  @Test
  void an_explicit_prompt_budget_stops_a_stream_even_while_bytes_keep_arriving() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(sse(longStream(80)).throttleBody(128, 100, TimeUnit.MILLISECONDS));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        LlmTransportException failure =
            assertThrows(
                LlmTransportException.class,
                () ->
                    transport.stream(
                        "model",
                        ChatMessage.conversation(null, "hello"),
                        Sampling.NONE,
                        List.of(),
                        Deltas.DISCARDING,
                        () -> false,
                        null,
                        io.aeyer.plowshare.server.llm.dispatch.InferenceObserver.NONE,
                        Duration.ofMillis(350)));
        assertTrue(failure.getMessage().contains("350ms"), failure.getMessage());
        assertEquals(1, server.getRequestCount());
      }
    }
  }

  @Test
  void an_explicit_prompt_budget_bounds_a_blocking_call_without_replaying_it() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
      server.start();
      PoolProperties props = poolAt(server.url("/v1").toString());
      props.setRetryInitialBackoff(Duration.ofSeconds(1));
      try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
        assertThrows(
            LlmTransportException.class,
            () ->
                transport.complete(
                    "model",
                    ChatMessage.conversation(null, "hello"),
                    Sampling.NONE,
                    List.of(),
                    null,
                    io.aeyer.plowshare.server.llm.dispatch.InferenceObserver.NONE,
                    Duration.ofMillis(200)));
        assertEquals(1, server.getRequestCount());
      }
    }
  }

  private static PoolProperties poolAt(String baseUrl) {
    PoolProperties props = new PoolProperties();
    props.setName("studio");
    props.setBaseUrl(baseUrl);
    props.setRetryInitialBackoff(Duration.ofMillis(1));
    return props;
  }

  private static OpenAiTransport transportAt(String baseUrl) {
    return new OpenAiTransport(poolAt(baseUrl), new ObjectMapper());
  }

  private static MockResponse json(String body) {
    return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
  }

  /**
   * {@code okhttp-sse} refuses a response whose content type is not this, so the header is half of
   * what makes a stream a stream.
   */
  private static MockResponse sse(String body) {
    return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body);
  }

  /**
   * Two tokens, an empty final delta carrying the finish reason, and the sentinel — the shape LM
   * Studio actually emits.
   */
  /**
   * A model that reasons before it answers, in the two spellings that exist.
   *
   * <p>{@code reasoning_content} and {@code reasoning} are both here on purpose: the field is not
   * in the OpenAI specification and differs by model — measured on one node, {@code qwen3.5-9b}
   * sends the first and {@code gpt-oss-20b} the second. A fixture carrying only one of them would
   * let a transport that read only one of them pass.
   */
  private static final String THINKING_STREAM =
      """
            data: {"choices":[{"delta":{"reasoning_content":"Let "},"finish_reason":null}]}

            data: {"choices":[{"delta":{"reasoning":"think."},"finish_reason":null}]}

            data: {"choices":[{"delta":{"content":"Bor"},"finish_reason":null}]}

            data: {"choices":[{"delta":{"content":"deaux"},"finish_reason":null}]}

            data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

            """;

  private static final String BORDEAUX_STREAM =
      """
            data: {"choices":[{"delta":{"content":"Bor"},"finish_reason":null}]}

            data: {"choices":[{"delta":{"content":"deaux"},"finish_reason":null}]}

            data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

            """;

  /**
   * A base URL with no scheme is an <em>endpoint</em> failure, not a caller's.
   *
   * <p>{@code LLM_BASE_URL=localhost:1234/v1} — one missing {@code http://} — makes {@code
   * Request.Builder.url} throw {@code IllegalArgumentException}, which escapes {@code
   * Archive.embed}'s deliberately narrow catch and gets unclassified by {@code ApiExceptionHandler}
   * and so answered with a {@code 500} saying the server is broken — while the memory is destroyed
   * on the way, because that exception is not the type {@code Archive.embed} catches. As an {@code
   * LlmTransportException} it becomes an {@code EmbeddingException} at the capability boundary and
   * reaches the agent as a {@code 503}: a side service is down, the archive is intact, ask again
   * later. The guarantee is unchanged from {@code OpenAiEmbeddingClient}; only the property path it
   * names has moved, because a base URL now belongs to a pool.
   */
  @Test
  void a_base_url_with_no_scheme_fails_as_an_endpoint_problem() {
    try (OpenAiTransport transport = transportAt("localhost:1234/v1")) {
      LlmTransportException failed =
          assertThrows(
              LlmTransportException.class, () -> transport.embed("nomic", List.of("anything")));

      assertTrue(failed.getMessage().contains("studio"), failed.getMessage());
      assertTrue(failed.getMessage().contains("plowshare.llm.pools"), failed.getMessage());
      assertTrue(failed.getMessage().contains("base-url"), failed.getMessage());
      assertTrue(failed.getMessage().contains("localhost:1234/v1"), failed.getMessage());
      assertTrue(failed.getMessage().contains("scheme"), failed.getMessage());
    }
  }

  @Test
  void a_batch_comes_back_in_order_with_what_it_cost() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          json(
              """
                    {"data":[{"index":0,"embedding":[1.0,2.0]},
                             {"index":1,"embedding":[3.0,4.0]}],
                     "usage":{"prompt_tokens":9,"total_tokens":9}}
                    """));
      server.start();

      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Embeddings embeddings = transport.embed("nomic", List.of("one", "two"));

        assertEquals(2, embeddings.vectors().size());
        assertEquals(2.0f, embeddings.vectors().get(0)[1]);
        assertEquals(3.0f, embeddings.vectors().get(1)[0]);
        assertEquals(9, embeddings.usage().promptTokens());
        assertNull(embeddings.usage().completionTokens());

        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(sent, "the server never received a request");
        assertEquals("/v1/embeddings", sent.getPath());
        assertTrue(sent.getBody().readUtf8().contains("\"model\":\"nomic\""));
      }
    }
  }

  /**
   * The response is positional — nothing in it names the input it came from — so a short array
   * cannot be repaired, only detected. Storing it would attach every vector to the wrong text,
   * silently and permanently.
   */
  @Test
  void a_short_batch_is_a_failure_and_not_a_partial_result() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"data\":[{\"index\":0,\"embedding\":[1.0]}]}"));
      server.start();

      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class, () -> transport.embed("nomic", List.of("one", "two")));
        // The whole phrase, not contains("2") and contains("1"): those
        // two are satisfied by a port number, a status code, or very
        // nearly any message this class can produce.
        assertTrue(
            failed.getMessage().contains("asked for 2 embeddings and got 1"), failed.getMessage());
      }
    }
  }

  /** An empty {@code data} array: the endpoint answered without answering. */
  @Test
  void a_response_with_an_empty_data_array_is_not_an_embedding() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"data\":[]}"));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class, () -> transport.embed("nomic", List.of("one")));
        assertTrue(failed.getMessage().contains("no data array"), failed.getMessage());
      }
    }
  }

  /**
   * A row that is present but carries a zero-width vector.
   *
   * <p>A separate branch from the one above and, until now, an untested one: {@code
   * a_response_with_no_vectors_is_not_an_embedding} was named for this case and actually exercised
   * the empty-{@code data} case, so the guard its name described had nothing behind it. A
   * zero-width vector must not reach the archive — {@code vector(768)} in the schema would reject
   * it at the INSERT, several layers from the endpoint that produced it.
   */
  @Test
  void a_response_with_an_empty_vector_is_not_an_embedding() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"data\":[{\"index\":0,\"embedding\":[]}]}"));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class, () -> transport.embed("nomic", List.of("one")));
        assertTrue(failed.getMessage().contains("empty vector"), failed.getMessage());
      }
    }
  }

  /**
   * A status error is returned prose from a server that understood the request and said no.
   * Retrying it just spends the caller's budget twice.
   */
  @Test
  void a_refusal_is_not_retried() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse().setResponseCode(400).setBody("no such model"));
      server.enqueue(json("{\"data\":[{\"embedding\":[1.0]}]}"));
      server.start();

      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class, () -> transport.embed("nomic", List.of("one")));
        assertTrue(failed.getMessage().contains("400"), failed.getMessage());
        assertEquals(1, server.getRequestCount(), "a refusal must not be tried again");
      }
    }
  }

  /**
   * A dropped socket is the endpoint restarting, which a second attempt genuinely fixes — with the
   * connection pool evicted first, because a half-broken pooled connection would fail the retry the
   * same way.
   */
  @Test
  void a_dropped_connection_is_retried() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
      server.enqueue(json("{\"data\":[{\"embedding\":[1.0]}]}"));
      server.start();

      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Embeddings embeddings = transport.embed("nomic", List.of("one"));
        assertEquals(1, embeddings.vectors().size());
        assertEquals(2, server.getRequestCount());
      }
    }
  }

  /**
   * No key configured, no header. An unauthenticated local box must not be handed an empty
   * Authorization header to reject.
   */
  @Test
  void no_key_means_no_authorization_header() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"data\":[{\"embedding\":[1.0]}]}"));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        transport.embed("nomic", List.of("one"));
        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(sent, "the server never received a request");
        assertNull(sent.getHeader("Authorization"));
      }
    }
  }

  @Test
  void a_configured_key_is_sent_as_a_bearer_token() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"data\":[{\"embedding\":[1.0]}]}"));
      server.start();

      PoolProperties props = poolAt(server.url("/v1").toString());
      props.setApiKey("test-only-not-a-real-key");
      try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
        transport.embed("nomic", List.of("one"));
        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(sent, "the server never received a request");
        String header = sent.getHeader("Authorization");
        // Presence and shape only. Asserting the value would put a key
        // in a source file, which is the habit this codebase is trying
        // not to have — see the pool's api-key javadoc.
        assertTrue(header != null && header.startsWith("Bearer "), "expected a Bearer token");
      }
    }
  }

  /**
   * A key with a character no HTTP header may carry.
   *
   * <p>A trailing newline is the ordinary shape of {@code LM_STUDIO_API_KEY=$(cat keyfile)} and of
   * a Docker secret read from a file. OkHttp validates header values and raises {@code
   * IllegalArgumentException} — which is the exact failure {@code url(String)} exists to translate,
   * one field over in the same method, and the one call in {@code request(...)} that was left
   * untranslated.
   *
   * <p>Untranslated it escapes {@code DispatchingEmbeddingClient}'s narrow {@code LlmException}
   * catch and {@code Archive.embed}'s narrower {@code EmbeddingException} one, and reaches the
   * agent as a 500 "the server is broken" rather than a 503 "a side service is down" — telling a
   * model its own proposal was at fault for an operator's key file. Asserting the type is the whole
   * point of the test.
   *
   * <p>No server is enqueued because the request is never built, let alone sent; the failure has to
   * happen before any socket is opened.
   */
  @Test
  void a_key_that_cannot_be_a_header_fails_as_an_endpoint_problem() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.start();

      PoolProperties props = poolAt(server.url("/v1").toString());
      // A trailing newline, which is what reading a key out of a file gives.
      props.setApiKey("test-only-not-a-real-key\n");
      try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class, () -> transport.embed("nomic", List.of("one")));
        assertTrue(failed.getMessage().contains("studio"), failed.getMessage());
        assertTrue(failed.getMessage().contains("api-key"), failed.getMessage());
        // Neither the key nor its length. OkHttp's own message carries
        // the offending index, and "at 29" for "Bearer " plus the key is
        // the key's exact length, so the cause is deliberately dropped.
        assertFalse(failed.getMessage().contains("test-only-not-a-real-key"), failed.getMessage());
        assertNull(failed.getCause(), "the cause carries the key's length as an index");
        assertEquals(0, server.getRequestCount(), "the request must fail before anything is sent");
      }
    }
  }

  /**
   * The blocking path's exception text is the endpoint's too, not only the refusal body's.
   *
   * <p>{@code streamDetail} guards this and {@code executeWithRetry} did not: Task 6 tightened the
   * stream path and never back-ported it, leaving the class javadoc's claim that the refusal body
   * is "the only one the endpoint controls" false in two places. A server controls the bytes inside
   * a client library's exceptions as well — a malformed status line comes back as {@code
   * ProtocolException("Unexpected status line: ...")} carrying whatever the peer sent, an {@code
   * IOException}, so it is retried and then interpolated into both a {@code log.warn} and the
   * terminal throw.
   *
   * <p>Two responses are enqueued because {@code retry-max-attempts} is 2 and this failure is
   * retried; the message under test is the one raised after the last attempt.
   */
  @Test
  void a_malformed_status_line_quoting_the_key_is_withheld_too() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse().setStatus("GARBAGE test-only-not-a-real-key"));
      server.enqueue(new MockResponse().setStatus("GARBAGE test-only-not-a-real-key"));
      server.start();

      PoolProperties props = poolAt(server.url("/v1").toString());
      props.setApiKey("test-only-not-a-real-key");
      props.setRetryInitialBackoff(Duration.ofMillis(1));
      try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class, () -> transport.embed("nomic", List.of("one")));

        assertFalse(
            failed.getMessage().contains("test-only-not-a-real-key"),
            "a library exception carrying the endpoint's bytes quoted the key: "
                + failed.getMessage());
        assertTrue(failed.getMessage().contains("studio"), failed.getMessage());
      }
    }
  }

  /**
   * An auth failure is exactly the moment something wants to print what it sent. It says which pool
   * refused, and nothing about the credential.
   */
  @Test
  void a_401_names_the_pool_and_never_the_key() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse().setResponseCode(401).setBody("unauthorized"));
      server.start();

      PoolProperties props = poolAt(server.url("/v1").toString());
      props.setApiKey("test-only-not-a-real-key");
      try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class, () -> transport.embed("nomic", List.of("one")));
        assertTrue(failed.getMessage().contains("studio"), failed.getMessage());
        assertTrue(failed.getMessage().contains("401"), failed.getMessage());
        assertTrue(
            !failed.getMessage().contains("test-only-not-a-real-key"),
            "the key must never appear in a message");
      }
    }
  }

  /**
   * The attempt count is set to something other than the default here on purpose. Task 4 lost a
   * mutant to exactly this: every test built its subject through a convenience factory that
   * happened to hardcode the value being mutated, so an implementation ignoring the property
   * entirely was indistinguishable from one honouring it. Three is not two.
   */
  @Test
  void an_unreachable_endpoint_fails_after_the_attempts_it_was_given() throws IOException {
    MockWebServer server = new MockWebServer();
    server.start();
    String url = server.url("/v1").toString();
    server.shutdown();

    PoolProperties props = poolAt(url);
    props.setRetryMaxAttempts(3);
    try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
      LlmTransportException failed =
          assertThrows(LlmTransportException.class, () -> transport.embed("nomic", List.of("one")));
      assertTrue(failed.getMessage().contains("studio"), failed.getMessage());
      assertTrue(failed.getMessage().contains("3 attempts"), failed.getMessage());
    }
  }

  /**
   * One dropped socket must not take the endpoint away for good.
   *
   * <p>This fails together with {@code a_dropped_connection_is_retried} and not independently of it
   * — under the only mutation that kills either, the first {@code embed} below already throws and
   * the second is never reached, so no mutant kills one and spares the other. An earlier draft of
   * this comment claimed the two came apart; they do not, and a test that cannot be shown to catch
   * something its neighbour misses should not say it does.
   *
   * <p>What it adds is the account. The sibling test looks like an ordinary retry test and would be
   * "fixed" by pointing it at 127.0.0.1, which passes and leaves the defect in place. This one
   * names the mechanism and asks the question that mechanism is about — whether the call
   * <em>after</em> a blip still reaches a healthy server — so that a later reader tempted by that
   * fix has to answer it. OkHttp remembers a failed route in a {@code RouteDatabase} that outlives
   * the call, and {@code localhost} resolves to both 127.0.0.1 and ::1 while a local inference
   * server listens on only one of them. With {@code retryOnConnectionFailure(false)} the first
   * failure postpones the address that works, the next call starts on the address that does not,
   * and with route fallback disabled there is nowhere to go — every subsequent embedding fails with
   * a ConnectException against a server that is running and healthy, until the process restarts.
   * The transport's own retry loop cannot see this: it retries onto the same broken preference.
   *
   * <p>The wedge is not hypothetical and not test-only. {@code http://localhost:1234/v1} is what
   * {@code url(...)}'s own error message tells operators to configure.
   */
  @Test
  void a_transient_disconnect_does_not_wedge_the_transport() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
      server.enqueue(json("{\"data\":[{\"embedding\":[1.0]}]}"));
      server.enqueue(json("{\"data\":[{\"embedding\":[2.0]}]}"));
      server.start();

      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        transport.embed("nomic", List.of("one"));

        Embeddings later = transport.embed("nomic", List.of("two"));
        assertEquals(1, later.vectors().size(), "a later call must still reach the server");
      }
    }
  }

  /**
   * A server that omits {@code usage} costs an unknown amount, not null.
   *
   * <p>{@link Embeddings} documents {@code usage} as non-null and does not enforce it, and {@code
   * LlmDispatcher} passes it straight to the {@code TokenLedger} without a check — so this is the
   * only place the guarantee can be made, and nothing downstream will make it later. LM Studio
   * omits the object on some builds, so the null arrives from an ordinary local setup rather than
   * from a broken one, and it arrives at whichever ledger implementation is wired up whenever
   * someone finally wires one up.
   *
   * <p>Note that every other test in this file would pass against a {@code usage(...)} that
   * returned null for a missing object: they either send a {@code usage} or never look at it.
   */
  @Test
  void a_response_with_no_usage_costs_an_unknown_amount_and_never_null() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"data\":[{\"embedding\":[1.0]}]}"));
      server.start();

      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Embeddings embeddings = transport.embed("nomic", List.of("one"));

        assertEquals(TokenUsage.UNKNOWN, embeddings.usage());
        assertNull(embeddings.usage().promptTokens());
        assertNull(embeddings.usage().completionTokens());
        assertNull(embeddings.usage().totalTokens());
      }
    }
  }

  /**
   * A count the endpoint did report and a count it did not are different facts, and {@code asInt()}
   * would render both as zero.
   */
  @Test
  void a_partial_usage_keeps_the_counts_that_were_reported() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          json(
              """
                    {"data":[{"embedding":[1.0]}],"usage":{"total_tokens":7}}
                    """));
      server.start();

      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Embeddings embeddings = transport.embed("nomic", List.of("one"));

        assertEquals(7, embeddings.usage().totalTokens());
        assertNull(embeddings.usage().promptTokens());
      }
    }
  }

  /**
   * An endpoint answering with an HTML error page is an endpoint problem, and the pool it belongs
   * to is the whole of what the operator needs.
   *
   * <p>A different pool name than every other test here, deliberately: with one name shared by a
   * whole file, an implementation that hardcoded it would be invisible.
   */
  @Test
  void a_response_that_is_not_json_names_the_pool_that_sent_it() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "text/html")
              .setBody("<html><body>502 Bad Gateway</body></html>"));
      server.start();

      PoolProperties props = poolAt(server.url("/v1").toString());
      props.setName("second-box");
      try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class, () -> transport.embed("nomic", List.of("one")));
        assertTrue(failed.getMessage().contains("second-box"), failed.getMessage());
      }
    }
  }

  /**
   * The endpoint is not allowed to leak the key back through us.
   *
   * <p>{@code a_401_names_the_pool_and_never_the_key} passes whether or not anything guards this,
   * because MockWebServer is told to say "unauthorized" and a server that says only that cannot
   * leak anything. Real gateways in front of an OpenAI-compatible endpoint quote the token they
   * rejected, and a 4xx body goes verbatim into an exception message, a log line, and eventually a
   * 503 body. "Never echoed back in any form" has to cover the form the endpoint chose, so the body
   * of a 401 or 403 is dropped and only the pool and the status survive — which is all the fix
   * needs anyway.
   */
  @Test
  void a_401_body_that_echoes_the_key_is_not_repeated() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .setResponseCode(401)
              .setBody("invalid bearer token: test-only-not-a-real-key"));
      server.start();

      PoolProperties props = poolAt(server.url("/v1").toString());
      props.setApiKey("test-only-not-a-real-key");
      try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class, () -> transport.embed("nomic", List.of("one")));

        assertTrue(
            !failed.getMessage().contains("test-only-not-a-real-key"),
            "the key must never appear in a message, not even quoted back to us");
        assertTrue(failed.getMessage().contains("studio"), failed.getMessage());
        assertTrue(failed.getMessage().contains("401"), failed.getMessage());
      }
    }
  }

  /**
   * A wedged endpoint gives the call back, on the embedding lane's own budget.
   *
   * <p>{@code LlmPool} waits on the result with no deadline of its own, so a transport that can
   * block forever turns one unresponsive host into a permanently blocked Tomcat worker, and then
   * into an exhausted container thread pool — the outage this whole slice exists to prevent.
   *
   * <p>It also pins <em>which</em> of the three clients an embedding uses. {@code chatTimeout} is
   * set two orders of magnitude longer than {@code embeddingTimeout} here so that a transport
   * reaching for the wrong one fails this assertion rather than quietly working: with every timeout
   * left at its generous default, all three clients are interchangeable and no test can tell them
   * apart.
   */
  @Test
  void an_embedding_gives_up_on_the_embedding_timeout_and_not_the_chat_one() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      for (int i = 0; i < 3; i++) {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
      }
      server.start();

      PoolProperties props = poolAt(server.url("/v1").toString());
      props.setEmbeddingTimeout(Duration.ofMillis(250));
      props.setChatTimeout(Duration.ofSeconds(30));
      props.setStreamingTimeout(Duration.ofSeconds(30));

      try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
        long start = System.nanoTime();
        assertThrows(LlmTransportException.class, () -> transport.embed("nomic", List.of("one")));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        // Two attempts of 250ms with 1ms of backoff, against 30s of
        // headroom before the chat client would have given up — and 50s
        // before its call ceiling would. Fifteen seconds is far more
        // than this needs (it runs in about half a second) and still far
        // less than the mutant costs. An upper bound is a claim about
        // how fast this machine is, so it should be set where being
        // wrong about the machine cannot make it fail; the backoff
        // test's javadoc says the same thing about the other direction,
        // and an earlier 5s bound here did not live up to it.
        assertTrue(
            took.compareTo(Duration.ofSeconds(15)) < 0,
            "gave up after "
                + took.toMillis()
                + "ms, so it was not the embedding"
                + " timeout that bounded the call");
      }
    }
  }

  /**
   * {@link io.aeyer.plowshare.server.llm.dispatch.LlmPool#close} calls this once with no guard of
   * its own and is safe to call twice only because this is, and {@code AutoCloseable} asks for the
   * same. A second close must neither throw nor do damage.
   */
  @Test
  void closing_twice_is_harmless() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"data\":[{\"embedding\":[1.0]}]}"));
      server.start();

      OpenAiTransport transport = transportAt(server.url("/v1").toString());
      try {
        // In a try, because an embed that threw would otherwise leave
        // this transport unclosed — a test about closing that leaks on
        // failure.
        transport.embed("nomic", List.of("one"));
      } finally {
        assertDoesNotThrow(
            () -> {
              transport.close();
              transport.close();
            });
      }
    }
  }

  /**
   * The pool name is what an exception and a log line have to carry, so it is read from the
   * properties rather than assumed.
   */
  @Test
  void the_transport_reports_the_pool_it_belongs_to() {
    PoolProperties props = poolAt("http://127.0.0.1:1/v1");
    props.setName("second-box");
    try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
      assertEquals("second-box", transport.poolName());
    }
  }

  /**
   * The texts have to be on the wire, in the order they were given.
   *
   * <p>Nothing else in this file can see this. The size check in {@code embed} compares the
   * response against the <em>local</em> list, so it is blind to a request that carried the wrong
   * texts — swap the inputs for two others and a real server answers with two perfectly well-formed
   * vectors of the right width, the right count and the wrong meaning. That is the short-batch
   * corruption entered from the request side, and it is the worse direction: a short batch throws,
   * while a wrong batch is stored, is never diagnosed, and makes every later recall quietly wrong.
   *
   * <p>Asserted as the whole JSON array rather than as two {@code contains} calls, because order is
   * half of what positional means.
   */
  @Test
  void the_request_carries_the_texts_it_was_asked_to_embed() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          json(
              """
                    {"data":[{"index":0,"embedding":[1.0]},{"index":1,"embedding":[2.0]}]}
                    """));
      server.start();

      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        transport.embed("nomic", List.of("alpha", "beta"));

        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(sent, "the server never received a request");
        String body = sent.getBody().readUtf8();
        assertTrue(body.contains("\"input\":[\"alpha\",\"beta\"]"), body);
      }
    }
  }

  /**
   * The backoff doubles, which is the difference between a retry and a second request at the same
   * instant.
   *
   * <p>A server that is restarting is the case the retry exists for, and it is unavailable for as
   * long as it takes to come up rather than for a fixed 200ms — so a flat backoff spends both
   * attempts inside the same outage and reports failure having learned nothing. Three attempts at
   * 200ms doubling is 600ms of waiting; flat it is 400ms.
   *
   * <p>A lower bound and deliberately only a lower bound. An upper bound would be a promise about
   * how fast this machine is; a lower bound can only ever fail to notice the mutant on a slow
   * machine, never invent a failure on one. The endpoint here is a port nothing is listening on, so
   * the attempts themselves are refused immediately and the elapsed time is very nearly the
   * sleeping.
   *
   * <p>The margin is the number to watch, and it was too thin at first: 200ms of initial backoff
   * put the real implementation at 614ms against a 500ms floor with the mutant at ~414ms, so 86ms
   * of scheduling noise on a loaded machine would have let the mutant through. Doubled to 400ms,
   * which puts 1200ms against a 1000ms floor with the mutant at ~800ms — 200ms either side — for
   * 600ms more wall clock in the suite.
   */
  @Test
  void the_backoff_between_attempts_doubles() throws IOException {
    MockWebServer server = new MockWebServer();
    server.start();
    String url = server.url("/v1").toString();
    server.shutdown();

    PoolProperties props = poolAt(url);
    props.setRetryMaxAttempts(3);
    props.setRetryInitialBackoff(Duration.ofMillis(400));

    try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
      long start = System.nanoTime();
      assertThrows(LlmTransportException.class, () -> transport.embed("nomic", List.of("one")));
      Duration took = Duration.ofNanos(System.nanoTime() - start);

      assertTrue(
          took.compareTo(Duration.ofMillis(1000)) >= 0,
          "gave up after "
              + took.toMillis()
              + "ms, which is nearer two flat backoffs"
              + " (800ms) than a doubling one (1200ms)");
    }
  }

  /**
   * An interrupted caller leaves, and leaves the flag set behind it.
   *
   * <p>Re-setting the flag is the load-bearing half. {@code LlmPool.submit} has its own {@code
   * InterruptedException} branch — it sheds the request and re-interrupts — and that branch is
   * unreachable if a transport several frames down swallows the interrupt while sleeping between
   * attempts. The caller would then sit through the remaining attempts of a call it has already
   * abandoned, on a lane slot nobody is waiting for.
   *
   * <p>The interrupt is set before the call rather than raced against it from another thread:
   * {@code Thread.sleep} throws immediately when the flag is already set, so the branch is reached
   * deterministically and there is no timing in the test at all.
   */
  @Test
  void an_interrupt_during_backoff_stops_the_call_and_stays_interrupted() throws IOException {
    MockWebServer server = new MockWebServer();
    server.start();
    String url = server.url("/v1").toString();
    server.shutdown();

    try (OpenAiTransport transport = transportAt(url)) {
      Thread.currentThread().interrupt();
      try {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class, () -> transport.embed("nomic", List.of("one")));

        assertTrue(failed.getMessage().contains("interrupted"), failed.getMessage());
        assertTrue(failed.getMessage().contains("studio"), failed.getMessage());
        assertTrue(
            Thread.currentThread().isInterrupted(),
            "the interrupt must survive the transport for LlmPool to act on");
      } finally {
        // Cleared however this test ends: a leaked interrupt flag on a
        // shared JUnit worker thread fails whichever test runs next.
        Thread.interrupted();
      }
    }
  }

  /**
   * Each credential status drops its body whole, and this is what shows that the status list does
   * work redaction cannot do.
   *
   * <p>The echo here is a <em>prefix</em> of the key rather than the key. That is the whole point
   * of the test. A first attempt at this used the full key and proved nothing: narrowing the list
   * back to 401 alone left it green, because a 403 then fell through to redaction-by-value, which
   * matched the literal key and removed it anyway. The two mechanisms overlap completely on a
   * full-key echo, so only a form redaction cannot recognise can tell them apart — and a truncated
   * key is the form gateways actually print.
   *
   * <p>Both statuses, because each is a separate branch and each was separately absent. 407 is
   * deliberately not here: OkHttp turns a 407 on an unproxied connection into a ProtocolException
   * before the transport sees the status at all, so a test for it would hang on the retry rather
   * than assert anything. See {@code detail}'s javadoc.
   */
  @Test
  void a_credential_status_withholds_a_body_that_quotes_even_part_of_the_key() throws Exception {
    for (int code : new int[] {401, 403}) {
      try (MockWebServer server = new MockWebServer()) {
        server.enqueue(
            new MockResponse()
                .setResponseCode(code)
                .setBody("rejected token test-only-not-a... (truncated)"));
        server.start();

        PoolProperties props = poolAt(server.url("/v1").toString());
        props.setApiKey("test-only-not-a-real-key");
        try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
          LlmTransportException failed =
              assertThrows(
                  LlmTransportException.class, () -> transport.embed("nomic", List.of("one")));

          assertTrue(
              !failed.getMessage().contains("test-only-not-a"),
              "HTTP " + code + " leaked part of the key: " + failed.getMessage());
          assertTrue(failed.getMessage().contains(String.valueOf(code)), failed.getMessage());
        }
      }
    }
  }

  /**
   * A status list alone is the wrong shape, and this is the case that shows it.
   *
   * <p>Nothing obliges a gateway to answer a bad key with 401. Several answer 400, and the first
   * draft of {@code detail} interpolated a 400 body verbatim — so the key came through under the
   * one status the guard was not watching.
   *
   * <p>The body is dropped whole rather than filtered. An intermediate version did redact the
   * matched value and pass the rest along, which reads as the better trade until you ask what
   * happens to the renderings the match missed: it would have printed the surrounding sentence as
   * though it had been cleaned. Withholding says what actually happened.
   */
  @Test
  void a_400_that_quotes_the_key_is_withheld_whatever_the_status() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .setResponseCode(400)
              .setBody("no such model for key test-only-not-a-real-key"));
      server.start();

      PoolProperties props = poolAt(server.url("/v1").toString());
      props.setApiKey("test-only-not-a-real-key");
      try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class, () -> transport.embed("nomic", List.of("one")));

        assertTrue(
            !failed.getMessage().contains("test-only-not-a-real-key"),
            "the key must never appear in a message, whatever the status");
        assertTrue(failed.getMessage().contains("400"), failed.getMessage());
      }
    }
  }

  /**
   * {@code String.replace} matches bytes; a key in someone else's error prose does not have to be
   * byte-identical.
   *
   * <p>This is the finding that turned redaction into withholding. Each case below is a
   * <b>whole</b> key that the byte-literal version passed straight through into a 503 response body
   * — not a fragment, which is what an earlier residual paragraph claimed was the worst of it.
   *
   * <p>The key here carries {@code +} and {@code /} so the percent-encoded rendering actually
   * differs from the raw one; with a plain alphanumeric key that case is vacuous and would have
   * tested nothing. The JSON case uses a backslash for the same reason — JSON escaping is a no-op
   * for keys drawn from the usual character set, so a realistic-looking key would have made that
   * branch untestable, and an untestable branch is the mistake 407 was.
   */
  @Test
  void a_refusal_quoting_the_key_in_another_rendering_is_still_withheld() throws Exception {
    record Case(String key, String body, String what) {}
    List<Case> cases =
        List.of(
            new Case(
                "test-only-not-a-real-key", "rejected TEST-ONLY-NOT-A-REAL-KEY", "upper-cased"),
            new Case(
                "sk+test/only=not=real",
                "bad url ?api_key=sk%2Btest%2Fonly%3Dnot%3Dreal",
                "percent-encoded"),
            new Case(
                "sk-test\\only-not-real",
                "{\"error\":\"bad key sk-test\\\\only-not-real\"}",
                "JSON-escaped"));

    for (Case one : cases) {
      try (MockWebServer server = new MockWebServer()) {
        server.enqueue(new MockResponse().setResponseCode(400).setBody(one.body()));
        server.start();

        PoolProperties props = poolAt(server.url("/v1").toString());
        props.setApiKey(one.key());
        try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
          LlmTransportException failed =
              assertThrows(
                  LlmTransportException.class, () -> transport.embed("nomic", List.of("one")));

          assertTrue(
              !failed.getMessage().contains(one.body()),
              one.what() + " reached the message: " + failed.getMessage());
        }
      }
    }
  }

  /**
   * A keyed pool whose endpoint says something unrelated keeps every word of it.
   *
   * <p>Withholding is only defensible while it is conditional. If it fired on every refusal from
   * every keyed pool, "no such model" — the one thing that tells an operator a pool's {@code
   * models} list disagrees with what is actually loaded — would be gone for exactly the remote
   * pools that are hardest to inspect by hand.
   */
  @Test
  void a_refusal_that_does_not_quote_the_key_keeps_its_prose() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse().setResponseCode(400).setBody("no such model"));
      server.start();

      PoolProperties props = poolAt(server.url("/v1").toString());
      props.setApiKey("test-only-not-a-real-key");
      try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class, () -> transport.embed("nomic", List.of("one")));

        assertTrue(failed.getMessage().contains("no such model"), failed.getMessage());
      }
    }
  }

  /**
   * The common case must not be collateral damage of the guard.
   *
   * <p>The reference box has no key, and {@code String.replace("", r)} inserts {@code r} between
   * every character — so a redaction that skipped its {@code hasApiKey} guard would shred every
   * refusal body from every unauthenticated pool into unreadable confetti, which is the opposite of
   * what the guard is for and would be noticed only by whoever was reading a 400 at the time.
   */
  @Test
  void a_refusal_from_a_pool_with_no_key_keeps_its_body() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse().setResponseCode(400).setBody("no such model"));
      server.start();

      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class, () -> transport.embed("nomic", List.of("one")));

        assertTrue(failed.getMessage().contains("no such model"), failed.getMessage());
      }
    }
  }

  /**
   * A key written into the base URL is still a key.
   *
   * <p>{@code https://user:secret@host/v1} is a legal thing for an operator to configure, and it
   * puts a credential in the one field this class prints on purpose. The api-key guards never look
   * at it. Only an unparseable URL is ever printed, so the path is narrow — the port is what makes
   * this one unparseable — but the message goes to a log line and into a 503 body.
   */
  @Test
  void a_key_written_into_the_base_url_is_not_printed_back() {
    try (OpenAiTransport transport =
        transportAt("https://user:test-only-not-a-real-key@host:notaport/v1")) {
      LlmTransportException failed =
          assertThrows(LlmTransportException.class, () -> transport.embed("nomic", List.of("one")));

      assertTrue(
          !failed.getMessage().contains("test-only-not-a-real-key"),
          "a credential in base-url must not be echoed: " + failed.getMessage());
      assertTrue(failed.getMessage().contains("host"), failed.getMessage());
    }
  }

  /**
   * A refusal whose body cannot be read is still a refusal.
   *
   * <p>The retry loop used to call {@code string()} before looking at the status, so a 400 that
   * promised more body than it delivered raised an IOException indistinguishable from a dropped
   * socket — and the refusal was retried, directly against the comment above it saying refusals
   * never are. Harmless for an idempotent POST and wrong in the documentation, which is the worse
   * of the two.
   */
  @Test
  void a_refusal_whose_body_dies_mid_read_is_still_not_retried() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .setResponseCode(400)
              .setHeader("Content-Length", "1000")
              .setBody("no such model")
              .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
      server.enqueue(json("{\"data\":[{\"embedding\":[1.0]}]}"));
      server.start();

      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class, () -> transport.embed("nomic", List.of("one")));

        assertTrue(failed.getMessage().contains("400"), failed.getMessage());
        assertEquals(
            1,
            server.getRequestCount(),
            "a refusal must not be tried again, whatever its body did");
      }
    }
  }

  // ---- Chat, blocking ----

  @Test
  void a_blocking_chat_returns_content_finish_reason_and_usage() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          json(
              """
                    {"choices":[{"message":{"role":"assistant","content":"Bordeaux"},
                                 "finish_reason":"stop"}],
                     "usage":{"prompt_tokens":11,"completion_tokens":2,"total_tokens":13}}
                    """));
      server.start();

      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Completion completion =
            transport.complete(
                "qwen3.5-9b",
                ChatMessage.conversation("be terse", "capital?"),
                Sampling.NONE,
                List.of());

        assertEquals("Bordeaux", completion.content());
        assertEquals("stop", completion.finishReason());
        assertEquals(13, completion.usage().totalTokens());

        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(sent, "the server never received a request");
        assertEquals("/v1/chat/completions", sent.getPath());
        String body = sent.getBody().readUtf8();
        assertTrue(body.contains("\"model\":\"qwen3.5-9b\""), body);
        assertTrue(body.contains("be terse"), body);
        assertTrue(!body.contains("\"stream\":true"), body);
      }
    }
  }

  /**
   * A system prompt nobody supplied must not become an empty system message: a blank system turn is
   * not the same input as no system turn, and small models notice.
   */
  @Test
  void a_blank_system_prompt_sends_one_message() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        transport.complete(
            "qwen3.5-9b", ChatMessage.conversation("   ", "hello"), Sampling.NONE, List.of());
        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(sent, "the server never received a request");
        String body = sent.getBody().readUtf8();
        assertTrue(!body.contains("\"role\":\"system\""), body);
        assertTrue(body.contains("\"role\":\"user\""), body);
      }
    }
  }

  @Test
  void a_chat_response_with_no_choices_is_not_an_answer() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"choices\":[]}"));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class,
                () ->
                    transport.complete(
                        "qwen3.5-9b",
                        ChatMessage.conversation(null, "hello"),
                        Sampling.NONE,
                        List.of()));
        assertTrue(failed.getMessage().contains("no choices"), failed.getMessage());
        assertTrue(failed.getMessage().contains("studio"), failed.getMessage());
      }
    }
  }

  /**
   * An explicit JSON null is not a finish reason called "null".
   *
   * <p><b>This passes against {@code asText(null)} too, and saying so is the point.</b> The plan
   * asserts that {@code asText(null)} yields the string "null" for an explicit JSON null; it does
   * not, because {@code NullNode} overrides the one-argument overload to return the default —
   * checked against Jackson 2.17.2. The string "null" comes from the <em>no-argument</em> {@code
   * asText()}, one overload along. So this test pins the behaviour that matters to {@link
   * Completion#finishReason()} without distinguishing the two readings; {@code
   * a_finish_reason_that_is_not_a_string_is_not_a_reason} is the one that does.
   */
  @Test
  void a_chat_that_reports_a_null_finish_reason_reports_none() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          json("{\"choices\":[{\"message\":{\"content\":\"hi\"},\"finish_reason\":null}]}"));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Completion completion =
            transport.complete(
                "qwen3.5-9b", ChatMessage.conversation(null, "hello"), Sampling.NONE, List.of());
        assertNull(
            completion.finishReason(), "an explicit JSON null must not become the string \"null\"");
        assertEquals(TokenUsage.UNKNOWN, completion.usage());
      }
    }
  }

  /**
   * A scalar that is not a string is not a finish reason either, and this is the only test in the
   * file that can tell {@code isTextual()} apart from {@code asText(null)}.
   *
   * <p>The two agree on a JSON null and on a missing field — see {@code
   * a_chat_that_reports_a_null_finish_reason_reports_none} for why the plan's account of that is
   * wrong — and disagree only here, where {@code asText(null)} would hand a caller "7". {@link
   * Completion#finishReason()} admits {@code stop}, {@code length} and null; a caller checking for
   * {@code length} before trusting truncated content gets no help from a number, and an endpoint
   * that answers this way is one whose finish reason should be treated as absent rather than
   * invented.
   */
  @Test
  void a_finish_reason_that_is_not_a_string_is_not_a_reason() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          json("{\"choices\":[{\"message\":{\"content\":\"hi\"},\"finish_reason\":7}]}"));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Completion completion =
            transport.complete(
                "qwen3.5-9b", ChatMessage.conversation(null, "hello"), Sampling.NONE, List.of());
        assertNull(completion.finishReason(), "\"7\" is not a reason a caller can act on");
      }
    }
  }

  // ---- Chat, tools ----

  /**
   * A call that states no sampling and offers no tools sends a body with neither: no {@code tools}
   * key, and <b>no {@code temperature} key</b>.
   *
   * <h2>This assertion moved, and the move is the point of the change</h2>
   *
   * <p>It used to end {@code "temperature":0.0}, pinning what this transport sent on the commit
   * before tools existed. That number is gone from this body and its absence is now what is pinned.
   *
   * <p><b>Nobody ever chose it.</b> {@code ChatRequest.of} hardcoded {@code 0.0} because until an
   * agent could name a temperature there was no other option, and eleven agents inherited a
   * constant that was never a decision about any of them. It is also the exact configuration {@code
   * implementation rationale} measured driving this server's summariser cascade into deterministic
   * repetition loops — 3 999 completion tokens, 3 997 of them reasoning, empty content — on a
   * family whose vendor forbids greedy decoding in as many words.
   *
   * <p><b>The endpoint has a better answer than any constant here.</b> LM Studio resolves a setting
   * model defaults → {@code model.yaml} → load-time → inference-time, later winning, and a model's
   * own {@code model.yaml} carries its vendor's recommended values. So a body that omits the key
   * runs the model correctly, and the body that carried a zero was overriding a right answer with a
   * wrong one.
   *
   * <p>The rest of the guard is unchanged and still load-bearing: an empty {@code tools} array is
   * not the same as no {@code tools} key, some OpenAI-compatible servers reject the former, and
   * whole-body equality is what catches a change of any other kind.
   */
  @Test
  void a_request_that_states_nothing_sends_neither_tools_nor_a_temperature() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        transport.complete(
            "qwen3.5-9b", ChatMessage.conversation(null, "hello"), Sampling.NONE, List.of());
        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(sent, "the server never received a request");
        assertEquals(
            "{\"model\":\"qwen3.5-9b\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
            sent.getBody().readUtf8(),
            "a request that states no sampling must carry no sampling key at all;"
                + " the absence is what lets the model's own defaults stand");
      }
    }
  }

  /**
   * A system prompt is still the first message, and a two-message conversation is still exactly two
   * messages. The other half of the byte-identical guard: {@code ChatMessage.conversation} is what
   * carries slice 2's "a system message only when the system prompt is non-blank" rule now, and a
   * whole-body assertion is what proves it carried it.
   */
  @Test
  void a_system_prompt_is_still_the_first_message() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        transport.complete(
            "qwen3.5-9b", ChatMessage.conversation("be terse", "hello"), Sampling.NONE, List.of());
        assertEquals(
            "{\"model\":\"qwen3.5-9b\","
                + "\"messages\":[{\"role\":\"system\",\"content\":\"be terse\"},"
                + "{\"role\":\"user\",\"content\":\"hello\"}]}",
            server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8());
      }
    }
  }

  /**
   * A message with no picture in it is a scalar {@code content}, and this test exists to be the
   * thing that fails when somebody makes it always an array.
   *
   * <h2>Why the mutation is tempting and why it is wrong</h2>
   *
   * <p>The array is the general form: it can carry text and it can carry an image, so one branch is
   * simpler than two. It is still wrong twice over. Some OpenAI-compatible servers refuse an array
   * for a plain text turn, and this project targets the narrowest shape every backend it talks to
   * accepts. And it would rewrite the request body of every one of the three and a half thousand
   * tests in this suite — including {@link #a_system_prompt_is_still_the_first_message} and the
   * sampling body above — for no behavioural gain at all.
   *
   * <p>Whole-body equality and not {@code contains}, for the reason the sampling test below gives:
   * the failure is a near-miss, and {@code contains("hello")} passes for {@code
   * [{"type":"text","text":"hello"}]}.
   */
  @Test
  void a_text_only_message_is_a_scalar_content_and_never_an_array() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        transport.complete(
            "qwen3.5-9b", List.of(ChatMessage.user("hello")), Sampling.NONE, List.of());

        assertEquals(
            "{\"model\":\"qwen3.5-9b\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",
            server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8());
      }
    }
  }

  /**
   * A message with a picture is the contract's other half: an array of typed parts, the words
   * first, the image as a {@code data:} URI.
   *
   * <p><b>Measured 2026-09-07</b> against {@code mlx-community/gemma-4-e4b-it} on LM Studio — this
   * shape, with a base64 data URI, answered "A red square is in the image." about a hand-built
   * 64×64 PNG in 3.0 s. The fixture below is not a real PNG, because nothing in this transport
   * decodes one; what is being pinned is the JSON.
   */
  @Test
  void a_message_with_an_image_is_an_array_carrying_a_data_uri() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"choices\":[{\"message\":{\"content\":\"a red square\"}}]}"));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        transport.complete(
            "gemma-4-e4b",
            List.of(
                ChatMessage.user(
                    "what is this",
                    List.of(
                        new Content.Image(
                            "img_" + "a".repeat(32), "data:image/png;base64,iVBORw0K")))),
            Sampling.NONE,
            List.of());

        assertEquals(
            "{\"model\":\"gemma-4-e4b\",\"messages\":[{\"role\":\"user\","
                + "\"content\":["
                + "{\"type\":\"text\",\"text\":\"what is this\"},"
                + "{\"type\":\"image_url\",\"image_url\":"
                + "{\"url\":\"data:image/png;base64,iVBORw0K\"}}]}]}",
            server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8());
      }
    }
  }

  /**
   * The transport never fetches an image, and there is no code in it that could.
   *
   * <h2>What is actually asserted, and why it is not a mock of the network</h2>
   *
   * <p>A server-side fetch of a caller-supplied URL is an SSRF surface: this process connecting to
   * an address somebody outside chose, from inside the network the inference nodes are on. The
   * control is not a rule a transport has to remember — it is that {@link Content.Image} cannot
   * hold anything but a {@code data:} URI, so there is no value a message could carry that a fetch
   * could be made from.
   *
   * <p>So this asserts both halves. The type refuses an {@code http} URL, and a call carrying an
   * image opens exactly one connection: the one to the endpoint. A transport that had grown a fetch
   * would have made two.
   */
  @Test
  void a_message_with_an_image_never_makes_the_transport_open_a_connection() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> new Content.Image("img_" + "a".repeat(32), "https://example.invalid/red.png"),
        "an image part that could hold a URL is an image part a transport could"
            + " be asked to resolve");

    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        transport.complete(
            "gemma-4-e4b",
            List.of(
                ChatMessage.user(
                    "what is this",
                    List.of(
                        new Content.Image(
                            "img_" + "b".repeat(32), "data:image/png;base64,iVBORw0K")))),
            Sampling.NONE,
            List.of());

        assertEquals(
            1,
            server.getRequestCount(),
            "one call to the endpoint and nothing else: the bytes were already"
                + " in the message, put there by the server out of its own"
                + " store");
      }
    }
  }

  /**
   * Every parameter this transport declares it can carry reaches the body, under the name the
   * endpoint knows it by.
   *
   * <h2>Whole-body equality, because the failure is a near-miss</h2>
   *
   * <p>A profile is written once and read by nobody afterwards, so the way this breaks is not "no
   * parameters were sent" — it is one of four sent under the wrong key, which an OpenAI-compatible
   * server ignores in silence. {@code contains("top_p")} passes for a body that spelled {@code
   * top_k} as {@code topK}; a whole body does not.
   *
   * <p><b>{@code top_k} is a vendor extension and that is why it is asserted here rather than
   * assumed.</b> OpenAI's own API has no such field; LM Studio documents it in the accepted set for
   * this endpoint. Anything that makes this project point at a different {@code /v1} provider has
   * to revisit {@code carries()}, and this test is where the claim is written down as bytes.
   */
  @Test
  void every_parameter_this_transport_carries_reaches_the_body_by_its_wire_name() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        transport.complete(
            "qwen3.5-9b",
            ChatMessage.conversation(null, "hello"),
            Sampling.NONE.withTemperature(0.6d).withTopP(0.95d).withTopK(20).withMaxTokens(30000),
            List.of());
        assertEquals(
            "{\"model\":\"qwen3.5-9b\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],"
                + "\"temperature\":0.6,\"top_p\":0.95,\"top_k\":20,"
                + "\"max_tokens\":30000}",
            server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8());
      }
    }
  }

  /**
   * Each parameter is written only when it was stated, one at a time.
   *
   * <p>Separate from the whole-body test above because that one cannot tell a body that writes
   * every key from a body that writes the keys it was given: a request stating all four is
   * satisfied by an implementation that defaults the missing ones. Here three of four are absent,
   * and a body that invented any of them fails.
   */
  @Test
  void a_parameter_nobody_stated_is_not_written_at_all() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        transport.complete(
            "qwen3.5-9b",
            ChatMessage.conversation(null, "hello"),
            Sampling.NONE.withTopK(64),
            List.of());
        assertEquals(
            "{\"model\":\"qwen3.5-9b\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],"
                + "\"top_k\":64}",
            server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8());
      }
    }
  }

  /**
   * {@code reasoning_effort} is both declared and written, and the two have to agree.
   *
   * <h2>The gap this used to pin, and the measurement that closed it</h2>
   *
   * <p>This test asserted the opposite until 2026-09-07: that the field was neither in {@code
   * carries()} nor on the wire. That was honest at the time — nothing had verified the endpoint
   * accepted it, and an unverified key is indistinguishable from no key, because a server ignores
   * what it does not know. Its javadoc said turning it on was "one enum constant and one line, the
   * day somebody confirms the spelling against a live endpoint".
   *
   * <p><b>Confirmed against llama.cpp b10835 serving GLM-4.7-Flash.</b> One prompt, {@code
   * max_tokens} 1024: {@code none} returned in 1.3s with zero reasoning characters and a complete
   * answer; {@code low}, {@code medium}, {@code high} and omitting the field all ran ~17s, emitted
   * ~5 200 characters of reasoning, and truncated at {@code length} with the answer unwritten. A
   * field the endpoint ignored could not produce that split.
   *
   * <p><b>The contract both halves still hold is unchanged.</b> A transport writing the field
   * without declaring it would have its own output dropped by {@link
   * io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher}, which filters on {@code carries()}; one
   * declaring it without writing it would claim to send something it never sends. This test fails
   * on either.
   */
  @Test
  void reasoning_effort_is_declared_and_written_and_the_two_agree() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        assertEquals(
            java.util.EnumSet.of(
                Sampling.Parameter.TEMPERATURE,
                Sampling.Parameter.TOP_P,
                Sampling.Parameter.TOP_K,
                Sampling.Parameter.MAX_TOKENS,
                Sampling.Parameter.REASONING_EFFORT,
                Sampling.Parameter.RESPONSE_FORMAT),
            transport.carries(),
            "a parameter this transport writes must be one it declares, or the"
                + " dispatcher filters away its own output");
        transport.complete(
            "glm-4.7-flash",
            ChatMessage.conversation(null, "hello"),
            Sampling.NONE.withReasoningEffort(Sampling.Effort.NONE),
            List.of());
        assertEquals(
            "{\"model\":\"glm-4.7-flash\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],"
                + "\"reasoning_effort\":\"none\"}",
            server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8(),
            "NONE is the value the measurement was about; it must reach the wire"
                + " spelled 'none', because that is the only spelling GLM"
                + " distinguishes from full reasoning");
      }
    }
  }

  /**
   * {@code response_format} reaches the wire <b>and</b> is declared, and this test fails if either
   * half is missing.
   *
   * <h2>Why one test and not two</h2>
   *
   * <p>The two halves are one decision and they fail in opposite, equally quiet directions. Written
   * and not declared: {@code LlmDispatcher.carried} filters the request against {@code carries()}
   * before the transport is reached, so the transport's own field is stripped from its own body —
   * the agent file names a contract, the request carries none, and a WARN about a dropped parameter
   * is the only trace. Declared and not written: the dispatcher passes the schema through and
   * nothing puts it in the body, so the model answers in prose and every caller parses JSON out of
   * it.
   *
   * <p>Two separate tests would each be green in one of those states. This is the shape {@code
   * reasoning_effort_is_declared_and_written_and_the_two_agree} established and it is copied
   * deliberately.
   *
   * <h2>Whole-body equality</h2>
   *
   * <p>The failure is a near-miss: {@code contains("json_schema")} passes for a body that put the
   * schema under {@code response_schema}, or that nested {@code name} one level too deep. Measured
   * 2026-09-07 on both nodes, the shape below is what they enforced.
   */
  @Test
  void response_format_is_declared_and_written_and_the_two_agree() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}"));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        assertTrue(
            transport.carries().contains(Sampling.Parameter.RESPONSE_FORMAT),
            "a parameter this transport writes must be one it declares, or the"
                + " dispatcher filters away its own output");

        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("shape", Map.of("type", "string")));
        transport.complete(
            "gemma-4-e4b",
            ChatMessage.conversation(null, "what shape"),
            Sampling.NONE.withResponseFormat(JsonSchema.from("figure_reading", schema)),
            List.of());

        assertEquals(
            "{\"model\":\"gemma-4-e4b\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"what shape\"}],"
                + "\"response_format\":{\"type\":\"json_schema\","
                + "\"json_schema\":{\"name\":\"figure_reading\","
                + "\"schema\":{\"type\":\"object\","
                + "\"properties\":{\"shape\":{\"type\":\"string\"}}}}}}",
            server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8());
      }
    }
  }

  /**
   * A tool result reaches the wire against the id of the call it answers.
   *
   * <p><b>This is the correlation the whole message-array change exists for.</b> Task 4 first
   * rendered the history as prose inside the user message, which could write an id into a sentence
   * but left the association as something the model had to infer from text rather than read from a
   * field. It is asserted as a whole body rather than by {@code contains}, because the failure this
   * guards against is a body that is <em>nearly</em> right — a tool message with the wrong id, or
   * in the wrong place, both of which pass every substring check.
   */
  @Test
  void a_tool_result_is_sent_against_the_id_of_the_call_it_answers() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"choices\":[{\"message\":{\"content\":\"done\"}}]}"));
      server.start();
      List<ChatMessage> conversation =
          List.of(
              ChatMessage.user("which programme?"),
              ChatMessage.assistant(
                  "let me look",
                  List.of(new ToolCall("c1", "memory_recall", "{\"question\":\"Gnomon\"}"))),
              ChatMessage.tool("c1", "Gnomon is one of Teller's programmes"));
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        transport.complete("qwen3.5-9b", conversation, Sampling.NONE, List.of());
        assertEquals(
            "{\"model\":\"qwen3.5-9b\",\"messages\":["
                + "{\"role\":\"user\",\"content\":\"which programme?\"},"
                + "{\"role\":\"assistant\",\"content\":\"let me look\","
                + "\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"memory_recall\","
                + "\"arguments\":\"{\\\"question\\\":\\\"Gnomon\\\"}\"}}]},"
                + "{\"role\":\"tool\",\"content\":"
                + "\"Gnomon is one of Teller\u0027s programmes\","
                + "\"tool_call_id\":\"c1\"}]}",
            server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8());
      }
    }
  }

  /**
   * A batch of two produces two tool messages, in order, each against its own id.
   *
   * <p>Measured 2026-08-29: qwen3.5-9b does not batch — 0/4 when asked for two independent lookups
   * — so this is <b>fixture-only</b> and unreachable against the model this project runs. That is
   * exactly why it is tested here, at the one layer that writes the wire: a runtime that paired
   * results with ids by position rather than by id would be correct for every run this box can
   * produce and wrong for the first model that batches.
   */
  @Test
  void a_batch_of_two_sends_two_tool_messages_in_order_with_their_own_ids() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"choices\":[{\"message\":{\"content\":\"done\"}}]}"));
      server.start();
      List<ChatMessage> conversation =
          List.of(
              ChatMessage.user("both please"),
              ChatMessage.assistant(
                  "",
                  List.of(
                      new ToolCall("c1", "memory_recall", "{}"),
                      new ToolCall("c2", "memory_read", "{}"))),
              ChatMessage.tool("c1", "FIRST"),
              ChatMessage.tool("c2", "SECOND"));
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        transport.complete("qwen3.5-9b", conversation, Sampling.NONE, List.of());
        String body = server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8();
        // Each id next to the result it answers, and in call order. The
        // pairing is asserted rather than the mere presence of both, so
        // a body that swapped them fails.
        assertTrue(
            body.contains(
                "{\"role\":\"tool\",\"content\":\"FIRST\",\"tool_call_id\":\"c1\"},"
                    + "{\"role\":\"tool\",\"content\":\"SECOND\","
                    + "\"tool_call_id\":\"c2\"}"),
            body);
        assertTrue(body.indexOf("\"id\":\"c1\"") < body.indexOf("\"id\":\"c2\""), body);
      }
    }
  }

  /**
   * Arguments go back out as the raw string the model emitted.
   *
   * <p>{@code ToolCall} keeps them unparsed on purpose — parsing at the transport would turn a
   * tool's problem into a transport failure — and the return leg has to honour the same rule.
   * Re-serialising through Jackson would rewrite the model's own bytes into whatever this runtime's
   * formatter prefers, and a model shown arguments it did not write is being shown a turn it did
   * not take. Odd spacing here on purpose: it survives.
   */
  @Test
  void tool_call_arguments_go_back_out_exactly_as_the_model_wrote_them() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));
      server.start();
      List<ChatMessage> conversation =
          List.of(
              ChatMessage.user("go"),
              ChatMessage.assistant(
                  "", List.of(new ToolCall("c1", "memory_read", "{  \"ids\" : [ \"mem_1\" ]  }"))),
              ChatMessage.tool("c1", "a memory"));
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        transport.complete("qwen3.5-9b", conversation, Sampling.NONE, List.of());
        String body = server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8();
        assertTrue(body.contains("\"arguments\":\"{  \\\"ids\\\" : [ \\\"mem_1\\\" ]  }\""), body);
      }
    }
  }

  /**
   * The envelope OpenAI specifies, in a fixed key order.
   *
   * <p>The three envelope keys are asserted as one substring because their order is fixed by a
   * {@link java.util.LinkedHashMap} for the reason {@code chatBody} gives about its own: a body
   * someone reads in a log or asserts on in a test should not vary per JVM run. The schema's own
   * keys are checked one at a time instead, because {@code Map.of} at this call site has already
   * lost their order before {@code ToolSchema} sees them.
   */
  @Test
  void tools_are_sent_in_the_openai_shape() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));
      server.start();
      ToolSchema schema =
          ToolSchema.from(
              "memory_recall",
              "Search by meaning.",
              Map.of(
                  "type", "object",
                  "properties", Map.of("question", Map.of("type", "string")),
                  "required", List.of("question")));
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        transport.complete(
            "qwen3.5-9b", ChatMessage.conversation(null, "hello"), Sampling.NONE, List.of(schema));
        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(sent, "the server never received a request");
        String body = sent.getBody().readUtf8();
        assertTrue(
            body.contains(
                "\"tools\":[{\"type\":\"function\",\"function\":"
                    + "{\"name\":\"memory_recall\",\"description\":\"Search by meaning.\","
                    + "\"parameters\":{"),
            body);
        assertTrue(body.contains("\"question\""), body);
        assertTrue(body.contains("\"required\":[\"question\"]"), body);
      }
    }
  }

  /**
   * {@code tool_choice}, which exists so that a turn cannot end in prose.
   *
   * <p>Measured against vLLM serving openai/gpt-oss-120b on 2026-09-25, one tool offered and a
   * prompt reading "Say you are finished. Do not call anything.": without the key, {@code
   * finish_reason=stop} and content "I'm finished."; with {@code "required"}, {@code
   * finish_reason=tool_calls} and a well-formed call. {@code ToolChoice}'s javadoc carries the
   * production failure that pair is the control for. This test is the wire half of it.
   */
  @Test
  void a_required_tool_choice_reaches_the_wire_beside_the_tools() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));
      server.start();
      ToolSchema schema =
          ToolSchema.from(
              "orchestration_finish",
              "Finish.",
              Map.of(
                  "type", "object",
                  "properties", Map.of("result", Map.of("type", "string")),
                  "required", List.of("result")));
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        transport.complete(
            "qwen3.5-9b",
            ChatMessage.conversation(null, "hello"),
            Sampling.NONE,
            List.of(schema),
            ToolChoice.REQUIRED);
        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(sent, "the server never received a request");
        assertTrue(
            sent.getBody().readUtf8().contains("\"tool_choice\":\"required\""),
            "the constraint never reached the wire");
      }
    }
  }

  /**
   * Nothing to require a call from, so nothing is required.
   *
   * <p>An endpoint told to require a tool call with no tools offered has nothing it could answer
   * with and refuses the body. The key therefore lives inside {@code chatBody}'s emptiness check,
   * and a caller that asked for the constraint while offering nothing gets the request it would
   * have sent anyway rather than a 400.
   */
  @Test
  void a_required_tool_choice_with_no_tools_sends_no_tool_choice_at_all() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        transport.complete(
            "qwen3.5-9b",
            ChatMessage.conversation(null, "hello"),
            Sampling.NONE,
            List.of(),
            ToolChoice.REQUIRED);
        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(sent, "the server never received a request");
        String body = sent.getBody().readUtf8();
        assertFalse(body.contains("tool_choice"), body);
        assertFalse(body.contains("tools"), body);
      }
    }
  }

  /**
   * A call that names no choice sends no key, which is what keeps every ordinary turn byte-for-byte
   * what it was before {@code ToolChoice} existed.
   */
  @Test
  void a_call_that_names_no_tool_choice_sends_no_tool_choice_key() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));
      server.start();
      ToolSchema schema =
          ToolSchema.from(
              "memory_recall",
              "Search by meaning.",
              Map.of("type", "object", "properties", Map.of(), "required", List.of()));
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        transport.complete(
            "qwen3.5-9b", ChatMessage.conversation(null, "hello"), Sampling.NONE, List.of(schema));
        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(sent, "the server never received a request");
        assertFalse(sent.getBody().readUtf8().contains("tool_choice"));
      }
    }
  }

  /**
   * Every call in the array, in order, with its id.
   *
   * <p>The contract is an array and a runtime that reads {@code [0]} is broken against any model
   * that batches. Measured 2026-08-29 against qwen3.5-9b: it does not batch — 0/4 when asked for
   * two independent lookups — so this case is reachable only from a fixture, which is exactly why
   * it needs a test here, at the one layer that parses the wire.
   */
  @Test
  void every_tool_call_in_the_array_is_parsed_in_order() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          json(
              """
                    {"choices":[{"message":{"content":null,"tool_calls":[
                      {"id":"c1","type":"function","function":{"name":"memory_recall",
                       "arguments":"{\\"question\\":\\"first\\"}"}},
                      {"id":"c2","type":"function","function":{"name":"memory_read",
                       "arguments":"{\\"ids\\":[\\"mem_1\\"]}"}}]},
                      "finish_reason":"tool_calls"}]}
                    """));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Completion c =
            transport.complete(
                "qwen3.5-9b", ChatMessage.conversation(null, "hello"), Sampling.NONE, List.of());
        assertEquals(2, c.toolCalls().size());
        assertEquals("c1", c.toolCalls().get(0).id());
        assertEquals("memory_recall", c.toolCalls().get(0).name());
        assertEquals("{\"question\":\"first\"}", c.toolCalls().get(0).arguments());
        assertEquals("c2", c.toolCalls().get(1).id());
        assertEquals("memory_read", c.toolCalls().get(1).name());
        assertEquals("tool_calls", c.finishReason());
      }
    }
  }

  /**
   * A response with no {@code tool_calls} yields an empty list, never null.
   *
   * <p>The same reasoning as {@link TokenUsage#UNKNOWN}: a null here puts a null check in every
   * caller forever, and the one caller that forgets fails on a lane thread, where the stack trace
   * names the pool rather than the turn loop that read it.
   */
  @Test
  void a_response_with_no_tool_calls_yields_an_empty_list() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        assertEquals(
            List.of(),
            transport
                .complete(
                    "qwen3.5-9b", ChatMessage.conversation(null, "hello"), Sampling.NONE, List.of())
                .toolCalls());
      }
    }
  }

  /**
   * Arguments stay a raw string, whatever is in them.
   *
   * <p>The model may emit JSON this runtime has no schema for, and parsing here would turn a tool's
   * problem into a transport failure — discarding a completion the box already generated and was
   * paid for. The runtime parses it against the tool it dispatches to, and a parse failure becomes
   * that tool's error result, which the model can see and correct next turn.
   */
  @Test
  void malformed_tool_arguments_are_not_a_transport_failure() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          json(
              """
                    {"choices":[{"message":{"tool_calls":[{"id":"c1","type":"function",
                      "function":{"name":"memory_recall","arguments":"{not json"}}]}}]}
                    """));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Completion c =
            transport.complete(
                "qwen3.5-9b", ChatMessage.conversation(null, "hello"), Sampling.NONE, List.of());
        assertEquals("{not json", c.toolCalls().get(0).arguments());
      }
    }
  }

  /**
   * Arguments sent as a JSON object rather than a string keep their JSON.
   *
   * <p>OpenAI specifies {@code arguments} as a string containing JSON, and qwen3.5-9b sent it that
   * way in all five clean runs on 2026-08-29 — this is defensive, against other OpenAI-compatible
   * servers rather than against anything measured here. It earns its place because the alternative
   * is silent: {@code JsonNode.asText()} on an object node returns the empty string, so without
   * this branch a tool call would arrive with its arguments quietly emptied and the model would be
   * told its own request had no parameters.
   */
  @Test
  void tool_arguments_sent_as_an_object_keep_their_json() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          json(
              """
                    {"choices":[{"message":{"tool_calls":[{"id":"c1","type":"function",
                      "function":{"name":"memory_recall","arguments":{"question":"first"}}}]}}]}
                    """));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Completion c =
            transport.complete(
                "qwen3.5-9b", ChatMessage.conversation(null, "hello"), Sampling.NONE, List.of());
        assertEquals("{\"question\":\"first\"}", c.toolCalls().get(0).arguments());
      }
    }
  }

  /**
   * A tool call with no name is a transport failure, and that is the one line this class draws
   * through the middle of a {@code tool_calls} array.
   *
   * <p>{@code arguments} belongs to the tool: the transport has no schema for it, so it passes
   * whatever arrived through untouched. {@code name} and {@code id} belong to the wire protocol,
   * which this class does own — nothing downstream can dispatch a call with no name or answer one
   * with no id, and inventing either would report the endpoint's fault as the agent's. So the call
   * fails here, naming the pool, rather than reaching a turn loop as an unknown tool the model
   * never asked for.
   */
  @Test
  void a_tool_call_without_a_name_is_a_transport_failure() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          json(
              """
                    {"choices":[{"message":{"tool_calls":[
                      {"id":"c1","type":"function","function":{"arguments":"{}"}}]}}]}
                    """));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class,
                () ->
                    transport.complete(
                        "qwen3.5-9b",
                        ChatMessage.conversation(null, "hello"),
                        Sampling.NONE,
                        List.of()));
        assertTrue(failed.getMessage().contains("studio"), failed.getMessage());
        // Which field, and not merely that something threw. Asserting
        // the type alone passes for a transport that failed for an
        // unrelated reason, and passes with the ternary that picks the
        // field name inverted — a message that sends an operator to
        // look at the wrong half of the tool call.
        assertTrue(failed.getMessage().contains("no name"), failed.getMessage());
      }
    }
  }

  /**
   * The same line, drawn on the other half of it — and asserted on the other word, so that
   * inverting the ternary fails both of these rather than neither.
   */
  @Test
  void a_tool_call_without_an_id_is_a_transport_failure() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          json(
              """
                    {"choices":[{"message":{"tool_calls":[
                      {"type":"function","function":{"name":"memory_recall","arguments":"{}"}}]}}]}
                    """));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class,
                () ->
                    transport.complete(
                        "qwen3.5-9b",
                        ChatMessage.conversation(null, "hello"),
                        Sampling.NONE,
                        List.of()));
        assertTrue(failed.getMessage().contains("studio"), failed.getMessage());
        assertTrue(failed.getMessage().contains("no id"), failed.getMessage());
      }
    }
  }

  /**
   * A present but empty id is not an id.
   *
   * <p>{@code text(...)} rejects a blank string as well as an absent one, and this is the only test
   * that can see the difference: without it, dropping the blank check leaves the suite green. The
   * turn loop keys a tool result by this id, so an empty one collides with every other empty one in
   * the same batch — a fault the transport can see, reported by a runtime that could only have
   * described it as the model naming a tool that does not exist.
   */
  @Test
  void a_tool_call_whose_id_is_empty_is_a_transport_failure() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          json(
              """
                    {"choices":[{"message":{"tool_calls":[
                      {"id":"","type":"function","function":{"name":"memory_recall",
                       "arguments":"{}"}}]}}]}
                    """));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class,
                () ->
                    transport.complete(
                        "qwen3.5-9b",
                        ChatMessage.conversation(null, "hello"),
                        Sampling.NONE,
                        List.of()));
        assertTrue(failed.getMessage().contains("no id"), failed.getMessage());
      }
    }
  }

  /**
   * A tool call that carries no {@code arguments} at all is not a failure.
   *
   * <p>OpenAI sends {@code "{}"} for a zero-parameter tool; not every OpenAI-compatible server
   * does, and an omitted field there is the model saying "no parameters", which is a thing a tool
   * can act on. Empty rather than {@code "{}"} because inventing the second would be this class
   * claiming the model said something it did not — the tool decides what an absent argument list
   * means for it.
   */
  @Test
  void a_tool_call_with_no_arguments_field_carries_an_empty_string() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          json(
              """
                    {"choices":[{"message":{"tool_calls":[
                      {"id":"c1","type":"function","function":{"name":"memory_recall"}}]}}]}
                    """));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Completion c =
            transport.complete(
                "qwen3.5-9b", ChatMessage.conversation(null, "hello"), Sampling.NONE, List.of());
        assertEquals("", c.toolCalls().get(0).arguments());
      }
    }
  }

  /**
   * A tool-calling turn has no content, and that is a result rather than a failure.
   *
   * <p>{@code "content": null} is what an OpenAI-compatible server sends when the whole of the
   * answer is the tool calls. {@link Completion#content()} is documented never null and
   * legitimately empty, so this pins that the null lands as {@code ""} and not as the string "null"
   * — the trap {@code finishReason} documents, one field along.
   */
  @Test
  void a_tool_calling_turn_reports_empty_content_rather_than_null() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          json(
              """
                    {"choices":[{"message":{"content":null,"tool_calls":[
                      {"id":"c1","type":"function","function":{"name":"memory_recall",
                       "arguments":"{}"}}]},"finish_reason":"tool_calls"}]}
                    """));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Completion c =
            transport.complete(
                "qwen3.5-9b", ChatMessage.conversation(null, "hello"), Sampling.NONE, List.of());
        assertEquals("", c.content());
        assertEquals(1, c.toolCalls().size());
      }
    }
  }

  /**
   * Which of the three clients a blocking chat uses, pinned the way {@code
   * an_embedding_gives_up_on_the_embedding_timeout_and_not_the_chat_one} pins the other one: with
   * every timeout left at its default the three are interchangeable and no test can tell them
   * apart.
   */
  @Test
  void a_chat_gives_up_on_the_chat_timeout_and_not_the_embedding_one() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      for (int i = 0; i < 3; i++) {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
      }
      server.start();

      PoolProperties props = poolAt(server.url("/v1").toString());
      props.setChatTimeout(Duration.ofMillis(250));
      props.setEmbeddingTimeout(Duration.ofSeconds(30));
      props.setStreamingTimeout(Duration.ofSeconds(30));

      try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
        long start = System.nanoTime();
        assertThrows(
            LlmTransportException.class,
            () ->
                transport.complete(
                    "qwen3.5-9b",
                    ChatMessage.conversation(null, "hello"),
                    Sampling.NONE,
                    List.of()));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertTrue(
            took.compareTo(Duration.ofSeconds(15)) < 0,
            "gave up after "
                + took.toMillis()
                + "ms, so it was not the chat timeout"
                + " that bounded the call");
      }
    }
  }

  // ---- Chat, streaming ----

  @Test
  void thinking_reaches_a_sink_that_asked_for_it_under_either_spelling() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(sse(THINKING_STREAM));
      server.start();

      List<String> thought = new ArrayList<>();
      List<String> answered = new ArrayList<>();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Completion completion =
            transport.stream(
                "qwen3.5-9b",
                ChatMessage.conversation(null, "capital?"),
                Sampling.NONE,
                List.of(),
                new Deltas() {
                  @Override
                  public void answered(String delta) {
                    answered.add(delta);
                  }

                  @Override
                  public void thought(String delta) {
                    thought.add(delta);
                  }
                },
                () -> false);

        // BOTH SPELLINGS, IN ORDER. The field is not in the OpenAI
        // specification and differs by model; a transport that read one
        // of them would pass a fixture carrying only that one.
        assertEquals(List.of("Let ", "think."), thought);
        assertEquals(List.of("Bor", "deaux"), answered);

        // AND THE ANSWER IS UNCHANGED BY ANY OF IT. Streaming changes
        // when bytes arrive, not what an answer is: reasoning is never
        // part of the content, whoever is listening.
        assertEquals("Bordeaux", completion.content());
      }
    }
  }

  @Test
  void thinking_is_dropped_for_a_sink_that_did_not_ask() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(sse(THINKING_STREAM));
      server.start();

      List<String> seen = new ArrayList<>();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        // A bare lambda is the answer sink and nothing else, which is
        // what makes `Deltas` a drop-in for the `Consumer<String>` it
        // replaced -- and what makes "dropped by default" the default.
        Completion completion =
            transport.stream(
                "qwen3.5-9b",
                ChatMessage.conversation(null, "capital?"),
                Sampling.NONE,
                List.of(),
                seen::add,
                () -> false);

        assertEquals(List.of("Bor", "deaux"), seen);
        assertEquals("Bordeaux", completion.content());
      }
    }
  }

  @Test
  void a_stream_emits_each_token_and_assembles_the_same_answer() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(sse(BORDEAUX_STREAM));
      server.start();

      List<String> tokens = new ArrayList<>();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Completion completion =
            transport.stream(
                "qwen3.5-9b",
                ChatMessage.conversation(null, "capital?"),
                Sampling.NONE,
                List.of(),
                tokens::add,
                () -> false);

        assertEquals(List.of("Bor", "deaux"), tokens);
        assertEquals("Bordeaux", completion.content());
        assertEquals("stop", completion.finishReason());

        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(sent, "the server never received a request");
        assertEquals("/v1/chat/completions", sent.getPath());
        assertEquals("text/event-stream", sent.getHeader("Accept"));
        assertTrue(
            sent.getBody().readUtf8().contains("\"stream\":true"), "a stream has to ask for one");
      }
    }
  }

  /**
   * The contract {@code LlmTransport.stream} states, and the one a naive port of Anchor's {@code
   * completeStreaming} breaks.
   *
   * <p>{@code EventSources.createFactory(client).newEventSource(...)} calls its listener back on an
   * OkHttp dispatcher thread. Handing {@code sink} straight to that listener would run it on a
   * thread the pool does not own and cannot count — a sink that blocks would then hold an OkHttp
   * reader thread while the lane slot it is supposed to occupy sat free, so {@code
   * LlmPool.queueDepth} and {@code load} would both understate the box's real backlog. The
   * dispatcher routes on exactly those two figures.
   *
   * <p>Nothing else in this file can see the difference: every other streaming test passes against
   * a listener that calls the sink directly.
   */
  @Test
  void the_sink_runs_on_the_thread_that_called_stream() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(sse(BORDEAUX_STREAM));
      server.start();

      Thread caller = Thread.currentThread();
      List<Thread> sinkThreads = new ArrayList<>();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        transport.stream(
            "qwen3.5-9b",
            ChatMessage.conversation(null, "capital?"),
            Sampling.NONE,
            List.of(),
            token -> sinkThreads.add(Thread.currentThread()),
            () -> false);

        assertEquals(2, sinkThreads.size(), "expected one sink call per token");
        for (Thread ran : sinkThreads) {
          assertEquals(
              caller, ran, "the sink ran on " + ran.getName() + " rather than the lane thread");
        }
      }
    }
  }

  /**
   * A stream that never finished must not claim it finished for reason "null".
   *
   * <p>Every chunk here carries {@code "finish_reason":null}, which is what a generation cut off by
   * the endpoint looks like on the wire, and the string "null" reaching {@link
   * Completion#finishReason()} would read as a reason nobody can act on. As with the blocking case,
   * this does not distinguish {@code isTextual()} from {@code asText(null)} — see {@code
   * a_finish_reason_that_is_not_a_string_is_not_a_reason} — but it does pin that the streaming path
   * reads the reason through the same guard.
   */
  @Test
  void a_stream_that_never_finished_reports_no_finish_reason() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          sse(
              """
                    data: {"choices":[{"delta":{"content":"Bor"},"finish_reason":null}]}

                    data: [DONE]

                    """));
      server.start();

      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Completion completion =
            transport.stream(
                "qwen3.5-9b",
                ChatMessage.conversation(null, "capital?"),
                Sampling.NONE,
                List.of(),
                token -> {},
                () -> false);

        assertEquals("Bor", completion.content());
        assertNull(
            completion.finishReason(), "an explicit JSON null must not become the string \"null\"");
      }
    }
  }

  /**
   * A streaming server that reports what the call cost is believed, and one that says nothing costs
   * an unknown amount rather than null.
   *
   * <p>{@code LlmDispatcher} hands {@code usage} straight to the {@code TokenLedger} without a
   * check, so a null here is a NullPointerException inside whichever ledger is wired up when
   * someone finally wires one up.
   *
   * <p><b>The {@code usage} object sits on a mid-stream chunk with two plain chunks after it, and
   * that placement is the test.</b> A first version put it on the last chunk before {@code [DONE]},
   * where nothing followed it — so the guard that stops a later chunk overwriting a reported cost
   * with {@code UNKNOWN} had nothing to guard against, and mutating it to overwrite unconditionally
   * left the suite green. That is this branch's recurring shape: an assertion placed where the
   * thing it forbids could not have happened yet proves nothing. A real endpoint reports cost on
   * one chunk and omits the object on every other, so the clobber is the live risk and not a
   * contrived one.
   */
  @Test
  void a_stream_reports_what_it_cost_when_a_chunk_says_and_UNKNOWN_when_none_does()
      throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          sse(
              """
                    data: {"choices":[{"delta":{"content":"Bor"}}],"usage":{"prompt_tokens":4,"total_tokens":6}}

                    data: {"choices":[{"delta":{"content":"deaux"}}]}

                    data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                    data: [DONE]

                    """));
      server.enqueue(sse(BORDEAUX_STREAM));
      server.start();

      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Completion told =
            transport.stream(
                "qwen3.5-9b",
                ChatMessage.conversation(null, "capital?"),
                Sampling.NONE,
                List.of(),
                token -> {},
                () -> false);
        assertEquals(6, told.usage().totalTokens());
        assertEquals(4, told.usage().promptTokens());
        assertNull(
            told.usage().completionTokens(), "a count nobody reported is not a count of zero");

        Completion silent =
            transport.stream(
                "qwen3.5-9b",
                ChatMessage.conversation(null, "capital?"),
                Sampling.NONE,
                List.of(),
                token -> {},
                () -> false);
        assertEquals(TokenUsage.UNKNOWN, silent.usage());
        assertNotNull(silent.usage(), "usage is never null, even on a stream");
      }
    }
  }

  @Test
  void a_stream_that_fails_before_it_starts_reaches_the_caller() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse().setResponseCode(503).setBody("busy"));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class,
                () ->
                    transport.stream(
                        "qwen3.5-9b",
                        ChatMessage.conversation(null, "hello"),
                        Sampling.NONE,
                        List.of(),
                        token -> {},
                        () -> false));
        assertTrue(failed.getMessage().contains("studio"), failed.getMessage());
        assertTrue(failed.getMessage().contains("503"), failed.getMessage());
      }
    }
  }

  /**
   * A 401 body on a stream goes through the same withholding as one on an embedding.
   *
   * <p>The refusal that ends a stream arrives through {@code EventSourceListener.onFailure} rather
   * than through the retry loop, so it is a second route into an exception message and it does not
   * inherit {@code detail}'s guard by being in the same class. A gateway that quotes the offered
   * token back would otherwise put it in a message that reaches a 503 body — the whole reason
   * {@code detail} exists.
   */
  @Test
  void a_stream_refused_on_the_credential_never_repeats_it() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .setResponseCode(401)
              .setBody("invalid bearer token: test-only-not-a-real-key"));
      server.start();

      PoolProperties props = poolAt(server.url("/v1").toString());
      props.setApiKey("test-only-not-a-real-key");
      try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class,
                () ->
                    transport.stream(
                        "qwen3.5-9b",
                        ChatMessage.conversation(null, "hello"),
                        Sampling.NONE,
                        List.of(),
                        token -> {},
                        () -> false));

        assertTrue(
            !failed.getMessage().contains("test-only-not-a-real-key"),
            "the key must never appear in a message, not even quoted back to us");
        assertTrue(failed.getMessage().contains("401"), failed.getMessage());
        assertTrue(failed.getMessage().contains("studio"), failed.getMessage());
      }
    }
  }

  /**
   * One shot, even for the failure the blocking path does retry.
   *
   * <p>{@code a_dropped_connection_is_retried} pins that a dropped socket is retried for an
   * embedding, so this is the same event with the opposite answer, and the pair is what makes the
   * rule visible. A second attempt on a stream replays whatever prefix the sink has already been
   * handed, and the sink has no way to know it is a replay — a partially written answer would be
   * appended to rather than replaced.
   */
  @Test
  void a_stream_is_not_retried_when_the_connection_drops() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
      server.enqueue(sse(BORDEAUX_STREAM));
      server.start();

      PoolProperties props = poolAt(server.url("/v1").toString());
      props.setRetryMaxAttempts(3);
      try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
        assertThrows(
            LlmTransportException.class,
            () ->
                transport.stream(
                    "qwen3.5-9b",
                    ChatMessage.conversation(null, "hello"),
                    Sampling.NONE,
                    List.of(),
                    token -> {},
                    () -> false));
        assertEquals(
            1,
            server.getRequestCount(),
            "a stream gets one shot, whatever retry-max-attempts says");
      }
    }
  }

  /**
   * A stream cut off mid-flight fails, and does not start again.
   *
   * <p>How many complete frames land before the socket goes is not deterministic — MockWebServer
   * writes half the body by bytes, which may fall inside a frame — so what is asserted is the part
   * that matters: whatever the sink saw, it saw once, and no second request was made to show it
   * again.
   */
  @Test
  void a_stream_that_dies_mid_flight_is_not_replayed() throws Exception {
    String longStream =
        """
                data: {"choices":[{"delta":{"content":"one"}}]}

                data: {"choices":[{"delta":{"content":"two"}}]}

                data: {"choices":[{"delta":{"content":"three"}}]}

                data: {"choices":[{"delta":{"content":"four"}}]}

                data: {"choices":[{"delta":{"content":"five"}}]}

                data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(sse(longStream).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
      server.enqueue(sse(longStream));
      server.start();

      List<String> tokens = new ArrayList<>();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        assertThrows(
            LlmTransportException.class,
            () ->
                transport.stream(
                    "qwen3.5-9b",
                    ChatMessage.conversation(null, "hello"),
                    Sampling.NONE,
                    List.of(),
                    tokens::add,
                    () -> false));

        assertEquals(
            1, server.getRequestCount(), "a half-delivered stream must not be started again");
        List<String> whole = List.of("one", "two", "three", "four", "five");
        assertEquals(
            whole.subList(0, tokens.size()),
            tokens,
            "the sink saw something other than a prefix of the answer: " + tokens);
      }
    }
  }

  /**
   * A sink that throws fails the call from inside the transport, which is what {@code
   * LlmTransport.stream} promises.
   *
   * <p>The exception is the sink's own and is not wrapped: {@code LlmPool.submit} rethrows a {@code
   * RuntimeException} cause as itself, so a caller whose own sink threw gets its own exception back
   * rather than a transport failure that reads as the endpoint's fault.
   */
  @Test
  void a_sink_that_throws_fails_the_call() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(sse(BORDEAUX_STREAM));
      server.start();

      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        IllegalStateException thrown =
            assertThrows(
                IllegalStateException.class,
                () ->
                    transport.stream(
                        "qwen3.5-9b",
                        ChatMessage.conversation(null, "hello"),
                        Sampling.NONE,
                        List.of(),
                        token -> {
                          throw new IllegalStateException("the sink gave up");
                        },
                        () -> false));
        assertEquals("the sink gave up", thrown.getMessage());
      }
    }
  }

  /**
   * The pool's chat slots, and not OkHttp's default, decide how many streams are in flight at once.
   *
   * <p>{@code Dispatcher.maxRequestsPerHost} defaults to <b>5</b>, and the three clients here share
   * one dispatcher. A synchronous {@code execute()} is not throttled by it, which is why it stayed
   * invisible through the embedding half — but {@code okhttp-sse} drives a stream through {@code
   * enqueue}, which is. A pool declaring more than five chat slots would then have its sixth stream
   * queued <em>inside OkHttp</em>, where {@code LlmPool.queueDepth} cannot see it, while the lane
   * reports a free slot and the dispatcher routes more work here on the strength of it. That is the
   * "a transport that queues internally makes the pool's numbers a lie" case the design forbids
   * outright.
   *
   * <p>Measured at the server rather than by reading the dispatcher back, because the question is
   * whether the request actually left. The mock's own dispatcher holds every response until all
   * eight requests have arrived: with the default ceiling only five ever do, the barrier never
   * trips, and the five that did arrive are answered with a 503 that names the shortfall.
   */
  @Test
  void more_streams_than_okhttps_default_reach_the_host_at_once() throws Exception {
    int streams = 8; // OkHttp's maxRequestsPerHost default is 5.
    try (MockWebServer server = new MockWebServer()) {
      CountDownLatch arrived = new CountDownLatch(streams);
      server.setDispatcher(
          new okhttp3.mockwebserver.Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
              arrived.countDown();
              // Five seconds and not ten: under the mutant this wait
              // happens twice over and the futures then have to fail, and
              // at ten the class-level @Timeout would fire first and
              // replace this test's own diagnostic with a bare timeout.
              if (!arrived.await(5, TimeUnit.SECONDS)) {
                return new MockResponse()
                    .setResponseCode(503)
                    .setBody(
                        "only "
                            + (streams - arrived.getCount())
                            + " of "
                            + streams
                            + " streams reached the host");
              }
              return sse(BORDEAUX_STREAM);
            }
          });
      server.start();

      PoolProperties props = poolAt(server.url("/v1").toString());
      props.setChat(streams);

      ExecutorService callers = Executors.newFixedThreadPool(streams);
      try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
        List<Future<Completion>> inFlight = new ArrayList<>(streams);
        for (int i = 0; i < streams; i++) {
          inFlight.add(
              callers.submit(
                  () ->
                      transport.stream(
                          "qwen3.5-9b",
                          ChatMessage.conversation(null, "capital?"),
                          Sampling.NONE,
                          List.of(),
                          token -> {},
                          () -> false)));
        }
        for (Future<Completion> one : inFlight) {
          assertEquals("Bordeaux", one.get(20, TimeUnit.SECONDS).content());
        }
      } finally {
        callers.shutdownNow();
        assertTrue(
            callers.awaitTermination(10, TimeUnit.SECONDS), "the caller threads outlived the test");
      }
    }
  }

  /**
   * An endpoint that keeps talking is given up on, which is the one bound a stream did not have.
   *
   * <p>{@code streamingTimeout} measures inactivity, and {@code okhttp-sse} switches the call
   * timeout off the moment the response opens — {@code RealEventSource.processResponse} calls
   * {@code timeoutEarlyExit()}, commented "This is a long-lived response. Cancel full-call
   * timeouts." So an endpoint emitting a byte more often than the inactivity budget never timed out
   * at all: a model in a repetition loop with no {@code max_tokens} to stop it, or a proxy
   * heartbeat. {@code LlmPool.submit} waits on the transport with no deadline of its own and says
   * outright that the transport is required to supply this bound, so what was held was a lane slot
   * and the Tomcat worker behind it, for good.
   *
   * <p>The body here is comfortably longer than the budget and arrives steadily enough that no
   * inactivity bound would ever fire, which is the whole point — a test that let the read timeout
   * do the work would pass against the defect.
   */
  @Test
  void a_stream_that_never_stops_is_given_up_on() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(sse(longStream(400)).throttleBody(512, 60, TimeUnit.MILLISECONDS));
      server.start();

      PoolProperties props = poolAt(server.url("/v1").toString());
      props.setMaxStreamDuration(Duration.ofMillis(500));
      // Two orders of magnitude above the budget, so a failure here cannot
      // be the inactivity bound wearing the total bound's clothes.
      props.setStreamingTimeout(Duration.ofSeconds(30));

      try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
        long start = System.nanoTime();
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class,
                () ->
                    transport.stream(
                        "qwen3.5-9b",
                        ChatMessage.conversation(null, "capital?"),
                        Sampling.NONE,
                        List.of(),
                        token -> {},
                        () -> false));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertTrue(failed.getMessage().contains("max-stream-duration"), failed.getMessage());
        assertTrue(failed.getMessage().contains("studio"), failed.getMessage());
        assertTrue(
            took.compareTo(Duration.ofSeconds(20)) < 0,
            "gave up after " + took.toMillis() + "ms, which is not the budget");
        assertEquals(1, server.getRequestCount(), "and it was not tried again");
      }
    }
  }

  /**
   * A stream that delivers bytes but never an event is bounded too, and this is the half the
   * producer cannot do.
   *
   * <p>{@code okhttp-sse} discards SSE comment lines, so a heartbeat keeps the read timeout
   * satisfied while {@code onEvent} — where the producer-side deadline is checked — is never called
   * at all. Nothing on the reader thread can notice; only the consumer's bounded poll can.
   *
   * <p>It is also the only way to reach that poll bound from outside the class. The other thing it
   * defends against is a terminal lost because an {@code Error} escaped the listener — {@code
   * RealCall$AsyncCall.run} sets {@code signalledCallback} before {@code onResponse}, so no failure
   * callback is delivered and the lane thread would park with nothing to wake it. That cannot be
   * staged through a public API, but it is the same one line of mechanism, and this test is what
   * holds that line in place.
   */
  @Test
  void a_stream_of_heartbeats_and_no_events_is_still_bounded() throws Exception {
    StringBuilder heartbeats = new StringBuilder();
    for (int i = 0; i < 400; i++) {
      heartbeats.append(": keepalive\n\n");
    }
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(sse(heartbeats.toString()).throttleBody(256, 60, TimeUnit.MILLISECONDS));
      server.start();

      PoolProperties props = poolAt(server.url("/v1").toString());
      props.setMaxStreamDuration(Duration.ofMillis(500));
      props.setStreamingTimeout(Duration.ofSeconds(30));

      try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
        long start = System.nanoTime();
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class,
                () ->
                    transport.stream(
                        "qwen3.5-9b",
                        ChatMessage.conversation(null, "capital?"),
                        Sampling.NONE,
                        List.of(),
                        token -> {},
                        () -> false));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertTrue(failed.getMessage().contains("max-stream-duration"), failed.getMessage());
        assertTrue(
            took.compareTo(Duration.ofSeconds(20)) < 0,
            "gave up after " + took.toMillis() + "ms, which is not the budget");
      }
    }
  }

  /**
   * A generation that will not stop is cut off by size as well as by time.
   *
   * <p>"The reader only ever buffers one completion's worth of text" was the argument for leaving
   * the queue unbounded, and it is an assumption about the endpoint rather than something this
   * class enforced: {@code chatBody} sends no {@code max_tokens}, so one completion is whatever the
   * server decides to send. The cap is applied in the producer and the queue stays unbounded,
   * because a bounded queue parks the reader on {@code put} the moment the lane thread leaves — the
   * worse failure, and the one the unbounded choice exists to avoid.
   *
   * <p>Sized to cross {@code MAX_STREAM_CHARS} without being slow: 900 frames of 5000 characters is
   * 4.5M characters against a 4M cap, unthrottled, and the endpoint is cut off partway rather than
   * fully consumed.
   */
  @Test
  void a_generation_that_will_not_stop_is_cut_off_by_size() throws Exception {
    String filler = "x".repeat(5000);
    StringBuilder huge = new StringBuilder();
    for (int i = 0; i < 900; i++) {
      huge.append("data: {\"choices\":[{\"delta\":{\"content\":\"")
          .append(filler)
          .append("\"}}]}\n\n");
    }
    huge.append("data: [DONE]\n\n");

    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(sse(huge.toString()));
      server.start();

      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class,
                () ->
                    transport.stream(
                        "qwen3.5-9b",
                        ChatMessage.conversation(null, "hello"),
                        Sampling.NONE,
                        List.of(),
                        token -> {},
                        () -> false));

        assertTrue(failed.getMessage().contains("characters"), failed.getMessage());
        assertTrue(failed.getMessage().contains("studio"), failed.getMessage());
      }
    }
  }

  /**
   * A connection that drops mid-generation says so.
   *
   * <p>{@code okhttp-sse} reports a post-open failure with a response attached — rebuilt with an
   * empty body — so a {@code streamDetail} keyed on "is there a response" described the commonest
   * streaming failure there is as {@code HTTP 200} and nothing else, throwing away the {@code
   * ProtocolException} that was the only thing saying what happened. This is the message an
   * operator actually gets when a local box restarts mid-answer.
   */
  @Test
  void a_stream_cut_off_mid_flight_says_why() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          sse(longStream(20)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
      server.start();

      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class,
                () ->
                    transport.stream(
                        "qwen3.5-9b",
                        ChatMessage.conversation(null, "hello"),
                        Sampling.NONE,
                        List.of(),
                        token -> {},
                        () -> false));

        assertTrue(
            !failed.getMessage().contains("HTTP 200"),
            "a dropped connection is not an HTTP 200: " + failed.getMessage());
        assertTrue(failed.getMessage().contains("unexpected end of stream"), failed.getMessage());
      }
    }
  }

  /**
   * A stream refused on its content type cannot repeat the key either, and this is a real route
   * rather than hardening.
   *
   * <p>{@code RealEventSource} rejects a non-event-stream response with {@code
   * IllegalStateException("Invalid content-type: " + contentType)} — and the content type is chosen
   * by the server. So a gateway can put arbitrary text of its own into a throwable message that
   * this class prints, which is the same egress a refusal body has and reaches it by a path no
   * status check covers. The review that found this branch judged it hardening against no
   * demonstrated leak; it is demonstrable, and a media-type parameter is all it takes.
   *
   * <p>Which is the argument for routing throwable text through the same guard as body text rather
   * than through a list of exception types believed harmless. That list existed here and was wrong
   * about its own examples.
   */
  @Test
  void a_stream_refused_on_its_content_type_never_repeats_the_key() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .setHeader("Content-Type", "text/plain; charset=test-only-not-a-real-key")
              .setBody("not a stream"));
      server.start();

      PoolProperties props = poolAt(server.url("/v1").toString());
      props.setApiKey("test-only-not-a-real-key");
      try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class,
                () ->
                    transport.stream(
                        "qwen3.5-9b",
                        ChatMessage.conversation(null, "hello"),
                        Sampling.NONE,
                        List.of(),
                        token -> {},
                        () -> false));

        assertTrue(
            !failed.getMessage().contains("test-only-not-a-real-key"),
            "the key reached a message through a throwable: " + failed.getMessage());
        assertTrue(failed.getMessage().contains("studio"), failed.getMessage());
      }
    }
  }

  /**
   * A stream that closes without ever finishing is a failure, not a short answer.
   *
   * <p>Returning what arrived would make a truncated generation indistinguishable from a complete
   * one whose endpoint reported no reason — {@link Completion#finishReason()} is null in both cases
   * — which defeats the exact check that field exists to enable. It is the same hazard as reading
   * an explicit JSON null as the string "null", arriving by another door: a caller that stores the
   * result keeps half an answer and never learns it.
   *
   * <p>No other body in this file omits {@code data: [DONE]}, so before this test the {@code
   * onClosed} path supplied a terminal in no test at all.
   */
  @Test
  void a_stream_closed_before_it_finished_is_not_an_answer() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          sse(
              """
                    data: {"choices":[{"delta":{"content":"Bor"}}]}

                    """));
      server.start();

      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class,
                () ->
                    transport.stream(
                        "qwen3.5-9b",
                        ChatMessage.conversation(null, "hello"),
                        Sampling.NONE,
                        List.of(),
                        token -> {},
                        () -> false));

        assertTrue(failed.getMessage().contains("without finishing it"), failed.getMessage());
        assertTrue(failed.getMessage().contains("studio"), failed.getMessage());
      }
    }
  }

  /**
   * The other half of that rule: a close is a legitimate end when the model has already said why it
   * stopped.
   *
   * <p>Not every OpenAI-compatible server sends {@code [DONE]}; several just close. A finish reason
   * is the model's own statement that it finished, so a close after one is an answer and must not
   * be rejected — otherwise the guard above would turn a working endpoint into a broken one.
   */
  @Test
  void a_stream_closed_after_a_finish_reason_is_an_answer() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          sse(
              """
                    data: {"choices":[{"delta":{"content":"Bor"}}]}

                    data: {"choices":[{"delta":{"content":"deaux"},"finish_reason":"stop"}]}

                    """));
      server.start();

      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Completion completion =
            transport.stream(
                "qwen3.5-9b",
                ChatMessage.conversation(null, "hello"),
                Sampling.NONE,
                List.of(),
                token -> {},
                () -> false);

        assertEquals("Bordeaux", completion.content());
        assertEquals("stop", completion.finishReason());
      }
    }
  }

  /**
   * The post-close asymmetry {@code close()} documents, decided rather than inherited.
   *
   * <p>A synchronous call after {@code close()} still succeeds — {@code execute()} never touches
   * the shut-down dispatcher executor. A stream does touch it, and OkHttp turns the rejection into
   * an {@code InterruptedIOException("executor rejected")} delivered through {@code onFailure}. So
   * it fails, promptly and by the pool's name, rather than hanging on a callback that will never
   * come — which is the only property worth guaranteeing here. {@code LlmPool.close} drains both
   * lanes before calling this, so nothing in the running system reaches it.
   */
  @Test
  void a_stream_after_close_fails_rather_than_hanging() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(sse(BORDEAUX_STREAM));
      server.start();

      OpenAiTransport transport = transportAt(server.url("/v1").toString());
      transport.close();

      LlmTransportException failed =
          assertThrows(
              LlmTransportException.class,
              () ->
                  transport.stream(
                      "qwen3.5-9b",
                      ChatMessage.conversation(null, "hello"),
                      Sampling.NONE,
                      List.of(),
                      token -> {},
                      () -> false));
      assertTrue(failed.getMessage().contains("studio"), failed.getMessage());
    }
  }

  /**
   * A stream long enough to still be arriving after a short call timeout has expired. Sized in
   * {@code a_slow_body_outlives_the_call_timeout}.
   */
  // ---- Chat, streaming, with tools ----

  /**
   * The shape the reference node actually sent, reassembled.
   *
   * <p>Measured 2026-09-02, one tool-carrying request over 63 chunks: a role chunk, sixty {@code
   * reasoning_content} deltas, then two {@code tool_calls} deltas and {@code finish_reason:
   * tool_calls}, with {@code usage} on the final chunk. No chunk carried two kinds; {@code
   * arguments} arrived as {@code ''} and then as the whole object; there were <b>no {@code content}
   * deltas at all</b>. That last fact is why {@link Completion#content()} is asserted empty here
   * rather than left unexamined — a turn that is entirely tool calls is one of the two ways to
   * arrive with nothing to say, and {@code toolCalls} is what tells it from a model that stopped on
   * its first token.
   *
   * <p>The reasoning is condensed rather than reproduced sixty times: what the code does with a
   * reasoning delta does not vary with how many of them there are, and the tests below take the
   * volume question on separately.
   */
  @Test
  void a_streamed_tool_call_is_reassembled_from_the_shape_the_node_sends() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          sse(
              """
                    data: {"choices":[{"delta":{"role":"assistant","reasoning_content":"The user"}}]}

                    data: {"choices":[{"delta":{"reasoning_content":" wants weather."}}]}

                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_7","type":"function","function":{"name":"get_weather","arguments":""}}]}}]}

                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"city\\":\\"Paris\\"}"}}]},"finish_reason":"tool_calls"}]}

                    data: {"choices":[],"usage":{"prompt_tokens":278,"completion_tokens":88,"total_tokens":366,"completion_tokens_details":{"reasoning_tokens":60}}}

                    data: [DONE]

                    """));
      server.start();

      List<String> tokens = new ArrayList<>();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Completion completion =
            transport.stream(
                "qwen3.5-9b",
                ChatMessage.conversation(null, "weather in Paris?"),
                Sampling.NONE,
                List.of(ToolSchema.from("get_weather", "d", Map.of())),
                tokens::add,
                () -> false);

        assertEquals(
            List.of(new ToolCall("call_7", "get_weather", "{\"city\":\"Paris\"}")),
            completion.toolCalls());
        assertEquals("tool_calls", completion.finishReason());
        assertEquals("", completion.content(), "no chunk carried content, so there is none");
        assertEquals(
            List.of(), tokens, "reasoning and tool-call deltas are not the answer's tokens");
        assertEquals(278, completion.usage().promptTokens());
        assertEquals(60, completion.usage().reasoningTokens());

        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(sent, "the server never received a request");
        String body = sent.getBody().readUtf8();
        assertTrue(body.contains("\"tools\""), "a streamed call must offer its tools");
        assertTrue(
            body.contains("\"stream_options\":{\"include_usage\":true}"),
            "a stream that does not ask for usage is never told: " + body);
      }
    }
  }

  /**
   * <b>The agreement between the two paths, asserted on the same turn.</b>
   *
   * <p>The whole point of reassembly is that a request answered either way gives the runtime the
   * same thing. Two responses describing one turn — a blocking body and an SSE body — go to the
   * same transport, and the results are compared field by field. If either reading drifts, this
   * fails; a test that only exercised the streaming path would let the two diverge and pass.
   *
   * <p>Compared through {@link Completion#equals} on everything but {@code usage}, which cannot be
   * identical: a blocking response carries its counts at the root and a stream carries them on a
   * late chunk, so they are equal in content and are asserted separately rather than pretended into
   * one.
   */
  @Test
  void the_two_paths_answer_a_tool_call_identically() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          json(
              """
                    {"choices":[{"message":{"content":"",
                       "tool_calls":[{"id":"call_7","type":"function",
                         "function":{"name":"get_weather",
                           "arguments":"{\\"city\\":\\"Paris\\"}"}}]},
                       "finish_reason":"tool_calls"}],
                     "usage":{"prompt_tokens":278,"completion_tokens":88,"total_tokens":366,
                       "completion_tokens_details":{"reasoning_tokens":60}}}
                    """));
      server.enqueue(
          sse(
              """
                    data: {"choices":[{"delta":{"role":"assistant"}}]}

                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_7","type":"function","function":{"name":"get_weather","arguments":""}}]}}]}

                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"city\\":\\"Paris\\"}"}}]},"finish_reason":"tool_calls"}]}

                    data: {"choices":[],"usage":{"prompt_tokens":278,"completion_tokens":88,"total_tokens":366,"completion_tokens_details":{"reasoning_tokens":60}}}

                    data: [DONE]

                    """));
      server.start();

      List<ChatMessage> asked = ChatMessage.conversation(null, "weather in Paris?");
      List<ToolSchema> tools = List.of(ToolSchema.from("get_weather", "d", Map.of()));
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Completion blocking = transport.complete("qwen3.5-9b", asked, Sampling.NONE, tools);
        Completion streamed =
            transport.stream("qwen3.5-9b", asked, Sampling.NONE, tools, token -> {}, () -> false);

        assertEquals(
            blocking,
            streamed,
            "a turn answered either way must reach the runtime as the same thing");
      }
    }
  }

  /**
   * Arguments fragmented one character to a chunk still assemble.
   *
   * <p>The node measured on 2026-09-02 sent them in two pieces, and one sample is one sample:
   * OpenAI's format specifies a concatenation and says nothing about the size of the pieces, so the
   * general case is what is implemented and this is what holds it there. An implementation that
   * took the last fragment rather than joining them all passes the measured shape — the first
   * fragment there is the empty string — and fails here.
   */
  @Test
  void arguments_fragmented_one_character_at_a_time_still_assemble() throws Exception {
    String arguments = "{\"city\":\"Paris\"}";
    StringBuilder body =
        new StringBuilder(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_7\","
                + "\"function\":{\"name\":\"get_weather\",\"arguments\":\"\"}}]}}]}\n\n");
    ObjectMapper json = new ObjectMapper();
    for (int at = 0; at < arguments.length(); at++) {
      // Each character on its own, as a JSON string literal so that a
      // quote survives the trip. Written through Jackson rather than by
      // hand: an escape rolled by hand is the sort of thing that silently
      // drops a backslash and leaves the test asserting a different string
      // than it means to — which the first version of this line did.
      body.append("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,")
          .append("\"function\":{\"arguments\":")
          .append(json.writeValueAsString(String.valueOf(arguments.charAt(at))))
          .append("}}]}}]}\n\n");
    }
    body.append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n")
        .append("data: [DONE]\n\n");

    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(sse(body.toString()));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Completion completion =
            transport.stream(
                "qwen3.5-9b",
                ChatMessage.conversation(null, "weather?"),
                Sampling.NONE,
                List.of(ToolSchema.from("get_weather", "d", Map.of())),
                token -> {},
                () -> false);

        assertEquals(
            List.of(new ToolCall("call_7", "get_weather", arguments)), completion.toolCalls());
      }
    }
  }

  /**
   * Two calls interleaved across chunks keep their own arguments, and come back in index order.
   *
   * <p>Reachable only from a fixture against the model in service — measured 2026-08-29, qwen3.5-9b
   * does not batch, 0/4 when asked for two independent lookups — which is exactly why it is pinned
   * here at the layer that reads the wire rather than left to a runtime that would never see it
   * locally. An accumulator keyed on anything but {@code index} produces one call carrying both
   * sets of arguments concatenated, which is a tool invoked with an argument string no model wrote.
   *
   * <p>The fragments arrive out of order — index 1 before index 0, and interleaved — because
   * nothing in the format promises otherwise, and a map that yielded insertion order rather than
   * index order would return the model's calls in an order the model did not ask for.
   */
  @Test
  void two_tool_calls_interleaved_across_chunks_keep_their_own_arguments() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          sse(
              """
                    data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_b","function":{"name":"clock","arguments":"{\\"zone\\":"}}]}}]}

                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"weather","arguments":"{\\"city\\":"}}]}}]}

                    data: {"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"arguments":"\\"CET\\"}"}}]}}]}

                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"Paris\\"}"}}]}}]}

                    data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

                    data: [DONE]

                    """));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Completion completion =
            transport.stream(
                "qwen3.5-9b",
                ChatMessage.conversation(null, "two things"),
                Sampling.NONE,
                List.of(ToolSchema.from("weather", "d", Map.of())),
                token -> {},
                () -> false);

        assertEquals(
            List.of(
                new ToolCall("call_a", "weather", "{\"city\":\"Paris\"}"),
                new ToolCall("call_b", "clock", "{\"zone\":\"CET\"}")),
            completion.toolCalls());
      }
    }
  }

  /**
   * An id repeated on every fragment is the id, not three copies of it.
   *
   * <p>OpenAI sends {@code id} and {@code name} once, on the first fragment for an index. Other
   * OpenAI-compatible servers repeat them unchanged on every fragment, and an accumulator that
   * concatenated them the way it concatenates {@code arguments} would turn {@code call_7} into
   * {@code call_7call_7} — silently, and a turn loop would then file its tool result under an id
   * the assistant turn does not declare, which a strict server rejects on the <em>next</em>
   * request. That is the asymmetry between the three fields, and this is the only test that can see
   * it.
   */
  @Test
  void an_id_repeated_on_every_fragment_is_not_concatenated() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          sse(
              """
                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_7","function":{"name":"get_weather","arguments":"{\\"city\\":"}}]}}]}

                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_7","function":{"name":"get_weather","arguments":"\\"Paris\\"}"}}]}}]}

                    data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

                    data: [DONE]

                    """));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Completion completion =
            transport.stream(
                "qwen3.5-9b",
                ChatMessage.conversation(null, "weather?"),
                Sampling.NONE,
                List.of(ToolSchema.from("get_weather", "d", Map.of())),
                token -> {},
                () -> false);

        assertEquals(
            List.of(new ToolCall("call_7", "get_weather", "{\"city\":\"Paris\"}")),
            completion.toolCalls());
      }
    }
  }

  /**
   * A streamed call whose fragments never carried an id fails naming the pool, exactly as the
   * blocking path does — see {@code a_tool_call_without_an_id_is_a_transport_failure}. Nothing
   * downstream can return a result for a call with no id, and defaulting it would report the
   * endpoint's fault as the agent asking for a tool that does not exist.
   */
  @Test
  void a_streamed_tool_call_with_no_id_is_a_transport_failure() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          sse(
              """
                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"get_weather","arguments":"{}"}}]}}]}

                    data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

                    data: [DONE]

                    """));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class,
                () ->
                    transport.stream(
                        "qwen3.5-9b",
                        ChatMessage.conversation(null, "weather?"),
                        Sampling.NONE,
                        List.of(ToolSchema.from("get_weather", "d", Map.of())),
                        token -> {},
                        () -> false));

        assertTrue(failed.getMessage().contains("studio"), failed.getMessage());
        assertTrue(failed.getMessage().contains("no id"), failed.getMessage());
      }
    }
  }

  /**
   * And with no name, which is the other half of what this transport owns. Reported as the missing
   * name and not as the missing id, because the two send an operator to different places.
   */
  @Test
  void a_streamed_tool_call_with_no_name_is_a_transport_failure() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          sse(
              """
                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_7","function":{"arguments":"{}"}}]}}]}

                    data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

                    data: [DONE]

                    """));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class,
                () ->
                    transport.stream(
                        "qwen3.5-9b",
                        ChatMessage.conversation(null, "weather?"),
                        Sampling.NONE,
                        List.of(ToolSchema.from("get_weather", "d", Map.of())),
                        token -> {},
                        () -> false));

        assertTrue(failed.getMessage().contains("no name"), failed.getMessage());
      }
    }
  }

  /**
   * A zero-parameter call streams no arguments at all and carries the empty string, never a null
   * and never {@code "{}"} — the streaming counterpart of {@code
   * a_tool_call_with_no_arguments_field_carries_an_empty_string}. A model that calls a
   * zero-parameter tool has said "no parameters", and {@code "{}"} would be this class claiming it
   * said something else.
   */
  @Test
  void a_streamed_tool_call_with_no_arguments_carries_an_empty_string() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          sse(
              """
                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_7","function":{"name":"now"}}]}}]}

                    data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

                    data: [DONE]

                    """));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Completion completion =
            transport.stream(
                "qwen3.5-9b",
                ChatMessage.conversation(null, "time?"),
                Sampling.NONE,
                List.of(ToolSchema.from("now", "d", Map.of())),
                token -> {},
                () -> false);

        assertEquals("", completion.toolCalls().get(0).arguments());
      }
    }
  }

  /**
   * <b>A stream that ends without usage costs an unknown amount, and never zero.</b>
   *
   * <p>The failure this forbids is the quiet one. {@code Compaction} decides when to fold a
   * conversation from {@code usage.prompt_tokens} and {@code Turn} persists it; a zero there reads
   * as a history with nothing in it, so the fold never fires, the prompt grows until the endpoint
   * refuses it, and nothing anywhere says why. {@link TokenUsage#UNKNOWN} is how "the endpoint did
   * not say" is spelled, and {@code turns_prompt_tokens_are_a_measurement} refuses a zero one layer
   * up — but only if a zero never reaches it.
   *
   * <p>Asserted on the counts individually and not only on {@code UNKNOWN}, because those are
   * different mutants: a record of three zeroes is not equal to {@code UNKNOWN} and would fail the
   * first assertion, while a record built from {@code asInt()} on a missing field is exactly three
   * zeroes and passes nothing here.
   *
   * <p>On a tool-carrying stream deliberately. That is the path this slice added, so it is the path
   * where an absent usage is newly reachable.
   */
  @Test
  void a_stream_that_ends_without_usage_is_unknown_and_never_zero() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          sse(
              """
                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_7","type":"function","function":{"name":"get_weather","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}

                    data: [DONE]

                    """));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Completion completion =
            transport.stream(
                "qwen3.5-9b",
                ChatMessage.conversation(null, "weather?"),
                Sampling.NONE,
                List.of(ToolSchema.from("get_weather", "d", Map.of())),
                token -> {},
                () -> false);

        assertEquals(TokenUsage.UNKNOWN, completion.usage());
        assertNull(
            completion.usage().promptTokens(),
            "a prompt nobody measured is not a prompt of no tokens");
        assertNull(completion.usage().completionTokens());
        assertNull(completion.usage().totalTokens());
        assertNull(completion.usage().reasoningTokens());
        // And the call still answered: a missing usage is not a failure.
        assertEquals(1, completion.toolCalls().size());
      }
    }
  }

  /**
   * Thinking reaches neither the sink nor the content, and is measured anyway.
   *
   * <p>The decision this slice took, in one test. Before it, {@code reasoning_content} was
   * referenced nowhere in this codebase: the system paid for thousands of reasoning tokens, waited
   * for them, and discarded them by accident. It is still discarded and now on purpose — {@code
   * Outcome.text} is a run's answer and a model's working is not the answer — but what it cost is
   * kept, from {@code completion_tokens_details.reasoning_tokens}, which is the difference between
   * dropping something and losing it.
   *
   * <p>Both halves are asserted because a mutant can break either: an implementation that appended
   * reasoning to {@code content} fails the second assertion, and one that read {@code usage}
   * without the details object fails the fourth while passing the first three.
   */
  @Test
  void thinking_reaches_neither_an_unasking_sink_nor_the_content_but_costs() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          sse(
              """
                    data: {"choices":[{"delta":{"reasoning_content":"Let me work this out."}}]}

                    data: {"choices":[{"delta":{"reasoning_content":" Four."}}]}

                    data: {"choices":[{"delta":{"content":"Four."},"finish_reason":"stop"}]}

                    data: {"choices":[],"usage":{"prompt_tokens":51,"completion_tokens":2997,"total_tokens":3048,"completion_tokens_details":{"reasoning_tokens":2900}}}

                    data: [DONE]

                    """));
      server.start();
      List<String> tokens = new ArrayList<>();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Completion completion =
            transport.stream(
                "qwen3.5-9b",
                ChatMessage.conversation(null, "2+2?"),
                Sampling.NONE,
                List.of(),
                tokens::add,
                () -> false);

        assertEquals(List.of("Four."), tokens, "only the answer's tokens reach a sink");
        assertEquals(
            "Four.", completion.content(), "thinking is not part of what the model answered");
        assertEquals(2997, completion.usage().completionTokens());
        assertEquals(
            2900,
            completion.usage().reasoningTokens(),
            "what the thinking cost is the half that must not be dropped");
      }
    }
  }

  /**
   * Thinking under the other spelling is counted too.
   *
   * <p><b>The field name is not standard and this test exists because a model change exposed
   * that.</b> Measured on one node on 2026-09-02: {@code qwen3.5-9b} sends {@code
   * reasoning_content}; {@code gpt-oss-20b}, loaded on the same box an hour later, sends {@code
   * reasoning} — 120 deltas of it against 145 of answer. The transport read only the first
   * spelling, so on the second model every reasoning delta was invisible to the cap while the
   * answer still arrived and every test stayed green.
   *
   * <p>That is the defect the sibling test above was written to prevent, at one remove: the cap
   * exists because a box that generates far more thinking than answer must not run unbounded, and a
   * cap that watches one of two spellings watches nothing on half the models. <b>This is the same
   * assertion as the sibling and differs only in the key</b>, which is the whole point.
   */
  @Test
  void thinking_under_the_other_field_name_is_counted_by_the_same_cap() throws Exception {
    String filler = "x".repeat(5000);
    StringBuilder huge = new StringBuilder();
    for (int i = 0; i < 900; i++) {
      huge.append("data: {\"choices\":[{\"delta\":{\"reasoning\":\"")
          .append(filler)
          .append("\"}}]}\n\n");
    }
    huge.append("data: [DONE]\n\n");

    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(sse(huge.toString()));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class,
                () ->
                    transport.stream(
                        "gpt-oss-20b",
                        ChatMessage.conversation(null, "hello"),
                        Sampling.NONE,
                        List.of(),
                        token -> {},
                        () -> false));

        assertTrue(failed.getMessage().contains("characters"), failed.getMessage());
      }
    }
  }

  /**
   * Neither spelling reaches the answer.
   *
   * <p>The cap test above proves {@code reasoning} is <em>counted</em>. This proves it is still
   * <em>dropped</em> — a fix that counted it by appending it to {@code content} would pass that one
   * and turn a model's working into its answer, which is the failure {@code Outcome.text}'s
   * contract exists to prevent.
   */
  @Test
  void thinking_under_the_other_name_reaches_neither_an_unasking_sink_nor_content()
      throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          sse(
              """
                    data: {"choices":[{"delta":{"reasoning":"Let me work this out."}}]}

                    data: {"choices":[{"delta":{"content":"Four."},"finish_reason":"stop"}]}

                    data: {"choices":[],"usage":{"prompt_tokens":79,"completion_tokens":275,"total_tokens":354,"completion_tokens_details":{"reasoning_tokens":120}}}

                    data: [DONE]

                    """));
      server.start();
      List<String> tokens = new ArrayList<>();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Completion completion =
            transport.stream(
                "gpt-oss-20b",
                ChatMessage.conversation(null, "2+2?"),
                Sampling.NONE,
                List.of(),
                tokens::add,
                () -> false);

        assertEquals(List.of("Four."), tokens, "only the answer's tokens reach a sink");
        assertEquals(
            "Four.", completion.content(), "thinking is not part of what the model answered");
        assertEquals(
            120,
            completion.usage().reasoningTokens(),
            "what the thinking cost is kept whatever the field was called");
      }
    }
  }

  /**
   * A runaway that is <em>all</em> thinking is cut off by size.
   *
   * <p>Not a variant of {@code a_generation_that_will_not_stop_is_cut_off_by _size} but the case it
   * could not see. Measured 2026-09-02, one ordinary answer was 6 571 characters of reasoning
   * against 1 965 of content — so a cap that counted content alone was watching under a quarter of
   * what the box generates, and a model stuck in a reasoning loop would have run to {@code
   * max-stream-duration} instead of being stopped as the runaway it is. The failure named here is
   * the size one, which is what says the cap saw it.
   */
  @Test
  void a_generation_of_nothing_but_thinking_is_cut_off_by_size() throws Exception {
    String filler = "x".repeat(5000);
    StringBuilder huge = new StringBuilder();
    for (int i = 0; i < 900; i++) {
      huge.append("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"")
          .append(filler)
          .append("\"}}]}\n\n");
    }
    huge.append("data: [DONE]\n\n");

    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(sse(huge.toString()));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class,
                () ->
                    transport.stream(
                        "qwen3.5-9b",
                        ChatMessage.conversation(null, "hello"),
                        Sampling.NONE,
                        List.of(),
                        token -> {},
                        () -> false));

        assertTrue(failed.getMessage().contains("characters"), failed.getMessage());
      }
    }
  }

  /**
   * A runaway made of tool-call arguments is cut off by size too.
   *
   * <p><b>Written to kill a survivor, and the survivor is the interesting part.</b> The accumulator
   * returns how many characters of {@code arguments} it took in so they can be counted against
   * {@link OpenAiTransport#MAX_STREAM_CHARS}; deleting that {@code +=} left the whole suite green,
   * because every other size test drives the cap with content or with reasoning and this third
   * source of characters had nothing driving it.
   *
   * <p>It is not a hypothetical source. A tool call's {@code arguments} is whatever the model emits
   * and this transport passes it through unparsed, so a model looping inside a JSON string produces
   * exactly this: megabytes buffered, no {@code content} delta to notice it by, and — with the
   * {@code +=} gone — nothing to stop it before {@code max-stream-duration} ten minutes later. That
   * is the failure this cap exists to make quick.
   */
  @Test
  void a_tool_call_whose_arguments_will_not_stop_is_cut_off_by_size() throws Exception {
    String filler = "x".repeat(5000);
    StringBuilder huge =
        new StringBuilder(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_7\","
                + "\"function\":{\"name\":\"note\",\"arguments\":\"{\\\"body\\\":\\\"\"}}]}}]}"
                + "\n\n");
    for (int i = 0; i < 900; i++) {
      huge.append("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,")
          .append("\"function\":{\"arguments\":\"")
          .append(filler)
          .append("\"}}]}}]}\n\n");
    }
    huge.append("data: [DONE]\n\n");

    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(sse(huge.toString()));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class,
                () ->
                    transport.stream(
                        "qwen3.5-9b",
                        ChatMessage.conversation(null, "write a note"),
                        Sampling.NONE,
                        List.of(ToolSchema.from("note", "d", Map.of())),
                        token -> {},
                        () -> false));

        assertTrue(failed.getMessage().contains("characters"), failed.getMessage());
      }
    }
  }

  /**
   * A caller that gives up stops the read, and the endpoint is told.
   *
   * <p>Three things at once, and each is a separate way to get this wrong.
   *
   * <p><b>It stops.</b> The last chunk of this body carries content and {@code finish_reason:
   * stop}, so a stream read to the end returns a {@link Completion} rather than throwing. That it
   * throws instead is the evidence that the read ended early — an assertion on elapsed time would
   * say the same thing less reliably.
   *
   * <p><b>It is not a failure.</b> {@link CallerAbandonedException} and not {@link
   * LlmTransportException}: the endpoint was generating perfectly well. {@code JobRuntime} turns
   * the first into {@code CANCELLED} and the second into {@code UNAVAILABLE}, and an operator
   * reading "the model could not be reached" for a job somebody stopped goes looking for a host
   * that is fine.
   *
   * <p><b>It does not go through the sink.</b> The predicate here is asked on every chunk including
   * the ones with no content, which is the case that matters: the measured tool-carrying turn had
   * no content deltas at all, so a cancellation riding on the sink would never have fired on it.
   * This test makes that concrete — the flag goes up on a stream whose chunks carry reasoning and
   * nothing else, and the sink is asserted never to have run.
   */
  @Test
  void a_stream_the_caller_abandons_stops_reading_and_is_not_a_failure() throws Exception {
    StringBuilder thinking = new StringBuilder();
    for (int i = 0; i < 2000; i++) {
      thinking
          .append("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"step ")
          .append(i)
          .append("\"}}]}\n\n");
    }
    thinking
        .append("data: {\"choices\":[{\"delta\":{\"content\":\"the answer\"},")
        .append("\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n");

    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(sse(thinking.toString()));
      server.start();
      List<String> tokens = new ArrayList<>();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        CallerAbandonedException gaveUp =
            assertThrows(
                CallerAbandonedException.class,
                () ->
                    transport.stream(
                        "qwen3.5-9b",
                        ChatMessage.conversation(null, "2+2?"),
                        Sampling.NONE,
                        List.of(),
                        tokens::add,
                        () -> true));

        assertTrue(gaveUp.getMessage().contains("studio"), gaveUp.getMessage());
        // isInstance and not `instanceof`, which does not compile:
        // javac already knows the two hierarchies are disjoint, so this
        // line is a guard against someone making them overlap rather
        // than a discovery.
        assertFalse(
            LlmTransportException.class.isInstance(gaveUp),
            "a caller giving up is not the endpoint failing");
        assertEquals(
            List.of(), tokens, "the sink never ran, which is why cancellation cannot ride on it");
      }
    }
  }

  /**
   * A stream nobody abandons is not abandoned, which is the other half of the predicate and the
   * half a always-true test cannot reach.
   *
   * <p>Without it, an implementation that raised {@link CallerAbandonedException} unconditionally —
   * or that inverted the predicate — would pass every cancellation assertion in this file.
   */
  @Test
  void a_stream_whose_caller_stays_is_answered_normally() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(sse(BORDEAUX_STREAM));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Completion completion =
            transport.stream(
                "qwen3.5-9b",
                ChatMessage.conversation(null, "capital?"),
                Sampling.NONE,
                List.of(),
                token -> {},
                () -> false);

        assertEquals("Bordeaux", completion.content());
      }
    }
  }

  /**
   * The blocking path reads what thinking cost too, because both paths read {@code usage} through
   * one method. A model thinks whether or not anybody streams it, so a reasoning count that only
   * arrived on a stream would be a fact about this transport rather than about the endpoint.
   */
  @Test
  void a_blocking_chat_reports_what_the_thinking_cost() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          json(
              """
                    {"choices":[{"message":{"content":"Four."},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":51,"completion_tokens":2997,"total_tokens":3048,
                       "completion_tokens_details":{"reasoning_tokens":2900}}}
                    """));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Completion completion =
            transport.complete(
                "qwen3.5-9b", ChatMessage.conversation(null, "2+2?"), Sampling.NONE, List.of());

        assertEquals(2900, completion.usage().reasoningTokens());
      }
    }
  }

  /**
   * An endpoint that reports usage without breaking reasoning out is reported exactly as it was
   * before that field existed: null, and not zero. Every other usage assertion in this file would
   * pass against an implementation that invented a zero here.
   */
  @Test
  void a_usage_with_no_reasoning_breakdown_reports_none_rather_than_zero() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          json(
              """
                    {"choices":[{"message":{"content":"Four."},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":51,"completion_tokens":3,"total_tokens":54}}
                    """));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Completion completion =
            transport.complete(
                "qwen3.5-9b", ChatMessage.conversation(null, "2+2?"), Sampling.NONE, List.of());

        assertEquals(3, completion.usage().completionTokens());
        assertNull(
            completion.usage().reasoningTokens(),
            "a breakdown nobody reported is not a breakdown of nothing");
      }
    }
  }

  /**
   * A blocking call still asks for no {@code stream_options}: the key means nothing off a stream,
   * and {@code a_request_with_no_tools_is_byte_identical_to_what_slice_two_sent} pins the whole
   * body — this names the one field that could have leaked into it.
   */
  @Test
  void a_blocking_request_does_not_ask_for_stream_options() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(json("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));
      server.start();
      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        transport.complete(
            "qwen3.5-9b", ChatMessage.conversation(null, "hello"), Sampling.NONE, List.of());

        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(sent, "the server never received a request");
        String body = sent.getBody().readUtf8();
        assertFalse(body.contains("stream_options"), body);
        assertFalse(body.contains("\"stream\""), body);
      }
    }
  }

  private static String longStream(int frames) {
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < frames; i++) {
      body.append("data: {\"choices\":[{\"delta\":{\"content\":\"t").append(i).append("\"}}]}\n\n");
    }
    return body.append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
        .append("data: [DONE]\n\n")
        .toString();
  }

  /**
   * A body still arriving long after the streaming client's call timeout has expired finishes
   * anyway — which is the assumption that {@code callTimeout} on that client rests on, and the
   * reason it is safe to have one.
   *
   * <p>OkHttp's own javadoc says a call timeout "includes ... reading the response body". On 4.12
   * that is not true of an SSE body, measured three ways: a 500ms ceiling left a 3126ms throttled
   * body alone; the same 500ms ceiling cancelled a call whose response headers were delayed 2000ms,
   * with {@code InterruptedIOException("timeout")} thrown from {@code RealCall.timeoutExit}; and a
   * control run with no ceiling and the same 2000ms header delay completed. The ceiling therefore
   * bounds the pre-response phase — where the multi-route connect blowup lives — and leaves
   * generation alone.
   *
   * <p><b>The third of those does not reproduce and the conclusion does not need it.</b>
   * Re-measured on 2026-09-02 with the read and call timeouts set independently, which the original
   * could not do because it moved both at once: with no call ceiling and the read timeout still at
   * 1s, a 2000ms header delay fails at 1003ms with {@code SocketTimeoutException("Read timed
   * out")}. The read timeout bounds the header wait on its own, at the same number, so removing the
   * ceiling does not make a delayed-header call complete — only raising the read timeout does. What
   * does hold, and is what this test rests on, is the second run: read 5s, call 1s, body delayed 2s
   * after the headers, <b>completed at 2013ms</b>. That is {@code timeoutEarlyExit} seen from
   * outside, and it is the whole claim.
   *
   * <p><b>So this test exists to fail on the okhttp upgrade that makes the javadoc true</b>, rather
   * than let a long healthy generation start being truncated in production with nothing to notice.
   * The lower bound on elapsed time is not decoration: without it the body might finish inside the
   * ceiling and the test would be asserting nothing at all — the shape this branch keeps hitting,
   * where the thing forbidden could not have happened yet.
   *
   * <p>The lower bound is safe on a loaded box in the only direction that matters: the server
   * enforces the floor by sleeping between chunks, so load can push the elapsed time up but never
   * below the seven periods the throttle requires. An upper bound here would be the claim about
   * machine speed this file refuses to make elsewhere.
   *
   * <p>The read timeout does not fire, because it measures inactivity and a chunk lands every 100ms
   * inside a 400ms budget. That is the same distinction the ceiling is being checked against, seen
   * from the other side.
   *
   * <p><b>This does not pin that the ceiling exists</b>, and cannot: deleting {@code callTimeout}
   * from the streaming client leaves this green, because a test that a ceiling does not interfere
   * passes most easily when there is no ceiling. Proving it bounds anything needs a hostname
   * resolving to enough unroutable addresses to outlast the connect phase, which is the case {@code
   * callCeiling} is recorded as untestable for on the other two clients, for the same reason.
   *
   * <p><b>The throttle is 512 bytes and not 20, and the difference is not cosmetic.</b> {@code
   * MockResponse.throttleBody} throttles the request <em>reader</em> as well as the response
   * writer, so a chunk small enough to be interesting for the response also starves the transport's
   * own POST body — the server never finishes reading the request, the timeout fires in the
   * pre-response phase, and the test fails while appearing to say something about generation. A
   * first version did exactly that. 512 bytes clears the request in one period and still needs
   * seven for the response.
   */
  @Test
  void a_slow_body_outlives_the_call_timeout() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(sse(longStream(80)).throttleBody(512, 100, TimeUnit.MILLISECONDS));
      server.start();

      PoolProperties props = poolAt(server.url("/v1").toString());
      props.setStreamingTimeout(Duration.ofMillis(400));

      try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
        List<String> tokens = new ArrayList<>();
        long start = System.nanoTime();
        Completion completion =
            transport.stream(
                "qwen3.5-9b",
                ChatMessage.conversation(null, "capital?"),
                Sampling.NONE,
                List.of(),
                tokens::add,
                () -> false);
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertEquals(80, tokens.size(), "the whole generation has to arrive");
        assertEquals("stop", completion.finishReason());
        assertTrue(
            took.compareTo(Duration.ofMillis(600)) >= 0,
            "the body arrived in "
                + took.toMillis()
                + "ms, which is not clearly past"
                + " the 400ms ceiling, so this proves nothing about outliving it");
      }
    }
  }

  /**
   * A stream that says nothing at all before its first chunk still gets the whole inactivity budget
   * to say it in — which is the case the 90-second default failed and the one no test here covered.
   *
   * <p><b>Why this shape and not a slow body.</b> {@code a_slow_body_outlives_the_call_timeout}
   * above throttles a body that has already started, so a chunk lands every 100ms and no inactivity
   * bound is ever near firing. Prefill is the opposite: the node reads the whole prompt before it
   * emits anything, and emits <em>nothing</em> while it does — no keepalive, no partial frame.
   * Measured on 2026-09-02, a novel 30 415-token prompt spent 119 seconds there. Against the 90
   * seconds this property shipped as, that healthy call failed, and it failed on the read timeout
   * rather than on any total: {@code setBodyDelay} past the read timeout was measured raising
   * {@code SocketTimeoutException("Read timed out")}.
   *
   * <p>Scaled down by three orders of magnitude, because what is being pinned is the ordering —
   * silence shorter than the budget survives — and not the production numbers, which no loopback
   * test should pretend to reproduce. The lower bound on elapsed time is the half that stops this
   * passing vacuously against a server that answered at once.
   */
  @Test
  void a_stream_silent_before_its_first_chunk_is_given_the_whole_budget() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(sse(BORDEAUX_STREAM).setBodyDelay(600, TimeUnit.MILLISECONDS));
      server.start();

      PoolProperties props = poolAt(server.url("/v1").toString());
      props.setStreamingTimeout(Duration.ofMillis(1500));

      try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
        List<String> tokens = new ArrayList<>();
        long start = System.nanoTime();
        Completion completion =
            transport.stream(
                "qwen3.5-9b",
                ChatMessage.conversation(null, "capital?"),
                Sampling.NONE,
                List.of(),
                tokens::add,
                () -> false);
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertEquals(List.of("Bor", "deaux"), tokens);
        assertEquals("stop", completion.finishReason());
        assertTrue(
            took.compareTo(Duration.ofMillis(600)) >= 0,
            "the first chunk arrived after "
                + took.toMillis()
                + "ms, which is not"
                + " clearly past the silence this is meant to survive");
      }
    }
  }

  /**
   * Silence longer than the inactivity budget still fails, and fails as inactivity rather than as
   * the total.
   *
   * <p>The mirror of the test above, and the half that keeps raising the budget from turning into
   * removing it. {@code max-stream-duration} is left at its ten-minute default here on purpose: if
   * the inactivity bound had been lost, this would sit for ten minutes and be killed by the class
   * {@code @Timeout} instead, so the elapsed-time assertion is what distinguishes "bounded by
   * inactivity" from "bounded by the total" — and the assertion on the message says the same thing
   * from the other side, because the total reports itself by name and the read timeout does not.
   */
  @Test
  void a_stream_silent_past_the_budget_fails_as_inactivity_and_not_as_the_total() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      // Two seconds and not ten: MockWebServer.close waits on its own
      // dispatcher, and a response still sleeping out a longer delay makes
      // the close throw "Gave up waiting for queue to shut down" and fail
      // the test after its assertions have already passed. Measured. Two
      // seconds is still most of an order of magnitude above the 300ms
      // budget, which is all this needs.
      server.enqueue(sse(BORDEAUX_STREAM).setBodyDelay(2, TimeUnit.SECONDS));
      server.start();

      PoolProperties props = poolAt(server.url("/v1").toString());
      props.setStreamingTimeout(Duration.ofMillis(300));

      try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
        long start = System.nanoTime();
        LlmTransportException failed =
            assertThrows(
                LlmTransportException.class,
                () ->
                    transport.stream(
                        "qwen3.5-9b",
                        ChatMessage.conversation(null, "capital?"),
                        Sampling.NONE,
                        List.of(),
                        token -> {},
                        () -> false));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertTrue(
            took.compareTo(Duration.ofSeconds(5)) < 0,
            "gave up after " + took.toMillis() + "ms, which is not the inactivity" + " budget");
        assertFalse(
            failed.getMessage().contains("max-stream-duration"),
            "the total bound reported a fault that was the inactivity one: " + failed.getMessage());
        assertTrue(failed.getMessage().contains("studio"), failed.getMessage());
      }
    }
  }

  /**
   * An interrupted caller leaves a stream, and leaves the flag set behind it.
   *
   * <p>The streaming counterpart of {@code
   * an_interrupt_during_backoff_stops_the_call_and_stays_interrupted}, and re-setting the flag is
   * the load-bearing half for the same reason: {@code LlmPool.submit} has an {@code
   * InterruptedException} branch that sheds the request and re-interrupts, and it is unreachable if
   * this frame swallows the interrupt. The caller would sit through a whole generation it has
   * already abandoned, on a lane slot nobody is waiting for.
   *
   * <p>The flag is set before the call rather than raced against it from another thread: a blocking
   * take on an already-interrupted thread throws at once, so the branch is reached
   * deterministically and there is no timing in the test.
   */
  @Test
  void an_interrupt_stops_a_stream_and_stays_interrupted() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(sse(BORDEAUX_STREAM));
      server.start();

      try (OpenAiTransport transport = transportAt(server.url("/v1").toString())) {
        Thread.currentThread().interrupt();
        try {
          LlmTransportException failed =
              assertThrows(
                  LlmTransportException.class,
                  () ->
                      transport.stream(
                          "qwen3.5-9b",
                          ChatMessage.conversation(null, "hello"),
                          Sampling.NONE,
                          List.of(),
                          token -> {},
                          () -> false));

          assertTrue(failed.getMessage().contains("interrupted"), failed.getMessage());
          assertTrue(failed.getMessage().contains("studio"), failed.getMessage());
          assertTrue(
              Thread.currentThread().isInterrupted(),
              "the interrupt must survive the transport for LlmPool to act on");
        } finally {
          // Cleared however this test ends: a leaked interrupt flag on
          // a shared JUnit worker thread fails whichever test runs next.
          Thread.interrupted();
        }
      }
    }
  }

  /**
   * A stream is bounded by inactivity between chunks and by nothing else, so this pins which client
   * it uses and that the bound exists at all.
   *
   * <p>{@code streamingTimeout} is set two orders of magnitude below the other two: a stream
   * reaching for {@code chatHttp} would sit here for thirty seconds instead.
   *
   * <p>This bounds a <em>silent</em> endpoint and only that. The sentence that used to end this
   * paragraph — that a total cap would kill a healthy long generation, so the read timeout is the
   * whole of the guarantee — was retracted as false and this was its last surviving copy, on the
   * test whose name most invites reading it as authority. An endpoint that keeps emitting never
   * trips an inactivity bound at all; {@code max-stream-duration} is what bounds that, and {@code
   * a_stream_that_never_stops_is_given_up_on} is where it is pinned.
   */
  @Test
  void a_stream_gives_up_on_the_streaming_timeout_and_not_the_chat_one() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
      server.start();

      PoolProperties props = poolAt(server.url("/v1").toString());
      props.setStreamingTimeout(Duration.ofMillis(250));
      props.setChatTimeout(Duration.ofSeconds(30));
      props.setEmbeddingTimeout(Duration.ofSeconds(30));

      try (OpenAiTransport transport = new OpenAiTransport(props, new ObjectMapper())) {
        long start = System.nanoTime();
        assertThrows(
            LlmTransportException.class,
            () ->
                transport.stream(
                    "qwen3.5-9b",
                    ChatMessage.conversation(null, "hello"),
                    Sampling.NONE,
                    List.of(),
                    token -> {},
                    () -> false));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertTrue(
            took.compareTo(Duration.ofSeconds(15)) < 0,
            "gave up after "
                + took.toMillis()
                + "ms, so it was not the streaming"
                + " timeout that bounded the call");
        assertEquals(1, server.getRequestCount(), "and it was not tried again");
      }
    }
  }
}
