package io.aeyer.plowshare.server.llm;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * What the server refuses to start with. No datasource, no endpoint.
 *
 * <p>Every case here is a configuration whose only other report would arrive long after boot,
 * somewhere that cannot name the property that caused it — mostly on the write path, where an
 * embedding failure is deliberately swallowed so the memory survives, and therefore reaches nobody
 * at all.
 */
class LlmConfigTest {

  @Test
  void a_smaller_fold_timeout_warns_prominently_and_still_starts() {
    Logger configLog = (Logger) LoggerFactory.getLogger(LlmConfig.class);
    ListAppender<ILoggingEvent> captured = new ListAppender<>();
    captured.start();
    configLog.addAppender(captured);
    try {
      runner
          .withPropertyValues(
              onePoolPlus("plowshare.llm.prompt-timeout=200s", "plowshare.llm.fold-timeout=160s"))
          .run(context -> assertNull(context.getStartupFailure()));
      assertTrue(
          captured.list.stream()
              .anyMatch(
                  event ->
                      event.getLevel() == Level.WARN
                          && event
                              .getFormattedMessage()
                              .contains("FOLD TIMEOUT CONFIGURATION WARNING")
                          && event.getFormattedMessage().contains("PT3M20S")));
    } finally {
      configLog.detachAppender(captured);
      captured.stop();
    }
  }

  @Test
  void global_prompt_budgets_must_be_finite_and_positive() {
    for (String key : List.of("prompt-timeout", "fold-timeout")) {
      for (String value : List.of("0s", "-1s", "500ns", "30d")) {
        runner
            .withPropertyValues(onePoolPlus("plowshare.llm." + key + "=" + value))
            .run(
                context -> {
                  assertNotNull(context.getStartupFailure());
                  assertTrue(trail(context.getStartupFailure()).contains(key));
                });
      }
    }
  }

  @Test
  void context_maximums_must_name_a_served_model_and_positive_tokens() {
    for (var invalid : java.util.Map.of("missing", 4096, "nomic-embed-text", 0).entrySet()) {
      runner
          .withPropertyValues(
              onePoolPlus(
                  "plowshare.llm.pools[0].max-context-lengths["
                      + invalid.getKey()
                      + "]="
                      + invalid.getValue()))
          .run(
              context -> {
                assertNotNull(context.getStartupFailure());
                assertTrue(trail(context.getStartupFailure()).contains("max-context-lengths"));
              });
    }
  }

  @Test
  void a_fold_threshold_above_the_working_context_maximum_is_refused() {
    runner
        .withPropertyValues(
            onePoolPlus(
                "plowshare.llm.pools[0].context-lengths[nomic-embed-text]=131072",
                "plowshare.llm.pools[0].max-context-lengths[nomic-embed-text]=64000",
                "plowshare.llm.pools[0].compaction-now-thresholds[nomic-embed-text]=70000"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              assertTrue(trail(context.getStartupFailure()).contains("64000"));
            });
  }

  /**
   * A value that must never reach a message, and is deliberately not a plausible key: a test that
   * greps for something real is a test that put something real in a file.
   */
  private static final String KEY = "a-key-that-must-never-be-printed";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          // This configuration-only fixture has no durable storage.
          .withPropertyValues("plowshare.llm.accounting.enabled=false")
          .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
          .withUserConfiguration(LlmConfig.class)
          .withBean(ObjectMapper.class)
          .withBean(NoOpTokenLedger.class);

  private static String trail(Throwable failure) {
    StringBuilder text = new StringBuilder();
    Throwable current = failure;
    while (current != null) {
      text.append(current.getMessage()).append('\n');
      current = current.getCause();
    }
    return text.toString();
  }

  /**
   * The one pool every case below starts from, so each test's own property is the only difference
   * between it and a context that starts.
   *
   * <p><b>{@code embedding-dim} is written out here and did not used to be.</b> {@link
   * LlmProperties} carried {@code 768} as a field initializer, so a runner that bound no such key
   * still got a positive width; the default now lives only in {@code application.yml}, which an
   * {@code ApplicationContextRunner} does not load. Without this line every case below would fail
   * on {@code requireEmbeddingDim} instead of on the property it is actually about — which is what
   * happened when the initializer went, and is exactly the kind of "test passes for the wrong
   * reason" this base array exists to prevent.
   */
  private static String[] onePoolPlus(String... extra) {
    String[] base = {
      "plowshare.llm.embedding-model=nomic-embed-text",
      "plowshare.llm.embedding-dim=768",
      "plowshare.llm.embedding-max-input-tokens=1536",
      "plowshare.llm.default-context-length=64000",
      "plowshare.llm.pools[0].name=studio",
      "plowshare.llm.pools[0].base-url=http://localhost:1234/v1",
      "plowshare.llm.pools[0].models[0]=nomic-embed-text",
    };
    String[] all = new String[base.length + extra.length];
    System.arraycopy(base, 0, all, 0, base.length);
    System.arraycopy(extra, 0, all, base.length, extra.length);
    return all;
  }

  /** A pool that would start, for a test to spoil one field of. */
  private static PoolProperties validPool() {
    PoolProperties pool = new PoolProperties();
    pool.setName("studio");
    pool.setBaseUrl("http://localhost:1234/v1");
    pool.setModels(List.of("nomic-embed-text"));
    return pool;
  }

  /**
   * The config called directly, for the states no property source can produce.
   *
   * <p>Spring's binder skips a setter for a value it resolves as null, so a null {@code Duration}
   * and a null map value are reachable only from code — which is how {@code
   * TransactionBoundaryTest} builds a {@code PoolProperties}, and is what {@code LlmPool}'s
   * constructor javadoc names this class as the guard for.
   */
  private static IllegalStateException refused(PoolProperties pool) {
    LlmProperties props = new LlmProperties();
    props.setEmbeddingModel("nomic-embed-text");
    props.setPools(List.of(pool));
    return assertThrows(
        IllegalStateException.class,
        () -> new LlmConfig().llmDispatcher(props, new ObjectMapper(), new NoOpTokenLedger()));
  }

  @Test
  void a_pool_that_serves_the_embedding_model_starts() {
    runner
        .withPropertyValues(onePoolPlus())
        .run(
            context -> {
              assertNotNull(context.getBean(LlmDispatcher.class));
              // Constructing a pool opens no socket, which is why this
              // test can run against a URL with nothing behind it.
            });
  }

