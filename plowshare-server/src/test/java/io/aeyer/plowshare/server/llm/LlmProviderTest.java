package io.aeyer.plowshare.server.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmPool;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransportException;
import io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.lmstudio.LmStudio;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The provider decorator: what a vendor's own API can answer that {@code /v1} cannot, and what
 * happens when it cannot be asked.
 *
 * <p><b>No test here reaches a real model endpoint or a real remote machine.</b> Every socket is a
 * {@link MockWebServer} on a loopback port it chose for itself; nothing binds a fixed port and
 * nothing names the reference box.
 *
 * <p>The fixture below is the shape measured against the reference box on 2026-08-31, re-measured
 * rather than carried over from the plan, and it is the fixture because the two fields disagree
 * there: {@code max_context_length} is 262144 and {@code loaded_context_length} is 128000. A test
 * whose fixture set them equal would pass against an implementation that read either one, which is
 * the whole hazard this facility exists to avoid — a prompt sized for what the model can do and
 * refused by the server running it.
 */
class LlmProviderTest {

  /**
   * The pool's key in every test that has one.
   *
   * <p>Deliberately not a plausible key and deliberately not the prefix a real LM Studio key starts
   * with: a test that greps for something real is a test that put something real in a file, and
   * {@code InvariantsTest} scans this tree for exactly that prefix.
   */
  @Test
  void an_lm_studio_model_is_capped_by_its_pools_working_context_maximum() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(routing(json(MODELS_BODY)));
      server.start();
      PoolProperties props = poolAt(server);
      props.setMaxContextLengths(Map.of("qwen3.5-9b", 32768));
      try (LlmDispatcher dispatcher =
          dispatcherOver(pool("studio", props, lmStudioAt(server, props)))) {
        assertEquals(OptionalInt.of(32768), dispatcher.contextLength("qwen3.5-9b", 64000));
        props.setMaxContextLengths(Map.of("qwen3.5-9b", 262144));
        assertEquals(
            OptionalInt.of(128000),
            dispatcher.contextLength("qwen3.5-9b", 64000),
            "a configured maximum cannot increase the discovered loaded window");
        props.setContextLengths(Map.of("qwen3.5-9b", 262144));
        assertEquals(
            OptionalInt.of(128000),
            dispatcher.contextLength("qwen3.5-9b", 64000),
            "with a maximum configured, a stale declared capacity cannot raise the loaded window either");
      }
    }
  }

  private static final String KEY = "test-only-not-a-real-key";

  /**
   * What {@code GET /api/v0/models} answered on the reference box, trimmed to the fields this
   * facility reads plus the one it must not.
   *
   * <p>Measured 2026-08-31. Both models report {@code state: "loaded"}; the chat model is capable
   * of 262144 and loaded at 128000, and the embedding model's two figures agree at 2048 — which is
   * the pre-existing finding the design document records and this slice does not fix.
   */
  private static final String MODELS_BODY =
      """
            {"data":[
              {"id":"nomic-embed-text","object":"model","type":"embeddings",
               "state":"loaded","max_context_length":2048,
               "loaded_context_length":2048},
              {"id":"qwen3.5-9b","object":"model","type":"vlm","state":"loaded",
               "max_context_length":262144,"loaded_context_length":128000,
               "capabilities":["tool_use"]}
            ],"object":"list"}""";

  private static final String COMPLETION_BODY =
      """
            {"choices":[{"message":{"content":"an answer"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":11,"completion_tokens":2,"total_tokens":13}}""";

  private static final String EMBEDDING_BODY =
      "{\"data\":[{\"embedding\":[0.5,0.25]}],\"usage\":{\"prompt_tokens\":3}}";

  private static MockResponse json(String body) {
    return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
  }

  /**
   * A pool pointed at {@code server}, with the vendor metadata endpoint and the two {@code /v1}
   * endpoints all answered from it.
   *
   * <p>The base URL is {@code /v1} on the same host, which is what makes the decorator's URL
   * arithmetic visible: {@code /api/v0/models} is a sibling of {@code /v1} rather than a path under
   * it, so a provider that appended the path to the base URL would ask for {@code
   * /v1/api/v0/models} and this dispatcher would answer 404.
   */
  private static PoolProperties poolAt(MockWebServer server) {
    PoolProperties pool = new PoolProperties();
    pool.setName("studio");
    pool.setBaseUrl(server.url("/v1").toString());
    pool.setModels(List.of("nomic-embed-text", "qwen3.5-9b"));
    // Milliseconds, so the one test that lets the probe fail on a dropped
    // socket does not spend the pool's real half-second backoff doing it.
    pool.setRetryInitialBackoff(Duration.ofMillis(1));
    return pool;
  }

  /**
   * Routes by path, so one server answers the metadata probe and the two {@code /v1} calls without
   * any test having to order its responses.
   */
  private static Dispatcher routing(MockResponse models) {
    return new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        String path = request.getPath() == null ? "" : request.getPath();
        if (path.startsWith("/api/v0/models")) {
          return models;
        }
        if (path.endsWith("/chat/completions")) {
          return json(COMPLETION_BODY);
        }
        if (path.endsWith("/embeddings")) {
          return json(EMBEDDING_BODY);
        }
        return new MockResponse().setResponseCode(404).setBody("no such endpoint");
      }
    };
  }

  private static LmStudio lmStudioAt(MockWebServer server, PoolProperties pool) {
    return new LmStudio(new OpenAiCompatible(pool, new ObjectMapper()));
  }

  /**
   * A pool that has not opted in never reaches for a websocket, even when a caller asks for
   * progress.
   *
   * <p><b>This is the assertion that the {@code /v1} path stays the default.</b> The
   * progress-carrying overload exists so a console can show a prefill bar, and the failure it must
   * not have is silently moving every LM Studio pool onto a second protocol. The fixture server
   * here answers {@code /v1} and would refuse a websocket upgrade, so a provider that tried one
   * would fail rather than pass quietly.
   */
  @Test
  @Timeout(30)
  void a_pool_that_did_not_ask_for_prefill_progress_still_streams_over_v1() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      // A streaming call needs an SSE reply; the shared fixture answers
      // chat with a whole JSON body, which a stream refuses by design.
      server.setDispatcher(
          new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
              return new MockResponse()
                  .setHeader("Content-Type", "text/event-stream")
                  .setBody(
                      "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\ndata: [DONE]\n\n");
            }
          });
      PoolProperties pool = poolAt(server);
      assertFalse(pool.isPrefillProgress(), "the switch must default to off");

      try (LmStudio provider = lmStudioAt(server, pool)) {
        List<Double> progress = new ArrayList<>();
        Completion completion =
            provider.stream(
                "qwen3.5-9b",
                List.of(ChatMessage.user("hello")),
                Sampling.NONE,
                List.of(),
                token -> {},
                () -> false,
                progress::add);

        assertEquals("hi", completion.content());
        assertTrue(
            progress.isEmpty(), "a /v1 pool has no prefill figure to report and must invent none");
      }
    }
  }

  /**
   * The whole point of the decorator: a vendor pool learns the length its model is <em>loaded</em>
   * at.
   *
   * <p>The assertion names the other number on purpose. 262144 is what this fixture reports as
   * {@code max_context_length}, and it is the plausible wrong answer — the model is capable of it
   * and the server running the model is not. A prompt built to 262144 here is valid for qwen3.5-9b
   * and refused by LM Studio, which reads as a model problem and is a configuration one.
   */
  @Test
  @Timeout(30)
  void an_lmstudio_pool_learns_the_context_length_its_model_is_loaded_at() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(routing(json(MODELS_BODY)));
      PoolProperties pool = poolAt(server);
      try (LmStudio provider = lmStudioAt(server, pool)) {
        assertTrue(
            provider.canDiscover(), "an lmstudio provider is the one that has somewhere to look");
        assertEquals(
            OptionalInt.of(128000),
            provider.contextLength("qwen3.5-9b"),
            "the provider must read loaded_context_length and not"
                + " max_context_length, which this fixture reports as 262144");
        assertEquals(
            OptionalInt.of(2048),
            provider.contextLength("nomic-embed-text"),
            "the embedding model is discovered by the same probe");
      }
    }
  }

  /**
   * A model the vendor endpoint lists without a loaded length is not discovered, and specifically
   * does not fall back to the capable one.
   *
   * <p>This is the mutant the test above cannot kill on its own. An implementation that read {@code
   * loaded_context_length} and fell back to {@code max_context_length} when it was absent passes
   * every other test here, and would report 262144 for a model that is listed but not loaded.
   */
  @Test
  @Timeout(30)
  void a_model_listed_without_a_loaded_length_is_not_discovered() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(
          routing(
              json(
                  """
                    {"data":[{"id":"qwen3.5-9b","object":"model","state":"not-loaded",
                              "max_context_length":262144}],"object":"list"}""")));
      try (LmStudio provider = lmStudioAt(server, poolAt(server))) {
        assertEquals(
            OptionalInt.empty(),
            provider.contextLength("qwen3.5-9b"),
            "a model with no loaded_context_length has no answer here; falling back"
                + " to max_context_length would report 262144 for a model that is"
                + " not loaded at all");
      }
    }
  }

  /**
   * The base provider speaks {@code /v1} and {@code /v1} carries no context length, so it can only
   * repeat what it was told.
   */
  @Test
  @Timeout(30)
  void an_openai_pool_cannot_discover_a_context_length() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(routing(json(MODELS_BODY)));
      PoolProperties pool = poolAt(server);
      try (OpenAiCompatible provider = new OpenAiCompatible(pool, new ObjectMapper())) {
        assertFalse(
            provider.canDiscover(),
            "the /v1 contract has no context field, so this provider has nowhere"
                + " to look and must say so rather than probing a vendor path");
        assertEquals(OptionalInt.empty(), provider.contextLength("qwen3.5-9b"));
        assertEquals(
            0,
            server.getRequestCount(),
            "an openai provider that cannot discover must not have gone looking");
      }
    }
  }

  /**
   * Precedence: an operator's number wins, and the vendor endpoint is not even asked.
   *
   * <p>Both numbers are written out and neither is derived from the other, so this cannot pass by
   * the two happening to agree. 64000 is not 128000, which the fixture would discover, and is not
   * 262144, which reading the wrong field would produce.
   *
   * <p>The request count is the second half and the stronger one: an implementation that probed
   * first and then let the configured value win would return the same number, and would pay for a
   * network call on every pool whose length is already known.
   */
  @Test
  @Timeout(30)
  void a_configured_context_length_wins_over_a_discovered_one() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(routing(json(MODELS_BODY)));
      PoolProperties pool = poolAt(server);
      pool.setContextLengths(Map.of("qwen3.5-9b", 64000));
      try (LmStudio provider = lmStudioAt(server, pool)) {
        assertEquals(
            OptionalInt.of(64000),
            provider.contextLength("qwen3.5-9b"),
            "an operator overriding a discovered number is doing it on purpose");
        assertEquals(
            0,
            server.getRequestCount(),
            "nothing configured is left for discovery to fill, so no probe was owed");
        // The filter needs something to filter out: the other model has
        // no configured length, so discovery still runs for it.
        assertEquals(
            OptionalInt.of(2048),
            provider.contextLength("nomic-embed-text"),
            "discovery fills the gap where nothing is configured");
        assertEquals(
            1, server.getRequestCount(), "and it costs exactly one probe, cached for the pool");
      }
    }
  }

  /**
   * A probe that fails is not an outage. The pool keeps serving; the length is simply not known.
   */
  @Test
  @Timeout(30)
  void a_probe_that_fails_leaves_the_pool_usable() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(
          routing(new MockResponse().setResponseCode(500).setBody("the vendor path is not here")));
      try (LmStudio provider = lmStudioAt(server, poolAt(server))) {
        assertEquals(
            OptionalInt.empty(),
            provider.contextLength("qwen3.5-9b"),
            "a failed probe leaves the length unknown rather than guessed");

        Completion answer =
            provider.complete(
                "qwen3.5-9b",
                ChatMessage.conversation("", "hello"),
                Sampling.NONE.withTemperature(0.2),
                List.of());
        assertEquals(
            "an answer",
            answer.content(),
            "the chat call is delegated untouched and does not care that the"
                + " vendor probe failed");
        Embeddings vectors = provider.embed("nomic-embed-text", List.of("some text"));
        assertEquals(1, vectors.vectors().size(), "and so is the embedding call");
      }
    }
  }

  /**
   * The probe carries the pool's Bearer token, because the endpoint it calls requires one.
   *
   * <p>Measured against the reference box on 2026-08-31: {@code GET /api/v0/models} with no {@code
   * Authorization} header is refused 401. So an unauthenticated probe would report "no context
   * length" on a perfectly healthy node — an absence that looks like the endpoint not having the
   * field.
   */
  @Test
  @Timeout(30)
  void the_probe_is_an_authenticated_call() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(routing(json(MODELS_BODY)));
      PoolProperties pool = poolAt(server);
      pool.setApiKey(KEY);
      try (LmStudio provider = lmStudioAt(server, pool)) {
        assertEquals(OptionalInt.of(128000), provider.contextLength("qwen3.5-9b"));
      }
      RecordedRequest probe = server.takeRequest();
      assertEquals("GET", probe.getMethod(), "a metadata read is a GET");
      assertNotNull(probe.getPath());
      assertTrue(
          probe.getPath().startsWith("/api/v0/models"),
          "the vendor path is a sibling of /v1 and not a child of it; asked for "
              + probe.getPath());
      assertEquals(
          "Bearer " + KEY,
          probe.getHeader("Authorization"),
          "the endpoint this probes refuses an unauthenticated request");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted waiting for the recorded probe", interrupted);
    }
  }

  /**
   * The probe is a new route from a response body into a message, and it must not be the one that
   * finally prints the key.
   *
   * <p><b>The body here is what the reference box actually sends</b>, measured 2026-08-31 by
   * offering it a token it did not like: it answers 401 with {@code "Malformed LM Studio API token
   * provided: sk-bogus-E**************"} — the first ten characters of the offered token, then
   * asterisks.
   *
   * <p>That shape matters because it defeats matching. A guard that searched the body for the
   * configured key would not find a masked prefix, and would pass the line through. What catches it
   * is {@code OpenAiTransport}'s status branch, which drops a 401 or 403 body whole on the grounds
   * that an auth failure is the reply most likely to quote the credential — and this test exists
   * because the probe reaches that guard only by going through the transport rather than around it.
   */
  @Test
  @Timeout(30)
  void a_probe_refused_with_the_key_quoted_back_never_repeats_it() throws IOException {
    String masked = KEY.substring(0, 10) + "**************";
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(
          routing(
              new MockResponse()
                  .setResponseCode(401)
                  .setBody(
                      "{\"error\":{\"code\":\"invalid_api_key\",\"message\":\"Malformed LM Studio API"
                          + " token provided: "
                          + masked
                          + ". Ensure you are using a valid"
                          + " token.\"}}")));
      PoolProperties pool = poolAt(server);
      pool.setApiKey(KEY);

      try (OpenAiCompatible base = new OpenAiCompatible(pool, new ObjectMapper())) {
        LlmTransportException refused =
            assertThrows(LlmTransportException.class, () -> base.probe("/api/v0/models"));
        assertFalse(
            refused.getMessage().contains(KEY), "the probe's refusal quoted the pool's key");
        assertFalse(
            refused.getMessage().contains(masked),
            "the probe's refusal quoted a masked prefix of the pool's key, which is"
                + " still the key's first ten characters");
        assertTrue(
            refused.getMessage().contains("studio"),
            "a refusal names the pool, which is the operator's only handle on which"
                + " of several hosts refused: "
                + refused.getMessage());
      }

      // And the same body, swallowed on the real path, must not reach a
      // log line either.
      Logger providerLog = (Logger) LoggerFactory.getLogger(LmStudio.class);
      ListAppender<ILoggingEvent> captured = new ListAppender<>();
      captured.start();
      providerLog.addAppender(captured);
      try (LmStudio provider = lmStudioAt(server, pool)) {
        assertEquals(OptionalInt.empty(), provider.contextLength("qwen3.5-9b"));
      } finally {
        providerLog.detachAppender(captured);
        captured.stop();
      }
      assertFalse(
          captured.list.isEmpty(),
          "a probe that failed should say so, or nobody can tell an unconfigured"
              + " length from an unreachable node");
      for (ILoggingEvent event : captured.list) {
        String rendered =
            event.getFormattedMessage()
                + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage());
        assertFalse(rendered.contains(KEY), "a log line carried the pool's key");
        assertFalse(
            rendered.contains(masked), "a log line carried a masked prefix of the pool's key");
      }
    }
  }

  /**
   * The boot says which pools are serving a model at a length nobody configured and nobody can
   * discover.
   *
   * <p>A warning and not a refusal, because {@code application.yml} records a deliberate property:
   * a model the server does not know "fails at the call rather than at startup". Refusing here
   * would trade that away for a fact that is only wanted by a long conversation.
   *
   * <p>Nothing probes. This runs at boot with no endpoint behind the base URL — which is why the
   * check can only ask whether the provider has <em>anywhere</em> to look, never what it would
   * find.
   */
  @Test
  void a_length_nobody_configured_and_nobody_can_discover_is_named_at_boot() {
    Logger configLog = (Logger) LoggerFactory.getLogger(LlmConfig.class);
    ListAppender<ILoggingEvent> captured = new ListAppender<>();
    captured.start();
    configLog.addAppender(captured);
    try {
      new ApplicationContextRunner()
          .withPropertyValues("plowshare.llm.accounting.enabled=false")
          .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
          .withUserConfiguration(LlmConfig.class)
          .withBean(ObjectMapper.class)
          .withBean(NoOpTokenLedger.class)
          .withPropertyValues(
              "plowshare.llm.embedding-model=nomic-embed-text",
              "plowshare.llm.embedding-dim=768",
              "plowshare.llm.embedding-max-input-tokens=1536",
              "plowshare.llm.default-context-length=64000",
              "plowshare.llm.pools[0].name=studio",
              "plowshare.llm.pools[0].base-url=http://192.0.2.10:1234/v1",
              "plowshare.llm.pools[0].models[0]=nomic-embed-text",
              "plowshare.llm.pools[0].models[1]=qwen3.5-9b",
              // The fixture the filter needs something to filter
              // out: one of the two models has a length, so a
              // warning naming both would be wrong rather than
              // merely noisy.
              "plowshare.llm.pools[0].context-lengths[nomic-embed-text]=2048")
          .run(
              context ->
                  assertNotNull(
                      context.getBean(LlmDispatcher.class),
                      "this is a warning and not a refusal; the context must start"));
    } finally {
      configLog.detachAppender(captured);
      captured.stop();
    }

    String warnings =
        captured.list.stream()
            .filter(event -> event.getLevel() == Level.WARN)
            .map(ILoggingEvent::getFormattedMessage)
            .reduce("", (all, one) -> all + one + "\n");
    assertTrue(
        warnings.contains("studio"),
        "the warning must name the pool, which is the operator's handle on it: " + warnings);
    assertTrue(
        warnings.contains("qwen3.5-9b"),
        "the warning must name the model whose length is unknown: " + warnings);
    assertFalse(
        warnings.contains("nomic-embed-text"),
        "nomic-embed-text has a configured length, so naming it would send an operator"
            + " to fix a key that is already set: "
            + warnings);
  }

  /**
   * <b>Nothing about starting the server touches the endpoint.</b>
   *
   * <p>This is the invariant the whole design of the boot check rests on, and it is asserted rather
   * than argued: a real socket is put behind the base URL, a full context is built with the
   * provider that <em>does</em> probe, and the server counts the requests it received. Zero.
   *
   * <p>An unroutable address would not prove it — a boot that probed one would simply be slow and
   * then carry on, which looks the same from outside. A listening socket that counts is the only
   * instrument that can tell "did not probe" from "probed and failed".
   *
   * <p>What it protects is {@code application.yml}'s deliberate property: a model name the server
   * does not know fails at the call rather than at startup. A probe on the boot path would make
   * every deployment where the inference box comes up second into a server that will not start.
   */
  @Test
  @Timeout(30)
  void starting_the_server_opens_no_connection_to_the_endpoint() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(routing(json(MODELS_BODY)));
      new ApplicationContextRunner()
          .withPropertyValues("plowshare.llm.accounting.enabled=false")
          .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
          .withUserConfiguration(LlmConfig.class)
          .withBean(ObjectMapper.class)
          .withBean(NoOpTokenLedger.class)
          .withPropertyValues(
              "plowshare.llm.embedding-model=nomic-embed-text",
              "plowshare.llm.embedding-dim=768",
              "plowshare.llm.embedding-max-input-tokens=1536",
              "plowshare.llm.default-context-length=64000",
              "plowshare.llm.pools[0].name=studio",
              "plowshare.llm.pools[0].base-url=" + server.url("/v1"),
              "plowshare.llm.pools[0].models[0]=nomic-embed-text",
              "plowshare.llm.pools[0].models[1]=qwen3.5-9b",
              "plowshare.llm.pools[0].provider=lmstudio")
          .run(
              context ->
                  assertNotNull(context.getBean(LlmDispatcher.class), "the context must start"));
      assertEquals(
          0,
          server.getRequestCount(),
          "the boot asked the endpoint something. Discovery is opportunistic on"
              + " purpose: a probe here makes the inference box a startup"
              + " dependency, and a model name the server does not know is meant"
              + " to fail at the call rather than at startup");
    }
  }

  /**
   * The other side of the same check: a pool that can discover is not warned about at boot, because
   * the boot has not asked it anything yet.
   */
  @Test
  void a_pool_that_can_discover_is_not_warned_about_before_it_has_been_asked() {
    Logger configLog = (Logger) LoggerFactory.getLogger(LlmConfig.class);
    ListAppender<ILoggingEvent> captured = new ListAppender<>();
    captured.start();
    configLog.addAppender(captured);
    try {
      new ApplicationContextRunner()
          .withPropertyValues("plowshare.llm.accounting.enabled=false")
          .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
          .withUserConfiguration(LlmConfig.class)
          .withBean(ObjectMapper.class)
          .withBean(NoOpTokenLedger.class)
          .withPropertyValues(
              "plowshare.llm.embedding-model=nomic-embed-text",
              "plowshare.llm.embedding-dim=768",
              "plowshare.llm.embedding-max-input-tokens=1536",
              "plowshare.llm.default-context-length=64000",
              "plowshare.llm.pools[0].name=studio",
              "plowshare.llm.pools[0].base-url=http://192.0.2.10:1234/v1",
              "plowshare.llm.pools[0].models[0]=nomic-embed-text",
              "plowshare.llm.pools[0].provider=lmstudio")
          .run(context -> assertNotNull(context.getBean(LlmDispatcher.class)));
    } finally {
      configLog.detachAppender(captured);
      captured.stop();
    }
    for (ILoggingEvent event : captured.list) {
      assertFalse(
          event.getLevel() == Level.WARN && event.getFormattedMessage().contains("context length"),
          "a pool with somewhere to look was warned about at boot, which would mean"
              + " the boot had already gone looking: "
              + event.getFormattedMessage());
    }
  }

  // --- reaching it from a running server ---------------------------------------------

  /**
   * The gap this facility shipped with: a running server could not ask.
   *
   * <p>The provider knew the number and nothing above it could get at one — {@link LlmPool} holds a
   * provider as an {@code LlmTransport}, and neither it nor {@link LlmDispatcher} exposed the
   * question. Every test above builds an {@code LmStudio} by hand, which no service can do: the
   * dispatcher is the only component that holds the pools and the only one that knows which pool
   * serves a specifier.
   *
   * <p><b>The class name is the half that discriminates.</b> {@code chat} is a name no endpoint
   * answers to, so an implementation that passed the specifier straight to a provider gets nothing
   * for it while still answering correctly for the wire name beside it. Resolving through the pool
   * is what makes both work, and it is what a caller actually has — an agent's {@code model:} may
   * be either.
   */
  @Test
  @Timeout(30)
  void a_running_server_asks_the_dispatcher_what_a_specifier_is_loaded_at() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(routing(json(MODELS_BODY)));
      PoolProperties props = poolAt(server);
      props.setClasses(Map.of("chat", "qwen3.5-9b"));
      try (LlmDispatcher dispatcher =
          dispatcherOver(pool("studio", props, lmStudioAt(server, props)))) {
        assertEquals(
            OptionalInt.of(128000),
            dispatcher.contextLength("qwen3.5-9b", FALLBACK),
            "the wire model's discovered length did not reach the dispatcher");
        assertEquals(
            OptionalInt.of(128000),
            dispatcher.contextLength("chat", FALLBACK),
            "a class was not resolved to the model behind it; the endpoint does not"
                + " answer to the name 'chat' and a provider asked for it"
                + " directly can only answer empty");
      }
    }
  }

  /**
   * A configured fold threshold reaches the dispatcher through the vendor provider, and costs no
   * connection.
   *
   * <p><b>The provider is where this could quietly go missing.</b> {@code LlmTransport} declares
   * {@code compactionThreshold} with a default answering empty, so a decorator that forgets to
   * delegate inherits silence and every {@code compaction-thresholds} key on an LM Studio pool —
   * which is every pool the reference deployment has — is read by nothing. The conversation then
   * folds at the derived default and no log line says so.
   *
   * <p>The request count is the other half. Unlike a context length there is nothing here to
   * discover: no endpoint has an opinion about when a conversation should fold, so asking one would
   * be a network call bought for nothing.
   *
   * <p>The class name discriminates for the reason the test above gives: a specifier passed
   * straight to a provider answers empty for {@code chat}.
   */
  @Test
  @Timeout(30)
  void a_configured_fold_threshold_reaches_the_dispatcher_and_costs_no_probe() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(routing(json(MODELS_BODY)));
      PoolProperties props = poolAt(server);
      props.setClasses(Map.of("chat", "qwen3.5-9b"));
      props.setCompactionThresholds(Map.of("qwen3.5-9b", 40000));
      try (LlmDispatcher dispatcher =
          dispatcherOver(pool("studio", props, lmStudioAt(server, props)))) {
        assertEquals(
            OptionalInt.of(40000),
            dispatcher.compactionThreshold("qwen3.5-9b"),
            "what the operator configured did not reach the dispatcher");
        assertEquals(
            OptionalInt.of(40000),
            dispatcher.compactionThreshold("chat"),
            "a class was not resolved to the model behind it");
        assertEquals(
            OptionalInt.empty(),
            dispatcher.compactionThreshold("nomic-embed-text"),
            "nothing was configured for this one, and nothing invents a number"
                + " here — the default is derived where it is argued");
        assertEquals(
            0, server.getRequestCount(), "there is nothing to discover, so no probe is owed");
      }
    }
  }

  /**
   * The in-turn fold threshold travels the same road, through the vendor provider, and costs no
   * connection either (spec 2026-09-30-fold-at-60-and-80 §1).
   */
  @Test
  @Timeout(30)
  void a_configured_in_turn_fold_threshold_reaches_the_dispatcher_and_costs_no_probe()
      throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(routing(json(MODELS_BODY)));
      PoolProperties props = poolAt(server);
      props.setClasses(Map.of("chat", "qwen3.5-9b"));
      props.setCompactionNowThresholds(Map.of("qwen3.5-9b", 90000));
      try (LlmDispatcher dispatcher =
          dispatcherOver(pool("studio", props, lmStudioAt(server, props)))) {
        assertEquals(OptionalInt.of(90000), dispatcher.compactionNowThreshold("qwen3.5-9b"));
        assertEquals(OptionalInt.of(90000), dispatcher.compactionNowThreshold("chat"));
        assertEquals(OptionalInt.empty(), dispatcher.compactionNowThreshold("nomic-embed-text"));
        assertEquals(0, server.getRequestCount());
      }
    }
  }

  /**
   * Two pools serving one specifier at two lengths: the smaller is the answer.
   *
   * <p>Not the first declared, and not the one {@code route} would pick — that one is chosen by
   * load, so a length routed the same way would change with the fleet's queue depths and a
   * conversation would compact or not depending on which host happened to be idle. The next call
   * may go to either pool, so a prompt is only safe if it fits the tighter one.
   *
   * <p>8000 is configured and 128000 is discovered, so this also fails on an implementation that
   * consulted only one source.
   */
  @Test
  @Timeout(30)
  void the_smallest_length_wins_when_two_pools_serve_one_specifier() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(routing(json(MODELS_BODY)));
      PoolProperties roomy = poolAt(server);
      PoolProperties cramped = poolAt(server);
      cramped.setName("cramped");
      cramped.setContextLengths(Map.of("qwen3.5-9b", 8000));
      try (LlmDispatcher dispatcher =
          dispatcherOver(
              pool("roomy", roomy, lmStudioAt(server, roomy)),
              pool("cramped", cramped, new OpenAiCompatible(cramped, new ObjectMapper())))) {
        assertEquals(OptionalInt.of(8000), dispatcher.contextLength("qwen3.5-9b", FALLBACK));
      }
    }
  }

  /**
   * A pool that cannot say is skipped rather than making the whole answer empty.
   *
   * <p>The direction that costs least, and it is a claim worth pinning rather than asserting in
   * prose: answering empty here would disable compaction against the pool that <em>could</em> be
   * asked as well as the one that could not, which is a worse bound than the one available.
   */
  @Test
  @Timeout(30)
  void a_pool_with_nothing_to_say_does_not_silence_one_that_has() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(routing(json(MODELS_BODY)));
      PoolProperties knowing = poolAt(server);
      PoolProperties silent = poolAt(server);
      silent.setName("silent");
      try (LlmDispatcher dispatcher =
          dispatcherOver(
              pool("silent", silent, new OpenAiCompatible(silent, new ObjectMapper())),
              pool("knowing", knowing, lmStudioAt(server, knowing)))) {
        assertEquals(OptionalInt.of(128000), dispatcher.contextLength("qwen3.5-9b", FALLBACK));
      }
    }
  }

  /**
   * A specifier nothing serves has no length, and asking is not an error.
   *
   * <p>{@code route} and {@code requireServed} both raise for this, and they should: a call has to
   * go somewhere. This question does not — a caller does the same thing for "nothing serves it" as
   * for "nobody knows", which is run without compacting — and the run's own first call is what
   * reports the specifier, with every pool named.
   *
   * <p><b>The fallback must not blur it, which is why one is passed here.</b> "Served, and nobody
   * can size it" is the gap {@code plowshare.llm.default-context-length} exists to fill; "nothing
   * serves this name" is a different fact, and answering it with a number would hand {@code
   * Compaction} a bound for a conversation whose very first call is going to raise {@code
   * UnknownSpecifierException}. It is also what keeps {@code foldIfItWouldNotFit}'s {@code
   * bound.isEmpty()} guard reachable rather than dead.
   */
  @Test
  @Timeout(30)
  void a_specifier_nothing_serves_has_no_length_and_does_not_raise() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(routing(json(MODELS_BODY)));
      PoolProperties props = poolAt(server);
      try (LlmDispatcher dispatcher =
          dispatcherOver(pool("studio", props, lmStudioAt(server, props)))) {
        assertEquals(
            OptionalInt.empty(), dispatcher.contextLength("nothing-serves-this", FALLBACK));
      }
    }
  }

  // --- the third tier, and which of the three answered --------------------------------

  /**
   * A pool that serves the model and cannot say how long a prompt it takes gets the configured
   * default, and does not get silence.
   *
   * <p><b>This is the tier that closed a silent off-switch.</b> Before it, {@code
   * Compaction.foldIfItWouldNotFit} returned at its {@code bound.isEmpty()} guard whenever no
   * length could be determined — so a deployment whose node would not answer ran with compaction
   * switched off entirely, and the only report was one warning at boot. The server kept working,
   * which is what made it dangerous.
   *
   * <p>{@link #FALLBACK} is deliberately not 64 000. The number an operator gets is whatever {@code
   * plowshare.llm.default-context-length} holds, and a test that used the shipped value could not
   * tell "the caller's fallback was used" from "the shipped default was hardcoded somewhere".
   */
  @Test
  @Timeout(30)
  void a_pool_that_cannot_say_gets_the_configured_default() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(routing(json(MODELS_BODY)));
      PoolProperties props = poolAt(server);
      try (LlmDispatcher dispatcher =
          dispatcherOver(pool("studio", props, new OpenAiCompatible(props, new ObjectMapper())))) {
        assertEquals(
            OptionalInt.of(FALLBACK),
            dispatcher.contextLength("qwen3.5-9b", FALLBACK),
            "nobody configured a length and this provider cannot discover one, so"
                + " the configured default is the whole of what is left");
        assertEquals(
            0,
            server.getRequestCount(),
            "an OpenAI-compatible pool has nowhere to look, so falling back to the"
                + " default must not cost a request");
      }
    }
  }

  /**
   * The default is the BOTTOM tier and never outranks a pool that can say.
   *
   * <p>Both directions in one test, because a fallback applied too eagerly is the realistic
   * mistake. The configured entry is 8 000 and the node reports 128 000; the fallback sits between
   * them, so an implementation that let it win — or that let it win only when it was smaller, which
   * "smallest wins" makes tempting — answers {@link #FALLBACK} for one of these two and fails.
   */
  @Test
  @Timeout(30)
  void the_default_never_outranks_a_configured_entry_or_the_node() throws IOException {
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(routing(json(MODELS_BODY)));
      PoolProperties discovering = poolAt(server);
      PoolProperties configured = poolAt(server);
      configured.setName("configured");
      configured.setContextLengths(Map.of("qwen3.5-9b", 8000));
      try (LlmDispatcher node =
          dispatcherOver(pool("studio", discovering, lmStudioAt(server, discovering)))) {
        assertEquals(
            OptionalInt.of(128000),
            node.contextLength("qwen3.5-9b", FALLBACK),
            "the node's own answer is better information than any blanket number");
      }
      try (LlmDispatcher operator =
          dispatcherOver(
              pool(
                  "configured",
                  configured,
                  new OpenAiCompatible(configured, new ObjectMapper())))) {
        assertEquals(
            OptionalInt.of(8000),
            operator.contextLength("qwen3.5-9b", FALLBACK),
            "an operator who wrote a context-lengths entry outranks everything");
      }
    }
  }

  /**
   * The log says which of the three tiers supplied the number, and the bottom one is a WARN.
   *
   * <p><b>Why this cannot be a boot log, which is where it was asked for.</b> The boot may not open
   * a connection — {@code starting_the_server_opens_no_connection_to_the_endpoint} above is the
   * assertion, and {@code application.yml} records the property it protects — so at startup the
   * middle tier is unknowable for every pool that could answer it. {@code LlmConfig} says what it
   * can there, once per pool, for the providers that have nowhere to look. The tier that actually
   * answered is known only at the moment somebody asks, so that is where this line is, and it is
   * written once per specifier rather than once per turn.
   *
   * <p>WARN for the default and INFO for the other two, because reaching the default means both the
   * operator's configuration and the node's own answer came up empty — on an LM Studio pool that is
   * a box that could not be probed, which an operator should notice. A number that came from
   * configuration or from the node is the system working.
   */
  @Test
  @Timeout(30)
  void the_log_names_the_tier_that_supplied_the_length() throws IOException {
    Logger dispatchLog = (Logger) LoggerFactory.getLogger(LlmDispatcher.class);
    ListAppender<ILoggingEvent> captured = new ListAppender<>();
    captured.start();
    dispatchLog.addAppender(captured);
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(routing(json(MODELS_BODY)));
      PoolProperties bare = poolAt(server);
      PoolProperties operator = poolAt(server);
      operator.setName("operator");
      operator.setContextLengths(Map.of("qwen3.5-9b", 8000));
      PoolProperties node = poolAt(server);
      node.setName("node");

      try (LlmDispatcher dispatcher =
          dispatcherOver(pool("bare", bare, new OpenAiCompatible(bare, new ObjectMapper())))) {
        dispatcher.contextLength("qwen3.5-9b", FALLBACK);
        // Twice, because a line written per ask is a line written per
        // ending turn in the server, which is how a warning gets
        // filtered out by whoever reads the log.
        dispatcher.contextLength("qwen3.5-9b", FALLBACK);
      }
      List<ILoggingEvent> fallback = List.copyOf(captured.list);
      assertEquals(
          1, fallback.size(), "one line per specifier, not one per ask: " + rendered(fallback));
      assertEquals(
          Level.WARN,
          fallback.get(0).getLevel(),
          "reaching the default means discovery failed, and an operator should notice");
      assertTrue(
          rendered(fallback).contains("default-context-length"),
          "the warning must name the key that supplied the number: " + rendered(fallback));

      captured.list.clear();
      try (LlmDispatcher dispatcher =
          dispatcherOver(
              pool("operator", operator, new OpenAiCompatible(operator, new ObjectMapper())))) {
        dispatcher.contextLength("qwen3.5-9b", FALLBACK);
      }
      assertEquals(
          Level.INFO,
          captured.list.get(0).getLevel(),
          "a configured length is the system working: " + rendered(captured.list));
      assertTrue(
          rendered(captured.list).contains("context-lengths"),
          "the line must name the tier that answered: " + rendered(captured.list));

      captured.list.clear();
      try (LlmDispatcher dispatcher =
          dispatcherOver(pool("node", node, lmStudioAt(server, node)))) {
        dispatcher.contextLength("qwen3.5-9b", FALLBACK);
      }
      assertEquals(
          Level.INFO,
          captured.list.get(0).getLevel(),
          "a discovered length is the system working: " + rendered(captured.list));
      String discovered = rendered(captured.list);
      assertTrue(discovered.contains("128000"), discovered);
      assertFalse(
          discovered.contains("context-lengths"),
          "nothing was configured here; naming that key would send an operator to"
              + " look at a key they never wrote: "
              + discovered);
    } finally {
      dispatchLog.detachAppender(captured);
      captured.stop();
    }
  }

  /**
   * A number that is not the shipped default, so a test cannot pass by reading 64000 out of
   * somewhere it was hardcoded.
   */
  private static final int FALLBACK = 12345;

  private static String rendered(List<ILoggingEvent> events) {
    return events.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .reduce("", (all, one) -> all + one + "\n");
  }

  private static LlmPool pool(String name, PoolProperties props, LlmProvider provider) {
    return new LlmPool(
        name, props.getModels(), props.getClasses(), 2, 1, Duration.ofSeconds(5), provider);
  }

  private static LlmDispatcher dispatcherOver(LlmPool... pools) {
    return new LlmDispatcher(List.of(pools), new NoOpTokenLedger());
  }
}
