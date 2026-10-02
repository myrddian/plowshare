package io.aeyer.plowshare.server.config;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.DefaultPropertiesPropertySource;
import org.springframework.boot.context.properties.ConfigurationPropertiesBean;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.origin.OriginLookup;
import org.springframework.boot.origin.OriginTrackedResource;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.ConfigurablePropertyResolver;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * What the boot asks of every {@link Live} key before this server serves
 * anything.
 *
 * <p>{@link Live} argues why a live key is written out by hand rather than
 * derived, and states the price of writing one out. This class is that price
 * being paid: before anything starts, every declaration is asked whether it
 * really names the value its accessor holds, and one that does not costs the
 * boot. That is what the seven validating {@code @Configuration} classes already
 * do with their own numbers, and for the same reason — a mistake in
 * configuration should cost a start, not a morning.
 *
 * <h2>A {@link SmartInitializingSingleton}, and <em>not</em> an
 * {@code ApplicationReadyEvent} listener</h2>
 *
 * <p>Both moments have every {@code @ConfigurationProperties} bean built, which
 * is the only thing the scan needs. They differ in what has already happened by
 * the time the check runs, and <b>that difference is the whole of this
 * decision</b>.
 *
 * <p>{@code afterSingletonsInstantiated} is the last step of {@code
 * finishBeanFactoryInitialization}, so it is still inside {@code refresh()}: a
 * throw from here aborts the refresh, and {@code finishRefresh()} — which starts
 * the lifecycle beans, binds the port, and only then publishes anything — never
 * runs. {@code ApplicationReadyEvent} is on the far side of all of it. A check
 * there would be refusing a server that had already opened its port, and it
 * would be racing {@code AuthConfig}'s announcement, which is a listener on that
 * same event that mints an operator token, writes it to the operator's disk at
 * mode 600, and prints a console URL. Listener order between two of them is not
 * defined, so the visible behaviour of a one-letter typo would be a console URL
 * on the terminal, a token file on disk, and a server that then died — some of
 * the time.
 *
 * <p>The second reason is smaller and it is the one a test can hold:
 * {@code ApplicationReadyEvent} is published by {@code SpringApplication} and
 * not by a context refresh, so it never fires under {@code
 * ApplicationContextRunner}. A check hung there could only be exercised by
 * booting a whole server, and {@code RuntimeConfigSeedTest} would pass over
 * nothing while looking exactly as it does now.
 *
 * <h2>What the two string checks prove, and what is left over</h2>
 *
 * <p>{@code containsProperty} on its own proves only that the key resolves
 * somewhere in the environment — a packaged default, an environment variable, a
 * command line. It does not prove that <em>this</em> accessor is the one Spring
 * bound from it. A key naming a real property on another class would pass it,
 * and so would one naming a real property that the enclosing prefix could never
 * have reached.
 *
 * <p>Both of those are closed here, by the prefix the {@code
 * @ConfigurationProperties} annotation already carries: a key that does not sit
 * under the prefix its own class declares is refused before the environment is
 * asked at all. That costs one comparison and no second naming rule, because the
 * prefix is read from the annotation rather than derived from anything.
 *
 * <p>What remains open is narrower than it looks and is genuinely not worth
 * closing: a key under the right prefix that names a <em>different</em> property
 * of the same class — {@code @Live("plowshare.documents.retention")} on {@code
 * ingestBudgetNow}. Catching that means deriving the key from the accessor's
 * name, which is the relaxed-binding reimplementation {@link Live} declines, and
 * a misspelling does not produce it: a typo of {@code ingest-budget} is a key
 * that names nothing, which the environment check already refuses.
 *
 * <h2>Boot precedence: what counts as an operator having said so</h2>
 *
 * <p>Spec §1.2 is that a shipped default seeds an empty map and never
 * overrules a value somebody has since set, while a value an operator pinned
 * outside the jar is written in over whatever the map held. The first draft of
 * that spec said "the file wins", which reverts every runtime write and buys
 * nothing, because every live key is named in the packaged {@code
 * application.yml}. So the question this has to answer, key by key, is
 * <b>whether the running value is the one the jar alone would have produced</b>.
 *
 * <p><b>And it is asked as that comparison, rather than as "which {@code
 * PropertySource} held the key", which is what this task was planned as and is
 * wrong on this repository's own configuration.</b> Measured against the shipped
 * file: {@code plowshare.documents.ingest-budget} is written there as {@code
 * ${PLOWSHARE_INGEST_BUDGET:1000}}, and every one of the 36 placeholders in that
 * file has the same shape. The knob the file offers an operator is therefore
 * <em>inside the packaged source</em> — exporting {@code
 * PLOWSHARE_INGEST_BUDGET} creates no property source that supplies the key, it
 * only changes what a placeholder in the classpath resource resolves to. A rule
 * reading the supplying source would answer "packaged {@code application.yml}"
 * for an operator who used the variable the file itself documents, and their pin
 * would lose, silently, to a runtime write. Resolving the key a second time over
 * the packaged sources alone answers the placeholder case and the
 * separate-source case with one comparison, and it does not have to enumerate
 * the ways an operator can speak.
 *
 * <h2>Which sources are the jar's</h2>
 *
 * <p>The cut is <b>the first source that answers for this key on the jar's
 * behalf, and everything below it</b>. Below is what the ordering already
 * means: {@code getPropertySources} is in precedence order, so a source above
 * the packaged one outranked it, which is the whole of "an operator said so" —
 * a command-line argument, an exported variable, an {@code application.yml}
 * beside the jar.
 *
 * <p><b>Two kinds of source answer on the jar's behalf here, and the list is
 * open.</b> The
 * packaged file is one. The other is {@code defaultProperties}, which {@code
 * PlowshareServerApplication.main} installs through {@code
 * setDefaultProperties}: a map written in {@code main}, which is code shipped in
 * the same jar and no more an operator's than the file is. It sorts last, which
 * an earlier draft of this comment read as "below the cut, and therefore the
 * jar's, correctly" — true only once something above it has been recognised. A
 * key that <em>only</em> {@code defaultProperties} supplies reaches the cut
 * nowhere, and is then the operator's by elimination: every boot rewrites the
 * map from a value the jar itself shipped, author {@code boot}, reverting an
 * operator's runtime write with nothing said. That is §1.2 failing in the
 * direction this comment calls the bad one, and it is not hypothetical here —
 * {@code plowshare.auth.token-file} is set exactly that way, and {@code
 * application.yml} states in capitals that the key is deliberately not written
 * in the file, because a value there would be read by every test as well. Any
 * future {@code @Live} on a key following that documented pattern arrives in
 * this shape.
 *
 * <p><b>That source is recognised by its name, and it is the one case where a
 * name is the contract.</b> It carries no origin to be recognised by — there is
 * no resource behind a map built in {@code main} — and {@code
 * DefaultPropertiesPropertySource.NAME} is an API constant the framework writes
 * the source under and reads it back by, asked here through that class's own
 * {@code hasMatchingName} rather than copied in as a literal. What the next
 * paragraph rejects is something else: reading a {@code toString}.
 *
 * <p><b>Not the packaged file's name.</b> Boot names these {@code Config
 * resource 'class path resource [application.yml]' via location
 * 'optional:classpath:/'}, which is
 * a {@code toString} and not an API, and an external {@code application.yml} an
 * operator drops beside the jar has the same shape with {@code file [...]} in it.
 * The value's {@link TextResourceOrigin} carries the actual {@link Resource}
 * instead, and "came off the classpath" is what "shipped in the jar" means. Boot
 * wraps that resource in an {@link OriginTrackedResource}, so it is unwrapped
 * before the type is asked — measured, on 3.3.5: without the unwrap every value
 * looks external and every boot overwrites the map.
 *
 * <p><b>What that costs, stated.</b> An operator who runs {@code java -cp
 * /etc/plowshare:app.jar} puts their own config directory on the classpath, and
 * it is read here as the jar's. The sharper test — a {@code jar:} URL — was
 * rejected because it inverts in development: {@code bootRun} and every test
 * serve the packaged {@code application.yml} out of a plain directory, so a
 * jar-scheme rule would call the shipped default an operator's pin on every
 * developer machine, and that is the failure that reverts runtime writes rather
 * than merely ignoring a pin.
 *
 * <p><b>And a packaged profile is read as the jar's, which is the same cost
 * arriving by a second road.</b> An {@code application-prod.yml} inside the jar
 * is origin-tracked and classpath-backed like any other config resource, so it
 * takes the cut, and an operator who selects it with {@code
 * --spring.profiles.active=prod} finds their profile's value read as unpinned —
 * the value is the jar's, and choosing which of the jar's values to run is not
 * distinguishable here from running its default. Forward-looking: this tree has
 * no {@code application-*.yml} today. It is written down because the shape is
 * ordinary and the failure is the mild one — a deliberate choice ignored rather
 * than a runtime write reverted — and a reader adding the first profile file
 * should meet the sentence rather than the behaviour.
 *
 * <p><b>Only a source that can enumerate its own keys is asked</b>, which is how
 * the attached {@code configurationProperties} source is kept out of the answer.
 * That source is an {@link OriginLookup} delegating to all the others from the
 * head of the list, so an origin it hands back belongs to whichever source
 * actually won — and a cut taken there would swallow every operator source below
 * it and read every deployment as unpinned.
 *
 * <p><b>Measured on 3.3.5, and it is inert today:</b> the attached source wraps
 * what it delegates in a {@code PropertySourceOrigin}, whose {@code toString} is
 * the delegate's own — so it never comes back as a {@link TextResourceOrigin}
 * and nothing here is fooled, which is also why no test below can break this
 * line. It is kept, and written as "can this source list itself" rather than as
 * "is this the attached one", because that wrapping is an implementation detail
 * rather than a contract, a delegating view is the thing that cannot enumerate,
 * and the failure it would let in is silent and arrives on every key at once.
 *
 * <h2>The refusals happen before the map is touched</h2>
 *
 * <p>Every declaration is checked, and only then is any of them seeded. Both
 * checks are strings in source against strings in the environment, and both are
 * in hand before a connection is opened — so a server whose Postgres is
 * unreachable still refuses a typo, rather than starting with a defect it will
 * report later as a value that does not change. Interleaved, a tree with one
 * good key and one typo would overwrite an operator's runtime value from a
 * pinned default on the way to a boot that then dies.
 *
 * <p><b>A third shape exists and is not recognised, which is why the sentence
 * above says the list is open rather than closed.</b> A jar-shipped {@code
 * @PropertySource("classpath:…")} is a {@code ResourcePropertySource}: it
 * carries no {@code OriginLookup}, so nothing takes the cut, and {@code
 * ConfigurationClassParser} inserts it under its own name rather than {@code
 * defaultProperties} — so neither rule sees it, and a value the jar shipped
 * pins as the operator's. A jar-shipped {@code EnvironmentPostProcessor}
 * installing a plain {@code MapPropertySource} is the same family.
 *
 * <p><b>Not reachable in this tree</b>, and checked rather than assumed: no
 * {@code @PropertySource}, no {@code EnvironmentPostProcessor}, no {@code
 * spring.factories} and no {@code AutoConfiguration.imports} in any of the
 * three modules. It is written down because a closed-world sentence is what
 * stops the next reader looking, and this method has already been wrong once in
 * exactly that way.
 */
