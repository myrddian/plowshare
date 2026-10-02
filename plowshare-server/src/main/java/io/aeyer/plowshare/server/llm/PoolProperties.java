package io.aeyer.plowshare.server.llm;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.convert.DurationUnit;

/**
 * One inference host, declared once.
 *
 * <p>Concurrency is here rather than in a global setting because it is a
 * property of the box: the reference machine serves one chat stream at a time
 * and will embed two batches while doing it. A second machine is a second entry
 * and no code change.
 *
 * <p>Nothing on this class validates. A missing {@code name}, a {@code base-url}
 * with no scheme or a slot count of zero are all refused at boot by {@code
 * LlmConfig}, which is the only place that can name the offending pool and the
 * offending property to the operator. A setter that threw would report the same
 * fault as a binder stack trace with the pool index in it and nothing else. The
 * four null-coalescing setters below are not exceptions to that: they are here
 * so that an explicitly blank key or an omitted collection arrives as empty
 * rather than null, since {@code getApiKey().isBlank()} several layers away is a
 * NullPointerException with no configuration in the frame at all.
 *
 * <p><b>{@link #provider} is the one exception to that paragraph, and it is a
 * binder-level one rather than a setter that throws.</b> It is an enum, so a
 * value outside {@code openai | lmstudio} is refused by Spring before this class
 * is involved, in a message naming the full property path. That is a worse
 * message than {@code LlmConfig} would write and it is still the right trade: a
 * {@code String} would let {@code provder: lmstudio} — or any other typo — bind
 * to the default and leave a pool silently unable to discover anything, which is
 * an absence nothing downstream can distinguish from a node that has no vendor
 * API.
 */
public class PoolProperties {

    /** How this pool is named in a log line and in a saturation message. */
    private String name;

    private Map<String, Object> chatTemplateKwargs = new LinkedHashMap<>();
    public Map<String, Object> getChatTemplateKwargs() { return Map.copyOf(chatTemplateKwargs); }
    public void setChatTemplateKwargs(Map<String, Object> value) {
        chatTemplateKwargs = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }

    private CountingProperties counting = new CountingProperties();
    public CountingProperties getCounting() { return counting; }
    public void setCounting(CountingProperties value) { counting = java.util.Objects.requireNonNull(value); }

    public enum RequestStyle { LOCAL, CLOUD }
    public enum ApiAuth { BEARER, AZURE_API_KEY }
    private RequestStyle requestStyle = RequestStyle.LOCAL;
    private ApiAuth apiAuth = ApiAuth.BEARER;
    private RequestCapabilities defaultCapabilities = new RequestCapabilities();
    private Map<String, RequestCapabilities> requestCapabilities = new LinkedHashMap<>();
    public RequestStyle getRequestStyle() { return requestStyle; }
    public void setRequestStyle(RequestStyle value) { requestStyle = java.util.Objects.requireNonNull(value); }
    public ApiAuth getApiAuth() { return apiAuth; }
    public void setApiAuth(ApiAuth value) { apiAuth = java.util.Objects.requireNonNull(value); }
    public RequestCapabilities getDefaultCapabilities() { return defaultCapabilities; }
    public void setDefaultCapabilities(RequestCapabilities value) { defaultCapabilities = java.util.Objects.requireNonNull(value); }
    public Map<String, RequestCapabilities> getRequestCapabilities() { return Map.copyOf(requestCapabilities); }
    public void setRequestCapabilities(Map<String, RequestCapabilities> value) { requestCapabilities = value == null ? Map.of() : Map.copyOf(value); }
    public RequestCapabilities capabilitiesFor(String model) {
        return requestCapabilities.getOrDefault(modelFamilies.getOrDefault(model,""),defaultCapabilities);
    }

    private String billingRoute;
    private Map<String, String> modelFamilies = new LinkedHashMap<>();

    /** The OpenAI-compatible root, e.g. {@code http://localhost:1234/v1}. */
    private String baseUrl;

    /**
     * Optional Bearer token for this host, since different hosts have different
     * keys.
     *
     * <p><b>Never logged, never put in an exception message, never echoed back
     * in any form.</b> An auth failure is exactly the moment something wants to
     * print what it sent; a 401 from here says which pool refused it.
     */
    private String apiKey = "";

    /** Model names this host answers to, used verbatim as the request's
     *  {@code model} field. */
    private List<String> models = new ArrayList<>();

