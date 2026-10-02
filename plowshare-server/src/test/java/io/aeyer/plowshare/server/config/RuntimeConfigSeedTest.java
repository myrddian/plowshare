package io.aeyer.plowshare.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultPropertiesPropertySource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginTrackedResource;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

/**
 * The boot check, <b>the moment in the boot it happens at</b>, and what the boot
 * then writes into the map.
 *
 * <p>Two things are under test here and only one of them is the message. The
 * other is {@link #the_refusal_lands_before_anything_the_boot_starts}, which
 * asserts that no lifecycle bean was ever started — the pin on {@link
 * RuntimeConfigSeed} refusing from inside {@code refresh()} rather than from an
 * {@code ApplicationReadyEvent} listener, which is the class's most-argued
 * decision and the one a reader is most likely to undo.
 *
 * <p>The precedence half turns on one distinction — a default that shipped in
 * the jar must not overrule a value somebody has since written, while anything
 * an operator supplied must. {@link
 * #a_packaged_default_the_operator_filled_in_overwrites_the_map} is the case the
 * plan for this work got wrong, and it and {@link
 * #a_packaged_default_left_at_its_placeholder_leaves_the_map_alone} are a pair:
 * each one is passed by an implementation the other fails.
 *
 * <p>No Spring Boot application and no database. {@link ApplicationContextRunner}
 * builds a context out of the two lines that matter — a properties bean and the
 * seed — so a test of "does this key name anything" costs no container. It is
 * also the reason the check cannot be an {@code ApplicationReadyEvent} listener:
 * the runner refreshes a context and never runs a {@code SpringApplication}, so
 * that event is never published and every assertion below would pass over
 * nothing.
 */
class RuntimeConfigSeedTest {