  @Test
  void vllm_counting_binds_an_explicit_proxy_url_and_template_contract() {
    runner
        .withPropertyValues(
            onePoolPlus(
                "plowshare.llm.pools[0].counting.url=http://localhost:1234/proxy/tokenize",
                "plowshare.llm.pools[0].counting.revision=template-v1",
                "plowshare.llm.pools[0].counting.automatic=true",
                "plowshare.llm.pools[0].counting.timeout=500ms",
                "plowshare.llm.pools[0].counting.cache-entries=2",
                "plowshare.llm.pools[0].chat-template-kwargs.enable_thinking=false"))
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var pool = context.getBean(LlmProperties.class).getPools().getFirst();
              assertTrue(pool.getCounting().isAutomatic());
              assertEquals(java.time.Duration.ofMillis(500), pool.getCounting().getTimeout());
              assertEquals(2, pool.getCounting().getCacheEntries());
              assertEquals(Boolean.FALSE, pool.getChatTemplateKwargs().enableThinking());
            });
  }

  @Test
  void azure_cloud_bindings_keep_deployment_identity_separate_from_family_capabilities() {
    runner
        .withPropertyValues(
            onePoolPlus(
                "plowshare.llm.pools[0].base-url=https://fixture.example/openai/v1/",
                "plowshare.llm.pools[0].request-style=cloud",
                "plowshare.llm.pools[0].api-auth=azure_api_key",
                "plowshare.llm.pools[0].billing-route=azure-fixture",
                "plowshare.llm.pools[0].model-families[nomic-embed-text]=family",
                "plowshare.llm.pools[0].request-capabilities[family].output-limit=max_completion_tokens",
                "plowshare.llm.pools[0].request-capabilities[family].default-output-tokens=8192",
                "plowshare.llm.pools[0].request-capabilities[family].reasoning-efforts[0]=none",
                "plowshare.llm.pools[0].request-capabilities[family].tools=true",
                "plowshare.llm.pools[0].request-capabilities[family].tools-only-without-reasoning=true"))
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var pool = context.getBean(LlmProperties.class).getPools().getFirst();
              assertEquals(PoolProperties.ApiAuth.AZURE_API_KEY, pool.getApiAuth());
              assertEquals("azure-fixture", pool.getBillingRoute());
              var cap = pool.capabilitiesFor("nomic-embed-text");
              assertEquals(8192, cap.getDefaultOutputTokens());
              assertTrue(cap.isToolsOnlyWithoutReasoning());
              assertFalse(pool.getDefaultCapabilities().isTools());
            });
  }

  @Test
  void counter_and_cloud_misconfiguration_is_refused_before_any_upstream_call() {
    for (String[] extra :
        List.of(
            new String[] {"plowshare.llm.pools[0].counting.url=https://other.example/tokenize"},
            new String[] {"plowshare.llm.pools[0].counting.automatic=true"},
            new String[] {"plowshare.llm.pools[0].counting.cache-ttl=61s"},
            new String[] {
              "plowshare.llm.pools[0].request-style=cloud",
              "plowshare.llm.pools[0].counting.url=http://localhost:1234/tokenize"
            },
            new String[] {"plowshare.llm.pools[0].request-capabilities[undeclared].tools=true"},
            new String[] {"plowshare.llm.pools[0].counting.cache-entires=3"},
            new String[] {
              "plowshare.llm.pools[0].counting.url=http://localhost:1234/tokenize",
              "plowshare.llm.pools[0].counting.cache-entires=3"
            },
            new String[] {"plowshare.llm.pools[0].default-capabilities.toolz=true"},
            new String[] {
              "plowshare.llm.pools[0].model-families[nomic-embed-text]=family",
              "plowshare.llm.pools[0].request-capabilities[family].toolz=true"
            })) {
      runner
          .withPropertyValues(onePoolPlus(extra))
          .run(context -> assertNotNull(context.getStartupFailure(), Arrays.toString(extra)));
    }
  }

  /**
   * The archive's capability is wired here and not by a stereotype.
   *
   * <p>Task 7 measured the alternative: the {@code llm} package is component scanned, so
   * {@code @Service} on {@link DispatchingEmbeddingClient} makes it an eager singleton needing a
   * dispatcher bean, and every Spring-context test fails at startup with {@code
   * NoSuchBeanDefinitionException}. The two are built from the same properties and have to be
   * refused together.
   */
  @Test
  void the_archive_s_embedding_capability_is_wired_beside_the_dispatcher() {
    runner
        .withPropertyValues(onePoolPlus())
        .run(context -> assertNotNull(context.getBean(EmbeddingClient.class)));
  }

  @Test
  void an_embedding_model_no_pool_serves_stops_the_boot() {
    runner
        .withPropertyValues(
            "plowshare.llm.embedding-model=bge-m3",
            "plowshare.llm.embedding-dim=768",
            "plowshare.llm.embedding-max-input-tokens=1536",
            "plowshare.llm.default-context-length=64000",
            "plowshare.llm.pools[0].name=studio",
            "plowshare.llm.pools[0].base-url=http://localhost:1234/v1",
            "plowshare.llm.pools[0].models[0]=nomic-embed-text")
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("bge-m3"), message);
              assertTrue(message.contains("embedding-model"), message);
              // And says why it is fatal rather than merely wrong: the
              // write path swallows this failure, so nothing downstream
              // would ever report it.
              //
              // "recall nothing" and not only the model name: the name
              // appears inside UnknownSpecifierException's own pool
              // description, so asserting on it alone left the whole
              // fatality paragraph deletable with this test still green.
              assertTrue(message.contains("nomic-embed-text"), message);
              assertTrue(message.contains("recall nothing"), message);
            });
  }

  /**
   * A blank one too, and not only an unserved one.
   *
   * <p>{@code DispatchingEmbeddingClient} carries a runtime guard for this and says plainly that it
   * is a backstop: a blank specifier reaches {@code EmbeddingRequest} as an {@code
   * IllegalArgumentException}, which is a misconfiguration wearing the type reserved for a bad
   * request.
   *
   * <p><b>The word "blank" is what this asserts on, and that is not pedantry.</b> Deleting the
   * guard leaves the boot refused anyway, by {@code requireServed} — measured — but with "no pool
   * serves ''", which points an operator at their pools when what is empty is one archive-wide
   * setting. An assertion on "embedding-model" alone cannot tell the two apart.
   */
  @Test
  void a_blank_embedding_model_stops_the_boot() {
    runner
        .withPropertyValues(
            "plowshare.llm.embedding-model=",
            "plowshare.llm.embedding-dim=768",
            "plowshare.llm.embedding-max-input-tokens=1536",
            "plowshare.llm.default-context-length=64000",
            "plowshare.llm.pools[0].name=studio",
            "plowshare.llm.pools[0].base-url=http://localhost:1234/v1",
            "plowshare.llm.pools[0].models[0]=nomic-embed-text")
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("embedding-model"), message);
              assertTrue(message.contains("blank"), message);
            });
  }

  @Test
  void a_server_with_no_pools_stops_the_boot() {
    runner
        .withPropertyValues("plowshare.llm.embedding-model=nomic-embed-text")
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              assertTrue(
                  trail(context.getStartupFailure()).contains("plowshare.llm.pools"),
                  trail(context.getStartupFailure()));
            });
  }

  @Test
  void two_pools_with_one_name_stop_the_boot() {
    runner
        .withPropertyValues(
            "plowshare.llm.embedding-model=nomic-embed-text",
            "plowshare.llm.pools[0].name=studio",
            "plowshare.llm.pools[0].base-url=http://localhost:1234/v1",
            "plowshare.llm.pools[0].models[0]=nomic-embed-text",
            "plowshare.llm.pools[1].name=studio",
            "plowshare.llm.pools[1].base-url=http://localhost:1235/v1",
            "plowshare.llm.pools[1].models[0]=nomic-embed-text")
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              // A duplicate name makes every saturation message ambiguous
              // about which box is busy, which is the one thing the
              // message exists to say.
              //
              // Asserting the phrase and not just "studio", which appears
              // in nine of the twelve refusals this class can produce and
              // so said nothing about duplicate detection.
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("two pools are both named 'studio'"), message);
            });
  }

  @Test
  void a_pool_with_no_name_stops_the_boot() {
    runner
        .withPropertyValues(
            "plowshare.llm.embedding-model=nomic-embed-text",
            "plowshare.llm.pools[0].base-url=http://localhost:1234/v1",
            "plowshare.llm.pools[0].models[0]=nomic-embed-text")
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              assertTrue(
                  trail(context.getStartupFailure()).contains("name"),
                  trail(context.getStartupFailure()));
            });
  }

  @Test
  void a_pool_with_a_blank_base_url_stops_the_boot() {
    runner
        .withPropertyValues(
            "plowshare.llm.embedding-model=nomic-embed-text",
            "plowshare.llm.pools[0].name=studio",
            "plowshare.llm.pools[0].base-url=",
            "plowshare.llm.pools[0].models[0]=nomic-embed-text")
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("studio"), message);
              assertTrue(message.contains("base-url"), message);
            });
  }

  /**
   * The incident, refused before it can cost anything.
   *
   * <p>{@code localhost:1234/v1} — one missing {@code http://} — booted cleanly until this guard,
   * because {@code OpenAiTransport} parses lazily. The first embed then failed into {@code
   * Archive.embed}, which swallows that failure by design so the memory survives, so nothing above
   * it could ever have reported the misconfiguration. {@code
   * TransactionBoundaryTest.a_base_url_with_no_scheme_keeps_the_memory_and_
   * answers_the_caller_normally} pins what happens at runtime; this pins that runtime is never
   * reached.
   *
   * <p>The base URL carries a password here on purpose. {@code https://user:key@host/v1} is a legal
   * thing to configure, so a refusal that quoted the value back would put a credential in a log
   * line — which is why {@code OpenAiTransport} strips userinfo before printing one. This message
   * does not print the URL at all, and this asserts that it stays that way.
   */
  @Test
  void a_base_url_with_no_scheme_stops_the_boot() {
    runner
        .withPropertyValues(
            "plowshare.llm.embedding-model=nomic-embed-text",
            "plowshare.llm.pools[0].name=studio",
            "plowshare.llm.pools[0].base-url=user:hunter2@localhost:1234/v1",
            "plowshare.llm.pools[0].models[0]=nomic-embed-text")
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("studio"), message);
              assertTrue(message.contains("scheme"), message);
              assertFalse(
                  message.contains("hunter2"),
                  "the refusal echoed a base-url carrying a password: " + message);
            });
  }

  /**
   * The twin of the blank-class case, and it was the one left unguarded: a blank name is a name no
   * request can resolve to, so the pool advertises something nothing routes to.
   */
  @Test
  void a_blank_entry_in_models_stops_the_boot() {
    runner
        .withPropertyValues(
            "plowshare.llm.embedding-model=nomic-embed-text",
            "plowshare.llm.pools[0].name=studio",
            "plowshare.llm.pools[0].base-url=http://localhost:1234/v1",
            "plowshare.llm.pools[0].models[0]=",
            "plowshare.llm.pools[0].models[1]=nomic-embed-text")
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("studio"), message);
              assertTrue(message.contains("models"), message);
            });
  }

  /**
   * A value written with no value under the retired per-pool key, which throws before any check can
   * see it.
   *
   * <p>{@code getClasses()} returns {@code Map.copyOf}, which rejects a null value — so {@code
   * classes: {fast: ~}} raises a {@code NullPointerException} inside {@code validate}, from a
   * getter, naming neither the pool nor the property. The wrapper is what turns that back into a
   * refusal an operator can act on, and this is what pins it.
   *
   * <p><b>It survives the key's retirement, and reaches a different guard than it used to.</b> The
   * refusal that would name the new location reads the map, so it is the getter that fires first —
   * meaning an operator whose old key <em>also</em> has a null value gets the wrapper's message
   * rather than the one naming {@code plowshare.llm.classes}. That is the right order: both
   * messages send them to the same three lines of YAML, and only one of them is true about a map
   * that cannot be read.
   */
  @Test
  void a_retired_per_pool_class_mapped_to_null_stops_the_boot_naming_the_pool() {
    PoolProperties pool = validPool();
    Map<String, String> classes = new LinkedHashMap<>();
    classes.put("fast", null);
    pool.setClasses(classes);

    IllegalStateException refusal = refused(pool);
    assertTrue(refusal.getMessage().contains("studio"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("pools[0]"), refusal.getMessage());
    assertNotNull(refusal.getCause(), "the binder's NullPointerException is the evidence");
  }

  /**
   * The same fault on the server-wide map, which has its own getter and needs its own catch.
   *
   * <p>{@code LlmProperties.getClasses()} copies through {@code Map.copyOf} too, so {@code
   * plowshare.llm.classes: {fast: ~}} raises from a getter that is nowhere near a pool — and the
   * wrapper that names a pool cannot help, because the key belongs to no pool. Without a catch of
   * its own the operator gets a binder stack trace naming neither the class nor the property.
   */
  @Test
  void a_class_mapped_to_null_on_the_server_map_stops_the_boot_naming_the_key() {
    LlmProperties props = new LlmProperties();
    props.setEmbeddingModel("nomic-embed-text");
    props.setEmbeddingDim(768);
    props.setEmbeddingMaxInputTokens(1536);
    props.setDefaultContextLength(64000);
    props.setPools(List.of(validPool()));
    Map<String, String> classes = new LinkedHashMap<>();
    classes.put("fast", null);
    props.setClasses(classes);

    IllegalStateException refusal =
        assertThrows(
            IllegalStateException.class,
            () -> new LlmConfig().llmDispatcher(props, new ObjectMapper(), new NoOpTokenLedger()));
    assertTrue(refusal.getMessage().contains("plowshare.llm.classes"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("fast: ~"), refusal.getMessage());
    assertNotNull(refusal.getCause(), "the getter's NullPointerException is the evidence");
  }

  /**
   * A fold threshold keyed by a model the pool does not serve stops the boot.
   *
   * <p><b>The quiet failure this refuses.</b> The key binds, reads as configured, and is consulted
   * by nothing — so the conversation goes on folding at the derived default and the only symptom is
   * a number nobody asked for. A typo or a model swapped out is the ordinary way to arrive here,
   * and the refusal names both the key and what the pool actually serves.
   */
  @Test
  void a_fold_threshold_for_a_model_the_pool_does_not_serve_stops_the_boot() {
    PoolProperties pool = validPool();
    pool.setCompactionThresholds(Map.of("a-model-nobody-serves", 40000));

    IllegalStateException refusal = refused(pool);
    assertTrue(refusal.getMessage().contains("compaction-threshold"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("a-model-nobody-serves"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("studio"), refusal.getMessage());
  }

  /**
   * A fold threshold of zero stops the boot, and the refusal says how to ask for the default.
   *
   * <p>Zero folds a conversation at every turn it can fold at, buying a summary of a summary for
   * ever; removing the key is what asks for the derived default, and an operator who wrote a nought
   * meant something else.
   */
  @Test
  void a_fold_threshold_of_zero_stops_the_boot() {
    PoolProperties pool = validPool();
    pool.setCompactionThresholds(Map.of("nomic-embed-text", 0));

    IllegalStateException refusal = refused(pool);
    assertTrue(refusal.getMessage().contains("compaction-threshold"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("must be positive"), refusal.getMessage());
  }

  /**
   * The in-turn fold threshold is checked like the between-turn one: a model the pool serves, and a
   * positive number (spec 2026-09-30-fold-at-60-and-80 §1).
   */
  @Test
  void an_in_turn_fold_threshold_for_a_model_the_pool_does_not_serve_stops_the_boot() {
    PoolProperties pool = validPool();
    pool.setCompactionNowThresholds(Map.of("a-model-nobody-serves", 40000));

    IllegalStateException refusal = refused(pool);
    assertTrue(refusal.getMessage().contains("compaction-now-threshold"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("a-model-nobody-serves"), refusal.getMessage());
  }

  @Test
  void an_in_turn_fold_threshold_of_zero_stops_the_boot() {
    PoolProperties pool = validPool();
    pool.setCompactionNowThresholds(Map.of("nomic-embed-text", 0));

    IllegalStateException refusal = refused(pool);
    assertTrue(refusal.getMessage().contains("compaction-now-threshold"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("must be positive"), refusal.getMessage());
  }

  /**
   * "Now" must be above "due": a fold that runs inside the turn before it is even due between turns
   * is two thresholds the wrong way round.
   */
  @Test
  void an_in_turn_fold_threshold_not_above_the_due_one_stops_the_boot() {
    PoolProperties pool = validPool();
    pool.setCompactionThresholds(Map.of("nomic-embed-text", 90000));
    pool.setCompactionNowThresholds(Map.of("nomic-embed-text", 90000));

    IllegalStateException refusal = refused(pool);
    assertTrue(refusal.getMessage().contains("must be above"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("compaction-threshold"), refusal.getMessage());
  }

  /** Neither threshold may pass the context length where the pool says what that is. */
  @Test
  void a_fold_threshold_above_a_configured_context_length_stops_the_boot() {
    PoolProperties due = validPool();
    due.setContextLengths(Map.of("nomic-embed-text", 131072));
    due.setCompactionThresholds(Map.of("nomic-embed-text", 140000));
    PoolProperties now = validPool();
    now.setContextLengths(Map.of("nomic-embed-text", 131072));
    now.setCompactionNowThresholds(Map.of("nomic-embed-text", 140000));

    IllegalStateException dueRefused = refused(due);
    IllegalStateException nowRefused = refused(now);
    assertTrue(dueRefused.getMessage().contains("context-length"), dueRefused.getMessage());
    assertTrue(nowRefused.getMessage().contains("context-length"), nowRefused.getMessage());
  }

  /**
   * Where the context length is configured the derived other half is known at boot, so a configured
   * "now" at or under the derived "due" is refused too: 80 000 against a 131 072 window whose fold
   * is due at 87 895.
   */
  @Test
  void an_in_turn_fold_threshold_under_the_derived_due_one_stops_the_boot() {
    PoolProperties pool = validPool();
    pool.setContextLengths(Map.of("nomic-embed-text", 131072));
    pool.setCompactionNowThresholds(Map.of("nomic-embed-text", 80000));

    IllegalStateException refusal = refused(pool);
    assertTrue(refusal.getMessage().contains("87895"), refusal.getMessage());
  }

  /**
   * Both thresholds, the right way round and inside the window, start, and the in-turn one binds
   * under its own key and reaches the dispatcher.
   */
  @Test
  void fold_thresholds_the_right_way_round_start() {
    runner
        .withPropertyValues(
            onePoolPlus(
                "plowshare.llm.pools[0].context-lengths[nomic-embed-text]=131072",
                "plowshare.llm.pools[0].compaction-thresholds[nomic-embed-text]=60000",
                "plowshare.llm.pools[0].compaction-now-thresholds[nomic-embed-text]=100000"))
        .run(
            context ->
                assertEquals(
                    java.util.OptionalInt.of(100000),
                    context
                        .getBean(LlmDispatcher.class)
                        .compactionNowThreshold("nomic-embed-text")));
  }

  /**
   * The absent case of the stream's only total bound, split from the non-positive one because
   * "non-positive" is untrue of a missing value.
   */
  @Test
  void a_null_max_stream_duration_stops_the_boot() {
    PoolProperties pool = validPool();
    pool.setMaxStreamDuration(null);

    IllegalStateException refusal = refused(pool);
    assertTrue(refusal.getMessage().contains("studio"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("max-stream-duration"), refusal.getMessage());
  }

  /**
   * A floor in the pool is not validation. {@code LlmPool.lane} clamps a non-positive slot count up
   * to one, so without this the server would serve traffic on a lane the operator believed they had
   * disabled.
   */
  @Test
  void a_pool_sized_to_zero_stops_the_boot() {
    runner
        .withPropertyValues(onePoolPlus("plowshare.llm.pools[0].chat=0"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("studio"), message);
              // Says what to do instead, because "must be at least 1" on
              // its own leaves an operator who wanted the lane off with
              // no next move.
              assertTrue(message.contains("remove it"), message);
            });
  }

  /**
   * The other lane, separately: the two are checked by one condition today, and one condition split
   * in half is how the second half stops being checked.
   */
  @Test
  void an_embedding_lane_sized_to_zero_stops_the_boot() {
    runner
        .withPropertyValues(onePoolPlus("plowshare.llm.pools[0].embedding=0"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              // The slot counts, and not merely the pool name: this test
              // exists because one condition covers both lanes, and only
              // the numbers say which lane was refused.
              assertTrue(message.contains("0 embedding slot(s)"), message);
            });
  }

  @Test
  void omitted_swarm_uses_half_the_chat_slots_and_routes_reasoning_to_the_scheduler() {
    for (int chat : List.of(1, 2, 7, 8)) {
      runner
          .withPropertyValues(
              onePoolPlus(
                  "plowshare.llm.pools[0].chat=" + chat,
                  "plowshare.llm.pools[0].models[1]=test-reasoning",
                  "plowshare.llm.classes.reasoning=test-reasoning"))
          .run(
              context -> {
                assertNull(context.getStartupFailure());
                LlmDispatcher dispatcher = context.getBean(LlmDispatcher.class);
                assertEquals(chat / 2, dispatcher.pools().get(0).swarmSlots());
                var swarm = new io.aeyer.plowshare.server.swarm.DispatcherPools(dispatcher);
                assertEquals(chat / 2, swarm.slots("studio"));
                assertEquals(chat > 1 ? List.of("studio") : List.of(), swarm.serving("reasoning"));
              });
    }
  }

  @Test
  void explicit_zero_swarm_keeps_a_multi_slot_pool_out_of_the_scheduler() {
    runner
        .withPropertyValues(
            onePoolPlus("plowshare.llm.pools[0].chat=8", "plowshare.llm.pools[0].swarm=0"))
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              LlmDispatcher dispatcher = context.getBean(LlmDispatcher.class);
              assertEquals(0, dispatcher.pools().get(0).swarmSlots());
              var swarm = new io.aeyer.plowshare.server.swarm.DispatcherPools(dispatcher);
              assertTrue(swarm.all().isEmpty());
            });
  }

  /**
   * The ceiling sits below chat, so a person's live turn always has a slot the swarm cannot take —
   * spec 2026-09-29 §5. Equal is refused, not only above.
   */
  @Test
  void swarm_slots_as_many_as_chat_stop_the_boot() {
    runner
        .withPropertyValues(
            onePoolPlus("plowshare.llm.pools[0].chat=4", "plowshare.llm.pools[0].swarm=4"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("studio"), message);
              assertTrue(message.contains("4 swarm slot(s)"), message);
              assertTrue(message.contains("below its 4 chat"), message);
            });
  }

  @Test
  void negative_swarm_slots_stop_the_boot() {
    runner
        .withPropertyValues(onePoolPlus("plowshare.llm.pools[0].swarm=-1"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              assertTrue(trail(context.getStartupFailure()).contains("-1 swarm slot(s)"));
            });
  }

  @Test
  void swarm_slots_below_chat_start_and_reach_the_pool() {
    runner
        .withPropertyValues(
            onePoolPlus("plowshare.llm.pools[0].chat=8", "plowshare.llm.pools[0].swarm=5"))
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              LlmDispatcher dispatcher = context.getBean(LlmDispatcher.class);
              assertEquals(5, dispatcher.pools().get(0).swarmSlots());
            });
  }

  /**
   * And for the stated reason, which the plan's version of this test did not check.
   *
   * <p>{@code assertNotNull(getStartupFailure())} alone passes with this guard deleted — measured —
   * because a pool that declares nothing serves the embedding model either, so {@code
   * requireServed} refuses the boot a few lines later. That is a true refusal with the wrong
   * account: it sends an operator to look at {@code embedding-model}, which is correct, rather than
   * at the pool that declares no model at all. Asserting the phrase is what separates the two.
   *
   * <p><b>The check is {@code models} alone, and used to be "neither models nor classes".</b> A
   * pool has no classes of its own since 2026-09-07: {@code plowshare.llm.classes} is one map for
   * the server, and a class reaches a pool only through a model that pool lists. So a pool with no
   * models is unreachable outright, and the second half of the old condition could never have been
   * the thing that saved it.
   */
  @Test
  void a_pool_that_declares_no_models_stops_the_boot() {
    runner
        .withPropertyValues(
            "plowshare.llm.embedding-model=nomic-embed-text",
            "plowshare.llm.pools[0].name=studio",
            "plowshare.llm.pools[0].base-url=http://localhost:1234/v1")
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("studio"), message);
              assertTrue(message.contains("declares no models"), message);
            });
  }

  /**
   * A pool that declares vision for a model it does not serve stops the boot.
   *
   * <h2>Why this is a refusal and not a shrug</h2>
   *
   * <p>A misspelt entry binds cleanly. Nothing routes through it, so the capability is simply
   * absent — and {@code LlmDispatcher.requireSees} then goes on refusing every agent that needs
   * vision, while the operator is looking at a configuration file that plainly grants it. The typo
   * and the refusal are in two different files and nothing connects them.
   *
   * <p>The same shape as the blank-model check beside it, one step further: that one catches a name
   * no request could resolve to, this one catches a capability attached to a name this pool has
   * never heard of.
   */
  @Test
  void a_pool_declaring_vision_for_a_model_it_does_not_serve_stops_the_boot() {
    runner
        .withPropertyValues(onePoolPlus("plowshare.llm.pools[0].vision[0]=gemma-4-e4b"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("studio"), message);
              assertTrue(message.contains("gemma-4-e4b"), message);
              assertTrue(message.contains("vision"), message);
            });
  }

  /**
   * And a pool declaring it for a model it does serve boots, which is the assertion a check that
   * refused everything could not pass.
   */
  @Test
  void a_pool_declaring_vision_for_a_model_it_serves_boots() {
    runner
        .withPropertyValues(onePoolPlus("plowshare.llm.pools[0].vision[0]=nomic-embed-text"))
        .run(
            context ->
                assertNull(context.getStartupFailure(), () -> trail(context.getStartupFailure())));
  }

  /**
   * A class is a name no endpoint answers to, so a class with no model behind it routes a request
   * to a {@code model} field it cannot fill. On the one server-wide map now, which is where the key
   * lives.
   */
  @Test
  void a_class_mapped_to_no_model_stops_the_boot() {
    runner
        .withPropertyValues(onePoolPlus("plowshare.llm.classes.fast="))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("plowshare.llm.classes"), message);
              assertTrue(message.contains("fast"), message);
            });
  }

  /**
   * A class naming a model no pool serves stops the boot, and says which class, which model, and
   * what the pools actually serve.
   *
   * <h2>The check the move buys, and it could not be expressed before</h2>
   *
   * <p>While {@code classes} was per pool, this configuration was not a fault at all — it was a
   * pool that never resolved that class, and one pool's map said nothing about what another pool
   * served. The fault surfaced at the first request naming the class, as {@code
   * UnknownSpecifierException}, however many hours after boot that happened to be. With one map it
   * is a statement about the whole server and is settled before the server starts.
   *
   * <p><b>And the report it replaces is worse than a late exception.</b> {@code AgentsConfig}
   * <em>disables</em> an agent whose model no pool serves rather than refusing the boot — it
   * refuses only for the three required agents — so the symptom of this configuration is one
   * quietly missing agent.
   *
   * <p>All three of class, model and pools are asserted separately because each names a different
   * fix: a typo in the class's value, a model the fleet no longer loads, or a pool whose {@code
   * models} was edited without the class being followed. The shipped {@code application.yml} spent
   * a day in exactly the third state — {@code 773c063} moved the pool's chat model default and left
   * both classes pointing at the old name — which is what this refusal would have caught at the
   * first boot.
   */
  @Test
  void a_class_naming_a_model_no_pool_serves_stops_the_boot() {
    runner
        .withPropertyValues(onePoolPlus("plowshare.llm.classes.fast=a-model-nobody-loads"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("fast"), message);
              assertTrue(message.contains("a-model-nobody-loads"), message);
              // The pools searched, by name and by what each one serves —
              // so an operator can see the mismatch rather than go and
              // compare two lists by eye.
              assertTrue(message.contains("studio"), message);
              assertTrue(message.contains("nomic-embed-text"), message);
            });
  }

  /**
   * A class that some pool serves and this one does not is an ordinary fleet, not a fault.
   *
   * <h2>Without this, the refusal above and the clause in {@code LlmPool.resolve} look like the
   * same rule</h2>
   *
   * <p>They are not, and conflating them would be the natural way to over-tighten this. {@code
   * requireClassesResolvable} asks whether <em>any</em> pool serves the model a class names; {@code
   * resolve} asks whether <em>this</em> pool does. A two-box fleet where one class points at each
   * box satisfies the first and fails the second on one pool per class, which is exactly what
   * routing is for.
   *
   * <p>So this boots: {@code fast} names a model only {@code studio} serves, {@code deep} names one
   * only {@code spark} serves, and neither pool serves the other's. A refusal written as "every
   * pool must serve every class" would refuse the shipped configuration.
   */
  @Test
  void a_class_only_one_pool_serves_is_not_a_boot_failure() {
    runner
        .withPropertyValues(
            "plowshare.llm.embedding-model=nomic-embed-text",
            "plowshare.llm.embedding-dim=768",
            "plowshare.llm.embedding-max-input-tokens=1536",
            "plowshare.llm.default-context-length=64000",
            "plowshare.llm.classes.fast=model-here",
            "plowshare.llm.classes.deep=model-there",
            "plowshare.llm.pools[0].name=studio",
            "plowshare.llm.pools[0].base-url=http://localhost:1234/v1",
            "plowshare.llm.pools[0].models[0]=nomic-embed-text",
            "plowshare.llm.pools[0].models[1]=model-here",
            "plowshare.llm.pools[1].name=spark",
            "plowshare.llm.pools[1].base-url=http://localhost:30000/v1",
            "plowshare.llm.pools[1].models[0]=model-there")
        .run(
            context -> {
              assertNull(
                  context.getStartupFailure(),
                  "a class each pool cannot serve is a fleet, not a misconfiguration: "
                      + trail(context.getStartupFailure()));
              LlmDispatcher dispatcher = context.getBean(LlmDispatcher.class);
              assertEquals("model-here", dispatcher.wireModelFor("fast"));
              assertEquals("model-there", dispatcher.wireModelFor("deep"));
            });
  }

  /**
   * A class whose NAME some pool lists as a model stops the boot: the second door into the defect
   * this whole change exists to close.
   *
   * <h2>Two pools, and that is the entire reason it was missed</h2>
   *
   * <p>The two tests that already pin model-over-class precedence — {@code
   * a_bare_model_name_resolves_without_consulting_the_class_map} and {@code
   * a_model_name_wins_over_a_class_of_the_same_name} — both build <b>one pool</b>. With one pool
   * the precedence is the whole story: the model wins, the class never fires, and the configuration
   * is merely dead. It takes a second pool for the collision to mean anything, and then it means
   * the original bug. {@code studio} does not list {@code qwen3.5-9b}, so it resolves that
   * specifier through the class map to {@code qwen3.8-27b}; {@code spark} does list it, so it
   * resolves the same specifier to {@code qwen3.5-9b}. One specifier, two wire models, and {@code
   * LlmDispatcher.route} choosing between them on queue depth.
   *
   * <p><b>And the shape is the natural one, not a contrivance.</b> Aliasing a retired model name
   * onto its replacement is what an operator reaches for when a box stops loading the old model but
   * agent definitions and saved requests still name it — which is precisely the migration {@code
   * 773c063} was in the middle of.
   *
   * <p>The refusal is asserted to name the class, both outcomes and the pool that listed it,
   * because the operator's fix depends on which they meant: if the alias was the point, the pool
   * listing the old name is the thing to edit; if the pool's list was the point, the class is.
   *
   * <p><b>The mutation:</b> delete the {@code listAsModel} refusal from {@code
   * requireClassesResolvable} and this goes red — measured, and it is the only test that does.
   */
  @Test
  void a_class_named_after_a_model_another_pool_serves_stops_the_boot() {
    runner
        .withPropertyValues(
            "plowshare.llm.embedding-model=nomic-embed-text",
            "plowshare.llm.embedding-dim=768",
            "plowshare.llm.embedding-max-input-tokens=1536",
            "plowshare.llm.default-context-length=64000",
            "plowshare.llm.classes.qwen3.5-9b=qwen3.8-27b",
            "plowshare.llm.pools[0].name=studio",
            "plowshare.llm.pools[0].base-url=http://localhost:1234/v1",
            "plowshare.llm.pools[0].models[0]=nomic-embed-text",
            "plowshare.llm.pools[0].models[1]=qwen3.8-27b",
            "plowshare.llm.pools[1].name=spark",
            "plowshare.llm.pools[1].base-url=http://localhost:30000/v1",
            "plowshare.llm.pools[1].models[0]=qwen3.5-9b")
        .run(
            context -> {
              assertNotNull(
                  context.getStartupFailure(),
                  "two pools answer 'qwen3.5-9b' with different wire models and the"
                      + " dispatcher picks between them on load; that is the fault"
                      + " plowshare.llm.classes was created to make impossible");
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("qwen3.5-9b"), message);
              assertTrue(message.contains("qwen3.8-27b"), message);
              assertTrue(message.contains("spark"), message);
              // Both readings named, because which one is dead and which
              // is ambiguous depends on the rest of the fleet, and the
              // operator has to be told which of the two keys to edit.
              assertTrue(message.contains("dead"), message);
              assertTrue(message.contains("ambiguous"), message);
            });
  }

  /**
   * When a class is both named after a model and pointed at a model nobody serves, the collision is
   * what it is refused for.
   *
   * <h2>Written because the ordering was an argument in a comment and nothing could break it</h2>
   *
   * <p>Both refusals apply to the configuration below, so which one an operator reads is decided
   * purely by the order the two checks sit in, and that order is a decision: a class whose name is
   * already a model is dead whether or not its value is served — the pool listing the name answers
   * with the model and the class map is never reached — so "remove this key" is the fix either way.
   * Reporting the value first would send someone to correct a model name on an entry that could
   * never have fired, and they would fix it and hit the collision on the next boot.
   *
   * <p>Asserted negatively as well as positively, because "the message mentions the class" is true
   * of both refusals and would pass whichever fired.
   *
   * <p><b>The mutation:</b> move the {@code listAsModel} block below the {@code servedAnywhere}
   * block in {@code requireClassesResolvable} and this goes red while every other test stays green
   * — which is the whole of what that comment claims.
   */
  @Test
  void a_class_named_after_a_model_is_refused_for_the_collision_not_for_its_value() {
    runner
        .withPropertyValues(
            onePoolPlus(
                "plowshare.llm.pools[0].models[1]=qwen3.5-9b",
                "plowshare.llm.classes.qwen3.5-9b=a-model-nobody-loads"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("already list in `models`"), message);
              assertFalse(
                  message.contains("which no pool serves"),
                  "the value is indeed unserved, but the key could never have fired"
                      + " whatever its value; naming the value sends the operator"
                      + " to fix the wrong half: "
                      + message);
            });
  }

  /**
   * A class named {@code system}, or shaped like {@code system.<type>}, stops the boot: {@code
   * LlmDispatcher.resolveSpecifier} intercepts both shapes ahead of {@code LlmPool.resolve}, so a
   * class of that name could never be reached by an agent naming it — every such request is
   * redirected through {@code plowshare.llm.system} instead, silently, which is exactly the "boots
   * clean, dies at the call" shape the third refusal above already treats as unacceptable for a
   * model-name collision.
   *
   * <p>Both shapes are asserted because they are two different string comparisons in {@code
   * reservedForSystemIndirection} — {@code equals} and {@code startsWith} — and a fix that only
   * added one of them would leave this suite green on whichever it happened to test.
   */
  @Test
  void a_class_named_system_or_shaped_like_system_dot_type_stops_the_boot() {
    runner
        .withPropertyValues(
            onePoolPlus(
                "plowshare.llm.pools[0].models[1]=qwen3.8-27b",
                "plowshare.llm.classes.system=qwen3.8-27b"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("'system'"), message);
              assertTrue(message.contains("reserved"), message);
            });

    runner
        .withPropertyValues(
            onePoolPlus(
                "plowshare.llm.pools[0].models[1]=qwen3.8-27b",
                "plowshare.llm.classes.system.compaction=qwen3.8-27b"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("system.compaction"), message);
              assertTrue(message.contains("reserved"), message);
            });
  }

  /**
   * A pool model named {@code system} stops the boot even with no {@code classes} map at all.
   *
   * <h2>Why this cannot live inside the {@code classes.isEmpty()} guard</h2>
   *
   * <p>The three refusals above all fire out of {@code classes.forEach}, which never runs when
   * nothing is declared under {@code plowshare.llm.classes} — correctly, since a class map has
   * nothing to say about a fleet that has none. A model named {@code system} is a different fault:
   * {@code LlmDispatcher.resolveSpecifier} intercepts the specifier before consulting {@code
   * classes} OR a pool's {@code models}, so the model is unreachable by that name whether or not
   * any class exists to collide with it. This fleet declares no classes at all, and the boot must
   * still fail.
   */
  @Test
  void a_pool_model_named_system_stops_the_boot_with_no_classes_declared() {
    runner
        .withPropertyValues(
            "plowshare.llm.embedding-model=nomic-embed-text",
            "plowshare.llm.embedding-dim=768",
            "plowshare.llm.embedding-max-input-tokens=1536",
            "plowshare.llm.default-context-length=64000",
            "plowshare.llm.pools[0].name=studio",
            "plowshare.llm.pools[0].base-url=http://localhost:1234/v1",
            "plowshare.llm.pools[0].models[0]=nomic-embed-text",
            "plowshare.llm.pools[0].models[1]=system")
        .run(
            context -> {
              assertNotNull(
                  context.getStartupFailure(),
                  "no classes are declared, so only the reserved-name refusal on"
                      + " pool models can be what stops this boot");
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("studio"), message);
              assertTrue(message.contains("'system'"), message);
              assertTrue(message.contains("reserved"), message);
            });
  }

  /**
   * A pool still carrying the retired {@code classes} key stops the boot and says where the key
   * went.
   *
   * <h2>Refused rather than ignored, which is the decision worth testing</h2>
   *
   * <p>Ignoring it is the cheap option and it is the one this repository keeps deleting: an
   * operator who upgrades without moving the key gets a server whose classes resolve to nothing,
   * whose {@code application.yml} still reads correctly, and whose only symptom is every agent
   * naming {@code fast} failing at its first call.
   *
   * <p>Deleting the setter on {@code PoolProperties} would also refuse it — {@code LlmProperties}
   * is {@code ignoreUnknownFields = false} — but as a binder error naming the property and nothing
   * else. That is why the field is kept and refused here instead, and why this asserts the <em>new
   * location</em> rather than merely that something failed: an "unknown key" message and this one
   * both stop the boot, and only one of them ends the operator's afternoon.
   */
  @Test
  void a_per_pool_classes_key_stops_the_boot_and_names_where_it_moved_to() {
    runner
        .withPropertyValues(
            onePoolPlus(
                "plowshare.llm.pools[0].models[1]=qwen3.8-27b",
                "plowshare.llm.pools[0].classes.fast=qwen3.8-27b"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("studio"), message);
              assertTrue(message.contains("plowshare.llm.classes"), message);
              assertTrue(message.contains("fast"), message);
            });
  }

  /**
   * The total a streaming call may take, and a stream's only total bound: {@code okhttp-sse}
   * switches the call timeout off once the response opens, and zero fails every stream on its first
   * event.
   */
  @Test
  void a_non_positive_max_stream_duration_stops_the_boot() {
    runner
        .withPropertyValues(onePoolPlus("plowshare.llm.pools[0].max-stream-duration=0s"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("studio"), message);
              assertTrue(message.contains("max-stream-duration"), message);
            });
  }

  /**
   * The reachable half of the submit budget, and the plan had no check for it.
   *
   * <p>{@code -1s} binds cleanly to {@code PT-1S}, and {@code LlmPool.submit} then waits a
   * non-positive number of milliseconds for its start latch — which returns at once, on an idle
   * pool as readily as on a busy one. Every request is shed as saturated by a host doing nothing.
   *
   * <p>Written as {@code -1s} and not {@code 0s} deliberately: an empty {@code submit-timeout:} was
   * measured to bind to the field's 30s default rather than to null, so a test that reached this
   * guard by accident would prove nothing about which value it refuses.
   */
  @Test
  void a_non_positive_submit_timeout_stops_the_boot() {
    runner
        .withPropertyValues(onePoolPlus("plowshare.llm.pools[0].submit-timeout=-1s"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("studio"), message);
              assertTrue(message.contains("submit-timeout"), message);
            });
  }

  /**
   * The null half, which no YAML can produce and which is refused anyway.
   *
   * <p>Called directly rather than through the runner because that is the only way to reach the
   * state: Spring's binder skips the setter for a value it resolves as null, so an empty key leaves
   * the 30s default in place. A {@code PoolProperties} built in code is what actually carries a
   * null here — which is how {@code TransactionBoundaryTest} builds one — and {@code LlmPool}'s
   * constructor javadoc names this class as what refuses it.
   */
  @Test
  void a_null_submit_timeout_stops_the_boot() {
    PoolProperties pool = new PoolProperties();
    pool.setName("studio");
    pool.setBaseUrl("http://localhost:1234/v1");
    pool.setModels(List.of("nomic-embed-text"));
    pool.setSubmitTimeout(null);

    LlmProperties props = new LlmProperties();
    props.setEmbeddingModel("nomic-embed-text");
    props.setPools(List.of(pool));

    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> new LlmConfig().llmDispatcher(props, new ObjectMapper(), new NoOpTokenLedger()));
    assertTrue(refused.getMessage().contains("studio"), refused.getMessage());
    assertTrue(refused.getMessage().contains("submit-timeout"), refused.getMessage());
  }

  /**
   * The three read timeouts, which were the ones nothing checked.
   *
   * <p>Zero is not "no bound" here even though that is what OkHttp reads it as, and that is the
   * whole reason it has to be refused rather than passed through. {@code chat-timeout: 0} and
   * {@code embedding-timeout: 0} are wrapped in {@code callCeiling(...)}, which adds two
   * connect-and-write budgets — so the call is bounded at twenty seconds, a number the operator did
   * not write and cannot find in {@code application.yml}. {@code streaming-timeout: 0} is now
   * wrapped the same way — it used to be taken raw for both the read and the call timeout, and this
   * paragraph used to say so — which leaves the two halves of a stream bounded differently: the
   * connect and the wait for response headers get the same unwritten twenty seconds, and everything
   * after the response opens gets nothing, because {@code okhttp-sse} switches the call timeout off
   * there and the read timeout is gone. A stream that opens and falls silent waits out {@code
   * max-stream-duration} instead of failing on inactivity.
   */
  @Test
  void a_chat_timeout_of_zero_stops_the_boot() {
    runner
        .withPropertyValues(onePoolPlus("plowshare.llm.pools[0].chat-timeout=0s"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("studio"), message);
              assertTrue(message.contains("chat-timeout"), message);
            });
  }

  @Test
  void an_embedding_timeout_of_zero_stops_the_boot() {
    runner
        .withPropertyValues(onePoolPlus("plowshare.llm.pools[0].embedding-timeout=0s"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("studio"), message);
              assertTrue(message.contains("embedding-timeout"), message);
            });
  }

  /**
   * The negative case, and the one that is really about the <em>shape</em> of the refusal rather
   * than about whether the boot fails.
   *
   * <p>It failed before this guard existed: {@code OkHttpClient.Builder} rejects a negative
   * duration, from inside {@code new OpenAiTransport(...)}. But it fails as {@code
   * IllegalArgumentException: timeout &lt; 0} — no pool name, no property name, no unit, and
   * nothing to say which of the three timeout keys on which of however many declared hosts carries
   * the typo. That is precisely the shape of message this slice objects to in other people's
   * exceptions, so the assertions below are on the pool and the property and not merely on the
   * failure.
   */
  @Test
  void a_negative_streaming_timeout_is_refused_by_name_and_not_by_okhttp() {
    runner
        .withPropertyValues(onePoolPlus("plowshare.llm.pools[0].streaming-timeout=-1s"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("studio"), message);
              assertTrue(message.contains("streaming-timeout"), message);
            });
  }

  /**
   * The null half of the same three, on the terms {@code a_null_submit_timeout_stops_the_boot} sets
   * out: unreachable from any property source, because Spring's binder skips the setter for a value
   * it resolves as null, and reachable from a {@code PoolProperties} built in code — which is how
   * every test in this slice and {@code TransactionBoundaryTest} build one.
   */
  @Test
  void a_null_read_timeout_stops_the_boot() {
    PoolProperties pool = validPool();
    pool.setChatTimeout(null);
    IllegalStateException refused = refused(pool);
    assertTrue(refused.getMessage().contains("studio"), refused.getMessage());
    assertTrue(refused.getMessage().contains("chat-timeout"), refused.getMessage());
  }

  /**
   * {@code retry-max-attempts: 0}, which is the twin of a lane sized to zero.
   *
   * <p>{@code OpenAiTransport.executeWithRetry} floors it with {@code Math.max(1, ...)}, exactly as
   * {@code LlmPool.lane} floors a slot count, so nothing crashes and one attempt is made. The
   * operator who wrote it has asked for something — no call at all, or no retry — and been given
   * neither an error nor the thing they asked for. A floor is not validation, and the difference is
   * only visible from here.
   */
  @Test
  void retry_max_attempts_below_one_stops_the_boot() {
    runner
        .withPropertyValues(onePoolPlus("plowshare.llm.pools[0].retry-max-attempts=0"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("studio"), message);
              assertTrue(message.contains("retry-max-attempts"), message);
            });
  }

  /**
   * Negative only, because zero is a real setting here.
   *
   * <p>{@code retry-initial-backoff: 0ms} means retry without pausing, which is defensible and is
   * what the {@code Math.max(0L, ...)} at the call site silently turns a negative into as well.
   * Refusing the negative is the whole of the difference: it is a typo that currently produces
   * working behaviour under a value nobody wrote.
   */
  /**
   * The boot summary, against a key written into the base URL rather than into {@code api-key}.
   *
   * <p>{@code a_key_never_reaches_the_boot_log} sets {@code api-key} and so tested the one field
   * every guard already watched. This is the shape that actually leaked: {@code
   * PoolProperties.toString} allowlists name and base URL, {@code LlmConfig} logs the bound pool
   * list at INFO, and a base URL is allowed to carry {@code user:password@}. Every guard was
   * individually correct and the credential reached the log on a healthy start.
   */
  @Test
  void a_key_written_into_the_base_url_never_reaches_the_boot_log() {
    Logger configLog = (Logger) LoggerFactory.getLogger(LlmConfig.class);
    ListAppender<ILoggingEvent> captured = new ListAppender<>();
    captured.start();
    Level inherited = configLog.getLevel();
    configLog.setLevel(Level.INFO);
    configLog.addAppender(captured);
    try {
      runner
          .withPropertyValues(
              "plowshare.llm.embedding-model=nomic-embed-text",
              "plowshare.llm.embedding-dim=768",
              "plowshare.llm.embedding-max-input-tokens=1536",
              "plowshare.llm.default-context-length=64000",
              "plowshare.llm.pools[0].name=studio",
              "plowshare.llm.pools[0].base-url=http://someone:" + KEY + "@localhost:1234/v1",
              "plowshare.llm.pools[0].models[0]=nomic-embed-text")
          .run(
              context -> {
                assertNull(context.getStartupFailure());
                assertTrue(
                    captured.list.stream()
                        .anyMatch(
                            event -> event.getFormattedMessage().startsWith("LLM dispatcher:")),
                    "the boot summary was never logged, so this test read nothing");
                for (ILoggingEvent event : captured.list) {
                  String rendered =
                      event.getFormattedMessage() + " " + Arrays.toString(event.getArgumentArray());
                  assertFalse(
                      rendered.contains(KEY),
                      "a boot log line carried a key from the base-url: " + rendered);
                }
              });
    } finally {
      configLog.detachAppender(captured);
      configLog.setLevel(inherited);
      captured.stop();
    }
  }

  /**
   * The boot validated a different string than the transport calls.
   *
   * <p>{@code validate} parsed {@code base-url}; {@code OpenAiTransport.url} parses {@code base-url
   * + path}. A trailing space parses alone and then POSTs to {@code /v1%20/embeddings}; a {@code
   * #fragment} parses alone and then swallows the appended path. Both 404, and a 404 on the write
   * path is swallowed by {@code Archive.embed} so the memory survives — so the server boots,
   * accepts every write, embeds none and recalls nothing. That is the outcome the base-url check
   * exists to prevent, through the door it did not cover.
   */
  @Test
  void a_base_url_that_is_unusable_as_a_prefix_stops_the_boot() {
    for (String bad :
        List.of(
            "http://localhost:1234/v1 ",
            "http://localhost:1234/v1#frag",
            "http://localhost:1234/v1?k=v")) {
      PoolProperties pool = validPool();
      pool.setBaseUrl(bad);
      IllegalStateException refused = refused(pool);
      assertTrue(refused.getMessage().contains("studio"), refused.getMessage());
      assertTrue(refused.getMessage().contains("base-url"), refused.getMessage());
    }
  }

  /**
   * And the fragment case reached through a real property source, because the three above are
   * asserted against {@code validate} directly.
   *
   * <p>Only two of the three are reachable that way. Measured while writing this: Spring's binder
   * trims trailing whitespace from a property value, so {@code base-url=http://host/v1 } binds
   * without the space and booted cleanly — the guard is right and the property source had already
   * repaired the input. The whitespace half therefore arrives only from a {@code PoolProperties}
   * built in code, which is how {@code TransactionBoundaryTest} builds one, and is kept on the same
   * terms as the null-timeout checks.
   */
  @Test
  void a_base_url_with_a_fragment_stops_the_boot_through_a_property_source() {
    runner
        .withPropertyValues(
            onePoolPlus("plowshare.llm.pools[0].base-url=http://localhost:1234/v1#f"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("studio"), message);
              assertTrue(message.contains("base-url"), message);
            });
  }

  @Test
  void a_negative_retry_initial_backoff_stops_the_boot() {
    runner
        .withPropertyValues(onePoolPlus("plowshare.llm.pools[0].retry-initial-backoff=-1ms"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("studio"), message);
              assertTrue(message.contains("retry-initial-backoff"), message);
            });
  }

  /**
   * {@code embedding-dim: 0} is not merely nonsensical.
   *
   * <p>It produces a mismatch message asserting that {@code vector(0)} is the schema's width, which
   * is false, and it arrives as an {@code EmbeddingException} — so the caller is told to wait for
   * an endpoint that is not the problem.
   */
  @Test
  void a_non_positive_embedding_dim_stops_the_boot() {
    runner
        .withPropertyValues(onePoolPlus("plowshare.llm.embedding-dim=0"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              assertTrue(
                  trail(context.getStartupFailure()).contains("embedding-dim"),
                  trail(context.getStartupFailure()));
            });
  }

  /**
   * The input-side twin, and zero here is not "no ceiling".
   *
   * <p>{@code embedding-dim} guards the width of what comes back; this key guards the size of what
   * goes out, which until it existed nothing guarded at all. A server with no ceiling sends
   * whatever it is handed to a model loaded at 2048 tokens, and {@code implementation rationale}
   * records that nobody knows whether a longer input is "truncated or refused" — so the good case
   * is an error and the bad case is a vector for text that is not the text, on a row that looks
   * perfect. Nothing downstream can report that, which is why it is refused before the server
   * starts.
   */
  @Test
  void a_non_positive_embedding_input_ceiling_stops_the_boot() {
    runner
        .withPropertyValues(onePoolPlus("plowshare.llm.embedding-max-input-tokens=0"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("embedding-max-input-tokens"), message);
              assertTrue(message.contains("truncated"), message);
            });
  }

  /**
   * {@code default-context-length: 0} is refused for the same kind of reason, and the damage it
   * does is the opposite one.
   *
   * <p>This key is the bottom tier of context-length resolution, so it is what answers whenever an
   * operator configured nothing and the node could not be asked. A bound of zero is under every
   * prompt any conversation can send, so every conversation folds on its first turn, for ever: a
   * summarising call bought on every utterance and a history that is thrown away as fast as it is
   * written. Nothing downstream can report that as a configuration fault — a fold that fires is not
   * an error — so this is the only place an operator is told.
   *
   * <p>Negative is refused by the same check and needs no case of its own: both are "not a length",
   * and the message names the key and the value.
   */
  @Test
  void a_non_positive_default_context_length_stops_the_boot() {
    runner
        .withPropertyValues(onePoolPlus("plowshare.llm.default-context-length=0"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              assertTrue(
                  trail(context.getStartupFailure()).contains("default-context-length"),
                  trail(context.getStartupFailure()));
            });
  }

  /**
   * The other half of the same rule, and it needs a boot that <em>succeeds</em>.
   *
   * <p>{@code no_refusal_ever_prints_a_key} below cannot cover the log line: it fails the boot at
   * {@code requireServed}, which runs before the summary is written, so the summary never executes
   * under it. Measured — mutating that line to interpolate {@code getApiKey()} left the entire
   * suite green. This test is what notices, and it is deliberately not an assertion about one
   * string: it reads every event {@code LlmConfig} logged, formatted message and argument array
   * both, because an argument beyond the last placeholder never reaches the formatted text and
   * would slip past a check that only read that.
   *
   * <p>What it does not replace is {@code
   * LlmPropertiesBindingTest.the_key_never_reaches_a_log_line_through_toString}. The summary logs
   * the pool <em>objects</em>, so today it is that allowlist doing the work. This test is what
   * would notice if someone later logged a pool's fields individually instead.
   */
  @Test
  void a_key_never_reaches_the_boot_log() {
    Logger configLog = (Logger) LoggerFactory.getLogger(LlmConfig.class);
    ListAppender<ILoggingEvent> captured = new ListAppender<>();
    captured.start();
    // The level is set here rather than inherited. This suite's root logger
    // comes from plowshare-client's logback.xml, and raising that to WARN
    // would silence the summary — failing this test with "the boot summary
    // was never logged", which blames LlmConfig for a change in a different
    // module. Restored below, since a Logger is process-wide.
    Level inherited = configLog.getLevel();
    configLog.setLevel(Level.INFO);
    configLog.addAppender(captured);
    try {
      runner
          .withPropertyValues(
              "plowshare.llm.embedding-model=nomic-embed-text",
              "plowshare.llm.embedding-dim=768",
              "plowshare.llm.embedding-max-input-tokens=1536",
              "plowshare.llm.default-context-length=64000",
              "plowshare.llm.pools[0].name=studio",
              "plowshare.llm.pools[0].base-url=http://localhost:1234/v1",
              "plowshare.llm.pools[0].api-key=" + KEY,
              "plowshare.llm.pools[0].models[0]=nomic-embed-text")
          .run(
              context -> {
                // The boot has to have succeeded, or the line under test
                // was never reached and this asserts nothing. That is the
                // failure shape this slice keeps finding: an assertion
                // placed where the thing it forbids could not have
                // happened yet.
                assertNull(context.getStartupFailure());
                assertNotNull(context.getBean(LlmDispatcher.class));
                assertTrue(
                    captured.list.stream()
                        .anyMatch(
                            event -> event.getFormattedMessage().startsWith("LLM dispatcher:")),
                    "the boot summary was never logged, so this test read nothing");
                for (ILoggingEvent event : captured.list) {
                  String rendered =
                      event.getFormattedMessage() + " " + Arrays.toString(event.getArgumentArray());
                  assertFalse(
                      rendered.contains(KEY),
                      "a boot log line carried the pool's key: " + rendered);
                }
              });
    } finally {
      configLog.detachAppender(captured);
      configLog.setLevel(inherited);
      captured.stop();
    }
  }

  /**
   * The one rule in this slice with no exceptions.
   *
   * <p>{@code LlmConfig} reads every pool's key to build its transport, and a refusal is exactly
   * the moment something wants to print what it was given. The pool's <em>name</em> is what a
   * failure names.
   *
   * <p>This covers what is <b>thrown</b> and only that; the logged half is {@code
   * a_key_never_reaches_the_boot_log} above, for the reason given there.
   */
  @Test
  void no_refusal_ever_prints_a_key() {
    runner
        .withPropertyValues(
            "plowshare.llm.embedding-model=bge-m3",
            "plowshare.llm.pools[0].name=studio",
            "plowshare.llm.pools[0].base-url=http://localhost:1234/v1",
            "plowshare.llm.pools[0].api-key=" + KEY,
            "plowshare.llm.pools[0].models[0]=nomic-embed-text")
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              assertFalse(
                  trail(context.getStartupFailure()).contains(KEY),
                  "a startup refusal printed the pool's key");
            });
  }

  @Test
  void a_system_binding_naming_a_model_no_pool_serves_stops_the_boot() {
    var props = new LlmProperties();
    props.setSystem("openai/gpt-oss-120b");
    var pool = new PoolProperties();
    pool.setName("studio");
    pool.setModels(java.util.List.of("mlx-community/gemma-4-e4b-it"));

    var failed =
        assertThrows(
            IllegalStateException.class,
            () -> LlmConfig.requireSystemResolvable(props, java.util.List.of(pool)));
    assertTrue(failed.getMessage().contains("plowshare.llm.system"), failed.getMessage());
    assertTrue(failed.getMessage().contains("openai/gpt-oss-120b"), failed.getMessage());
    assertTrue(failed.getMessage().contains("studio"), failed.getMessage());
  }

  @Test
  void a_system_binding_naming_a_declared_class_is_accepted() {
    var props = new LlmProperties();
    props.setClasses(java.util.Map.of("fast", "mlx-community/gemma-4-e4b-it"));
    props.setSystem("fast");
    var pool = new PoolProperties();
    pool.setName("studio");
    pool.setModels(java.util.List.of("mlx-community/gemma-4-e4b-it"));

    assertDoesNotThrow(() -> LlmConfig.requireSystemResolvable(props, java.util.List.of(pool)));
  }

  @Test
  void an_override_is_checked_as_well_as_the_binding_it_overrides() {
    var props = new LlmProperties();
    props.setClasses(java.util.Map.of("fast", "mlx-community/gemma-4-e4b-it"));
    props.setSystem("fast");
    props.setSystemOverrides(java.util.Map.of("compaction", "openai/gpt-oss-120b"));
    var pool = new PoolProperties();
    pool.setName("studio");
    pool.setModels(java.util.List.of("mlx-community/gemma-4-e4b-it"));

    var failed =
        assertThrows(
            IllegalStateException.class,
            () -> LlmConfig.requireSystemResolvable(props, java.util.List.of(pool)));
    assertTrue(failed.getMessage().contains("compaction"), failed.getMessage());
  }

  @Test
  void nothing_is_refused_when_no_harness_work_is_bound() {
    assertDoesNotThrow(
        () -> LlmConfig.requireSystemResolvable(new LlmProperties(), java.util.List.of()));
  }

  /**
   * A system override written with no value is refused by name, not by an uncaught {@code
   * NullPointerException}.
   *
   * <p>{@code plowshare.llm.system-overrides.compaction: ~} binds through {@code
   * setSystemOverrides} to a map holding a null value for {@code compaction} — {@code
   * setSystemOverrides} itself accepts a null value without complaint, since it only copies the map
   * into a fresh {@code LinkedHashMap}. It is the getter, {@code getSystemOverrides}, that calls
   * {@code Map.copyOf} and throws — the exact hazard {@link #requireClassesResolvable} already
   * guards against for {@code getClasses()}, and this is {@code requireSystemResolvable}'s twin of
   * that guard. Built the way the getClasses() case above builds it, bypassing the setter's own
   * null-tolerant copy so the null actually reaches the getter.
   */
  @Test
  void a_system_override_written_with_no_value_stops_the_boot_instead_of_crashing_with_an_npe() {
    var props = new LlmProperties();
    Map<String, String> overrides = new LinkedHashMap<>();
    overrides.put("compaction", null);
    props.setSystemOverrides(overrides);
    var pool = new PoolProperties();
    pool.setName("studio");
    pool.setModels(java.util.List.of("mlx-community/gemma-4-e4b-it"));

    IllegalStateException failed =
        assertThrows(
            IllegalStateException.class,
            () -> LlmConfig.requireSystemResolvable(props, java.util.List.of(pool)));
    assertTrue(failed.getMessage().contains("plowshare.llm.system-overrides"), failed.getMessage());
    assertTrue(failed.getMessage().contains("compaction: ~"), failed.getMessage());
    assertNotNull(failed.getCause(), "the getter's NullPointerException is the evidence");
  }

  /**
   * The refusal proven above as a pure function also fires when Spring is the one binding the
   * property and instantiating {@code LlmConfig}'s {@code llmDispatcher} bean — the shape {@code
   * a_class_naming_a_model_no_pool_serves_stops_the_boot} uses for {@code
   * requireClassesResolvable}. Without this, deleting the call at the dispatcher bean's
   * construction site would leave every test in this class about {@code requireSystemResolvable}
   * green.
   */
  @Test
  void a_system_binding_naming_an_unserved_model_stops_the_boot_through_the_context() {
    runner
        .withPropertyValues(onePoolPlus("plowshare.llm.system=a-model-nobody-loads"))
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              String message = trail(context.getStartupFailure());
              assertTrue(message.contains("plowshare.llm.system"), message);
              assertTrue(message.contains("a-model-nobody-loads"), message);
              assertTrue(message.contains("studio"), message);
            });
  }

  @Test
  void the_retired_memory_model_key_is_refused_by_name_with_its_replacement() {
    var failed =
        assertThrows(
            IllegalStateException.class,
            () ->
                LlmConfig.requireMemoryModelRetired(
                    java.util.Map.of("plowshare.memory.model", "fast")));
    assertTrue(
        failed.getMessage().contains("plowshare.llm.system-overrides.memory"), failed.getMessage());
  }

  @Test
  void a_harness_profile_for_a_model_the_pool_does_not_serve_stops_the_boot() {
    PoolProperties pool = validPool();
    pool.setHarnessProfiles(Map.of("gpt-oss-120b", "guided"));

    IllegalStateException stopped = refused(pool);

    assertTrue(stopped.getMessage().contains("harness-profile"), stopped.getMessage());
    assertTrue(stopped.getMessage().contains("gpt-oss-120b"), stopped.getMessage());
    assertTrue(stopped.getMessage().contains("nomic-embed-text"), stopped.getMessage());
  }
}