    /**
     * <b>Retired. It exists only so that a configuration still setting it stops
     * the boot instead of being ignored.</b>
     *
     * <p>Classes moved to {@link LlmProperties#getClasses() plowshare.llm.classes}
     * on 2026-09-07, because per pool a class could name two different models
     * and {@code LlmDispatcher.route} would then pick between them on queue
     * depth. That argument is written out where the key now lives.
     *
     * <p><b>Why the field is kept rather than deleted, which is the part worth
     * arguing.</b> {@link LlmProperties} is {@code ignoreUnknownFields = false},
     * so deleting the setter would also refuse this key — as a binder error
     * naming {@code plowshare.llm.pools[0].classes} and nothing else. That
     * message tells an operator the key is unknown; it does not tell them the
     * key moved, or where to. Binding it and refusing it in {@code
     * LlmConfig.validate} is what buys the sentence naming the pool and the new
     * location, which is the whole difference between an upgrade that takes a
     * minute and one that takes an afternoon.
     *
     * <p>Silently ignoring it was the third option and is the worst of the
     * three: an operator who upgraded without moving the key would get a server
     * whose classes resolve to nothing, whose {@code application.yml} still
     * looks correct, and whose only symptom is every agent naming {@code fast}
     * failing at its first call. That is the failure this repository keeps
     * deleting rather than adding.
     *
     * <p>Delete this field once no deployment can still be carrying the old key
     * — and accept the worse message when you do.
     */
    private Map<String, String> classes = new LinkedHashMap<>();

    /**
     * Which of this pool's models can be shown a picture.
     *
     * <h2>Declared, and never inferred from a name</h2>
     *
     * <p>The first capability this server has, and it is one capability with one
     * check — not a tag system, not a selection policy, and not roles as
     * queries. {@code 2026-09-07-a-class-means-one-thing-design.md} §5 deferred
     * a general one on exactly the grounds that a capability system designed
     * against imagined requirements is worse than none, and said vision would be
     * what made it concrete. This is that, at the size it made concrete and no
     * larger.
     *
     * <p><b>Placement on the pool, capability on the model</b>, which is the
     * correction that spec recorded and this is the first design that has to
     * obey it. Whether a model sees is a property of the model; whether
     * <em>this host</em> serves it in a build that exposes its vision tower is a
     * property of the pool. The same model on two boxes can differ, so the
     * declaration is per pool, keyed by the wire model name — {@link
     * #contextLengths}' shape and its binding measurement.
     *
     * <p><b>Nothing guesses.</b> This server does not look at {@code gemma-4-e4b}
     * and decide that it probably sees. A capability inferred from a string is
     * wrong the first time somebody names a model differently in their own
     * configuration, and it is wrong silently: the model answers that it cannot
     * see an image, one call at a time, with nothing in the configuration to
     * point at.
     *
     * <p>A name here that this pool does not serve is refused at boot by {@code
     * LlmConfig}. A declaration about a model a pool has never heard of is a
     * typo, and the alternative is an agent that is refused for needing vision
     * from a model an operator can see declared as having it.
     */
    private List<String> vision = new ArrayList<>();

    /**
     * Which backend this is, and therefore what may be asked of it beyond
     * {@code /v1}.
     *
     * <p>{@code openai} is the default because {@code /v1} is the contract every
     * backend honours, and a pool that declares nothing should be assumed to
     * offer nothing more. Naming a vendor here does not change a single chat or
     * embedding call; it only says that a second, vendor-specific endpoint
     * exists to answer questions {@code /v1} has no field for.
     */
    private Provider provider = Provider.OPENAI;

    /**
     * A model's context length, in tokens, where an operator has decided it
     * rather than leaving it to be discovered.
     *
     * <p><b>A configured value wins over a discovered one</b>, which is the
     * whole reason this key exists: an operator overriding what a node reports
     * is doing it on purpose, most plausibly to hold a margin under a length the
     * node will later reload at. Discovery fills the gap where nothing is
     * configured. Where neither answers, {@code LlmConfig} says so at boot.
     *
     * <p><b>The keys are wire model names, which contain dots and may contain
     * capitals</b> — {@code qwen3.5-9b} is the one this project runs — so the
     * question of whether Spring's relaxed binding mangles them is not
     * theoretical. <b>Measured on Spring Boot 3.3.5, this branch, 2026-08-31:
     * it does not.</b> Both {@code context-lengths[qwen3.5-9b]: 64000} and the
     * bare {@code context-lengths.qwen3.5-9b: 64000} bind the key verbatim, and
     * a mixed-case key keeps its case through the bare form too. The binder
     * canonicalises the path <em>to</em> a map, not the key <em>within</em> a
     * map whose values are scalars.
     *
     * <p>That is written down because the opposite is the plausible belief — it
     * was this field's own first draft, asserting in a javadoc and in a boot
     * refusal that the bare form "does not bind that key at all" — and a silent
     * failure to bind is exactly the shape nobody would catch: the pool binds,
     * the boot succeeds, and the length is never found. {@code
     * a_context_length_binds_under_a_model_name_with_a_dot_in_it} pins the
     * measurement so the belief cannot quietly become true again in a later
     * Spring.
     *
     * <p>The bracketed form is still what {@code application.yml} shows, as the
     * form that is explicit about where the key begins and ends.
     */
    private Map<String, Integer> contextLengths = new LinkedHashMap<>();