@Component
public class RuntimeConfigSeed implements SmartInitializingSingleton {

    /** What the seed records as the author of a value it writes in. */
    private static final String BOOT = "boot";

    private final ApplicationContext beans;
    private final ConfigurableEnvironment environment;
    private final RuntimeConfig store;

    /**
     * @param environment {@link ConfigurableEnvironment} and not {@code
     *     Environment}: the checks want only {@code containsProperty}, but boot
     *     precedence has to tell a value an operator pinned outside the jar from
     *     one that arrived with it, and that is answered by walking {@code
     *     getPropertySources} — which the read-only interface does not expose.
     */
    public RuntimeConfigSeed(
            ApplicationContext beans, ConfigurableEnvironment environment, RuntimeConfig store) {
        this.beans = beans;
        this.environment = environment;
        this.store = store;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<Declaration> declarations = declared();
        for (Declaration declared : declarations) {
            if (!declared.isUnderItsOwnPrefix()) {
                throw new IllegalStateException(
                        declared.key() + " is declared @Live on " + declared.where()
                                + ", whose @ConfigurationProperties prefix is "
                                + declared.prefix() + ", so Spring bound this accessor from no"
                                + " key by that name. A live key names the property its accessor"
                                + " holds: one that happens to resolve elsewhere in the"
                                + " environment would start the server, be reported as live, and"
                                + " take an operator's write into a map this accessor never"
                                + " reads. Spell it under " + declared.prefix() + ", or move the"
                                + " annotation to the class that owns the key");
            }
            if (!environment.containsProperty(declared.key())) {
                throw new IllegalStateException(
                        declared.key() + " is declared @Live on " + declared.where()
                                + ", and Spring bound no property by that name. A live key is"
                                + " written out rather than derived from the accessor, so a"
                                + " misspelling matches nothing: the map would be written under"
                                + " one spelling and read under another, and the accessor would"
                                + " go on returning its bound value with nothing to say so."
                                + " Spell it as application.yml names the key, or drop the"
                                + " annotation");
            }
        }
        for (Declaration declared : declarations) {
            seed(declared.key());
        }
    }