    /** The refusal {@link Live} argues for, in the case it argues from: a key is
     *  named rather than derived, so a typo names nothing and the boot is where
     *  that gets caught. */
    @Test
    void a_live_key_that_names_no_bound_property_refuses_the_boot() {
        ApplicationContextRunner runner = runner()
                .withUserConfiguration(PropertiesWithATypo.class);

        runner.run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(context.getStartupFailure().getMessage()
                    .contains("plowshare.documents.ingest-budgett"));
        });
    }

    /**
     * <b>And it names where the typo is, not only that there is one.</b>
     *
     * <p>The key is a string in a source file, so the refusal is only actionable
     * if it says which source file. A message carrying the key alone sends a
     * reader grepping for a spelling that by definition does not exist anywhere
     * else in the tree.
     */
    @Test
    void the_refusal_names_the_accessor_that_declared_the_key() {
        runner()
                .withUserConfiguration(PropertiesWithATypo.class)
                .run(context -> {
                    String said = context.getStartupFailure().getMessage();
                    assertTrue(said.contains("TypoProperties"), said);
                    assertTrue(said.contains("getIngestBudget"), said);
                });
    }

    /**
     * <b>The refusal lands before the context starts anything, and this is the
     * assertion that pins it.</b>
     *
     * <p>{@link Started} is a {@link SmartLifecycle}, so the container starts it
     * inside {@code finishRefresh()} — after every {@code
     * SmartInitializingSingleton} has run and before any {@code
     * ContextRefreshedEvent} listener, and, in a real server, in the same phase
     * that binds the port. That it was never started is the difference between
     * a boot that refused and a boot that came up, served, and then complained.
     *
     * <p>Delete {@code implements SmartInitializingSingleton} from the seed in
     * favour of any listener and this goes red while the message assertions
     * above stay green, which is the point of it.
     */
    @Test
    void the_refusal_lands_before_anything_the_boot_starts() {
        runner()
                .withUserConfiguration(PropertiesWithATypo.class)
                .withBean(Started.class, Started::new)
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertFalse(Started.EVER,
                            "the context started a lifecycle bean before refusing the boot");
                });
    }

    /**
     * <b>Two typos in a tree name the same one every time.</b>
     *
     * <p>The two beans are registered by hand, in an order this test fixes, and
     * under names chosen so that <em>scan order and key order disagree</em>:
     * {@link ScannedFirst} is registered first and named first alphabetically,
     * and it is the one holding {@code zzz-typo}. So the answer distinguishes
     * the sort from every order the context could otherwise have handed back —
     * delete {@code found.sort} and this reports {@code zzz-typo} and goes red.
     *
     * <p>Registered with {@code withBean} and not with {@code
     * @EnableConfigurationProperties}, because that annotation collects its
     * classes into a {@code Set} before registering them: the order two classes
     * listed there arrive in is a hash order over {@code Class} objects, which
     * is identity-based and therefore differs between JVM runs. That is exactly
     * the instability the sort exists to absorb, and a test of the sort cannot
     * be built on it.
     */
    @Test
    void two_misspelled_keys_are_reported_in_an_order_the_source_decides() {
        runner()
                .withUserConfiguration(JustTheSeed.class)
                .withBean("aaa-scanned-first", ScannedFirst.class, ScannedFirst::new)
                .withBean("zzz-scanned-last", ScannedLast.class, ScannedLast::new)
                .run(context -> {
                    String said = context.getStartupFailure().getMessage();
                    assertTrue(said.contains("plowshare.documents.aaa-typo"), said);
                    assertFalse(said.contains("plowshare.documents.zzz-typo"), said);
                });
    }

    /**
     * <b>{@code @Live} on a setter is refused, and the property it names is
     * set</b> — so what refuses this boot is the shape of the method and not the
     * environment check, which would have passed.
     *
     * <p>{@code @Target(METHOD)} cannot say "accessor", so without this the
     * annotation lands on {@code setIngestBudget}, the key resolves, the config
     * API reports it live, an operator writes it, and nothing changes. That is
     * the failure {@link Live} exists to refuse, reached through {@link Live}.
     */
    @Test
    void a_live_key_on_a_setter_refuses_the_boot() {
        runner()
                .withUserConfiguration(LiveOnASetter.class)
                .withPropertyValues("plowshare.documents.ingest-budget=1000")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    String said = context.getStartupFailure().getMessage();
                    assertTrue(said.contains("setIngestBudget"), said);
                    assertTrue(said.contains("not an accessor"), said);
                    assertTrue(said.contains("takes an argument"), said);
                });
    }

    /** And a method that takes nothing but returns nothing either, which is the
     *  other half of "there is no value to read through this". */
    @Test
    void a_live_key_on_a_method_returning_nothing_refuses_the_boot() {
        runner()
                .withUserConfiguration(LiveOnAVoidMethod.class)
                .withPropertyValues("plowshare.documents.ingest-budget=1000")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    String said = context.getStartupFailure().getMessage();
                    assertTrue(said.contains("reload"), said);
                    assertTrue(said.contains("returns void"), said);
                });
    }

    /**
     * <b>One key, two accessors: refused, and both are named.</b>
     *
     * <p>The key would be seeded from one of the two bound values and read back
     * through both, so an operator's write would land or not land depending on
     * which accessor the caller happened to hold. Nothing about that is visible
     * at runtime, which is why it costs a boot instead.
     */
    @Test
    void one_key_declared_on_two_accessors_refuses_the_boot() {
        runner()
                .withUserConfiguration(TwoAccessorsOneKey.class)
                .withPropertyValues("plowshare.documents.ingest-budget=1000")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    String said = context.getStartupFailure().getMessage();
                    assertTrue(said.contains("getBudget"), said);
                    assertTrue(said.contains("getIngestBudget"), said);
                });
    }

    /**
     * <b>A covariant override is one declaration, not two.</b>
     *
     * <p>{@link Covariant#getIngestBudget} narrows an interface's return type,
     * so the compiler emits a bridge method beside it and {@code
     * Class#getMethods} hands back both; Spring resolves a bridge to its target
     * when it looks for annotations, so both answer {@code @Live}. Undeduplicated,
     * boot precedence would seed the key twice and the config API would list it
     * twice.
     *
     * <p>Deduplicating on the <em>accessor</em> and not on the key is what keeps
     * {@link #one_key_declared_on_two_accessors_refuses_the_boot} refusing: a
     * bridge shares its target's name, and two real accessors do not.
     */
    @Test
    void a_covariant_override_declares_its_key_once() {
        runner()
                .withUserConfiguration(CovariantAccessor.class)
                .withPropertyValues("plowshare.documents.ingest-budget=1000")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    RuntimeConfigSeed seed = context.getBean(RuntimeConfigSeed.class);
                    assertEquals(1, seed.declared().size(), seed.declared().toString());
                });
    }

    /**
     * <b>A key outside its own class's prefix is refused, though it names a real
     * property.</b>
     *
     * <p>{@code plowshare.llm.sampling-directory} is set here, so {@code
     * containsProperty} would pass and the old check would have started this
     * server. Spring never bound {@code getIngestBudget} from it — the class
     * binds under {@code plowshare.documents} — so the accessor would go on
     * returning its own value while the config API reported the sampling key
     * as live and an operator's write went into a map nothing reads.
     */
    @Test
    void a_live_key_outside_its_class_prefix_refuses_the_boot() {
        runner()
                .withUserConfiguration(KeyFromAnotherPrefix.class)
                .withPropertyValues("plowshare.llm.sampling-directory=/srv/sampling")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    String said = context.getStartupFailure().getMessage();
                    assertTrue(said.contains("plowshare.llm.sampling-directory"), said);
                    assertTrue(said.contains("plowshare.documents"), said);
                });
    }

    /** A key that names a property Spring bound is the ordinary case, and it
     *  must cost the boot nothing. */
    @Test
    void a_live_key_that_names_a_bound_property_starts() {
        runner()
                .withUserConfiguration(WellNamedProperties.class)
                .withPropertyValues("plowshare.documents.ingest-budget=1000")
                .run(context -> assertNull(context.getStartupFailure()));
    }

    /** The annotation is the whole of the difference: a properties class that
     *  declares no live key is not asked anything at boot, whatever its
     *  accessors are called. */
    @Test
    void a_properties_class_with_no_live_key_is_not_asked_about_its_accessors() {
        runner()
                .withUserConfiguration(NothingLive.class)
                .run(context -> assertNull(context.getStartupFailure()));
    }

    /**
     * <b>An operator pinned it outside the jar, so the operator wins</b> — and
     * the packaged default is present underneath, which is what makes this a
     * test of precedence rather than of an empty environment.
     */
    @Test
    void a_key_pinned_by_the_environment_overwrites_the_map_on_boot() {
        config.put(KEY, "1200", "someone");
        runner()
                .withUserConfiguration(WellNamedProperties.class)
                .withInitializer(packagedDefault(KEY, "1000"))
                .withPropertyValues(KEY + "=300")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(Optional.of("300"), config.get(KEY));
                    assertEquals("boot", config.authorOf(KEY).orElseThrow());
                });
    }

    /**
     * <b>The case the spec was first wrong about.</b> Every live key is named in
     * the packaged {@code application.yml}, so a rule of "the file wins" reverts
     * every runtime write and persistence buys nothing.
     *
     * <p><b>The packaged default is the string the map already holds, and the
     * author is what carries the assertion.</b> Written the obvious way — {@code
     * 1000} shipped against {@code 1200} in the map — every wrong answer changes
     * the value too, so the author adds nothing while reading as though it
     * covered something. With both at {@code 1200} it covers the case it was
     * written for and is the only thing that does: a seed that rewrote the row
     * leaves the value right and {@code updated_by} claiming the boot chose what
     * an operator did. Delete the author assertion and this test stops holding
     * anything.
     */
    @Test
    void a_key_carrying_only_its_packaged_default_leaves_the_map_alone() {
        config.put(KEY, "1200", "someone");
        runner()
                .withUserConfiguration(WellNamedProperties.class)
                .withInitializer(packagedDefault(KEY, "1200"))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(Optional.of("1200"), config.get(KEY));
                    assertEquals("someone", config.authorOf(KEY).orElseThrow());
                });
    }

    /** A shipped default seeds a gap, which is the half of §1.2 that makes the
     *  map usable at all on a server nobody has written to yet. */
    @Test
    void a_key_absent_from_the_map_takes_the_packaged_default() {
        runner()
                .withUserConfiguration(WellNamedProperties.class)
                .withInitializer(packagedDefault(KEY, "1000"))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(Optional.of("1000"), config.get(KEY));
                    assertEquals("boot", config.authorOf(KEY).orElseThrow());
                });
    }

    /**
     * <b>The case the plan was wrong about, and the one this class exists to
     * hold.</b>
     *
     * <p>The packaged {@code application.yml} writes this key as {@code
     * ${PLOWSHARE_INGEST_BUDGET:1000}} — all 36 of its placeholders have that
     * shape — so the knob it offers an operator lives <em>inside the
     * packaged source</em>. Exporting that variable adds no property source that
     * supplies {@code plowshare.documents.ingest-budget}; it only changes what a
     * classpath resource resolves to. The plan's rule, "find the {@code
     * PropertySource} that supplied the key and leave the map alone if it is the
     * packaged one", therefore reads the documented way of pinning this key as a
     * shipped default and lets a runtime write outlive it, silently.
     *
     * <p>Implement it that way and this is the only test that goes red.
     */
    @Test
    void a_packaged_default_the_operator_filled_in_overwrites_the_map() {
        config.put(KEY, "1200", "someone");
        runner()
                .withUserConfiguration(WellNamedProperties.class)
                .withInitializer(packagedDefault(KEY, "${PLOWSHARE_INGEST_BUDGET:1000}"))
                .withPropertyValues("PLOWSHARE_INGEST_BUDGET=300")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(Optional.of("300"), config.get(KEY));
                });
    }

    /**
     * And its other half, which is what stops the fix above from swallowing
     * everything: a placeholder nobody filled in is still a packaged default.
     *
     * <p>The cheap version of the fix — compare the source's raw text against
     * the resolved value — passes the test above and fails this one, because
     * {@code ${PLOWSHARE_INGEST_BUDGET:1200}} never equals {@code 1200}. That
     * version would overwrite the map on every boot of a server nobody had
     * pinned anything on, which is the failure §1.2 was written to remove.
     *
     * <p>Laid out as its sibling above is, for the same reason: the placeholder
     * resolves to what the map already holds, so the author is what separates a
     * boot that left the row alone from one that wrote the same string over it.
     */
    @Test
    void a_packaged_default_left_at_its_placeholder_leaves_the_map_alone() {
        config.put(KEY, "1200", "someone");
        runner()
                .withUserConfiguration(WellNamedProperties.class)
                .withInitializer(packagedDefault(KEY, "${PLOWSHARE_INGEST_BUDGET:1200}"))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(Optional.of("1200"), config.get(KEY));
                    assertEquals("someone", config.authorOf(KEY).orElseThrow());
                });
    }

    /**
     * <b>A default property is code shipped in the jar too, and it must not
     * overrule the map either.</b>
     *
     * <p>{@code SpringApplication.setDefaultProperties} installs a source whose
     * contents are written in {@code main}, so a value only it supplies is the
     * jar's own answer and never an operator's. The source carries no origin at
     * all, so a rule that asks only "did this text come off the classpath"
     * answers "nothing shipped this key" and reads the jar's own default as a
     * pin — and then every boot rewrites the map from it, with {@code boot} as
     * the author, silently reverting whatever an operator had set. That is §1.2
     * failing in the direction the class comment calls the bad one.
     *
     * <p>Not hypothetical here: {@code PlowshareServerApplication.main} sets
     * {@code plowshare.auth.token-file} that way, and {@code application.yml}
     * says in capitals that the key is deliberately not written in the file
     * because a value there would be read by every test as well. Any future
     * {@code @Live} on a key following that documented pattern arrives in
     * exactly this shape.
     */
    @Test
    void a_default_property_the_jar_ships_leaves_the_map_alone() {
        config.put(KEY, "1200", "someone");
        runner()
                .withUserConfiguration(WellNamedProperties.class)
                .withInitializer(mainsDefaultProperty(KEY, "1000"))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(Optional.of("1200"), config.get(KEY));
                });
    }

    /**
     * And the opposite case, which is what stops the fix above from reading
     * every deployment as unpinned: <b>a key no jar-side source supplies at all
     * is the operator's by elimination.</b>
     *
     * <p>Nothing shipped it — no classpath resource names it and {@code main}
     * declares no default for it — so whatever did is outside the jar and wins.
     * It is the one path where the isolated read is taken over no sources
     * whatever: the comparison answers {@code null} against a value that is
     * present, which is a difference, which is a pin. The seed states that rule
     * by falling through rather than by a branch of its own, and this is the
     * test that holds the fall-through.
     */
    @Test
    void a_key_no_jar_side_source_supplies_overwrites_the_map() {
        config.put(KEY, "1200", "someone");
        runner()
                .withUserConfiguration(WellNamedProperties.class)
                .withPropertyValues(KEY + "=300")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(Optional.of("300"), config.get(KEY));
                    assertEquals("boot", config.authorOf(KEY).orElseThrow());
                });
    }

    /**
     * <b>A shipped placeholder with no default, filled in by the operator, is a
     * pin and not a boot failure</b> — and this is the test that holds {@code
     * setIgnoreUnresolvableNestedPlaceholders}.
     *
     * <p>The shape is one {@code application.yml} does not use today: all 36 of
     * its placeholders carry a default, so every one of them resolves over the
     * packaged sources alone. A {@code ${SOME_SECRET}} without a default does
     * not — the jar has no answer for it, which is precisely what makes the
     * operator's answer a pin — and the isolated read is where that is
     * discovered. Left to throw, it throws inside {@code
     * afterSingletonsInstantiated} and costs the boot, so a key the operator
     * supplied correctly would refuse to start the server.
     */
    @Test
    void a_shipped_placeholder_the_jar_cannot_resolve_is_a_pin_and_not_a_refusal() {
        config.put(KEY, "1200", "someone");
        runner()
                .withUserConfiguration(WellNamedProperties.class)
                .withInitializer(packagedDefault(KEY, "${PLOWSHARE_INGEST_SECRET}"))
                .withPropertyValues("PLOWSHARE_INGEST_SECRET=300")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(Optional.of("300"), config.get(KEY));
                });
    }

    /**
     * <b>An {@code application.yml} beside the jar is the operator's, not a
     * packaged default</b>, though it has the same filename and the same shape
     * of property source.
     *
     * <p>What separates the two is where the text was read from, so the
     * distinction is drawn on the {@link Resource} behind the value's origin —
     * a {@link ClassPathResource} is the jar's and a {@link FileSystemResource}
     * is somebody's. Both sources here are laid out as Spring lays them out:
     * the external file above, the classpath below.
     */
    @Test
    void an_application_yml_beside_the_jar_overwrites_the_map() {
        config.put(KEY, "1200", "someone");
        runner()
                .withUserConfiguration(WellNamedProperties.class)
                .withInitializer(besideTheJar(KEY, "300"))
                .withInitializer(packagedDefault(KEY, "1000"))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(Optional.of("300"), config.get(KEY));
                });
    }

    /**
     * <b>A boot that is going to be refused writes nothing on the way there.</b>
     *
     * <p>{@link GoodKeyAndTypo} declares a sound key and a misspelled one, and
     * the sound one sorts first — so a seed interleaved with the checks would
     * reach it, overwrite an operator's runtime value with a packaged default,
     * and only then refuse the boot. The map is asked whether anything at all
     * was written, rather than about one key, because the fault is the write
     * happening and not which key it landed on.
     */
    @Test
    void a_boot_that_is_going_to_be_refused_writes_nothing_first() {
        runner()
                .withUserConfiguration(AGoodKeyAndATypo.class)
                .withInitializer(packagedDefault(KEY, "1000"))
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(config.all().isEmpty(), config.all().toString());
                });
    }

    /** The one key every fixture here declares, and the one the shipped file
     *  writes as a placeholder. */
    private static final String KEY = "plowshare.documents.ingest-budget";

    /**
     * The map the seed writes into, per test method.
     *
     * <p>An instance field, so JUnit's per-method instance is what isolates one
     * test's writes from the next — the same reason {@link Started} needs a
     * reset and cannot have one.
     */
    private final InMemory config = new InMemory();

    /**
     * Every context here needs the store the seed now takes, including the ones
     * that refuse before reaching it: a missing bean would fail the refresh with
     * a message about wiring rather than about a key.
     *
     * <p>And every context here carries the attached {@code
     * configurationProperties} source, which {@link ApplicationContextRunner}
     * does not add on its own and a real boot always has. The seed walks the
     * source list, and that one is a delegating {@code OriginLookup} at the head
     * of it — the shape most likely to make this code answer the wrong thing.
     *
     * <p><b>It is here for realism, and not because a test turns on it.</b>
     * Remove the attach and every assertion below stays green, for the reason
     * {@link RuntimeConfigSeed}'s class comment gives: the attached source wraps
     * what it delegates in a {@code PropertySourceOrigin}, so it never comes
     * back as a {@code TextResourceOrigin} and the seed is never fooled by it. A
     * fixture booting in a shape no real server has is a fixture whose green
     * means less, which is worth two lines; it is not a case this class can
     * hold, and the sentence that said it was has been removed.
     */
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withBean(RuntimeConfig.class, () -> config)
                .withInitializer(context ->
                        ConfigurationPropertySources.attach(context.getEnvironment()));
    }

    /**
     * A property source shaped like the one Boot builds for the {@code
     * application.yml} inside the jar.
     *
     * <p>Built by hand rather than loaded, because {@link
     * ApplicationContextRunner} refreshes a context and never runs a {@code
     * SpringApplication}, so no config data is loaded for it at all. What has to
     * be reproduced is only what the seed reads: an {@link
     * OriginTrackedMapPropertySource} whose values carry a {@link
     * TextResourceOrigin} over a classpath resource, wrapped in {@link
     * OriginTrackedResource} exactly as Boot 3.3.5 wraps it — measured from a
     * real boot, and the wrapper is why the seed unwraps before asking the type.
     *
     * <p>{@code addLast}: the packaged file is the bottom of the precedence
     * order, under anything an operator supplies.
     *
     * <p><b>Package-private because {@code RuntimeConfigControllerTest} builds
     * its fixture from this one.</b> That test asserts what the controller
     * reports as {@code pinned}, which is this class's rule seen from the other
     * end — so the two have to agree on what a jar-shipped source looks like,
     * and a second hand-built copy over there is exactly the pair that would
     * drift. What is reproduced here was measured off a real boot, and only one
     * file should have to be corrected when Boot changes it.
     */
    static ApplicationContextInitializer<ConfigurableApplicationContext> packagedDefault(
            String key, String value) {
        return source("class path resource [application.yml]",
                new ClassPathResource("application.yml"), key, value);
    }

    /** The same file, placed beside the jar by an operator instead of shipped
     *  in it — which is the distinction the seed has to draw. */
    private static ApplicationContextInitializer<ConfigurableApplicationContext> besideTheJar(
            String key, String value) {
        return source("file [config/application.yml]",
                new FileSystemResource("config/application.yml"), key, value);
    }

    /**
     * The source {@code SpringApplication.setDefaultProperties} installs, which
     * {@code PlowshareServerApplication.main} uses.
     *
     * <p>{@link DefaultPropertiesPropertySource} rather than a {@code
     * MapPropertySource} named by hand: the name is the contract the seed reads
     * it by, and it is that class's constant on both sides. It carries no origin
     * — there is no resource behind a map written in {@code main} — which is the
     * whole of why the classpath rule cannot see it.
     */
    private static ApplicationContextInitializer<ConfigurableApplicationContext>
            mainsDefaultProperty(String key, String value) {
        return context -> context.getEnvironment().getPropertySources()
                .addLast(new DefaultPropertiesPropertySource(Map.of(key, value)));
    }

    static ApplicationContextInitializer<ConfigurableApplicationContext> source(
            String name, Resource resource, String key, String value) {
        Origin origin = new TextResourceOrigin(
                OriginTrackedResource.of(resource, null), new TextResourceOrigin.Location(1, 1));
        Map<String, Object> tracked = Map.of(key, OriginTrackedValue.of(value, origin));
        return context -> context.getEnvironment().getPropertySources()
                .addLast(new OriginTrackedMapPropertySource("Config resource '" + name + "'",
                        tracked));
    }

    /**
     * The map, in memory.
     *
     * <p>A subclass and not a mock: three of the tests above turn on a write
     * being visible to a later read, which is state and not an interaction, and
     * {@link RuntimeConfig#get} and {@link RuntimeConfig#authorOf} are already
     * projections of {@link RuntimeConfig#entry} — so overriding the two
     * statements gives the whole surface. The {@code null} {@code JdbcTemplate}
     * is safe for exactly that reason: nothing left un-overridden touches it.
     */
    static final class InMemory extends RuntimeConfig {

        private final Map<String, Entry> rows = new LinkedHashMap<>();

        InMemory() {
            super(null);
        }

        @Override
        public void put(String key, String value, String updatedBy) {
            rows.put(key, new Entry(key, value, Instant.EPOCH, updatedBy));
        }

        @Override
        public Optional<Entry> entry(String key) {
            return Optional.ofNullable(rows.get(key));
        }

        @Override
        public List<Entry> all() {
            return List.copyOf(rows.values());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TypoProperties.class)
    @Import(RuntimeConfigSeed.class)
    static class PropertiesWithATypo {}

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GoodKeyAndTypo.class)
    @Import(RuntimeConfigSeed.class)
    static class AGoodKeyAndATypo {}

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WellNamed.class)
    @Import(RuntimeConfigSeed.class)
    static class WellNamedProperties {}

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(Unannotated.class)
    @Import(RuntimeConfigSeed.class)
    static class NothingLive {}

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(Setter.class)
    @Import(RuntimeConfigSeed.class)
    static class LiveOnASetter {}

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(VoidMethod.class)
    @Import(RuntimeConfigSeed.class)
    static class LiveOnAVoidMethod {}

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TwoAccessors.class)
    @Import(RuntimeConfigSeed.class)
    static class TwoAccessorsOneKey {}

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(Covariant.class)
    @Import(RuntimeConfigSeed.class)
    static class CovariantAccessor {}

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WrongPrefix.class)
    @Import(RuntimeConfigSeed.class)
    static class KeyFromAnotherPrefix {}

    /** The seed and nothing else: {@link
     *  #two_misspelled_keys_are_reported_in_an_order_the_source_decides} brings
     *  its own properties beans, in an order it fixes. */
    @Configuration(proxyBeanMethods = false)
    @Import(RuntimeConfigSeed.class)
    static class JustTheSeed {}

    /** One letter wrong, which is the whole failure this check exists for. */
    @ConfigurationProperties(prefix = "plowshare.documents")
    static class TypoProperties {

        private int ingestBudget;

        @Live("plowshare.documents.ingest-budgett")
        public int getIngestBudget() {
            return ingestBudget;
        }

        public void setIngestBudget(int ingestBudget) {
            this.ingestBudget = ingestBudget;
        }
    }

    @ConfigurationProperties(prefix = "plowshare.documents")
    static class WellNamed {

        private int ingestBudget;

        @Live("plowshare.documents.ingest-budget")
        public int getIngestBudget() {
            return ingestBudget;
        }

        public void setIngestBudget(int ingestBudget) {
            this.ingestBudget = ingestBudget;
        }
    }

    /** Scanned first, sorting last. */
    @ConfigurationProperties(prefix = "plowshare.documents")
    static class ScannedFirst {

        @Live("plowshare.documents.zzz-typo")
        public int getZzz() {
            return 0;
        }
    }

    /** Scanned last, sorting first. */
    @ConfigurationProperties(prefix = "plowshare.documents")
    static class ScannedLast {

        @Live("plowshare.documents.aaa-typo")
        public int getAaa() {
            return 0;
        }
    }

    /** The key is real and set; the method it is on cannot be read through. */
    @ConfigurationProperties(prefix = "plowshare.documents")
    static class Setter {

        private int ingestBudget;

        public int getIngestBudget() {
            return ingestBudget;
        }

        @Live("plowshare.documents.ingest-budget")
        public void setIngestBudget(int ingestBudget) {
            this.ingestBudget = ingestBudget;
        }
    }

    /** Takes nothing, returns nothing, and so is not a value either. */
    @ConfigurationProperties(prefix = "plowshare.documents")
    static class VoidMethod {

        private int ingestBudget;

        public int getIngestBudget() {
            return ingestBudget;
        }

        public void setIngestBudget(int ingestBudget) {
            this.ingestBudget = ingestBudget;
        }

        @Live("plowshare.documents.ingest-budget")
        public void reload() {
            // Nothing to do: the point is the signature.
        }
    }

    /** Two accessors, one key, and no way to tell which one a write reached. */
    @ConfigurationProperties(prefix = "plowshare.documents")
    static class TwoAccessors {

        private int ingestBudget;

        @Live("plowshare.documents.ingest-budget")
        public int getIngestBudget() {
            return ingestBudget;
        }

        public void setIngestBudget(int ingestBudget) {
            this.ingestBudget = ingestBudget;
        }

        @Live("plowshare.documents.ingest-budget")
        public int getBudget() {
            return ingestBudget;
        }
    }

    /** Narrows {@link Budgeted}'s return type, so javac emits a bridge method
     *  carrying the same annotation. */
    interface Budgeted {

        Number getIngestBudget();
    }

    @ConfigurationProperties(prefix = "plowshare.documents")
    static class Covariant implements Budgeted {

        private Integer ingestBudget = 0;

        @Override
        @Live("plowshare.documents.ingest-budget")
        public Integer getIngestBudget() {
            return ingestBudget;
        }

        public void setIngestBudget(Integer ingestBudget) {
            this.ingestBudget = ingestBudget;
        }
    }

    /** A real key, set in the environment, on a class that binds under a
     *  different prefix and so was never bound from it. */
    @ConfigurationProperties(prefix = "plowshare.documents")
    static class WrongPrefix {

        private int ingestBudget;

        @Live("plowshare.llm.sampling-directory")
        public int getIngestBudget() {
            return ingestBudget;
        }

        public void setIngestBudget(int ingestBudget) {
            this.ingestBudget = ingestBudget;
        }
    }

    /** A sound key that sorts first and a misspelled one that sorts second, so
     *  a seed interleaved with the checks would write before it refused. */
    @ConfigurationProperties(prefix = "plowshare.documents")
    static class GoodKeyAndTypo {

        private int ingestBudget;

        @Live("plowshare.documents.ingest-budget")
        public int getIngestBudget() {
            return ingestBudget;
        }

        public void setIngestBudget(int ingestBudget) {
            this.ingestBudget = ingestBudget;
        }

        @Live("plowshare.documents.retentionn")
        public int getRetention() {
            return 0;
        }
    }

    /** No {@code @Live} anywhere on it, and an accessor named after a key that
     *  is not set, so a scan that ignored the annotation would refuse this. */
    @ConfigurationProperties(prefix = "plowshare.nothing")
    static class Unannotated {

        private int ingestBudget;

        public int getIngestBudget() {
            return ingestBudget;
        }

        public void setIngestBudget(int ingestBudget) {
            this.ingestBudget = ingestBudget;
        }
    }

    /**
     * Records that the container reached the phase that starts things.
     *
     * <p>Static, because the context under test is thrown away before the
     * assertion runs — a failed refresh has no beans to ask. Reset on
     * construction rather than in a {@code @BeforeEach}, so the flag belongs to
     * the one context that built it.
     */
    static class Started implements SmartLifecycle {

        static boolean EVER;

        private boolean running;

        Started() {
            EVER = false;
        }

        @Override
        public void start() {
            EVER = true;
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }
}