    /**
     * Wire model → harness profile, for models this pool serves. A model assigned
     * nothing runs {@code plowshare.harness.default-profile}.
     */
    private Map<String, String> harnessProfiles = new LinkedHashMap<>();

    /**
     * The prompt size, in tokens, at which a fold of a conversation on this model
     * becomes <b>due</b> — it then runs when the turn ends, if the conversation is
     * still over — where an operator has decided it rather than leaving it to be
     * derived. {@link #compactionNowThresholds} is the fold that runs inside a turn.
     *
     * <p><b>A configured value wins over the derived default</b>, exactly as
     * {@link #contextLengths} wins over a discovered length, and for the same
     * reason: an operator naming a number here is doing it on purpose. Where
     * nothing is configured, {@code FoldThresholds} derives it from whatever context
     * length the model turns out to be loaded at — none at 64K or less, 60% just
     * above, rising linearly to 75% at 200K — and argues it where it is written.
     *
     * <p><b>Why a number an operator can set at all, rather than a constant.</b>
     * The default was measured against one model on one box —
     * {@code qwen3.5-9b} on the reference node, whose prefill runs at about 295
     * tokens a second and whose generation decays from 31.1 to 8.1 tokens a
     * second between an empty context and 72 000 —
     * {@code implementation rationale} §4. A
     * different model has a different curve and its own best place to fold, and
     * so does the same model on a faster box. Keyed by wire model for that
     * reason, and per pool because the box is half of what was measured.
     *
     * <p>Folding is lossy and every fold compounds, so the value is a trade
     * rather than a maximum to be turned down: a smaller number makes every turn
     * cheaper and buys more summaries of summaries.
     *
     * <p>The keys are wire model names and bind verbatim under either spelling,
     * for the reason {@link #contextLengths} measures at length.
     */
    private Map<String, Integer> compactionThresholds = new LinkedHashMap<>();

    /**
     * The prompt size, in tokens, at which a conversation on this model folds <b>inside a
     * turn</b> — at the next tool-call boundary, before the next model call — where an operator
     * has decided it rather than leaving it to be derived.
     *
     * <p>{@link #compactionThresholds} is when a fold becomes <em>due</em>, and a due fold waits
     * for the turn to end; this is when one runs <em>now</em>, because a single turn of a hundred
     * model calls can outgrow the window before it ends (spec
     * 2026-09-30-fold-at-60-and-80, measured on a coder turn that reached 80 345 of 131 072
     * tokens with no fold possible). Where nothing is configured {@code FoldThresholds} derives
     * it from the model's context length — 90% at 64K or less, falling linearly to 80% at
     * 128K and 80% beyond. Keyed and bound exactly as {@link #compactionThresholds} is.
     *
     * <p>{@code LlmConfig} refuses a value that is not above this pool's due threshold for the
     * same model or that passes its configured context length, wherever it can see both.
     */
    private Map<String, Integer> compactionNowThresholds = new LinkedHashMap<>();

    /** Chat slots. One, for a box that generates one stream at a time. */
    /**
     * Whether a streaming call that asks for prefill progress may use LM
     * Studio's own websocket API to get it.
     *
     * <p><b>False by default, and that default is the whole safety argument.</b>
     * {@code /v1} is the contract every backend honours and stays the path every
     * call takes unless an operator turns this on for a pool they know is LM
     * Studio. Switching it on changes nothing for a caller that does not ask for
     * progress: the six-argument stream still goes to {@code /v1}.
     *
     * <p>Meaningless unless {@code provider} is {@code lmstudio}, and ignored
     * rather than refused in that case — a pool that names another backend has
     * no websocket namespace to offer and nothing to report.
     */
    private boolean prefillProgress = false;

    private int chat = 1;

    /** Embedding slots. */
    private int embedding = 2;