    /**
     * One key's boot precedence, applied.
     *
     * <p>A pinned key is written unconditionally; an unpinned one is written
     * only into a gap. {@link RuntimeConfig#get} answering empty is what "a gap"
     * means, and that store's class comment argues why empty and {@code ""} have
     * to stay different answers — collapsing them here would make a shipped
     * default overwrite a value an operator deliberately blanked.
     *
     * <p>The map is not read at all when the key is pinned, which is the
     * short-circuit and not an accident: the answer does not depend on what is
     * there, so a pinned deployment does not owe a query per live key.
     */
    private void seed(String key) {
        if (operatorPinned(key) || store.get(key).isEmpty()) {
            store.put(key, environment.getProperty(key), BOOT);
        }
    }

    /**
     * Whether the value this server is running with is something other than what
     * the jar alone would have produced.
     *
     * <p>The class comment argues the shape; this is it. The packaged sources
     * are resolved a second time, in isolation, and a value that comes back
     * different is a value somebody outside the jar had a hand in — whether they
     * supplied the key from a higher source or filled in a placeholder the
     * shipped file left for them.
     *
     * <p>A key <b>no</b> jar-side source supplies is the operator's by
     * elimination — nothing shipped it, so whatever did is not the jar — and
     * that rule is stated by falling through rather than by a branch of its own.
     * An empty {@code shipped} is a resolver over no sources, which answers
     * {@code null} against a value the environment has (every key here passed
     * {@code containsProperty} above), and a difference is a pin. The explicit
     * {@code if (!reached) return true;} that stood here said the same thing
     * twice: no test could tell the two apart, and a branch no test can hold is
     * a claim about behaviour that nothing checks. {@code
     * a_key_no_jar_side_source_supplies_overwrites_the_map} holds the path.
     *
     * <p><b>What a comparison cannot see, measured on a real boot rather than
     * reasoned about:</b> an operator who pins a key to exactly the value that
     * already ships — {@code PLOWSHARE_INGEST_BUDGET=1000} against a shipped
     * {@code 1000} — is indistinguishable from one who pinned nothing, and a
     * runtime write of {@code 1200} survives their restart. No signal in the
     * environment separates the two: the placeholder resolved to the same text
     * either way. It is the mild direction of the two errors — a pin that names
     * the shipped number is ignored, rather than a runtime write being reverted
     * on every boot — and it is what the listing spec §5 asks for will say, so
     * the answer an operator is shown before writing is the answer they get.
     *
     * <p><b>Package-private, and the boot is no longer its only caller.</b>
     * {@code RuntimeConfigController} reports this per key as {@code pinned}, so
     * an operator is told before writing whether their change survives a
     * restart. It is asked rather than copied for the reason the paragraphs
     * above are as long as they are: three drafts of this rule read a real
     * deployment's pin as a shipped default, and a second implementation in a
     * controller would be the one nobody re-derived. Safe to ask after the boot
     * because it reads only {@link ConfigurableEnvironment#getPropertySources},
     * which is fixed once the context has refreshed — the answer is the same
     * answer on every call for the life of the process, which is also why the
     * controller does not cache it.
     *
     * <p>{@link ConfigurationPropertySources#createPropertyResolver} rather than
     * a bare {@code PropertySourcesPropertyResolver}, so the isolated read
     * resolves a key exactly as the environment it is being compared against
     * does.
     *
     * <p><b>Unresolvable placeholders are ignored rather than thrown on.</b> A
     * shipped {@code ${SOME_SECRET}} with no default resolves in the environment,
     * because the operator supplied the variable, and cannot resolve over {@code
     * shipped}, because supplying it is the whole of what they did — which is
     * precisely the case where the jar alone has no answer and the operator has.
     * Left to throw, it throws inside {@code afterSingletonsInstantiated} and
     * costs the boot: a key pinned correctly would refuse to start the server.
     * All 36 placeholders in today's {@code application.yml} carry a default, so
     * nothing in this tree has that shape yet and the flag looks inert; {@code
     * a_shipped_placeholder_the_jar_cannot_resolve_is_a_pin_and_not_a_refusal}
     * brings the shape rather than trusting the argument.
     */
    boolean operatorPinned(String key) {
        MutablePropertySources shipped = new MutablePropertySources();
        boolean reached = false;
        for (PropertySource<?> source : environment.getPropertySources()) {
            reached = reached || cameWithTheJar(source, key);
            if (reached) {
                shipped.addLast(source);
            }
        }
        ConfigurablePropertyResolver asShipped =
                ConfigurationPropertySources.createPropertyResolver(shipped);
        asShipped.setIgnoreUnresolvableNestedPlaceholders(true);
        return !Objects.equals(environment.getProperty(key), asShipped.getProperty(key));
    }

