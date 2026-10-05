package io.aeyer.plowshare.server.llm.lmstudio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.llm.PoolProperties;
import io.aeyer.plowshare.server.llm.WebSocketOpener;
import io.aeyer.plowshare.server.llm.dispatch.CallerAbandonedException;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Content;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransport;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransportException;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The websocket transport, against a loopback server that speaks LM Studio's framing back at it.
 *
 * <p><b>Every frame these tests feed the transport was copied from a recorded exchange with the
 * reference node on 2026-09-02</b>, not composed from the published schema. That distinction is the
 * whole value of the fixtures: the running server sends fields its own schema declares impossible —
 * {@code isStructural} on a fragment, {@code rawContent} on a tool call, {@code totalTimeSec} in
 * stats — and a suite built from the schema would have been green against a shape the node never
 * sends.
 *
 * <p>No test binds a fixed port; {@link MockWebServer} takes an ephemeral one and the transport is
 * pointed at whatever it got.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class LmStudioSocketTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** A frame the node really sent, mid-prefill. */
  private static String progress(double fraction) {
    return "{\"type\":\"channelSend\",\"channelId\":1,\"message\":"
        + "{\"type\":\"promptProcessingProgress\",\"progress\":"
        + fraction
        + "}}";
  }

  /** A fragment, with the {@code isStructural} field the node adds and its own schema forbids. */
  private static String fragment(String content, String reasoningType) {
    return "{\"type\":\"channelSend\",\"channelId\":1,\"message\":{\"type\":\"fragment\","
        + "\"fragment\":{\"content\":\""
        + content
        + "\",\"tokensCount\":1,"
        + "\"containsDrafted\":false,\"reasoningType\":\""
        + reasoningType
        + "\","
        + "\"isStructural\":false}}}";
  }

  private static String success(String stats) {
    return "{\"type\":\"channelSend\",\"channelId\":1,\"message\":{\"type\":\"success\","
        + "\"stats\":"
        + stats
        + ",\"modelInfo\":{},\"loadModelConfig\":{},"
        + "\"predictionConfig\":{}}}";
  }

  private static final String CLOSE = "{\"type\":\"channelClose\",\"channelId\":1}";

  /** The stats object the node returned for a tool-calling turn. */
  private static final String TOOL_STATS =
      "{\"stopReason\":\"toolCalls\",\"tokensPerSecond\":30.6,"
          + "\"timeToFirstTokenSec\":1.13,\"totalTimeSec\":3.94,"
          + "\"promptTokensCount\":279,\"predictedTokensCount\":86,"
          + "\"totalTokensCount\":365}";

  /**
   * The tool call end frame the node sent, {@code rawContent} and all.
   *
   * <p><b>The spacing in {@code rawContent} is the point.</b> Measured against the node, {@code
   * rawContent} is the literal concatenation of the {@code
   * toolCallGenerationArgumentFragmentGenerated} fragments — the bytes the model emitted — while
   * {@code arguments} is the node's parse of them. A model that puts a space after the colon
   * produces two spellings of the same object, and {@link ToolCall#arguments()} is specified as the
   * first. An earlier version of this fixture used the same spelling for both, and a transport that
   * re-serialised the parsed object passed it.
   */
  private static final String TOOL_CALL =
      "{\"type\":\"channelSend\",\"channelId\":1,\"message\":"
          + "{\"type\":\"toolCallGenerationEnd\",\"toolCallRequest\":"
          + "{\"id\":\"abc123\",\"type\":\"function\",\"arguments\":{\"city\":\"Paris\"},"
          + "\"name\":\"get_weather\"},"
          + "\"rawContent\":\"{\\\"city\\\": \\\"Paris\\\"}\"}}";

  /**
   * The same call as the node would send it if it ever omitted the raw bytes, which is the only
   * case the parsed object is used for.
   */
  private static final String TOOL_CALL_WITHOUT_RAW =
      "{\"type\":\"channelSend\",\"channelId\":1,\"message\":"
          + "{\"type\":\"toolCallGenerationEnd\",\"toolCallRequest\":"
          + "{\"id\":\"abc123\",\"type\":\"function\",\"arguments\":{\"city\":\"Paris\"},"
          + "\"name\":\"get_weather\"}}}";

  // ---- harness -------------------------------------------------------------

  /**
   * A server that answers the handshake and then plays a script.
   *
   * <p>It replies to the first client frame with an auth result and to the second — the {@code
   * channelCreate} — by sending every scripted frame in order. That is the real sequence, and it is
   * what lets a test assert on what the transport sent as well as what it did with what came back.
   */
  private static final class Node extends WebSocketListener {

    private final boolean authorised;
    private final List<String> script;
    final List<String> received = new CopyOnWriteArrayList<>();

    Node(boolean authorised, List<String> script) {
      this.authorised = authorised;
      this.script = script;
    }

    @Override
    public void onMessage(WebSocket socket, String text) {
      received.add(text);
      if (received.size() == 1) {
        socket.send(
            authorised
                ? "{\"success\":true}"
                : "{\"success\":false,\"error\":\"An LM Studio API token is required\"}");
        return;
      }
      if (received.size() == 2) {
        for (String frame : script) {
          socket.send(frame);
        }
      }
    }

    /**
     * Completes the close handshake the transport starts.
     *
     * <p>Without this the server half of the socket stays open and {@link MockWebServer#close()}
     * spends its whole grace period waiting for a connection that will never drain, which fails the
     * test at the {@code try}-with-resources rather than at an assertion. A real server closes its
     * end; so does this one.
     */
    @Override
    public void onClosing(WebSocket socket, int code, String reason) {
      socket.close(code, null);
    }
  }

  private record Wiring(MockWebServer server, Node node, LmStudioSocket transport, Client client)
      implements AutoCloseable {
    @Override
    public void close() throws IOException {
      client.close();
      server.close();
    }
  }

  private static Wiring wire(Node node, String apiKey) {
    MockWebServer server = new MockWebServer();
    server.enqueue(new MockResponse().withWebSocketUpgrade(node));
    PoolProperties props = new PoolProperties();
    props.setName("studio");
    props.setBaseUrl(server.url("/v1").toString());
    props.setApiKey(apiKey);
    props.setMaxStreamDuration(Duration.ofSeconds(10));
    props.setChatTimeout(Duration.ofSeconds(10));
    Client client = new Client(new OkHttpClient());
    LmStudioSocket transport = new LmStudioSocket(props, MAPPER, new Delegate(), client);
    return new Wiring(server, node, transport, client);
  }

  /**
   * A well-formed token that is not a key.
   *
   * <p>Assembled rather than written for the reason {@code InvariantsTest} assembles its needle:
   * that scan forbids the literal prefix anywhere under {@code src}, and a fixture spelling it out
   * would fail the guard that exists to stop a real one being committed.
   */
  private static final String TOKEN = "sk" + "-lm-" + "ABCD1234:EFGH5678IJKL9012MNOP";

  private static Wiring wire(List<String> script) {
    return wire(new Node(true, script), TOKEN);
  }

  /**
   * The pool's client, which a test is allowed to hold.
   *
   * <p>{@code InvariantsTest} restricts an {@code OkHttpClient} to named files in {@code main} and
   * deliberately excludes tests, "a test that drives the real client against a real server
   * legitimately holds one". This is that case: the production transport is handed a connection by
   * the pool and never opens one, so a test has to supply what the pool would.
   */
  private record Client(OkHttpClient http) implements WebSocketOpener, AutoCloseable {
    @Override
    public WebSocket openWebSocket(HttpUrl url, WebSocketListener listener) {
      return http.newWebSocket(new Request.Builder().url(url).build(), listener);
    }

    @Override
    public void close() {
      http.dispatcher().executorService().shutdown();
      http.connectionPool().evictAll();
    }
  }

  /**
   * Records that it was called, so a delegation test can prove the {@code /v1} path is the one that
   * ran.
   */
  private static final class Delegate implements LlmTransport {
    boolean streamed;
    boolean completed;
    boolean closed;

    @Override
    public String poolName() {
      return "studio";
    }

    @Override
    public Completion complete(
        String wireModel, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      completed = true;
      return new Completion("delegated", "stop", TokenUsage.UNKNOWN, List.of());
    }

    @Override
    public Completion stream(
        String wireModel,
        List<ChatMessage> messages,
        Sampling sampling,
        List<ToolSchema> tools,
        Deltas sink,
        BooleanSupplier abandoned) {
      streamed = true;
      sink.answered("delegated");
      return new Completion("delegated", "stop", TokenUsage.UNKNOWN, List.of());
    }

    @Override
    public Embeddings embed(String wireModel, List<String> input) {
      return new Embeddings(List.of(), TokenUsage.UNKNOWN);
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  private static final BooleanSupplier PRESENT = () -> false;

  private static List<ChatMessage> hello() {
    return List.of(ChatMessage.user("hello"));
  }

  private static JsonNode parse(String text) {
    try {
      return MAPPER.readTree(text);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * A picture is refused on this path rather than dropped from it.
   *
   * <h2>Why a refusal is the right answer to an unverified spelling</h2>
   *
   * <p>LM Studio's websocket message shape takes an array of parts and very likely has a spelling
   * for an image among them. Nothing here has confirmed it against a running node — which is the
   * same bar {@link LmStudioSocket#carries()} holds {@code top_p} and {@code max_tokens} to, and it
   * declares two parameters rather than four for exactly this reason.
   *
   * <p>The alternative is worse than not carrying it: a transport that sent the words and left the
   * picture out would have the model answer "I cannot see an image" about a request that looked
   * correct from every side, and nothing anywhere would say the image had been dropped. That is the
   * invisible-fact failure the whole {@code carries()} contract exists to prevent, reached through
   * a different door.
   *
   * <p>It is reachable only on a pool configured for prefill progress: {@code LmStudio} delegates
   * the blocking and plain-streaming paths to the {@code /v1} transport, which does carry images.
   */
  @Test
  void a_picture_is_refused_on_the_socket_rather_than_sent_without_it() throws Exception {
    try (Wiring wiring = wire(List.of(CLOSE))) {
      LlmTransportException refused =
          assertThrows(
              LlmTransportException.class,
              () ->
                  wiring.transport().stream(
                      "gemma-4-e4b",
                      List.of(
                          ChatMessage.user(
                              "what is this",
                              List.of(
                                  new Content.Image(
                                      "img_" + "a".repeat(32), "data:image/png;base64,iVBORw0K")))),
                      Sampling.NONE,
                      List.of(),
                      token -> {},
                      PRESENT,
                      progress -> {}));

      assertTrue(
          refused.getMessage().contains("prefill"),
          "and it says which setting put the call on this path: " + refused.getMessage());
    }
  }

  // ---- progress ------------------------------------------------------------

  @Test
  void prefill_progress_reaches_the_callback_in_the_order_the_node_sent_it() throws Exception {
    try (Wiring wiring =
        wire(
            List.of(
                progress(0.0),
                progress(0.067335),
                progress(0.942693),
                progress(1.0),
                fragment("hi", "none"),
                success("{\"stopReason\":\"eosFound\",\"promptTokensCount\":7}"),
                CLOSE))) {
      List<Double> seen = new ArrayList<>();
      Completion completion =
          wiring.transport().stream(
              "qwen3.5-9b",
              hello(),
              Sampling.NONE.withTemperature(0.2),
              List.of(),
              token -> {},
              PRESENT,
              seen::add);

      assertEquals(List.of(0.0, 0.067335, 0.942693, 1.0), seen);
      assertEquals("hi", completion.content());
    }
  }

  @Test
  void a_cached_prefix_reports_only_the_two_endpoints_and_that_is_not_a_failure() throws Exception {
    // The shape measured against the node when it still had the prefix
    // cached: 53 970 prompt tokens "processed" in 1.19 seconds, with
    // nothing between 0.0 and 1.0. A consumer waiting for intermediate
    // frames sees none, and the call still succeeds.
    try (Wiring wiring =
        wire(
            List.of(
                progress(0.0),
                progress(1.0),
                fragment("ok", "none"),
                success("{\"stopReason\":\"eosFound\",\"promptTokensCount\":53970}"),
                CLOSE))) {
      List<Double> seen = new ArrayList<>();
      Completion completion =
          wiring.transport().stream(
              "qwen3.5-9b",
              hello(),
              Sampling.NONE.withTemperature(0.2),
              List.of(),
              token -> {},
              PRESENT,
              seen::add);

      assertEquals(List.of(0.0, 1.0), seen);
      assertEquals(53970, completion.usage().promptTokens());
    }
  }

  // ---- reasoning -----------------------------------------------------------

  @Test
  void reasoning_fragments_reach_neither_the_sink_nor_the_content() throws Exception {
    // The measured shape of a thinking turn: the node marks its working
    // with a reasoningType, and only "none" is the answer.
    try (Wiring wiring =
        wire(
            List.of(
                fragment("The", "reasoning"),
                fragment(" user asks", "reasoning"),
                fragment("", "reasoningEndTag"),
                fragment("Bonjour", "none"),
                success("{\"stopReason\":\"eosFound\"}"),
                CLOSE))) {
      List<String> tokens = new ArrayList<>();
      Completion completion =
          wiring.transport().stream(
              "qwen3.5-9b",
              hello(),
              Sampling.NONE.withTemperature(0.2),
              List.of(),
              tokens::add,
              PRESENT,
              fraction -> {});

      assertEquals(List.of("Bonjour"), tokens);
      assertEquals("Bonjour", completion.content());
    }
  }

  // ---- tools ---------------------------------------------------------------

  @Test
  void a_tool_call_keeps_the_bytes_the_model_emitted() throws Exception {
    try (Wiring wiring = wire(List.of(TOOL_CALL, success(TOOL_STATS), CLOSE))) {
      Completion completion =
          wiring.transport().stream(
              "qwen3.5-9b",
              hello(),
              Sampling.NONE.withTemperature(0.2),
              List.of(),
              token -> {},
              PRESENT,
              fraction -> {});

      assertEquals(1, completion.toolCalls().size());
      ToolCall call = completion.toolCalls().get(0);
      assertEquals("abc123", call.id());
      assertEquals("get_weather", call.name());
      // rawContent, not a re-serialisation of the parsed arguments:
      // the model's own spacing survives.
      assertEquals("{\"city\": \"Paris\"}", call.arguments());
      assertEquals("tool_calls", completion.finishReason());
    }
  }

  @Test
  void a_tool_call_with_no_raw_bytes_falls_back_to_the_parsed_arguments() throws Exception {
    try (Wiring wiring = wire(List.of(TOOL_CALL_WITHOUT_RAW, success(TOOL_STATS), CLOSE))) {
      Completion completion =
          wiring.transport().stream(
              "qwen3.5-9b",
              hello(),
              Sampling.NONE.withTemperature(0.2),
              List.of(),
              token -> {},
              PRESENT,
              fraction -> {});

      assertEquals("{\"city\":\"Paris\"}", completion.toolCalls().get(0).arguments());
    }
  }

  @Test
  void offering_tools_sets_the_prediction_tools_key_with_the_schema() throws Exception {
    try (Wiring wiring = wire(List.of(success("{\"stopReason\":\"eosFound\"}"), CLOSE))) {
      ToolSchema tool = ToolSchema.from("get_weather", "Get weather", Map.of("type", "object"));
      wiring.transport().stream(
          "qwen3.5-9b",
          hello(),
          Sampling.NONE.withTemperature(0.2),
          List.of(tool),
          token -> {},
          PRESENT,
          fraction -> {});

      JsonNode create = parse(wiring.node().received.get(1));
      JsonNode fields =
          create
              .path("creationParameter")
              .path("predictionConfigStack")
              .path("layers")
              .get(0)
              .path("config")
              .path("fields");
      JsonNode tools = null;
      for (JsonNode field : fields) {
        if ("llm.prediction.tools".equals(field.path("key").asText())) {
          tools = field.path("value");
        }
      }
      assertNotNull(tools, "the tools key must be set when tools are offered");
      assertEquals("toolArray", tools.path("type").asText());
      assertEquals(
          "get_weather", tools.path("tools").get(0).path("function").path("name").asText());
    }
  }

  /**
   * The prediction layer carries a temperature and a {@code topKSampling}, and nothing else —
   * matching exactly what this transport declares.
   *
   * <h2>The narrow claim is the point</h2>
   *
   * <p>These two keys are the ones this project has confirmed against LM Studio's own websocket
   * namespace. Siblings for nucleus truncation and a token ceiling very likely exist; nobody here
   * has confirmed their spellings, and a key guessed by analogy is a setting nobody could tell had
   * been ignored — the endpoint accepts the frame either way.
   *
   * <p>So the request below states all five parameters and exactly two reach the frame. That is not
   * a bug being pinned: it is {@code carries()} being true, and {@code LlmDispatcher} names the
   * three it removed in a warning before this transport ever sees them.
   */
  @Test
  void the_prediction_layer_carries_a_temperature_and_a_top_k_and_nothing_else() throws Exception {
    try (Wiring wiring = wire(List.of(success("{\"stopReason\":\"eosFound\"}"), CLOSE))) {
      wiring.transport().stream(
          "qwen3.5-9b",
          hello(),
          Sampling.NONE
              .withTemperature(0.6d)
              .withTopK(20)
              .withTopP(0.95d)
              .withMaxTokens(4096)
              .withReasoningEffort(Sampling.Effort.HIGH),
          List.of(),
          token -> {},
          PRESENT,
          fraction -> {});

      JsonNode fields =
          parse(wiring.node().received.get(1))
              .path("creationParameter")
              .path("predictionConfigStack")
              .path("layers")
              .get(0)
              .path("config")
              .path("fields");
      Map<String, JsonNode> byKey = new java.util.LinkedHashMap<>();
      for (JsonNode field : fields) {
        byKey.put(field.path("key").asText(), field.path("value"));
      }
      assertEquals(
          java.util.Set.of("llm.prediction.temperature", "llm.prediction.topKSampling"),
          byKey.keySet(),
          "the frame must carry exactly what carries() claims; a key more is a"
              + " setting nobody verified and a key fewer is one silently lost");
      assertEquals(0.6d, byKey.get("llm.prediction.temperature").asDouble());
      assertEquals(20, byKey.get("llm.prediction.topKSampling").asInt());
    }
  }

  /**
   * A call that states no sampling sets no prediction field at all.
   *
   * <p>The websocket half of the change {@code OpenAiTransportTest} pins for {@code /v1}. This
   * layer is {@code apiOverride}, which beats LM Studio's model defaults, its {@code model.yaml}
   * and its load-time layer — so a temperature written here when nobody asked for one is precisely
   * the override that took a measured ingest down. Absent means absent, and the three layers
   * underneath get to answer.
   */
  @Test
  void a_call_that_states_no_sampling_writes_no_prediction_field() throws Exception {
    try (Wiring wiring = wire(List.of(success("{\"stopReason\":\"eosFound\"}"), CLOSE))) {
      wiring.transport().stream(
          "qwen3.5-9b", hello(), Sampling.NONE, List.of(), token -> {}, PRESENT, fraction -> {});

      JsonNode fields =
          parse(wiring.node().received.get(1))
              .path("creationParameter")
              .path("predictionConfigStack")
              .path("layers")
              .get(0)
              .path("config")
              .path("fields");
      assertEquals(
          0,
          fields.size(),
          "a request that states nothing must override nothing: the model's own"
              + " model.yaml carries its vendor's recommended values, and this"
              + " layer is the one that would overwrite them");
    }
  }

  @Test
  void a_prose_call_sets_no_tools_key_at_all() throws Exception {
    // The rule LlmTransport states for /v1, kept here: an empty toolArray
    // is not the same thing as no tools.
    try (Wiring wiring = wire(List.of(success("{\"stopReason\":\"eosFound\"}"), CLOSE))) {
      wiring.transport().stream(
          "qwen3.5-9b",
          hello(),
          Sampling.NONE.withTemperature(0.2),
          List.of(),
          token -> {},
          PRESENT,
          fraction -> {});

      JsonNode fields =
          parse(wiring.node().received.get(1))
              .path("creationParameter")
              .path("predictionConfigStack")
              .path("layers")
              .get(0)
              .path("config")
              .path("fields");
      for (JsonNode field : fields) {
        assertFalse(
            "llm.prediction.tools".equals(field.path("key").asText()),
            "a prose call must not declare tools");
      }
    }
  }

  @Test
  void a_tool_result_is_carried_as_a_tool_call_result_part_with_its_id() throws Exception {
    try (Wiring wiring = wire(List.of(success("{\"stopReason\":\"eosFound\"}"), CLOSE))) {
      List<ChatMessage> conversation =
          List.of(
              ChatMessage.user("weather?"),
              ChatMessage.assistant(
                  null, List.of(new ToolCall("abc123", "get_weather", "{\"city\":\"Paris\"}"))),
              ChatMessage.tool("abc123", "17C"));

      wiring.transport().stream(
          "qwen3.5-9b",
          conversation,
          Sampling.NONE.withTemperature(0.2),
          List.of(),
          token -> {},
          PRESENT,
          fraction -> {});

      JsonNode messages =
          parse(wiring.node().received.get(1))
              .path("creationParameter")
              .path("history")
              .path("messages");
      JsonNode result = messages.get(2);
      assertEquals("tool", result.path("role").asText());
      JsonNode part = result.path("content").get(0);
      assertEquals("toolCallResult", part.path("type").asText());
      assertEquals("abc123", part.path("toolCallId").asText());
      assertEquals("17C", part.path("content").asText());

      JsonNode asked = messages.get(1).path("content").get(0);
      assertEquals("toolCallRequest", asked.path("type").asText());
      assertEquals("get_weather", asked.path("toolCallRequest").path("name").asText());
    }
  }

  // ---- usage ---------------------------------------------------------------

  @Test
  void an_absent_prompt_count_stays_unknown_rather_than_becoming_a_zero() throws Exception {
    // Compaction folds from promptTokens; a zero would be a fold that never
    // happens, silently.
    try (Wiring wiring = wire(List.of(success("{\"stopReason\":\"eosFound\"}"), CLOSE))) {
      Completion completion =
          wiring.transport().stream(
              "qwen3.5-9b",
              hello(),
              Sampling.NONE.withTemperature(0.2),
              List.of(),
              token -> {},
              PRESENT,
              fraction -> {});

      assertNull(completion.usage().promptTokens());
    }
  }

  @Test
  void the_stats_object_becomes_the_usage_compaction_reads() throws Exception {
    try (Wiring wiring = wire(List.of(success(TOOL_STATS), CLOSE))) {
      Completion completion =
          wiring.transport().stream(
              "qwen3.5-9b",
              hello(),
              Sampling.NONE.withTemperature(0.2),
              List.of(),
              token -> {},
              PRESENT,
              fraction -> {});

      assertEquals(279, completion.usage().promptTokens());
      assertEquals(86, completion.usage().completionTokens());
      assertEquals(365, completion.usage().totalTokens());
      // This namespace has no reasoning breakdown, so it is honestly
      // unavailable rather than zero.
      assertNull(completion.usage().reasoningTokens());
    }
  }

  @Test
  void a_prediction_with_no_success_frame_reports_no_cost() throws Exception {
    try (Wiring wiring = wire(List.of(fragment("hi", "none"), CLOSE))) {
      Completion completion =
          wiring.transport().stream(
              "qwen3.5-9b",
              hello(),
              Sampling.NONE.withTemperature(0.2),
              List.of(),
              token -> {},
              PRESENT,
              fraction -> {});

      assertEquals(TokenUsage.UNKNOWN, completion.usage());
      assertNull(completion.finishReason());
    }
  }

  // ---- stop reasons --------------------------------------------------------

  @Test
  void a_capped_generation_finishes_for_the_reason_the_openai_path_would_give() throws Exception {
    try (Wiring wiring =
        wire(List.of(success("{\"stopReason\":\"maxPredictedTokensReached\"}"), CLOSE))) {
      Completion completion =
          wiring.transport().stream(
              "qwen3.5-9b",
              hello(),
              Sampling.NONE.withTemperature(0.2),
              List.of(),
              token -> {},
              PRESENT,
              fraction -> {});

      assertEquals("length", completion.finishReason());
    }
  }

  // ---- failure paths -------------------------------------------------------

  @Test
  void a_refused_handshake_fails_the_call_without_quoting_the_credential() throws Exception {
    String key = "sk" + "-lm-" + "SECRETID:SUPERSECRETPASSKEY1";
    try (Wiring wiring = wire(new Node(false, List.of()), key)) {
      LlmTransportException refused =
          assertThrows(
              LlmTransportException.class,
              () ->
                  wiring.transport().stream(
                      "qwen3.5-9b",
                      hello(),
                      Sampling.NONE.withTemperature(0.2),
                      List.of(),
                      token -> {},
                      PRESENT,
                      fraction -> {}));

      assertTrue(
          refused.getMessage().contains("studio"), "the message must name the pool that refused");
      assertFalse(refused.getMessage().contains("SECRETID"));
      assertFalse(refused.getMessage().contains("SUPERSECRETPASSKEY1"));
      assertFalse(refused.getMessage().contains(key));
    }
  }

  @Test
  void a_channel_error_fails_the_call() throws Exception {
    try (Wiring wiring =
        wire(
            List.of(
                "{\"type\":\"channelError\",\"channelId\":1,"
                    + "\"error\":{\"title\":\"Model not found\"}}"))) {
      LlmTransportException failed =
          assertThrows(
              LlmTransportException.class,
              () ->
                  wiring.transport().stream(
                      "qwen3.5-9b",
                      hello(),
                      Sampling.NONE.withTemperature(0.2),
                      List.of(),
                      token -> {},
                      PRESENT,
                      fraction -> {}));

      assertTrue(failed.getMessage().contains("Model not found"));
    }
  }

  @Test
  void an_abandoned_caller_stops_the_stream_and_never_returns_a_partial_answer() throws Exception {
    try (Wiring wiring =
        wire(
            List.of(
                fragment("half ", "none"),
                fragment("an answer", "none"),
                success("{\"stopReason\":\"eosFound\"}"),
                CLOSE))) {
      assertThrows(
          CallerAbandonedException.class,
          () ->
              wiring.transport().stream(
                  "qwen3.5-9b",
                  hello(),
                  Sampling.NONE.withTemperature(0.2),
                  List.of(),
                  token -> {},
                  () -> true,
                  fraction -> {}));
    }
  }

  // ---- the /v1 path is untouched -------------------------------------------

  @Test
  void the_streaming_call_without_a_progress_callback_goes_to_the_openai_path() throws Exception {
    try (Wiring wiring = wire(List.of())) {
      Delegate delegate = new Delegate();
      PoolProperties props = new PoolProperties();
      props.setName("studio");
      props.setBaseUrl(wiring.server().url("/v1").toString());
      LmStudioSocket transport = new LmStudioSocket(props, MAPPER, delegate, wiring.client());

      List<String> tokens = new ArrayList<>();
      Completion completion =
          transport.stream(
              "qwen3.5-9b",
              hello(),
              Sampling.NONE.withTemperature(0.2),
              List.of(),
              tokens::add,
              PRESENT);

      assertTrue(delegate.streamed, "the six-argument stream must delegate to /v1");
      assertEquals("delegated", completion.content());
      assertEquals(List.of("delegated"), tokens);
      // Nothing was asked of the websocket at all.
      assertTrue(wiring.node().received.isEmpty());
    }
  }

  @Test
  void completing_and_embedding_are_delegated_untouched() throws Exception {
    try (Wiring wiring = wire(List.of())) {
      Delegate delegate = new Delegate();
      PoolProperties props = new PoolProperties();
      props.setName("studio");
      props.setBaseUrl(wiring.server().url("/v1").toString());
      LmStudioSocket transport = new LmStudioSocket(props, MAPPER, delegate, wiring.client());

      assertEquals(
          "delegated",
          transport
              .complete("qwen3.5-9b", hello(), Sampling.NONE.withTemperature(0.2), List.of())
              .content());
      assertTrue(delegate.completed);
      assertNotNull(transport.embed("nomic-embed-text", List.of("x")));
      assertTrue(wiring.node().received.isEmpty());
    }
  }

  @Test
  void closing_closes_the_delegate() throws Exception {
    try (Wiring wiring = wire(List.of())) {
      Delegate delegate = new Delegate();
      PoolProperties props = new PoolProperties();
      props.setName("studio");
      props.setBaseUrl(wiring.server().url("/v1").toString());
      new LmStudioSocket(props, MAPPER, delegate, wiring.client()).close();
      assertTrue(delegate.closed);
    }
  }

  // ---- the handshake -------------------------------------------------------

  @Test
  void the_api_token_is_split_into_the_two_fields_the_handshake_wants() throws Exception {
    try (Wiring wiring = wire(List.of(success("{\"stopReason\":\"eosFound\"}"), CLOSE))) {
      wiring.transport().stream(
          "qwen3.5-9b",
          hello(),
          Sampling.NONE.withTemperature(0.2),
          List.of(),
          token -> {},
          PRESENT,
          fraction -> {});

      JsonNode auth = parse(wiring.node().received.get(0));
      assertEquals(1, auth.path("authVersion").asInt());
      assertEquals("ABCD1234", auth.path("clientIdentifier").asText());
      assertEquals("EFGH5678IJKL9012MNOP", auth.path("clientPasskey").asText());
    }
  }

  @Test
  void a_pool_with_no_token_still_offers_an_identity_rather_than_refusing_locally()
      throws Exception {
    try (Wiring wiring =
        wire(new Node(true, List.of(success("{\"stopReason\":\"eosFound\"}"), CLOSE)), "")) {
      wiring.transport().stream(
          "qwen3.5-9b",
          hello(),
          Sampling.NONE.withTemperature(0.2),
          List.of(),
          token -> {},
          PRESENT,
          fraction -> {});

      JsonNode auth = parse(wiring.node().received.get(0));
      assertTrue(auth.path("clientIdentifier").asText().startsWith("guest:"));
      assertFalse(auth.path("clientPasskey").asText().isEmpty());
    }
  }

  @Test
  void the_channel_is_created_against_the_predict_endpoint_for_the_named_model() throws Exception {
    try (Wiring wiring = wire(List.of(success("{\"stopReason\":\"eosFound\"}"), CLOSE))) {
      wiring.transport().stream(
          "qwen3.5-9b",
          hello(),
          Sampling.NONE.withTemperature(0.2),
          List.of(),
          token -> {},
          PRESENT,
          fraction -> {});

      JsonNode create = parse(wiring.node().received.get(1));
      assertEquals("channelCreate", create.path("type").asText());
      assertEquals("predict", create.path("endpoint").asText());
      assertEquals(
          "qwen3.5-9b",
          create
              .path("creationParameter")
              .path("modelSpecifier")
              .path("query")
              .path("identifier")
              .asText());
      assertEquals(
          "hello",
          create
              .path("creationParameter")
              .path("history")
              .path("messages")
              .get(0)
              .path("content")
              .get(0)
              .path("text")
              .asText());
    }
  }
}