    /**
     * Swarm slots — spec 2026-09-29 §5. How many of this pool's chat slots the swarm scheduler may
     * fill at once; 0, the default, admits none. A ceiling below {@code chat}, so at least one chat
     * slot is never taken by swarm work: LlmConfig refuses anything but 0 or
     * {@code 1 <= swarm < chat}. Those slots are not reserved for a person — a person's live turn
     * shares them with every other unscheduled run. Bound at boot, never live — {@code pools} is a
     * list, which {@code @Live} cannot address.
     */
    private int swarm = 0;

    /**
     * The queueing budget for requests that name none.
     *
     * <p><b>{@code @DurationUnit} is on every {@code Duration} here for a
     * reason, and this is that reason once for all six.</b> Spring reads a
     * unit-less number as milliseconds, so {@code submit-timeout: 45} would
     * bind to 45ms — and a pool whose budget is 45ms sheds every request that
     * does not find a free slot instantly, which reads from the outside as a
     * dead endpoint. Nothing fails at boot and nothing is logged.
     *
     * <p>A bare number is exactly what an operator writes here, too: {@code
     * chat: 1} and {@code embedding: 2} are unit-less numbers in the same
     * block, and the key these replaced was {@code timeout-seconds}, so
     * carrying a value across means writing the number and dropping the suffix.
     * The annotation makes that reading correct rather than catastrophic. A
     * written unit still wins, in both directions — see {@code
     * a_written_unit_wins_over_the_field_s_default_unit}.
     */
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration submitTimeout = Duration.ofSeconds(30);

    /**
     * Read timeout for a blocking chat call.
     *
     * <h2>Sixty seconds is not enough for a model that thinks, and it survives
     * anyway because the calls that think no longer come this way</h2>
     *
     * <p><b>The measurement that put this in doubt.</b> On 2026-09-02 a 51-token
     * prompt took 95.9 seconds against qwen3.5-9b: 2 997 completion tokens, 6 571
     * characters of {@code reasoning_content}, and an answer of 1 965. The
     * question was arithmetic. Prompt size had nothing to do with it, which is
     * what every mitigation before that had been aimed at, and thinking cannot be
     * switched off — six ways were tried against the node and all six were
     * accepted and ignored.
     *
     * <p>So a blocking call on this pool's chat model cannot be relied on to
     * finish inside sixty seconds, and raising the number is not the fix: it
     * would trade a failure on a slow-but-healthy answer for a much longer wait
     * on a genuinely wedged host, which is the wall this bound exists to
     * enforce. <b>The fix is not to wait wall-clock time for an answer that
     * arrives in pieces.</b> {@code JobRuntime} streams every turn, so the calls
     * that think are bounded by {@link #streamingTimeout} between chunks under
     * {@link #maxStreamDuration} for the whole call, and never by this. Both
     * halves of that were checked rather than repeated: {@code maxStreamDuration}
     * is enforced in {@code OpenAiTransport.stream} on both sides of the queue —
     * a deadline checked in {@code onEvent} and a bounded {@code poll} on the
     * consumer — and {@code a_stream_that_never_stops_is_given_up_on} and
     * {@code a_stream_of_heartbeats_and_no_events_is_still_bounded} reach one
     * each. It is not OkHttp's {@code callTimeout} doing it, and could not be;
     * see {@link #maxStreamDuration}.
     *
     * <p><b>What that sentence still hid, and what it cost.</b> "Between chunks"
     * is where the eye stops, and the expensive silence is the one <em>before
     * the first chunk</em>: the node reads the whole prompt before it emits
     * anything, and {@link #streamingTimeout} is the bound on that too. Streaming
     * did not make time-to-first-token unbounded — it made it inactivity-bounded,
     * which is a different claim and a weaker one than this paragraph read as.
     *
     * <p><b>What still waits here, and why sixty is right for it.</b> Two model
     * calls in this server stay blocking: {@code Compaction}'s summariser and
     * the {@code Scribe}'s filing judgement. Both are prose calls with no tools,
     * both are on a synchronous write path, and — the part that matters —
     * <b>both already treat a failure as an answer</b>: a summariser that does
     * not come back leaves the history unfolded and the turn runs with all of
     * it, and a scribe that does not come back files flat. A long wait on either
     * is worse than a short one, because the caller is holding a request open
     * for something it is prepared to do without. That is the argument for
     * leaving this where it is, and it is an argument about those two call sites
     * rather than about the number.
     *
     * <p>It is not a claim that they never think. They will, and when one of
     * them starts failing on it the answer is to stream it rather than to raise
     * this — with a measurement, which is how this note got here.
     */
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration chatTimeout = Duration.ofSeconds(60);