    /**
     * Whether this source supplies this key on the jar's behalf.
     *
     * <p>Two shapes, and they are told apart by different things because they
     * are different things. A packaged file is text read off the classpath, and
     * the origin behind the value is what says so. {@code defaultProperties} is
     * a map written in {@code main} — code, shipped in the same jar, and just as
     * much not-an-operator — and it has <em>no</em> origin to be recognised by,
     * which is exactly why the classpath rule alone reads it as somebody
     * outside.
     *
     * <p><b>So it is identified by its name, which is the one case where a name
     * is the contract.</b> {@link DefaultPropertiesPropertySource#hasMatchingName}
     * is {@code NAME.equals(source.getName())} and nothing else — the constant,
     * asked through Boot's own predicate rather than copied here as a literal —
     * and {@code SpringApplication.setDefaultProperties} installs the source
     * under that name. The class comment's objection to reading names is to
     * reading a {@code toString}; this is an API constant that the framework
     * writes and reads on both sides.
     *
     * <p>A source that cannot list its own keys is not asked; the class comment
     * says which source that excludes and what it would otherwise cost.
     */
    private static boolean cameWithTheJar(PropertySource<?> source, String key) {
        if (DefaultPropertiesPropertySource.hasMatchingName(source)) {
            return source.containsProperty(key);
        }
        if (!(source instanceof EnumerablePropertySource<?>)
                || !(OriginLookup.getOrigin(source, key) instanceof TextResourceOrigin text)) {
            return false;
        }
        Resource resource = text.getResource();
        while (resource instanceof OriginTrackedResource tracked) {
            resource = tracked.getResource();
        }
        return resource instanceof ClassPathResource;
    }

