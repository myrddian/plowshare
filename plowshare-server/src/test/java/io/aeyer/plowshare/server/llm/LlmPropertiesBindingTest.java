package io.aeyer.plowshare.server.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.files.LocalProvider;
import io.aeyer.plowshare.server.files.RemoteProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
 * <p>No datasource and no Spring Boot application here — one properties class
 * and the binder, which is all that is under test. Nothing in this file opens a
 * socket: the context runner wires {@link Binding}, which is {@code
 * @EnableConfigurationProperties} and nothing else, and the rest is {@code
 * PoolProperties} objects and their {@code toString}.
 *
 * <p><b>The host is 192.0.2.10, and it used to be the reference box's real
 * address.</b> Every use of it here is a string being bound, compared or
 * printed — including the two redaction tests, whose entire job is to contain a
 * base URL and assert what {@code toString} does to it. The slice's invariant is
 * that <em>no test opens a connection to the box</em>, and these never did; but
 * the plan checked it by grepping {@code src/test} for that address, which fires
 * on a string that opens nothing and would miss a test reaching the box through
 * a property file, an environment variable or a hostname. The next person to run
 * that grep would have found nine hits in a file that is innocent, and the
 * cheapest way to make it green would have been to delete a redaction test.
 * (The address is deliberately not written out anywhere in this comment either:
 * a note explaining the grep that then trips it is the same fault one level up.)
 *
 * <p>192.0.2.10 is TEST-NET-1 from RFC 5737, reserved for documentation and
 * guaranteed not to route. It serves every purpose the old value served — a host
 * that is visibly not {@code localhost} — and it takes the tension out of the
 * grep, which now means what it says. It is still not a check that anything
 * opened a socket; see the plan's Step 6 for what that check would have to be
 * and why this repository has no instrument for it.
 */
class LlmPropertiesBindingTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LlmProperties.class)
    static class Binding {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(
                    AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(Binding.class);

    @Test
    void a_pool_binds_from_the_shape_the_spec_documents() {
        runner.withPropertyValues(
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
                .run(context -> {
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
                    assertEquals("a-bigger-model-when-there-is-one",
                            props.getClasses().get("reasoning"));
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
    void the_defaults_are_the_serial_box_the_project_actually_runs_on() {
        runner.withPropertyValues(
                        "plowshare.llm.pools[0].name=studio",
                        "plowshare.llm.pools[0].base-url=http://localhost:1234/v1")
                .run(context -> {
                    PoolProperties pool = context.getBean(LlmProperties.class).getPools().get(0);
                    assertEquals(1, pool.getChat(), "the reference box generates one stream at a time");
                    assertEquals(2, pool.getEmbedding());
                    assertEquals(0, pool.getSwarm(), "a pool admits no swarm work unless it says so");
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
                    assertTrue(pool.getStreamingTimeout().compareTo(pool.getMaxStreamDuration()) < 0,
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
     * {@link LlmProperties} answers with nothing for the three keys
     * {@code application.yml} owns until something binds them.
     *
     * <p><b>This is the assertion that keeps one default in one place.</b> Both
     * keys used to be written twice — a field initializer here and a
     * {@code ${LLM_...:...}} placeholder in {@code application.yml} — and the
     * YAML won at every boot, because Spring binds over an initializer. So the
     * Java values were dead in the only way that matters: nothing compared them
     * against the YAML, nothing failed when they drifted apart, and removing a
     * key from the YAML would have brought a stale one silently back to life.
     *
     * <p>The empty answers below are therefore the contract and not a gap. A
     * server is configured by {@code application.yml}; a {@link LlmProperties}
     * nothing has bound is a half-built object, and {@code LlmConfig} refuses to
     * start on all three of these — {@code embedding-dim} and {@code
     * default-context-length} for being non-positive, {@code embedding-model}
     * for being blank — which is what turns "no Java default" into a boot
     * failure naming the key rather than an archive embedded at width zero or a
     * conversation that folds on every turn.
     */
    @Test
    void the_class_carries_no_default_for_the_keys_the_yaml_owns() {
        LlmProperties unbound = new LlmProperties();

        assertNull(unbound.getEmbeddingModel(),
                "a default here is a second source of truth for application.yml's own key");
        assertEquals(0, unbound.getEmbeddingDim(),
                "a default here is a second source of truth for application.yml's own key");
        assertEquals(0, unbound.getDefaultContextLength(),
                "a default here is a second source of truth for application.yml's own key");
        assertEquals(0, unbound.getEmbeddingMaxInputTokens(),
                "a default here is a second source of truth for application.yml's own key");
    }

    /**
     * The shipped {@code application.yml} is where the two archive-wide defaults
     * live, and this is what reads it.
     *
     * <p>Every other test in this file binds properties the runner is handed
     * directly, which is the right shape for asking what the binder does with a
     * key. It is the wrong shape for asking what the project's own defaults
     * <em>are</em>: a runner with no property source loads no YAML at all, so a
     * test written that way can only ever see a Java field initializer — which
     * is exactly the thing that is no longer there.
     *
     * <p>{@link ConfigDataApplicationContextInitializer} is the difference. It
     * runs Spring Boot's own config-data step, so the file this repository ships
     * is the property source and the assertions below are about the deployment
     * rather than about the class. {@code embedding-dim} must stay at the
     * {@code vector(n)} width in {@code V1__memories.sql}, and this is now the
     * only place that number is checked outside a migration.
     *
     * <p>{@code default-context-length} is pinned here for a different reason.
     * It is the bottom tier of the three a context length is resolved through,
     * and the only one that answers when the other two cannot — so a deployment
     * that lost this key would have compaction switched off entirely, which is
     * the state the key was added to make impossible. 64 000 is the number the
     * YAML argues for at length; this is what stops it drifting silently.
     *
     * <p><b>What this pins and what it cannot: it is the test that should have
     * caught the 2026-09-06 fault and did not.</b> It reads the real file
     * Docker-free and exists to stop shipped defaults drifting — and the fault
     * was a shipped default drifting, {@code 773c063} moving the pool's chat
     * model and leaving {@code plowshare.llm.classes} pointing at the old name
     * for a day. It was invisible here because every assertion above is a
     * <em>scalar</em> read alone, and the fault was a <em>relation</em> between
     * two keys that were each individually fine. Adding {@code classes} to the
     * list above would not have caught it either, for the same reason. See
     * {@link #every_class_in_the_shipped_yaml_names_a_model_some_pool_serves},
     * which asserts the relation and is the guard this one could not be.
     */
    @Test
    void the_archive_wide_defaults_come_from_the_shipped_yaml_and_nowhere_else() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(Binding.class)
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    LlmProperties props = context.getBean(LlmProperties.class);
                    assertEquals("nomic-embed-text", props.getEmbeddingModel());
                    assertEquals(768, props.getEmbeddingDim(),
                            "must match vector(768) in V1__memories.sql");
                    assertEquals(64000, props.getDefaultContextLength(),
                            "the bottom tier of context-length resolution; see the key's own"
                                    + " comment for why it is this number and not a larger one");
                });
    }

    /**
     * Every class the shipped {@code application.yml} declares names a model
     * some pool in that same file serves.
     *
     * <h2>This is the regression guard for the bug the change was written for,
     * and until it existed the guard needed Docker</h2>
     *
     * <p>{@code 773c063} moved the pool's chat model default to {@code
     * qwen3.8-27b} and left both class values at {@code qwen3.5-9b}, so a
     * default boot sent a model the box did not serve and got a 404 at the first
     * turn. {@code LlmConfig.requireClassesResolvable} now refuses that — but
     * only when it runs, and it runs only inside a context that has both the
     * shipped YAML and {@code LlmConfig} in it. {@code LlmConfigTest} has {@code
     * LlmConfig} and synthetic properties; this file has the shipped YAML and no
     * {@code LlmConfig}. The tests that have both are the end-to-end ones, and
     * every one of those is {@code @Testcontainers} — so on a machine or a CI
     * leg without a Docker daemon, re-introducing that exact drift went green.
     * The commit's claim that "re-introducing it turns every end-to-end test
     * red" was true and rested on the heaviest tests in the suite.
     *
     * <p><b>{@link #the_archive_wide_defaults_come_from_the_shipped_yaml_and_nowhere_else}
     * is the test that should have caught the live bug and did not.</b> It is
     * the pin over shipped defaults against drift, it runs Docker-free on the
     * real file, and it pins {@code embedding-model}, {@code embedding-dim} and
     * {@code default-context-length} — every archive-wide scalar, and no
     * relation between two keys. The fault was a relation: the class map and the
     * pool's model list, each correct read alone. That is what this asserts, and
     * it is why it is a second test rather than three more lines in that one —
     * the assertion is not "this key is still 64000" but "these two keys still
     * agree", which is a different kind of claim about the file.
     *
     * <p><b>Deliberately a re-derivation and not a call into {@code
     * LlmConfig}.</b> Standing the whole configuration class up here would pull
     * in an {@code ObjectMapper}, a token ledger and an {@code OkHttpClient} per
     * pool, which is the weight this file exists without. The cost is that the
     * rule is written twice, and the two could drift: what stops that mattering
     * is that {@code LlmConfigTest} owns the refusal's behaviour on arbitrary
     * configurations and this owns only the shipped file. If they ever
     * disagree, the refusal is the one that is right.
     */
    @Test
    void every_class_in_the_shipped_yaml_names_a_model_some_pool_serves() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(Binding.class)
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    LlmProperties props = context.getBean(LlmProperties.class);
                    assertFalse(props.getClasses().isEmpty(),
                            "the shipped configuration declares no classes, so this guard is"
                                    + " asserting nothing");

                    Set<String> servedAnywhere = props.getPools().stream()
                            .flatMap(pool -> pool.getModels().stream())
                            .collect(Collectors.toSet());
                    String fleet = props.getPools().stream()
                            .map(pool -> pool.getName() + "=" + pool.getModels())
                            .collect(Collectors.joining(", "));

                    props.getClasses().forEach((className, wireModel) -> assertTrue(
                            servedAnywhere.contains(wireModel),
                            "plowshare.llm.classes maps '" + className + "' to '" + wireModel
                                    + "', which no pool in the shipped application.yml serves ("
                                    + fleet + "). A default boot would send that name to a box"
                                    + " that does not have it and take a 404 at the first turn,"
                                    + " which is what 773c063 shipped. If the two placeholders"
                                    + " have drifted apart again, they are the fix"));
                });
    }

    /**
     * No pool in the shipped {@code application.yml} is named for a property of
     * the pool rather than for the machine it is.
     *
     * <h2>What is being guarded, and why a deny-list of bad names would not do
     * it</h2>
     *
     * <p>The pool now named {@code studio} was called {@code local} until
     * 2026-09-07. That is a property wearing a name — two local instances can
     * exist, and both of this fleet's nodes are on the operator's own network,
     * so calling one of them {@code local} implied the other was not. Its
     * neighbour was named {@code spark} after the hardware from the start, which
     * is what made the mismatch visible.
     *
     * <p><b>A list of forbidden words is the obvious test and it is weak in the
     * exact place it matters: it only knows the mistakes already made.</b>
     * Someone adding a third pool called {@code gpu}, {@code fast} or {@code
     * remote} would sail past a list containing {@code local}. So the three
     * checks below all read their forbidden set out of something that grows on
     * its own, and each one names a way a pool has already been misnamed or
     * nearly was:
     *
     * <ol>
     *   <li><b>Not a name for a kind in another subsystem.</b> Read from {@link
     *       LocalProvider#NAME} and {@link RemoteProvider#NAME}, the file
     *       providers — the server's own disk against a client machine's. That
     *       is a real binary with two values and no third, so {@code local}
     *       there is a name for a kind and earns the word; a pool using it makes
     *       one string mean two unrelated things in two subsystems, which is
     *       what a grep cannot tell apart. Reading the constants rather than
     *       repeating the strings is what makes a rename over there move this
     *       guard with it.</li>
     *   <li><b>Not the software the box happens to run.</b> Read from {@link
     *       PoolProperties.Provider}, so a third backend extends the guard by
     *       existing. {@code name: lmstudio} would be the same category error a
     *       third time, coupling an identity to software that can change without
     *       the box changing — and {@code name: studio} sits two lines above
     *       {@code provider: lmstudio} precisely so a reader sees they are
     *       different things.</li>
     *   <li><b>Not a role the pool happens to serve.</b> Read from the shipped
     *       {@code plowshare.llm.classes}, so a class added tomorrow is a name
     *       forbidden tomorrow. A pool called {@code fast} would be the same
     *       error a fourth time: a class is what a pool serves, not what it
     *       is.</li>
     * </ol>
     *
     * <p><b>What it still cannot catch, said plainly.</b> None of the three
     * knows that {@code gpu} or {@code nearby} is a property; "names a machine"
     * is not a predicate this configuration can evaluate. What the test buys is
     * that every word this server already uses for something else is closed off
     * without anyone remembering to close it, and that the next person to add a
     * pool reads this list of four errors before they name it. That is a guard
     * over a habit rather than a proof, and it is the honest size of the claim.
     */
    @Test
    void no_pool_is_named_for_a_property_of_the_pool() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(Binding.class)
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    LlmProperties props = context.getBean(LlmProperties.class);
                    assertFalse(props.getPools().isEmpty(),
                            "the shipped configuration declares no pools, so this guard is"
                                    + " asserting nothing");

                    Set<String> kinds = Set.of(LocalProvider.NAME, RemoteProvider.NAME);
                    Set<String> backends = Arrays.stream(PoolProperties.Provider.values())
                            .map(provider -> provider.name().toLowerCase(Locale.ROOT))
                            .collect(Collectors.toSet());
                    Set<String> roles = props.getClasses().keySet();

                    for (PoolProperties pool : props.getPools()) {
                        String name = pool.getName().toLowerCase(Locale.ROOT);
                        assertFalse(kinds.contains(name),
                                "pool '" + pool.getName() + "' takes a word that already names a"
                                        + " kind elsewhere in this server " + kinds + ": one"
                                        + " string would mean two unrelated things in two"
                                        + " subsystems. Name it after the machine");
                        assertFalse(backends.contains(name),
                                "pool '" + pool.getName() + "' is named for the software it runs "
                                        + backends + ", which can change without the box changing."
                                        + " Name it after the machine");
                        assertFalse(roles.contains(name),
                                "pool '" + pool.getName() + "' is named for a role it serves "
                                        + roles + ". A class is what a pool serves, not what it"
                                        + " is. Name it after the machine");
                    }
                });
    }

    /**
     * A context length keyed by a real wire model name survives the binder
     * intact — dots, capitals and all.
     *
     * <p><b>This pins a measurement against a belief that is easy to hold and
     * was held here.</b> Spring's relaxed binding canonicalises property names,
     * and the natural conclusion is that {@code
     * context-lengths.qwen3.5-9b: 64000} splits on the dot and never produces
     * that key — which is what {@code PoolProperties#contextLengths} and a boot
     * refusal both asserted in their first draft. Measured on Spring Boot 3.3.5:
     * it does not. Both forms bind the key verbatim, and the bare form keeps
     * mixed case too. What is canonicalised is the path <em>to</em> the map, not
     * a key <em>within</em> a map of scalars.
     *
     * <p>Worth a test rather than a corrected sentence, because the failure
     * either belief predicts is silent: the pool binds, the boot succeeds, and
     * the configured length is simply never found. If a later Spring makes the
     * old belief true, this is what says so.
     */
    @Test
    void a_context_length_binds_under_a_model_name_with_a_dot_in_it() {
        runner.withPropertyValues(
                        "plowshare.llm.pools[0].name=studio",
                        "plowshare.llm.pools[0].base-url=http://192.0.2.10:1234/v1",
                        "plowshare.llm.pools[0].models[0]=qwen3.5-9b",
                        "plowshare.llm.pools[0].models[1]=Mixed.Case-Model",
                        "plowshare.llm.pools[0].context-lengths[qwen3.5-9b]=64000",
                        "plowshare.llm.pools[0].context-lengths.Mixed.Case-Model=2048")
                .run(context -> {
                    PoolProperties pool = context.getBean(LlmProperties.class).getPools().get(0);
                    assertEquals(Map.of("qwen3.5-9b", 64000, "Mixed.Case-Model", 2048),
                            pool.getContextLengths(),
                            "a wire model name has to survive the binder exactly, because"
                                    + " nothing downstream looks it up by any other spelling");
                });
    }

    /** The default is the backend every endpoint is, so a pool that declares
     *  nothing is assumed to offer nothing beyond {@code /v1}. */
    @Test
    void a_pool_that_names_no_provider_is_the_openai_compatible_one() {
        runner.withPropertyValues(
                        "plowshare.llm.pools[0].name=studio",
                        "plowshare.llm.pools[0].base-url=http://192.0.2.10:1234/v1")
                .run(context -> {
                    PoolProperties pool = context.getBean(LlmProperties.class).getPools().get(0);
                    assertEquals(PoolProperties.Provider.OPENAI, pool.getProvider());
                    assertEquals(Map.of(), pool.getContextLengths(),
                            "and it has been told no lengths");
                });
    }

    /**
     * A misspelt provider stops the boot rather than binding to the default.
     *
     * <p>This is the whole argument for the field being an enum. As a {@code
     * String}, {@code provider: lmstduio} would leave a pool unable to discover
     * anything and unable to say why — an absence indistinguishable from a
     * backend that has no vendor API, discovered by a conversation that never
     * gets compacted.
     */
    @Test
    void a_provider_nobody_implements_fails_the_startup_it_would_otherwise_pass() {
        runner.withPropertyValues(
                        "plowshare.llm.pools[0].name=studio",
                        "plowshare.llm.pools[0].base-url=http://192.0.2.10:1234/v1",
                        "plowshare.llm.pools[0].provider=lmstduio")
                .run(context -> assertTrue(context.getStartupFailure() != null,
                        "a provider name nothing implements bound quietly"));
    }

    /**
     * The single-endpoint keys are gone, and their absence has to be loud.
     *
     * <p>Spring ignores an unbound key by default, so a deployment still
     * exporting {@code LLM_BASE_URL} into {@code plowshare.llm.base-url} would
     * start cleanly and talk to whatever the pool list happened to say —
     * an operator's setting silently discarded. Excalibur paid for the same
     * shape when a retired {@code workspaces_dir} left a deployment's job
     * output orphaned with nothing reporting it.
     */
    @Test
    void a_retired_single_endpoint_key_fails_the_startup_that_would_have_ignored_it() {
        runner.withPropertyValues(
                        "plowshare.llm.base-url=http://192.0.2.10:1234/v1",
                        "plowshare.llm.pools[0].name=studio",
                        "plowshare.llm.pools[0].base-url=http://localhost:1234/v1")
                .run(context -> {
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
     * <p>This is the one binding rule an operator is most likely to get wrong
     * and least likely to be told about. Spring's unannotated default for a
     * {@code Duration} is <em>milliseconds</em>, so {@code chat-timeout: 60}
     * binds to 60ms — and every model call then fails with a read timeout that
     * is indistinguishable from a dead endpoint. There is no boot failure and
     * no log line.
     *
     * <p>Three things make that a likely thing to type rather than an exotic
     * one. {@code chat: 1} and {@code embedding: 2} sit directly beside these
     * keys in the same block, so a bare number is the local convention. The key
     * these replaced was {@code timeout-seconds}, so an operator carrying a
     * value across writes the number and drops the suffix. And Anchor, which
     * this configuration is deliberately shaped after, spells its equivalent in
     * seconds.
     *
     * <p>{@code retry-initial-backoff} is milliseconds and not seconds, which
     * is the same argument and not an exception to it: its default is 500ms and
     * the key it replaced was {@code retry-initial-backoff-ms}. Reading that
     * one as seconds would turn a carried-across {@code 500} into eight minutes
     * of backoff between attempts.
     */
    @Test
    void a_bare_number_is_the_unit_the_field_s_own_default_was_written_in() {
        runner.withPropertyValues(
                        "plowshare.llm.pools[0].name=studio",
                        "plowshare.llm.pools[0].base-url=http://localhost:1234/v1",
                        "plowshare.llm.pools[0].submit-timeout=45",
                        "plowshare.llm.pools[0].chat-timeout=60",
                        "plowshare.llm.pools[0].streaming-timeout=90",
                        "plowshare.llm.pools[0].embedding-timeout=30",
                        "plowshare.llm.pools[0].retry-initial-backoff=500")
                .run(context -> {
                    PoolProperties pool = context.getBean(LlmProperties.class).getPools().get(0);
                    assertEquals(Duration.ofSeconds(45), pool.getSubmitTimeout());
                    assertEquals(Duration.ofSeconds(60), pool.getChatTimeout(),
                            "a bare 60 is a minute, not a sixteenth of a second");
                    // A bare 90 here, and it stays 90: this test is about the
                    // unit a bare number binds in, not about the default, which
                    // is 300 and is pinned in the defaults test above.
                    assertEquals(Duration.ofSeconds(90), pool.getStreamingTimeout());
                    assertEquals(Duration.ofSeconds(30), pool.getEmbeddingTimeout());
                    assertEquals(Duration.ofMillis(500), pool.getRetryInitialBackoff(),
                            "the backoff kept the unit of the -ms key it replaced");
                });
    }

    /**
     * A written unit always wins, in both directions.
     *
     * <p>Worth its own test because the fix for the case above is an annotation
     * that changes what an <em>absent</em> unit means, and an annotation that
     * also overrode a written one would be a worse trap than the default it
     * replaced — {@code 250ms} silently becoming four minutes.
     */
    @Test
    void a_written_unit_wins_over_the_field_s_default_unit() {
        runner.withPropertyValues(
                        "plowshare.llm.pools[0].name=studio",
                        "plowshare.llm.pools[0].base-url=http://localhost:1234/v1",
                        "plowshare.llm.pools[0].submit-timeout=250ms",
                        "plowshare.llm.pools[0].retry-initial-backoff=2s")
                .run(context -> {
                    PoolProperties pool = context.getBean(LlmProperties.class).getPools().get(0);
                    assertEquals(Duration.ofMillis(250), pool.getSubmitTimeout());
                    assertEquals(Duration.ofSeconds(2), pool.getRetryInitialBackoff());
                });
    }

    /**
     * What a getter hands out cannot be used to change what the next reader
     * sees.
     *
     * <p>These are Spring singletons: one bean, alive for the process. A caller
     * that did {@code pool.getModels().removeIf(...)} would edit the running
     * configuration of every later reader, with nothing written to any
     * configuration file and no log line naming the change. {@code LlmPool}'s
     * constructor already copies what it is handed, but that defends {@code
     * LlmPool}; it does nothing for the next consumer, and this class should
     * not depend on every consumer being the careful one.
     *
     * <p>Two independent leaks, so two independent assertions: the getter can
     * hand out the field, and the setter can keep the caller's list — which
     * lets whoever called it edit this pool afterwards through a reference it
     * never gave up. Neither assertion catches the other's mutant, which was
     * checked rather than assumed.
     *
     * <p>One mutant deliberately survives both, and should. Wrapping the live
     * field in {@code Collections.unmodifiableList} instead of copying it
     * passes everything here, because once the setter copies, nothing outside
     * this class can reach the field at all — the wrapper and the copy are
     * indistinguishable from where a caller stands. Do not add an assertion to
     * kill it; it is an equivalent implementation, not a surviving defect.
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
        assertThrows(UnsupportedOperationException.class,
                () -> classes.put("reasoning", "never-served"));
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
     * The key is not in the {@code toString}, and nobody has to remember that
     * it must not be.
     *
     * <p>The absence is asserted two ways on purpose. {@code contains} is the
     * direct claim; the exact-string assertion is the tripwire, because the
     * realistic way a key reaches a log line is not someone appending {@code
     * apiKey} today but someone adding a {@code bearerToken} field in a year
     * and reaching for the IDE's generate-toString, which takes every field.
     * That rewrite passes a {@code contains} check written against the field
     * names of 2026 and fails this one. Loosen it and the guard is gone.
     *
     * <p>The value used here is a marker rather than anything key-shaped: the
     * slice's one absolute rule is that no key material appears in a log line,
     * an exception, or a test, and a test file is not the place to make the
     * first exception.
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
     * The allowlist was right about fields and leaked anyway, because a base URL
     * can carry the credential inside it.
     *
     * <p>{@code https://user:key@host/v1} is a legal thing to configure. The
     * {@code api-key} field is empty in that deployment, so every guard that
     * looks at {@code apiKey} sees nothing to hide — and {@code LlmConfig}'s
     * boot summary passes the whole pool list to a {@code log.info} that {@code
     * application.yml} enables at INFO. A perfectly healthy start wrote a live
     * credential to the log.
     *
     * <p><b>The exact-string assertion above is what locked that in.</b> It is
     * the tripwire for a generated {@code toString}, and it was doing its job;
     * it simply pinned a format that was already wrong. Recorded because it is
     * the more interesting half: a test can hold a leak in place precisely by
     * being strict about the wrong thing, and strictness reads as rigour.
     */
    @Test
    void a_key_written_into_the_base_url_does_not_reach_a_log_line_either() {
        PoolProperties pool = new PoolProperties();
        pool.setName("studio");
        pool.setBaseUrl("https://someone:MUST-NOT-BE-PRINTED@192.0.2.10:1234/v1");

        assertFalse(pool.toString().contains("MUST-NOT-BE-PRINTED"), pool.toString());
        assertEquals("PoolProperties(studio -> https://[redacted]@192.0.2.10:1234/v1)",
                pool.toString());
    }

    /** An '@' in the path is not userinfo, and over-redacting a URL an operator
     *  has to read is its own failure. */
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

    /**
     * The overlay example, on top of the shipped YAML, is a configuration this
     * server boots: every refusal {@code LlmConfig} makes at startup is asked of
     * it, and the class it adds routes to the pool it adds.
     *
     * <p>A call into {@code LlmConfig} and not a re-derivation, against the
     * choice {@link #every_class_in_the_shipped_yaml_names_a_model_some_pool_serves}
     * makes: an example an operator copies has to pass the real boot, and the
     * only way to know it does is to run that boot's checks.
     */
    @Test
    void the_example_overlay_boots_and_routes_its_fallback_class_to_its_own_pool() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(Binding.class)
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.additional-location=" + OVERLAY)
                .run(context -> {
                    LlmProperties props = context.getBean(LlmProperties.class);
                    assertEquals(List.of("studio", "spark", "refusal-fallback"),
                            props.getPools().stream().map(PoolProperties::getName).toList());
                    assertEquals("mlx-community/gemma-4-e4b-it", props.getClasses().get("fast"),
                            "classes is a map: the overlay adds one and keeps the shipped two");

                    try (io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher dispatcher =
                            new LlmConfig().llmDispatcher(props,
                                    new com.fasterxml.jackson.databind.ObjectMapper(),
                                    new io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger())) {
                        dispatcher.requireServed("low_refusal_osint");
                        assertEquals("your-low-refusal-model",
                                dispatcher.wireModelFor("low_refusal_osint"));
                    }
                });
    }

    /**
     * The two pools the overlay restates are the two pools {@code application.yml}
     * ships, key for key.
     *
     * <p>They have to be written out: Spring replaces a list from a
     * higher-precedence source rather than merging it, so an overlay that added
     * a third pool alone would delete the other two. That makes the example a
     * second copy of the shipped pools, which is exactly the kind of copy this
     * file keeps finding drifted — so the drift is what fails here.
     */
    @Test
    void the_example_overlay_restates_the_shipped_pools_as_shipped() {
        List<String> shipped = new ArrayList<>();
        List<String> restated = new ArrayList<>();
        ApplicationContextRunner base = new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(Binding.class)
                .withInitializer(new ConfigDataApplicationContextInitializer());
        base.run(context -> context.getBean(LlmProperties.class).getPools()
                .forEach(pool -> shipped.add(described(pool))));
        base.withPropertyValues("spring.config.additional-location=" + OVERLAY)
                .run(context -> context.getBean(LlmProperties.class).getPools().stream()
                        .limit(shipped.size())
                        .forEach(pool -> restated.add(described(pool))));

        assertEquals(shipped, restated,
                "bin/application-local.example.yml no longer restates the pools application.yml"
                        + " ships. An operator who copies it would run on the old ones. Change"
                        + " the example to match");
    }

    private static String described(PoolProperties pool) {
        return String.join(" | ", pool.getName(), pool.getBaseUrl(), pool.getApiKey(),
                String.valueOf(pool.getModels()), String.valueOf(pool.getVision()),
                String.valueOf(pool.getProvider()), String.valueOf(pool.getContextLengths()),
                String.valueOf(pool.getCompactionThresholds()),
                String.valueOf(pool.isPrefillProgress()), String.valueOf(pool.getChat()),
                String.valueOf(pool.getEmbedding()), String.valueOf(pool.getSubmitTimeout()),
                String.valueOf(pool.getChatTimeout()), String.valueOf(pool.getStreamingTimeout()),
                String.valueOf(pool.getMaxStreamDuration()),
                String.valueOf(pool.getEmbeddingTimeout()),
                String.valueOf(pool.getRetryMaxAttempts()),
                String.valueOf(pool.getRetryInitialBackoff()));
    }
}