    /**
     * Read timeout for a streaming chat call.
     *
     * <p>Per chunk, not per call: OkHttp's read timeout measures inactivity,
     * which is one of the two questions a stream needs asked and not a bound on
     * anything. An endpoint emitting a byte more often than this never trips it
     * — a keepalive comment, a proxy heartbeat, a model in a repetition loop —
     * so the total is {@link #maxStreamDuration}, enforced where the tokens
     * arrive.
     *
     * <p>This paragraph used to end "a total cap would kill a long generation
     * that is arriving perfectly well", offered as the reason a stream should
     * have no total bound at all. Both halves were wrong, and the field below
     * is what replaced them; the sentence outlived its own correction here
     * because it sat one field away from the answer.
     *
     * <h2>It bounds the silence before the first chunk too, which is the
     * expensive one</h2>
     *
     * <p><b>"Per chunk" reads as "between chunks", and the gap that actually
     * costs is the one before the first.</b> The node reads the whole prompt
     * before it emits anything, and that prefill is silent: no keepalive, no
     * partial frame, nothing OkHttp can count as activity. So this number is
     * also the budget for time-to-first-token, and it is the bound a long prompt
     * hits. Measured both ways against {@code okhttp 4.x} on 2026-09-02, with
     * the read and call timeouts set independently so each could be attributed:
     * a response whose <em>headers</em> were delayed past the read timeout failed
     * at it with {@code SocketTimeoutException("Read timed out")}, and one whose
     * <em>body</em> was delayed past it after the headers had arrived failed the
     * same way. Prefill fails here whichever side of the response head the node
     * spends it on.
     *
     * <p><b>Why 300 seconds and not the 90 it shipped as.</b> 90 was chosen
     * against inter-chunk gaps, which are milliseconds, and nothing had measured
     * a prefill. A novel 30 415-token prompt then took <b>119 seconds of prefill
     * in total silence</b> before its first chunk — so the shipped default failed
     * a healthy call by 29 seconds, on a prompt size that is ordinary once a
     * conversation has a few file reads in it. 300 gives that measurement 2.5x
     * of margin, and at the rate it implies — 30 415 tokens in 119 s, about 256
     * tokens a second — covers a prompt of roughly 76 700 tokens.
     *
     * <p><b>Corroborated, and the rate is the load-bearing part.</b> {@code
     * implementation rationale} measured the same
     * node independently and puts a <em>cold</em> prefill at about 300 tokens a
     * second, against the 256 the 119-second run implies. Two measurements of
     * one rate, agreeing to within the difference between a novel prompt and a
     * partly-cached one, is what a default is allowed to rest on. The same
     * document is why the ordinary case is comfortable: a turn that
     * <em>extends</em> a cached history prefills only what is new — about 24
     * seconds even at a 128 000-token depth — so the long silences here are the
     * cold ones, which happen on a novel prompt, on the rebuilt prompt after a
     * fold, and after anything that loses the node's cache.
     *
     * <p><b>What 300 still does not cover, stated rather than left to be
     * discovered.</b> This pool's chat model is loaded at 128 000 tokens
     * (verified against {@code GET /api/v0/models} on 2026-08-31, and recorded in
     * {@code application.yml}), and {@code Compaction} today folds only when the
     * history would exceed that — so a history sitting at the wall with a cold
     * cache is reachable, and prefills for 430 to 500 seconds depending on which
     * of the two rates is used. This will not cover that, and neither will
     * writing 500 here: {@link #maxStreamDuration} is 600, and the same document
     * puts generation at the wall near 125 seconds, so that turn wants something
     * like 550 to 625 seconds in total and is at or past the total bound before
     * this one is even consulted. <b>A stream at the 128 000-token wall is not
     * bounded wrongly by this number; it does not fit in the budget at all.</b>
     * The fix for it is the compaction threshold that document argues for —
     * folding at 30 000 to 45 000 rather than coasting to the wall — which keeps
     * the worst cold prefill near 150 seconds and leaves this with the 2x margin
     * it was chosen for. Raising this number instead would hide that.
     *
     * <p><b>The number is a floor under the measurement, not a solution to
     * prefill.</b> The fix in shape is to stop the prefill being silent, or to
     * give time-to-first-token its own budget and keep this one tight for the
     * gaps between chunks once tokens are flowing — both of which need a
     * consumer-side bound rather than a larger read timeout, because OkHttp has
     * one read timeout per client and cannot tell the two phases apart.
     *
     * <p>Raising this does raise what a wedged endpoint costs before it is given
     * up on, and that is the trade being made deliberately: failing a healthy
     * call is the worse of the two, the total is still {@link
     * #maxStreamDuration}, and a stream that is silent because the host is gone
     * is bounded there whatever this says.
     */
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration streamingTimeout = Duration.ofSeconds(300);