    /**
     * Every {@link Live} key this context declares, and where each was declared.
     *
     * <p>Its own method because the check above is not its only reader: boot
     * precedence walks the same list to decide, key by key, whether an operator
     * pinned a value outside the jar. Two scans would be two answers to "what is
     * live", and the one that drifted would be the one that seeds the map.
     *
     * <p>{@link ConfigurationPropertiesBean#getAll} and not {@code
     * getBeansWithAnnotation}, for one reason that matters and two that are
     * free. The reason: it hands back each bean's {@code @ConfigurationProperties}
     * annotation, so the prefix a key must sit under is read rather than
     * reconstructed, and boot precedence gets the same fact for nothing.
     *
     * <p><b>That is the whole of the reason, and two others were removed from
     * this paragraph because they are not true.</b> It claimed the swap also
     * found beans whose {@code @ConfigurationProperties} sits on a {@code @Bean}
     * factory method, and that it selected from definitions rather than
     * instantiating every singleton. Measured against 3.3.5:
     * {@code getBeanNamesForAnnotation} already falls through to {@code
     * RootBeanDefinition.getResolvedFactoryMethod} and searches its annotations,
     * and it already iterates {@code beanDefinitionNames} with {@code getBean}
     * called only on the matches — the same shape {@code getAll} has. The old
     * scan did both. An unsourced claim beside a sourced one reads as equally
     * checked, which is how a nearly-true comment outlives the thing it
     * describes.
     *
     * <p>{@link ClassUtils#getUserClass} stays on top of it, and is not
     * redundant with it. Measured against Boot 3.3.5's bytecode: {@code
     * ConfigurationPropertiesBean.get} passes {@code bean.getClass()} straight
     * into its bind target, so a bean the container decided to proxy is
     * described by the proxy subclass. Reading accessors off that would find a
     * subclass's copies of them rather than the ones the source declares.
     *
     * <p>Sorted, and not incidentally. Unsorted, the refusal reported for a tree
     * with two misspelled keys is decided by {@code Class#getMethods}, whose
     * order is undefined, and then by bean registration order — so which typo a
     * boot names would depend on the order somebody happened to list two classes
     * in, and could change between JVMs with nothing about the source changed.
     * A refusal is read by a person under time pressure; the same tree should
     * always hand them the same sentence.
     *
     * <p>Package-private: it is the seam this class is factored along, not a
     * question anything outside {@code config} should be asking. What is live is
     * answered for the rest of the server by the config API, over the same list.
     */
    List<Declaration> declared() {
        List<Declaration> found = new ArrayList<>();
        for (ConfigurationPropertiesBean bean : ConfigurationPropertiesBean.getAll(beans).values()) {
            Class<?> owner = ClassUtils.getUserClass(bean.getInstance());
            String prefix = bean.getAnnotation().prefix();
            for (Method method : owner.getMethods()) {
                Live live = AnnotatedElementUtils.findMergedAnnotation(method, Live.class);
                if (live != null) {
                    found.add(new Declaration(live.value(), prefix, owner, method));
                }
            }
        }
        found.sort(Comparator.comparing(Declaration::key).thenComparing(Declaration::where));
        return oneAccessorPerKey(found);
    }

