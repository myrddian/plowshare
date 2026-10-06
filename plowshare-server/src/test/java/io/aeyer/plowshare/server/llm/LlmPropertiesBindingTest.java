package io.aeyer.plowshare.server.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * What the YAML in the spec actually binds to.
 *
 * <p>No datasource and no Spring Boot application here — one properties class and the binder, which
 * is all that is under test. Nothing in this file opens a socket: the context runner wires {@link
 * Binding}, which is {@code @EnableConfigurationProperties} and nothing else, and the rest is
 * {@code PoolProperties} objects and their {@code toString}.
 *
 * <p><b>The host is 192.0.2.10, and it used to be the reference box's real address.</b> Every use
 * of it here is a string being bound, compared or printed — including the two redaction tests,
 * whose entire job is to contain a base URL and assert what {@code toString} does to it. The
 * slice's invariant is that <em>no test opens a connection to the box</em>, and these never did;
 * but the plan checked it by grepping {@code src/test} for that address, which fires on a string
 * that opens nothing and would miss a test reaching the box through a property file, an environment
 * variable or a hostname. The next person to run that grep would have found nine hits in a file
 * that is innocent, and the cheapest way to make it green would have been to delete a redaction
 * test. (The address is deliberately not written out anywhere in this comment either: a note
 * explaining the grep that then trips it is the same fault one level up.)
 *
 * <p>192.0.2.10 is TEST-NET-1 from RFC 5737, reserved for documentation and guaranteed not to
 * route. It serves every purpose the old value served — a host that is visibly not {@code
 * localhost} — and it takes the tension out of the grep, which now means what it says. It is still
 * not a check that anything opened a socket; see the plan's Step 6 for what that check would have
 * to be and why this repository has no instrument for it.
 */
class LlmPropertiesBindingTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(LlmProperties.class)
  static class Binding {}

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
          .withUserConfiguration(Binding.class);

  @Test
  void a_pool_binds_from_the_shape_the_spec_documents() {
    runner
        .withPropertyValues(
            "plowshare.llm.embedding-model=nomic-embed-text",
            // One map, for the server. It was pools[0].classes until
            // 2026-09-07; per pool a class could name two different
            // models and the dispatcher would pick between them on
            // queue depth.
            "plowshare.llm.classes.fast=qwen3.5-9b",
            "plowshare.llm.classes.reasoning=a-bigger-model-when-there-is-one",
            "plowshare.llm.pools[0].name=studio",
            "plowshare.llm.pools[0].base-url=http://192.0.2.10:1234/v1",
            "plowshare.llm.pools[0].models[0]=nomic-embed-text",
            "plowshare.llm.pools[0].models[1]=qwen3.5-9b",
            "plowshare.llm.pools[0].chat=1",
            "plowshare.llm.pools[0].embedding=2",
            "plowshare.llm.pools[0].submit-timeout=45s")
        .run(
            context -> {
              LlmProperties props = context.getBean(LlmProperties.class);
              assertEquals(1, props.getPools().size());
              PoolProperties pool = props.getPools().get(0);
              assertEquals("studio", pool.getName());
              assertEquals("http://192.0.2.10:1234/v1", pool.getBaseUrl());
              assertEquals(List.of("nomic-embed-text", "qwen3.5-9b"), pool.getModels());
              // A class maps to a wire model, and is not merely declared:
              // "fast" is not a name any endpoint answers to, so a list
              // here would leave nothing to put in the request body.
              assertEquals("qwen3.5-9b", props.getClasses().get("fast"));
              // Deliberately a name nothing serves: this asserts that the map
              // binds two distinct values, and there is exactly one chat model
              // on the reference box today. LlmConfig refuses a boot on it;
              // nothing here boots, and the binder is the subject.
              assertEquals("a-bigger-model-when-there-is-one", props.getClasses().get("reasoning"));
              // And the pool has none of its own. The key still binds — it
              // is kept so a configuration still setting it can be refused
              // with a message naming the new location — so this is the
              // assertion that says the YAML above put it in the right
              // place.
              assertEquals(Map.of(), pool.getClasses());
              assertEquals(1, pool.getChat());
              assertEquals(2, pool.getEmbedding());
              assertEquals(Duration.ofSeconds(45), pool.getSubmitTimeout());
            });
  }

  @Test
  void conservative_pool_defaults_bind_without_deployment_configuration() {
    runner
        .withPropertyValues(
            "plowshare.llm.pools[0].name=studio",
            "plowshare.llm.pools[0].base-url=http://localhost:1234/v1")
        .run(
            context -> {
              PoolProperties pool = context.getBean(LlmProperties.class).getPools().get(0);
              assertEquals(1, pool.getChat(), "the reference box generates one stream at a time");
              assertEquals(2, pool.getEmbedding());
              assertEquals(
                  0, pool.getSwarm(), "one chat slot cannot be shared without taking all capacity");
              assertEquals(Duration.ofSeconds(30), pool.getSubmitTimeout());
              // The remaining four are pinned because application.yml
              // prints all of them as commented defaults, and a comment
              // that has drifted from the code is worse than no comment.
              assertEquals(Duration.ofSeconds(60), pool.getChatTimeout());
              // 300 and not the 90 this shipped as. It bounds the silence
              // before the first chunk as well as between chunks, and a
              // 30 415-token prompt was measured prefilling for 119 seconds
              // in silence — so 90 failed a healthy call. See
              // PoolProperties#streamingTimeout for the arithmetic and for
              // what 300 still does not cover.
              assertEquals(Duration.ofSeconds(300), pool.getStreamingTimeout());
              assertEquals(Duration.ofMinutes(10), pool.getMaxStreamDuration());
              assertTrue(
                  pool.getStreamingTimeout().compareTo(pool.getMaxStreamDuration()) < 0,
                  "the inactivity bound has to stay under the total, or it is not a"
                      + " bound anyone reaches");
              assertEquals(Duration.ofSeconds(30), pool.getEmbeddingTimeout());
              assertEquals(2, pool.getRetryMaxAttempts());
              assertEquals(Duration.ofMillis(500), pool.getRetryInitialBackoff());
              assertEquals("", pool.getApiKey());
              // embedding-dim is deliberately NOT asserted here any more.
              // It used to be, and what it pinned was a field initializer
              // on LlmProperties that application.yml overrode on every
              // real boot — so this test was the only reader of a value no
              // running server ever used, and nothing tied it to the 768
              // the YAML actually ships. The two archive-wide keys are now
              // pinned where they live, by
              // the_archive_wide_defaults_come_from_the_shipped_yaml_and_nowhere_else
              // below, and their absence from this class by
              // the_class_carries_no_default_for_the_keys_the_yaml_owns.
            });
  }

  /**
   * {@link LlmProperties} answers with nothing for the three keys {@code application.yml} owns
   * until something binds them.
   *
   * <p><b>This is the assertion that keeps one default in one place.</b> Both keys used to be
   * written twice — a field initializer here and a {@code ${LLM_...:...}} placeholder in {@code
   * application.yml} — and the YAML won at every boot, because Spring binds over an initializer. So
   * the Java values were dead in the only way that matters: nothing compared them against the YAML,
   * nothing failed when they drifted apart, and removing a key from the YAML would have brought a
   * stale one silently back to life.
   *
   * <p>The empty answers below are therefore the contract and not a gap. A server is configured by
   * {@code application.yml}; a {@link LlmProperties} nothing has bound is a half-built object, and
   * {@code LlmConfig} refuses to start on all three of these — {@code embedding-dim} and {@code
   * default-context-length} for being non-positive, {@code embedding-model} for being blank — which
   * is what turns "no Java default" into a boot failure naming the key rather than an archive
   * embedded at width zero or a conversation that folds on every turn.
   */
  @Test
  void the_class_carries_no_default_for_the_keys_the_yaml_owns() {
    LlmProperties unbound = new LlmProperties();

    assertNull(
        unbound.getEmbeddingModel(),
        "a default here is a second source of truth for application.yml's own key");
    assertEquals(
        0,
        unbound.getEmbeddingDim(),
        "a default here is a second source of truth for application.yml's own key");
    assertEquals(
        0,
        unbound.getDefaultContextLength(),
        "a default here is a second source of truth for application.yml's own key");
    assertEquals(
        0,
        unbound.getEmbeddingMaxInputTokens(),
        "a default here is a second source of truth for application.yml's own key");
  }

  /**
   * The shipped {@code application.yml} is where the two archive-wide defaults live, and this is
   * what reads it.
   *
   * <p>Every other test in this file binds properties the runner is handed directly, which is the
   * right shape for asking what the binder does with a key. It is the wrong shape for asking what
   * the project's own defaults <em>are</em>: a runner with no property source loads no YAML at all,
   * so a test written that way can only ever see a Java field initializer — which is exactly the
   * thing that is no longer there.
   *
   * <p>{@link ConfigDataApplicationContextInitializer} is the difference. It runs Spring Boot's own
   * config-data step, so the file this repository ships is the property source and the assertions
   * below are about the deployment rather than about the class. {@code embedding-dim} must stay at
   * the {@code vector(n)} width in {@code V1__memories.sql}, and this is now the only place that
   * number is checked outside a migration.
   *
   * <p>{@code default-context-length} is pinned here for a different reason. It is the bottom tier
   * of the three a context length is resolved through, and the only one that answers when the other
   * two cannot — so a deployment that lost this key would have compaction switched off entirely,
   * which is the state the key was added to make impossible. 64 000 is the number the YAML argues
   * for at length; this is what stops it drifting silently.
   *
   * <p><b>What this pins and what it cannot: it is the test that should have caught the 2026-09-06
   * fault and did not.</b> It reads the real file Docker-free and exists to stop shipped defaults
   * drifting — and the fault was a shipped default drifting, {@code 773c063} moving the pool's chat
   * model and leaving {@code plowshare.llm.classes} pointing at the old name for a day. It was
   * invisible here because every assertion above is a <em>scalar</em> read alone, and the fault was
   * a <em>relation</em> between two keys that were each individually fine. Adding {@code classes}
   * to the list above would not have caught it either, for the same reason. See {@link
   * #generic_single_pool_routes_both_chat_classes}, which asserts the relation and is the guard
   * this one could not be.
   */
  @Test
  void the_archive_wide_defaults_come_from_the_shipped_yaml_and_nowhere_else() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(Binding.class)
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .run(
            context -> {
              LlmProperties props = context.getBean(LlmProperties.class);
              assertEquals(
                  "", props.getEmbeddingModel(), "a deployment must choose its embedding model");
              assertEquals(
                  768, props.getEmbeddingDim(), "must match vector(768) in V1__memories.sql");
              assertNull(
                  props.getFoldTimeout(), "folds retain the existing stream limits by default");
              assertNull(
                  props.getPromptTimeout(), "an absent global override retains pool defaults");
              assertEquals(
                  64000,
                  props.getDefaultContextLength(),
                  "the bottom tier of context-length resolution; see the key's own"
                      + " comment for why it is this number and not a larger one");
            });
  }

  /** A packaged configuration must never identify an operator's hardware or model fleet. */
  @Test
  void shipped_configuration_requires_explicit_models_and_endpoint() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(Binding.class)
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .run(
            context -> {
              LlmProperties props = context.getBean(LlmProperties.class);
              assertEquals(1, props.getPools().size());
              PoolProperties pool = props.getPools().get(0);
              assertEquals("primary", pool.getName());
              assertEquals("", pool.getBaseUrl());
              assertEquals(List.of("", ""), pool.getModels());
              assertEquals(PoolProperties.Provider.OPENAI, pool.getProvider());
              assertTrue(pool.getVision().isEmpty());
              assertTrue(pool.getContextLengths().isEmpty());
              assertEquals(0, pool.getSwarm());
              assertThrows(
                  IllegalStateException.class,
                  () ->
                      new LlmConfig()
                          .llmDispatcher(
                              props,
                              new com.fasterxml.jackson.databind.ObjectMapper(),
                              new io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger()));
            });
  }

  /** A single explicitly configured endpoint serves both classes without another host. */
  @Test
  void generic_single_pool_routes_both_chat_classes() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(Binding.class)
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withPropertyValues(
            "LLM_BASE_URL=http://192.0.2.10:9000/v1",
            "LLM_CHAT_MODEL=test-chat",
            "LLM_EMBEDDING_MODEL=test-embedding",
            "LLM_API_KEY=test-key")
        .run(
            context -> {
              LlmProperties props = context.getBean(LlmProperties.class);
              assertEquals(
                  Map.of("fast", "test-chat", "reasoning", "test-chat"), props.getClasses());
              assertEquals("test-key", props.getPools().get(0).getApiKey());
              try (var dispatcher =
                  new LlmConfig()
                      .llmDispatcher(
                          props,
                          new com.fasterxml.jackson.databind.ObjectMapper(),
                          new io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger())) {
                assertEquals("test-chat", dispatcher.wireModelFor("fast"));
                assertEquals("test-chat", dispatcher.wireModelFor("reasoning"));
                dispatcher.requireServed("test-embedding");
              }
            });
  }

  /**
   * A context length keyed by a real wire model name survives the binder intact — dots, capitals
   * and all.
   *
   * <p><b>This pins a measurement against a belief that is easy to hold and was held here.</b>
   * Spring's relaxed binding canonicalises property names, and the natural conclusion is that
   * {@code context-lengths.qwen3.5-9b: 64000} splits on the dot and never produces that key — which
   * is what {@code PoolProperties#contextLengths} and a boot refusal both asserted in their first
   * draft. Measured on Spring Boot 3.3.5: it does not. Both forms bind the key verbatim, and the
   * bare form keeps mixed case too. What is canonicalised is the path <em>to</em> the map, not a
   * key <em>within</em> a map of scalars.
   *
   * <p>Worth a test rather than a corrected sentence, because the failure either belief predicts is
   * silent: the pool binds, the boot succeeds, and the configured length is simply never found. If
   * a later Spring makes the old belief true, this is what says so.
   */
  @Test
  void a_context_length_binds_under_a_model_name_with_a_dot_in_it() {
    runner
        .withPropertyValues(
            "plowshare.llm.pools[0].name=studio",
            "plowshare.llm.pools[0].base-url=http://192.0.2.10:1234/v1",
            "plowshare.llm.pools[0].models[0]=qwen3.5-9b",
            "plowshare.llm.pools[0].models[1]=Mixed.Case-Model",
            "plowshare.llm.pools[0].context-lengths[qwen3.5-9b]=64000",
            "plowshare.llm.pools[0].context-lengths.Mixed.Case-Model=2048")
        .run(
            context -> {
              PoolProperties pool = context.getBean(LlmProperties.class).getPools().get(0);
              assertEquals(
                  Map.of("qwen3.5-9b", 64000, "Mixed.Case-Model", 2048),
                  pool.getContextLengths(),
                  "a wire model name has to survive the binder exactly, because"
                      + " nothing downstream looks it up by any other spelling");
            });
  }

  /**
   * The default is the backend every endpoint is, so a pool that declares nothing is assumed to
   * offer nothing beyond {@code /v1}.
   */
  @Test
  void a_pool_that_names_no_provider_is_the_openai_compatible_one() {
    runner
        .withPropertyValues(
            "plowshare.llm.pools[0].name=studio",
            "plowshare.llm.pools[0].base-url=http://192.0.2.10:1234/v1")
        .run(
            context -> {
              PoolProperties pool = context.getBean(LlmProperties.class).getPools().get(0);
              assertEquals(PoolProperties.Provider.OPENAI, pool.getProvider());
              assertEquals(Map.of(), pool.getContextLengths(), "and it has been told no lengths");
            });
  }

  /**
   * A misspelt provider stops the boot rather than binding to the default.
   *
   * <p>This is the whole argument for the field being an enum. As a {@code String}, {@code
   * provider: lmstduio} would leave a pool unable to discover anything and unable to say why — an
   * absence indistinguishable from a backend that has no vendor API, discovered by a conversation
   * that never gets compacted.
   */
  @Test
  void a_provider_nobody_implements_fails_the_startup_it_would_otherwise_pass() {
    runner
        .withPropertyValues(
            "plowshare.llm.pools[0].name=studio",
            "plowshare.llm.pools[0].base-url=http://192.0.2.10:1234/v1",
            "plowshare.llm.pools[0].provider=lmstduio")
        .run(
            context ->
                assertTrue(
                    context.getStartupFailure() != null,
                    "a provider name nothing implements bound quietly"));
  }

  /**
   * The single-endpoint keys are gone, and their absence has to be loud.
   *
   * <p>Spring ignores an unbound key by default, so a deployment still exporting {@code
   * LLM_BASE_URL} into {@code plowshare.llm.base-url} would start cleanly and talk to whatever the
   * pool list happened to say — an operator's setting silently discarded. Excalibur paid for the
   * same shape when a retired {@code workspaces_dir} left a deployment's job output orphaned with
   * nothing reporting it.
   */
  @Test
  void a_retired_single_endpoint_key_fails_the_startup_that_would_have_ignored_it() {
    runner
        .withPropertyValues(
            "plowshare.llm.base-url=http://192.0.2.10:1234/v1",
            "plowshare.llm.pools[0].name=studio",
            "plowshare.llm.pools[0].base-url=http://localhost:1234/v1")
        .run(
            context -> {
              assertNotNull(context.getStartupFailure());
              Throwable root = context.getStartupFailure();
              StringBuilder trail = new StringBuilder();
              while (root != null) {
                trail.append(root.getMessage()).append('\n');
                root = root.getCause();
              }
              assertTrue(trail.toString().contains("plowshare.llm.base-url"), trail.toString());
            });
  }

  /**
   * A number with no unit means what the field's default was written in.
   *
   * <p>This is the one binding rule an operator is most likely to get wrong and least likely to be
   * told about. Spring's unannotated default for a {@code Duration} is <em>milliseconds</em>, so
   * {@code chat-timeout: 60} binds to 60ms — and every model call then fails with a read timeout
   * that is indistinguishable from a dead endpoint. There is no boot failure and no log line.
   *
   * <p>Three things make that a likely thing to type rather than an exotic one. {@code chat: 1} and
   * {@code embedding: 2} sit directly beside these keys in the same block, so a bare number is the
   * local convention. The key these replaced was {@code timeout-seconds}, so an operator carrying a
   * value across writes the number and drops the suffix. And Anchor, which this configuration is
   * deliberately shaped after, spells its equivalent in seconds.
   *
   * <p>{@code retry-initial-backoff} is milliseconds and not seconds, which is the same argument
   * and not an exception to it: its default is 500ms and the key it replaced was {@code
   * retry-initial-backoff-ms}. Reading that one as seconds would turn a carried-across {@code 500}
   * into eight minutes of backoff between attempts.
   */
  @Test
  void a_bare_number_is_the_unit_the_field_s_own_default_was_written_in() {
    runner
        .withPropertyValues(
            "plowshare.llm.pools[0].name=studio",
            "plowshare.llm.pools[0].base-url=http://localhost:1234/v1",
            "plowshare.llm.pools[0].submit-timeout=45",
            "plowshare.llm.pools[0].chat-timeout=60",
            "plowshare.llm.pools[0].streaming-timeout=90",
            "plowshare.llm.pools[0].embedding-timeout=30",
            "plowshare.llm.pools[0].retry-initial-backoff=500")
        .run(
            context -> {
              PoolProperties pool = context.getBean(LlmProperties.class).getPools().get(0);
              assertEquals(Duration.ofSeconds(45), pool.getSubmitTimeout());
              assertEquals(
                  Duration.ofSeconds(60),
                  pool.getChatTimeout(),
                  "a bare 60 is a minute, not a sixteenth of a second");
              // A bare 90 here, and it stays 90: this test is about the
              // unit a bare number binds in, not about the default, which
              // is 300 and is pinned in the defaults test above.
              assertEquals(Duration.ofSeconds(90), pool.getStreamingTimeout());
              assertEquals(Duration.ofSeconds(30), pool.getEmbeddingTimeout());
              assertEquals(
                  Duration.ofMillis(500),
                  pool.getRetryInitialBackoff(),
                  "the backoff kept the unit of the -ms key it replaced");
            });
  }

  /**
   * A written unit always wins, in both directions.
   *
   * <p>Worth its own test because the fix for the case above is an annotation that changes what an
   * <em>absent</em> unit means, and an annotation that also overrode a written one would be a worse
   * trap than the default it replaced — {@code 250ms} silently becoming four minutes.
   */
  @Test
  void a_written_unit_wins_over_the_field_s_default_unit() {
    runner
        .withPropertyValues(
            "plowshare.llm.pools[0].name=studio",
            "plowshare.llm.pools[0].base-url=http://localhost:1234/v1",
            "plowshare.llm.pools[0].submit-timeout=250ms",
            "plowshare.llm.pools[0].retry-initial-backoff=2s")
        .run(
            context -> {
              PoolProperties pool = context.getBean(LlmProperties.class).getPools().get(0);
              assertEquals(Duration.ofMillis(250), pool.getSubmitTimeout());
              assertEquals(Duration.ofSeconds(2), pool.getRetryInitialBackoff());
            });
  }

  /**
   * What a getter hands out cannot be used to change what the next reader sees.
   *
   * <p>These are Spring singletons: one bean, alive for the process. A caller that did {@code
   * pool.getModels().removeIf(...)} would edit the running configuration of every later reader,
   * with nothing written to any configuration file and no log line naming the change. {@code
   * LlmPool}'s constructor already copies what it is handed, but that defends {@code LlmPool}; it
   * does nothing for the next consumer, and this class should not depend on every consumer being
   * the careful one.
   *
   * <p>Two independent leaks, so two independent assertions: the getter can hand out the field, and
   * the setter can keep the caller's list — which lets whoever called it edit this pool afterwards
   * through a reference it never gave up. Neither assertion catches the other's mutant, which was
   * checked rather than assumed.
   *
   * <p>One mutant deliberately survives both, and should. Wrapping the live field in {@code
   * Collections.unmodifiableList} instead of copying it passes everything here, because once the
   * setter copies, nothing outside this class can reach the field at all — the wrapper and the copy
   * are indistinguishable from where a caller stands. Do not add an assertion to kill it; it is an
   * equivalent implementation, not a surviving defect.
   */
  @Test
  void a_getter_hands_out_a_copy_and_not_the_running_configuration() {
    PoolProperties pool = new PoolProperties();
    pool.setModels(new ArrayList<>(List.of("nomic-embed-text", "qwen3.5-9b")));
    pool.setClasses(new LinkedHashMap<>(Map.of("fast", "qwen3.5-9b")));

    List<String> models = pool.getModels();
    assertThrows(UnsupportedOperationException.class, () -> models.add("never-served"));
    assertEquals(List.of("nomic-embed-text", "qwen3.5-9b"), pool.getModels());

    Map<String, String> classes = pool.getClasses();
    assertThrows(
        UnsupportedOperationException.class, () -> classes.put("reasoning", "never-served"));
    assertEquals(Map.of("fast", "qwen3.5-9b"), pool.getClasses());

    LlmProperties props = new LlmProperties();
    List<PoolProperties> declared = new ArrayList<>(List.of(pool));
    props.setPools(declared);
    List<PoolProperties> pools = props.getPools();
    assertThrows(UnsupportedOperationException.class, () -> pools.add(new PoolProperties()));
    assertEquals(1, props.getPools().size());

    // Inbound: the list handed to a setter is not the list kept.
    declared.add(new PoolProperties());
    assertEquals(1, props.getPools().size(), "a second host appeared without a second entry");

    List<String> declaredModels = new ArrayList<>(List.of("nomic-embed-text"));
    pool.setModels(declaredModels);
    declaredModels.add("never-declared");
    assertEquals(List.of("nomic-embed-text"), pool.getModels());

    Map<String, String> declaredClasses = new LinkedHashMap<>(Map.of("fast", "qwen3.5-9b"));
    pool.setClasses(declaredClasses);
    declaredClasses.put("reasoning", "never-declared");
    assertEquals(Map.of("fast", "qwen3.5-9b"), pool.getClasses());
  }

  /**
   * The key is not in the {@code toString}, and nobody has to remember that it must not be.
   *
   * <p>The absence is asserted two ways on purpose. {@code contains} is the direct claim; the
   * exact-string assertion is the tripwire, because the realistic way a key reaches a log line is
   * not someone appending {@code apiKey} today but someone adding a {@code bearerToken} field in a
   * year and reaching for the IDE's generate-toString, which takes every field. That rewrite passes
   * a {@code contains} check written against the field names of 2026 and fails this one. Loosen it
   * and the guard is gone.
   *
   * <p>The value used here is a marker rather than anything key-shaped: the slice's one absolute
   * rule is that no key material appears in a log line, an exception, or a test, and a test file is
   * not the place to make the first exception.
   */
  @Test
  void the_key_never_reaches_a_log_line_through_toString() {
    PoolProperties pool = new PoolProperties();
    pool.setName("studio");
    pool.setBaseUrl("http://192.0.2.10:1234/v1");
    pool.setApiKey("MUST-NOT-BE-PRINTED");

    assertTrue(pool.hasApiKey(), "the field has to be populated for the check to mean anything");
    assertFalse(pool.toString().contains("MUST-NOT-BE-PRINTED"), pool.toString());
    assertEquals("PoolProperties(studio -> http://192.0.2.10:1234/v1)", pool.toString());
  }

  /**
   * The allowlist was right about fields and leaked anyway, because a base URL can carry the
   * credential inside it.
   *
   * <p>{@code https://user:key@host/v1} is a legal thing to configure. The {@code api-key} field is
   * empty in that deployment, so every guard that looks at {@code apiKey} sees nothing to hide —
   * and {@code LlmConfig}'s boot summary passes the whole pool list to a {@code log.info} that
   * {@code application.yml} enables at INFO. A perfectly healthy start wrote a live credential to
   * the log.
   *
   * <p><b>The exact-string assertion above is what locked that in.</b> It is the tripwire for a
   * generated {@code toString}, and it was doing its job; it simply pinned a format that was
   * already wrong. Recorded because it is the more interesting half: a test can hold a leak in
   * place precisely by being strict about the wrong thing, and strictness reads as rigour.
   */
  @Test
  void a_key_written_into_the_base_url_does_not_reach_a_log_line_either() {
    PoolProperties pool = new PoolProperties();
    pool.setName("studio");
    pool.setBaseUrl("https://someone:MUST-NOT-BE-PRINTED@192.0.2.10:1234/v1");

    assertFalse(pool.toString().contains("MUST-NOT-BE-PRINTED"), pool.toString());
    assertEquals(
        "PoolProperties(studio -> https://[redacted]@192.0.2.10:1234/v1)", pool.toString());
  }

  /**
   * An '@' in the path is not userinfo, and over-redacting a URL an operator has to read is its own
   * failure.
   */
  @Test
  void a_base_url_with_no_userinfo_is_printed_as_written() {
    PoolProperties pool = new PoolProperties();
    pool.setName("studio");
    pool.setBaseUrl("http://192.0.2.10:1234/v1/@me");

    assertEquals("PoolProperties(studio -> http://192.0.2.10:1234/v1/@me)", pool.toString());
  }

  // --- the deployment overlay: bin/application-local.example.yml ------------------------

  /** Where the example overlay is, from this module's working directory. */
  private static final String OVERLAY = "file:../bin/application-local.example.yml";

  /** The minimal example binds one fleet with shared chat/embedding capacity and no extra host. */
  @Test
  void example_overlay_serves_chat_and_embeddings_from_one_pool() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(Binding.class)
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withPropertyValues(
            "spring.config.additional-location=" + OVERLAY,
            "LLM_BASE_URL=http://192.0.2.10:9000/v1",
            "LLM_CHAT_MODEL=overlay-chat",
            "LLM_EMBEDDING_MODEL=overlay-embedding")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              LlmProperties props = context.getBean(LlmProperties.class);
              assertEquals(
                  List.of("inference"),
                  props.getPools().stream().map(PoolProperties::getName).toList());
              assertEquals("overlay-embedding", props.getEmbeddingModel());
              assertEquals(
                  Map.of("fast", "overlay-chat", "reasoning", "overlay-chat"), props.getClasses());
              try (var dispatcher =
                  new LlmConfig()
                      .llmDispatcher(
                          props,
                          new com.fasterxml.jackson.databind.ObjectMapper(),
                          new io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger())) {
                assertEquals("overlay-chat", dispatcher.wireModelFor("reasoning"));
                dispatcher.requireServed("overlay-embedding");
                var swarm = new io.aeyer.plowshare.server.swarm.DispatcherPools(dispatcher);
                assertEquals(List.of(), swarm.serving("reasoning"));
                assertEquals(0, swarm.slots("inference"));
                assertEquals(1, props.getPools().getFirst().getChat());
                assertEquals(1, props.getPools().getFirst().getEmbedding());
                assertEquals("http://192.0.2.10:9000/v1", props.getPools().getFirst().getBaseUrl());
              }
            });
  }

  @Test
  void example_overlay_refuses_an_omitted_endpoint() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(Binding.class)
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withPropertyValues(
            "spring.config.additional-location=" + OVERLAY,
            "LLM_CHAT_MODEL=overlay-chat",
            "LLM_EMBEDDING_MODEL=overlay-embedding")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              // The binder may retain an unresolved placeholder in a String.
              // The dispatcher must reject it before opening any connection.
              assertThrows(
                  IllegalStateException.class,
                  () ->
                      new LlmConfig()
                          .llmDispatcher(
                              context.getBean(LlmProperties.class),
                              new com.fasterxml.jackson.databind.ObjectMapper(),
                              new io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger()));
            });
  }

  @Test
  void explicit_timeout_environment_overrides_bind_and_the_overlay_preserves_them() {
    for (String location : List.of("", OVERLAY)) {
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
          .withUserConfiguration(Binding.class)
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withInitializer(
              context ->
                  context
                      .getEnvironment()
                      .getPropertySources()
                      .addFirst(
                          new org.springframework.core.env.SystemEnvironmentPropertySource(
                              "systemEnvironment",
                              Map.of(
                                  "PLOWSHARE_LLM_PROMPTTIMEOUT",
                                  "120s",
                                  "PLOWSHARE_LLM_FOLDTIMEOUT",
                                  "160s"))))
          .withPropertyValues(
              "spring.config.additional-location=" + location,
              "LLM_BASE_URL=http://192.0.2.10:9000/v1",
              "LLM_CHAT_MODEL=overlay-chat",
              "LLM_EMBEDDING_MODEL=overlay-embedding")
          .run(
              context -> {
                assertNull(context.getStartupFailure());
                LlmProperties props = context.getBean(LlmProperties.class);
                assertEquals(Duration.ofSeconds(120), props.getPromptTimeout());
                assertEquals(Duration.ofSeconds(160), props.getFoldTimeout());
              });
    }
  }
}