    /**
     * The whole of a streaming call, wall clock, however busy it looks.
     *
     * <p><b>Not the same knob as {@link #streamingTimeout} and the difference is
     * the point.</b> That one is inactivity between chunks; this one is the total
     * a stream may take. An endpoint emitting one byte more often than {@code
     * streaming-timeout} never trips the inactivity bound at all — an SSE {@code
     * : keepalive} comment, a proxy heartbeat, or a model in a repetition loop
     * with no {@code max_tokens} to stop it — and OkHttp cannot supply the total,
     * because {@code okhttp-sse} deliberately switches the call timeout off once
     * the response opens. So without this the stream has no upper bound in time,
     * and {@code LlmPool.submit} waits on it with no deadline of its own: one
     * chatty endpoint holds a lane slot and the Tomcat worker behind it for good.
     *
     * <p><b>Where it is enforced, since nothing in OkHttp does it.</b> {@code
     * OpenAiTransport.stream} fixes a {@code nanoTime} deadline before the call
     * starts and checks it on both sides of the event queue: the producer tests
     * it in {@code onEvent} and cancels the event source, and the consumer polls
     * the queue for no longer than what is left of it. Neither is redundant —
     * the producer's check is the only one that can stop the endpoint
     * generating, and the consumer's is the only one that fires for a stream
     * delivering bytes but no events, which an SSE keepalive comment produces
     * because {@code okhttp-sse} discards those before any listener sees them.
     * The two are reached by {@code a_stream_that_never_stops_is_given_up_on}
     * and {@code a_stream_of_heartbeats_and_no_events_is_still_bounded}.
     *
     * <p>Because the deadline is taken before the request is sent, it covers
     * connect and prefill as well as generation. It is therefore a real bound on
     * a stream whatever {@link #streamingTimeout} is set to, which is what makes
     * raising that one safe.
     *
     * <p>Generous on purpose, because the failure it must not cause is killing a
     * long generation that is arriving perfectly well. Ten minutes is far past
     * any single answer the reference box produces and far short of forever.
     */
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration maxStreamDuration = Duration.ofMinutes(10);

    /** Read timeout for an embedding call. Its own knob, as in Anchor: a batch
     *  of a hundred summaries and a single question are the same endpoint but
     *  not the same wait. */
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration embeddingTimeout = Duration.ofSeconds(30);

    private int retryMaxAttempts = 2;

    /**
     * Milliseconds, and the one field here that is not seconds.
     *
     * <p>Not an inconsistency but the same argument applied honestly: the
     * default is 500ms and the key this replaced was {@code
     * retry-initial-backoff-ms}, so the number an operator carries across is a
     * count of milliseconds. Reading it as seconds would turn {@code 500} into
     * eight minutes of backoff between attempts — a far worse outcome than the
     * unannotated default it would be replacing, which for this one field
     * happened to be right.
     */
    @DurationUnit(ChronoUnit.MILLIS)
    private Duration retryInitialBackoff = Duration.ofMillis(500);

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBillingRoute() { return billingRoute; }
    public void setBillingRoute(String value) { billingRoute = value; }

    /** Existing pools default to their stable name; a configured blank route is an error. */
    public String billingRoute() { return billingRoute == null ? name : billingRoute; }