    /**
     * The sorted scan, reduced to one row per accessor, and refused outright
     * when the declarations do not describe something readable.
     *
     * <p><b>A method that is not an accessor is refused</b>, because {@code
     * @Target(METHOD)} lets the annotation land on one and nothing downstream
     * would notice. {@code @Live} on a setter, or on anything returning {@code
     * void} or taking an argument, passes the environment check like any other
     * declaration, is reported as live by the config API, and is then read
     * through by nobody — so an operator writes the key and nothing changes,
     * which is the exact failure {@link Live} exists to refuse, arriving through
     * {@link Live} itself.
     *
     * <p><b>One accessor named twice collapses; two accessors on one key are
     * refused.</b> A covariant override compiles to a bridge method alongside
     * the real one, and Spring resolves the bridge back to its target when it
     * looks for annotations, so {@code getMethods} hands the same declaration
     * back twice — which would seed one key twice and list it twice. Deduplicating
     * on the accessor rather than on the key is what leaves the other case
     * visible: two <em>different</em> accessors claiming one key is a genuine
     * ambiguity, since the key would be seeded from one bound value and read
     * through both, and which accessor an operator's write reached would be
     * whichever one the caller happened to hold. Silence there is worse than a
     * refused boot, so it is refused.
     *
     * <p>Both refusals run over the sorted list, for the reason the sort exists:
     * a tree with two faults should name the same one on every JVM.
     */
    private static List<Declaration> oneAccessorPerKey(List<Declaration> sorted) {
        List<Declaration> unique = new ArrayList<>();
        for (Declaration declared : sorted) {
            if (!declared.isAccessor()) {
                throw new IllegalStateException(
                        declared.key() + " is declared @Live on " + declared.where()
                                + ", which is not an accessor: it "
                                + (declared.accessor().getParameterCount() > 0
                                        ? "takes an argument" : "returns void")
                                + ". @Live marks the accessor a live value is read through, and"
                                + " everything that walks these declarations takes it for one:"
                                + " the boot check passes, the config API reports the key as"
                                + " live, and an operator writes it and nothing happens, which is"
                                + " the failure the annotation exists to refuse. Move it to the"
                                + " accessor that returns the value");
            }
            Declaration previous = unique.isEmpty() ? null : unique.get(unique.size() - 1);
            if (previous == null || !previous.key().equals(declared.key())) {
                unique.add(declared);
            } else if (!previous.identity().equals(declared.identity())) {
                throw new IllegalStateException(
                        declared.key() + " is declared @Live on both " + previous.where()
                                + " and " + declared.where() + ". A key names one value: it would"
                                + " be seeded from one of the two bound values and read back"
                                + " through both, so which accessor an operator's write reached"
                                + " would be whichever one the caller happened to hold. Leave the"
                                + " annotation on the accessor that owns the key");
            }
        }
        return unique;
    }

