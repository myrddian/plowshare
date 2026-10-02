package io.aeyer.plowshare.server.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Says that this accessor's value may be changed on a running server.
 *
 * <p>Everything without it is bound once and frozen into a constructor —
 * {@link RuntimeConfig}'s class comment describes that path and why it is the
 * default. This annotation is the whole of the difference between the two
 * classes of configuration, and it sits <b>on the accessor, in the properties
 * class that owns the key</b>, so the boundary is visible to a reader who is
 * already looking at the number. A central registry of live keys would be a
 * third thing to keep in step with {@code application.yml} and the class, and
 * the two that already exist are enough.
 *
 * <h2>It declares; it does not intercept</h2>
 *
 * <p>Marking an accessor does <b>not</b> make it read the map. The accessor's
 * own body does that, by asking {@link RuntimeConfig} with its bound value as
 * the fallback. Nothing here proxies a {@code @ConfigurationProperties} bean or
 * rewrites a call: a reader who stepped into a getter would find a value
 * arriving from somewhere the source does not mention.
 *
 * <p><b>Which means the annotation alone can lie, and nothing here catches
 * it.</b> Put {@code @Live} on an accessor and stop, and the key is refused-on-
 * misspelling, seeded, and listed by {@code GET /v1/config} as writable — while
 * the accessor still returns its bound field, so an operator's {@code PUT}
 * changes a row nothing reads. That is exactly the defect Task 4a was opened to
 * repair after Task 4 shipped it, and the boot check cannot see it: {@code
 * RuntimeConfigSeed.oneAccessorPerKey} refuses a non-accessor and a duplicated
 * key, {@code isUnderItsOwnPrefix} refuses a key outside the bean's own prefix,
 * and none of the three reads a method body. The check that the value is
 * actually live is a test asserting behaviour after a write — {@code
 * IngestServiceTest}'s three budget tests are the pattern, and the one Task 6
 * left behind ({@code
 * a_write_to_the_runtime_map_reaches_neither_the_registry_nor_the_fence}) is the
 * mirror image, holding a key that is deliberately <em>not</em> live.
 *
 * <p><b>That argument used to lean on a count — "invisible machinery for three
 * keys" — and the count is now one, so the count is not what it rests on.</b>
 * Spec §6.1 found the third key is not a property Spring binds at all, and
 * §6.2 found the second has no reader that can honour a runtime write; {@code
 * plowshare.documents.ingest-budget} is the whole set. A proxy over one bean is
 * not much machinery, and pretending otherwise would be arguing from a number
 * that moved. What survives the number is the legibility: {@code
 * DocumentsProperties#ingestBudgetNow} says {@code live.intOr(...)} in its own
 * body, where the proxy version would be a getter reading {@code return
 * ingestBudget} and returning something else. One key is precisely the case in
 * which nothing gives a reader a reason to suspect a proxy is there.
 *
 * <p><b>And what the annotation itself buys does not shrink with the count,
 * which is why it is kept at one key.</b> Three separate mechanisms need the
 * <em>set</em> of live keys and none of them can get it by reading an accessor
 * body: the boot check that turns a misspelled key from a permanently ignored
 * write into a refusal, the seed's precedence pass, and {@code GET /v1/config}.
 * The alternative to declaring is a central registry, which is a third thing to
 * keep in step with {@code application.yml} and the class — the same cost at one
 * key as at ten, and paid somewhere a reader of the number is not standing. So
 * the ratio is genuinely thin today, and it is thin in the direction of an
 * annotation that costs one line on one accessor.
 *
 * <p>So what reads this annotation is everything that needs the <em>list</em>:
 * {@link RuntimeConfigSeed} refuses a boot whose declaration does not hold up
 * and seeds the map from an operator's pinned value, and the config API answers
 * "what is live?" from it. Those need a set of keys and cannot get one by
 * looking at accessor bodies.
 *
 * <h2>Top-level {@code @ConfigurationProperties} beans only</h2>
 *
 * <p>{@link RuntimeConfigSeed} finds a declaration by walking the beans
 * annotated {@code @ConfigurationProperties} and reading their accessors, so an
 * accessor this annotation can reach is one on such a bean. A nested type
 * carries no annotation of its own: {@code PoolProperties} is an element of
 * {@code List<PoolProperties>} inside {@code LlmProperties}, and the scan never
 * reaches it. A {@code @Live} there would be invisible in every direction at
 * once — no refusal for a typo, nothing seeded, nothing listed, and an accessor
 * reading a key nothing validated.
 *
 * <p>Naming a list element is beyond it in any case. The value here is fixed at
 * compile time and {@code plowshare.llm.pools[0].base-url} is indexed by
 * position, with one {@code PoolProperties} per configured pool, so whatever a
 * single constant said would be right for at most one of them.
 *
 * <p>That is a limit, not an oversight. Spec §6.1 records what lifting it would
 * cost — {@code pools} becoming a name-keyed map, which changes an
 * operator-visible config shape and every deployment's {@code application.yml}
 * — and defers it as its own decision.
 *
 * <h2>The key is written out, and a boot check is what pays for that</h2>
 *
 * <p><b>Nothing derives the key from the accessor, and after the naming
 * convention below there is nothing left to derive it from.</b> {@code @Live}
 * sits on {@code ingestBudgetNow()} — no {@code get} prefix, so Boot's {@code
 * JavaBeanBinder} does not see it as a property at all, and the annotation is
 * the sole source of the key rather than a restatement of one.
 *
 * <p>It was a restatement once. This paragraph argued, while {@code @Live} was
 * still on {@code getIngestBudget}, that deriving the key here would mean
 * reproducing relaxed binding — {@code getIngestBudget} to {@code ingest-budget}
 * under the enclosing prefix — in a second place, and that a second
 * implementation of a naming rule agrees with the first until a key is spelled
 * unusually. That reasoning holds and the rename only strengthened it: the
 * question is no longer whether to duplicate a derivation but that there is no
 * name to derive from.
 *
 * <p>What naming it costs is that a typo matches nothing, silently: the map
 * would hold {@code ingest-budget} and the accessor would ask for {@code
 * ingest-budgett}, get nothing, and return its bound value for ever, with an
 * operator watching a written value have no effect. So {@link
 * RuntimeConfigSeed} refuses the boot when a declared key names no property
 * Spring bound. That is the same trade the seven validating {@code
 * @Configuration} classes make: a mistake in a string is caught once, at start,
 * rather than repeatedly and quietly at use.
 *
 * <p><b>The value is the key as Spring canonicalises it</b> — lower case, dashed
 * — because it is also the key the map is stored under and the key a {@code PUT}
 * names. A camelCase spelling that relaxed binding would happily accept is
 * refused at boot rather than accepted here and then missed by every later
 * lookup, which is the same failure the check exists for wearing a different
 * hat.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Live {

    /** The full property key, as Spring binds it. */
    String value();
}
