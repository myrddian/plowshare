package io.aeyer.plowshare.server.llm.dispatch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one entry point in front of every model.
 *
 * <p>Services ask for work, not for a host: a request names a model or a class of model, and this
 * is the only component that knows which pool serves it, at what URL, under what concurrency. Two
 * models on two machines are two pool entries and no change above this line.
 *
 * <p>A request's budget is passed through untouched, {@code null} included: the default belongs to
 * the pool, so the pool is what substitutes it. One place, and no caller that can forget.
 *
 * <p>Submission blocks. Callers get a completion, not a future — Anchor's shape, and its reasoning
 * holds: orchestration threads are cheap and mostly waiting on the model. Asynchrony can be added
 * for a caller that needs it; starting there would complicate every consumer for a need nothing has
 * yet.
 */
public final class LlmDispatcher implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(LlmDispatcher.class);

  private final List<LlmPool> pools;
  private final TokenLedger ledger;
  private final InferenceAccounting accounting;
  private final Duration promptTimeout;
  private final Duration foldTimeout;
  private final UnaryOperator<String> systemBinding;

  /** Specifiers whose context length has already been reported; see {@link #reportOnce}. */
  private final Set<String> reportedContextLengths = ConcurrentHashMap.newKeySet();

  /**
   * Pool, wire model and parameter triples already reported as undeliverable; see {@link #carried}.
   */
  private final Set<String> reportedDroppedParameters = ConcurrentHashMap.newKeySet();

  /**
   * Arguments are taken as given, on {@link LlmPool}'s reasoning and for the same reason: {@code
   * LlmConfig} builds the one of these that exists, from bound properties, and that is where an
   * empty pool list or two pools sharing a name has to be refused — at boot, naming the property,
   * so the server does not start half-configured. A guard here would report the same fault later
   * with less to say about it. {@code List.copyOf} still rejects a null list or a null element,
   * which is a programming error rather than a misconfiguration.
   *
   * <p>No system binding, which is not the same as an empty one: {@link #resolveSpecifier} answers
   * every {@code system} or {@code system.<type>} specifier with the empty-string default {@link
   * io.aeyer.plowshare.server.llm.LlmProperties#systemSpecifier} gives an unbound type, which reads
   * as "nothing is bound" and refuses exactly as it would through the three-argument constructor
   * with a real, empty {@code LlmProperties}. Every test and every call site that predates the
   * system binding existing takes this constructor and none of them names {@code system} in a
   * specifier, so nothing here changes under them.
   */
  public LlmDispatcher(List<LlmPool> pools, TokenLedger ledger) {
    this(pools, ledger, type -> "");
  }

  /**
   * The same dispatcher, with somewhere for {@code system} and {@code system.<type>} to resolve
   * through.
   *
   * <h2>A function and not an {@code LlmProperties}</h2>
   *
   * <p>{@code LlmProperties} lives one package up, and every class in {@code dispatch} reads the
   * other way: {@code LlmConfig} depends on this package, never the reverse. Taking the properties
   * object here would be the first edge running backwards, for a class that needs exactly one
   * method off it. A {@link UnaryOperator} is the whole of what {@link
   * io.aeyer.plowshare.server.llm.LlmProperties#systemSpecifier} is from this side — a type in, a
   * specifier out — and {@code props::systemSpecifier} is what {@code LlmConfig} passes; a test
   * that wants a binding with no {@code LlmProperties} in reach can pass a lambda instead.
   *
   * <p><b>The one guard, and it is here for the reason the paragraph above gives for having
   * none.</b> {@code List.copyOf(pools)} already rejects a null list or element at construction, so
   * a programming error on that argument is reported where it was made. A null {@code
   * systemBinding} was not rejected anywhere: nothing reads the field until the first {@code
   * system} or {@code system.<type>} specifier is routed, which on a server whose agents mostly
   * name a class is some later turn, on a fold's own thread, as an NPE with this constructor's
   * caller nowhere in the stack. Same class of mistake, same construction-time answer.
   *
   * @param systemBinding {@code type -> specifier}, exactly {@link
   *     io.aeyer.plowshare.server.llm.LlmProperties#systemSpecifier}'s contract: the empty string
   *     for a type nothing binds, never {@code null}
   */
  public LlmDispatcher(
      List<LlmPool> pools, TokenLedger ledger, UnaryOperator<String> systemBinding) {
    this(pools, ledger, systemBinding, InferenceAccounting.NONE);
  }

  public LlmDispatcher(
      List<LlmPool> pools,
      TokenLedger ledger,
      UnaryOperator<String> systemBinding,
      InferenceAccounting accounting) {
    this(pools, ledger, systemBinding, accounting, null, null);
  }

  /** Optional global prompt budgets; validated by LlmConfig before transports are built. */
  public LlmDispatcher(
      List<LlmPool> pools,
      TokenLedger ledger,
      UnaryOperator<String> systemBinding,
      InferenceAccounting accounting,
      Duration promptTimeout,
      Duration foldTimeout) {
    this.promptTimeout = promptTimeout;
    this.foldTimeout = foldTimeout;
    this.pools = List.copyOf(pools);
    this.accounting = Objects.requireNonNull(accounting, "accounting");
    this.ledger = ledger;
    this.systemBinding = Objects.requireNonNull(systemBinding, "systemBinding");
  }

  public io.aeyer.plowshare.server.llm.counting.PromptCount count(ChatRequest request) {
    Routing routing = route(request.specifier(), Lane.CHAT);
    return routing
        .pool()
        .countChat(routing.wireModel(), request.withSampling(carried(routing, request.sampling())));
  }

  public io.aeyer.plowshare.server.llm.counting.PromptCount count(EmbeddingRequest request) {
    Routing routing = route(request.specifier(), Lane.EMBEDDING);
    return routing.pool().countEmbedding(routing.wireModel(), request);
  }

  private void preflight(
      InferenceObserver observer,
      Routing routing,
      java.util.function.Supplier<io.aeyer.plowshare.server.llm.counting.PromptCount> work) {
    io.aeyer.plowshare.server.llm.counting.PromptCount count;
    try {
      count = work.get();
    } catch (RuntimeException unavailable) {
      count =
          io.aeyer.plowshare.server.llm.counting.PromptCount.unknown(
              routing.pool().name(), routing.wireModel(), "counter_unavailable");
    }
    observer.preflight(count);
  }

  public Completion complete(ChatRequest request) {
    Routing routing = route(request.specifier(), Lane.CHAT);
    Sampling sampling = carried(routing, request.sampling());
    InferenceObserver observer =
        accounting.begin(
            routing.pool().name(),
            routing.wireModel(),
            request.specifier(),
            Lane.CHAT,
            request.attribution());
    if (routing.pool().automaticCounting())
      preflight(
          observer,
          routing,
          () -> routing.pool().countChat(routing.wireModel(), request.withSampling(sampling)));
    Completion completion =
        routing
            .pool()
            .complete(
                routing.wireModel(),
                request.messages(),
                sampling,
                request.tools(),
                request.toolChoice(),
                request.submitTimeout(),
                observer,
                promptTimeout);
    return recorded(routing, request.specifier(), Lane.CHAT, completion)
        .captured(observer.capture());
  }

  /**
   * The same call, a token at a time.
   *
   * <p>Same pools, same specifier, same budget, <b>and now the same tools</b>: a streaming call is
   * the same inference resource held for longer, not a different kind of work. This method used to
   * refuse a request that carried tools, because nothing reassembled {@code tool_calls} deltas out
   * of an SSE body; {@code OpenAiTransport.stream} does that now, so the refusal is gone and a turn
   * loop can stream.
   *
   * <p><b>The reason it must is a measurement, not a preference.</b> A 51-token prompt took 96
   * seconds against {@code qwen3.5-9b} on 2026-09-02 — 2 997 completion tokens, almost all of them
   * reasoning — while the pool's {@code chat-timeout} is 60 seconds. Streaming does not make that
   * faster. It replaces a wall-clock read deadline with an inactivity one ({@code
   * streaming-timeout}) under a whole-call bound ({@code max-stream-duration}), which is the shape
   * that fits a model that thinks before it answers.
   *
   * <p>One call, one dispatch, no retry: {@code OpenAiTransport.stream} never replays a stream, so
   * a caller that claimed a budget before calling this has claimed it once however the stream ends.
   * {@code JobRuntime} does exactly that.
   *
   * <p>A stream nobody will abandon. Kept for callers with no cancellation of their own, so that "I
   * do not cancel" is written once here rather than as a {@code () -> false} at every call site —
   * one of which would eventually be a {@code null} instead.
   */
  public Completion stream(ChatRequest request, Deltas sink) {
    return stream(request, sink, () -> false);
  }

  /**
   * The same call, stopped early when {@code abandoned} says so.
   *
   * <p>See {@link LlmTransport#stream} for what the predicate is asked and when. What matters here
   * is what comes back: a stream stopped this way raises {@link CallerAbandonedException}, which is
   * deliberately not an {@link LlmException}, so a caller cannot report its own cancellation as the
   * endpoint having failed.
   *
   * <p>With durable accounting enabled, the worker records cancellation as a terminal outcome with
   * the last available provider observation. Missing usage remains unknown. The legacy success-only
   * TokenLedger is retained for compatibility constructors.
   */
  public Completion stream(ChatRequest request, Deltas sink, BooleanSupplier abandoned) {
    return stream(request, sink, abandoned, false);
  }

  /** Both compaction paths use the fold budget, independent of accounting availability. */
  public Completion streamFold(ChatRequest request, Deltas sink) {
    return stream(request, sink, () -> false, true);
  }

  /** A foreground skill summary uses the same fold limits and observes its tree's cancellation. */
  public Completion streamFold(ChatRequest request, Deltas sink, BooleanSupplier abandoned) {
    return stream(request, sink, abandoned, true);
  }

  private Duration foldTimeoutFor(LlmPool pool) {
    Duration general = promptTimeout == null ? pool.promptTimeout() : promptTimeout;
    if (foldTimeout == null) {
      return promptTimeout;
    }
    return general != null && general.compareTo(foldTimeout) > 0 ? general : foldTimeout;
  }

  private Completion stream(
      ChatRequest request, Deltas sink, BooleanSupplier abandoned, boolean fold) {
    Routing routing = route(request.specifier(), Lane.CHAT);
    Sampling sampling = carried(routing, request.sampling());
    InferenceObserver observer =
        accounting.begin(
            routing.pool().name(),
            routing.wireModel(),
            request.specifier(),
            Lane.CHAT,
            request.attribution());
    if (routing.pool().automaticCounting())
      preflight(
          observer,
          routing,
          () -> routing.pool().countChat(routing.wireModel(), request.withSampling(sampling)));
    Completion completion =
        routing.pool().stream(
            routing.wireModel(),
            request.messages(),
            sampling,
            request.tools(),
            sink,
            abandoned,
            request.toolChoice(),
            request.submitTimeout(),
            observer,
            fold ? foldTimeoutFor(routing.pool()) : promptTimeout);
    return recorded(routing, request.specifier(), Lane.CHAT, completion)
        .captured(observer.capture());
  }

  /**
   * The request's sampling, minus whatever the chosen transport cannot send, and a note naming what
   * was removed.
   *
   * <h2>Dropped, never silently</h2>
   *
   * <p>A profile is written against a model, and it reaches the model through a transport that may
   * not have a field for half of it. The failure that matters is not the drop -- it is that an
   * endpoint <em>ignores</em> a key it does not know rather than refusing it, so a configuration an
   * operator can read in a file is not the configuration that ran, and nothing at either end says
   * so. {@link LlmTransport#carries()} is the transport's own statement of its subset and this is
   * where the statement is applied.
   *
   * <p><b>Once per pool, per wire model, per parameter</b>, on the same reasoning as {@code
   * reportOnce} for context lengths: an ingest is hundreds of calls on one route, and a line per
   * call would bury the one line that matters. WARN and not INFO, because the state it reports is a
   * real divergence between what a file says and what the endpoint was told -- an operator tuning
   * {@code top_p} against a transport that cannot carry it would otherwise change nothing,
   * repeatedly, with no signal at all.
   *
   * <p>Costs one {@code isEmpty()} on the ordinary path: {@link Sampling#notCarriedBy} allocates
   * nothing for a request whose parameters the transport all accepts, and the set-building below is
   * reached only when something was actually lost.
   */
  private Sampling carried(Routing routing, Sampling requested) {
    Set<Sampling.Parameter> carries = routing.pool().carries(routing.wireModel(), requested);
    Set<Sampling.Parameter> dropped = requested.notCarriedBy(carries);
    for (Sampling.Parameter parameter : dropped) {
      String key = routing.pool().name() + '/' + routing.wireModel() + '/' + parameter;
      if (reportedDroppedParameters.add(key)) {
        log.warn(
            "pool '{}' cannot send {} to '{}', so the profile's value for it is"
                + " dropped from every call on this route. What the model is asked for"
                + " and what the profile says will differ until either the profile stops"
                + " naming it or the transport learns to carry it.",
            routing.pool().name(),
            parameter.declared(),
            routing.wireModel());
      }
    }
    return dropped.isEmpty() ? requested : requested.carriedBy(carries);
  }

  public Embeddings embed(EmbeddingRequest request) {
    Routing routing = route(request.specifier(), Lane.EMBEDDING);
    InferenceObserver observer =
        accounting.begin(
            routing.pool().name(),
            routing.wireModel(),
            request.specifier(),
            Lane.EMBEDDING,
            request.attribution());
    if (routing.pool().automaticCounting())
      preflight(
          observer, routing, () -> routing.pool().countEmbedding(routing.wireModel(), request));
    Embeddings embeddings =
        routing
            .pool()
            .embed(routing.wireModel(), request.input(), request.submitTimeout(), observer);
    // Through the same helper as the two chat call sites, and not its own
    // ledger call: three sites with two conventions is how the guard below
    // comes to cover two of them.
    record(routing, request.specifier(), Lane.EMBEDDING, embeddings.usage(), null);
    return embeddings.captured(observer.capture());
  }

  /**
   * Fail now if nothing serves this specifier.
   *
   * <p>For startup: an embedding model no pool declares would otherwise surface at the first write,
   * where the failure is <em>swallowed</em> so the write survives — leaving an archive that stores
   * everything, embeds nothing, and reports nothing. That is the exact shape of silent
   * under-coverage this project keeps finding the expensive way.
   *
   * <p>The lane is immaterial and {@code EMBEDDING} is simply the caller this exists for. Nothing
   * is submitted, so the load reading this takes is discarded; only the resolve step, which
   * consults the same immutable map for either lane, decides the answer.
   */
  public void requireServed(String specifier) {
    route(specifier, Lane.EMBEDDING);
  }

  /**
   * Fail now if a call on this specifier could land on a model that cannot be shown a picture.
   *
   * <h2>Every pool that serves it, and not merely one</h2>
   *
   * <p>{@link #route} picks the least-loaded pool, so a specifier served by two pools of which one
   * declares vision would work or not depending on which box happened to be idle. That is the worst
   * shape a capability check can have: green at boot, green in a test, and intermittently wrong in
   * production with the model itself reporting it — "I cannot see an image" — which reads as a
   * model limitation rather than as a configuration one.
   *
   * <p>So this is {@link #contextLength}'s rule with a different operator. That method takes the
   * <em>smallest</em> known length because the next call may go to any pool and a prompt is only
   * safe if it fits the tightest; this takes the <em>conjunction</em>, because the next call may go
   * to any pool and an image is only safe if every one of them can be shown it. Neither is routed
   * on load, and for the same sentence.
   *
   * <p><b>A pool that serves the specifier and declares nothing is a refusal</b>, not a pool
   * skipped. {@code contextLength} skips a pool that cannot say, because answering "no bound" would
   * disable compaction against every pool including the ones that could say. Here silence is an
   * answer: a pool that has not declared a model as seeing has said it does not, since nothing
   * about that declaration is discovered — see {@code PoolProperties.vision}, which is why nothing
   * here guesses from a name.
   *
   * <p>For startup, the way {@link #requireServed} is: an agent that needs vision from a model no
   * pool declares would otherwise find out at its first image, inside a job, where the whole report
   * is a model saying it saw nothing.
   *
   * @throws UnknownSpecifierException if nothing serves the specifier at all, which {@link #route}
   *     raises and this deliberately does not catch: "no pool serves this" is a different fault
   *     from "the pools that serve it cannot see", and answering the second when the first is true
   *     would send an operator to the wrong key
   * @throws LlmException if some pool serving it has not declared the model it resolves to as one
   *     that sees, naming every pool and what it declared
   */
  public void requireSees(String specifier) {
    // Resolved once, up front, and the resolved form is what both the loud
    // check below and the loop over pools use. route() alone resolving
    // system/system.<type> would not have been enough: this method used to
    // call pool.resolve(specifier) a second time with the raw specifier
    // after route() succeeded, which for a system specifier is a literal
    // string no pool's models or classes ever contain. Every wireModel
    // would come back null, blind would stay empty, and this would return
    // silently having checked nothing — the fault a_pool_with_every_slot
    // catches for load, that a vision check would have made for sight.
    String target = resolveSpecifier(specifier);
    // Raises for a specifier nothing serves, so that the two faults stay
    // two faults. Its message lists what IS configured, which is the more
    // useful sentence when the name itself is the mistake.
    route(target, Lane.CHAT);
    List<String> blind = new ArrayList<>();
    for (LlmPool pool : pools) {
      String wireModel = pool.resolve(target);
      if (wireModel != null && !pool.sees(wireModel)) {
        blind.add(pool.name() + " serving '" + wireModel + "'");
      }
    }
    if (!blind.isEmpty()) {
      throw new LlmException(
          "'"
              + specifier
              + "' resolves to a model that has not been declared as one"
              + " that sees, on "
              + blind
              + ". A call on this specifier is routed"
              + " to the least-loaded pool that serves it, so it is only safe to"
              + " send an image when EVERY such pool declares it. Add the wire"
              + " model name to that pool's plowshare.llm.pools[...].vision, or"
              + " point the agent at a specifier only vision pools serve.");
    }
  }

  /**
   * A configured {@code max-context-lengths} ceiling on any eligible pool caps the answer below. It
   * is never a capacity override: a larger maximum cannot raise a known window. If capacity is
   * unknown, it caps the fallback. Keeping both minima also protects a request that may be
   * load-balanced onto the pool with the smaller working budget.
   *
   * <p>The longest prompt a call on {@code specifier} may carry: {@code fallback} where it is
   * served and nobody can size it, and empty only where nothing serves it at all.
   *
   * <h2>Three tiers, and this method ranks them</h2>
   *
   * <p>In order, and the order is the whole contract:
   *
   * <ol>
   *   <li>a {@code pools[...].context-lengths[<model>]} entry, wherever an operator wrote one. It
   *       wins over everything, because holding a margin under what the node reports is a decision
   *       somebody took deliberately;
   *   <li>what the node itself reports — LM Studio's {@code GET /api/v0/models}, read for {@code
   *       loaded_context_length} and deliberately not {@code max_context_length}. Better
   *       information than any blanket number a configuration file can hold;
   *   <li>{@code fallback}, which is {@code plowshare.llm.default-context-length}.
   * </ol>
   *
   * <p><b>The third tier exists because its absence was a silent off-switch on compaction.</b>
   * {@code Compaction.foldIfItWouldNotFit} takes no decision without a bound, so a model nobody
   * could size never folded at all: the server kept working, conversations kept running, and the
   * only report was one warning at boot. What that ends in is a conversation that grows until the
   * endpoint refuses it — the failure compaction exists to prevent.
   *
   * <p><b>The fallback is a parameter and not a field</b>, because it is a policy rather than a
   * fact about the fleet, and the one caller that holds the policy is {@code Compaction}.
   * Everything above this line answers "what does the endpoint accept"; what to do when nobody can
   * say belongs to whoever has to decide without an answer.
   *
   * <p><b>Which tier answered is written to the log, once per specifier.</b> The bottom one is a
   * WARN and the other two are INFO: reaching the default means both the configuration and the node
   * came up empty, which on an LM Studio pool is a box that could not be probed. It cannot be a
   * boot line — the boot may not open a connection, so the middle tier is unknowable there, and
   * {@code LlmConfig} says at boot only what boot can know.
   *
   * <p><b>The one thing the LLM layer knows that a conversation cannot work without.</b> {@code
   * agents.Compaction} compares the previous turn's measured {@code prompt_tokens} against this;
   * without it there is no bound to compact towards, and until this method existed the number was
   * unreachable from a running server — {@link LlmPool} holds a provider as an {@link LlmTransport}
   * and neither it nor this class exposed one.
   *
   * <h2>The smallest wins, and it is not routed on load</h2>
   *
   * <p>{@link #route} picks the least-loaded pool, which is right for a call and wrong for this:
   * the answer would then change with the fleet's queue depths, and a conversation would compact or
   * not depending on which host happened to be idle when it asked. So every pool serving the
   * specifier is consulted and the <b>smallest</b> known length is the answer, because the next
   * call may go to any of them and a prompt is only safe if it fits the tightest.
   *
   * <p><b>A pool that cannot say is skipped rather than making the whole answer empty</b>, and the
   * direction that costs least is the reason. If one pool reports 128000 and another says nothing,
   * answering empty would disable compaction against both; answering 128000 bounds the prompt
   * against the one host that could be asked, which is strictly better than no bound at all. It is
   * not a guarantee about the silent pool, and this sentence is the whole of what is claimed.
   *
   * <p><b>Which is also why {@code fallback} is not folded in per pool.</b> Giving every silent
   * pool the default and then taking the smallest would turn a fleet with one 128000 host and one
   * silent host into a 64000 answer — the default outranking a real measurement, which is precisely
   * the ordering above forbids. The default is reached only when <em>no</em> pool could say
   * anything.
   *
   * <h2>An unserved specifier answers empty rather than throwing</h2>
   *
   * <p>Unlike {@link #route} and {@link #requireServed}, which exist to make an unknown specifier
   * loud. This is an advisory question asked before a turn, and a caller does the same thing for
   * "nothing serves it" as for "nobody knows": run without compacting. The run itself is what
   * reports the specifier — its first call raises {@link UnknownSpecifierException} naming every
   * pool — so a typo is still found at the point it can be acted on, one moment later and with far
   * more to say.
   *
   * <p><b>{@code fallback} must not blur that.</b> "Served, and nobody can size it" is the gap the
   * default fills; "nothing serves this name" is a different fact, and answering it with a number
   * would hand a caller a bound for a conversation whose very first call is going to raise. It is
   * what keeps {@code foldIfItWouldNotFit}'s {@code bound.isEmpty()} guard reachable rather than
   * dead.
   *
   * <p><b>May open a connection</b>, through a discovering provider's first probe, and must
   * therefore not be called on the boot path. {@code LlmConfig} does not.
   *
   * <h2>{@code system} and {@code system.<type>} are the one exception, and loudly rather than by
   * the rule just above</h2>
   *
   * <p>The obvious alternative — treat an unresolvable {@code system} specifier as just another
   * name nothing serves, and answer {@code fallback} the way an ordinary unserved specifier does —
   * was rejected. "Nothing serves this name" is a fact about the fleet that the specifier's own
   * first call surfaces loudly, as the section above argues; an unbound {@code
   * plowshare.llm.system} is a fact about a key nobody set, which no call this method's caller
   * makes will ever surface, since a fold's {@code Compaction} loop has no other reason to ask
   * about the model it already knows it is running against. Silence here is exactly the "resolves
   * to the default profile and never says so" failure {@code application.yml} warns {@link
   * #wireModelFor} would otherwise produce, one layer further from an operator's eyes.
   */
  public OptionalInt contextLength(String specifier, int fallback) {
    // Resolved once, up front, exactly as route() resolves for complete,
    // stream and embed. specifier itself is left alone below — the log
    // lines report on what the caller actually wrote (a fold agent's
    // "system.compaction", not whatever it happens to bind to today), on
    // the same reasoning contextLength already gives for reporting per
    // specifier and not per wire model: an operator reading the log wants
    // to find the line for the name in their agent definition.
    String target = resolveSpecifier(specifier);
    OptionalInt smallestKnown = OptionalInt.empty();
    OptionalInt smallestConfigured = OptionalInt.empty();
    OptionalInt smallestMaximum = OptionalInt.empty();
    boolean served = false;
    for (LlmPool pool : pools) {
      String wireModel = pool.resolve(target);
      if (wireModel == null) {
        continue;
      }
      served = true;
      smallestMaximum = smaller(smallestMaximum, pool.maxContextLength(wireModel));
      smallestConfigured = smaller(smallestConfigured, pool.configuredContextLength(wireModel));
      smallestKnown = smaller(smallestKnown, pool.contextLength(wireModel));
    }
    if (!served) {
      return OptionalInt.empty();
    }
    if (smallestKnown.isEmpty()) {
      int effective =
          smallestMaximum.isPresent() ? Math.min(fallback, smallestMaximum.getAsInt()) : fallback;
      reportOnce(
          specifier,
          () ->
              log.warn(
                  "specifier '{}': no pool serving it could say how long a prompt it accepts —"
                      + " nobody configured plowshare.llm.pools[...].context-lengths[{}]"
                      + " and no provider discovered one, which for an LM Studio pool"
                      + " means the node could not be asked. Falling back to"
                      + " plowshare.llm.default-context-length, {} tokens. Compaction will"
                      + " fold against {} tokens after applying any configured maximum;"
                      + " if the model is loaded at less than it,"
                      + " prompts will be refused before a fold takes them",
                  specifier,
                  specifier,
                  fallback,
                  effective));
      return OptionalInt.of(effective);
    }
    if (smallestMaximum.isPresent()) {
      smallestKnown = smaller(smallestKnown, smallestMaximum);
      smallestConfigured = smaller(smallestConfigured, smallestMaximum);
    }
    int bound = smallestKnown.getAsInt();
    if (smallestConfigured.isPresent() && smallestConfigured.getAsInt() == bound) {
      reportOnce(
          specifier,
          () ->
              log.info(
                  "specifier '{}' is bounded at {} tokens by a configured"
                      + " plowshare.llm.pools[...].context-lengths or max-context-lengths entry.",
                  specifier,
                  bound));
    } else {
      reportOnce(
          specifier,
          () ->
              log.info(
                  "specifier '{}' is bounded at {} tokens, which is what the node serving it"
                      + " reports it is loaded at.",
                  specifier,
                  bound));
    }
    return smallestKnown;
  }

  /** The smaller of what is already known and one more answer, either of which may be empty. */
  private static OptionalInt smaller(OptionalInt known, OptionalInt candidate) {
    if (candidate.isEmpty()) {
      return known;
    }
    if (known.isEmpty() || candidate.getAsInt() < known.getAsInt()) {
      return candidate;
    }
    return known;
  }

  /**
   * Writes {@code line} the first time this dispatcher is asked about {@code specifier}, and never
   * again.
   *
   * <p><b>Once per specifier and not once per ask, because the caller is compaction and it asks at
   * the end of every turn.</b> A line per ask is a line per utterance in every conversation on the
   * server, which is how a warning that matters gets filtered out by whoever reads the log.
   *
   * <p>The set is unbounded and that is not a leak worth guarding: its keys are model specifiers,
   * so it is bounded by the fleet's configuration rather than by traffic — a handful of entries for
   * the life of the process.
   *
   * <p><b>What it costs is that a change is reported once.</b> A node reloaded at a different
   * length while the server runs is already outside what {@code LmStudio.discover} handles — it
   * caches a successful probe for the life of the process — so this adds no new blind spot. A pool
   * that could not be probed at all is the case that matters, and that one is reported by {@code
   * LmStudio} itself on every failed probe.
   */
  private void reportOnce(String specifier, Runnable line) {
    if (reportedContextLengths.add(specifier)) {
      line.run();
    }
  }

  /**
   * The prompt size at which a conversation on {@code specifier} should fold its history, where an
   * operator configured one, or empty where nobody did.
   *
   * <p><b>The smallest known answer wins, and every pool serving the specifier is asked</b> — the
   * same rule {@link #contextLength(String, int)} follows and for the same reason: the next call
   * may go to any of them, and a threshold is only doing its job if it holds against the tightest.
   *
   * <p>Empty is the ordinary answer. Nothing here supplies a default, because the default is
   * derived from the context length and belongs where the derivation is argued; see {@code
   * Compaction}.
   *
   * <p><b>Opens no connection</b>, unlike {@link #contextLength(String, int)}: there is nothing to
   * discover, only what was configured.
   *
   * <p>Resolves {@code system} and {@code system.<type>} exactly as {@link #contextLength(String,
   * int)} does, including that an unbound key raises rather than answering empty — see that
   * method's javadoc for why silence was rejected.
   */
  public OptionalInt compactionThreshold(String specifier) {
    String target = resolveSpecifier(specifier);
    OptionalInt smallest = OptionalInt.empty();
    for (LlmPool pool : pools) {
      String wireModel = pool.resolve(target);
      if (wireModel == null) {
        continue;
      }
      OptionalInt threshold = pool.compactionThreshold(wireModel);
      if (threshold.isPresent()
          && (smallest.isEmpty() || threshold.getAsInt() < smallest.getAsInt())) {
        smallest = threshold;
      }
    }
    return smallest;
  }

  /**
   * The prompt size at which a conversation on {@code specifier} should fold <b>inside a turn</b>,
   * where an operator configured one, or empty where nobody did.
   *
   * <p>{@link #compactionThreshold(String)} exactly, over {@code compaction-now-thresholds}: every
   * pool serving the specifier is asked and the smallest known answer wins, no connection is
   * opened, and an unbound {@code system} key raises.
   */
  public OptionalInt compactionNowThreshold(String specifier) {
    String target = resolveSpecifier(specifier);
    OptionalInt smallest = OptionalInt.empty();
    for (LlmPool pool : pools) {
      String wireModel = pool.resolve(target);
      if (wireModel == null) {
        continue;
      }
      OptionalInt threshold = pool.compactionNowThreshold(wireModel);
      if (threshold.isPresent()
          && (smallest.isEmpty() || threshold.getAsInt() < smallest.getAsInt())) {
        smallest = threshold;
      }
    }
    return smallest;
  }

  /**
   * The model name the endpoint knows {@code specifier} by, or {@code null} where no pool serves
   * it.
   *
   * <h2>It exists because a profile is keyed on the wire model and nothing outside this class knows
   * one</h2>
   *
   * <p>An agent declares {@code model: fast}; a sampling profile is written for {@code gemma} or
   * {@code qwen3}. Turning the first into the second is this class's job and only this class's —
   * {@code LlmPool.resolve} holds the map — so the loader that layers a profile under an agent has
   * to ask.
   *
   * <p><b>No routing decision and no load reading</b>, unlike {@link #route}: the pools' specifier
   * maps are immutable and every pool that serves a specifier resolves it to the same wire model,
   * so the first answer is the answer. Nothing is submitted and nothing is measured.
   *
   * <p><b>That "same wire model" is a boot guarantee and not an obvious one.</b> It rests on all
   * three of {@code LlmConfig.requireClassesResolvable}'s refusals — one server-wide class map, so
   * no two pools can disagree about what a class names; a class whose model no pool serves refused;
   * and a class name that some pool lists as a <em>model</em> refused, which is the one that closes
   * this. Without the last, {@code classes: {qwen3.5-9b: qwen3.8-27b}} over a fleet where one pool
   * still lists {@code qwen3.5-9b} makes the two pools answer one specifier differently — and then
   * this method returns whichever pool comes first while {@link #route} sends the request wherever
   * load says, so the sampling profile is chosen for a model that is not on the wire. If a fourth
   * way for two pools to disagree is ever introduced, this paragraph and that method go together.
   *
   * <p>{@code null} rather than an exception for an unserved specifier, because the caller this
   * exists for is deciding what to log rather than whether to run: {@code AgentsConfig} refuses an
   * unserved model separately, through {@link #requireServed}, with a message naming the agent and
   * the directory. A second refusal here would report the same fault twice with less to say about
   * it the second time.
   *
   * <h2>{@code system} and {@code system.<type>} are resolved before that rule applies, and an
   * unbound key is not treated as merely unserved</h2>
   *
   * <p>This is the method the ruling that added this paragraph named by itself: {@code
   * application.yml}'s own comment on {@code SamplingProfiles.resolve} warns that a wire model
   * matching no profile "resolves to the default profile and never says so — the failure is silent
   * and looks like the model being bad." An agent declaring {@code model: system} with {@code
   * plowshare.llm.system} unset would get exactly that shape at one remove: {@code null} back from
   * this method, {@code AgentsConfig.sampled} resolving a sampling profile for a model named {@code
   * null}, and an operator watching an agent send the wrong temperature with nothing in the log
   * about a binding never being set. The paragraph above is right that an ordinary unserved
   * specifier should stay quiet here and let {@link #requireServed} say so with more to work with —
   * but a {@code system} specifier that fails to resolve has no second call site that would ever
   * raise it, since nothing about a fold agent's own {@code Compaction} loop asks {@link
   * #requireServed} about the model it is already running against. So this one case is loud,
   * through {@link #resolveSpecifier}, rather than joining the quiet rule the rest of this method
   * follows.
   */
  public String wireModelFor(String specifier) {
    String target = resolveSpecifier(specifier);
    for (LlmPool pool : pools) {
      String wireModel = pool.resolve(target);
      if (wireModel != null) {
        return wireModel;
      }
    }
    return null;
  }

  /**
   * Every pool, in the order the operator declared them. The swarm scheduler reads its ceilings
   * from here (spec 2026-09-29 §5); nothing routes through this.
   */
  public List<LlmPool> pools() {
    return pools;
  }

  /**
   * Every pool that would answer {@code specifier}, in declared order — the candidates {@link
   * #route} chooses among, before load is compared. Empty when none does. The swarm scheduler asks
   * this to know where a run may be admitted; it never routes.
   */
  public List<LlmPool> poolsServing(String specifier) {
    String target = resolveSpecifier(specifier);
    return pools.stream().filter(pool -> pool.resolve(target) != null).toList();
  }

  /**
   * {@link #stream(ChatRequest, Deltas, BooleanSupplier)}, on the pool the swarm scheduler admitted
   * the call to rather than the least loaded one — the scheduler's slot is a slot on THAT pool, so
   * the call must land there. The request's budget is passed through untouched; a scheduled caller
   * sends {@link LlmPool#NO_DEADLINE}.
   */
  public Completion streamOn(
      String pool, ChatRequest request, Deltas sink, BooleanSupplier abandoned) {
    LlmPool named =
        pools.stream()
            .filter(candidate -> candidate.name().equals(pool))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("no pool is named '" + pool + "'"));
    String wireModel = named.resolve(resolveSpecifier(request.specifier()));
    if (wireModel == null) {
      throw new UnknownSpecifierException(request.specifier(), named.describe());
    }
    Routing routing = new Routing(named, wireModel);
    Sampling sampling = carried(routing, request.sampling());
    InferenceObserver observer =
        accounting.begin(
            named.name(), wireModel, request.specifier(), Lane.CHAT, request.attribution());
    if (named.automaticCounting())
      preflight(
          observer, routing, () -> named.countChat(wireModel, request.withSampling(sampling)));
    Completion completion =
        named.stream(
            wireModel,
            request.messages(),
            sampling,
            request.tools(),
            sink,
            abandoned,
            request.toolChoice(),
            request.submitTimeout(),
            observer,
            promptTimeout);
    return recorded(routing, request.specifier(), Lane.CHAT, completion)
        .captured(observer.capture());
  }

  private record Routing(LlmPool pool, String wireModel) {}

  /**
   * Least loaded wins, measured as {@code (active + queued) / slots}.
   *
   * <p>This summary line said "Shortest queue wins." while the body four lines down said {@link
   * LlmPool#load} and not {@code queueDepth} — and a summary line is what an IDE tooltip shows, so
   * the wrong half was the half most readers saw. Depth-based routing is not a near-miss of this
   * rule: the design identifies it as <em>worse than the round-robin it was chosen over</em>, for
   * the reason set out below.
   *
   * <p>Not round-robin: the executor already knows what it is running and what it has queued, so
   * load-aware costs nothing and behaves better when two hosts differ in speed — which they will,
   * the reference machines being an M1 Max and an M5 Max. Strict {@code <} so that with equal load
   * the first pool declared wins, which keeps a single-pool deployment entirely predictable.
   *
   * <p><b>{@link LlmPool#load} and not {@code queueDepth}.</b> Queue depth is zero for a lane whose
   * every slot is occupied, because these executors only queue once the core is full, so routing on
   * depth cannot tell a saturated pool from an idle one and quietly sends the whole fleet to the
   * pool declared first. That claim is measured in {@code
   * a_pool_with_every_slot_busy_loses_to_an_idle_one}, which no depth-based router can pass. {@code
   * queueDepth} keeps its own use: it is what {@link LlmSaturatedException} reports, where "how
   * many are already waiting" is exactly the right number.
   *
   * <p><b>The load read is that of the lane this call will wait in</b>, and not the chat lane for
   * everything. A full chat lane says nothing about the embedding lane — having two is the point,
   * since a box generating one token stream will happily embed two batches while it does it — so
   * routing an embedding on the chat queue steers it away from a host whose embedding lane is
   * empty. It does that silently: both pools serve the specifier and both answers look correct,
   * which is why {@code an_embedding_routes_on_the_embedding_queue_and_not_the_chat_one} asserts on
   * which transport was reached rather than on the result.
   */
  private Routing route(String specifier, Lane lane) {
    String target = resolveSpecifier(specifier);
    LlmPool chosen = null;
    String wireModel = null;
    // Infinity and not MAX_VALUE: load is finite for every pool, since the
    // slot count it divides by has a floor of one, so the first pool that
    // serves the specifier is always chosen on a real comparison. The
    // `chosen == null` clause this replaces could never fire, and a dead
    // clause reads like a guard someone is relying on.
    double lightest = Double.POSITIVE_INFINITY;
    for (LlmPool pool : pools) {
      String resolved = pool.resolve(target);
      if (resolved == null) {
        continue;
      }
      double load = pool.load(lane);
      if (load < lightest) {
        chosen = pool;
        wireModel = resolved;
        lightest = load;
      }
    }
    if (chosen == null) {
      // The specifier the CALLER WROTE, and not `target`. For everything
      // that is not harness work the two are the same string. For
      // `system.memory` they are not: `target` is whatever
      // plowshare.llm.system-overrides.memory resolved to, so naming it
      // sends an operator looking for a pool to add a wire model to, when
      // what they almost certainly need is to point that key somewhere a
      // pool already serves. `resolveSpecifier` makes exactly this
      // argument for the unbound case and gets it right there; this is the
      // bound-but-unserved case and it is the same operator.
      throw new UnknownSpecifierException(
          specifier, pools.stream().map(LlmPool::describe).collect(Collectors.joining(", ")));
    }
    return new Routing(chosen, wireModel);
  }

  /**
   * {@code specifier}, unchanged, unless it names harness work rather than a class or a wire model
   * — in which case what {@code plowshare.llm.system}(-overrides) binds that name to, resolved as
   * far as it is safe to go automatically.
   *
   * <h2>Two names, one indirection</h2>
   *
   * <p>{@code system} is the plain binding; {@code system.<type>} is a type asking for its own.
   * Both are turned into a call on {@code systemBinding} — {@code ""} and {@code "<type>"}
   * respectively — because that is the whole of what {@link
   * io.aeyer.plowshare.server.llm.LlmProperties#systemSpecifier} needs to answer either question,
   * and a caller with no type to name should not have to invent one.
   *
   * <p><b>Everything else falls through untouched</b> — a class name, a wire model name, anything
   * not shaped like {@code system} or {@code system.<type>} — which is what keeps every specifier
   * that predates this method meaning exactly what it always meant. {@link #route} and {@link
   * #requireSees} both call this before touching a pool, so an agent declaring {@code model: fast}
   * takes the same path through this method it always did: one string comparison, no match,
   * returned as given.
   *
   * <h2>Refusal, not a loop, and not a fallback</h2>
   *
   * <p>What {@code systemBinding} answers is asked exactly once more — is it itself shaped like
   * {@code system} or {@code system.<type>}? — because the one thing worse than a specifier that
   * names nothing is a specifier that names another specifier that names nothing, forever. {@code
   * plowshare.llm.system: system} would do exactly that, one call this method makes on itself away
   * from a {@code StackOverflowError} with no caller who wrote the loop able to see it in a stack
   * trace. Refusing it outright, loudly, and once is the same call {@link
   * io.aeyer.plowshare.server.llm.LlmProperties#systemSpecifier} makes about a blank override not
   * counting as one: an indirection either lands somewhere real or it is treated as not being there
   * at all, never as a promise to keep chasing.
   *
   * <p>An empty answer — nothing bound for this type, and nothing bound generally either — is
   * exactly {@link #route}'s ordinary "nothing serves this" refusal, so it is raised the same way
   * and named by the specifier the caller actually wrote. {@code UnknownSpecifierException} naming
   * {@code ""} would send an operator looking for a key that is not the one they need to set;
   * naming {@code system.memory} sends them straight to {@code
   * plowshare.llm.system-overrides.memory}.
   */
  private String resolveSpecifier(String specifier) {
    if (!isSystemSpecifier(specifier)) {
      return specifier;
    }
    String type = specifier.equals(SYSTEM) ? "" : specifier.substring(SYSTEM_PREFIX.length());
    String target = systemBinding.apply(type);
    if (target.isEmpty()) {
      throw new UnknownSpecifierException(
          specifier, pools.stream().map(LlmPool::describe).collect(Collectors.joining(", ")));
    }
    if (isSystemSpecifier(target)) {
      throw new LlmException(
          "'"
              + specifier
              + "' is bound to '"
              + target
              + "', which is itself a system"
              + " binding rather than a class or a wire model. plowshare.llm.system"
              + " and plowshare.llm.system-overrides must each name something that"
              + " does not need this same resolution done to it again — chasing a"
              + " second indirection has no way to know how many are left to take"
              + " before one finally names a model, and plowshare.llm.system:"
              + " system would take that number to infinity. Point it at a class in"
              + " plowshare.llm.classes or at a wire model directly.");
    }
    return target;
  }

  private static final String SYSTEM = "system";
  private static final String SYSTEM_PREFIX = "system.";

  /**
   * {@code system} itself, or {@code system.<type>} for some type — the two shapes {@link
   * #resolveSpecifier} turns into a call on {@code systemBinding} rather than routing directly.
   */
  private static boolean isSystemSpecifier(String specifier) {
    return specifier.equals(SYSTEM) || specifier.startsWith(SYSTEM_PREFIX);
  }

  private Completion recorded(Routing routing, String specifier, Lane lane, Completion completion) {
    record(routing, specifier, lane, completion.usage(), completion.finishReason());
    // Stamped here because this is the only frame that knows both halves: a
    // transport does not know which specifier chose it, and a caller does
    // not know which pool the lightest-load comparison picked. A log that
    // attributes an answer to a model reads it from this and not from the
    // specifier, which names a class and not whoever answered for it.
    return completion.servedBy(routing.pool().name(), routing.wireModel());
  }

  /**
   * Compatibility success-only ledger. Durable accounting records inside the pool/transport
   * instead, including failed attempts, and must never be double-booked here. A legacy ledger
   * failure does not discard an answer that has already been generated.
   */
  private void record(
      Routing routing, String specifier, Lane lane, TokenUsage usage, String finishReason) {
    if (accounting != InferenceAccounting.NONE) {
      return;
    }
    try {
      ledger.record(
          new LedgerEntry(
              routing.pool().name(), routing.wireModel(), specifier, lane, usage, finishReason));
    } catch (RuntimeException e) {
      log.warn(
          "token ledger rejected an entry for pool '{}' ({} lane); "
              + "the model call itself succeeded and its result stands",
          routing.pool().name(),
          lane.wireName(),
          e);
    }
  }

  /**
   * Closes every pool, because the dispatcher is the only thing that holds them.
   *
   * <p>A loop that keeps going, because one bad pool must not strand every pool declared after it:
   * that would leak an OkHttp connection pool and the threads its dispatcher runs apiece, at
   * shutdown, with nothing left running to report it. Belt-and-braces rather than a live concern —
   * {@link LlmPool#close} is documented not to throw and {@link LlmTransport#close} is required not
   * to — but the contract it rests on is enforced nowhere, and its first real implementation is the
   * OkHttp transport in the next task. Three lines against a shutdown-time leak nothing would
   * report is worth it.
   *
   * <p>Idempotent by inheritance and not by a flag here, on the same terms as {@code
   * LlmPool.close}. What is asserted is not how many times each pool was closed but that
   * <em>each</em> was reached at all: emptying this method left all ten of this task's original
   * tests green, which is what {@code closing_the_dispatcher_closes_every_pool} was added to catch.
   */
  @Override
  public void close() {
    for (LlmPool pool : pools) {
      try {
        pool.close();
      } catch (RuntimeException e) {
        log.warn("pool '{}' failed to close; closing the rest", pool.name(), e);
      }
    }
  }
}