    public Map<String, String> getModelFamilies() { return Map.copyOf(modelFamilies); }
    public void setModelFamilies(Map<String, String> value) {
        modelFamilies = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * A copy, because this bean is a singleton for the life of the process.
     *
     * <p>Handing out the field itself means one caller's {@code
     * models.removeIf(...)} silently changes what every later reader sees, with
     * no write to any configuration and nothing to grep for. {@link
     * io.aeyer.plowshare.server.llm.dispatch.LlmPool} already takes {@code
     * List.copyOf} of what it is given, and this class should not be relying on
     * its consumer being the careful one.
     *
     * <p>A shallow copy is a complete defence here only because the elements
     * are {@code String}. That is not a general rule — see {@link
     * LlmProperties#getPools()} for the case where it is not enough.
     *
     * <p>A copy and not {@code unmodifiableList}: a view stops the caller
     * writing through it but still changes underfoot if anything can reach the
     * list behind it, which is why {@link #setModels(List)} copies too. Neither
     * half is sufficient alone and no single assertion catches both — the
     * wrapper mutant passes a getter-only test — so the test asserts each
     * direction separately.
     */
    public List<String> getModels() {
        return List.copyOf(models);
    }

    /** Copied in as well as out: without this the caller keeps a live handle on
     *  the list it passed and can edit this pool through it afterwards, which
     *  is the same leak as {@link #getModels()} with the arrow reversed. */
    public void setModels(List<String> models) {
        this.models = models == null ? new ArrayList<>() : new ArrayList<>(models);
    }

    /** Copied out for {@link #getModels}' reason: a caller holding the live
     *  list could change what every later reader sees. */
    public List<String> getVision() {
        return List.copyOf(vision);
    }

    public void setVision(List<String> vision) {
        this.vision = vision == null ? new ArrayList<>() : new ArrayList<>(vision);
    }

    /** A copy, for the reason on {@link #getModels()}; keys and values are both
     *  {@code String}, so this one is complete too. Read by exactly one caller —
     *  the check in {@code LlmConfig} that refuses a pool still carrying this
     *  retired key. */
    public Map<String, String> getClasses() {
        return Map.copyOf(classes);
    }

    /** Copied in, for the reason on {@link #setModels(List)}. */
    public void setClasses(Map<String, String> classes) {
        this.classes = classes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(classes);
    }

    public Provider getProvider() {
        return provider;
    }

    /** Null-coalescing on the reason the other three setters give: a null here
     *  is a NullPointerException in a factory several layers away, with no
     *  configuration in the frame to name. */
    public void setProvider(Provider provider) {
        this.provider = provider == null ? Provider.OPENAI : provider;
    }

    /** A copy, for the reason on {@link #getModels()}; keys are {@code String}
     *  and values are {@code Integer}, so this one is complete too. */
    public Map<String, Integer> getContextLengths() {
        return Map.copyOf(contextLengths);
    }

    /** Copied in, for the reason on {@link #setModels(List)}. */
    public void setContextLengths(Map<String, Integer> contextLengths) {
        this.contextLengths = contextLengths == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(contextLengths);
    }

    /** A copy, for the reason on {@link #getModels()}; keys are {@code String}
     *  and values are {@code Integer}, so this one is complete too. */
    public Map<String, Integer> getCompactionThresholds() {
        return Map.copyOf(compactionThresholds);
    }

    /** Copied in, for the reason on {@link #setModels(List)}. */
    public void setCompactionThresholds(Map<String, Integer> compactionThresholds) {
        this.compactionThresholds = compactionThresholds == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(compactionThresholds);
    }

    /** A copy, for the reason on {@link #getModels()}. */
    public Map<String, Integer> getCompactionNowThresholds() {
        return Map.copyOf(compactionNowThresholds);
    }

    /** Copied in, for the reason on {@link #setModels(List)}. */
    public void setCompactionNowThresholds(Map<String, Integer> compactionNowThresholds) {
        this.compactionNowThresholds = compactionNowThresholds == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(compactionNowThresholds);
    }

    /** A copy, for the reason on {@link #getModels()}; keys and values are both
     *  {@code String}, so this one is complete too. */
    public Map<String, String> getHarnessProfiles() {
        return Map.copyOf(harnessProfiles);
    }

    /** Copied in, for the reason on {@link #setModels(List)}. */
    public void setHarnessProfiles(Map<String, String> harnessProfiles) {
        this.harnessProfiles = harnessProfiles == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(harnessProfiles);
    }

    public boolean isPrefillProgress() {
        return prefillProgress;
    }

    public void setPrefillProgress(boolean prefillProgress) {
        this.prefillProgress = prefillProgress;
    }

    public int getChat() {
        return chat;
    }

    public void setChat(int chat) {
        this.chat = chat;
    }

    public int getEmbedding() {
        return embedding;
    }

    public void setEmbedding(int embedding) {
        this.embedding = embedding;
    }

    public int getSwarm() {
        return swarm;
    }

    public void setSwarm(int swarm) {
        this.swarm = swarm;
    }

    public Duration getSubmitTimeout() {
        return submitTimeout;
    }

    public void setSubmitTimeout(Duration submitTimeout) {
        this.submitTimeout = submitTimeout;
    }

    public Duration getChatTimeout() {
        return chatTimeout;
    }

    public void setChatTimeout(Duration chatTimeout) {
        this.chatTimeout = chatTimeout;
    }

    public Duration getStreamingTimeout() {
        return streamingTimeout;
    }

    public void setStreamingTimeout(Duration streamingTimeout) {
        this.streamingTimeout = streamingTimeout;
    }

    public Duration getMaxStreamDuration() {
        return maxStreamDuration;
    }

    public void setMaxStreamDuration(Duration maxStreamDuration) {
        this.maxStreamDuration = maxStreamDuration;
    }

    public Duration getEmbeddingTimeout() {
        return embeddingTimeout;
    }

    public void setEmbeddingTimeout(Duration embeddingTimeout) {
        this.embeddingTimeout = embeddingTimeout;
    }

    public int getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    public void setRetryMaxAttempts(int retryMaxAttempts) {
        this.retryMaxAttempts = retryMaxAttempts;
    }

    public Duration getRetryInitialBackoff() {
        return retryInitialBackoff;
    }

    public void setRetryInitialBackoff(Duration retryInitialBackoff) {
        this.retryInitialBackoff = retryInitialBackoff;
    }

    /**
     * Name and base URL — the URL with any userinfo redacted — and deliberately
     * an allowlist of the two fields rather than every field.
     *
     * <p>Not "remember to leave the key out" — a generated {@code toString},
     * which is what the next field added to this class will reach for, takes
     * every field including {@link #apiKey}, and one {@code log.debug("binding
     * {}", pool)} then puts a live credential in a file somebody ships to
     * support. Adding a field here has to be a decision, so the format is
     * pinned by {@code the_key_never_reaches_a_log_line_through_toString}.
     *
     * <p><b>The allowlist alone was not enough, and that is the more useful
     * lesson.</b> A base URL is on it, and a base URL can carry a key in its
     * userinfo — so the allowlist was right about fields and leaked anyway. See
     * {@link #withoutUserInfo(String)}.
     */
    @Override
    public String toString() {
        return "PoolProperties(" + name + " -> " + withoutUserInfo(baseUrl) + ")";
    }

    /**
     * A base URL with any {@code user:password@} removed, for anything that
     * prints one.
     *
     * <p><b>A base URL can itself carry a credential, which is why this is not
     * cosmetic.</b> {@code https://user:sk-...@host/v1} is a legal thing to
     * configure and puts a key exactly where every {@code api-key} guard looks
     * past: {@link #toString} renders it, {@code LlmConfig}'s boot summary hands
     * the whole pool list to a {@code log.info}, and {@code application.yml}
     * enables INFO. So before this, a key written into the URL reached the boot
     * log of a perfectly healthy start.
     *
     * <p>That gap was three tasks interacting rather than anyone's mistake. The
     * two-field allowlist on {@link #toString} was written before anyone had
     * noticed a URL can carry userinfo; the transport's redaction was added
     * later and guarded only the transport's own message; the boot summary was
     * written later still and inherited neither. Redacting <em>here</em> is what
     * makes that recurrence structural rather than remembered: there is one
     * renderer of a base URL, and {@code OpenAiTransport} calls it.
     *
     * <p>Index arithmetic rather than a regular expression, and that is
     * deliberate in this codebase: a pattern applied to operator- or
     * model-supplied text is a ReDoS waiting to lock a whole server, and there
     * is nothing here a regex would express more clearly anyway.
     */
    public static String withoutUserInfo(String baseUrl) {
        if (baseUrl == null) {
            return "null";
        }
        int schemeEnd = baseUrl.indexOf("://");
        if (schemeEnd < 0) {
            // No authority delimiter, so no userinfo to hide — and this is the
            // common shape of the very fault the transport reports using this,
            // a missing scheme.
            return baseUrl;
        }
        int authority = schemeEnd + 3;
        int at = baseUrl.indexOf('@', authority);
        int slash = baseUrl.indexOf('/', authority);
        if (at < 0 || (slash >= 0 && slash < at)) {
            // The '@' is in the path, not the authority.
            return baseUrl;
        }
        return baseUrl.substring(0, authority) + "[redacted]" + baseUrl.substring(at);
    }

    /**
     * Which backend a pool is, for the one purpose of deciding what may be
     * asked of it beyond {@code /v1}.
     *
     * <p>Not a list of vendors this project supports — every entry here speaks
     * {@code /v1} for every chat and embedding call, and differs only in what
     * else it can be asked. Adding one is adding a decorator, not a second
     * transport.
     *
     * <p>Deliberately kept out of {@link #toString()}. That method is an
     * allowlist of two fields rather than a generated dump, on the grounds that
     * adding a field to a boot log line has to be a decision; this one is a
     * decision to leave it out, because {@code
     * the_key_never_reaches_a_log_line_through_toString} pins the exact rendering
     * and the pool's provider is already visible in the boot warning that is the
     * only place it changes anything an operator can act on.
     */
    public enum Provider {

        /** {@code /v1} and nothing else. A context length is whatever this pool
         *  was told; there is no field in the OpenAI model list to read one
         *  from. */
        OPENAI,

        /** {@code /v1} for every call, plus LM Studio's own {@code /api/v0}
         *  for the facts {@code /v1} has no field for. */
        LMSTUDIO
    }
}