    /**
     * One declaration: the key, the prefix it has to sit under, and the accessor
     * that named it.
     *
     * <p>The accessor is carried because a refusal that gave only the key would
     * send a reader grepping for a spelling that, by definition, exists nowhere
     * else in the tree. The prefix is carried because it is the enclosing
     * {@code @ConfigurationProperties} bean's, and this record is what outlives
     * the scan that had the bean in hand.
     */
    record Declaration(String key, String prefix, Class<?> owner, Method accessor) {

        /** What a reader is shown: the short name is the one they recognise. */
        String where() {
            return owner.getSimpleName() + "." + accessor.getName() + "()";
        }

        /**
         * What duplicates are collapsed on, and <b>deliberately not {@link
         * #where()}</b>.
         *
         * <p>A bridge method shares its target's declaring class and name, so
         * either would collapse it. Only this one keeps two classes of the same
         * simple name, in different packages, under one prefix, with {@code
         * @Live} on the same method name from collapsing into each other — which
         * would silently discard the second declaration through the one path
         * that exists to refuse it. Contrived, and the fix costs a package
         * name.
         */
        String identity() {
            return owner.getName() + "." + accessor.getName();
        }

        /**
         * Whether a value can be read through this at all.
         *
         * <p>Not "is it named {@code getX}": Boot binds a record component and a
         * fluent {@code ingestBudget()} as readily as a JavaBean getter, and a
         * naming rule here would be the derivation {@link Live} declines wearing
         * a different hat. What is asked instead is only what {@code
         * @Target(METHOD)} fails to ask — that there is a value coming back, and
         * that reading it does not require an argument nobody has.
         */
        boolean isAccessor() {
            return accessor.getParameterCount() == 0 && accessor.getReturnType() != void.class;
        }

        /**
         * Whether the key sits under the prefix its own class declares.
         *
         * <p>An empty prefix binds from the root of the environment, so every
         * key is under it and there is nothing to check. That is the {@code
         * @ConfigurationProperties} default rather than a case somebody has to
         * arrange, so it is answered here rather than at the call site.
         */
        boolean isUnderItsOwnPrefix() {
            return prefix.isEmpty() || key.startsWith(prefix + ".");
        }
    }
}
