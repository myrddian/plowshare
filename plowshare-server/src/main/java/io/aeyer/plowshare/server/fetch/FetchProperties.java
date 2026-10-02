package io.aeyer.plowshare.server.fetch;

import io.aeyer.plowshare.server.config.Live;
import io.aeyer.plowshare.server.config.RuntimeConfig;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The four numbers the fetch facade needs, split two and two — an operator's
 * levers on one side, values bound once and frozen into a constructor on the
 * other. {@code SearchProperties} is the shape this class copies; the split
 * itself is not the same split, and the two halves below argue why.
 *
 * <h2>{@code ttl} and {@code live-window} are {@link Live}</h2>
 *
 * <p>Both govern {@code FetchedPageStore.purgeExpired} — not built by this
 * task, but the reader of both keys once it lands — and both are exactly the
 * kind of number {@code plowshare.search.result-set-ttl} already established
 * as a live one: a schedule an operator watches run and retunes without a
 * restart. A server that shipped {@code ttl: PT24H} and is holding onto pages
 * for a week longer than a disk budget allows should not need a redeploy to
 * say so, and neither should a {@code live-window} that turns out too short
 * for how long an agent actually spends inside one long document.
 *
 * <h2>{@code window} is not, and the reason is what it is <em>for</em></h2>
 *
 * <p>{@link #getWindow} is not a fetch-side number at all — nothing in this
 * package reads it. It is the size of the slice a later reader hands a model
 * for one page-turn, which makes it the shape of what every agent is shown,
 * not a lever over how this server behaves. Changing it while pages already
 * fetched are being read mid-document would mean two agents reading the same
 * stored text see different page boundaries depending on which one asked
 * first — a change to the unit of reading itself, which is exactly the class
 * of decision {@link RuntimeConfig}'s own class comment reserves for a restart
 * rather than a {@code PUT}. {@code plowshare.documents.chunk-target-tokens} is
 * the same shape already: a number that defines what a unit of the corpus
 * <em>is</em>, bound once, for the same reason.
 *
 * <h2>{@code timeout} is not, on {@code plowshare.search.timeout}'s own
 * argument</h2>
 *
 * <p>It is a transport bound handed to {@code Call#timeout()} for one fetch,
 * on the exact terms {@code SearchProperties#getTimeout()} argues for one
 * search call: an operator retuning it is adjusting a client library's dial,
 * not steering traffic away from a misbehaving rung or recovering a server
 * from a bad number without a restart. Nothing about it needs to reach the
 * next fetch before the next deploy does.
 *
 * <h2>Two live keys, not four — and that is the whole of the count</h2>
 *
 * <p>{@link Live}'s own class comment argues that what the annotation buys —
 * the boot check, the seed's precedence pass, {@code GET /v1/config}'s
 * listing — does not shrink with how many keys carry it, because none of
 * those three can get the set of live keys by reading an accessor's body.
 * That argument does not depend on this class agreeing with {@code
 * SearchProperties}' count of four; it depends only on {@code ttl} and {@code
 * live-window} being numbers an operator needs to retune a running server
 * over, which {@code window} and {@code timeout} are not.
 *
 * <h2>{@link RuntimeConfig} holds no {@code durationOr}, on purpose</h2>
 *
 * <p>{@link RuntimeConfig}'s own class comment refuses to hold an opinion
 * about what a key <em>is</em> — no per-key typing, no registry of which key
 * is which shape, no default of its own. Both live accessors below read
 * {@link RuntimeConfig#get} and parse the string themselves, naming their own
 * type and their own fallback, exactly as {@code
 * SearchProperties#resultSetTtlNow()} does for the one duration-typed live key
 * that class already carries.
 */
@ConfigurationProperties(prefix = "plowshare.fetch")
public class FetchProperties {

    private static final Logger log = LoggerFactory.getLogger(FetchProperties.class);

    /**
     * The key {@link #ttlNow()} is live under, written once.
     *
     * <p>{@link Live} takes a compile-time constant and {@link RuntimeConfig}
     * takes the same string at runtime, so the alternative is the key spelled
     * twice a few lines apart — and the boot check that makes a misspelling a
     * refusal reads the annotation, so the copy that could drift is the one
     * nothing checks. {@code SearchProperties#LADDER} is the pattern.
     */
    private static final String TTL = "plowshare.fetch.ttl";

    /** {@link #liveWindowNow()}'s key, on {@link #TTL}'s reasoning. */
    private static final String LIVE_WINDOW = "plowshare.fetch.live-window";

    /**
     * The map both {@code ...Now()} accessors read through, or {@code null}
     * where there is none.
     *
     * <p>Null is a supported state, on {@code DocumentsProperties#live}'s
     * reasoning: this is a POJO Spring binds, and a context with no
     * datasource has no {@link RuntimeConfig} bean to inject. Every live
     * accessor below falls through to its bound field in that case.
     *
     * <p>Not {@code final} and not a constructor parameter for Spring's own
     * sake — Spring binds this class by calling setters on an instance it
     * made through the no-arg constructor below, and a required constructor
     * argument that is not a bound property would take that away from it.
     * {@link #FetchProperties(RuntimeConfig)} exists beside it for exactly
     * the callers that are not Spring — see that constructor's own comment.
     */
    private RuntimeConfig live;

    /**
     * How long a fetched page is kept before it is a candidate for purging,
     * as Spring bound it — see the class comment for why this key is live.
     */
    private Duration ttl;

    /**
     * How recently a page must have been read for it to count as still being
     * read, as Spring bound it — see the class comment for why this key is
     * live.
     */
    private Duration liveWindow;

    /**
     * How many characters of a stored page's text one page-turn hands a
     * model, as Spring bound it — see the class comment for why this is a
     * bound-once value and not a fifth-and-third {@link Live} key.
     */
    private int window;

    /**
     * The budget one fetch call is given, as Spring bound it — see the class
     * comment for why this is an ordinary bound property, on {@code
     * SearchProperties#timeout}'s own reasoning for the identical shape.
     */
    private Duration timeout;

    /**
     * {@code plowshare.fetch.allow-private}: the {@code host:port} entries fetch
     * may reach on a private address, as Spring bound them. {@code FetchConfig}
     * parses them through {@link FetchAllowlist#parse}, and refuses to start on
     * a malformed entry or one naming a never-tier literal.
     *
     * <p>Bound once and deliberately not {@link Live}. This decides what the
     * server can reach, not how it behaves. A key the runtime map could change
     * would let whoever can write that map open a read channel onto the network
     * this server sits on (spec §2.5).
     */
    private List<String> allowPrivate = List.of();

    /**
     * The no-arg constructor Spring uses to build this bean before binding it
     * — {@code JavaBeanBinder} needs one, and {@link
     * #FetchProperties(RuntimeConfig)} below is a second constructor, which is
     * what keeps Spring from attempting constructor binding here at all: a
     * class with more than one declared constructor is not a candidate for
     * deduced constructor binding, so the presence of this constructor and
     * that one together is what pins Spring to the ordinary setter path.
     */
    public FetchProperties() {
    }

    /**
     * The constructor a caller that already holds a {@link RuntimeConfig}
     * uses instead of Spring's own setter-injection path — every test in
     * {@code FetchPropertiesTest} builds an instance this way, precisely
     * because a test has a database and a map to write through and no
     * container to ask for a bean.
     *
     * <p>Spring itself never calls this. {@code @ConfigurationProperties}
     * binding runs through the no-arg constructor above and {@link
     * #setLive}, on {@code DocumentsProperties}' own precedent for why {@link
     * #live} cannot be a required constructor argument in that path. This
     * constructor is the other path — direct construction, with no container
     * in the loop at all.
     */
    public FetchProperties(RuntimeConfig live) {
        this.live = live;
    }

    /**
     * {@link #ttl}, or what an operator has since changed it to — the value
     * as of this call, which is the one thing that can differ between two
     * calls a moment apart.
     *
     * <p>{@link RuntimeConfig} exposes no {@code durationOr} and is not meant
     * to grow one — see the class comment. This accessor is where the type
     * and the fallback belong instead, parsed by hand and falling back to
     * {@link #ttl} exactly as {@link RuntimeConfig#intOr} falls back to its
     * own caller's default: no row is the ordinary state of a server nobody
     * has written to and answers quietly, and a row that will not parse as a
     * {@link Duration} answers the same value but warns, naming the key, the
     * value that would not parse, and the value this method is answering with
     * instead.
     */
    @Live(TTL)
    public Duration ttlNow() {
        Optional<String> found = live == null ? Optional.empty() : live.get(TTL);
        if (found.isEmpty()) {
            return ttl;
        }
        String value = found.get();
        try {
            return Duration.parse(value.trim());
        } catch (DateTimeParseException notADuration) {
            log.warn("the runtime config map holds '{}' for {}, which is not an ISO-8601"
                    + " duration, so this server is answering with {} — the value it started"
                    + " with. Nothing checks a value on the way in, so this is what a mistyped"
                    + " write looks like from the reading end", value, TTL, ttl);
            return ttl;
        }
    }

    /** {@link #liveWindow}, live — {@link #ttlNow()}'s reasoning, in full. */
    @Live(LIVE_WINDOW)
    public Duration liveWindowNow() {
        Optional<String> found = live == null ? Optional.empty() : live.get(LIVE_WINDOW);
        if (found.isEmpty()) {
            return liveWindow;
        }
        String value = found.get();
        try {
            return Duration.parse(value.trim());
        } catch (DateTimeParseException notADuration) {
            log.warn("the runtime config map holds '{}' for {}, which is not an ISO-8601"
                    + " duration, so this server is answering with {} — the value it started"
                    + " with. Nothing checks a value on the way in, so this is what a mistyped"
                    + " write looks like from the reading end", value, LIVE_WINDOW, liveWindow);
            return liveWindow;
        }
    }

    /** {@link #ttl}, as bound — the value {@code FetchConfig} checks at boot,
     *  which is the operator's configured duration rather than whatever this
     *  instant's map holds, on {@code SearchConfig}'s own reasoning for
     *  reading the bound value rather than {@link #ttlNow()} at boot. */
    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    /** {@link #getTtl()}'s counterpart for {@link #liveWindow}. */
    public Duration getLiveWindow() {
        return liveWindow;
    }

    public void setLiveWindow(Duration liveWindow) {
        this.liveWindow = liveWindow;
    }

    /**
     * {@link #window}, as bound. A plain getter, not {@code windowNow()}:
     * this key is never read through {@link RuntimeConfig}, so an accessor
     * shaped like the two above — the shape {@link Live}'s own javadoc warns
     * a reader against trusting on sight — would claim a liveness this value
     * does not have. See the class comment for why it is bound once rather
     * than live.
     */
    public int getWindow() {
        return window;
    }

    public void setWindow(int window) {
        this.window = window;
    }

    /**
     * {@link #timeout}, as bound — on {@link #getWindow()}'s own reasoning
     * for why this is a plain getter and not a {@code ...Now()} accessor. See
     * the class comment for why this key is bound once, on {@code
     * SearchProperties#getTimeout()}'s own argument for the identical shape.
     */
    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    /** {@link #allowPrivate}, as bound. A plain getter: see its field for why this is never live. */
    public List<String> getAllowPrivate() {
        return allowPrivate;
    }

    public void setAllowPrivate(List<String> allowPrivate) {
        this.allowPrivate = allowPrivate == null ? List.of() : List.copyOf(allowPrivate);
    }

    /**
     * Setter injection, and the whole of how a bound POJO reaches a Spring
     * bean — {@code DocumentsProperties#setLive}'s pattern, copied exactly.
     *
     * <p>{@code required = false} because a context can legitimately have no
     * {@link RuntimeConfig} — see {@link #live} — and because the alternative
     * is a properties class that refuses to exist without a database.
     */
    @Autowired(required = false)
    public void setLive(RuntimeConfig live) {
        this.live = live;
    }
}
